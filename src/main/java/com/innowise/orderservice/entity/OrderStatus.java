package com.innowise.orderservice.entity;

/**
 * Status of an order. The values are stored as text and are repeated in the CHECK constraint
 * {@code ck_orders_status}; a test keeps the two in step.
 *
 * <p>Allowed transitions (enforced by {@link Order}, not by the database):
 * {@code PENDING -> PAID | PAYMENT_FAILED | CANCELLED},
 * {@code PAYMENT_FAILED -> PAID | PAYMENT_FAILED | CANCELLED}. {@code PAID} and
 * {@code CANCELLED} are final.
 */
public enum OrderStatus {

  /** Created and waiting for a payment. */
  PENDING,

  /** A payment with the status SUCCESS was received. */
  PAID,

  /** The last payment failed; the client may pay again. */
  PAYMENT_FAILED,

  /** Cancelled by the owner or an admin before it was paid. */
  CANCELLED;

  /**
   * Tells whether an order in this status may move to the target status.
   *
   * @param target the status to move to
   * @return {@code true} if the transition is allowed
   */
  public boolean canMoveTo(OrderStatus target) {
    return switch (this) {
      case PENDING, PAYMENT_FAILED -> target == PAID || target == PAYMENT_FAILED
        || target == CANCELLED;
      case PAID, CANCELLED -> false;
    };
  }

  /** Tells whether the lines of an order in this status may still be replaced. */
  public boolean isEditable() {
    return this == PENDING || this == PAYMENT_FAILED;
  }
}
