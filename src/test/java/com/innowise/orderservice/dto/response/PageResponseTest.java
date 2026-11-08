package com.innowise.orderservice.dto.response;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/** Tests of {@link PageResponse}. */
class PageResponseTest {

  @Test
  void copiesContentAndPagingMetadata() {
    Page<String> page = new PageImpl<>(List.of("a", "b"), PageRequest.of(1, 2), 5);

    PageResponse<String> response = PageResponse.from(page);

    assertAll(
      () -> assertEquals(List.of("a", "b"), response.content()),
      () -> assertEquals(1, response.page()),
      () -> assertEquals(2, response.size()),
      () -> assertEquals(5L, response.totalElements()),
      () -> assertEquals(3, response.totalPages()));
  }

  @Test
  void describesEmptyPage() {
    Page<String> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);

    PageResponse<String> response = PageResponse.from(page);

    assertAll(
      () -> assertTrue(response.content().isEmpty()),
      () -> assertEquals(0, response.page()),
      () -> assertEquals(20, response.size()),
      () -> assertEquals(0L, response.totalElements()),
      () -> assertEquals(0, response.totalPages()));
  }
}
