package com.innowise.orderservice.dto.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;

/**
 * The event {@code CREATE_PAYMENT} that the Payment Service sends to the topic
 * {@code payment-events} after it has saved a payment.
 *
 * <p>This is a CONTRACT between two services that share no code: the Payment Service has its own
 * copy of this record, and the JSON of the event is pinned by a test on both sides. A field that
 * this service does not know is ignored, so the producer may add fields. The fields that can be
 * missing in a broken event are objects, not primitives: a missing number must be seen as
 * {@code null}, not read as {@code 0}.
 *
 * @param eventId    id of the event; the same payment always gives the same id
 * @param eventType  the kind of the event; only {@link #CREATE_PAYMENT} is handled
 * @param paymentId  id of the payment in the Payment Service (for the log)
 * @param orderId    id of the paid order
 * @param userId     id of the payer; it must be the owner of the order
 * @param status     the outcome of the payment
 * @param amount     the amount as text, for example {@code "24.00"}; it must equal the total of
 *                   the order
 * @param occurredAt when the payment was made
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentEvent(
  UUID eventId,
  String eventType,
  String paymentId,
  Long orderId,
  Long userId,
  PaymentOutcome status,
  String amount,
  @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt) {

  /** The only kind of event of this topic for now. */
  public static final String CREATE_PAYMENT = "CREATE_PAYMENT";
}
