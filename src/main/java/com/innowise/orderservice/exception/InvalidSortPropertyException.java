package com.innowise.orderservice.exception;

import java.util.Set;

/** Thrown when a request asks to sort by a property that is not part of the API. */
public class InvalidSortPropertyException extends RuntimeException {

  /**
   * Creates the exception. The rejected property is deliberately left out of the message: it is
   * client input and the message is logged.
   *
   * @param allowedProperties properties the client may sort by
   */
  public InvalidSortPropertyException(Set<String> allowedProperties) {
    super(String.format("Unsupported sort property. Allowed properties: %s", allowedProperties));
  }
}
