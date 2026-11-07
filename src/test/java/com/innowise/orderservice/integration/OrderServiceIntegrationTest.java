package com.innowise.orderservice.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.innowise.orderservice.dto.request.ItemRequest;
import com.innowise.orderservice.dto.request.OrderItemRequest;
import com.innowise.orderservice.dto.request.OrderRequest;
import com.innowise.orderservice.dto.response.OrderResponse;
import com.innowise.orderservice.entity.Order;
import com.innowise.orderservice.entity.OrderStatus;
import com.innowise.orderservice.exception.ItemNotAvailableException;
import com.innowise.orderservice.exception.OrderNotDeletableException;
import com.innowise.orderservice.exception.OrderNotEditableException;
import com.innowise.orderservice.exception.OrderNotFoundException;
import com.innowise.orderservice.exception.OrderStatusTransitionException;
import com.innowise.orderservice.repository.OrderRepository;
import com.innowise.orderservice.service.Caller;
import com.innowise.orderservice.service.ItemService;
import com.innowise.orderservice.service.OrderFilter;
import com.innowise.orderservice.service.OrderService;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The order service on a real database through the real transactional proxy: prices, the
 * replacement of lines, ownership, the status rules, the soft delete, the row lock and the number
 * of statements. Nothing is mocked and no test is {@code @Transactional}, so every call commits
 * like in production.
 */
class OrderServiceIntegrationTest extends AbstractIntegrationTest {

  private static final Caller OWNER = new Caller(7L, OWNER_EMAIL, false);
  private static final Caller STRANGER = new Caller(8L, "other@example.com", false);
  private static final Caller ADMIN = new Caller(1L, "admin@example.com", true);
  private static final OrderFilter ANY = new OrderFilter(null, null, null, null);

  @Autowired
  private OrderService orderService;

  @Autowired
  private ItemService itemService;

  @Autowired
  private OrderRepository orderRepository;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @Autowired
  private EntityManagerFactory entityManagerFactory;

  // ---- prices

  @Test
  void createStoresTheOrderWithItsLinesThePricesOfTheMomentAndTheTotal() {
    long keyboard = insertItem("Keyboard", "49.90");
    long mouse = insertItem("Mouse", "19.90");

    OrderResponse created = orderService.create(OWNER, request(line(keyboard, 2), line(mouse, 1)));

    assertAll(
      () -> assertEquals(OrderStatus.PENDING, created.status()),
      () -> assertEquals(new BigDecimal("119.70"), created.totalPrice()),
      () -> assertEquals(7L, created.userId()),
      () -> assertEquals(START, created.createdAt()),
      () -> assertEquals(List.of("Keyboard", "Mouse"),
        created.items().stream().map(item -> item.itemName()).toList()),
      () -> assertEquals(1L, countRows("orders")),
      () -> assertEquals(2L, countRows("order_items")),
      () -> assertEquals(OWNER_EMAIL, jdbcTemplate.queryForObject(
        "SELECT user_email FROM orders WHERE id = ?", String.class, created.id())),
      () -> assertEquals(List.of(new BigDecimal("49.90"), new BigDecimal("19.90")),
        jdbcTemplate.queryForList(
          "SELECT unit_price FROM order_items ORDER BY item_id", BigDecimal.class)));
  }

  @Test
  void aLaterPriceChangeLeavesTheOrderAloneUntilItsLinesAreReplaced() {
    long keyboard = insertItem("Keyboard", "49.90");
    long mouse = insertItem("Mouse", "19.90");
    OrderRequest request = request(line(keyboard, 2), line(mouse, 1));
    long orderId = orderService.create(OWNER, request).id();

    itemService.update(keyboard, new ItemRequest("Keyboard", new BigDecimal("59.90")));
    OrderResponse unchanged = orderService.getById(orderId, OWNER);
    OrderResponse repriced = orderService.update(orderId, OWNER, request);

    assertAll(
      () -> assertEquals(new BigDecimal("119.70"), unchanged.totalPrice()),
      () -> assertEquals(new BigDecimal("49.90"), unchanged.items().get(0).unitPrice()),
      () -> assertEquals(new BigDecimal("139.70"), repriced.totalPrice()),
      () -> assertEquals(new BigDecimal("59.90"), repriced.items().get(0).unitPrice()));
  }

  @Test
  void anItemThatWasDeletedCannotBeOrdered() {
    long keyboard = insertItem("Keyboard", "49.90");
    itemService.delete(keyboard);

    assertAll(
      () -> assertThrows(ItemNotAvailableException.class,
        () -> orderService.create(OWNER, request(line(keyboard, 1)))),
      () -> assertThrows(ItemNotAvailableException.class,
        () -> orderService.create(OWNER, request(line(999L, 1)))),
      () -> assertEquals(0L, countRows("orders")));
  }

  // ---- replacing lines (the lesson of JACAG-13)

  @Test
  void replacingTheLinesUpdatesTheLineOfAnItemThatStaysInPlace() {
    long keyboard = insertItem("Keyboard", "49.90");
    long mouse = insertItem("Mouse", "19.90");
    long monitor = insertItem("Monitor", "199.00");
    long orderId = orderService.create(OWNER, request(line(keyboard, 1), line(mouse, 2))).id();
    Map<Long, Long> before = lineIdsByItem(orderId);

    // The mouse stays with a new quantity, the keyboard goes, the monitor comes.
    OrderResponse updated = orderService.update(orderId, OWNER,
      request(line(mouse, 3), line(monitor, 1)));

    Map<Long, Long> after = lineIdsByItem(orderId);
    assertAll(
      () -> assertEquals(Set.of(mouse, monitor), after.keySet()),
      () -> assertEquals(before.get(mouse), after.get(mouse), "the line of the mouse is reused"),
      () -> assertEquals(3, jdbcTemplate.queryForObject(
        "SELECT quantity FROM order_items WHERE order_id = ? AND item_id = ?",
        Integer.class, orderId, mouse)),
      () -> assertEquals(new BigDecimal("258.70"), updated.totalPrice()),
      () -> assertEquals(2L, countRows("order_items")));
  }

  @Test
  void replacingTheLinesWithTheSameItemsOnlyChangesQuantities() {
    long keyboard = insertItem("Keyboard", "49.90");
    long mouse = insertItem("Mouse", "19.90");
    long orderId = orderService.create(OWNER, request(line(keyboard, 1), line(mouse, 1))).id();
    Map<Long, Long> before = lineIdsByItem(orderId);

    OrderResponse updated = orderService.update(orderId, OWNER,
      request(line(keyboard, 5), line(mouse, 1)));

    assertAll(
      () -> assertEquals(before, lineIdsByItem(orderId)),
      () -> assertEquals(new BigDecimal("269.40"), updated.totalPrice()));
  }

  // ---- ownership and status rules

  @Test
  void aStrangerSeesAndChangesNothingAndAnAdminSeesEverything() {
    long keyboard = insertItem("Keyboard", "49.90");
    long orderId = orderService.create(OWNER, request(line(keyboard, 1))).id();

    assertAll(
      () -> assertThrows(OrderNotFoundException.class,
        () -> orderService.getById(orderId, STRANGER)),
      () -> assertThrows(OrderNotFoundException.class,
        () -> orderService.update(orderId, STRANGER, request(line(keyboard, 9)))),
      () -> assertThrows(OrderNotFoundException.class,
        () -> orderService.cancel(orderId, STRANGER)),
      () -> assertThrows(OrderNotFoundException.class,
        () -> orderService.delete(orderId, STRANGER)),
      () -> assertEquals(orderId, orderService.getById(orderId, ADMIN).id()),
      () -> assertEquals("PENDING", statusOf(orderId)),
      () -> assertEquals(1, jdbcTemplate.queryForObject(
        "SELECT quantity FROM order_items WHERE order_id = ?", Integer.class, orderId)));
  }

  @Test
  void aCancelledOrderStaysCancelledAndCannotBeEdited() {
    long keyboard = insertItem("Keyboard", "49.90");
    long orderId = orderService.create(OWNER, request(line(keyboard, 1))).id();

    OrderResponse first = orderService.cancel(orderId, OWNER);
    OrderResponse second = orderService.cancel(orderId, OWNER);

    assertAll(
      () -> assertEquals(OrderStatus.CANCELLED, first.status()),
      () -> assertEquals(OrderStatus.CANCELLED, second.status()),
      () -> assertThrows(OrderNotEditableException.class,
        () -> orderService.update(orderId, OWNER, request(line(keyboard, 2)))));
  }

  @Test
  void aPaidOrderCannotBeCancelledOrDeletedByItsOwnerButAnAdminMayDeleteIt() {
    long keyboard = insertItem("Keyboard", "49.90");
    long orderId = orderService.create(OWNER, request(line(keyboard, 1))).id();
    jdbcTemplate.update("UPDATE orders SET status = 'PAID' WHERE id = ?", orderId);

    assertAll(
      () -> assertThrows(OrderStatusTransitionException.class,
        () -> orderService.cancel(orderId, OWNER)),
      () -> assertThrows(OrderNotDeletableException.class,
        () -> orderService.delete(orderId, OWNER)),
      () -> assertThrows(OrderNotEditableException.class,
        () -> orderService.update(orderId, OWNER, request(line(keyboard, 2)))));

    orderService.delete(orderId, ADMIN);

    assertThrows(OrderNotFoundException.class, () -> orderService.getById(orderId, ADMIN));
  }

  @Test
  void aDeletedOrderDisappearsButItsRowsStayAndUpdatedAtMoves() {
    long keyboard = insertItem("Keyboard", "49.90");
    long orderId = orderService.create(OWNER, request(line(keyboard, 1))).id();
    clock.advance(Duration.ofMinutes(5));

    orderService.delete(orderId, OWNER);

    assertAll(
      () -> assertThrows(OrderNotFoundException.class,
        () -> orderService.getById(orderId, OWNER)),
      () -> assertTrue(orderService.findOwn(7L, ANY, PageRequest.of(0, 10)).isEmpty()),
      () -> assertEquals(1L, countRows("orders")),
      () -> assertEquals(1L, countRows("order_items")),
      () -> assertEquals(Boolean.TRUE, jdbcTemplate.queryForObject(
        "SELECT deleted FROM orders WHERE id = ?", Boolean.class, orderId)),
      () -> assertEquals(START.plus(Duration.ofMinutes(5)), jdbcTemplate.queryForObject(
        "SELECT updated_at FROM orders WHERE id = ?", OffsetDateTime.class, orderId)
        .toInstant()));
  }

  // ---- lists

  @Test
  void anOwnersListHoldsOnlyHisOrdersAndAnAdminCanFilterByOwnerAndStatus() {
    long keyboard = insertItem("Keyboard", "49.90");
    long first = orderService.create(OWNER, request(line(keyboard, 1))).id();
    orderService.create(OWNER, request(line(keyboard, 2)));
    orderService.create(STRANGER, request(line(keyboard, 3)));
    orderService.cancel(first, OWNER);
    PageRequest page = PageRequest.of(0, 10, Sort.by("id"));

    assertAll(
      () -> assertEquals(2, orderService.findOwn(7L, ANY, page).getTotalElements()),
      () -> assertEquals(1, orderService.findOwn(7L,
        new OrderFilter(null, null, Set.of(OrderStatus.CANCELLED), null), page)
        .getTotalElements()),
      // Asking for somebody else's orders through the owner's list changes nothing.
      () -> assertEquals(2, orderService.findOwn(7L,
        new OrderFilter(null, null, null, 8L), page).getTotalElements()),
      () -> assertEquals(3, orderService.findAll(ANY, page).getTotalElements()),
      () -> assertEquals(1, orderService.findAll(
        new OrderFilter(null, null, null, 8L), page).getTotalElements()));
  }

  @Test
  void aPageOfOrdersCostsTheSameNumberOfStatementsForOneAndForFiveOrders() {
    createOrdersWithTwoItemsEach(1);
    long one = statementsToReadTheFirstPage();

    resetState();
    createOrdersWithTwoItemsEach(5);
    long five = statementsToReadTheFirstPage();

    assertEquals(one, five, String.format("one order: %d statements, five orders: %d", one, five));
  }

  // ---- the row lock

  /**
   * A payment holds the lock of the row of the order; an update of the lines that starts at the
   * same moment must wait for it and then see the order as PAID. A change of the lines alone does
   * not change the row of the order, so {@code @Version} would not stop it.
   */
  @Test
  void anUpdateWaitsForThePaymentThatHoldsTheLockAndThenSeesThePaidOrder() throws Exception {
    long keyboard = insertItem("Keyboard", "49.90");
    long orderId = orderService.create(OWNER, request(line(keyboard, 1))).id();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    AtomicReference<Future<OrderResponse>> update = new AtomicReference<>();
    try {
      new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
        Order locked = orderRepository.findByIdForUpdate(orderId).orElseThrow();
        Future<OrderResponse> started = executor.submit(
          () -> orderService.update(orderId, OWNER, request(line(keyboard, 5))));
        update.set(started);
        assertThrows(TimeoutException.class, () -> started.get(500, TimeUnit.MILLISECONDS),
          "the update must wait for the row lock");
        locked.markPaid();
      });

      ExecutionException failure = assertThrows(ExecutionException.class,
        () -> update.get().get(10, TimeUnit.SECONDS));

      assertAll(
        () -> assertInstanceOf(OrderNotEditableException.class, failure.getCause()),
        () -> assertEquals("PAID", statusOf(orderId)),
        () -> assertEquals(1, jdbcTemplate.queryForObject(
          "SELECT quantity FROM order_items WHERE order_id = ?", Integer.class, orderId)));
    } finally {
      executor.shutdownNow();
    }
  }

  // ---- helpers

  private static OrderItemRequest line(long itemId, int quantity) {
    return new OrderItemRequest(itemId, quantity);
  }

  private static OrderRequest request(OrderItemRequest... lines) {
    return new OrderRequest(List.of(lines));
  }

  private String statusOf(long orderId) {
    return jdbcTemplate.queryForObject(
      "SELECT status FROM orders WHERE id = ?", String.class, orderId);
  }

  /** The ids of the lines of an order, by item id. */
  private Map<Long, Long> lineIdsByItem(long orderId) {
    return jdbcTemplate.query("SELECT item_id, id FROM order_items WHERE order_id = ?",
      rows -> {
        Map<Long, Long> ids = new HashMap<>();
        while (rows.next()) {
          ids.put(rows.getLong("item_id"), rows.getLong("id"));
        }
        return ids;
      }, orderId);
  }

  private void createOrdersWithTwoItemsEach(int count) {
    for (int i = 0; i < count; i++) {
      long first = insertItem(String.format("First %d", i), "10.00");
      long second = insertItem(String.format("Second %d", i), "20.00");
      orderService.create(OWNER, request(line(first, 1), line(second, 2)));
    }
  }

  private long statementsToReadTheFirstPage() {
    Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.clear();
    Page<OrderResponse> page = orderService.findAll(ANY, PageRequest.of(0, 20, Sort.by("id")));
    assertTrue(page.getContent().stream().allMatch(order -> order.items().size() == 2));
    return statistics.getPrepareStatementCount();
  }
}
