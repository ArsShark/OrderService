package com.innowise.orderservice.exception;

/**
 * Thrown when an order does not exist, is deleted, or belongs to somebody else. The three cases
 * look the same on purpose, so a caller cannot find out which order ids are taken.
 */
public class OrderNotFoundException extends RuntimeException {

  public OrderNotFoundException(Long id) {
    super(String.format("Order with id %d not found", id));
  }
}
