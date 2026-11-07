package com.innowise.orderservice.exception;

/** Thrown when an item does not exist or has been deleted. */
public class ItemNotFoundException extends RuntimeException {

  public ItemNotFoundException(Long id) {
    super(String.format("Item with id %d not found", id));
  }
}
