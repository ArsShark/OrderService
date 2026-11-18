package com.innowise.orderservice.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.innowise.orderservice.messaging.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

/** The topics that this service declares: their names and their shape. */
class KafkaTopicConfigTest {

  @Test
  void thePaymentEventsTopicIsDeclaredLikeTheOwnerDeclaresIt() {
    NewTopic topic = new KafkaTopicConfig().paymentEventsTopic();

    assertAll(
      () -> assertEquals("payment-events", topic.name()),
      () -> assertEquals(3, topic.numPartitions()),
      () -> assertEquals((short) 1, topic.replicationFactor()));
  }

  @Test
  void theDeadLetterTopicHasTheNameAndTheShapeOfTheContract() {
    NewTopic topic = new KafkaTopicConfig().paymentEventsDeadLetterTopic();

    assertAll(
      () -> assertEquals("payment-events.DLT", topic.name()),
      () -> assertEquals(3, topic.numPartitions()),
      () -> assertEquals((short) 1, topic.replicationFactor()),
      () -> assertEquals(KafkaTopics.PARTITIONS, topic.numPartitions()));
  }
}
