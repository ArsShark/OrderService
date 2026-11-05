package com.innowise.orderservice;

import com.innowise.orderservice.entity.Item;
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

  /** Sets the id and the audit columns of an entity. */
  public static void stamp(Object entity, Long id) {
    ReflectionTestUtils.setField(entity, "id", id);
    ReflectionTestUtils.setField(entity, "createdAt", CREATED_AT);
    ReflectionTestUtils.setField(entity, "updatedAt", UPDATED_AT);
  }
}
