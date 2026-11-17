package com.innowise.orderservice.messaging;

import com.innowise.orderservice.dto.event.PaymentEvent;
import com.innowise.orderservice.service.OrderService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reads the payment events that the Payment Service writes to {@link KafkaTopics#PAYMENT_EVENTS}
 * and hands every one to {@link OrderService#applyPaymentResult}.
 *
 * <p>The class only filters. What an event means for an order, and what is logged about it, is
 * decided in the service, which runs in its own transaction. The offset of a record is committed
 * after this method returns (acknowledge mode {@code record}); if it throws, the error handler
 * decides (see {@code KafkaErrorHandlingConfig}).
 *
 * <p>The group id is {@code spring.kafka.consumer.group-id}. Every instance of this service has
 * the same group, so a partition is read by one instance at a time.
 */
@Component
public class PaymentEventListener {

  /** The id of the listener container; a test starts and stops the listener with it. */
  public static final String LISTENER_ID = "payment-event-listener";

  private static final Logger LOGGER = LoggerFactory.getLogger(PaymentEventListener.class);

  private final OrderService orderService;

  public PaymentEventListener(OrderService orderService) {
    this.orderService = orderService;
  }

  /**
   * Handles one record. {@code idIsGroup = false} keeps the group id of the configuration: by
   * default the id of the listener would replace it.
   *
   * @param record the record with the event already read from the JSON
   */
  @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = KafkaTopics.PAYMENT_EVENTS)
  public void onPaymentEvent(ConsumerRecord<String, PaymentEvent> record) {
    PaymentEvent event = record.value();
    if (event == null) {
      LOGGER.warn("Skipped a record without a value (partition {}, offset {})",
        record.partition(), record.offset());
      return;
    }
    if (!PaymentEvent.CREATE_PAYMENT.equals(event.eventType())) {
      LOGGER.warn("Skipped an event of an unknown type (partition {}, offset {})",
        record.partition(), record.offset());
      return;
    }
    LOGGER.info("Received {} of payment {} (partition {}, offset {})", event.eventType(),
      event.paymentId(), record.partition(), record.offset());
    orderService.applyPaymentResult(event);
  }
}
