package com.innowise.orderservice.service;

import com.innowise.orderservice.entity.OrderItem;
import com.innowise.orderservice.exception.OrderTotalTooLargeException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import org.springframework.stereotype.Component;

/** Calculates the total of an order from its lines. The client never sends a price. */
@Component
public class PriceCalculator {

  /** The largest value of {@code NUMERIC(12,2)}. */
  static final BigDecimal MAX_TOTAL = new BigDecimal("9999999999.99");

  /**
   * Sums {@code unitPrice * quantity} of the lines. The unit prices have two decimals, so the
   * sum is exact.
   *
   * @param lines the lines of the order
   * @return the total with two decimals
   * @throws OrderTotalTooLargeException if the total does not fit into the column
   */
  public BigDecimal total(Collection<OrderItem> lines) {
    BigDecimal total = BigDecimal.ZERO;
    for (OrderItem line : lines) {
      total = total.add(line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity())));
    }
    BigDecimal exact = total.setScale(2, RoundingMode.UNNECESSARY);
    if (exact.compareTo(MAX_TOTAL) > 0) {
      throw new OrderTotalTooLargeException();
    }
    return exact;
  }
}
