package com.innowise.orderservice.integration;

import com.innowise.orderservice.messaging.KafkaTopics;
import com.innowise.orderservice.messaging.PaymentEventListener;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * Helpers of the tests that talk to Kafka. Everything is static or created by the test, and
 * nothing here touches the Spring context, so the helpers cannot change the shared context of the
 * integration tests (the same rule as {@code ApiSupport}).
 *
 * <p>The listener of the application does not start with the context. A test class that needs it
 * starts it once ({@link #startListener}) and stops it when the class is done
 * ({@link #stopListener}); between the two, a test sends an event, waits until it is consumed
 * ({@link #awaitConsumed}) and checks the database. A test never ends with an event that is not
 * consumed yet, because every test starts the identity counters again and so reuses the ids.
 */
final class KafkaSupport {

  /** The consumer group of the application ({@code spring.kafka.consumer.group-id}). */
  static final String GROUP = "order-service";

  static final Duration WAIT = Duration.ofSeconds(30);

  private KafkaSupport() {
  }

  /** Starts the listener and waits until it has been given the partitions of the topic. */
  static void startListener(KafkaListenerEndpointRegistry registry) {
    MessageListenerContainer container =
      registry.getListenerContainer(PaymentEventListener.LISTENER_ID);
    container.start();
    waitUntil(WAIT, () -> container.getAssignedPartitions() != null
      && container.getAssignedPartitions().size() == KafkaTopics.PARTITIONS,
      "the listener was not given the partitions of the topic");
  }

  /** Stops the listener. A stopped listener keeps its committed position. */
  static void stopListener(KafkaListenerEndpointRegistry registry) {
    registry.getListenerContainer(PaymentEventListener.LISTENER_ID).stop();
  }

  /**
   * Waits until the group has committed every record that is in the topic. A record is committed
   * after the listener has handled it (or the error handler has sent it to the dead letter
   * topic), so afterwards the database shows what the event did.
   */
  static void awaitConsumed(String bootstrapServers) {
    try (Admin admin = Admin.create(adminConfig(bootstrapServers))) {
      waitUntil(WAIT, () -> isFullyConsumed(admin),
        "the group did not consume every record of the topic");
    }
  }

  private static boolean isFullyConsumed(Admin admin) {
    try {
      Map<TopicPartition, OffsetAndMetadata> committed = admin
        .listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
        .get(10, TimeUnit.SECONDS);
      Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
      for (int partition = 0; partition < KafkaTopics.PARTITIONS; partition++) {
        latest.put(new TopicPartition(KafkaTopics.PAYMENT_EVENTS, partition), OffsetSpec.latest());
      }
      return admin.listOffsets(latest).all().get(10, TimeUnit.SECONDS).entrySet().stream()
        .allMatch(end -> {
          OffsetAndMetadata done = committed.get(end.getKey());
          long committedOffset = done == null ? 0L : done.offset();
          return committedOffset >= end.getValue().offset();
        });
    } catch (ExecutionException | TimeoutException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for the consumer", e);
    }
  }

  /**
   * Writes a record to the topic of the payment events like the Payment Service does: the key is
   * the order id as text and the value is the JSON.
   */
  static final class Publisher implements AutoCloseable {

    private final KafkaProducer<String, String> producer;

    Publisher(String bootstrapServers) {
      Map<String, Object> config = Map.of(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
        ProducerConfig.ACKS_CONFIG, "all");
      producer = new KafkaProducer<>(config);
      // Fetch the metadata now, so the first send of a test does not spend its time on it.
      producer.partitionsFor(KafkaTopics.PAYMENT_EVENTS);
    }

    /** Sends a record and waits until the broker has it. */
    void send(String key, String json) {
      try {
        producer.send(new ProducerRecord<>(KafkaTopics.PAYMENT_EVENTS, key, json))
          .get(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while sending", e);
      } catch (ExecutionException | TimeoutException e) {
        throw new IllegalStateException("the record was not sent", e);
      }
    }

    @Override
    public void close() {
      producer.close(Duration.ofSeconds(5));
    }
  }

  /**
   * Reads a topic the plain way, with the Kafka client and no Spring, so a test sees exactly what
   * another service would see on the wire. There is no consumer group (the partitions are
   * assigned by hand and nothing is committed), so every call reads from the beginning and cannot
   * disturb the group of the application. The topic is shared by all tests, so a test looks for
   * the records of a key that only it uses.
   *
   * @return the records with that key; fewer than expected if the time ran out
   */
  static List<ConsumerRecord<String, String>> readByKey(String bootstrapServers, String topic,
    String key, int expected, Duration timeout) {
    Map<String, Object> config = Map.of(
      ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
      ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
      ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
      ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    List<ConsumerRecord<String, String>> found = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
      List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
        .map(info -> new TopicPartition(info.topic(), info.partition()))
        .toList();
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);
      Instant giveUpAt = Instant.now().plus(timeout);
      while (found.size() < expected && Instant.now().isBefore(giveUpAt)) {
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
          if (key.equals(record.key())) {
            found.add(record);
          }
        }
      }
    }
    return found;
  }

  /** Polls a condition until it holds or the time is over; the second case fails the test. */
  static void waitUntil(Duration timeout, BooleanSupplier condition, String failure) {
    Instant giveUpAt = Instant.now().plus(timeout);
    while (!condition.getAsBoolean()) {
      if (Instant.now().isAfter(giveUpAt)) {
        throw new AssertionError(failure);
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while waiting", e);
      }
    }
  }

  private static Map<String, Object> adminConfig(String bootstrapServers) {
    return Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
  }
}
