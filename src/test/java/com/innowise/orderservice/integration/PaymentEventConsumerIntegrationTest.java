package com.innowise.orderservice.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.innowise.orderservice.PaymentEvents;
import com.innowise.orderservice.messaging.KafkaTopics;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/**
 * The consumer of the payment events with the whole application: a real broker, a real listener
 * and the real database. A test writes an event the way the Payment Service does (key = order id,
 * the JSON of the contract), waits until the group has consumed it, and looks at the order.
 *
 * <p>The listener is started once for the class and stopped at the end, see {@link KafkaSupport}.
 * The orders are inserted with plain SQL in the status the test needs. The topic of the dead
 * letters is shared by all tests and never emptied, so every test that looks into it uses an
 * order id of its own (they start at 880000, far from the ids of the database).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentEventConsumerIntegrationTest extends AbstractIntegrationTest {

  private static final String ORIGINAL_TOPIC_HEADER = "kafka_dlt-original-topic";
  private static final String JAVA_TYPE_HEADER = "__TypeId__";

  @Autowired
  private KafkaListenerEndpointRegistry registry;

  private KafkaSupport.Publisher publisher;

  @BeforeAll
  void startTheConsumer() {
    publisher = new KafkaSupport.Publisher(kafkaBootstrapServers);
    KafkaSupport.startListener(registry);
  }

  @AfterAll
  void stopTheConsumer() {
    KafkaSupport.stopListener(registry);
    publisher.close();
  }

  private void publishAndWait(long orderId, String json) {
    publisher.send(String.valueOf(orderId), json);
    KafkaSupport.awaitConsumed(kafkaBootstrapServers);
  }

  private static String headerOf(ConsumerRecord<String, String> record, String name) {
    return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
  }

  private String statusOf(long orderId) {
    return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class,
      orderId);
  }

  private long versionOf(long orderId) {
    Long version = jdbcTemplate.queryForObject("SELECT version FROM orders WHERE id = ?",
      Long.class, orderId);
    return version == null ? -1L : version;
  }

  private List<ConsumerRecord<String, String>> deadLettersOf(long orderId, int expected,
    Duration timeout) {
    return KafkaSupport.readByKey(kafkaBootstrapServers, KafkaTopics.PAYMENT_EVENTS_DLT,
      String.valueOf(orderId), expected, timeout);
  }

  // ---- what an event does to an order

  @Test
  void aSuccessfulPaymentPaysThePendingOrder() {
    long orderId = insertOrder(ApiSupport.ANNA_ID, "PENDING", "24.00");

    publishAndWait(orderId, PaymentEvents.json(orderId, ApiSupport.ANNA_ID, "SUCCESS", "24.00"));

    assertEquals("PAID", statusOf(orderId));
  }

  @Test
  void aFailedPaymentMarksThePendingOrder() {
    long orderId = insertOrder(ApiSupport.ANNA_ID, "PENDING", "24.00");

    publishAndWait(orderId, PaymentEvents.json(orderId, ApiSupport.ANNA_ID, "FAILED", "24.00"));

    assertEquals("PAYMENT_FAILED", statusOf(orderId));
  }

  @Test
  void aSecondTryThatSucceedsPaysAnOrderWhosePaymentFailedBefore() {
    long orderId = insertOrder(ApiSupport.ANNA_ID, "PENDING", "24.00");

    publishAndWait(orderId, PaymentEvents.json(orderId, ApiSupport.ANNA_ID, "FAILED", "24.00"));
    String afterTheFailure = statusOf(orderId);
    publishAndWait(orderId, PaymentEvents.json(orderId, ApiSupport.ANNA_ID, "SUCCESS", "24.00"));

    assertAll(
      () -> assertEquals("PAYMENT_FAILED", afterTheFailure),
      () -> assertEquals("PAID", statusOf(orderId)));
  }

  @Test
  void theSameEventAgainChangesNothingNotEvenTheVersionOfTheRow() {
    long orderId = insertOrder(ApiSupport.ANNA_ID, "PENDING", "24.00");
    String event = PaymentEvents.json(orderId, ApiSupport.ANNA_ID, "SUCCESS", "24.00");

    publishAndWait(orderId, event);
    long versionAfterTheFirst = versionOf(orderId);
    publishAndWait(orderId, event);

    assertAll(
      () -> assertEquals("PAID", statusOf(orderId)),
      () -> assertEquals(versionAfterTheFirst, versionOf(orderId)));
  }

  @Test
  void aFailedPaymentThatArrivesAfterThePaymentIsIgnored() {
    long orderId = insertOrder(ApiSupport.ANNA_ID, "PAID", "24.00");

    publishAndWait(orderId, PaymentEvents.json(orderId, ApiSupport.ANNA_ID, "FAILED", "24.00"));

    assertEquals("PAID", statusOf(orderId));
  }

  @Test
  void aSuccessfulPaymentOfACancelledOrderLeavesItCancelled() {
    long orderId = insertOrder(ApiSupport.ANNA_ID, "CANCELLED", "24.00");

    publishAndWait(orderId, PaymentEvents.json(orderId, ApiSupport.ANNA_ID, "SUCCESS", "24.00"));

    assertEquals("CANCELLED", statusOf(orderId));
  }

  @Test
  void anAmountIsComparedAsANumber() {
    long orderId = insertOrder(ApiSupport.ANNA_ID, "PENDING", "24.00");

    publishAndWait(orderId, PaymentEvents.json(orderId, ApiSupport.ANNA_ID, "SUCCESS", "24"));

    assertEquals("PAID", statusOf(orderId));
  }

  // ---- events that do not belong to the order: logged, committed, nothing else

  @Test
  void aPaymentOfAnotherUserDoesNotPayTheOrder() {
    long orderId = insertOrder(ApiSupport.ANNA_ID, "PENDING", "24.00");

    publishAndWait(orderId, PaymentEvents.json(orderId, ApiSupport.BORIS_ID, "SUCCESS", "24.00"));

    assertEquals("PENDING", statusOf(orderId));
  }

  @Test
  void aPaymentOfAnotherAmountDoesNotPayTheOrder() {
    long orderId = insertOrder(ApiSupport.ANNA_ID, "PENDING", "24.00");

    publishAndWait(orderId, PaymentEvents.json(orderId, ApiSupport.ANNA_ID, "SUCCESS", "25.00"));

    assertEquals("PENDING", statusOf(orderId));
  }

  @Test
  void aPaymentOfAnOrderThatDoesNotExistIsCommittedAndNotSentToTheDeadLetters() {
    long unknownOrderId = 880_001L;

    publishAndWait(unknownOrderId,
      PaymentEvents.json(unknownOrderId, ApiSupport.ANNA_ID, "SUCCESS", "24.00"));

    assertAll(
      () -> assertEquals(0L, countRows("orders")),
      () -> assertTrue(deadLettersOf(unknownOrderId, 1, Duration.ofSeconds(2)).isEmpty()));
  }

  @Test
  void aPaymentOfADeletedOrderDoesNotPayIt() {
    long orderId = insertOrder(ApiSupport.ANNA_ID, "PENDING", "24.00");
    jdbcTemplate.update("UPDATE orders SET deleted = true WHERE id = ?", orderId);

    publishAndWait(orderId, PaymentEvents.json(orderId, ApiSupport.ANNA_ID, "SUCCESS", "24.00"));

    assertEquals("PENDING", statusOf(orderId));
  }

  @Test
  void anEventOfAnotherTypeIsSkipped() {
    long orderId = insertOrder(ApiSupport.ANNA_ID, "PENDING", "24.00");
    String refund = PaymentEvents.json(orderId, ApiSupport.ANNA_ID, "SUCCESS", "24.00")
      .replace("CREATE_PAYMENT", "REFUND_PAYMENT");

    publishAndWait(orderId, refund);

    assertEquals("PENDING", statusOf(orderId));
  }

  // ---- records that cannot be applied: they go to the dead letter topic, the consumer goes on

  @Test
  void aRecordThatIsNotJsonGoesToTheDeadLettersAsItCameAndTheNextOneIsStillApplied() {
    long poisonKey = 880_101L;
    long orderId = insertOrder(ApiSupport.ANNA_ID, "PENDING", "24.00");

    publisher.send(String.valueOf(poisonKey), "this is not json");
    publishAndWait(orderId, PaymentEvents.json(orderId, ApiSupport.ANNA_ID, "SUCCESS", "24.00"));

    List<ConsumerRecord<String, String>> deadLetters =
      deadLettersOf(poisonKey, 1, KafkaSupport.WAIT);
    assertEquals(1, deadLetters.size(), "the record did not reach the dead letter topic");
    ConsumerRecord<String, String> record = deadLetters.get(0);
    assertAll(
      () -> assertEquals("this is not json", record.value()),
      () -> assertEquals(KafkaTopics.PAYMENT_EVENTS,
        headerOf(record, ORIGINAL_TOPIC_HEADER)),
      () -> assertEquals("PAID", statusOf(orderId)));
  }

  @Test
  void aStatusThatDoesNotExistGoesToTheDeadLettersAsItCame() {
    long orderId = 880_102L;
    String json = PaymentEvents.json(orderId, ApiSupport.ANNA_ID, "REFUNDED", "24.00");

    publishAndWait(orderId, json);

    List<ConsumerRecord<String, String>> deadLetters =
      deadLettersOf(orderId, 1, KafkaSupport.WAIT);
    assertEquals(1, deadLetters.size(), "the record did not reach the dead letter topic");
    assertEquals(json, deadLetters.get(0).value());
  }

  @Test
  void anEventThatTheServiceRefusesGoesToTheDeadLettersWithoutAJavaTypeHeader() {
    long orderId = 880_103L;

    publishAndWait(orderId,
      PaymentEvents.json(orderId, ApiSupport.ANNA_ID, "SUCCESS", "forty-nine"));

    List<ConsumerRecord<String, String>> deadLetters =
      deadLettersOf(orderId, 1, KafkaSupport.WAIT);
    assertEquals(1, deadLetters.size(), "the record did not reach the dead letter topic");
    ConsumerRecord<String, String> record = deadLetters.get(0);
    assertAll(
      () -> assertTrue(record.value().contains("\"orderId\":880103"), record.value()),
      () -> assertTrue(record.value().contains("\"amount\":\"forty-nine\""), record.value()),
      () -> assertNull(record.headers().lastHeader(JAVA_TYPE_HEADER)),
      () -> assertEquals(KafkaTopics.PAYMENT_EVENTS, headerOf(record, ORIGINAL_TOPIC_HEADER)));
  }

  @Test
  void anEventWithoutAnOrderIdGoesToTheDeadLetters() {
    long key = 880_104L;
    String withoutOrderId = """
      {"eventType": "CREATE_PAYMENT", "userId": 7, "status": "SUCCESS", "amount": "24.00"}
      """;

    publishAndWait(key, withoutOrderId);

    assertEquals(1, deadLettersOf(key, 1, KafkaSupport.WAIT).size());
  }
}
