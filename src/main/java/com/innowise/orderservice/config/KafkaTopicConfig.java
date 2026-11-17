package com.innowise.orderservice.config;

import com.innowise.orderservice.messaging.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topic that this service owns: the dead letter topic. {@code KafkaAdmin} of Spring
 * Boot creates every {@link NewTopic} bean when the application starts and does nothing if the
 * topic exists already. The topic {@code payment-events} is declared by the Payment Service, its
 * owner; this service waits for it.
 *
 * <p>The broker has automatic topic creation switched off, so a typo in a topic name is an error
 * and not a new topic. If the broker is not reachable at start, the application still starts and
 * logs a warning.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaTopicConfig {

  /**
   * The topic {@code payment-events.DLT}.
   *
   * @return the declaration of the topic
   */
  @Bean
  NewTopic paymentEventsDeadLetterTopic() {
    return TopicBuilder.name(KafkaTopics.PAYMENT_EVENTS_DLT)
      .partitions(KafkaTopics.PARTITIONS)
      .replicas(KafkaTopics.REPLICAS)
      .build();
  }
}
