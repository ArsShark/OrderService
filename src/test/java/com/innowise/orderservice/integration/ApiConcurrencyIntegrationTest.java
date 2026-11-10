package com.innowise.orderservice.integration;

import static com.innowise.orderservice.integration.ApiSupport.ANNA_EMAIL;
import static com.innowise.orderservice.integration.ApiSupport.annaToken;
import static com.innowise.orderservice.integration.ApiSupport.as;
import static com.innowise.orderservice.integration.ApiSupport.json;
import static com.innowise.orderservice.integration.ApiSupport.orderJson;
import static com.innowise.orderservice.integration.ApiSupport.placeOrder;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The rules of an order under parallel requests: the row lock that every write takes, so that two
 * changes of one order wait for each other instead of breaking the UNIQUE (order, item) rule or
 * overwriting each other. None of this can be proved by one request at a time.
 *
 * <p>Every request is a real HTTP-level call on its own thread, and all threads are released at
 * the same moment by a barrier. A race is a matter of luck, so each test is repeated: a lost lock
 * would show up in one of the repetitions, while a correct implementation passes every time,
 * because what is checked is the same whichever way the requests interleave.
 *
 * <p>The payment event does not exist before JACAG-24; the race between it and a cancel is
 * tested there, with the real consumer. At most eight requests are in flight, so the pool of ten
 * connections is never exhausted by requests that wait for a lock.
 */
class ApiConcurrencyIntegrationTest extends AbstractIntegrationTest {

  private static final int OK = 200;
  private static final int CREATED = 201;
  private static final int NO_CONTENT = 204;
  private static final int NOT_FOUND = 404;
  private static final int CONFLICT = 409;

  private static final String ANNA = annaToken();

  @BeforeEach
  void createTheCatalogueAndTheStub() {
    insertItem("Keyboard", "49.90");
    insertItem("Mouse", "19.90");
    UserServiceStub.respondWithUser(ANNA_EMAIL, 7, "Anna", "Petrova");
  }

  @RepeatedTest(5)
  void parallelCreatesAllSucceedAndEachOrderHasItsOwnLine() throws Exception {
    List<Callable<Integer>> requests = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      requests.add(statusOf(json(as(post("/api/orders"), ANNA), orderJson(1, 1))));
    }

    List<Integer> statuses = runTogether(requests);

    // There is no idempotency key (a known limit), so eight requests are eight orders.
    assertAll(
      () -> assertEquals(8, Collections.frequency(statuses, CREATED)),
      () -> assertEquals(8L, countRows("orders")),
      () -> assertEquals(8L, countRows("order_items")));
  }

  @RepeatedTest(5)
  void parallelDeletesOfOneOrderGiveOne204AndSeven404() throws Exception {
    long orderId = placeOrder(mockMvc, ANNA, orderJson(1, 1));
    List<Callable<Integer>> requests = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      requests.add(statusOf(as(delete("/api/orders/{id}", orderId), ANNA)));
    }

    List<Integer> statuses = runTogether(requests);

    // The loser of the lock finds the order deleted, which is the same as not finding it.
    assertAll(
      () -> assertEquals(1, Collections.frequency(statuses, NO_CONTENT),
        "exactly one request deletes the order"),
      () -> assertEquals(7, Collections.frequency(statuses, NOT_FOUND),
        "all other requests find nothing"),
      () -> assertEquals(true, jdbcTemplate.queryForObject(
        "SELECT deleted FROM orders WHERE id = ?", Boolean.class, orderId)),
      () -> assertEquals(1L, countRows("orders")));
  }

  @RepeatedTest(5)
  void parallelCancelsAreAllAnswered200AndTheOrderIsCancelled() throws Exception {
    long orderId = placeOrder(mockMvc, ANNA, orderJson(1, 1));
    List<Callable<Integer>> requests = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      requests.add(statusOf(as(patch("/api/orders/{id}/cancel", orderId), ANNA)));
    }

    List<Integer> statuses = runTogether(requests);

    assertAll(
      () -> assertEquals(8, Collections.frequency(statuses, OK)),
      () -> assertEquals("CANCELLED", statusOfOrder(orderId)));
  }

  @RepeatedTest(5)
  void parallelReplacementsOfTheLinesAreSerializedAndNeverBreakTheUniqueRule()
      throws Exception {
    long orderId = placeOrder(mockMvc, ANNA, orderJson(1, 1));
    List<Callable<Integer>> requests = new ArrayList<>();
    for (int quantity = 1; quantity <= 6; quantity++) {
      // Every request removes the keyboard line and adds or updates the mouse line.
      requests.add(statusOf(json(as(put("/api/orders/{id}", orderId), ANNA),
        orderJson(2, quantity))));
    }

    List<Integer> statuses = runTogether(requests);

    Set<BigDecimal> totalsOfTheRequests = Set.of(new BigDecimal("19.90"),
      new BigDecimal("39.80"), new BigDecimal("59.70"), new BigDecimal("79.60"),
      new BigDecimal("99.50"), new BigDecimal("119.40"));
    // Without the row lock two requests would insert the mouse line at the same time (UNIQUE).
    assertAll(
      () -> assertEquals(6, Collections.frequency(statuses, OK),
        "every replacement must succeed, one after the other"),
      () -> assertEquals(1L, countRows("order_items"), "one line of the mouse remains"),
      () -> assertTotalMatchesTheLines(orderId),
      () -> assertTrue(totalsOfTheRequests.contains(totalOf(orderId)),
        "the total is the total of one of the requests, never a mixture"));
  }

  @RepeatedTest(5)
  void replacementsAndCancelsRunInAnyOrderAndNoRequestEndsInAnError() throws Exception {
    long orderId = placeOrder(mockMvc, ANNA, orderJson(1, 1));
    // Even positions are replacements, odd positions are cancels.
    List<Callable<Integer>> requests = new ArrayList<>();
    for (int quantity = 1; quantity <= 4; quantity++) {
      requests.add(statusOf(json(as(put("/api/orders/{id}", orderId), ANNA),
        orderJson(2, quantity))));
      requests.add(statusOf(as(patch("/api/orders/{id}/cancel", orderId), ANNA)));
    }

    List<Integer> statuses = runTogether(requests);

    List<Integer> replacements = new ArrayList<>();
    List<Integer> cancels = new ArrayList<>();
    for (int i = 0; i < statuses.size(); i++) {
      (i % 2 == 0 ? replacements : cancels).add(statuses.get(i));
    }
    boolean aReplacementWon = replacements.contains(OK);
    // A replacement that ran before the first cancel is a 200; one that ran after it is a 409.
    assertAll(
      () -> assertEquals(4, Collections.frequency(cancels, OK), "every cancel succeeds"),
      () -> assertTrue(replacements.stream().allMatch(code -> code == OK || code == CONFLICT),
        "a replacement is a 200 or a 409 and never an error"),
      () -> assertEquals("CANCELLED", statusOfOrder(orderId)),
      () -> assertTotalMatchesTheLines(orderId),
      // The lines are those of a replacement that was answered 200, or the original ones.
      () -> assertEquals(aReplacementWon ? 0 : 1, linesOfItem(orderId, 1)),
      () -> assertEquals(aReplacementWon ? 1 : 0, linesOfItem(orderId, 2)));
  }

  @RepeatedTest(5)
  void aReplacementAndADeletionRunInEitherOrderAndTheDeletionAlwaysWins() throws Exception {
    long orderId = placeOrder(mockMvc, ANNA, orderJson(1, 1));
    List<Callable<Integer>> requests = List.of(
      statusOf(json(as(put("/api/orders/{id}", orderId), ANNA), orderJson(2, 2))),
      statusOf(as(delete("/api/orders/{id}", orderId), ANNA)));

    List<Integer> statuses = runTogether(requests);

    int replacement = statuses.get(0);
    assertAll(
      () -> assertEquals(NO_CONTENT, statuses.get(1)),
      // A replacement that came after the deletion finds no order.
      () -> assertTrue(replacement == OK || replacement == NOT_FOUND,
        "a replacement is a 200 or a 404 and never an error"),
      () -> assertEquals(true, jdbcTemplate.queryForObject(
        "SELECT deleted FROM orders WHERE id = ?", Boolean.class, orderId)),
      () -> assertTotalMatchesTheLines(orderId),
      () -> assertEquals(replacement == OK ? 1 : 0, linesOfItem(orderId, 2)));
  }

  // ---- helpers

  /** A request that returns the HTTP status of the answer, whatever it is. */
  private Callable<Integer> statusOf(MockHttpServletRequestBuilder request) {
    return () -> mockMvc.perform(request).andReturn().getResponse().getStatus();
  }

  /** Runs the tasks on separate threads, starts them at the same moment and collects results. */
  private List<Integer> runTogether(List<Callable<Integer>> tasks) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
    CyclicBarrier barrier = new CyclicBarrier(tasks.size());
    try {
      List<Future<Integer>> futures = new ArrayList<>();
      for (Callable<Integer> task : tasks) {
        futures.add(executor.submit(() -> {
          barrier.await(30, TimeUnit.SECONDS);
          return task.call();
        }));
      }
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> future : futures) {
        statuses.add(future.get(60, TimeUnit.SECONDS));
      }
      return statuses;
    } finally {
      executor.shutdownNow();
    }
  }

  private String statusOfOrder(long orderId) {
    return jdbcTemplate.queryForObject(
      "SELECT status FROM orders WHERE id = ?", String.class, orderId);
  }

  private BigDecimal totalOf(long orderId) {
    return jdbcTemplate.queryForObject(
      "SELECT total_price FROM orders WHERE id = ?", BigDecimal.class, orderId);
  }

  private int linesOfItem(long orderId, long itemId) {
    Integer count = jdbcTemplate.queryForObject(
      "SELECT count(*) FROM order_items WHERE order_id = ? AND item_id = ?", Integer.class,
      orderId, itemId);
    return count == null ? 0 : count;
  }

  /** The total of an order is always the sum of its lines, whichever request came last. */
  private void assertTotalMatchesTheLines(long orderId) {
    BigDecimal sum = jdbcTemplate.queryForObject(
      "SELECT COALESCE(SUM(unit_price * quantity), 0) FROM order_items WHERE order_id = ?",
      BigDecimal.class, orderId);
    assertEquals(0, totalOf(orderId).compareTo(sum),
      String.format("the total %s must equal the sum of the lines %s", totalOf(orderId), sum));
  }
}
