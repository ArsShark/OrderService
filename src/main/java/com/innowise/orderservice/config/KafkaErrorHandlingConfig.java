package com.innowise.orderservice.config;

import com.innowise.orderservice.dto.event.PaymentEvent;
import com.innowise.orderservice.exception.InvalidPaymentEventException;
import com.innowise.orderservice.messaging.KafkaTopics;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.util.backoff.BackOff;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the listener does when a payment event cannot be applied.
 *
 * <ul>
 *   <li>A record that cannot be read (broken JSON, a status that does not exist) never reaches
 *   the listener: {@code ErrorHandlingDeserializer} hands the failure to the error handler,
 *   which does not retry it and sends the record to the dead letter topic.</li>
 *   <li>A valid JSON that the service refuses ({@link InvalidPaymentEventException}) is not
 *   retried either: the same record would fail again. It goes to the dead letter topic.</li>
 *   <li>Any other exception (the database is down, a lock times out) may be temporary: the
 *   record is retried with growing pauses, and only when the retries are used up it goes to the
 *   dead letter topic. The listener thread waits meanwhile, so the partitions that it reads wait
 *   too; this is fine at this volume.</li>
 * </ul>
 *
 * <p>An event that does not belong to an order (another payer, another amount, no such order) is
 * not an error at all: the service logs it and the record is committed.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaErrorHandlingConfig {

  /** How often a failing record is tried again before it goes to the dead letter topic. */
  static final int MAX_RETRIES = 4;

  /** The pause before the first retry; every next pause is twice as long (0.5, 1, 2, 4 s). */
  static final long FIRST_PAUSE_MILLIS = 500L;

  static final double PAUSE_MULTIPLIER = 2.0;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * The pauses between the retries of a record.
   *
   * @return the back off
   */
  @Bean
  BackOff paymentEventBackOff() {
    ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
    backOff.setInitialInterval(FIRST_PAUSE_MILLIS);
    backOff.setMultiplier(PAUSE_MULTIPLIER);
    return backOff;
  }

  /**
   * The producer of the dead letter records. It is the only producer of this service, so its
   * settings are written here and not in {@code application.yml}: the same as the producer of
   * the Payment Service (all replicas confirm, no duplicates, no long waits).
   *
   * <p>A dead letter record has one of two kinds of value. A record that could not be
   * deserialized is sent as it came, as {@code byte[]}; a valid JSON that was refused is sent as
   * the JSON of the {@link PaymentEvent}. The serializer tells them apart by the type.
   *
   * @param bootstrapServers the address of the broker
   * @return the factory of the producers
   */
  @Bean
  DefaultKafkaProducerFactory<String, Object> deadLetterProducerFactory(
    @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
    Map<String, Object> config = Map.of(
      ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
      ProducerConfig.ACKS_CONFIG, "all",
      ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
      ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000,
      ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000,
      ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30000);
    Serializer<Object> eventSerializer = (topic, event) -> JSON.writeValueAsBytes(event);
    Map<Class<?>, Serializer<?>> serializers = new LinkedHashMap<>();
    serializers.put(byte[].class, new ByteArraySerializer());
    serializers.put(PaymentEvent.class, eventSerializer);
    return new DefaultKafkaProducerFactory<>(config, new StringSerializer(),
      new DelegatingByTypeSerializer(serializers));
  }

  /**
   * The template that writes the dead letter records.
   *
   * @param producerFactory the factory above
   * @return the template
   */
  @Bean
  KafkaTemplate<String, Object> deadLetterTemplate(
    DefaultKafkaProducerFactory<String, Object> producerFactory) {
    return new KafkaTemplate<>(producerFactory);
  }

  /**
   * Writes a record that failed for good to {@link KafkaTopics#PAYMENT_EVENTS_DLT}. The headers
   * of the new record tell where it came from and why it failed.
   *
   * @param deadLetterTemplate the template above
   * @return the recoverer
   */
  @Bean
  DeadLetterPublishingRecoverer paymentEventRecoverer(
    KafkaTemplate<String, Object> deadLetterTemplate) {
    return new DeadLetterPublishingRecoverer(deadLetterTemplate,
      KafkaErrorHandlingConfig::deadLetterDestination);
  }

  /**
   * The error handler of every listener container. Spring Boot adds a bean of this type to the
   * container factory it builds.
   *
   * @param recoverer the recoverer above
   * @param backOff   the pauses above
   * @return the handler
   */
  @Bean
  DefaultErrorHandler kafkaErrorHandler(DeadLetterPublishingRecoverer recoverer, BackOff backOff) {
    DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
    handler.addNotRetryableExceptions(InvalidPaymentEventException.class);
    return handler;
  }

  /** The dead letter topic and the partition number of the failed record. */
  static TopicPartition deadLetterDestination(ConsumerRecord<?, ?> record, Exception exception) {
    return new TopicPartition(KafkaTopics.PAYMENT_EVENTS_DLT, record.partition());
  }
}
