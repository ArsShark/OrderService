package com.innowise.orderservice.integration;

import static com.innowise.orderservice.integration.ApiSupport.ANNA_EMAIL;
import static com.innowise.orderservice.integration.ApiSupport.annaToken;
import static com.innowise.orderservice.integration.ApiSupport.as;
import static com.innowise.orderservice.integration.ApiSupport.bodyOf;
import static com.innowise.orderservice.integration.ApiSupport.itemJson;
import static com.innowise.orderservice.integration.ApiSupport.json;
import static com.innowise.orderservice.integration.ApiSupport.orderJson;
import static com.innowise.orderservice.integration.ApiSupport.placeOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The catalogue through the REST API: the life of an item, the validation of its body, who may
 * read and who may write, pages and sorting, and what the soft delete of an item means for the
 * orders that contain it.
 *
 * <p>The default caller of {@code mockMvc} is an admin; Anna is a user.
 */
class ItemApiIntegrationTest extends AbstractIntegrationTest {

  private static final String ANNA = annaToken();

  // ---- the life of an item

  @Test
  void anItemIsCreatedReadChangedAndSoftDeleted() throws Exception {
    mockMvc.perform(json(post("/api/items"), itemJson("Keyboard", "49.9"))).andExpectAll(
      status().isCreated(),
      header().string(HttpHeaders.LOCATION, startsWith("http://localhost/api/items/1")),
      jsonPath("$.id").value(1),
      jsonPath("$.name").value("Keyboard"),
      jsonPath("$.price").value(49.90),
      jsonPath("$.createdAt").value("2026-11-02T10:00:00Z"));
    mockMvc.perform(get("/api/items/1")).andExpectAll(
      status().isOk(),
      jsonPath("$.name").value("Keyboard"));
    clock.advance(Duration.ofHours(1));

    mockMvc.perform(json(put("/api/items/1"), itemJson("Mechanical keyboard", "79.00")))
      .andExpectAll(
        status().isOk(),
        jsonPath("$.name").value("Mechanical keyboard"),
        jsonPath("$.price").value(79.00),
        jsonPath("$.createdAt").value("2026-11-02T10:00:00Z"),
        jsonPath("$.updatedAt").value("2026-11-02T11:00:00Z"));
    mockMvc.perform(delete("/api/items/1")).andExpectAll(
      status().isNoContent(),
      content().string(""));

    String notFound = "Item with id 1 not found";
    mockMvc.perform(get("/api/items/1")).andExpectAll(
      status().isNotFound(), problemJson(), jsonPath("$.detail").value(notFound));
    mockMvc.perform(json(put("/api/items/1"), itemJson("Again", "1.00")))
      .andExpect(status().isNotFound());
    mockMvc.perform(delete("/api/items/1")).andExpect(status().isNotFound());
    mockMvc.perform(get("/api/items")).andExpectAll(
      status().isOk(), jsonPath("$.totalElements").value(0));

    assertAll(
      () -> assertEquals(1L, countRows("items"), "a deleted item stays in the table"),
      () -> assertTrue(jdbcTemplate.queryForObject(
        "SELECT deleted FROM items WHERE id = 1", Boolean.class)),
      () -> assertEquals(new BigDecimal("79.00"), jdbcTemplate.queryForObject(
        "SELECT price FROM items WHERE id = 1", BigDecimal.class)));
  }

  @Test
  void theLimitsOfThePriceAreAllowedAndAFreeItemIsAnItem() throws Exception {
    mockMvc.perform(json(post("/api/items"), itemJson("Free", "0.00")))
      .andExpect(status().isCreated());
    mockMvc.perform(json(post("/api/items"), itemJson("Dear", "99999.99")))
      .andExpectAll(status().isCreated(), jsonPath("$.price").value(99999.99));
  }

  // ---- bad bodies

  static Stream<Arguments> invalidItems() {
    return Stream.of(
      Arguments.of("a blank name", itemJson("   ", "1.00")),
      Arguments.of("no name", "{\"price\": 1.00}"),
      Arguments.of("a name of 256 characters", itemJson("x".repeat(256), "1.00")),
      Arguments.of("no price", "{\"name\": \"Cable\"}"),
      Arguments.of("a negative price", itemJson("Cable", "-0.01")),
      Arguments.of("a price over the limit", itemJson("Cable", "100000.00")),
      Arguments.of("a price with three decimals", itemJson("Cable", "1.234")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidItems")
  void anInvalidItemIsRefusedWith400AndNothingIsSaved(String name, String body)
      throws Exception {
    mockMvc.perform(json(post("/api/items"), body)).andExpectAll(
      status().isBadRequest(),
      problemJson(),
      jsonPath("$.detail").value("Request validation failed"),
      jsonPath("$.errors").isNotEmpty());
    mockMvc.perform(json(put("/api/items/1"), body)).andExpect(status().isBadRequest());

    assertEquals(0L, countRows("items"));
  }

  @Test
  void aPriceThatIsTextAndABrokenBodyAreA400AndAPathThatIsNotANumberToo() throws Exception {
    mockMvc.perform(json(post("/api/items"), "{\"name\": \"Cable\", \"price\": \"abc\"}"))
      .andExpectAll(status().isBadRequest(), problemJson());
    mockMvc.perform(json(post("/api/items"), "{\"name\": "))
      .andExpectAll(status().isBadRequest(), problemJson());
    mockMvc.perform(get("/api/items/abc")).andExpectAll(
      status().isBadRequest(),
      jsonPath("$.detail").value("Invalid value of the parameter 'id'"));

    assertEquals(0L, countRows("items"));
  }

  // ---- who may do what

  @Test
  void aUserReadsTheCatalogueButCannotWriteToIt() throws Exception {
    insertItem("Keyboard", "49.90");

    mockMvc.perform(as(get("/api/items"), ANNA)).andExpectAll(
      status().isOk(), jsonPath("$.totalElements").value(1));
    mockMvc.perform(as(get("/api/items/1"), ANNA)).andExpectAll(
      status().isOk(), jsonPath("$.name").value("Keyboard"));
    mockMvc.perform(json(as(post("/api/items"), ANNA), itemJson("Mouse", "19.90")))
      .andExpectAll(status().isForbidden(), problemJson());
    mockMvc.perform(json(as(put("/api/items/1"), ANNA), itemJson("Mouse", "19.90")))
      .andExpect(status().isForbidden());
    mockMvc.perform(as(delete("/api/items/1"), ANNA)).andExpect(status().isForbidden());

    assertAll(
      () -> assertEquals(1L, countRows("items")),
      () -> assertEquals("Keyboard", jdbcTemplate.queryForObject(
        "SELECT name FROM items WHERE id = 1", String.class)),
      () -> assertEquals(false, jdbcTemplate.queryForObject(
        "SELECT deleted FROM items WHERE id = 1", Boolean.class)));
  }

  // ---- pages and sorting

  /** Items 1 to 5: Cable 5.00, Adapter 15.00, Dock 15.00, Battery 25.00, Zip 1.00. */
  private void createFiveItems() throws Exception {
    insertItem("Cable", "5.00");
    insertItem("Adapter", "15.00");
    insertItem("Dock", "15.00");
    insertItem("Battery", "25.00");
    insertItem("Zip", "1.00");
  }

  @Test
  void theListIsPagedAndSortedByTheAllowedPropertiesWithTheIdBreakingTies() throws Exception {
    createFiveItems();

    mockMvc.perform(get("/api/items").param("size", "2").param("page", "1")).andExpectAll(
      status().isOk(),
      jsonPath("$.page").value(1),
      jsonPath("$.size").value(2),
      jsonPath("$.totalElements").value(5),
      jsonPath("$.totalPages").value(3));

    assertAll(
      () -> assertEquals(List.of(1L, 2L, 3L, 4L, 5L), idsOf(get("/api/items"))),
      () -> assertEquals(List.of(3L, 4L),
        idsOf(get("/api/items").param("size", "2").param("page", "1"))),
      () -> assertEquals(List.of(2L, 4L, 1L, 3L, 5L),
        idsOf(get("/api/items").param("sort", "name"))),
      () -> assertEquals(List.of(4L, 2L, 3L, 1L, 5L),
        idsOf(get("/api/items").param("sort", "price,desc"))));
  }

  @Test
  void aDeletedItemLeavesTheListAndThePageSizeIsCapped() throws Exception {
    createFiveItems();
    mockMvc.perform(delete("/api/items/3")).andExpect(status().isNoContent());

    mockMvc.perform(get("/api/items").param("size", "500")).andExpectAll(
      status().isOk(),
      jsonPath("$.size").value(100),
      jsonPath("$.totalElements").value(4));

    assertEquals(List.of(1L, 2L, 4L, 5L), idsOf(get("/api/items")));
  }

  @Test
  void aSortPropertyThatIsNotPartOfTheApiIsA400AndIsNotRepeated() throws Exception {
    mockMvc.perform(get("/api/items").param("sort", "deleted")).andExpectAll(
      status().isBadRequest(),
      problemJson(),
      jsonPath("$.detail", startsWith("Unsupported sort property")),
      content().string(not(containsString("deleted"))));
  }

  // ---- the soft delete of an item and the orders that contain it

  @Test
  void anOrderKeepsItsLineOfADeletedItemButTheItemCannotBeOrderedAgain() throws Exception {
    insertItem("Keyboard", "49.90");
    insertItem("Mouse", "19.90");
    UserServiceStub.respondWithUser(ANNA_EMAIL, 7, "Anna", "Petrova");
    long orderId = placeOrder(mockMvc, ANNA, orderJson(1, 1, 2, 1));

    mockMvc.perform(delete("/api/items/1")).andExpect(status().isNoContent());

    mockMvc.perform(as(get("/api/orders/{id}", orderId), ANNA)).andExpectAll(
      status().isOk(),
      jsonPath("$.items[0].itemName").value("Keyboard"),
      jsonPath("$.items[0].unitPrice").value(49.90),
      jsonPath("$.totalPrice").value(69.80));
    // The item is gone for new orders and for a replacement that still names it.
    mockMvc.perform(json(as(post("/api/orders"), ANNA), orderJson(1, 1)))
      .andExpectAll(
        status().isUnprocessableEntity(),
        jsonPath("$.detail").value("Item with id 1 is not available"));
    mockMvc.perform(json(as(put("/api/orders/{id}", orderId), ANNA), orderJson(1, 1, 2, 2)))
      .andExpect(status().isUnprocessableEntity());
    long linesBefore = countRows("order_items");

    // A replacement without the deleted item works, and the old line goes.
    mockMvc.perform(json(as(put("/api/orders/{id}", orderId), ANNA), orderJson(2, 2)))
      .andExpectAll(
        status().isOk(),
        jsonPath("$.items.length()").value(1),
        jsonPath("$.items[0].itemName").value("Mouse"),
        jsonPath("$.totalPrice").value(39.80));

    assertAll(
      () -> assertEquals(2L, linesBefore, "the line of the deleted item stayed meanwhile"),
      () -> assertEquals(1L, countRows("order_items")),
      () -> assertEquals(2L, countRows("items")));
  }

  // ---- helpers

  private List<Long> idsOf(MockHttpServletRequestBuilder request) throws Exception {
    MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
    List<Number> ids = JsonPath.read(bodyOf(result), "$.content[*].id");
    return ids.stream().map(Number::longValue).toList();
  }
}
