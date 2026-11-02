package com.innowise.orderservice.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves that the integration test infrastructure works: the application starts on a real
 * PostgreSQL, Liquibase applies every changeSet, the test clock replaces the application clock,
 * and the state is reset between tests, which every later integration test relies on.
 */
class SmokeIntegrationTest extends AbstractIntegrationTest {

  @Test
  void liquibaseAppliedEveryChangeSetInOrder() {
    List<String> applied = jdbcTemplate.queryForList(
      "SELECT id FROM databasechangelog ORDER BY orderexecuted", String.class);

    assertEquals(List.of(
      "create-items-table",
      "add-items-constraints",
      "create-items-indexes",
      "create-orders-table",
      "add-orders-constraints",
      "create-orders-indexes",
      "create-order-items-table",
      "add-order-items-constraints",
      "create-order-items-indexes"), applied);
  }

  @Test
  void resetStateEmptiesTheTablesRestartsTheIdentityAndTheClock() {
    long itemId = insertItem("Keyboard", "49.90");
    long orderId = insertOrder(7, "PENDING", "49.90");
    insertOrderItem(orderId, itemId, 1, "49.90");
    clock.advance(Duration.ofHours(1));

    resetState();

    assertAll(
      () -> assertEquals(0, countRows("items")),
      () -> assertEquals(0, countRows("orders")),
      () -> assertEquals(0, countRows("order_items")),
      () -> assertEquals(START, clock.instant()),
      // The identity counter restarted as well: the next item gets the id 1, not 2.
      () -> assertEquals(1L, insertItem("Mouse", "19.90")));
  }
}
