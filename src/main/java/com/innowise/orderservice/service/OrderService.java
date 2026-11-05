package com.innowise.orderservice.service;

import com.innowise.orderservice.dto.request.OrderItemRequest;
import com.innowise.orderservice.dto.request.OrderRequest;
import com.innowise.orderservice.dto.response.OrderResponse;
import com.innowise.orderservice.entity.Item;
import com.innowise.orderservice.entity.Order;
import com.innowise.orderservice.entity.OrderItem;
import com.innowise.orderservice.entity.OrderStatus;
import com.innowise.orderservice.exception.ItemNotAvailableException;
import com.innowise.orderservice.exception.OrderNotDeletableException;
import com.innowise.orderservice.exception.OrderNotEditableException;
import com.innowise.orderservice.exception.OrderNotFoundException;
import com.innowise.orderservice.mapper.OrderMapper;
import com.innowise.orderservice.repository.ItemRepository;
import com.innowise.orderservice.repository.OrderRepository;
import com.innowise.orderservice.repository.specification.OrderSpecifications;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic of orders: only the database, no call to another service. The user data is added
 * above this class, after the transaction has ended, so an HTTP call never holds a connection.
 *
 * <p>The class is read-only by default; every method that writes opts in with
 * {@link Transactional}. Every write loads the order with a row lock
 * ({@link OrderRepository#findByIdForUpdate}). Who may see or change an order is decided here from
 * the {@link Caller}: an order of somebody else is the same {@link OrderNotFoundException} as a
 * missing one.
 */
@Service
@Transactional(readOnly = true)
public class OrderService {

  private static final Logger LOGGER = LoggerFactory.getLogger(OrderService.class);

  private final OrderRepository orderRepository;
  private final ItemRepository itemRepository;
  private final OrderMapper orderMapper;
  private final PriceCalculator priceCalculator;

  public OrderService(OrderRepository orderRepository, ItemRepository itemRepository,
    OrderMapper orderMapper, PriceCalculator priceCalculator) {
    this.orderRepository = orderRepository;
    this.itemRepository = itemRepository;
    this.orderMapper = orderMapper;
    this.priceCalculator = priceCalculator;
  }

  /**
   * Creates a PENDING order of the caller. The prices are copied from the catalog and the total
   * is calculated here.
   *
   * @throws ItemNotAvailableException if an item does not exist or is deleted
   * @throws com.innowise.orderservice.exception.OrderTotalTooLargeException if the total does
   *   not fit into the column
   */
  @Transactional
  public OrderResponse create(Caller caller, OrderRequest request) {
    Objects.requireNonNull(caller.userId(), "an order needs the id of its owner");
    Objects.requireNonNull(caller.email(), "an order needs the email of its owner");
    Map<Long, Item> items = loadItems(request.items());

    Order order = new Order();
    order.setUserId(caller.userId());
    order.setUserEmail(caller.email());
    for (OrderItemRequest line : request.items()) {
      Item item = items.get(line.itemId());
      order.addItem(orderMapper.toLine(line, item, item.getPrice()));
    }
    order.setTotalPrice(priceCalculator.total(order.getItems()));

    Order saved = orderRepository.saveAndFlush(order);
    LOGGER.info("Created order with id {} of user {}", saved.getId(), saved.getUserId());
    return orderMapper.toResponse(saved);
  }

  /**
   * Returns an order of the caller (an admin: any order).
   *
   * @throws OrderNotFoundException if there is no such order or it belongs to somebody else
   */
  public OrderResponse getById(Long id, Caller caller) {
    Order order = orderRepository.findById(id)
      .filter(caller::canAccess)
      .orElseThrow(() -> new OrderNotFoundException(id));
    return orderMapper.toResponse(order);
  }

  /** Returns a page of the orders of every user that match the filter. For admins. */
  public Page<OrderResponse> findAll(OrderFilter filter, Pageable pageable) {
    return orderRepository.findAll(
        OrderSpecifications.matches(filter.createdFrom(), filter.createdBefore(),
          filter.statuses(), filter.userId()), pageable)
      .map(orderMapper::toResponse);
  }

  /** Returns a page of the orders of one user that match the filter; its owner is forced. */
  public Page<OrderResponse> findOwn(Long userId, OrderFilter filter, Pageable pageable) {
    return findAll(filter.withUserId(userId), pageable);
  }

  /**
   * Replaces the lines of an order that is still PENDING or PAYMENT_FAILED. The prices are copied
   * from the catalog again and the total is recalculated.
   *
   * <p>A line of an item that stays is updated in place (quantity and price); only lines of
   * other items are removed and added. Hibernate runs inserts before deletes, so deleting and
   * adding the line of the same item in one flush would break the UNIQUE constraint on
   * {@code (order_id, item_id)}.
   *
   * @throws OrderNotFoundException if there is no such order or it belongs to somebody else
   * @throws OrderNotEditableException if the order is PAID or CANCELLED
   * @throws ItemNotAvailableException if an item does not exist or is deleted
   */
  @Transactional
  public OrderResponse update(Long id, Caller caller, OrderRequest request) {
    Order order = findForUpdate(id, caller);
    if (!order.isEditable()) {
      throw new OrderNotEditableException(id, order.getStatus());
    }
    Map<Long, Item> items = loadItems(request.items());
    replaceLines(order, request.items(), items);
    order.setTotalPrice(priceCalculator.total(order.getItems()));

    Order saved = orderRepository.saveAndFlush(order);
    LOGGER.info("Updated order with id {}", id);
    return orderMapper.toResponse(saved);
  }

  /**
   * Cancels an order. A cancelled order stays cancelled, so a repeated call is harmless.
   *
   * @throws OrderNotFoundException if there is no such order or it belongs to somebody else
   * @throws com.innowise.orderservice.exception.OrderStatusTransitionException if the order is
   *   PAID
   */
  @Transactional
  public OrderResponse cancel(Long id, Caller caller) {
    Order order = findForUpdate(id, caller);
    order.cancel();
    Order saved = orderRepository.saveAndFlush(order);
    LOGGER.info("Cancelled order with id {}", id);
    return orderMapper.toResponse(saved);
  }

  /**
   * Deletes an order softly. The owner may delete an order that is not PAID, an admin any order.
   *
   * @throws OrderNotFoundException if there is no such order or it belongs to somebody else
   * @throws OrderNotDeletableException if the owner deletes a PAID order
   */
  @Transactional
  public void delete(Long id, Caller caller) {
    Order order = findForUpdate(id, caller);
    if (!caller.admin() && order.getStatus() == OrderStatus.PAID) {
      throw new OrderNotDeletableException(id, order.getStatus());
    }
    order.markDeleted();
    orderRepository.saveAndFlush(order);
    LOGGER.info("Deleted order with id {}", id);
  }

  private Order findForUpdate(Long id, Caller caller) {
    return orderRepository.findByIdForUpdate(id)
      .filter(caller::canAccess)
      .orElseThrow(() -> new OrderNotFoundException(id));
  }

  /** Loads every requested item with one query; the first one that is missing is reported. */
  private Map<Long, Item> loadItems(List<OrderItemRequest> lines) {
    Set<Long> ids = lines.stream()
      .map(OrderItemRequest::itemId)
      .collect(Collectors.toCollection(LinkedHashSet::new));
    Map<Long, Item> found = itemRepository.findAllByIdInAndDeletedFalse(ids).stream()
      .collect(Collectors.toMap(Item::getId, Function.identity()));
    for (Long id : ids) {
      if (!found.containsKey(id)) {
        throw new ItemNotAvailableException(id);
      }
    }
    return found;
  }

  private void replaceLines(Order order, List<OrderItemRequest> requested, Map<Long, Item> items) {
    Set<Long> wanted = new HashSet<>(items.keySet());
    Map<Long, OrderItem> kept = new HashMap<>();
    for (OrderItem line : List.copyOf(order.getItems())) {
      Long itemId = line.getItem().getId();
      if (wanted.contains(itemId)) {
        kept.put(itemId, line);
      } else {
        order.removeItem(line);
      }
    }
    for (OrderItemRequest request : requested) {
      Item item = items.get(request.itemId());
      OrderItem line = kept.get(request.itemId());
      if (line == null) {
        order.addItem(orderMapper.toLine(request, item, item.getPrice()));
      } else {
        line.setQuantity(request.quantity());
        line.setUnitPrice(item.getPrice());
      }
    }
  }
}
