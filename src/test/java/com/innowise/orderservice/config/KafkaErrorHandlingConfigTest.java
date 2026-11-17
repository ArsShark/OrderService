package com.innowise.orderservice.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.innowise.orderservice.messaging.KafkaTopics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOffExecution;

/**
 * The parts of the error handling that need no broker: the pauses between the retries and the
 * place of a dead letter record. What the handler does with a record is proved on a real broker.
 */
class KafkaErrorHandlingConfigTest {

  private final KafkaErrorHandlingConfig config = new KafkaErrorHandlingConfig();

  @Test
  void aFailingRecordIsRetriedFourTimesWithPausesThatDoubleAndThenGivenUp() {
    BackOffExecution pauses = config.paymentEventBackOff().start();

    assertAll(
      () -> assertEquals(500L, pauses.nextBackOff()),
      () -> assertEquals(1000L, pauses.nextBackOff()),
      () -> assertEquals(2000L, pauses.nextBackOff()),
      () -> assertEquals(4000L, pauses.nextBackOff()),
      () -> assertEquals(BackOffExecution.STOP, pauses.nextBackOff()));
  }

  @Test
  void aDeadLetterRecordGoesToTheSamePartitionNumberOfTheDeadLetterTopic() {
    ConsumerRecord<String, String> failed =
      new ConsumerRecord<>(KafkaTopics.PAYMENT_EVENTS, 2, 17L, "5", "{}");

    TopicPartition destination = KafkaErrorHandlingConfig.deadLetterDestination(failed,
      new IllegalStateException("boom"));

    assertAll(
      () -> assertEquals("payment-events.DLT", destination.topic()),
      () -> assertEquals(2, destination.partition()));
  }
}
