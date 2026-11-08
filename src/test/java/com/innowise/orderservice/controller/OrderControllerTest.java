package com.innowise.orderservice.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.innowise.orderservice.config.ClockConfig;
import com.innowise.orderservice.config.JwtConfig;
import com.innowise.orderservice.config.SecurityConfig;
import com.innowise.orderservice.dto.request.OrderItemRequest;
import com.innowise.orderservice.dto.request.OrderRequest;
import com.innowise.orderservice.dto.response.OrderDetailsResponse;
import com.innowise.orderservice.dto.response.OrderItemResponse;
import com.innowise.orderservice.dto.response.UserInfo;
import com.innowise.orderservice.entity.OrderStatus;
import com.innowise.orderservice.exception.ItemNotAvailableException;
import com.innowise.orderservice.exception.OrderNotDeletableException;
import com.innowise.orderservice.exception.OrderNotEditableException;
import com.innowise.orderservice.exception.OrderNotFoundException;
import com.innowise.orderservice.exception.OrderStatusTransitionException;
import com.innowise.orderservice.exception.OrderTotalTooLargeException;
import com.innowise.orderservice.service.Caller;
import com.innowise.orderservice.service.OrderFacade;
import com.innowise.orderservice.service.OrderFilter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * HTTP contract tests of {@link OrderController}: paths, status codes, headers, JSON shape, and
 * what the controller makes of the token. The facade is a mock, so these tests do not touch a
 * database; the full flow is covered by the integration tests.
 *
 * <p>The security chain is real. The token is placed in the security context by the
 * {@code jwt()} post-processor, so the decoder is not used, but the controller sees exactly what
 * it sees in production: a {@code Jwt} principal with its claims and authorities.
 */
@WebMvcTest(controllers = OrderController.class,
  excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import({SecurityConfig.class, JwtConfig.class, ClockConfig.class})
class OrderControllerTest {

  private static final Instant NOW = Instant.parse("2030-01-01T10:15:30Z");

  private static final String VALID_ORDER_JSON = """
    {"items": [{"itemId": 1, "quantity": 2}, {"itemId": 2, "quantity": 1}]}
    """;

  private static final Caller BUYER = new Caller(7L, "buyer@example.com", false);

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private OrderFacade orderFacade;

  private static RequestPostProcessor user() {
    return jwt().jwt(token -> token.claim("user_id", 7L).claim("email", "buyer@example.com"))
      .authorities(new SimpleGrantedAuthority("ROLE_USER"));
  }

  private static RequestPostProcessor admin() {
    return jwt().jwt(token -> token.claim("user_id", 1L).claim("email", "admin@example.com"))
      .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  private static RequestPostProcessor adminWithoutProfile() {
    return jwt().jwt(token -> token.claim("email", "admin@example.com"))
      .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  private static OrderDetailsResponse order(long id) {
    return new OrderDetailsResponse(id, 7L, OrderStatus.PENDING, new BigDecimal("99.80"),
      List.of(new OrderItemResponse(1L, "Keyboard", 2, new BigDecimal("49.90"), new BigDecimal("99.80"))),
      NOW, NOW, new UserInfo(7L, "Anna", "Petrova", "buyer@example.com"), true);
  }

  private static PageImpl<OrderDetailsResponse> pageOf(OrderDetailsResponse... orders) {
    return new PageImpl<>(List.of(orders), PageRequest.of(0, 20), orders.length);
  }

  // ---- create

  @Test
  void createReturns201WithLocationBodyAndTheOwnerFromTheToken() throws Exception {
    when(orderFacade.create(eq(BUYER), any(OrderRequest.class))).thenReturn(order(5));

    mockMvc.perform(post("/api/orders").with(user())
      .contentType(MediaType.APPLICATION_JSON)
      .content(VALID_ORDER_JSON)).andExpectAll(
      status().isCreated(),
      header().string(HttpHeaders.LOCATION, "http://localhost/api/orders/5"),
      jsonPath("$.id").value(5),
      jsonPath("$.status").value("PENDING"),
      jsonPath("$.totalPrice").value(99.80),
      jsonPath("$.items[0].itemName").value("Keyboard"),
      jsonPath("$.user.email").value("buyer@example.com"),
      jsonPath("$.userInfoAvailable").value(true));

    ArgumentCaptor<OrderRequest> request = ArgumentCaptor.forClass(OrderRequest.class);
    verify(orderFacade).create(eq(BUYER), request.capture());
    assertEquals(new OrderRequest(List.of(new OrderItemRequest(1L, 2),
      new OrderItemRequest(2L, 1))), request.getValue());
  }

  /** The body has no owner field: a {@code userId} that a client adds is not used. */
  @Test
  void createTakesTheOwnerFromTheTokenWhateverTheBodySays() throws Exception {
    when(orderFacade.create(any(Caller.class), any(OrderRequest.class))).thenReturn(order(5));
    String body = """
      {"userId": 99, "totalPrice": 0.01, "items": [{"itemId": 1, "quantity": 1}]}
      """;

    mockMvc.perform(post("/api/orders").with(user())
      .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());

    verify(orderFacade).create(eq(BUYER), any(OrderRequest.class));
  }

  @Test
  void anAdminWithAProfileMayPlaceAnOrderForHimself() throws Exception {
    when(orderFacade.create(any(Caller.class), any(OrderRequest.class))).thenReturn(order(5));

    mockMvc.perform(post("/api/orders").with(admin())
      .contentType(MediaType.APPLICATION_JSON)
      .content(VALID_ORDER_JSON)).andExpect(status().isCreated());

    verify(orderFacade).create(eq(new Caller(1L, "admin@example.com", true)),
      any(OrderRequest.class));
  }

  @Test
  void createIsRefusedWith403ForATokenWithoutAProfileAndTheFacadeIsNeverCalled()
      throws Exception {
    mockMvc.perform(post("/api/orders").with(adminWithoutProfile())
      .contentType(MediaType.APPLICATION_JSON)
      .content(VALID_ORDER_JSON)).andExpectAll(
      status().isForbidden(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.detail").value("Access is denied"));

    verifyNoInteractions(orderFacade);
  }

  @Test
  void createRejectsAnInvalidBodyWith400AndNeverEchoesTheValues() throws Exception {
    String body = """
      {"items": [{"itemId": 1, "quantity": 5000}, {"itemId": -4, "quantity": 0}]}
      """;

    mockMvc.perform(post("/api/orders").with(user())
      .contentType(MediaType.APPLICATION_JSON).content(body)).andExpectAll(
      status().isBadRequest(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.errors[0].field").value("items[0].quantity"),
      content().string(not(containsString("5000"))));

    verifyNoInteractions(orderFacade);
  }

  @Test
  void createRejectsAnEmptyListAndARepeatedItemWith400() throws Exception {
    mockMvc.perform(post("/api/orders").with(user())
      .contentType(MediaType.APPLICATION_JSON).content("{\"items\": []}"))
      .andExpectAll(status().isBadRequest(), jsonPath("$.errors[0].field").value("items"));
    mockMvc.perform(post("/api/orders").with(user())
      .contentType(MediaType.APPLICATION_JSON)
      .content("{\"items\": [{\"itemId\": 1, \"quantity\": 1}, {\"itemId\": 1, \"quantity\": 2}]}"))
      .andExpectAll(status().isBadRequest(), jsonPath("$.errors[0].field").value("items"));

    verifyNoInteractions(orderFacade);
  }

  @Test
  void createAnswers422WhenAnItemIsNotAvailableOrTheTotalIsTooLarge() throws Exception {
    when(orderFacade.create(any(Caller.class), any(OrderRequest.class)))
      .thenThrow(new ItemNotAvailableException(2L))
      .thenThrow(new OrderTotalTooLargeException());

    mockMvc.perform(post("/api/orders").with(user())
      .contentType(MediaType.APPLICATION_JSON).content(VALID_ORDER_JSON)).andExpectAll(
      status().is(422),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.detail").value("Item with id 2 is not available"));
    mockMvc.perform(post("/api/orders").with(user())
      .contentType(MediaType.APPLICATION_JSON).content(VALID_ORDER_JSON))
      .andExpect(status().is(422));
  }

  // ---- get by id

  @Test
  void getByIdReturns200AndPassesTheCaller() throws Exception {
    when(orderFacade.getById(5L, BUYER)).thenReturn(order(5));

    mockMvc.perform(get("/api/orders/5").with(user())).andExpectAll(
      status().isOk(),
      jsonPath("$.id").value(5),
      jsonPath("$.user.name").value("Anna"));
  }

  @Test
  void getByIdLetsAnAdminWithoutAProfileThrough() throws Exception {
    when(orderFacade.getById(5L, new Caller(null, "admin@example.com", true)))
      .thenReturn(order(5));

    mockMvc.perform(get("/api/orders/5").with(adminWithoutProfile()))
      .andExpect(status().isOk());
  }

  @Test
  void getByIdAnswers404WhenTheOrderIsMissingOrNotTheCallers() throws Exception {
    when(orderFacade.getById(9L, BUYER)).thenThrow(new OrderNotFoundException(9L));

    mockMvc.perform(get("/api/orders/9").with(user())).andExpectAll(
      status().isNotFound(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.detail").value("Order with id 9 not found"));
  }

  @Test
  void getByIdAnswers400ForANonNumericId() throws Exception {
    mockMvc.perform(get("/api/orders/abc").with(user())).andExpectAll(
      status().isBadRequest(),
      jsonPath("$.detail").value("Invalid value of the parameter 'id'"));

    verifyNoInteractions(orderFacade);
  }

  // ---- the list of everybody's orders (admin)

  @Test
  void getAllReturnsAPageInTheStablePageShape() throws Exception {
    when(orderFacade.findAll(any(OrderFilter.class), any(Pageable.class)))
      .thenReturn(pageOf(order(1), order(2)));

    mockMvc.perform(get("/api/orders").with(admin())).andExpectAll(
      status().isOk(),
      jsonPath("$.content.length()").value(2),
      jsonPath("$.page").value(0),
      jsonPath("$.size").value(20),
      jsonPath("$.totalElements").value(2),
      jsonPath("$.totalPages").value(1));
  }

  @Test
  void getAllBuildsTheFilterFromTheParameters() throws Exception {
    when(orderFacade.findAll(any(OrderFilter.class), any(Pageable.class)))
      .thenReturn(pageOf());

    mockMvc.perform(get("/api/orders").with(admin())
      .param("createdFrom", "2026-11-01T00:00:00Z")
      .param("createdBefore", "2026-11-08T00:00:00Z")
      .param("statuses", "PENDING,PAID")
      .param("userId", "7")).andExpect(status().isOk());

    ArgumentCaptor<OrderFilter> filter = ArgumentCaptor.forClass(OrderFilter.class);
    verify(orderFacade).findAll(filter.capture(), any(Pageable.class));
    assertEquals(new OrderFilter(Instant.parse("2026-11-01T00:00:00Z"),
      Instant.parse("2026-11-08T00:00:00Z"), Set.of(OrderStatus.PENDING, OrderStatus.PAID), 7L),
      filter.getValue());
  }

  @Test
  void getAllAcceptsRepeatedStatusParameters() throws Exception {
    when(orderFacade.findAll(any(OrderFilter.class), any(Pageable.class)))
      .thenReturn(pageOf());

    mockMvc.perform(get("/api/orders").with(admin())
      .param("statuses", "PAID").param("statuses", "CANCELLED")).andExpect(status().isOk());

    ArgumentCaptor<OrderFilter> filter = ArgumentCaptor.forClass(OrderFilter.class);
    verify(orderFacade).findAll(filter.capture(), any(Pageable.class));
    assertEquals(Set.of(OrderStatus.PAID, OrderStatus.CANCELLED), filter.getValue().statuses());
  }

  @Test
  void getAllSortsByTheNewestOrderFirstAndAppendsIdAsTheTiebreaker() throws Exception {
    when(orderFacade.findAll(any(OrderFilter.class), any(Pageable.class)))
      .thenReturn(pageOf());

    mockMvc.perform(get("/api/orders").with(admin())).andExpect(status().isOk());

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(orderFacade).findAll(any(OrderFilter.class), pageable.capture());
    assertAll(
      () -> assertEquals(20, pageable.getValue().getPageSize()),
      () -> assertEquals(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")),
        pageable.getValue().getSort()));
  }

  @Test
  void getAllCapsThePageSizeAt100() throws Exception {
    when(orderFacade.findAll(any(OrderFilter.class), any(Pageable.class)))
      .thenReturn(pageOf());

    mockMvc.perform(get("/api/orders").with(admin()).param("size", "1000"))
      .andExpect(status().isOk());

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(orderFacade).findAll(any(OrderFilter.class), pageable.capture());
    assertEquals(100, pageable.getValue().getPageSize());
  }

  @Test
  void getAllRejectsAnUnknownSortPropertyAndABadParameterWithoutEchoingThem() throws Exception {
    mockMvc.perform(get("/api/orders").with(admin()).param("sort", "userEmail")).andExpectAll(
      status().isBadRequest(),
      content().string(not(containsString("userEmail"))));
    mockMvc.perform(get("/api/orders").with(admin()).param("statuses", "PAID,BOGUS-STATUS"))
      .andExpectAll(
        status().isBadRequest(),
        jsonPath("$.detail").value("Invalid value of the parameter 'statuses'"),
        content().string(not(containsString("BOGUS-STATUS"))));
    mockMvc.perform(get("/api/orders").with(admin()).param("createdFrom", "yesterday"))
      .andExpectAll(
        status().isBadRequest(),
        jsonPath("$.detail").value("Invalid value of the parameter 'createdFrom'"));

    verifyNoInteractions(orderFacade);
  }

  @Test
  void getAllRejectsAnEmptyRangeWith400() throws Exception {
    mockMvc.perform(get("/api/orders").with(admin())
      .param("createdFrom", "2026-11-08T00:00:00Z")
      .param("createdBefore", "2026-11-08T00:00:00Z")).andExpectAll(
      status().isBadRequest(),
      jsonPath("$.detail").value("createdFrom must be before createdBefore"));

    verifyNoInteractions(orderFacade);
  }

  @Test
  void getAllIsForAnAdminOnlyAndAUserGets403() throws Exception {
    mockMvc.perform(get("/api/orders").with(user())).andExpectAll(
      status().isForbidden(),
      jsonPath("$.detail").value("Access is denied"));

    verifyNoInteractions(orderFacade);
  }

  // ---- own orders

  @Test
  void getMyAsksForTheOwnOrdersOfTheProfileInTheToken() throws Exception {
    when(orderFacade.findOwn(eq(7L), any(OrderFilter.class), any(Pageable.class)))
      .thenReturn(pageOf(order(1)));

    mockMvc.perform(get("/api/orders/my").with(user())
      .param("statuses", "PAID")).andExpectAll(
      status().isOk(),
      jsonPath("$.content.length()").value(1));

    ArgumentCaptor<OrderFilter> filter = ArgumentCaptor.forClass(OrderFilter.class);
    verify(orderFacade).findOwn(eq(7L), filter.capture(), any(Pageable.class));
    assertEquals(Set.of(OrderStatus.PAID), filter.getValue().statuses());
  }

  /** There is no {@code userId} parameter on this endpoint: the owner is always the caller. */
  @Test
  void getMyIgnoresAUserIdParameter() throws Exception {
    when(orderFacade.findOwn(eq(7L), any(OrderFilter.class), any(Pageable.class)))
      .thenReturn(pageOf());

    mockMvc.perform(get("/api/orders/my").with(user()).param("userId", "8"))
      .andExpect(status().isOk());

    ArgumentCaptor<OrderFilter> filter = ArgumentCaptor.forClass(OrderFilter.class);
    verify(orderFacade).findOwn(eq(7L), filter.capture(), any(Pageable.class));
    assertNull(filter.getValue().userId());
  }

  @Test
  void getMyIsRefusedWith403ForATokenWithoutAProfileInsteadOfListingEveryOrder()
      throws Exception {
    mockMvc.perform(get("/api/orders/my").with(adminWithoutProfile())).andExpectAll(
      status().isForbidden(),
      jsonPath("$.detail").value("Access is denied"));

    verifyNoInteractions(orderFacade);
  }

  @Test
  void getMyDoesNotAskForAnOrderById() throws Exception {
    when(orderFacade.findOwn(eq(7L), any(OrderFilter.class), any(Pageable.class)))
      .thenReturn(pageOf());

    mockMvc.perform(get("/api/orders/my").with(user())).andExpect(status().isOk());

    verify(orderFacade, never()).getById(any(), any());
  }

  // ---- update

  @Test
  void updateReturns200AndPassesTheCallerAndTheRequest() throws Exception {
    when(orderFacade.update(eq(5L), eq(BUYER), any(OrderRequest.class))).thenReturn(order(5));

    mockMvc.perform(put("/api/orders/5").with(user())
      .contentType(MediaType.APPLICATION_JSON)
      .content(VALID_ORDER_JSON)).andExpectAll(
      status().isOk(),
      jsonPath("$.id").value(5));
  }

  @Test
  void updateAnswers409WhenTheStatusDoesNotAllowIt() throws Exception {
    when(orderFacade.update(eq(5L), eq(BUYER), any(OrderRequest.class)))
      .thenThrow(new OrderNotEditableException(5L, OrderStatus.PAID));

    mockMvc.perform(put("/api/orders/5").with(user())
      .contentType(MediaType.APPLICATION_JSON)
      .content(VALID_ORDER_JSON)).andExpectAll(
      status().isConflict(),
      jsonPath("$.detail").value("Order with id 5 is PAID and cannot be changed"));
  }

  @Test
  void updateAnswers409ForALostUpdateWithAConstantDetail() throws Exception {
    when(orderFacade.update(eq(5L), eq(BUYER), any(OrderRequest.class)))
      .thenThrow(new OptimisticLockingFailureException("Order#5 was updated"));

    mockMvc.perform(put("/api/orders/5").with(user())
      .contentType(MediaType.APPLICATION_JSON)
      .content(VALID_ORDER_JSON)).andExpectAll(
      status().isConflict(),
      jsonPath("$.detail").value("The order was changed by another request, try again"),
      content().string(not(containsString("Order#5"))));
  }

  @Test
  void updateAnswers404AndRejectsAnInvalidBody() throws Exception {
    when(orderFacade.update(eq(9L), eq(BUYER), any(OrderRequest.class)))
      .thenThrow(new OrderNotFoundException(9L));

    mockMvc.perform(put("/api/orders/9").with(user())
      .contentType(MediaType.APPLICATION_JSON)
      .content(VALID_ORDER_JSON)).andExpect(status().isNotFound());
    mockMvc.perform(put("/api/orders/5").with(user())
      .contentType(MediaType.APPLICATION_JSON)
      .content("{}")).andExpect(status().isBadRequest());
  }

  // ---- cancel

  @Test
  void cancelReturns200() throws Exception {
    when(orderFacade.cancel(5L, BUYER)).thenReturn(order(5));

    mockMvc.perform(patch("/api/orders/5/cancel").with(user())).andExpectAll(
      status().isOk(),
      jsonPath("$.id").value(5));
  }

  @Test
  void cancelAnswers409ForAPaidOrderAnd404ForAMissingOne() throws Exception {
    when(orderFacade.cancel(5L, BUYER))
      .thenThrow(new OrderStatusTransitionException(OrderStatus.PAID, OrderStatus.CANCELLED));
    when(orderFacade.cancel(9L, BUYER)).thenThrow(new OrderNotFoundException(9L));

    mockMvc.perform(patch("/api/orders/5/cancel").with(user())).andExpectAll(
      status().isConflict(),
      jsonPath("$.detail").value("An order in the status PAID cannot move to CANCELLED"));
    mockMvc.perform(patch("/api/orders/9/cancel").with(user()))
      .andExpect(status().isNotFound());
  }

  // ---- delete

  @Test
  void deleteReturns204WithoutABody() throws Exception {
    mockMvc.perform(delete("/api/orders/5").with(user())).andExpectAll(
      status().isNoContent(),
      content().string(""));

    verify(orderFacade).delete(5L, BUYER);
  }

  @Test
  void deleteAnswers409ForAPaidOrderOfAUserAnd404ForAMissingOne() throws Exception {
    doThrow(new OrderNotDeletableException(5L, OrderStatus.PAID))
      .when(orderFacade).delete(5L, BUYER);
    doThrow(new OrderNotFoundException(9L)).when(orderFacade).delete(9L, BUYER);

    mockMvc.perform(delete("/api/orders/5").with(user())).andExpect(status().isConflict());
    mockMvc.perform(delete("/api/orders/9").with(user())).andExpect(status().isNotFound());
  }

  @Test
  void anAdminDeletesWithTheAdminRightInTheCaller() throws Exception {
    mockMvc.perform(delete("/api/orders/5").with(adminWithoutProfile()))
      .andExpect(status().isNoContent());

    verify(orderFacade).delete(5L, new Caller(null, "admin@example.com", true));
  }

  // ---- the chain in front of the controller

  @Test
  void aRequestWithoutATokenIs401AndNothingIsCalled() throws Exception {
    mockMvc.perform(get("/api/orders/5")).andExpectAll(
      status().isUnauthorized(),
      jsonPath("$.detail").value("Authentication is required"));

    verifyNoInteractions(orderFacade);
  }

  @Test
  void aMethodThatIsNotListedStaysClosed() throws Exception {
    mockMvc.perform(patch("/api/orders/5").with(user())).andExpect(status().isForbidden());
    mockMvc.perform(post("/api/orders/5/cancel").with(user())).andExpect(status().isForbidden());

    verifyNoInteractions(orderFacade);
  }
}
