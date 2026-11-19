package com.innowise.orderservice;

/**
 * The JSON of the event {@code CREATE_PAYMENT} the way the Payment Service writes it, for the
 * tests that put an event into Kafka. The shape is the contract between the two services; the
 * Payment Service pins the same text in its own tests, so a change of one side that the other
 * cannot read fails a test in both repositories.
 */
public final class PaymentEvents {

  /** The id of the event in the tests; the consumer only passes it on. */
  public static final String EVENT_ID = "4d1f8f3e-0c0b-3b52-9c7e-1f6a2d9b7a10";

  /** The id of the payment in the tests; it appears in the log lines. */
  public static final String PAYMENT_ID = "65f0c0ffee0123456789abcd";

  private static final String TEMPLATE = """
    {
      "eventId": "%s",
      "eventType": "CREATE_PAYMENT",
      "paymentId": "%s",
      "orderId": %d,
      "userId": %d,
      "status": "%s",
      "amount": "%s",
      "occurredAt": "2026-11-16T10:00:00.123Z"
    }
    """;

  private PaymentEvents() {
  }

  /**
   * An event of the payment {@link #PAYMENT_ID}.
   *
   * @param orderId the paid order
   * @param userId  the payer
   * @param status  {@code SUCCESS} or {@code FAILED} (any text, to build a broken event)
   * @param amount  the amount as text, for example {@code 24.00}
   * @return the JSON text, as the Payment Service sends it
   */
  public static String json(long orderId, long userId, String status, String amount) {
    return String.format(TEMPLATE, EVENT_ID, PAYMENT_ID, orderId, userId, status, amount);
  }
}
