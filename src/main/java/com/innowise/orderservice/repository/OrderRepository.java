package com.innowise.orderservice.repository;

import com.innowise.orderservice.entity.Order;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.Repository;

/**
 * Data access of orders.
 *
 * <p>The interface extends the marker {@link Repository} instead of {@code JpaRepository} on
 * purpose and redeclares only the operations the application needs. There is no {@code delete}
 * here, so an order cannot be removed from the table by mistake: it is deleted softly with
 * {@link Order#markDeleted()}. Spring Data routes the redeclared methods to the standard
 * implementation.
 *
 * <p>Deleted orders are hidden by the restriction on the entity, so no method here has to
 * filter on {@code deleted}.
 */
public interface OrderRepository extends Repository<Order, Long> {

  /**
   * Saves a new order together with its lines (cascade) or a changed one, and writes the
   * change to the database at once, so the generated id, {@code version} and
   * {@code updatedAt} are already set when the method returns.
   */
  Order saveAndFlush(Order order);

  /** Finds a not deleted order by id. The lines are loaded lazily on first access. */
  Optional<Order> findById(Long id);

  /**
   * Finds a not deleted order and locks its row until the end of the transaction
   * ({@code SELECT ... FOR UPDATE}). Every method that changes an order loads it this way: a
   * payment that arrives at the same moment waits for the change and then sees its result, and an
   * update of the lines cannot slip in after the order was paid. A change of the lines alone does
   * not touch the row of the order (the collection is the inverse side), so {@code @Version}
   * would not notice it.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select o from Order o where o.id = :id")
  Optional<Order> findByIdForUpdate(@Param("id") Long id);

  /**
   * Finds a page of not deleted orders that match the specification, see
   * {@link com.innowise.orderservice.repository.specification.OrderSpecifications}.
   */
  Page<Order> findAll(Specification<Order> specification, Pageable pageable);

  /** Finds a page of not deleted orders of one user. */
  Page<Order> findAllByUserId(Long userId, Pageable pageable);
}
