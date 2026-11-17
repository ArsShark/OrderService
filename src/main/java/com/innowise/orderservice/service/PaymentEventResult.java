package com.innowise.orderservice.service;

/**
 * What {@link OrderService#applyPaymentResult} did with a payment event. Every case is a normal
 * outcome that the Kafka consumer commits; the log line of the service tells which one it was.
 */
public enum PaymentEventResult {

  /** The order moved to PAID or PAYMENT_FAILED. */
  APPLIED,

  /** The order was in the status that the event asks for already (a repeated event). */
  ALREADY_APPLIED,

  /** A failed payment that arrived after the order was paid or cancelled: it changes nothing. */
  IGNORED,

  /** A successful payment of an order that was cancelled meanwhile. The order stays cancelled. */
  ORDER_CANCELLED,

  /** No order with that id, or it was deleted. */
  ORDER_NOT_FOUND,

  /** The payer is not the owner of the order. */
  OWNER_MISMATCH,

  /** The amount of the payment is not the total of the order. */
  AMOUNT_MISMATCH
}
