package com.innowise.orderservice.exception;

/** Thrown when the total of an order does not fit into the column {@code NUMERIC(12,2)}. */
public class OrderTotalTooLargeException extends RuntimeException {

  public OrderTotalTooLargeException() {
    super("The total price of the order is too large");
  }
}
