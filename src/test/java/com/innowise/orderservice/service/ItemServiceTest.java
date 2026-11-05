package com.innowise.orderservice.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.innowise.orderservice.TestEntities;
import com.innowise.orderservice.dto.request.ItemRequest;
import com.innowise.orderservice.dto.response.ItemResponse;
import com.innowise.orderservice.entity.Item;
import com.innowise.orderservice.exception.ItemNotFoundException;
import com.innowise.orderservice.mapper.ItemMapper;
import com.innowise.orderservice.repository.ItemRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests of {@link ItemService} with plain Mockito: no Spring context, no database. The
 * transactions need a Spring proxy and are covered by the integration tests. Strict stubs: a
 * stubbing that the service never uses fails the test.
 */
@ExtendWith(MockitoExtension.class)
class ItemServiceTest {

  private static final Long ITEM_ID = 7L;
  private static final ItemRequest REQUEST = new ItemRequest("Keyboard", new BigDecimal("49.90"));

  @Mock
  private ItemRepository itemRepository;

  @Mock
  private ItemMapper itemMapper;

  private ItemService itemService;

  @BeforeEach
  void createService() {
    itemService = new ItemService(itemRepository, itemMapper);
  }

  @Test
  void createSavesTheMappedItemAndReturnsTheResponse() {
    Item mapped = new Item();
    Item saved = TestEntities.item(ITEM_ID, "Keyboard", "49.90");
    ItemResponse expected = response(ITEM_ID);
    when(itemMapper.toEntity(REQUEST)).thenReturn(mapped);
    when(itemRepository.saveAndFlush(mapped)).thenReturn(saved);
    when(itemMapper.toResponse(saved)).thenReturn(expected);

    assertSame(expected, itemService.create(REQUEST));
  }

  @Test
  void getByIdReturnsTheItem() {
    Item item = TestEntities.item(ITEM_ID, "Keyboard", "49.90");
    ItemResponse expected = response(ITEM_ID);
    when(itemRepository.findByIdAndDeletedFalse(ITEM_ID)).thenReturn(Optional.of(item));
    when(itemMapper.toResponse(item)).thenReturn(expected);

    assertSame(expected, itemService.getById(ITEM_ID));
  }

  @Test
  void aMissingOrDeletedItemIsNotFoundByEveryOperation() {
    when(itemRepository.findByIdAndDeletedFalse(ITEM_ID)).thenReturn(Optional.empty());

    assertAll(
      () -> assertEquals("Item with id 7 not found",
        assertThrows(ItemNotFoundException.class, () -> itemService.getById(ITEM_ID))
          .getMessage()),
      () -> assertThrows(ItemNotFoundException.class,
        () -> itemService.update(ITEM_ID, REQUEST)),
      () -> assertThrows(ItemNotFoundException.class, () -> itemService.delete(ITEM_ID)),
      () -> verifyNoInteractions(itemMapper),
      () -> verify(itemRepository, never()).saveAndFlush(any()));
  }

  @Test
  void getAllMapsEveryElementAndKeepsThePageMetadata() {
    Item first = TestEntities.item(1L, "Keyboard", "49.90");
    Item second = TestEntities.item(2L, "Mouse", "19.90");
    Pageable pageable = PageRequest.of(0, 2);
    when(itemRepository.findAllByDeletedFalse(pageable))
      .thenReturn(new PageImpl<>(List.of(first, second), pageable, 5));
    when(itemMapper.toResponse(first)).thenReturn(response(1L));
    when(itemMapper.toResponse(second)).thenReturn(response(2L));

    Page<ItemResponse> page = itemService.getAll(pageable);

    assertAll(
      () -> assertEquals(List.of(1L, 2L),
        page.getContent().stream().map(ItemResponse::id).toList()),
      () -> assertEquals(5, page.getTotalElements()),
      () -> assertEquals(3, page.getTotalPages()));
  }

  @Test
  void updateReplacesTheFieldsAndSavesTheItem() {
    Item item = TestEntities.item(ITEM_ID, "Old", "1.00");
    ItemResponse expected = response(ITEM_ID);
    when(itemRepository.findByIdAndDeletedFalse(ITEM_ID)).thenReturn(Optional.of(item));
    when(itemRepository.saveAndFlush(item)).thenReturn(item);
    when(itemMapper.toResponse(item)).thenReturn(expected);

    ItemResponse result = itemService.update(ITEM_ID, REQUEST);

    InOrder order = inOrder(itemMapper, itemRepository);
    order.verify(itemMapper).updateEntity(REQUEST, item);
    order.verify(itemRepository).saveAndFlush(item);
    assertSame(expected, result);
  }

  @Test
  void deleteMarksTheItemDeletedAndSavesIt() {
    Item item = TestEntities.item(ITEM_ID, "Keyboard", "49.90");
    when(itemRepository.findByIdAndDeletedFalse(ITEM_ID)).thenReturn(Optional.of(item));

    itemService.delete(ITEM_ID);

    assertAll(
      () -> assertTrue(item.isDeleted()),
      () -> verify(itemRepository).saveAndFlush(item));
  }

  @Test
  void aLookupNeverChangesTheItem() {
    Item item = TestEntities.item(ITEM_ID, "Keyboard", "49.90");
    when(itemRepository.findByIdAndDeletedFalse(ITEM_ID)).thenReturn(Optional.of(item));
    when(itemMapper.toResponse(item)).thenReturn(response(ITEM_ID));

    itemService.getById(ITEM_ID);

    assertFalse(item.isDeleted());
  }

  private static ItemResponse response(Long id) {
    return new ItemResponse(id, "Keyboard", new BigDecimal("49.90"),
      TestEntities.CREATED_AT, TestEntities.UPDATED_AT);
  }
}
