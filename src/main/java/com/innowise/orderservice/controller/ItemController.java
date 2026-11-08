package com.innowise.orderservice.controller;

import com.innowise.orderservice.dto.request.ItemRequest;
import com.innowise.orderservice.dto.response.ItemResponse;
import com.innowise.orderservice.dto.response.PageResponse;
import com.innowise.orderservice.security.AccessRules;
import com.innowise.orderservice.service.ItemService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * REST API of the catalogue of items. Any caller with a role reads it; only an administrator
 * adds, changes and deletes items. Deleting is soft: orders keep their lines.
 */
@RestController
@RequestMapping("/api/items")
public class ItemController {

  private static final SortWhitelist SORT = SortWhitelist.of("name", "price", "createdAt",
      "updatedAt");

  private final ItemService itemService;

  public ItemController(ItemService itemService) {
    this.itemService = itemService;
  }

  /** Creates an item: 201 with the {@code Location} of the new item. */
  @PostMapping
  @PreAuthorize(AccessRules.ADMIN_ONLY)
  public ResponseEntity<ItemResponse> create(@Valid @RequestBody ItemRequest request) {
    ItemResponse created = itemService.create(request);
    URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
        .path("/{id}").buildAndExpand(created.id()).toUri();
    return ResponseEntity.created(location).body(created);
  }

  /** Returns one item: 200, or 404 for a missing or deleted item. */
  @GetMapping("/{id}")
  @PreAuthorize(AccessRules.ANY_ROLE)
  public ResponseEntity<ItemResponse> getById(@PathVariable Long id) {
    return ResponseEntity.ok(itemService.getById(id));
  }

  /** Returns a page of the items that are not deleted. */
  @GetMapping
  @PreAuthorize(AccessRules.ANY_ROLE)
  public ResponseEntity<PageResponse<ItemResponse>> getAll(Pageable pageable) {
    return ResponseEntity.ok(PageResponse.from(itemService.getAll(SORT.apply(pageable))));
  }

  /** Replaces an item: 200, or 404. */
  @PutMapping("/{id}")
  @PreAuthorize(AccessRules.ADMIN_ONLY)
  public ResponseEntity<ItemResponse> update(@PathVariable Long id,
      @Valid @RequestBody ItemRequest request) {
    return ResponseEntity.ok(itemService.update(id, request));
  }

  /** Deletes an item softly: 204, or 404. */
  @DeleteMapping("/{id}")
  @PreAuthorize(AccessRules.ADMIN_ONLY)
  public ResponseEntity<Void> delete(@PathVariable Long id) {
    itemService.delete(id);
    return ResponseEntity.noContent().build();
  }
}
