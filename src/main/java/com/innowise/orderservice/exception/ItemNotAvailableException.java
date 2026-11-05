package com.innowise.orderservice.exception;

/** Thrown when an order refers to an item that does not exist or has been deleted. */
public class ItemNotAvailableException extends RuntimeException {

  public ItemNotAvailableException(Long itemId) {
    super(String.format("Item with id %d is not available", itemId));
  }
}
