package com.innowise.orderservice.mapper;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.innowise.orderservice.TestEntities;
import com.innowise.orderservice.dto.request.OrderItemRequest;
import com.innowise.orderservice.dto.response.OrderDetailsResponse;
import com.innowise.orderservice.dto.response.OrderItemResponse;
import com.innowise.orderservice.dto.response.OrderResponse;
import com.innowise.orderservice.dto.response.UserInfo;
import com.innowise.orderservice.entity.Item;
import com.innowise.orderservice.entity.Order;
import com.innowise.orderservice.entity.OrderItem;
import com.innowise.orderservice.entity.OrderStatus;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/** Tests of the generated {@link OrderMapper}; no database is involved. */
@SpringJUnitConfig(OrderMapperImpl.class)
class OrderMapperTest {

  @Autowired
  private OrderMapper mapper;

  @Test
  void toResponseMapsTheOrderWithItsLines() {
    Item keyboard = TestEntities.item(3L, "Keyboard", "59.90");
    Item mouse = TestEntities.item(4L, "Mouse", "19.90");
    Order order = TestEntities.order(12L, 7L, "buyer@example.com",
      TestEntities.line(keyboard, 2, "49.90"),
      TestEntities.line(mouse, 1, "19.90"));

    OrderResponse response = mapper.toResponse(order);

    assertAll(
      () -> assertEquals(12L, response.id()),
      () -> assertEquals(7L, response.userId()),
      () -> assertEquals("buyer@example.com", response.userEmail()),
      () -> assertEquals(OrderStatus.PENDING, response.status()),
      () -> assertEquals(new BigDecimal("119.70"), response.totalPrice()),
      () -> assertEquals(TestEntities.CREATED_AT, response.createdAt()),
      () -> assertEquals(TestEntities.UPDATED_AT, response.updatedAt()),
      () -> assertEquals(2, response.items().size()),
      () -> assertEquals(new OrderItemResponse(3L, "Keyboard", 2, new BigDecimal("49.90"),
        new BigDecimal("99.80")), response.items().get(0)),
      () -> assertEquals(new OrderItemResponse(4L, "Mouse", 1, new BigDecimal("19.90"),
        new BigDecimal("19.90")), response.items().get(1)));
  }

  @Test
  void theLineShowsThePriceOfTheOrderNotTheCurrentPriceOfTheItem() {
    Item keyboard = TestEntities.item(3L, "Keyboard", "59.90");
    Order order = TestEntities.order(12L, 7L, "buyer@example.com",
      TestEntities.line(keyboard, 1, "49.90"));

    assertEquals(new BigDecimal("49.90"), mapper.toResponse(order).items().get(0).unitPrice());
  }

  @Test
  void theEmailIsNotPartOfTheStringForm() {
    Order order = TestEntities.order(12L, 7L, "buyer@example.com");

    assertFalse(mapper.toResponse(order).toString().contains("buyer"));
  }

  @Test
  void toLineBuildsALineWithTheGivenItemAndPrice() {
    Item keyboard = TestEntities.item(3L, "Keyboard", "59.90");

    OrderItem line = mapper.toLine(new OrderItemRequest(3L, 4), keyboard, new BigDecimal("59.90"));

    assertAll(
      () -> assertSame(keyboard, line.getItem()),
      () -> assertEquals(4, line.getQuantity()),
      () -> assertEquals(new BigDecimal("59.90"), line.getUnitPrice()),
      () -> assertNull(line.getOrder()),
      () -> assertNull(line.getId()));
  }

  @Test
  void toDetailsKeepsTheOrderAndAddsTheOwner() {
    Item keyboard = TestEntities.item(3L, "Keyboard", "59.90");
    OrderResponse order = mapper.toResponse(TestEntities.order(12L, 7L, "buyer@example.com",
      TestEntities.line(keyboard, 2, "49.90")));
    UserInfo owner = new UserInfo(7L, "Anna", "Petrova", "buyer@example.com");

    OrderDetailsResponse details = mapper.toDetails(order, owner, true);

    assertAll(
      // The id of the order, not the id of the user: both records have one.
      () -> assertEquals(12L, details.id()),
      () -> assertEquals(7L, details.userId()),
      () -> assertEquals(OrderStatus.PENDING, details.status()),
      () -> assertEquals(new BigDecimal("99.80"), details.totalPrice()),
      () -> assertEquals(order.items(), details.items()),
      () -> assertEquals(TestEntities.CREATED_AT, details.createdAt()),
      () -> assertEquals(TestEntities.UPDATED_AT, details.updatedAt()),
      () -> assertSame(owner, details.user()),
      () -> assertTrue(details.userInfoAvailable()));
  }

  @Test
  void toDetailsWithoutAnOwnerKeepsTheFlag() {
    OrderResponse order = mapper.toResponse(TestEntities.order(12L, 7L, "buyer@example.com"));

    OrderDetailsResponse gone = mapper.toDetails(order, null, true);
    OrderDetailsResponse unavailable = mapper.toDetails(order, null, false);

    assertAll(
      () -> assertNull(gone.user()),
      () -> assertTrue(gone.userInfoAvailable()),
      () -> assertNull(unavailable.user()),
      () -> assertFalse(unavailable.userInfoAvailable()));
  }
}
