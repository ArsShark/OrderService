package com.innowise.orderservice;

import com.innowise.orderservice.entity.Item;
import com.innowise.orderservice.entity.Order;
import com.innowise.orderservice.entity.OrderItem;
import com.innowise.orderservice.service.PriceCalculator;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Entities for tests that need no database. The id and the audit columns have no setters on
 * purpose, so they are set by reflection, the way Hibernate and JPA Auditing set them.
 */
public final class TestEntities {

  public static final Instant CREATED_AT = Instant.parse("2026-11-02T10:00:00Z");
  public static final Instant UPDATED_AT = Instant.parse("2026-11-02T11:00:00Z");

  private TestEntities() {
  }

  /** An item that looks like a loaded one: with an id and audit columns. */
  public static Item item(Long id, String name, String price) {
    Item item = new Item();
    item.setName(name);
    item.setPrice(new BigDecimal(price));
    stamp(item, id);
    return item;
  }

  /** A line of an order: the item, the quantity and the price kept at the time of the order. */
  public static OrderItem line(Item item, int quantity, String unitPrice) {
    OrderItem line = new OrderItem();
    line.setItem(item);
    line.setQuantity(quantity);
    line.setUnitPrice(new BigDecimal(unitPrice));
    return line;
  }

  /** A PENDING order that looks like a loaded one, with the total of its lines. */
  public static Order order(Long id, Long userId, String email, OrderItem... lines) {
    Order order = new Order();
    order.setUserId(userId);
    order.setUserEmail(email);
    for (OrderItem line : lines) {
      order.addItem(line);
    }
    order.setTotalPrice(new PriceCalculator().total(order.getItems()));
    stamp(order, id);
    return order;
  }

  /** Sets the id and the audit columns of an entity. */
  public static void stamp(Object entity, Long id) {
    ReflectionTestUtils.setField(entity, "id", id);
    ReflectionTestUtils.setField(entity, "createdAt", CREATED_AT);
    ReflectionTestUtils.setField(entity, "updatedAt", UPDATED_AT);
  }
}
