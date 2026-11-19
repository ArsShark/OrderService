package com.innowise.orderservice.dto.event;

/**
 * The outcome of a payment, the field {@code status} of {@link PaymentEvent}. A value that is not
 * listed here cannot be read: the event goes to the dead letter topic instead of being guessed.
 */
public enum PaymentOutcome {

  /** The payment went through; the order is paid. */
  SUCCESS,

  /** The payment was declined; the client may pay again. */
  FAILED
}
