package com.innowise.orderservice.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.innowise.orderservice.entity.Order;
import com.innowise.orderservice.entity.OrderStatus;
import com.innowise.orderservice.repository.OrderRepository;
import com.innowise.orderservice.repository.specification.OrderSpecifications;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/**
 * The filter of the order list on a real database. The orders are created through the
 * repository with the test clock set to a chosen instant, so {@code createdAt} is known exactly
 * and the boundaries of the date range can be tested.
 *
 * <pre>
 * name  user  status          created
 * o1    7     PENDING         2026-11-01 00:00
 * o2    7     PAID            2026-11-02 00:00
 * o3    8     PAID            2026-11-03 00:00
 * o4    8     CANCELLED       2026-11-04 00:00
 * o5    7     PAYMENT_FAILED  2026-11-05 00:00
 * gone  7     PAID            2026-11-02 12:00  (deleted: never in a result)
 * </pre>
 */
class OrderSpecificationsIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private OrderRepository orderRepository;

  private final Map<String, Long> ids = new HashMap<>();

  @BeforeEach
  void seed() {
    ids.clear();
    save("o1", 7, OrderStatus.PENDING, day(1, 0));
    save("o2", 7, OrderStatus.PAID, day(2, 0));
    save("o3", 8, OrderStatus.PAID, day(3, 0));
    save("o4", 8, OrderStatus.CANCELLED, day(4, 0));
    save("o5", 7, OrderStatus.PAYMENT_FAILED, day(5, 0));
    save("gone", 7, OrderStatus.PAID, day(2, 12));
    jdbcTemplate.update("UPDATE orders SET deleted = true WHERE id = ?", ids.get("gone"));
  }

  @Test
  void theLowerBoundOfTheDateRangeIsInclusive() {
    assertEquals(idsOf("o3", "o4", "o5"),
      found(OrderSpecifications.createdFrom(day(3, 0))));
  }

  @Test
  void theUpperBoundOfTheDateRangeIsExclusive() {
    // o3 was created exactly at the bound and is not part of the range.
    assertEquals(idsOf("o1", "o2"),
      found(OrderSpecifications.createdBefore(day(3, 0))));
  }

  @Test
  void aDateRangeKeepsTheOrdersFromTheLowerBoundUpToTheUpperBound() {
    assertEquals(idsOf("o2", "o3", "o4"),
      found(OrderSpecifications.matches(day(2, 0), day(5, 0), null, null)));
  }

  @Test
  void anEmptyRangeFindsNothing() {
    assertEquals(List.of(),
      found(OrderSpecifications.matches(day(4, 0), day(2, 0), null, null)));
  }

  @Test
  void theStatusFilterAcceptsOneOrSeveralStatuses() {
    assertAll(
      () -> assertEquals(idsOf("o2", "o3"),
        found(OrderSpecifications.statusIn(List.of(OrderStatus.PAID)))),
      () -> assertEquals(idsOf("o2", "o3", "o4"),
        found(OrderSpecifications.statusIn(List.of(OrderStatus.PAID, OrderStatus.CANCELLED)))));
  }

  @Test
  void aMissingFilterMeansNoFilterButADeletedOrderIsNeverReturned() {
    List<String> everyOrder = List.of("o1", "o2", "o3", "o4", "o5");

    assertAll(
      () -> assertEquals(idsOf(everyOrder.toArray(String[]::new)),
        found(OrderSpecifications.matches(null, null, null, null))),
      () -> assertEquals(idsOf(everyOrder.toArray(String[]::new)),
        found(OrderSpecifications.statusIn(List.of()))));
  }

  @Test
  void theUserFilterKeepsTheOrdersOfOneUser() {
    assertAll(
      () -> assertEquals(idsOf("o1", "o2", "o5"),
        found(OrderSpecifications.userIdEquals(7L))),
      () -> assertEquals(idsOf("o3", "o4"),
        found(OrderSpecifications.userIdEquals(8L))),
      () -> assertEquals(List.of(),
        found(OrderSpecifications.userIdEquals(999L))));
  }

  @Test
  void allFiltersAreCombinedWithAnd() {
    // User 7 and PAID and from the 2nd: o2 only (gone is deleted, o3 belongs to user 8).
    assertEquals(idsOf("o2"), found(OrderSpecifications.matches(
      day(2, 0), day(6, 0), List.of(OrderStatus.PAID), 7L)));
  }

  @Test
  void aPageIsCutFromTheFilteredResultAndTheTotalIgnoresDeletedOrders() {
    Specification<Order> everything = OrderSpecifications.matches(null, null, null, null);
    Sort newestFirst = Sort.by(Sort.Direction.DESC, "createdAt");

    Page<Order> first = orderRepository.findAll(everything, PageRequest.of(0, 2, newestFirst));
    Page<Order> last = orderRepository.findAll(everything, PageRequest.of(2, 2, newestFirst));

    assertAll(
      () -> assertEquals(5L, first.getTotalElements()),
      () -> assertEquals(3, first.getTotalPages()),
      () -> assertEquals(idsOf("o5", "o4"), idsOf(first)),
      () -> assertEquals(idsOf("o1"), idsOf(last)));
  }

  private void save(String name, long userId, OrderStatus status, Instant createdAt) {
    clock.set(createdAt);
    Order order = new Order();
    order.setUserId(userId);
    order.setUserEmail(OWNER_EMAIL);
    moveTo(order, status);
    order.setTotalPrice(new BigDecimal("10.00"));
    ids.put(name, orderRepository.saveAndFlush(order).getId());
  }

  /** Brings a new order to the status; every status can be reached from PENDING. */
  private static void moveTo(Order order, OrderStatus status) {
    switch (status) {
      case PENDING -> { }
      case PAID -> order.markPaid();
      case PAYMENT_FAILED -> order.markPaymentFailed();
      case CANCELLED -> order.cancel();
    }
  }

  private static Instant day(int dayOfNovember, int hour) {
    return Instant.parse(String.format("2026-11-%02dT%02d:00:00Z", dayOfNovember, hour));
  }

  /** The ids of the named orders, in the order of the names. */
  private List<Long> idsOf(String... names) {
    return Arrays.stream(names).map(ids::get).toList();
  }

  private static List<Long> idsOf(Page<Order> page) {
    return page.getContent().stream().map(Order::getId).toList();
  }

  /** The ids found by a specification, sorted by id (orders were saved in the order of names). */
  private List<Long> found(Specification<Order> specification) {
    return idsOf(orderRepository.findAll(
      specification, PageRequest.of(0, 100, Sort.by("id"))));
  }
}
