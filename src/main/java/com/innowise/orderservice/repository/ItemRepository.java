package com.innowise.orderservice.repository;

import com.innowise.orderservice.entity.Item;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/**
 * Data access of items.
 *
 * <p>Like {@link OrderRepository} it extends the marker {@link Repository} and has no
 * {@code delete}: an item is hidden with {@link Item#markDeleted()}. Items have no
 * {@code @SQLRestriction} (the lines of old orders keep referring to a hidden item), so every
 * query of the catalog filters on {@code deleted} itself.
 */
public interface ItemRepository extends Repository<Item, Long> {

  /** Saves a new or a changed item and writes it to the database at once. */
  Item saveAndFlush(Item item);

  /** Finds an item that is not deleted. */
  Optional<Item> findByIdAndDeletedFalse(Long id);

  /** Finds a page of the items that are not deleted. */
  Page<Item> findAllByDeletedFalse(Pageable pageable);

  /** Finds the items with the given ids that are not deleted; an id that is gone is missing. */
  List<Item> findAllByIdInAndDeletedFalse(Collection<Long> ids);
}
