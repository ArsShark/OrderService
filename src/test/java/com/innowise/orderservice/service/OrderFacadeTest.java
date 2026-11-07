package com.innowise.orderservice.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.innowise.orderservice.TestEntities;
import com.innowise.orderservice.dto.request.OrderItemRequest;
import com.innowise.orderservice.dto.request.OrderRequest;
import com.innowise.orderservice.dto.response.OrderDetailsResponse;
import com.innowise.orderservice.dto.response.OrderItemResponse;
import com.innowise.orderservice.dto.response.OrderResponse;
import com.innowise.orderservice.dto.response.UserInfo;
import com.innowise.orderservice.entity.OrderStatus;
import com.innowise.orderservice.mapper.OrderMapperImpl;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests of {@link OrderFacade}: the order service and the resolver are mocks, the mapper is
 * the real one. What the facade promises is the combination: the data of the order always comes
 * through, and the owner is added according to the three results of the lookup.
 */
@ExtendWith(MockitoExtension.class)
class OrderFacadeTest {

  private static final Long ORDER_ID = 5L;
  private static final Caller OWNER = new Caller(7L, "anna@example.com", false);
  private static final UserInfo ANNA = new UserInfo(7L, "Anna", "Petrova", "anna@example.com");
  private static final OrderRequest REQUEST =
    new OrderRequest(List.of(new OrderItemRequest(1L, 1)));

  @Mock
  private OrderService orderService;

  @Mock
  private UserInfoResolver userInfoResolver;

  private OrderFacade facade;

  @BeforeEach
  void createFacade() {
    facade = new OrderFacade(orderService, userInfoResolver, new OrderMapperImpl());
  }

  @Test
  void aCreatedOrderComesWithItsOwner() {
    when(orderService.create(OWNER, REQUEST)).thenReturn(order(ORDER_ID, 7L, "anna@example.com"));
    when(userInfoResolver.lookup("anna@example.com")).thenReturn(UserLookup.found(ANNA));

    OrderDetailsResponse details = facade.create(OWNER, REQUEST);

    assertAll(
      () -> assertEquals(ORDER_ID, details.id()),
      () -> assertEquals(7L, details.userId()),
      () -> assertEquals(new BigDecimal("49.90"), details.totalPrice()),
      () -> assertEquals(1, details.items().size()),
      () -> assertEquals(ANNA, details.user()),
      () -> assertTrue(details.userInfoAvailable()));
  }

  @Test
  void anOrderWhoseOwnerHasNoProfileAnyMoreComesWithoutOwnerButWithAnAnswer() {
    when(orderService.getById(ORDER_ID, OWNER)).thenReturn(order(ORDER_ID, 7L, "anna@example.com"));
    when(userInfoResolver.lookup("anna@example.com")).thenReturn(UserLookup.notFound());

    OrderDetailsResponse details = facade.getById(ORDER_ID, OWNER);

    assertAll(
      () -> assertNull(details.user()),
      () -> assertTrue(details.userInfoAvailable()),
      () -> assertEquals(ORDER_ID, details.id()));
  }

  @Test
  void anOrderIsStillCompleteWhenTheUserServiceCannotAnswer() {
    when(orderService.update(ORDER_ID, OWNER, REQUEST))
      .thenReturn(order(ORDER_ID, 7L, "anna@example.com"));
    when(userInfoResolver.lookup("anna@example.com")).thenReturn(UserLookup.unavailable());

    OrderDetailsResponse details = facade.update(ORDER_ID, OWNER, REQUEST);

    assertAll(
      () -> assertNull(details.user()),
      () -> assertFalse(details.userInfoAvailable()),
      () -> assertEquals(OrderStatus.PENDING, details.status()),
      () -> assertEquals(new BigDecimal("49.90"), details.totalPrice()),
      () -> assertEquals(1, details.items().size()));
  }

  @Test
  void aCancelledOrderComesWithItsOwner() {
    when(orderService.cancel(ORDER_ID, OWNER)).thenReturn(
      new OrderResponse(ORDER_ID, 7L, "anna@example.com", OrderStatus.CANCELLED,
        new BigDecimal("49.90"), List.of(), TestEntities.CREATED_AT, TestEntities.UPDATED_AT));
    when(userInfoResolver.lookup("anna@example.com")).thenReturn(UserLookup.found(ANNA));

    OrderDetailsResponse details = facade.cancel(ORDER_ID, OWNER);

    assertAll(
      () -> assertEquals(OrderStatus.CANCELLED, details.status()),
      () -> assertEquals(ANNA, details.user()));
  }

  @Test
  void aPageAsksForEveryOwnerAndKeepsTheOrderAndThePageData() {
    Pageable pageable = PageRequest.of(0, 3);
    List<OrderResponse> content = List.of(
      order(1L, 7L, "anna@example.com"),
      order(2L, 8L, "boris@example.com"),
      order(3L, 7L, "anna@example.com"));
    UserInfo boris = new UserInfo(8L, "Boris", "Ivanov", "boris@example.com");
    when(orderService.findAll(any(), any())).thenReturn(new PageImpl<>(content, pageable, 9));
    when(userInfoResolver.lookup(List.of("anna@example.com", "boris@example.com",
      "anna@example.com"))).thenReturn(Map.of(
        "anna@example.com", UserLookup.found(ANNA),
        "boris@example.com", UserLookup.unavailable()));

    Page<OrderDetailsResponse> page = facade.findAll(
      new OrderFilter(null, null, null, null), pageable);

    assertAll(
      () -> assertEquals(List.of(1L, 2L, 3L),
        page.getContent().stream().map(OrderDetailsResponse::id).toList()),
      () -> assertEquals(ANNA, page.getContent().get(0).user()),
      () -> assertNull(page.getContent().get(1).user()),
      () -> assertFalse(page.getContent().get(1).userInfoAvailable()),
      () -> assertEquals(ANNA, page.getContent().get(2).user()),
      () -> assertEquals(9, page.getTotalElements()),
      () -> assertEquals(3, page.getTotalPages()),
      () -> assertEquals(boris.id(), page.getContent().get(1).userId()));
  }

  @Test
  void anOwnersPageIsReadThroughFindOwn() {
    Pageable pageable = PageRequest.of(0, 20);
    OrderFilter filter = new OrderFilter(null, null, null, null);
    when(orderService.findOwn(7L, filter, pageable)).thenReturn(
      new PageImpl<>(List.of(order(1L, 7L, "anna@example.com")), pageable, 1));
    when(userInfoResolver.lookup(List.of("anna@example.com")))
      .thenReturn(Map.of("anna@example.com", UserLookup.found(ANNA)));

    Page<OrderDetailsResponse> page = facade.findOwn(7L, filter, pageable);

    assertEquals(ANNA, page.getContent().get(0).user());
  }

  @Test
  void anEmptyPageStaysEmpty() {
    Pageable pageable = PageRequest.of(0, 20);
    when(orderService.findAll(any(), any())).thenReturn(Page.empty(pageable));

    Page<OrderDetailsResponse> page = facade.findAll(
      new OrderFilter(null, null, null, null), pageable);

    assertTrue(page.isEmpty());
  }

  @Test
  void aDeletionDelegatesAndNeverAsksTheUserService() {
    facade.delete(ORDER_ID, OWNER);

    assertAll(
      () -> verify(orderService).delete(ORDER_ID, OWNER),
      () -> verifyNoInteractions(userInfoResolver));
  }

  private static OrderResponse order(Long id, Long userId, String email) {
    return new OrderResponse(id, userId, email, OrderStatus.PENDING, new BigDecimal("49.90"),
      List.of(new OrderItemResponse(1L, "Keyboard", 1, new BigDecimal("49.90"),
        new BigDecimal("49.90"))),
      TestEntities.CREATED_AT, TestEntities.UPDATED_AT);
  }
}
