package com.innowise.orderservice.entity;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

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
}
