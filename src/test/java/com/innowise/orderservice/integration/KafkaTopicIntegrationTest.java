package com.innowise.orderservice.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.innowise.orderservice.messaging.KafkaTopics;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.junit.jupiter.api.Test;

/**
 * Proves that the application creates the dead letter topic it owns when it starts, and that the
 * topic has as many partitions as the topic of the Payment Service: a dead letter record goes to
 * the same partition number as the original, so a smaller topic would refuse some of them.
 */
class KafkaTopicIntegrationTest extends AbstractIntegrationTest {

  @Test
  void theDeadLetterTopicHasTheSamePartitionsAsThePaymentEventsTopic() throws Exception {
    Map<String, Object> config = Map.of(
      AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);

    Map<String, TopicDescription> topics;
    try (Admin admin = Admin.create(config)) {
      topics = admin.describeTopics(
          List.of(KafkaTopics.PAYMENT_EVENTS, KafkaTopics.PAYMENT_EVENTS_DLT))
        .allTopicNames().get(10, TimeUnit.SECONDS);
    }

    TopicDescription events = topics.get(KafkaTopics.PAYMENT_EVENTS);
    TopicDescription deadLetters = topics.get(KafkaTopics.PAYMENT_EVENTS_DLT);
    assertAll(
      () -> assertEquals(3, events.partitions().size()),
      () -> assertEquals(3, deadLetters.partitions().size()),
      () -> assertEquals(List.of(1, 1, 1), deadLetters.partitions().stream()
        .map(partition -> partition.replicas().size()).toList()));
  }
}
