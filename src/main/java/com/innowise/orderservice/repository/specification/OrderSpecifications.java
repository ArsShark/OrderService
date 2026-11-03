package com.innowise.orderservice.repository.specification;

import com.innowise.orderservice.entity.Order;
import com.innowise.orderservice.entity.OrderStatus;
import java.time.Instant;
import java.util.Collection;
import org.springframework.data.jpa.domain.Specification;

/**
 * Building blocks of the order filter. Every method returns an unrestricted specification when
 * its value is {@code null} (or an empty collection), so the filters that a caller did not set
 * simply do not appear in the query.
 */
public final class OrderSpecifications {

  private OrderSpecifications() {
  }

  /**
   * Combines all filters with AND.
   *
   * @param createdFrom   earliest creation time, inclusive; {@code null} for no lower bound
   * @param createdBefore latest creation time, exclusive; {@code null} for no upper bound
   * @param statuses      allowed statuses; {@code null} or empty for any status
   * @param userId        owner of the orders; {@code null} for every owner
   */
  public static Specification<Order> matches(Instant createdFrom, Instant createdBefore,
                                             Collection<OrderStatus> statuses, Long userId) {
    return Specification.allOf(
      createdFrom(createdFrom),
      createdBefore(createdBefore),
      statusIn(statuses),
      userIdEquals(userId));
  }

  /** Orders created at the given instant or later: {@code created_at >= from}. */
  public static Specification<Order> createdFrom(Instant from) {
    if (from == null) {
      return Specification.unrestricted();
    }
    return (root, query, builder) ->
      builder.greaterThanOrEqualTo(root.<Instant>get("createdAt"), from);
  }

  /**
   * Orders created strictly before the given instant: {@code created_at < before}.
   *
   * <p>With the inclusive lower bound of {@link #createdFrom} the range is half-open,
   * {@code [from, before)}: ranges placed one after another neither overlap nor leave a gap, and
   * there is no question what happens to a row exactly on the boundary.
   */
  public static Specification<Order> createdBefore(Instant before) {
    if (before == null) {
      return Specification.unrestricted();
    }
    return (root, query, builder) -> builder.lessThan(root.<Instant>get("createdAt"), before);
  }

  /** Orders whose status is one of the given ones: {@code status IN (...)}. */
  public static Specification<Order> statusIn(Collection<OrderStatus> statuses) {
    if (statuses == null || statuses.isEmpty()) {
      return Specification.unrestricted();
    }
    return (root, query, builder) -> root.get("status").in(statuses);
  }

  /** Orders of one user. */
  public static Specification<Order> userIdEquals(Long userId) {
    if (userId == null) {
      return Specification.unrestricted();
    }
    return (root, query, builder) -> builder.equal(root.get("userId"), userId);
  }
}
