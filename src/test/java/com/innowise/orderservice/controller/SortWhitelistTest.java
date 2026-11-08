package com.innowise.orderservice.controller;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.innowise.orderservice.exception.InvalidSortPropertyException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Tests of {@link SortWhitelist}. */
class SortWhitelistTest {

  private final SortWhitelist whitelist = SortWhitelist.of("name", "surname");

  @Test
  void keepsAllowedSortAndAppendsIdAsTiebreaker() {
    Pageable pageable = PageRequest.of(2, 10, Sort.by(Sort.Direction.DESC, "name"));

    Pageable result = whitelist.apply(pageable);

    assertAll(
      () -> assertEquals(2, result.getPageNumber()),
      () -> assertEquals(10, result.getPageSize()),
      () -> assertEquals(Sort.by(Sort.Order.desc("name"), Sort.Order.asc("id")),
        result.getSort()));
  }

  @Test
  void doesNotDuplicateIdWhenRequestAlreadySortsByIt() {
    Pageable result = whitelist.apply(PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "id")));

    assertEquals(Sort.by(Sort.Order.desc("id")), result.getSort());
  }

  @Test
  void sortsByIdWhenRequestHasNoSort() {
    Pageable result = whitelist.apply(PageRequest.of(0, 20));

    assertEquals(Sort.by("id"), result.getSort());
  }

  @Test
  void rejectsUnknownPropertyWithoutEchoingIt() {
    Pageable pageable = PageRequest.of(0, 20, Sort.by("password"));

    InvalidSortPropertyException exception =
      assertThrows(InvalidSortPropertyException.class, () -> whitelist.apply(pageable));

    assertAll(
      () -> assertTrue(exception.getMessage().contains("[id, name, surname]")),
      () -> assertFalse(exception.getMessage().contains("password")));
  }

  @Test
  void rejectsRequestWhenOnlyOneOfSeveralPropertiesIsUnknown() {
    Pageable pageable = PageRequest.of(0, 20, Sort.by("name", "number"));

    assertThrows(InvalidSortPropertyException.class, () -> whitelist.apply(pageable));
  }
}
