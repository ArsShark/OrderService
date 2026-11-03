package com.innowise.orderservice.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.innowise.orderservice.entity.Item;
import com.innowise.orderservice.entity.Order;
import com.innowise.orderservice.entity.OrderItem;
import com.innowise.orderservice.entity.OrderStatus;
import com.innowise.orderservice.repository.OrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The operations of the DAO layer on a real database: create with cascade, read, update, soft
 * delete and the protection against a lost update. Every step runs in its own committed
 * transaction; the lines of an order are read inside a transaction because they are lazy.
 */
class OrderRepositoryIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private OrderRepository orderRepository;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @PersistenceContext
  private EntityManager entityManager;

  private TransactionTemplate transaction;

  @BeforeEach
  void createTransactionTemplate() {
    transaction = new TransactionTemplate(transactionManager);
  }

  @Test
  void saveAndFlushStoresTheOrderWithItsLinesByCascade() {
    long keyboard = insertItem("Keyboard", "49.90");
    long mouse = insertItem("Mouse", "19.90");

    Order saved = transaction.execute(status ->
      orderRepository.saveAndFlush(newOrder(7L, keyboard, mouse)));

    List<String> names = transaction.execute(status -> orderRepository.findById(saved.getId())
      .orElseThrow().getItems().stream().map(line -> line.getItem().getName()).toList());
    assertAll(
      () -> assertNotNull(saved.getId()),
      () -> assertEquals(0L, saved.getVersion()),
      () -> assertEquals(START, saved.getCreatedAt()),
      () -> assertEquals(START, saved.getUpdatedAt()),
      () -> assertEquals(2, countRows("order_items")),
      // The lines come back in the order of their ids.
      () -> assertEquals(List.of("Keyboard", "Mouse"), names),
      () -> assertEquals(new BigDecimal("10.00"), jdbcTemplate.queryForObject(
        "SELECT unit_price FROM order_items ORDER BY id LIMIT 1", BigDecimal.class)));
  }

  @Test
  void findByIdFindsNothingForAnUnknownId() {
    assertTrue(orderRepository.findById(999L).isEmpty());
  }

  @Test
  void anUpdateChangesTheOrderAndItsLinesAndRefreshesUpdatedAt() {
    long keyboard = insertItem("Keyboard", "49.90");
    long mouse = insertItem("Mouse", "19.90");
    long monitor = insertItem("Monitor", "99.00");
    long orderId = transaction.execute(status ->
      orderRepository.saveAndFlush(newOrder(7L, keyboard, mouse)).getId());
    clock.advance(Duration.ofHours(1));

    transaction.executeWithoutResult(status -> {
      Order order = orderRepository.findById(orderId).orElseThrow();
      order.setStatus(OrderStatus.PAID);
      order.setTotalPrice(new BigDecimal("30.00"));
      // Replace the keyboard by the monitor. Hibernate runs inserts before deletes, so a line
      // of the SAME item must not be removed and added again in one flush (UNIQUE).
      order.removeItem(order.getItems().get(0));
      order.addItem(newLine(monitor));
      orderRepository.saveAndFlush(order);
    });

    Instant[] times = transaction.execute(status -> {
      Order order = orderRepository.findById(orderId).orElseThrow();
      return new Instant[] {order.getCreatedAt(), order.getUpdatedAt()};
    });
    assertAll(
      () -> assertEquals("PAID", jdbcTemplate.queryForObject(
        "SELECT status FROM orders WHERE id = ?", String.class, orderId)),
      () -> assertEquals(new BigDecimal("30.00"), jdbcTemplate.queryForObject(
        "SELECT total_price FROM orders WHERE id = ?", BigDecimal.class, orderId)),
      () -> assertEquals(List.of("Mouse", "Monitor"), jdbcTemplate.queryForList("""
        SELECT i.name FROM order_items oi JOIN items i ON i.id = oi.item_id
        WHERE oi.order_id = ? ORDER BY oi.id
        """, String.class, orderId)),
      () -> assertEquals(3, countRows("items")),
      () -> assertEquals(1L, jdbcTemplate.queryForObject(
        "SELECT version FROM orders WHERE id = ?", Long.class, orderId)),
      () -> assertEquals(START, times[0]),
      () -> assertEquals(START.plus(Duration.ofHours(1)), times[1]));
  }

  @Test
  void aDeletedOrderDisappearsFromEveryQueryButItsRowsStay() {
    long keyboard = insertItem("Keyboard", "49.90");
    long orderId = transaction.execute(status ->
      orderRepository.saveAndFlush(newOrder(7L, keyboard)).getId());
    clock.advance(Duration.ofHours(1));

    transaction.executeWithoutResult(status -> {
      Order order = orderRepository.findById(orderId).orElseThrow();
      order.markDeleted();
      orderRepository.saveAndFlush(order);
    });

    Specification<Order> everything = Specification.unrestricted();
    PageRequest firstPage = PageRequest.of(0, 10);
    assertAll(
      () -> assertTrue(orderRepository.findById(orderId).isEmpty()),
      () -> assertEquals(0L, orderRepository.findAll(everything, firstPage).getTotalElements()),
      () -> assertEquals(0L, orderRepository.findAllByUserId(7L, firstPage).getTotalElements()),
      // The rows are still there, and the change went through auditing.
      () -> assertEquals(1, countRows("orders")),
      () -> assertEquals(1, countRows("order_items")),
      () -> assertEquals(true, jdbcTemplate.queryForObject(
        "SELECT deleted FROM orders WHERE id = ?", Boolean.class, orderId)),
      () -> assertEquals(true, jdbcTemplate.queryForObject(
        "SELECT updated_at > created_at FROM orders WHERE id = ?", Boolean.class, orderId)));
  }

  @Test
  void findAllByUserIdReturnsOnlyTheOrdersOfThatUserOnePageAtATime() {
    // Ids 1 to 5: users 7, 8, 7, 8, 7.
    for (long userId : new long[] {7, 8, 7, 8, 7}) {
      orderRepository.saveAndFlush(newOrder(userId));
    }

    Page<Order> first = orderRepository.findAllByUserId(
      7L, PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "id")));
    Page<Order> second = orderRepository.findAllByUserId(
      7L, PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "id")));

    assertAll(
      () -> assertEquals(3L, first.getTotalElements()),
      () -> assertEquals(2, first.getTotalPages()),
      () -> assertEquals(List.of(5L, 3L), first.getContent().stream().map(Order::getId).toList()),
      () -> assertEquals(List.of(1L), second.getContent().stream().map(Order::getId).toList()));
  }

  @Test
  void anUpdateOfAStaleOrderIsRefusedInsteadOfOverwritingTheOtherChange() {
    long orderId = orderRepository.saveAndFlush(newOrder(7L)).getId();
    // Somebody reads the order, ...
    Order stale = transaction.execute(status -> orderRepository.findById(orderId).orElseThrow());
    // ... somebody else changes and commits it, ...
    transaction.executeWithoutResult(status ->
      orderRepository.findById(orderId).orElseThrow().setStatus(OrderStatus.PAID));
    // ... and the first one tries to save a change that is based on the old state.
    stale.setStatus(OrderStatus.CANCELLED);

    assertAll(
      () -> assertThrows(OptimisticLockingFailureException.class,
        () -> orderRepository.saveAndFlush(stale)),
      () -> assertEquals("PAID", jdbcTemplate.queryForObject(
        "SELECT status FROM orders WHERE id = ?", String.class, orderId)),
      () -> assertEquals(1L, jdbcTemplate.queryForObject(
        "SELECT version FROM orders WHERE id = ?", Long.class, orderId)));
  }

  /** A new order without an id, with one line of each given item. Call inside a transaction. */
  private Order newOrder(long userId, long... itemIds) {
    Order order = new Order();
    order.setUserId(userId);
    order.setUserEmail(OWNER_EMAIL);
    order.setTotalPrice(new BigDecimal("69.80"));
    for (long itemId : itemIds) {
      order.addItem(newLine(itemId));
    }
    return order;
  }

  private OrderItem newLine(long itemId) {
    OrderItem line = new OrderItem();
    line.setItem(entityManager.getReference(Item.class, itemId));
    line.setQuantity(1);
    line.setUnitPrice(new BigDecimal("10.00"));
    return line;
  }
}
