package com.innowise.orderservice.messaging;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.innowise.orderservice.dto.event.PaymentEvent;
import com.innowise.orderservice.dto.event.PaymentOutcome;
import com.innowise.orderservice.exception.InvalidPaymentEventException;
import com.innowise.orderservice.service.OrderService;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests of {@link PaymentEventListener} with a mocked service: the listener only filters and
 * passes on. What an event means for an order is tested with the service.
 */
@ExtendWith(MockitoExtension.class)
class PaymentEventListenerTest {

  @Mock
  private OrderService orderService;

  private PaymentEventListener listener;

  @BeforeEach
  void createListener() {
    listener = new PaymentEventListener(orderService);
  }

  private static PaymentEvent event(String type) {
    return new PaymentEvent(UUID.fromString("4d1f8f3e-0c0b-3b52-9c7e-1f6a2d9b7a10"), type,
      "pay-1", 5L, 7L, PaymentOutcome.SUCCESS, "24.00", Instant.parse("2026-11-16T10:00:00Z"));
  }

  private static ConsumerRecord<String, PaymentEvent> record(PaymentEvent value) {
    return new ConsumerRecord<>(KafkaTopics.PAYMENT_EVENTS, 1, 42L, "5", value);
  }

  @Test
  void anEventOfTheKnownTypeIsHandedToTheService() {
    PaymentEvent event = event(PaymentEvent.CREATE_PAYMENT);

    listener.onPaymentEvent(record(event));

    verify(orderService).applyPaymentResult(event);
  }

  @Test
  void anEventOfAnotherTypeIsSkippedWithoutAnError() {
    listener.onPaymentEvent(record(event("REFUND_PAYMENT")));

    verifyNoInteractions(orderService);
  }

  @Test
  void aRecordWithoutAValueIsSkippedWithoutAnError() {
    listener.onPaymentEvent(record(null));

    verifyNoInteractions(orderService);
  }

  @Test
  void aRefusalOfTheServiceIsNotSwallowedSoTheErrorHandlerSeesIt() {
    PaymentEvent event = event(PaymentEvent.CREATE_PAYMENT);
    InvalidPaymentEventException refusal = new InvalidPaymentEventException("broken");
    when(orderService.applyPaymentResult(event)).thenThrow(refusal);

    InvalidPaymentEventException thrown = assertThrows(InvalidPaymentEventException.class,
      () -> listener.onPaymentEvent(record(event)));

    assertSame(refusal, thrown);
  }
}
