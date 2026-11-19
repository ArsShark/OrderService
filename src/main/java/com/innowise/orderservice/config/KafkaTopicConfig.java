package com.innowise.orderservice.config;

import com.innowise.orderservice.messaging.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topics that this service reads or owns: {@code payment-events} and its dead letter
 * topic. {@code KafkaAdmin} of Spring Boot creates every {@link NewTopic} bean when the
 * application starts and does nothing if the topic exists already (it only adds partitions if
 * the declared number is larger).
 *
 * <p>The Payment Service is the owner of {@code payment-events} and declares it too, with the
 * same shape. This service declares it as well, so it does not depend on the order in which the
 * two services start: a consumer of a topic that does not exist logs a warning every second
 * until somebody creates it. Both declarations come from {@link KafkaTopics} and a change of one
 * has to be made in the other repository too.
 *
 * <p>The broker has automatic topic creation switched off, so a typo in a topic name is an error
 * and not a new topic. If the broker is not reachable at start, the application still starts and
 * logs a warning.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaTopicConfig {

  /**
   * The topic {@code payment-events}, the same declaration as in the Payment Service.
   *
   * @return the declaration of the topic
   */
  @Bean
  NewTopic paymentEventsTopic() {
    return TopicBuilder.name(KafkaTopics.PAYMENT_EVENTS)
      .partitions(KafkaTopics.PARTITIONS)
      .replicas(KafkaTopics.REPLICAS)
      .build();
  }

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
