package com.innowise.orderservice.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.innowise.orderservice.TestEntities;
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
import com.innowise.orderservice.exception.OrderStatusTransitionException;
import com.innowise.orderservice.exception.OrderTotalTooLargeException;
import com.innowise.orderservice.mapper.OrderMapperImpl;
import com.innowise.orderservice.repository.ItemRepository;
import com.innowise.orderservice.repository.OrderRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/**
 * Unit tests of {@link OrderService} with plain Mockito: no Spring context, no database. The
 * repositories are mocks; the mapper and the calculator are the real classes (they have their own
 * tests). Strict stubs: a stubbing that the service never uses fails the test, so a write that
 * forgot the row lock ({@code findByIdForUpdate}) is caught. The transactions and the locks
 * themselves are proved by the integration tests.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

  private static final Long ORDER_ID = 5L;
  private static final Caller OWNER = new Caller(7L, "buyer@example.com", false);
  private static final Caller STRANGER = new Caller(8L, "other@example.com", false);
  private static final Caller ADMIN = new Caller(1L, "admin@example.com", true);

  private static final Item KEYBOARD = TestEntities.item(1L, "Keyboard", "49.90");
  private static final Item MOUSE = TestEntities.item(2L, "Mouse", "19.90");
  private static final Item MONITOR = TestEntities.item(3L, "Monitor", "199.00");

  @Mock
  private OrderRepository orderRepository;

  @Mock
  private ItemRepository itemRepository;

  private OrderService orderService;

  @BeforeEach
  void createService() {
    orderService = new OrderService(orderRepository, itemRepository, new OrderMapperImpl(),
      new PriceCalculator());
  }

  // ---- create

  @Test
  void createCopiesThePricesCalculatesTheTotalAndTakesTheOwnerFromTheCaller() {
    when(itemRepository.findAllByIdInAndDeletedFalse(any()))
      .thenReturn(List.of(KEYBOARD, MOUSE));
    when(orderRepository.saveAndFlush(any())).thenAnswer(invocation -> {
      Order saved = invocation.getArgument(0);
      TestEntities.stamp(saved, 100L);
      return saved;
    });

    OrderResponse response = orderService.create(OWNER, new OrderRequest(
      List.of(new OrderItemRequest(1L, 2), new OrderItemRequest(2L, 1))));

    ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
    verify(orderRepository).saveAndFlush(captor.capture());
    Order stored = captor.getValue();
    assertAll(
      () -> assertEquals(100L, response.id()),
      () -> assertEquals(7L, stored.getUserId()),
      () -> assertEquals("buyer@example.com", stored.getUserEmail()),
      () -> assertEquals(OrderStatus.PENDING, stored.getStatus()),
      () -> assertEquals(new BigDecimal("119.70"), stored.getTotalPrice()),
      () -> assertEquals(List.of(new BigDecimal("49.90"), new BigDecimal("19.90")),
        stored.getItems().stream().map(OrderItem::getUnitPrice).toList()),
      () -> assertEquals(List.of(2, 1),
        stored.getItems().stream().map(OrderItem::getQuantity).toList()),
      () -> assertEquals(new BigDecimal("119.70"), response.totalPrice()));
  }

  @Test
  void createReportsTheFirstItemThatIsNotAvailableAndSavesNothing() {
    when(itemRepository.findAllByIdInAndDeletedFalse(any())).thenReturn(List.of(KEYBOARD));

    ItemNotAvailableException thrown = assertThrows(ItemNotAvailableException.class,
      () -> orderService.create(OWNER, new OrderRequest(
        List.of(new OrderItemRequest(1L, 1), new OrderItemRequest(2L, 1)))));

    assertAll(
      () -> assertEquals("Item with id 2 is not available", thrown.getMessage()),
      () -> verify(orderRepository, never()).saveAndFlush(any()));
  }

  @Test
  void createRefusesATotalThatDoesNotFitIntoTheColumn() {
    Item gold = TestEntities.item(9L, "Gold", "9999999999.00");
    when(itemRepository.findAllByIdInAndDeletedFalse(any())).thenReturn(List.of(gold));

    assertAll(
      () -> assertThrows(OrderTotalTooLargeException.class, () -> orderService.create(OWNER,
        new OrderRequest(List.of(new OrderItemRequest(9L, 2))))),
      () -> verify(orderRepository, never()).saveAndFlush(any()));
  }

  @Test
  void createNeedsAnOwnerWithAnIdAndAnEmail() {
    OrderRequest request = new OrderRequest(List.of(new OrderItemRequest(1L, 1)));

    assertAll(
      () -> assertThrows(NullPointerException.class,
        () -> orderService.create(new Caller(null, "admin@example.com", true), request)),
      () -> assertThrows(NullPointerException.class,
        () -> orderService.create(new Caller(7L, null, false), request)),
      () -> verifyNoInteractions(orderRepository, itemRepository));
  }

  // ---- read

  @Test
  void anOwnerAndAnAdminSeeTheOrderAndAStrangerDoesNot() {
    Order order = TestEntities.order(ORDER_ID, 7L, "buyer@example.com",
      TestEntities.line(KEYBOARD, 1, "49.90"));
    when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

    assertAll(
      () -> assertEquals(ORDER_ID, orderService.getById(ORDER_ID, OWNER).id()),
      () -> assertEquals(ORDER_ID, orderService.getById(ORDER_ID, ADMIN).id()),
      () -> assertEquals("Order with id 5 not found", assertThrows(OrderNotFoundException.class,
        () -> orderService.getById(ORDER_ID, STRANGER)).getMessage()));
  }

  @Test
  void aMissingOrderLooksLikeAnOrderOfSomebodyElse() {
    when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

    assertEquals("Order with id 5 not found", assertThrows(OrderNotFoundException.class,
      () -> orderService.getById(ORDER_ID, OWNER)).getMessage());
  }

  @Test
  void findAllMapsEveryOrderAndKeepsThePageMetadata() {
    Pageable pageable = PageRequest.of(0, 2);
    Order first = TestEntities.order(1L, 7L, "buyer@example.com");
    Order second = TestEntities.order(2L, 8L, "other@example.com");
    when(orderRepository.findAll(ArgumentMatchers.<Specification<Order>>any(), eq(pageable)))
      .thenReturn(new PageImpl<>(List.of(first, second), pageable, 5));

    Page<OrderResponse> page = orderService.findAll(
      new OrderFilter(null, null, Set.of(OrderStatus.PENDING), null), pageable);

    assertAll(
      () -> assertEquals(List.of(1L, 2L),
        page.getContent().stream().map(OrderResponse::id).toList()),
      () -> assertEquals(5, page.getTotalElements()));
  }

  @Test
  void findOwnAsksTheSameQueryAsFindAll() {
    Pageable pageable = PageRequest.of(0, 20);
    Order mine = TestEntities.order(1L, 7L, "buyer@example.com");
    when(orderRepository.findAll(ArgumentMatchers.<Specification<Order>>any(), eq(pageable)))
      .thenReturn(new PageImpl<>(List.of(mine), pageable, 1));

    Page<OrderResponse> page = orderService.findOwn(7L, new OrderFilter(null, null, null, 8L),
      pageable);

    assertEquals(List.of(1L), page.getContent().stream().map(OrderResponse::id).toList());
  }

  @Test
  void findOwnRefusesACallWithoutAnOwnerInsteadOfListingEveryOrder() {
    Pageable pageable = PageRequest.of(0, 20);
    OrderFilter filter = new OrderFilter(null, null, null, null);

    assertAll(
      () -> assertThrows(NullPointerException.class,
        () -> orderService.findOwn(null, filter, pageable)),
      () -> verifyNoInteractions(orderRepository));
  }

  @Test
  void theFilterOfAnOwnerIsRestrictedToThatOwnerWhateverItSaid() {
    OrderFilter asked = new OrderFilter(null, null, Set.of(OrderStatus.PAID), 8L);

    OrderFilter forced = asked.withUserId(7L);

    assertAll(
      () -> assertEquals(7L, forced.userId()),
      () -> assertEquals(Set.of(OrderStatus.PAID), forced.statuses()));
  }

  // ---- update

  @Test
  void updateKeepsTheLineOfAnItemThatStaysAndReplacesTheOthers() {
    OrderItem keyboardLine = TestEntities.line(KEYBOARD, 1, "40.00");
    OrderItem mouseLine = TestEntities.line(MOUSE, 2, "19.90");
    Order order = TestEntities.order(ORDER_ID, 7L, "buyer@example.com", keyboardLine, mouseLine);
    when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
    when(itemRepository.findAllByIdInAndDeletedFalse(any()))
      .thenReturn(List.of(KEYBOARD, MONITOR));
    when(orderRepository.saveAndFlush(order)).thenReturn(order);

    OrderResponse response = orderService.update(ORDER_ID, OWNER, new OrderRequest(
      List.of(new OrderItemRequest(1L, 5), new OrderItemRequest(3L, 1))));

    assertAll(
      // The same instance: the row is updated, not deleted and inserted again.
      () -> assertSame(keyboardLine, order.getItems().get(0)),
      () -> assertEquals(5, keyboardLine.getQuantity()),
      () -> assertEquals(new BigDecimal("49.90"), keyboardLine.getUnitPrice()),
      () -> assertFalse(order.getItems().contains(mouseLine)),
      () -> assertEquals(List.of(1L, 3L),
        order.getItems().stream().map(line -> line.getItem().getId()).toList()),
      () -> assertEquals(new BigDecimal("448.50"), order.getTotalPrice()),
      () -> assertEquals(new BigDecimal("448.50"), response.totalPrice()));
  }

  @Test
  void updateRefusesAPaidOrderAndAClosedOneWithoutTouchingTheCatalog() {
    Order paid = TestEntities.order(ORDER_ID, 7L, "buyer@example.com");
    paid.markPaid();
    when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(paid));

    OrderNotEditableException thrown = assertThrows(OrderNotEditableException.class,
      () -> orderService.update(ORDER_ID, OWNER,
        new OrderRequest(List.of(new OrderItemRequest(1L, 1)))));

    assertAll(
      () -> assertEquals("Order with id 5 is PAID and cannot be changed", thrown.getMessage()),
      () -> verifyNoInteractions(itemRepository),
      () -> verify(orderRepository, never()).saveAndFlush(any()));
  }

  @Test
  void updateOfSomebodyElsesOrderIsNotFound() {
    Order order = TestEntities.order(ORDER_ID, 7L, "buyer@example.com");
    when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));

    assertAll(
      () -> assertThrows(OrderNotFoundException.class, () -> orderService.update(ORDER_ID,
        STRANGER, new OrderRequest(List.of(new OrderItemRequest(1L, 1))))),
      () -> verifyNoInteractions(itemRepository));
  }

  // ---- cancel

  @Test
  void anOwnerAndAnAdminCancelAPendingOrder() {
    Order mine = TestEntities.order(ORDER_ID, 7L, "buyer@example.com");
    Order theirs = TestEntities.order(6L, 7L, "buyer@example.com");
    when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(mine));
    when(orderRepository.findByIdForUpdate(6L)).thenReturn(Optional.of(theirs));
    when(orderRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    OrderResponse byOwner = orderService.cancel(ORDER_ID, OWNER);
    OrderResponse byAdmin = orderService.cancel(6L, ADMIN);

    assertAll(
      () -> assertEquals(OrderStatus.CANCELLED, byOwner.status()),
      () -> assertEquals(OrderStatus.CANCELLED, byAdmin.status()));
  }

  @Test
  void aPaidOrderCannotBeCancelledAndIsNotSaved() {
    Order paid = TestEntities.order(ORDER_ID, 7L, "buyer@example.com");
    paid.markPaid();
    when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(paid));

    assertAll(
      () -> assertThrows(OrderStatusTransitionException.class,
        () -> orderService.cancel(ORDER_ID, OWNER)),
      () -> assertEquals(OrderStatus.PAID, paid.getStatus()),
      () -> verify(orderRepository, never()).saveAndFlush(any()));
  }

  @Test
  void cancellingSomebodyElsesOrderIsNotFound() {
    Order order = TestEntities.order(ORDER_ID, 7L, "buyer@example.com");
    when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));

    assertAll(
      () -> assertThrows(OrderNotFoundException.class,
        () -> orderService.cancel(ORDER_ID, STRANGER)),
      () -> assertEquals(OrderStatus.PENDING, order.getStatus()));
  }

  // ---- delete

  @Test
  void anOwnerDeletesAnOrderThatIsNotPaidAndAnAdminDeletesAny() {
    Order pending = TestEntities.order(ORDER_ID, 7L, "buyer@example.com");
    Order paid = TestEntities.order(6L, 7L, "buyer@example.com");
    paid.markPaid();
    when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(pending));
    when(orderRepository.findByIdForUpdate(6L)).thenReturn(Optional.of(paid));

    orderService.delete(ORDER_ID, OWNER);
    orderService.delete(6L, ADMIN);

    assertAll(
      () -> assertTrue(pending.isDeleted()),
      () -> assertTrue(paid.isDeleted()),
      () -> verify(orderRepository).saveAndFlush(pending),
      () -> verify(orderRepository).saveAndFlush(paid));
  }

  @Test
  void anOwnerCannotDeleteAPaidOrder() {
    Order paid = TestEntities.order(ORDER_ID, 7L, "buyer@example.com");
    paid.markPaid();
    when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(paid));

    OrderNotDeletableException thrown = assertThrows(OrderNotDeletableException.class,
      () -> orderService.delete(ORDER_ID, OWNER));

    assertAll(
      () -> assertEquals("Order with id 5 is PAID and cannot be deleted", thrown.getMessage()),
      () -> assertFalse(paid.isDeleted()),
      () -> verify(orderRepository, never()).saveAndFlush(any()));
  }

  @Test
  void deletingSomebodyElsesOrderIsNotFound() {
    Order order = TestEntities.order(ORDER_ID, 7L, "buyer@example.com");
    when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));

    assertAll(
      () -> assertThrows(OrderNotFoundException.class,
        () -> orderService.delete(ORDER_ID, STRANGER)),
      () -> assertFalse(order.isDeleted()));
  }
}
