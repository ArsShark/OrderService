package com.innowise.orderservice.controller;

import com.innowise.orderservice.exception.InvalidSortPropertyException;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Restricts the properties a client may sort by and makes the sort order deterministic.
 *
 * <p>The sort names are part of the API contract, so they are checked in the controller layer,
 * before the service and the database are involved. The property {@code id} is always allowed
 * and is appended as the last sort key when the request does not sort by it: without a unique
 * tiebreaker the database may return rows with equal values in a different order for every
 * page, so pages can overlap or skip items.
 */
public final class SortWhitelist {

  private static final String ID = "id";

  private final Set<String> allowed;

  private SortWhitelist(Set<String> allowed) {
    this.allowed = allowed;
  }

  /**
   * Creates a whitelist.
   *
   * @param properties entity properties a client may sort by; {@code id} is added automatically
   */
  public static SortWhitelist of(String... properties) {
    Set<String> allowed = new TreeSet<>(Arrays.asList(properties));
    allowed.add(ID);
    return new SortWhitelist(allowed);
  }

  /**
   * Checks the requested sort and returns the same page request with {@code id} as tiebreaker.
   *
   * @throws InvalidSortPropertyException if any requested property is not allowed
   */
  public Pageable apply(Pageable pageable) {
    Sort sort = pageable.getSort();
    for (Sort.Order order : sort) {
      if (!allowed.contains(order.getProperty())) {
        throw new InvalidSortPropertyException(allowed);
      }
    }
    if (sort.getOrderFor(ID) == null) {
      sort = sort.and(Sort.by(ID));
    }
    return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
  }
}
