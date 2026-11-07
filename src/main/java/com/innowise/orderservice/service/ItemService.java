package com.innowise.orderservice.service;

import com.innowise.orderservice.dto.request.ItemRequest;
import com.innowise.orderservice.dto.response.ItemResponse;
import com.innowise.orderservice.entity.Item;
import com.innowise.orderservice.exception.ItemNotFoundException;
import com.innowise.orderservice.mapper.ItemMapper;
import com.innowise.orderservice.repository.ItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic of the catalog of items.
 *
 * <p>The class is read-only by default; every method that writes opts in with
 * {@link Transactional}. A deleted item behaves like a missing one for every operation here;
 * the orders that contain it are not affected.
 */
@Service
@Transactional(readOnly = true)
public class ItemService {

  private static final Logger LOGGER = LoggerFactory.getLogger(ItemService.class);

  private final ItemRepository itemRepository;
  private final ItemMapper itemMapper;

  public ItemService(ItemRepository itemRepository, ItemMapper itemMapper) {
    this.itemRepository = itemRepository;
    this.itemMapper = itemMapper;
  }

  /**
   * Creates an item.
   *
   * @param request name and price
   * @return the created item
   */
  @Transactional
  public ItemResponse create(ItemRequest request) {
    Item saved = itemRepository.saveAndFlush(itemMapper.toEntity(request));
    LOGGER.info("Created item with id {}", saved.getId());
    return itemMapper.toResponse(saved);
  }

  /**
   * Returns an item.
   *
   * @throws ItemNotFoundException if there is no such item or it is deleted
   */
  public ItemResponse getById(Long id) {
    return itemMapper.toResponse(find(id));
  }

  /** Returns a page of the items that are not deleted. */
  public Page<ItemResponse> getAll(Pageable pageable) {
    return itemRepository.findAllByDeletedFalse(pageable).map(itemMapper::toResponse);
  }

  /**
   * Replaces the name and the price of an item. Orders that exist already keep the price they
   * were made with.
   *
   * @throws ItemNotFoundException if there is no such item or it is deleted
   */
  @Transactional
  public ItemResponse update(Long id, ItemRequest request) {
    Item item = find(id);
    itemMapper.updateEntity(request, item);
    Item saved = itemRepository.saveAndFlush(item);
    LOGGER.info("Updated item with id {}", id);
    return itemMapper.toResponse(saved);
  }

  /**
   * Deletes an item softly. The row stays; the item disappears from the catalog and cannot be
   * ordered any more.
   *
   * @throws ItemNotFoundException if there is no such item or it is already deleted
   */
  @Transactional
  public void delete(Long id) {
    Item item = find(id);
    item.markDeleted();
    itemRepository.saveAndFlush(item);
    LOGGER.info("Deleted item with id {}", id);
  }

  private Item find(Long id) {
    return itemRepository.findByIdAndDeletedFalse(id)
      .orElseThrow(() -> new ItemNotFoundException(id));
  }
}
