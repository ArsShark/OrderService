package com.innowise.orderservice.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.innowise.orderservice.entity.Item;
import com.innowise.orderservice.entity.Order;
import com.innowise.orderservice.entity.OrderItem;
import com.innowise.orderservice.entity.OrderStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves that the entities match the schema (Hibernate also validates it at startup) and that
 * JPA Auditing, the cascade, the orphan removal and the soft delete restriction do what the
 * design says. The tests are not transactional: every step runs in its own transaction, so
 * everything is really committed.
 */
class EntityMappingIntegrationTest extends AbstractIntegrationTest {

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
  void anOrderIsSavedTogetherWithItsLinesAndTheAuditColumnsComeFromTheClock() {
    long keyboard = saveItem("Keyboard", "49.90");
    long mouse = saveItem("Mouse", "19.90");

    long orderId = saveOrder(keyboard, mouse);

    List<String> names = transaction.execute(status -> {
      Order order = entityManager.find(Order.class, orderId);
      return order.getItems().stream().map(line -> line.getItem().getName()).toList();
    });
    Instant[] times = transaction.execute(status -> {
      Order order = entityManager.find(Order.class, orderId);
      return new Instant[] {order.getCreatedAt(), order.getUpdatedAt()};
    });
    assertAll(
      () -> assertEquals(2, countRows("order_items")),
      // The lines come back in the order of their ids (@OrderBy).
      () -> assertEquals(List.of("Keyboard", "Mouse"), names),
      () -> assertEquals(START, times[0]),
      () -> assertEquals(START, times[1]));
  }

  @Test
  void updatedAtMovesWhenAnOrderChangesAndCreatedAtStays() {
    long orderId = saveOrder(saveItem("Keyboard", "49.90"));
    clock.advance(Duration.ofHours(1));

    transaction.executeWithoutResult(status ->
      entityManager.find(Order.class, orderId).setStatus(OrderStatus.PAID));

    Instant[] times = transaction.execute(status -> {
      Order order = entityManager.find(Order.class, orderId);
      return new Instant[] {order.getCreatedAt(), order.getUpdatedAt()};
    });
    assertAll(
      () -> assertEquals(START, times[0]),
      () -> assertEquals(START.plus(Duration.ofHours(1)), times[1]),
      () -> assertEquals("PAID", jdbcTemplate.queryForObject(
        "SELECT status FROM orders WHERE id = ?", String.class, orderId)));
  }

  @Test
  void aNewOrderIsPendingAndNotDeleted() {
    long orderId = saveOrder(saveItem("Keyboard", "49.90"));

    assertAll(
      () -> assertEquals("PENDING", jdbcTemplate.queryForObject(
        "SELECT status FROM orders WHERE id = ?", String.class, orderId)),
      () -> assertEquals(false, jdbcTemplate.queryForObject(
        "SELECT deleted FROM orders WHERE id = ?", Boolean.class, orderId)));
  }

  @Test
  void removingALineFromTheOrderDeletesItsRow() {
    long keyboard = saveItem("Keyboard", "49.90");
    long mouse = saveItem("Mouse", "19.90");
    long orderId = saveOrder(keyboard, mouse);

    transaction.executeWithoutResult(status -> {
      Order order = entityManager.find(Order.class, orderId);
      order.removeItem(order.getItems().get(0));
    });

    assertAll(
      () -> assertEquals(1, countRows("order_items")),
      // Only the line is removed, never the item.
      () -> assertEquals(2, countRows("items")));
  }

  @Test
  void aSoftDeletedOrderIsInvisibleToFindAndToQueries() {
    long orderId = insertOrder(7, "PENDING", "10.00");
    jdbcTemplate.update("UPDATE orders SET deleted = true WHERE id = ?", orderId);

    Order found = transaction.execute(status -> entityManager.find(Order.class, orderId));
    Long visible = transaction.execute(status -> entityManager
      .createQuery("select count(o) from Order o", Long.class).getSingleResult());

    assertAll(
      () -> assertNull(found),
      () -> assertEquals(0L, visible),
      // The row is still in the table.
      () -> assertEquals(1, countRows("orders")));
  }

  @Test
  void aLineStillSeesItsItemAfterTheItemWasSoftDeleted() {
    long itemId = insertItem("Keyboard", "49.90");
    long orderId = insertOrder(7, "PENDING", "49.90");
    insertOrderItem(orderId, itemId, 1, "49.90");
    jdbcTemplate.update("UPDATE items SET deleted = true WHERE id = ?", itemId);

    String name = transaction.execute(status ->
      entityManager.find(Order.class, orderId).getItems().get(0).getItem().getName());
    boolean deleted = Objects.requireNonNull(transaction.execute(status ->
      entityManager.find(Order.class, orderId).getItems().get(0).getItem().isDeleted()));

    assertAll(
      () -> assertEquals("Keyboard", name),
      () -> assertTrue(deleted));
  }

  /** Persists an item and returns its id. */
  private long saveItem(String name, String price) {
    Long id = transaction.execute(status -> {
      Item item = new Item();
      item.setName(name);
      item.setPrice(new BigDecimal(price));
      entityManager.persist(item);
      return item.getId();
    });
    return Objects.requireNonNull(id);
  }

  /** Persists an order with one line (quantity 1) per given item and returns its id. */
  private long saveOrder(long... itemIds) {
    Long id = transaction.execute(status -> {
      Order order = new Order();
      order.setUserId(7L);
      order.setUserEmail(OWNER_EMAIL);
      order.setTotalPrice(new BigDecimal("100.00"));
      for (long itemId : itemIds) {
        OrderItem line = new OrderItem();
        line.setItem(entityManager.getReference(Item.class, itemId));
        line.setQuantity(1);
        line.setUnitPrice(new BigDecimal("49.90"));
        order.addItem(line);
      }
      entityManager.persist(order);
      return order.getId();
    });
    return Objects.requireNonNull(id);
  }
}
