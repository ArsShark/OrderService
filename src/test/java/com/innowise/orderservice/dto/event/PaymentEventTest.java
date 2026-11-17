package com.innowise.orderservice.dto.event;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The contract of the event from the side of the reader: the JSON that the Payment Service writes
 * is read into {@link PaymentEvent}. The text below is the one that the Payment Service pins in
 * {@code PaymentEventPublisherIntegrationTest}; keep the two equal.
 */
class PaymentEventTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final String WIRE = """
    {
      "eventId": "4d1f8f3e-0c0b-3b52-9c7e-1f6a2d9b7a10",
      "eventType": "CREATE_PAYMENT",
      "paymentId": "65f0c0ffee0123456789abcd",
      "orderId": 5,
      "userId": 7,
      "status": "SUCCESS",
      "amount": "24.00",
      "occurredAt": "2026-11-16T10:00:00.123Z"
    }
    """;

  @Test
  void theJsonOfThePaymentServiceIsRead() {
    PaymentEvent event = JSON.readValue(WIRE, PaymentEvent.class);

    assertAll(
      () -> assertEquals(UUID.fromString("4d1f8f3e-0c0b-3b52-9c7e-1f6a2d9b7a10"),
        event.eventId()),
      () -> assertEquals(PaymentEvent.CREATE_PAYMENT, event.eventType()),
      () -> assertEquals("65f0c0ffee0123456789abcd", event.paymentId()),
      () -> assertEquals(5L, event.orderId()),
      () -> assertEquals(7L, event.userId()),
      () -> assertEquals(PaymentOutcome.SUCCESS, event.status()),
      () -> assertEquals("24.00", event.amount()),
      () -> assertEquals(Instant.parse("2026-11-16T10:00:00.123Z"), event.occurredAt()));
  }

  @Test
  void aFieldThatThisServiceDoesNotKnowIsIgnored() {
    String withNewField = WIRE.replace("\"eventType\"", "\"currency\": \"EUR\", \"eventType\"");

    assertEquals(PaymentOutcome.SUCCESS,
      JSON.readValue(withNewField, PaymentEvent.class).status());
  }

  @Test
  void aMissingNumberIsNullAndNotZero() {
    PaymentEvent event = JSON.readValue("{}", PaymentEvent.class);

    assertAll(
      () -> assertNull(event.orderId()),
      () -> assertNull(event.userId()),
      () -> assertNull(event.status()),
      () -> assertNull(event.amount()));
  }

  @Test
  void aStatusThatThisServiceDoesNotKnowCannotBeRead() {
    String unknown = WIRE.replace("SUCCESS", "REFUNDED");

    assertThrows(RuntimeException.class, () -> JSON.readValue(unknown, PaymentEvent.class));
  }

  @Test
  void theTimeIsWrittenAsAnIsoStringWhateverTheDefaultOfTheMapperIs() {
    PaymentEvent event = JSON.readValue(WIRE, PaymentEvent.class);

    String written = JSON.writeValueAsString(event);

    assertTrue(written.contains("\"occurredAt\":\"2026-11-16T10:00:00.123Z\""), written);
  }
}
