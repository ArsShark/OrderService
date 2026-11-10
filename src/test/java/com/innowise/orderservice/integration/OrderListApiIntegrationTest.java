package com.innowise.orderservice.integration;

import static com.innowise.orderservice.integration.ApiSupport.ANNA_EMAIL;
import static com.innowise.orderservice.integration.ApiSupport.BORIS_EMAIL;
import static com.innowise.orderservice.integration.ApiSupport.annaToken;
import static com.innowise.orderservice.integration.ApiSupport.as;
import static com.innowise.orderservice.integration.ApiSupport.bodyOf;
import static com.innowise.orderservice.integration.ApiSupport.borisToken;
import static com.innowise.orderservice.integration.ApiSupport.orderJson;
import static com.innowise.orderservice.integration.ApiSupport.placeOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The lists of orders through the REST API: the filters of the admin list and of {@code /my},
 * the half-open range of the creation time, pages, the cap of the page size, sorting and the
 * errors of bad parameters. The orders are placed through the API with the clock set to a
 * different day each time, so the creation times are real.
 *
 * <p>Six orders are placed, one per day from {@link #START}: 1, 2, 4 and 6 by Anna, 3 and 5 by
 * Boris; the keyboard (49.90) in 1, 3 and 6, the mouse (19.90) in 2, 4 and 5. Then order 2 is
 * cancelled, order 4 is PAID, order 5 is PAYMENT_FAILED (set with SQL: no payment event exists
 * yet) and order 6 is deleted. So the visible orders are:
 *
 * <pre>
 * id  owner  created  status          total
 * 1   Anna   day 0    PENDING         49.90
 * 2   Anna   day 1    CANCELLED       19.90
 * 3   Boris  day 2    PENDING         49.90
 * 4   Anna   day 3    PAID            19.90
 * 5   Boris  day 4    PAYMENT_FAILED  19.90
 * </pre>
 */
class OrderListApiIntegrationTest extends AbstractIntegrationTest {

  private static final String ANNA = annaToken();
  private static final String BORIS = borisToken();

  @BeforeEach
  void placeTheOrders() throws Exception {
    insertItem("Keyboard", "49.90");
    insertItem("Mouse", "19.90");
    UserServiceStub.respondWithUser(ANNA_EMAIL, 7, "Anna", "Petrova");
    UserServiceStub.respondWithUser(BORIS_EMAIL, 8, "Boris", "Ivanov");
    placeOnDay(0, ANNA, 1);
    placeOnDay(1, ANNA, 2);
    placeOnDay(2, BORIS, 1);
    placeOnDay(3, ANNA, 2);
    placeOnDay(4, BORIS, 2);
    placeOnDay(5, ANNA, 1);
    mockMvc.perform(as(patch("/api/orders/2/cancel"), ANNA)).andExpect(status().isOk());
    jdbcTemplate.update("UPDATE orders SET status = 'PAID' WHERE id = 4");
    jdbcTemplate.update("UPDATE orders SET status = 'PAYMENT_FAILED' WHERE id = 5");
    mockMvc.perform(as(delete("/api/orders/6"), ANNA)).andExpect(status().isNoContent());
    clock.set(day(10));
  }

  // ---- the list of the admin

  @Test
  void theListIsNewestFirstHidesDeletedOrdersAndShowsTheOwnerOfEveryOrder() throws Exception {
    mockMvc.perform(get("/api/orders")).andExpectAll(
      status().isOk(),
      jsonPath("$.page").value(0),
      jsonPath("$.size").value(20),
      jsonPath("$.totalElements").value(5),
      jsonPath("$.totalPages").value(1),
      jsonPath("$.content[0].id").value(5),
      jsonPath("$.content[0].status").value("PAYMENT_FAILED"),
      jsonPath("$.content[0].user.name").value("Boris"),
      jsonPath("$.content[0].userInfoAvailable").value(true),
      jsonPath("$.content[4].id").value(1),
      jsonPath("$.content[4].user.name").value("Anna"),
      jsonPath("$.content[4].items[0].itemName").value("Keyboard"));

    assertEquals(List.of(5L, 4L, 3L, 2L, 1L), idsOf(get("/api/orders")));
  }

  @Test
  void theCreationRangeIncludesItsStartAndExcludesItsEnd() throws Exception {
    assertAll(
      () -> assertEquals(List.of(5L, 4L, 3L, 2L),
        idsOf(get("/api/orders").param("createdFrom", day(1).toString()))),
      () -> assertEquals(List.of(2L, 1L),
        idsOf(get("/api/orders").param("createdBefore", day(2).toString()))),
      () -> assertEquals(List.of(3L, 2L),
        idsOf(get("/api/orders")
          .param("createdFrom", day(1).toString())
          .param("createdBefore", day(3).toString()))),
      // One second before the start of order 2, and one second after it.
      () -> assertEquals(List.of(5L, 4L, 3L, 2L),
        idsOf(get("/api/orders").param("createdFrom", day(1).minusSeconds(1).toString()))),
      () -> assertEquals(List.of(5L, 4L, 3L),
        idsOf(get("/api/orders").param("createdFrom", day(1).plusSeconds(1).toString()))));
  }

  @Test
  void anEmptyOrReversedRangeIsA400() throws Exception {
    String same = day(2).toString();

    mockMvc.perform(get("/api/orders").param("createdFrom", same).param("createdBefore", same))
      .andExpectAll(
        status().isBadRequest(),
        problemJson(),
        jsonPath("$.detail").value("createdFrom must be before createdBefore"));
    mockMvc.perform(get("/api/orders")
        .param("createdFrom", day(3).toString()).param("createdBefore", day(1).toString()))
      .andExpect(status().isBadRequest());
  }

  @Test
  void statusesAcceptACommaSeparatedListAndRepeatedValues() throws Exception {
    assertAll(
      () -> assertEquals(List.of(2L), idsOf(get("/api/orders").param("statuses", "CANCELLED"))),
      () -> assertEquals(List.of(5L, 4L),
        idsOf(get("/api/orders").param("statuses", "PAID,PAYMENT_FAILED"))),
      () -> assertEquals(List.of(4L, 2L),
        idsOf(get("/api/orders").param("statuses", "PAID").param("statuses", "CANCELLED"))),
      () -> assertEquals(List.of(3L, 1L), idsOf(get("/api/orders").param("statuses", "PENDING"))));
  }

  @Test
  void anAdminFiltersByOwner() throws Exception {
    assertAll(
      () -> assertEquals(List.of(5L, 3L), idsOf(get("/api/orders").param("userId", "8"))),
      () -> assertEquals(List.of(4L, 2L, 1L), idsOf(get("/api/orders").param("userId", "7"))),
      () -> assertEquals(List.of(), idsOf(get("/api/orders").param("userId", "99"))));
  }

  @Test
  void everyFilterNarrowsTheResultAtTheSameTime() throws Exception {
    // Anna, pending or cancelled, from day 1 on: only the cancelled order of day 1.
    assertEquals(List.of(2L), idsOf(get("/api/orders")
      .param("userId", "7")
      .param("statuses", "PENDING,CANCELLED")
      .param("createdFrom", day(1).toString())));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("badParameters")
  void aBadParameterIsA400ThatNamesItAndNeverRepeatsTheValue(String parameter, String value)
      throws Exception {
    mockMvc.perform(get("/api/orders").param(parameter, value)).andExpectAll(
      status().isBadRequest(),
      problemJson(),
      jsonPath("$.detail").value(String.format("Invalid value of the parameter '%s'", parameter)),
      jsonPath("$.detail", not(containsString(value))));
  }

  static Stream<Arguments> badParameters() {
    return Stream.of(
      Arguments.of("statuses", "SHIPPED"),
      Arguments.of("createdFrom", "yesterday"),
      Arguments.of("createdBefore", "2026-11-03"),
      Arguments.of("userId", "abc"));
  }

  // ---- pages

  @Test
  void aPageIsCutFromTheFilteredResultAndTheTotalsAreOfTheWholeResult() throws Exception {
    mockMvc.perform(get("/api/orders").param("size", "2").param("page", "1")).andExpectAll(
      status().isOk(),
      jsonPath("$.page").value(1),
      jsonPath("$.size").value(2),
      jsonPath("$.totalElements").value(5),
      jsonPath("$.totalPages").value(3),
      jsonPath("$.content.length()").value(2));

    assertAll(
      () -> assertEquals(List.of(5L, 4L), idsOf(get("/api/orders").param("size", "2"))),
      () -> assertEquals(List.of(3L, 2L),
        idsOf(get("/api/orders").param("size", "2").param("page", "1"))),
      () -> assertEquals(List.of(1L),
        idsOf(get("/api/orders").param("size", "2").param("page", "2"))),
      () -> assertEquals(List.of(),
        idsOf(get("/api/orders").param("size", "2").param("page", "3"))));
  }

  @Test
  void aPageIsNeverLargerThanOneHundred() throws Exception {
    mockMvc.perform(get("/api/orders").param("size", "500")).andExpectAll(
      status().isOk(),
      jsonPath("$.size").value(100),
      jsonPath("$.totalElements").value(5),
      jsonPath("$.content.length()").value(5));
  }

  // ---- sorting

  @Test
  void aListCanBeSortedByAnAllowedPropertyAndTheIdBreaksTies() throws Exception {
    assertAll(
      () -> assertEquals(List.of(2L, 4L, 5L, 1L, 3L),
        idsOf(get("/api/orders").param("sort", "totalPrice,asc"))),
      () -> assertEquals(List.of(1L, 3L, 2L, 4L, 5L),
        idsOf(get("/api/orders").param("sort", "totalPrice,desc"))),
      () -> assertEquals(List.of(3L, 5L, 1L, 2L, 4L),
        idsOf(get("/api/orders").param("sort", "userId,desc"))),
      () -> assertEquals(List.of(1L, 2L, 3L, 4L, 5L),
        idsOf(get("/api/orders").param("sort", "createdAt,asc"))));
  }

  @Test
  void aSortPropertyThatIsNotPartOfTheApiIsA400AndIsNotRepeated() throws Exception {
    mockMvc.perform(get("/api/orders").param("sort", "userEmail")).andExpectAll(
      status().isBadRequest(),
      problemJson(),
      jsonPath("$.detail", startsWith("Unsupported sort property")),
      content().string(not(containsString("userEmail"))));
    mockMvc.perform(get("/api/orders").param("sort", "items.item.name"))
      .andExpect(status().isBadRequest());
  }

  // ---- the list of a user

  @Test
  void aUserListsOnlyHisOwnOrdersWhateverTheFilterSays() throws Exception {
    assertAll(
      () -> assertEquals(List.of(4L, 2L, 1L), idsOf(as(get("/api/orders/my"), ANNA))),
      () -> assertEquals(List.of(5L, 3L), idsOf(as(get("/api/orders/my"), BORIS))),
      // The owner of /my is the token; a userId parameter is not part of it.
      () -> assertEquals(List.of(4L, 2L, 1L),
        idsOf(as(get("/api/orders/my").param("userId", "8"), ANNA))));
  }

  @Test
  void theListOfAUserTakesTheSameFiltersPagesAndSorting() throws Exception {
    assertAll(
      () -> assertEquals(List.of(4L), idsOf(as(get("/api/orders/my")
        .param("statuses", "PAID"), ANNA))),
      () -> assertEquals(List.of(4L, 2L), idsOf(as(get("/api/orders/my")
        .param("createdFrom", day(1).toString()), ANNA))),
      () -> assertEquals(List.of(2L, 1L), idsOf(as(get("/api/orders/my")
        .param("createdBefore", day(2).toString()), ANNA))),
      () -> assertEquals(List.of(1L), idsOf(as(get("/api/orders/my")
        .param("size", "2").param("page", "1").param("sort", "id,desc"), ANNA))),
      () -> assertEquals(List.of(2L, 4L, 1L),
        idsOf(as(get("/api/orders/my").param("sort", "totalPrice,asc"), ANNA))));
  }

  // ---- helpers

  private static Instant day(int number) {
    return START.plus(Duration.ofDays(number));
  }

  private void placeOnDay(int number, String token, long itemId) throws Exception {
    clock.set(day(number));
    placeOrder(mockMvc, token, orderJson(itemId, 1));
  }

  /** Sends the request and returns the ids of the orders of the page, in the order of the page. */
  private List<Long> idsOf(MockHttpServletRequestBuilder request) throws Exception {
    MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
    List<Number> ids = JsonPath.read(bodyOf(result), "$.content[*].id");
    return ids.stream().map(Number::longValue).toList();
  }
}
