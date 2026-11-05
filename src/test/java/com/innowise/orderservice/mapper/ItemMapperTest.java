package com.innowise.orderservice.mapper;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.innowise.orderservice.TestEntities;
import com.innowise.orderservice.dto.request.ItemRequest;
import com.innowise.orderservice.dto.response.ItemResponse;
import com.innowise.orderservice.entity.Item;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/** Tests of the generated {@link ItemMapper}; no database is involved. */
@SpringJUnitConfig(ItemMapperImpl.class)
class ItemMapperTest {

  @Autowired
  private ItemMapper mapper;

  @Test
  void toResponseMapsAllFields() {
    Item item = TestEntities.item(7L, "Keyboard", "49.90");

    ItemResponse response = mapper.toResponse(item);

    assertAll(
      () -> assertEquals(7L, response.id()),
      () -> assertEquals("Keyboard", response.name()),
      () -> assertEquals(new BigDecimal("49.90"), response.price()),
      () -> assertEquals(TestEntities.CREATED_AT, response.createdAt()),
      () -> assertEquals(TestEntities.UPDATED_AT, response.updatedAt()));
  }

  @Test
  void toEntityCreatesAnItemThatIsNotDeletedAndGivesThePriceTwoDecimals() {
    Item item = mapper.toEntity(new ItemRequest("Keyboard", new BigDecimal("49.9")));

    assertAll(
      () -> assertNull(item.getId()),
      () -> assertEquals("Keyboard", item.getName()),
      () -> assertEquals(new BigDecimal("49.90"), item.getPrice()),
      () -> assertFalse(item.isDeleted()));
  }

  @Test
  void updateEntityReplacesTheNameAndThePriceAndKeepsTheRest() {
    Item item = TestEntities.item(7L, "Keyboard", "49.90");
    item.markDeleted();

    mapper.updateEntity(new ItemRequest("Mouse", new BigDecimal("19")), item);

    assertAll(
      () -> assertEquals(7L, item.getId()),
      () -> assertEquals("Mouse", item.getName()),
      () -> assertEquals(new BigDecimal("19.00"), item.getPrice()),
      () -> assertTrue(item.isDeleted()));
  }
}
