package com.innowise.orderservice.entity;

import com.innowise.orderservice.exception.OrderStatusTransitionException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

/**
 * An order of one user. The user lives in another service, so {@code userId} is a plain value
 * without a foreign key, and {@code userEmail} is a copy of the email from the token: the lookup
 * of the user in the User Service is done by email.
 *
 * <p>Deleted orders stay in the table with {@code deleted = true}. The restriction below hides
 * them from every query and from {@code find} of this entity.
 */
@Entity
@Table(name = "orders")
@SQLRestriction("deleted = false")
@Getter
@Setter
@NoArgsConstructor
public class Order extends AuditableEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Setter(AccessLevel.NONE)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "user_email", nullable = false, length = 255)
  private String userEmail;

  /**
   * Status of the order. There is no setter: the status changes only through {@link #cancel()},
   * {@link #markPaid()} and {@link #markPaymentFailed()}, which check the transition.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  @Setter(AccessLevel.NONE)
  private OrderStatus status = OrderStatus.PENDING;

  /** Sum of {@code unitPrice * quantity} of the lines; always calculated by the server. */
  @Column(name = "total_price", nullable = false, precision = 12, scale = 2)
  private BigDecimal totalPrice;

  @Column(name = "deleted", nullable = false)
  @Setter(AccessLevel.NONE)
  private boolean deleted;

  /**
   * Counter of optimistic locking. Hibernate adds {@code AND version = ?} to every update and
   * increments the value, so an update of an order that somebody else changed meanwhile touches
   * no row and fails with an optimistic locking exception instead of overwriting that change.
   */
  @Version
  @Column(name = "version", nullable = false)
  @Setter(AccessLevel.NONE)
  private long version;

  /**
   * Lines of the order. This is the inverse side of the association: the foreign key lives in
   * {@code order_items.order_id}. There is no setter, because replacing the collection instance
   * breaks {@code orphanRemoval}; use {@link #addItem} and {@link #removeItem}.
   */
  @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("id")
  @Setter(AccessLevel.NONE)
  private List<OrderItem> items = new ArrayList<>();

  /** Tells whether the lines of this order may still be replaced (PENDING or PAYMENT_FAILED). */
  public boolean isEditable() {
    return status.isEditable();
  }

  /**
   * Cancels the order. An order that is cancelled already stays as it is, so a repeated request
   * is harmless.
   *
   * @throws OrderStatusTransitionException if the order is PAID
   */
  public void cancel() {
    if (status == OrderStatus.CANCELLED) {
      return;
    }
    moveTo(OrderStatus.CANCELLED);
  }

  /**
   * Marks the order as paid. An order that is paid already stays as it is, so a repeated
   * payment event is harmless.
   *
   * @throws OrderStatusTransitionException if the order is CANCELLED
   */
  public void markPaid() {
    if (status == OrderStatus.PAID) {
      return;
    }
    moveTo(OrderStatus.PAID);
  }

  /**
   * Marks the last payment as failed; the client may pay again.
   *
   * @throws OrderStatusTransitionException if the order is PAID or CANCELLED
   */
  public void markPaymentFailed() {
    moveTo(OrderStatus.PAYMENT_FAILED);
  }

  private void moveTo(OrderStatus target) {
    if (!status.canMoveTo(target)) {
      throw new OrderStatusTransitionException(status, target);
    }
    status = target;
  }

  /**
   * Marks the order as deleted. The row stays in the table and disappears from every query of
   * this entity. The change goes through dirty checking, so JPA Auditing sets {@code updatedAt}
   * from the application clock. Calling it again changes nothing.
   */
  public void markDeleted() {
    deleted = true;
  }

  /**
   * Adds the line to this order and sets the owning side of the association.
   *
   * @param item the line to add
   */
  public void addItem(OrderItem item) {
    items.add(item);
    item.setOrder(this);
  }

  /**
   * Removes the line from this order; with {@code orphanRemoval} its row is deleted.
   *
   * @param item the line to remove
   */
  public void removeItem(OrderItem item) {
    items.remove(item);
    item.setOrder(null);
  }
}
