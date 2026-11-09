package com.innowise.orderservice.integration;

import static com.innowise.orderservice.integration.ApiSupport.ANNA_EMAIL;
import static com.innowise.orderservice.integration.ApiSupport.annaToken;
import static com.innowise.orderservice.integration.ApiSupport.as;
import static com.innowise.orderservice.integration.ApiSupport.bodyOf;
import static com.innowise.orderservice.integration.ApiSupport.bossToken;
import static com.innowise.orderservice.integration.ApiSupport.itemJson;
import static com.innowise.orderservice.integration.ApiSupport.json;
import static com.innowise.orderservice.integration.ApiSupport.orderJson;
import static com.innowise.orderservice.integration.ApiSupport.placeOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The life of an order through the REST API, from the controller to the database and to the User
 * Service (a WireMock stub): what is stored, what the answers show, and which statuses refuse
 * which request. Ownership has its own class ({@link OrderOwnershipIntegrationTest}), lists and
 * filters too ({@link OrderListApiIntegrationTest}).
 *
 * <p>The catalogue has a keyboard (item 1, 49.90) and a mouse (item 2, 19.90). Anna is the owner
 * of the orders; the default caller of {@code mockMvc}, an admin without a profile, is used for
 * the operations that only an admin may do. An order becomes PAID only by a payment event, which
 * does not exist before JACAG-24, so these tests set that status with SQL.
 */
class OrderApiIntegrationTest extends AbstractIntegrationTest {

  private static final String ANNA = annaToken();

  @BeforeEach
  void createCatalogueAndStub() {
    insertItem("Keyboard", "49.90");
    insertItem("Mouse", "19.90");
    UserServiceStub.respondWithUser(ANNA_EMAIL, 7, "Anna", "Petrova");
  }

  // ---- placing an order

  @Test
  void placingAnOrderCopiesThePricesCalculatesTheTotalAndTakesTheOwnerFromTheToken()
      throws Exception {
    // A client may send fields that the API does not have; none of them is used.
    String body = """
      {"userId": 99, "totalPrice": 0.01, "status": "PAID",
       "items": [{"itemId": 1, "quantity": 2}, {"itemId": 2, "quantity": 1}]}
      """;

    MvcResult created = mockMvc.perform(json(as(post("/api/orders"), ANNA), body))
      .andExpectAll(
        status().isCreated(),
        header().string(HttpHeaders.LOCATION, startsWith("http://localhost/api/orders/1")),
        jsonPath("$.id").value(1),
        jsonPath("$.userId").value(7),
        jsonPath("$.status").value("PENDING"),
        jsonPath("$.totalPrice").value(119.70),
        jsonPath("$.items", hasSize(2)),
        jsonPath("$.items[0].itemId").value(1),
        jsonPath("$.items[0].itemName").value("Keyboard"),
        jsonPath("$.items[0].quantity").value(2),
        jsonPath("$.items[0].unitPrice").value(49.90),
        jsonPath("$.items[0].lineTotal").value(99.80),
        jsonPath("$.items[1].itemName").value("Mouse"),
        jsonPath("$.items[1].lineTotal").value(19.90),
        jsonPath("$.createdAt").value("2026-11-02T10:00:00Z"),
        jsonPath("$.updatedAt").value("2026-11-02T10:00:00Z"),
        jsonPath("$.user.id").value(7),
        jsonPath("$.user.name").value("Anna"),
        jsonPath("$.userInfoAvailable").value(true))
      .andReturn();

    String read = bodyOf(mockMvc.perform(as(get("/api/orders/1"), ANNA))
      .andExpect(status().isOk()).andReturn());
    assertAll(
      () -> assertEquals(7L, stored("user_id", 1, Long.class)),
      () -> assertEquals(ANNA_EMAIL, stored("user_email", 1, String.class)),
      () -> assertEquals("PENDING", stored("status", 1, String.class)),
      () -> assertEquals(new BigDecimal("119.70"), stored("total_price", 1, BigDecimal.class)),
      () -> assertEquals(List.of(new BigDecimal("49.90"), new BigDecimal("19.90")),
        jdbcTemplate.queryForList(
          "SELECT unit_price FROM order_items WHERE order_id = 1 ORDER BY id",
          BigDecimal.class)),
      () -> assertEquals(1, UserServiceStub.requestsReceived()),
      () -> assertEquals(ANNA_EMAIL,
        UserServiceStub.requests().get(0).queryParameter("email").firstValue()),
      // What POST answers is what GET answers later.
      () -> assertEquals(bodyOf(created), read));
  }

  @Test
  void anAdminWithAProfilePlacesAnOrderForHimself() throws Exception {
    UserServiceStub.respondWithUser("boss@example.com", 1, "Boss", "Bossov");

    mockMvc.perform(json(as(post("/api/orders"), bossToken()), orderJson(2, 1)))
      .andExpectAll(
        status().isCreated(),
        jsonPath("$.userId").value(1),
        jsonPath("$.user.name").value("Boss"));

    assertAll(
      () -> assertEquals(1L, stored("user_id", 1, Long.class)),
      () -> assertEquals("boss@example.com", stored("user_email", 1, String.class)));
  }

  @Test
  void thePostIsNotIdempotentAndASecondIdenticalRequestCreatesASecondOrder() throws Exception {
    long first = placeOrder(mockMvc, ANNA, orderJson(1, 1));
    long second = placeOrder(mockMvc, ANNA, orderJson(1, 1));

    assertAll(
      () -> assertEquals(1L, first),
      () -> assertEquals(2L, second),
      () -> assertEquals(2L, countRows("orders")));
  }

  // ---- prices

  @Test
  void aLaterPriceChangeLeavesTheOrderAloneUntilItsLinesAreReplaced() throws Exception {
    long orderId = placeOrder(mockMvc, ANNA, orderJson(1, 1));

    mockMvc.perform(json(put("/api/items/1"), itemJson("Keyboard", "59.90")))
      .andExpect(status().isOk());
    mockMvc.perform(as(get("/api/orders/{id}", orderId), ANNA)).andExpectAll(
      status().isOk(),
      jsonPath("$.items[0].unitPrice").value(49.90),
      jsonPath("$.totalPrice").value(49.90));
    mockMvc.perform(json(as(put("/api/orders/{id}", orderId), ANNA), orderJson(1, 1)))
      .andExpectAll(
        status().isOk(),
        jsonPath("$.items[0].unitPrice").value(59.90),
        jsonPath("$.totalPrice").value(59.90));
  }

  // ---- replacing the lines

  @Test
  void replacingTheLinesKeepsTheRowOfAnItemThatStaysAndMovesOnlyUpdatedAt() throws Exception {
    insertItem("Monitor", "199.00");
    long orderId = placeOrder(mockMvc, ANNA, orderJson(1, 1, 2, 2));
    long keyboardLine = lineOf(orderId, 1);
    clock.advance(Duration.ofHours(1));

    mockMvc.perform(json(as(put("/api/orders/{id}", orderId), ANNA), orderJson(1, 5, 3, 1)))
      .andExpectAll(
        status().isOk(),
        jsonPath("$.totalPrice").value(448.50),
        jsonPath("$.items", hasSize(2)),
        jsonPath("$.items[0].itemName").value("Keyboard"),
        jsonPath("$.items[0].quantity").value(5),
        jsonPath("$.items[1].itemName").value("Monitor"),
        jsonPath("$.createdAt").value("2026-11-02T10:00:00Z"),
        jsonPath("$.updatedAt").value("2026-11-02T11:00:00Z"));

    assertAll(
      // The same row, updated: not deleted and inserted again.
      () -> assertEquals(keyboardLine, lineOf(orderId, 1)),
      () -> assertTrue(linesOf(orderId, 2).isEmpty(), "the mouse line must be gone"),
      () -> assertEquals(1, linesOf(orderId, 3).size()),
      () -> assertEquals(2L, countRows("order_items")),
      () -> assertEquals(new BigDecimal("448.50"),
        stored("total_price", orderId, BigDecimal.class)));
  }

  // ---- statuses

  @Test
  void aCancelledOrderStaysCancelledWhenCancelledAgainAndCannotBeEdited() throws Exception {
    long orderId = placeOrder(mockMvc, ANNA, orderJson(1, 1));

    mockMvc.perform(as(patch("/api/orders/{id}/cancel", orderId), ANNA)).andExpectAll(
      status().isOk(),
      jsonPath("$.status").value("CANCELLED"));
    mockMvc.perform(as(patch("/api/orders/{id}/cancel", orderId), ANNA)).andExpectAll(
      status().isOk(),
      jsonPath("$.status").value("CANCELLED"));
    mockMvc.perform(json(as(put("/api/orders/{id}", orderId), ANNA), orderJson(2, 1)))
      .andExpectAll(
        status().isConflict(),
        problemJson(),
        jsonPath("$.detail").value("Order with id 1 is CANCELLED and cannot be changed"));

    assertAll(
      () -> assertEquals("CANCELLED", stored("status", orderId, String.class)),
      () -> assertEquals(1, linesOf(orderId, 1).size()));
  }

  @Test
  void anOrderWhosePaymentFailedCanStillBeEditedAndCancelled() throws Exception {
    long orderId = placeOrder(mockMvc, ANNA, orderJson(1, 1));
    setStatus(orderId, "PAYMENT_FAILED");

    mockMvc.perform(json(as(put("/api/orders/{id}", orderId), ANNA), orderJson(2, 2)))
      .andExpectAll(
        status().isOk(),
        jsonPath("$.status").value("PAYMENT_FAILED"),
        jsonPath("$.totalPrice").value(39.80));
    mockMvc.perform(as(patch("/api/orders/{id}/cancel", orderId), ANNA)).andExpectAll(
      status().isOk(),
      jsonPath("$.status").value("CANCELLED"));
  }

  @Test
  void aPaidOrderCannotBeCancelledEditedOrDeletedByItsOwnerButAnAdminMayDeleteIt()
      throws Exception {
    long orderId = placeOrder(mockMvc, ANNA, orderJson(1, 1));
    setStatus(orderId, "PAID");

    mockMvc.perform(as(patch("/api/orders/{id}/cancel", orderId), ANNA)).andExpectAll(
      status().isConflict(),
      problemJson(),
      jsonPath("$.detail").value("An order in the status PAID cannot move to CANCELLED"));
    mockMvc.perform(json(as(put("/api/orders/{id}", orderId), ANNA), orderJson(2, 1)))
      .andExpectAll(
        status().isConflict(),
        jsonPath("$.detail").value("Order with id 1 is PAID and cannot be changed"));
    mockMvc.perform(as(delete("/api/orders/{id}", orderId), ANNA)).andExpectAll(
      status().isConflict(),
      jsonPath("$.detail").value("Order with id 1 is PAID and cannot be deleted"));
    boolean stillThere = !stored("deleted", orderId, Boolean.class);

    mockMvc.perform(delete("/api/orders/{id}", orderId)).andExpect(status().isNoContent());

    assertAll(
      () -> assertTrue(stillThere, "a refused deletion must change nothing"),
      () -> assertEquals("PAID", stored("status", orderId, String.class)),
      () -> assertEquals(1, linesOf(orderId, 1).size()),
      () -> assertTrue(stored("deleted", orderId, Boolean.class)));
  }

  // ---- deleting

  @Test
  void aDeletedOrderIsGoneForEverybodyButItsRowsStay() throws Exception {
    long orderId = placeOrder(mockMvc, ANNA, orderJson(1, 1, 2, 1));
    clock.advance(Duration.ofHours(1));

    mockMvc.perform(as(delete("/api/orders/{id}", orderId), ANNA)).andExpectAll(
      status().isNoContent(),
      content().string(""));

    String detail = "Order with id 1 not found";
    mockMvc.perform(as(get("/api/orders/{id}", orderId), ANNA)).andExpectAll(
      status().isNotFound(), jsonPath("$.detail").value(detail));
    mockMvc.perform(get("/api/orders/{id}", orderId)).andExpect(status().isNotFound());
    mockMvc.perform(json(as(put("/api/orders/{id}", orderId), ANNA), orderJson(1, 1)))
      .andExpect(status().isNotFound());
    mockMvc.perform(as(patch("/api/orders/{id}/cancel", orderId), ANNA))
      .andExpect(status().isNotFound());
    mockMvc.perform(as(delete("/api/orders/{id}", orderId), ANNA))
      .andExpect(status().isNotFound());
    mockMvc.perform(as(get("/api/orders/my"), ANNA)).andExpectAll(
      status().isOk(), jsonPath("$.totalElements").value(0));

    assertAll(
      () -> assertEquals(1L, countRows("orders")),
      () -> assertEquals(2L, countRows("order_items")),
      () -> assertTrue(stored("deleted", orderId, Boolean.class)),
      // The deletion goes through dirty checking, so auditing moved updated_at.
      () -> assertEquals("2026-11-02T11:00:00Z",
        stored("updated_at", orderId, OffsetDateTime.class)
          .toInstant().toString()));
  }

  // ---- bad requests

  static Stream<Arguments> invalidBodies() {
    long[] fiftyOneLines = new long[102];
    for (int i = 0; i < 51; i++) {
      fiftyOneLines[2 * i] = i + 1L;
      fiftyOneLines[2 * i + 1] = 1;
    }
    return Stream.of(
      Arguments.of("an empty list", "{\"items\": []}"),
      Arguments.of("no items at all", "{}"),
      Arguments.of("items null", "{\"items\": null}"),
      Arguments.of("quantity zero", orderJson(1, 0)),
      Arguments.of("quantity over the limit", orderJson(1, 1001)),
      Arguments.of("item id zero", orderJson(0, 1)),
      Arguments.of("item id negative", orderJson(-3, 1)),
      Arguments.of("quantity missing", "{\"items\": [{\"itemId\": 1}]}"),
      Arguments.of("item id missing", "{\"items\": [{\"quantity\": 1}]}"),
      Arguments.of("a null line", "{\"items\": [null]}"),
      Arguments.of("the same item twice", orderJson(1, 1, 1, 2)),
      Arguments.of("fifty-one lines", orderJson(fiftyOneLines)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidBodies")
  void anInvalidBodyIsRefusedWith400AndNothingIsSaved(String name, String body) throws Exception {
    mockMvc.perform(json(as(post("/api/orders"), ANNA), body)).andExpectAll(
      status().isBadRequest(),
      problemJson(),
      jsonPath("$.detail").value("Request validation failed"),
      jsonPath("$.errors").isNotEmpty());

    assertAll(
      () -> assertEquals(0L, countRows("orders")),
      () -> assertEquals(0, UserServiceStub.requestsReceived()));
  }

  @Test
  void aRefusedValueIsNeverRepeatedInTheAnswer() throws Exception {
    mockMvc.perform(json(as(post("/api/orders"), ANNA), orderJson(1, 5000123))).andExpectAll(
      status().isBadRequest(),
      jsonPath("$.errors[0].field").value("items[0].quantity"),
      content().string(not(containsString("5000123"))));
  }

  @Test
  void aBodyThatIsNotValidJsonIsA400WithTheProblemFormat() throws Exception {
    mockMvc.perform(json(as(post("/api/orders"), ANNA), "{\"items\": [")).andExpectAll(
      status().isBadRequest(), problemJson());
    mockMvc.perform(as(post("/api/orders"), ANNA).contentType(MediaType.APPLICATION_JSON))
      .andExpectAll(status().isBadRequest(), problemJson());
  }

  @Test
  void aBodyThatIsNotJsonIsA415WithTheProblemFormat() throws Exception {
    mockMvc.perform(as(post("/api/orders"), ANNA)
        .contentType(MediaType.TEXT_PLAIN).content(orderJson(1, 1)))
      .andExpectAll(status().isUnsupportedMediaType(), problemJson());

    assertEquals(0L, countRows("orders"));
  }

  @Test
  void anItemThatDoesNotExistOrWasDeletedIsA422AndNothingIsSaved() throws Exception {
    mockMvc.perform(delete("/api/items/2")).andExpect(status().isNoContent());

    mockMvc.perform(json(as(post("/api/orders"), ANNA), orderJson(999, 1))).andExpectAll(
      status().isUnprocessableEntity(),
      problemJson(),
      jsonPath("$.detail").value("Item with id 999 is not available"));
    mockMvc.perform(json(as(post("/api/orders"), ANNA), orderJson(1, 1, 2, 1))).andExpectAll(
      status().isUnprocessableEntity(),
      jsonPath("$.detail").value("Item with id 2 is not available"));

    assertEquals(0L, countRows("orders"));
  }

  @Test
  void aPathThatIsNotANumberIsA400ThatNamesTheParameterOnly() throws Exception {
    mockMvc.perform(as(get("/api/orders/abc"), ANNA)).andExpectAll(
      status().isBadRequest(),
      problemJson(),
      jsonPath("$.detail").value("Invalid value of the parameter 'id'"));
    mockMvc.perform(as(patch("/api/orders/abc/cancel"), ANNA)).andExpect(status().isBadRequest());
    mockMvc.perform(as(delete("/api/orders/abc"), ANNA)).andExpect(status().isBadRequest());
  }

  // ---- helpers

  private <T> T stored(String column, long orderId, Class<T> type) {
    return jdbcTemplate.queryForObject(
      String.format("SELECT %s FROM orders WHERE id = ?", column), type, orderId);
  }

  private void setStatus(long orderId, String status) {
    jdbcTemplate.update("UPDATE orders SET status = ? WHERE id = ?", status, orderId);
  }

  /** The ids of the lines of an order with this item (none, or one: the pair is unique). */
  private List<Long> linesOf(long orderId, long itemId) {
    return jdbcTemplate.queryForList(
      "SELECT id FROM order_items WHERE order_id = ? AND item_id = ?", Long.class, orderId,
      itemId);
  }

  private long lineOf(long orderId, long itemId) {
    return linesOf(orderId, itemId).get(0);
  }
}
