package com.innowise.orderservice.integration;

import static com.innowise.orderservice.integration.ApiSupport.ANNA_EMAIL;
import static com.innowise.orderservice.integration.ApiSupport.BORIS_EMAIL;
import static com.innowise.orderservice.integration.ApiSupport.annaToken;
import static com.innowise.orderservice.integration.ApiSupport.as;
import static com.innowise.orderservice.integration.ApiSupport.bodyOf;
import static com.innowise.orderservice.integration.ApiSupport.borisToken;
import static com.innowise.orderservice.integration.ApiSupport.bossToken;
import static com.innowise.orderservice.integration.ApiSupport.json;
import static com.innowise.orderservice.integration.ApiSupport.orderJson;
import static com.innowise.orderservice.integration.ApiSupport.placeOrder;
import static com.innowise.orderservice.integration.ApiSupport.userToken;
import static com.innowise.orderservice.integration.ApiSupport.userTokenWithoutProfile;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Who may see and change which order, with two users, an admin with a profile and an admin
 * without one. The rule: an order of somebody else looks exactly like a missing order (404), and
 * a refused request changes nothing and does not even ask the User Service anything.
 *
 * <p>Anna (profile 7) owns order 1, Boris (profile 8) owns order 2. The default caller of
 * {@code mockMvc} is an admin without a profile.
 */
class OrderOwnershipIntegrationTest extends AbstractIntegrationTest {

  private static final String ANNA = annaToken();
  private static final String BORIS = borisToken();

  @BeforeEach
  void placeTheOrders() throws Exception {
    insertItem("Keyboard", "49.90");
    insertItem("Mouse", "19.90");
    UserServiceStub.respondWithUser(ANNA_EMAIL, 7, "Anna", "Petrova");
    UserServiceStub.respondWithUser(BORIS_EMAIL, 8, "Boris", "Ivanov");
    placeOrder(mockMvc, ANNA, orderJson(1, 1));
    placeOrder(mockMvc, BORIS, orderJson(2, 1));
  }

  // ---- a user and the order of somebody else

  @Test
  void aUserReadsHisOwnOrderButTheOrderOfSomebodyElseLooksMissing() throws Exception {
    mockMvc.perform(as(get("/api/orders/1"), ANNA)).andExpectAll(
      status().isOk(),
      jsonPath("$.userId").value(7));
    int askedBefore = UserServiceStub.requestsReceived();

    MvcResult foreign = mockMvc.perform(as(get("/api/orders/2"), ANNA)).andExpectAll(
      status().isNotFound(),
      problemJson(),
      jsonPath("$.detail").value("Order with id 2 not found"),
      content().string(not(containsString("Boris"))),
      content().string(not(containsString(BORIS_EMAIL)))).andReturn();
    MvcResult missing = mockMvc.perform(as(get("/api/orders/999"), ANNA)).andExpectAll(
      status().isNotFound(),
      jsonPath("$.detail").value("Order with id 999 not found")).andReturn();

    assertAll(
      // Apart from the id in the text, the two answers are the same.
      () -> assertEquals(withoutIdentity(missing), withoutIdentity(foreign)),
      () -> assertEquals(askedBefore, UserServiceStub.requestsReceived(),
        "a refused request must not reach the User Service"));
  }

  @Test
  void aUserCannotChangeCancelOrDeleteTheOrderOfSomebodyElseAndNothingChanges()
      throws Exception {
    List<Object> before = snapshot(2);

    mockMvc.perform(json(as(put("/api/orders/2"), ANNA), orderJson(1, 3)))
      .andExpect(status().isNotFound());
    mockMvc.perform(as(patch("/api/orders/2/cancel"), ANNA)).andExpect(status().isNotFound());
    mockMvc.perform(as(delete("/api/orders/2"), ANNA)).andExpect(status().isNotFound());

    assertEquals(before, snapshot(2));
  }

  @Test
  void aPutCannotMoveAnOrderToAnotherOwner() throws Exception {
    String body = """
      {"userId": 8, "items": [{"itemId": 2, "quantity": 2}]}
      """;

    mockMvc.perform(json(as(put("/api/orders/1"), ANNA), body)).andExpectAll(
      status().isOk(),
      jsonPath("$.userId").value(7),
      jsonPath("$.user.name").value("Anna"));

    assertEquals(7L, jdbcTemplate.queryForObject(
      "SELECT user_id FROM orders WHERE id = 1", Long.class));
  }

  // ---- the lists

  @Test
  void aUserListsOnlyHisOwnOrdersAndCannotUseTheListOfEverybody() throws Exception {
    mockMvc.perform(as(get("/api/orders/my"), ANNA)).andExpectAll(
      status().isOk(),
      jsonPath("$.totalElements").value(1),
      jsonPath("$.content[0].id").value(1));
    mockMvc.perform(as(get("/api/orders"), ANNA)).andExpectAll(
      status().isForbidden(),
      problemJson(),
      jsonPath("$.detail").value("Access is denied"));
    mockMvc.perform(get("/api/orders")).andExpectAll(
      status().isOk(),
      jsonPath("$.totalElements").value(2));
  }

  // ---- admins

  @Test
  void anAdminWithAProfileReadsAndChangesTheOrdersOfEverybody() throws Exception {
    String boss = bossToken();
    UserServiceStub.respondWithUser("boss@example.com", 1, "Boss", "Bossov");

    mockMvc.perform(as(get("/api/orders/1"), boss)).andExpect(status().isOk());
    mockMvc.perform(as(get("/api/orders/2"), boss)).andExpect(status().isOk());
    mockMvc.perform(json(as(put("/api/orders/1"), boss), orderJson(2, 2))).andExpectAll(
      status().isOk(),
      jsonPath("$.userId").value(7));
    mockMvc.perform(as(patch("/api/orders/2/cancel"), boss)).andExpectAll(
      status().isOk(),
      jsonPath("$.status").value("CANCELLED"));
    mockMvc.perform(as(delete("/api/orders/1"), boss)).andExpect(status().isNoContent());

    assertAll(
      () -> assertEquals("CANCELLED", jdbcTemplate.queryForObject(
        "SELECT status FROM orders WHERE id = 2", String.class)),
      () -> assertEquals(true, jdbcTemplate.queryForObject(
        "SELECT deleted FROM orders WHERE id = 1", Boolean.class)));
  }

  @Test
  void anAdminWithoutAProfileManagesEveryOrderButCannotPlaceOneOrHaveOrders() throws Exception {
    // The default caller of mockMvc: an admin whose token has no user_id.
    mockMvc.perform(get("/api/orders/1")).andExpect(status().isOk());
    mockMvc.perform(json(put("/api/orders/2"), orderJson(1, 1))).andExpect(status().isOk());
    mockMvc.perform(patch("/api/orders/1/cancel")).andExpect(status().isOk());
    mockMvc.perform(delete("/api/orders/2")).andExpect(status().isNoContent());

    mockMvc.perform(json(post("/api/orders"), orderJson(1, 1))).andExpectAll(
      status().isForbidden(), problemJson());
    mockMvc.perform(get("/api/orders/my")).andExpectAll(
      status().isForbidden(), problemJson());

    assertEquals(2L, countRows("orders"), "a refused order must not be saved");
  }

  // ---- the identity of the caller

  @Test
  void aTokenWithoutAProfileOwnsNothingEvenWithTheEmailOfAnOwner() throws Exception {
    String token = userTokenWithoutProfile(ANNA_EMAIL);

    mockMvc.perform(as(get("/api/orders/1"), token)).andExpect(status().isNotFound());
    mockMvc.perform(json(as(put("/api/orders/1"), token), orderJson(1, 1)))
      .andExpect(status().isNotFound());
    mockMvc.perform(as(patch("/api/orders/1/cancel"), token)).andExpect(status().isNotFound());
    mockMvc.perform(as(delete("/api/orders/1"), token)).andExpect(status().isNotFound());
    mockMvc.perform(json(as(post("/api/orders"), token), orderJson(1, 1)))
      .andExpect(status().isForbidden());
    mockMvc.perform(as(get("/api/orders/my"), token)).andExpect(status().isForbidden());
    // The catalogue is open to every role.
    mockMvc.perform(as(get("/api/items"), token)).andExpect(status().isOk());

    assertEquals(2L, countRows("orders"));
  }

  @Test
  void theOwnerIsDecidedByTheProfileIdAndNotByTheEmail() throws Exception {
    // Anna's email with another profile: not her order.
    String namesake = userToken(9, ANNA_EMAIL);
    // Anna's profile with another email: still her order.
    String sameProfile = userToken(7, "other-address@example.com");

    mockMvc.perform(as(get("/api/orders/1"), namesake)).andExpect(status().isNotFound());
    mockMvc.perform(as(delete("/api/orders/1"), namesake)).andExpect(status().isNotFound());
    mockMvc.perform(as(get("/api/orders/1"), sameProfile)).andExpect(status().isOk());
  }

  // ---- helpers

  /** The answer without the parts that differ by the id in the request: detail and instance. */
  private static Map<String, Object> withoutIdentity(MvcResult result) throws Exception {
    Map<String, Object> body = JsonPath.read(bodyOf(result), "$");
    body.remove("detail");
    body.remove("instance");
    return body;
  }

  /** The row of an order and its lines, to prove that a refused request changed nothing. */
  private List<Object> snapshot(long orderId) {
    Map<String, Object> order = jdbcTemplate.queryForMap(
      "SELECT status, deleted, total_price, version, updated_at FROM orders WHERE id = ?",
      orderId);
    List<Map<String, Object>> lines = jdbcTemplate.queryForList(
      "SELECT item_id, quantity, unit_price FROM order_items WHERE order_id = ? ORDER BY id",
      orderId);
    return List.of(order, lines);
  }
}
