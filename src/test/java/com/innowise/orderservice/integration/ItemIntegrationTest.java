package com.innowise.orderservice.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.innowise.orderservice.dto.request.ItemRequest;
import com.innowise.orderservice.dto.response.ItemResponse;
import com.innowise.orderservice.entity.Item;
import com.innowise.orderservice.exception.ItemNotFoundException;
import com.innowise.orderservice.repository.ItemRepository;
import com.innowise.orderservice.service.ItemService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * The catalog of items on a real database through the real transactional proxy: the audit
 * columns, the soft delete and the queries that hide deleted items.
 */
class ItemIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private ItemService itemService;

  @Autowired
  private ItemRepository itemRepository;

  @Test
  void createStoresTheItemWithTwoDecimalsAndTheAuditColumns() {
    ItemResponse created = itemService.create(new ItemRequest("Keyboard", new BigDecimal("49.9")));

    assertAll(
      () -> assertEquals(1L, created.id()),
      () -> assertEquals(new BigDecimal("49.90"), created.price()),
      () -> assertEquals(START, created.createdAt()),
      () -> assertEquals(START, created.updatedAt()),
      () -> assertEquals(1L, countRows("items")));
  }

  @Test
  void updateChangesTheFieldsAndMovesOnlyUpdatedAt() {
    long id = itemService.create(new ItemRequest("Keyboard", new BigDecimal("49.90"))).id();
    clock.advance(Duration.ofHours(1));

    ItemResponse updated = itemService.update(id, new ItemRequest("Mouse", new BigDecimal("19")));

    assertAll(
      () -> assertEquals("Mouse", updated.name()),
      () -> assertEquals(new BigDecimal("19.00"), updated.price()),
      () -> assertEquals(START, updated.createdAt()),
      () -> assertEquals(START.plus(Duration.ofHours(1)), updated.updatedAt()));
  }

  @Test
  void deleteHidesTheItemFromTheCatalogButKeepsTheRow() {
    long id = itemService.create(new ItemRequest("Keyboard", new BigDecimal("49.90"))).id();

    itemService.delete(id);

    assertAll(
      () -> assertThrows(ItemNotFoundException.class, () -> itemService.getById(id)),
      () -> assertThrows(ItemNotFoundException.class, () -> itemService.delete(id)),
      () -> assertThrows(ItemNotFoundException.class,
        () -> itemService.update(id, new ItemRequest("Mouse", new BigDecimal("1.00")))),
      () -> assertTrue(itemService.getAll(PageRequest.of(0, 10)).isEmpty()),
      () -> assertEquals(1L, countRows("items")),
      () -> assertEquals(Boolean.TRUE, jdbcTemplate.queryForObject(
        "SELECT deleted FROM items WHERE id = ?", Boolean.class, id)));
  }

  @Test
  void aDeletionMovesUpdatedAtThroughAuditing() {
    long id = itemService.create(new ItemRequest("Keyboard", new BigDecimal("49.90"))).id();
    clock.advance(Duration.ofMinutes(5));

    itemService.delete(id);

    OffsetDateTime updatedAt = jdbcTemplate.queryForObject(
      "SELECT updated_at FROM items WHERE id = ?", OffsetDateTime.class, id);
    assertEquals(START.plus(Duration.ofMinutes(5)), updatedAt.toInstant());
  }

  @Test
  void aPageHoldsOnlyLiveItemsAndKeepsTheTotals() {
    insertItem("Keyboard", "49.90");
    long gone = insertItem("Mouse", "19.90");
    insertItem("Monitor", "199.00");
    jdbcTemplate.update("UPDATE items SET deleted = true WHERE id = ?", gone);

    Page<ItemResponse> page = itemService.getAll(PageRequest.of(0, 1, Sort.by("id")));

    assertAll(
      () -> assertEquals(2L, page.getTotalElements()),
      () -> assertEquals(2, page.getTotalPages()),
      () -> assertEquals(List.of("Keyboard"),
        page.getContent().stream().map(ItemResponse::name).toList()));
  }

  @Test
  void theLookupByIdsReturnsOnlyLiveItems() {
    long keyboard = insertItem("Keyboard", "49.90");
    long mouse = insertItem("Mouse", "19.90");
    long monitor = insertItem("Monitor", "199.00");
    jdbcTemplate.update("UPDATE items SET deleted = true WHERE id = ?", mouse);

    Set<Long> found = itemRepository
      .findAllByIdInAndDeletedFalse(List.of(keyboard, mouse, monitor, 999L)).stream()
      .map(Item::getId)
      .collect(Collectors.toSet());

    assertEquals(Set.of(keyboard, monitor), found);
  }
}
