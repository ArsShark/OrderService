package com.innowise.orderservice.entity;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.innowise.orderservice.exception.OrderStatusTransitionException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class OrderTest {

  @Test
  void aNewOrderIsPendingNotDeletedAndHasNoLines() {
    Order order = new Order();

    assertAll(
      () -> assertEquals(OrderStatus.PENDING, order.getStatus()),
      () -> assertFalse(order.isDeleted()),
      () -> assertEquals(List.of(), order.getItems()));
  }

  @Test
  void markDeletedFlagsTheOrderAndRepeatingItChangesNothing() {
    Order order = new Order();

    order.markDeleted();
    order.markDeleted();

    assertTrue(order.isDeleted());
  }

  @Test
  void addItemAndRemoveItemKeepBothSidesOfTheAssociationInStep() {
    Order order = new Order();
    OrderItem line = new OrderItem();

    order.addItem(line);
    assertAll(
      () -> assertEquals(List.of(line), order.getItems()),
      () -> assertSame(order, line.getOrder()));

    order.removeItem(line);
    assertAll(
      () -> assertEquals(List.of(), order.getItems()),
      () -> assertNull(line.getOrder()));
  }

  /** The whole table of the allowed transitions, every pair of statuses once. */
  static Stream<Arguments> transitions() {
    return Stream.of(
      Arguments.of(OrderStatus.PENDING, OrderStatus.PENDING, false),
      Arguments.of(OrderStatus.PENDING, OrderStatus.PAID, true),
      Arguments.of(OrderStatus.PENDING, OrderStatus.PAYMENT_FAILED, true),
      Arguments.of(OrderStatus.PENDING, OrderStatus.CANCELLED, true),
      Arguments.of(OrderStatus.PAYMENT_FAILED, OrderStatus.PENDING, false),
      Arguments.of(OrderStatus.PAYMENT_FAILED, OrderStatus.PAID, true),
      Arguments.of(OrderStatus.PAYMENT_FAILED, OrderStatus.PAYMENT_FAILED, true),
      Arguments.of(OrderStatus.PAYMENT_FAILED, OrderStatus.CANCELLED, true),
      Arguments.of(OrderStatus.PAID, OrderStatus.PENDING, false),
      Arguments.of(OrderStatus.PAID, OrderStatus.PAID, false),
      Arguments.of(OrderStatus.PAID, OrderStatus.PAYMENT_FAILED, false),
      Arguments.of(OrderStatus.PAID, OrderStatus.CANCELLED, false),
      Arguments.of(OrderStatus.CANCELLED, OrderStatus.PENDING, false),
      Arguments.of(OrderStatus.CANCELLED, OrderStatus.PAID, false),
      Arguments.of(OrderStatus.CANCELLED, OrderStatus.PAYMENT_FAILED, false),
      Arguments.of(OrderStatus.CANCELLED, OrderStatus.CANCELLED, false));
  }

  @ParameterizedTest(name = "{0} -> {1}: {2}")
  @MethodSource("transitions")
  void canMoveToFollowsTheTable(OrderStatus from, OrderStatus to, boolean allowed) {
    assertEquals(allowed, from.canMoveTo(to));
  }

  @ParameterizedTest
  @EnumSource(value = OrderStatus.class, names = {"PENDING", "PAYMENT_FAILED"})
  void anOrderThatIsNotFinalCanBeEditedAndCancelled(OrderStatus status) {
    Order order = orderIn(status);

    order.cancel();

    assertAll(
      () -> assertTrue(status.isEditable()),
      () -> assertEquals(OrderStatus.CANCELLED, order.getStatus()),
      () -> assertFalse(order.isEditable()));
  }

  @Test
  void aPaidOrderCannotBeCancelledOrFailedAndIsNotEditable() {
    Order order = orderIn(OrderStatus.PAID);

    assertAll(
      () -> assertThrows(OrderStatusTransitionException.class, order::cancel),
      () -> assertThrows(OrderStatusTransitionException.class, order::markPaymentFailed),
      () -> assertEquals(OrderStatus.PAID, order.getStatus()),
      () -> assertFalse(order.isEditable()));
  }

  @Test
  void aCancelledOrderCannotBePaidOrFailed() {
    Order order = orderIn(OrderStatus.CANCELLED);

    assertAll(
      () -> assertThrows(OrderStatusTransitionException.class, order::markPaid),
      () -> assertThrows(OrderStatusTransitionException.class, order::markPaymentFailed),
      () -> assertEquals(OrderStatus.CANCELLED, order.getStatus()));
  }

  @Test
  void repeatingTheSameFinalTransitionIsHarmless() {
    Order paid = orderIn(OrderStatus.PAID);
    Order cancelled = orderIn(OrderStatus.CANCELLED);

    paid.markPaid();
    cancelled.cancel();

    assertAll(
      () -> assertEquals(OrderStatus.PAID, paid.getStatus()),
      () -> assertEquals(OrderStatus.CANCELLED, cancelled.getStatus()));
  }

  @Test
  void aFailedPaymentCanBeRetriedAndThenSucceed() {
    Order order = new Order();

    order.markPaymentFailed();
    order.markPaymentFailed();
    order.markPaid();

    assertEquals(OrderStatus.PAID, order.getStatus());
  }

  @Test
  void theMessageOfARefusedTransitionNamesOnlyTheStatuses() {
    OrderStatusTransitionException thrown = assertThrows(OrderStatusTransitionException.class,
      orderIn(OrderStatus.PAID)::cancel);

    assertEquals("An order in the status PAID cannot move to CANCELLED", thrown.getMessage());
  }

  /** The state machine must not be bypassed: a public setter would allow any status. */
  @Test
  void theStatusHasNoSetter() {
    assertThrows(NoSuchMethodException.class,
      () -> Order.class.getMethod("setStatus", OrderStatus.class));
  }

  private static Order orderIn(OrderStatus status) {
    Order order = new Order();
    switch (status) {
      case PENDING -> { }
      case PAID -> order.markPaid();
      case PAYMENT_FAILED -> order.markPaymentFailed();
      case CANCELLED -> order.cancel();
    }
    return order;
  }
}
