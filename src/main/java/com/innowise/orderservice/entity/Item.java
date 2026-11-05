package com.innowise.orderservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A product that can be ordered. Managed by an admin.
 *
 * <p>An item is never removed from the table: {@code deleted} hides it from the catalog, while
 * the order lines that refer to it keep working. For that reason this entity has no
 * {@code @SQLRestriction}: a lazy reference from an order line to a hidden row would fail. The
 * queries of the catalog filter on {@code deleted} explicitly.
 */
@Entity
@Table(name = "items")
@Getter
@Setter
@NoArgsConstructor
public class Item extends AuditableEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Setter(AccessLevel.NONE)
  private Long id;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  /** Current price. Orders copy it into {@link OrderItem#getUnitPrice()}. */
  @Column(name = "price", nullable = false, precision = 12, scale = 2)
  private BigDecimal price;

  @Column(name = "deleted", nullable = false)
  @Setter(AccessLevel.NONE)
  private boolean deleted;

  /**
   * Hides the item from the catalog. The row stays in the table, and the lines of old orders keep
   * their reference. The change goes through dirty checking, so JPA Auditing sets
   * {@code updatedAt} from the application clock. Calling it again changes nothing.
   */
  public void markDeleted() {
    deleted = true;
  }
}
