package com.innowise.orderservice.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.innowise.orderservice.entity.Order;
import com.innowise.orderservice.entity.OrderItem;
import com.innowise.orderservice.entity.OrderStatus;
import com.innowise.orderservice.repository.OrderRepository;
import com.innowise.orderservice.repository.specification.OrderSpecifications;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Guards against the N+1 select problem. Reading a page of orders together with their lines and
 * the items of the lines must cost the same number of SQL statements for one order and for five
 * orders. Without batch fetching ({@code default_batch_fetch_size}) it would grow with the number
 * of orders, because every lazy collection and every lazy reference would load on its own.
 */
class QueryCountIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private OrderRepository orderRepository;

  @Autowired
  private EntityManagerFactory entityManagerFactory;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @Test
  void aPageOfOrdersCostsTheSameNumberOfStatementsForOneAndForFiveOrders() {
    seedOrders(1);
    ReadResult one = readPageWithLinesAndItems();

    resetState();
    seedOrders(5);
    ReadResult five = readPageWithLinesAndItems();

    assertAll(
      // The reads really touched every line and item.
      () -> assertEquals(2, one.itemNames().size()),
      () -> assertEquals(10, five.itemNames().size()),
      () -> assertTrue(one.statements() > 0),
      () -> assertEquals(one.statements(), five.statements(),
        String.format("one order: %d statements, five orders: %d",
          one.statements(), five.statements())));
  }

  /** Inserts the given number of PENDING orders, each with two lines of two different items. */
  private void seedOrders(int count) {
    for (int i = 1; i <= count; i++) {
      long first = insertItem(String.format("Item %d-1", i), "10.00");
      long second = insertItem(String.format("Item %d-2", i), "20.00");
      long orderId = insertOrder(7, "PENDING", "30.00");
      insertOrderItem(orderId, first, 1, "10.00");
      insertOrderItem(orderId, second, 1, "20.00");
    }
  }

  /** Reads the first page like a service does: the orders, then every line and its item. */
  private ReadResult readPageWithLinesAndItems() {
    Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    List<String> itemNames = new ArrayList<>();
    statistics.clear();

    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      Page<Order> page = orderRepository.findAll(
        OrderSpecifications.statusIn(List.of(OrderStatus.PENDING)),
        PageRequest.of(0, 20, Sort.by("id")));
      for (Order order : page) {
        for (OrderItem line : order.getItems()) {
          itemNames.add(line.getItem().getName());
        }
      }
    });

    return new ReadResult(statistics.getPrepareStatementCount(), itemNames);
  }

  private record ReadResult(long statements, List<String> itemNames) {
  }
}
