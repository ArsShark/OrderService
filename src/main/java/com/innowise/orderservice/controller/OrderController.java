package com.innowise.orderservice.controller;

import com.innowise.orderservice.dto.request.OrderRequest;
import com.innowise.orderservice.dto.response.OrderDetailsResponse;
import com.innowise.orderservice.dto.response.PageResponse;
import com.innowise.orderservice.entity.OrderStatus;
import com.innowise.orderservice.exception.InvalidDateRangeException;
import com.innowise.orderservice.security.AccessRules;
import com.innowise.orderservice.security.Callers;
import com.innowise.orderservice.service.Caller;
import com.innowise.orderservice.service.OrderFacade;
import com.innowise.orderservice.service.OrderFilter;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * REST API of the orders.
 *
 * <p>The controller is thin: it builds the {@code Caller} out of the verified token, turns the
 * query parameters into an {@code OrderFilter} and calls the facade. Whose order it is, which
 * status allows which change and what the answers are is decided in the service layer; the
 * exceptions are mapped to statuses by {@code GlobalExceptionHandler}. The owner of a new order
 * is the profile of the token, never a field of the body.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

  private static final SortWhitelist SORT = SortWhitelist.of("status", "totalPrice", "userId",
      "createdAt", "updatedAt");

  private final OrderFacade orderFacade;

  public OrderController(OrderFacade orderFacade) {
    this.orderFacade = orderFacade;
  }

  /** Places an order for the profile of the token: 201 with the {@code Location}. */
  @PostMapping
  @PreAuthorize(AccessRules.ANY_ROLE)
  public ResponseEntity<OrderDetailsResponse> create(Authentication authentication,
      @Valid @RequestBody OrderRequest request) {
    OrderDetailsResponse created = orderFacade.create(Callers.owner(authentication), request);
    URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
        .path("/{id}").buildAndExpand(created.id()).toUri();
    return ResponseEntity.created(location).body(created);
  }

  /** Returns one order: 200, or 404 for a missing order and for the order of somebody else. */
  @GetMapping("/{id}")
  @PreAuthorize(AccessRules.ANY_ROLE)
  public ResponseEntity<OrderDetailsResponse> getById(@PathVariable Long id,
      Authentication authentication) {
    return ResponseEntity.ok(orderFacade.getById(id, Callers.from(authentication)));
  }

  /** Returns a page of the orders of everybody that match the filter. Admin only. */
  @GetMapping
  @PreAuthorize(AccessRules.ADMIN_ONLY)
  public ResponseEntity<PageResponse<OrderDetailsResponse>> getAll(
      @RequestParam(required = false) Instant createdFrom,
      @RequestParam(required = false) Instant createdBefore,
      @RequestParam(required = false) Set<OrderStatus> statuses,
      @RequestParam(required = false) Long userId,
      @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
    OrderFilter filter = filterOf(createdFrom, createdBefore, statuses, userId);
    return ResponseEntity.ok(
        PageResponse.from(orderFacade.findAll(filter, SORT.apply(pageable))));
  }

  /**
   * Returns a page of the own orders of the profile of the token. There is no {@code userId}
   * parameter: the owner is always the caller.
   */
  @GetMapping("/my")
  @PreAuthorize(AccessRules.ANY_ROLE)
  public ResponseEntity<PageResponse<OrderDetailsResponse>> getMy(Authentication authentication,
      @RequestParam(required = false) Instant createdFrom,
      @RequestParam(required = false) Instant createdBefore,
      @RequestParam(required = false) Set<OrderStatus> statuses,
      @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
    Caller owner = Callers.owner(authentication);
    OrderFilter filter = filterOf(createdFrom, createdBefore, statuses, null);
    return ResponseEntity.ok(PageResponse.from(
        orderFacade.findOwn(owner.userId(), filter, SORT.apply(pageable))));
  }

  /** Replaces the lines of an order that is still editable: 200. */
  @PutMapping("/{id}")
  @PreAuthorize(AccessRules.ANY_ROLE)
  public ResponseEntity<OrderDetailsResponse> update(@PathVariable Long id,
      Authentication authentication, @Valid @RequestBody OrderRequest request) {
    return ResponseEntity.ok(orderFacade.update(id, Callers.from(authentication), request));
  }

  /** Cancels an order: 200, and the same answer when it is already cancelled. */
  @PatchMapping("/{id}/cancel")
  @PreAuthorize(AccessRules.ANY_ROLE)
  public ResponseEntity<OrderDetailsResponse> cancel(@PathVariable Long id,
      Authentication authentication) {
    return ResponseEntity.ok(orderFacade.cancel(id, Callers.from(authentication)));
  }

  /** Deletes an order softly: 204. */
  @DeleteMapping("/{id}")
  @PreAuthorize(AccessRules.ANY_ROLE)
  public ResponseEntity<Void> delete(@PathVariable Long id, Authentication authentication) {
    orderFacade.delete(id, Callers.from(authentication));
    return ResponseEntity.noContent().build();
  }

  /** Builds the filter; an empty range is a mistake of the caller, not an empty page. */
  private static OrderFilter filterOf(Instant createdFrom, Instant createdBefore,
      Set<OrderStatus> statuses, Long userId) {
    if (createdFrom != null && createdBefore != null && !createdFrom.isBefore(createdBefore)) {
      throw new InvalidDateRangeException();
    }
    return new OrderFilter(createdFrom, createdBefore, statuses, userId);
  }
}
