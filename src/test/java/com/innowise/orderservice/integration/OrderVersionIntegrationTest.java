package com.innowise.orderservice.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.innowise.orderservice.entity.Item;
import com.innowise.orderservice.entity.Order;
import com.innowise.orderservice.entity.OrderItem;
import com.innowise.orderservice.repository.OrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An experiment on a real database: what {@code @Version} of an order notices and what it does
 * not. The services never rely on it, because every write loads the order with a row lock
 * ({@code findByIdForUpdate}); this class shows why the lock is needed and what the version is
 * left to do.
 *
 * <p>The situation is the one of a payment against an edit, without the lock. A transaction reads
 * an order (version 0). Another transaction pays it and commits (version 1). The first one then
 * changes the order and saves it. Both transactions run in the same test: the second one is a
 * nested {@code REQUIRES_NEW} transaction on its own connection.
 *
 * <p>The lines of an order are the inverse side of the association ({@code mappedBy}), so adding
 * a line changes no column of the row {@code orders}, and Hibernate sends no {@code UPDATE} of
 * that row, so no version is compared. The first test pins that this is what happens. If it
 * turns red, the assumption in the Javadoc of
 * {@code OrderRepository#findByIdForUpdate} is wrong, and the text must change with the test.
 */
class OrderVersionIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private OrderRepository orderRepository;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @PersistenceContext
  private EntityManager entityManager;

  private TransactionTemplate transaction;
  private TransactionTemplate separateTransaction;

  @BeforeEach
  void createTransactionTemplates() {
    transaction = new TransactionTemplate(transactionManager);
    separateTransaction = new TransactionTemplate(transactionManager);
    separateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Test
  void aChangeOfTheLinesAloneIsNotNoticedByTheVersionAndSlipsThroughAPayment() {
    long keyboard = insertItem("Keyboard", "49.90");
    long mouse = insertItem("Mouse", "19.90");
    long orderId = transaction.execute(status ->
      orderRepository.saveAndFlush(newOrder(keyboard)).getId());

    transaction.executeWithoutResult(status -> {
      Order mine = orderRepository.findById(orderId).orElseThrow();
      // Load the lines now, so that this transaction has a picture of the order.
      mine.getItems().size();
      separateTransaction.executeWithoutResult(inner ->
        orderRepository.findById(orderId).orElseThrow().markPaid());
      mine.addItem(newLine(mouse));
      orderRepository.saveAndFlush(mine);
    });

    assertAll(
      () -> assertEquals("PAID", statusOf(orderId)),
      // The line was added to an order that is paid meanwhile: nothing refused it.
      () -> assertEquals(2L, countRows("order_items")),
      // The version moved only once, with the payment.
      () -> assertEquals(1L, versionOf(orderId)));
  }

  @Test
  void aChangeOfAColumnOfTheRowIsRefusedByTheVersionAndNothingIsSaved() {
    long keyboard = insertItem("Keyboard", "49.90");
    long mouse = insertItem("Mouse", "19.90");
    long orderId = transaction.execute(status ->
      orderRepository.saveAndFlush(newOrder(keyboard)).getId());

    assertThrows(OptimisticLockingFailureException.class,
      () -> transaction.executeWithoutResult(status -> {
        Order mine = orderRepository.findById(orderId).orElseThrow();
        mine.getItems().size();
        separateTransaction.executeWithoutResult(inner ->
          orderRepository.findById(orderId).orElseThrow().markPaid());
        mine.addItem(newLine(mouse));
        // The total is a column of the row, so the UPDATE carries "AND version = 0".
        mine.setTotalPrice(new BigDecimal("70.00"));
        orderRepository.saveAndFlush(mine);
      }));

    assertAll(
      () -> assertEquals("PAID", statusOf(orderId)),
      () -> assertEquals(1L, countRows("order_items"), "the added line is rolled back"),
      () -> assertEquals(1L, versionOf(orderId)),
      () -> assertEquals(new BigDecimal("49.90"), jdbcTemplate.queryForObject(
        "SELECT total_price FROM orders WHERE id = ?", BigDecimal.class, orderId)));
  }

  private String statusOf(long orderId) {
    return jdbcTemplate.queryForObject(
      "SELECT status FROM orders WHERE id = ?", String.class, orderId);
  }

  private long versionOf(long orderId) {
    Long version = jdbcTemplate.queryForObject(
      "SELECT version FROM orders WHERE id = ?", Long.class, orderId);
    return version == null ? -1 : version;
  }

  /** A new order of the user 7 with one line of the item. Call inside a transaction. */
  private Order newOrder(long itemId) {
    Order order = new Order();
    order.setUserId(7L);
    order.setUserEmail(OWNER_EMAIL);
    order.setTotalPrice(new BigDecimal("49.90"));
    order.addItem(newLine(itemId));
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
