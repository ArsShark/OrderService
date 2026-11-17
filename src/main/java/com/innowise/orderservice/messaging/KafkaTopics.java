package com.innowise.orderservice.messaging;

/**
 * Names and sizes of the Kafka topics that this service reads or owns. Constants, not settings:
 * the topic is part of the contract with the Payment Service, and a name that an environment
 * variable could change would let the two services drift apart.
 */
public final class KafkaTopics {

  /** The topic of the payment events. The Payment Service writes it and owns it. */
  public static final String PAYMENT_EVENTS = "payment-events";

  /**
   * The dead letter topic of {@link #PAYMENT_EVENTS}: where a record goes that cannot be applied
   * (see {@code KafkaErrorHandlingConfig}). This service owns it. Nobody reads it for now: the
   * records stay there for a person to look at.
   */
  public static final String PAYMENT_EVENTS_DLT = "payment-events.DLT";

  /**
   * Partitions of the topics. A dead letter record goes to the SAME partition number as the
   * original, so the dead letter topic must have at least as many partitions as the topic of the
   * Payment Service; a test keeps the two in step.
   */
  public static final int PARTITIONS = 3;

  /** Copies of every partition. One broker cannot hold more than one. */
  public static final short REPLICAS = 1;

  private KafkaTopics() {
  }
}
