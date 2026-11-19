package com.innowise.orderservice.exception;

/**
 * A payment event that cannot be applied however often it is tried: a required field is missing
 * or the amount is not a number. The Kafka error handler does not retry it and sends the record
 * to the dead letter topic.
 *
 * <p>The message names the problem, never a value of the event.
 */
public class InvalidPaymentEventException extends RuntimeException {

  public InvalidPaymentEventException(String message) {
    super(message);
  }
}
