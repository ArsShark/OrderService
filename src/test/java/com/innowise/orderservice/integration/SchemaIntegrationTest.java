package com.innowise.orderservice.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.innowise.orderservice.entity.OrderStatus;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.postgresql.util.PSQLException;

/**
 * Pins the database schema that Liquibase builds: the columns and their types, the names of
 * every constraint and index, and what the constraints really do. A constraint is proved by the
 * violation it causes: the test reads the name of the violated constraint from the PostgreSQL
 * error, which does not depend on the language of the server messages.
 */
class SchemaIntegrationTest extends AbstractIntegrationTest {

  private static final String TIMESTAMPTZ = "timestamp with time zone";

  @Test
  void itemsHasTheExpectedColumns() {
    assertEquals(Map.of(
      "id", "bigint",
      "name", "character varying(255)",
      "price", "numeric(12,2)",
      "deleted", "boolean",
      "created_at", TIMESTAMPTZ,
      "updated_at", TIMESTAMPTZ), columnsOf("items"));
  }

  @Test
  void ordersHasTheExpectedColumns() {
    assertEquals(Map.of(
      "id", "bigint",
      "user_id", "bigint",
      "user_email", "character varying(255)",
      "status", "character varying(20)",
      "total_price", "numeric(12,2)",
      "deleted", "boolean",
      "version", "bigint",
      "created_at", TIMESTAMPTZ,
      "updated_at", TIMESTAMPTZ), columnsOf("orders"));
  }

  @Test
  void orderItemsHasTheExpectedColumns() {
    assertEquals(Map.of(
      "id", "bigint",
      "order_id", "bigint",
      "item_id", "bigint",
      "quantity", "integer",
      "unit_price", "numeric(12,2)",
      "created_at", TIMESTAMPTZ,
      "updated_at", TIMESTAMPTZ), columnsOf("order_items"));
  }

  @Test
  void everyColumnIsNotNull() {
    assertAll(
      () -> assertEquals(List.of(), nullableColumnsOf("items")),
      () -> assertEquals(List.of(), nullableColumnsOf("orders")),
      () -> assertEquals(List.of(), nullableColumnsOf("order_items")));
  }

  @Test
  void everyConstraintHasItsName() {
    assertAll(
      () -> assertEquals(
        Set.of("pk_items", "ck_items_price_non_negative"),
        constraintsOf("items")),
      () -> assertEquals(
        Set.of("pk_orders", "ck_orders_status", "ck_orders_total_price_non_negative"),
        constraintsOf("orders")),
      () -> assertEquals(
        Set.of("pk_order_items", "fk_order_items_order", "fk_order_items_item",
          "uq_order_items_order_id_item_id", "ck_order_items_quantity_positive",
          "ck_order_items_unit_price_non_negative"),
        constraintsOf("order_items")));
  }

  @Test
  void everyIndexHasItsName() {
    assertAll(
      () -> assertEquals(
        Set.of("pk_items", "idx_items_name"),
        indexesOf("items")),
      () -> assertEquals(
        Set.of("pk_orders", "idx_orders_user_id_created_at", "idx_orders_status_created_at"),
        indexesOf("orders")),
      () -> assertEquals(
        Set.of("pk_order_items", "uq_order_items_order_id_item_id", "idx_order_items_item_id"),
        indexesOf("order_items")));
  }

  @Test
  void theColumnOrderOfTheCompositeIndexesIsPinned() {
    // The filter column comes first, so the index serves "WHERE user_id = ? ORDER BY created_at".
    assertAll(
      () -> assertTrue(indexDefinition("idx_orders_user_id_created_at")
        .contains("(user_id, created_at)")),
      () -> assertTrue(indexDefinition("idx_orders_status_created_at")
        .contains("(status, created_at)")));
  }

  @Test
  void defaultsFillTheColumnsThatTheApplicationDoesNotSet() {
    long itemId = insertItem("Keyboard", "49.90");
    long orderId = jdbcTemplate.queryForObject(
      "INSERT INTO orders (user_id, user_email, total_price) VALUES (7, ?, 0) RETURNING id",
      Long.class, OWNER_EMAIL);

    assertAll(
      () -> assertEquals(false, jdbcTemplate.queryForObject(
        "SELECT deleted FROM items WHERE id = ?", Boolean.class, itemId)),
      () -> assertEquals("PENDING", jdbcTemplate.queryForObject(
        "SELECT status FROM orders WHERE id = ?", String.class, orderId)),
      () -> assertEquals(false, jdbcTemplate.queryForObject(
        "SELECT deleted FROM orders WHERE id = ?", Boolean.class, orderId)),
      () -> assertEquals(0L, jdbcTemplate.queryForObject(
        "SELECT version FROM orders WHERE id = ?", Long.class, orderId)),
      () -> assertNotNull(jdbcTemplate.queryForObject(
        "SELECT created_at FROM orders WHERE id = ?", Object.class, orderId)),
      () -> assertNotNull(jdbcTemplate.queryForObject(
        "SELECT updated_at FROM orders WHERE id = ?", Object.class, orderId)));
  }

  @Test
  void moneyIsStoredWithTwoDecimals() {
    long itemId = insertItem("Keyboard", "49.9");

    assertEquals(new BigDecimal("49.90"), jdbcTemplate.queryForObject(
      "SELECT price FROM items WHERE id = ?", BigDecimal.class, itemId));
  }

  @Test
  void checkConstraintsRejectInvalidValues() {
    long itemId = insertItem("Keyboard", "49.90");
    long orderId = insertOrder(7, "PENDING", "49.90");

    assertAll(
      () -> assertEquals("ck_items_price_non_negative",
        violatedConstraint(() -> insertItem("Broken", "-0.01"))),
      () -> assertEquals("ck_orders_total_price_non_negative",
        violatedConstraint(() -> insertOrder(7, "PENDING", "-0.01"))),
      () -> assertEquals("ck_orders_status",
        violatedConstraint(() -> insertOrder(7, "SHIPPED", "10.00"))),
      () -> assertEquals("ck_order_items_quantity_positive",
        violatedConstraint(() -> insertOrderItem(orderId, itemId, 0, "49.90"))),
      () -> assertEquals("ck_order_items_unit_price_non_negative",
        violatedConstraint(() -> insertOrderItem(orderId, itemId, 1, "-1.00"))));
  }

  @Test
  void aFreeItemAndAZeroTotalAreAllowed() {
    assertAll(
      () -> assertDoesNotThrow(() -> insertItem("Free sample", "0.00")),
      () -> assertDoesNotThrow(() -> insertOrder(7, "PENDING", "0.00")));
  }

  @ParameterizedTest
  @EnumSource(OrderStatus.class)
  void everyStatusOfTheEnumIsAcceptedByTheDatabase(OrderStatus status) {
    // Keeps the enum and the CHECK constraint ck_orders_status in step.
    assertDoesNotThrow(() -> insertOrder(7, status.name(), "10.00"));
  }

  @Test
  void anItemCanBeOnAnOrderOnlyOnce() {
    long itemId = insertItem("Keyboard", "49.90");
    long orderId = insertOrder(7, "PENDING", "49.90");
    insertOrderItem(orderId, itemId, 1, "49.90");

    assertEquals("uq_order_items_order_id_item_id",
      violatedConstraint(() -> insertOrderItem(orderId, itemId, 2, "49.90")));
  }

  @Test
  void aLineMustPointToAnExistingOrderAndItem() {
    long itemId = insertItem("Keyboard", "49.90");
    long orderId = insertOrder(7, "PENDING", "49.90");

    assertAll(
      () -> assertEquals("fk_order_items_order",
        violatedConstraint(() -> insertOrderItem(999, itemId, 1, "49.90"))),
      () -> assertEquals("fk_order_items_item",
        violatedConstraint(() -> insertOrderItem(orderId, 999, 1, "49.90"))));
  }

  @Test
  void deletingAnOrderDeletesItsLinesButNotItsItems() {
    long keyboard = insertItem("Keyboard", "49.90");
    long mouse = insertItem("Mouse", "19.90");
    long orderId = insertOrder(7, "PENDING", "69.80");
    insertOrderItem(orderId, keyboard, 1, "49.90");
    insertOrderItem(orderId, mouse, 1, "19.90");

    jdbcTemplate.update("DELETE FROM orders WHERE id = ?", orderId);

    assertAll(
      () -> assertEquals(0, countRows("order_items")),
      () -> assertEquals(2, countRows("items")));
  }

  @Test
  void anItemThatIsUsedByALineCannotBeDeleted() {
    long itemId = insertItem("Keyboard", "49.90");
    long orderId = insertOrder(7, "PENDING", "49.90");
    insertOrderItem(orderId, itemId, 1, "49.90");

    assertAll(
      () -> assertEquals("fk_order_items_item", violatedConstraint(
        () -> jdbcTemplate.update("DELETE FROM items WHERE id = ?", itemId))),
      () -> assertEquals(1, countRows("items")));
  }

  /** Column name to PostgreSQL type, for example {@code price -> numeric(12,2)}. */
  private Map<String, String> columnsOf(String table) {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
      SELECT a.attname AS name, format_type(a.atttypid, a.atttypmod) AS type
      FROM pg_attribute a
      WHERE a.attrelid = to_regclass(?::text) AND a.attnum > 0 AND NOT a.attisdropped
      """, table);
    Map<String, String> columns = new HashMap<>();
    for (Map<String, Object> row : rows) {
      columns.put((String) row.get("name"), (String) row.get("type"));
    }
    return columns;
  }

  private List<String> nullableColumnsOf(String table) {
    return jdbcTemplate.queryForList("""
      SELECT attname FROM pg_attribute
      WHERE attrelid = to_regclass(?::text) AND attnum > 0 AND NOT attisdropped
        AND NOT attnotnull
      """, String.class, table);
  }

  /** Primary key, foreign key, unique and check constraints (not the NOT NULL ones). */
  private Set<String> constraintsOf(String table) {
    return new HashSet<>(jdbcTemplate.queryForList("""
      SELECT conname FROM pg_constraint
      WHERE conrelid = to_regclass(?::text) AND contype IN ('p', 'f', 'u', 'c')
      """, String.class, table));
  }

  private Set<String> indexesOf(String table) {
    return new HashSet<>(jdbcTemplate.queryForList("""
      SELECT indexname FROM pg_indexes
      WHERE schemaname = current_schema() AND tablename = ?
      """, String.class, table));
  }

  private String indexDefinition(String index) {
    return jdbcTemplate.queryForObject(
      "SELECT indexdef FROM pg_indexes WHERE indexname = ?", String.class, index);
  }

  /**
   * Runs the action, expects the database to refuse it and returns the name of the violated
   * constraint, taken from the error of the PostgreSQL server.
   */
  private static String violatedConstraint(Executable action) {
    Exception failure = assertThrows(Exception.class, action);
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
        return psql.getServerErrorMessage().getConstraint();
      }
    }
    return fail("The failure has no PostgreSQL error in its cause chain", failure);
  }
}
