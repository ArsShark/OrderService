package com.innowise.orderservice.exception;

import com.innowise.orderservice.entity.OrderStatus;

/** Thrown when the owner of a paid order tries to delete it; only an admin may. */
public class OrderNotDeletableException extends RuntimeException {

  public OrderNotDeletableException(Long id, OrderStatus status) {
    super(String.format("Order with id %d is %s and cannot be deleted", id, status));
  }
}
