package com.innowise.orderservice.integration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.innowise.orderservice.PaymentEvents;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/**
 * A successful payment and a cancel of the same order arrive at the same moment: the event
 * through Kafka and the real listener, the cancel through the REST API. Both load the order with
 * the row lock, so one of them gets it first and the other one sees the result:
 *
 * <ul>
 *   <li>the event first: the order is PAID and the cancel is refused with 409;</li>
 *   <li>the cancel first: the order is CANCELLED, the cancel answers 200 and the event leaves the
 *   order cancelled.</li>
 * </ul>
 *
 * <p>Which one wins is not decided; the test asserts the two outcomes that are allowed and that
 * the answer of the cancel and the status of the order always agree. Five repetitions give the
 * two sides a chance to take turns.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentEventCancelRaceIntegrationTest extends AbstractIntegrationTest {

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

  @RepeatedTest(5)
  void aPaymentAndACancelThatRaceNeverLeaveAnOrderThatIsBothPaidAndCancelled() throws Exception {
    long orderId = insertOrder(ApiSupport.ANNA_ID, "PENDING", "24.00");
    String event = PaymentEvents.json(orderId, ApiSupport.ANNA_ID, "SUCCESS", "24.00");
    CyclicBarrier startTogether = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);

    int cancelStatus;
    try {
      Callable<Integer> cancel = () -> {
        startTogether.await(10, TimeUnit.SECONDS);
        return mockMvc.perform(ApiSupport.as(patch("/api/orders/{id}/cancel", orderId),
          ApiSupport.annaToken())).andReturn().getResponse().getStatus();
      };
      Callable<Void> pay = () -> {
        startTogether.await(10, TimeUnit.SECONDS);
        publisher.send(String.valueOf(orderId), event);
        return null;
      };
      Future<Integer> cancelled = pool.submit(cancel);
      Future<Void> paid = pool.submit(pay);
      cancelStatus = cancelled.get(30, TimeUnit.SECONDS);
      paid.get(30, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
    KafkaSupport.awaitConsumed(kafkaBootstrapServers);

    String status = jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?",
      String.class, orderId);
    boolean cancelWon = cancelStatus == 200 && "CANCELLED".equals(status);
    boolean paymentWon = cancelStatus == 409 && "PAID".equals(status);
    assertTrue(cancelWon || paymentWon,
      String.format("the cancel answered %d and the order is %s", cancelStatus, status));
  }
}
