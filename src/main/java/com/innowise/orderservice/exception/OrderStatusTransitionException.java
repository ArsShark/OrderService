package com.innowise.orderservice.exception;

import com.innowise.orderservice.entity.OrderStatus;

/** Thrown when an order is asked to move to a status that its current status does not allow. */
public class OrderStatusTransitionException extends RuntimeException {

  public OrderStatusTransitionException(OrderStatus from, OrderStatus to) {
    super(String.format("An order in the status %s cannot move to %s", from, to));
  }
}
