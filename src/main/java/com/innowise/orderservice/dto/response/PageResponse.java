package com.innowise.orderservice.dto.response;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Stable JSON shape of one page of results.
 *
 * <p>Spring Data's {@code PageImpl} is not returned directly: its JSON structure is not a
 * supported contract and may change between versions.
 *
 * @param content items of the requested page
 * @param page zero-based index of the page
 * @param size requested page size
 * @param totalElements number of items across all pages
 * @param totalPages number of pages
 * @param <T> type of the items
 */
public record PageResponse<T>(
  List<T> content, int page, int size, long totalElements, int totalPages) {

  /** Copies the content and the paging metadata of a Spring Data page. */
  public static <T> PageResponse<T> from(Page<T> page) {
    return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
      page.getTotalElements(), page.getTotalPages());
  }
}
