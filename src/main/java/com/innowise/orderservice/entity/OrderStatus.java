package com.innowise.orderservice.entity;

/**
 * Status of an order. The values are stored as text and are repeated in the CHECK constraint
 * {@code ck_orders_status}; a test keeps the two in step.
 *
 * <p>Allowed transitions (enforced by the service layer, not by the database):
 * {@code PENDING -> PAID | PAYMENT_FAILED | CANCELLED},
 * {@code PAYMENT_FAILED -> PAID | PAYMENT_FAILED | CANCELLED}.
 */
public enum OrderStatus {

  /** Created and waiting for a payment. */
  PENDING,

  /** A payment with the status SUCCESS was received. */
  PAID,

  /** The last payment failed; the client may pay again. */
  PAYMENT_FAILED,

  /** Cancelled by the owner or an admin before it was paid. */
  CANCELLED
}
