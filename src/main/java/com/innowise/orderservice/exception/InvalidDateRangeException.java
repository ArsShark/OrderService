package com.innowise.orderservice.exception;

/**
 * Thrown when a request asks for orders created from an instant that is not before the instant
 * they must be created before: the half-open range {@code [createdFrom, createdBefore)} would be
 * empty, which is almost certainly a mistake of the caller.
 */
public class InvalidDateRangeException extends RuntimeException {

  /** Creates the exception. The text is constant: the instants are client input. */
  public InvalidDateRangeException() {
    super("createdFrom must be before createdBefore");
  }
}
