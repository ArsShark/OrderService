package com.innowise.orderservice.validation;

import com.innowise.orderservice.dto.request.OrderItemRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks {@link UniqueItems}. A {@code null} list and a {@code null} element or item id are not
 * judged here: {@code @NotNull} and {@code @NotEmpty} report those.
 */
public class UniqueItemsValidator
  implements ConstraintValidator<UniqueItems, List<OrderItemRequest>> {

  @Override
  public boolean isValid(List<OrderItemRequest> lines, ConstraintValidatorContext context) {
    if (lines == null) {
      return true;
    }
    Set<Long> seen = new HashSet<>();
    for (OrderItemRequest line : lines) {
      if (line != null && line.itemId() != null && !seen.add(line.itemId())) {
        return false;
      }
    }
    return true;
  }
}
