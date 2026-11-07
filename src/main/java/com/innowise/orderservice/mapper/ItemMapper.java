package com.innowise.orderservice.mapper;

import com.innowise.orderservice.dto.request.ItemRequest;
import com.innowise.orderservice.dto.response.ItemResponse;
import com.innowise.orderservice.entity.Item;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;

/**
 * Converts between {@link Item} and item DTOs.
 *
 * <p>The id, the {@code deleted} flag and the audit columns have no setters in the entity, so a
 * request can never set them.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ItemMapper {

  ItemResponse toResponse(Item item);

  /** Creates a new item from a request. */
  @Mapping(target = "price", qualifiedByName = "money")
  Item toEntity(ItemRequest request);

  /** Replaces the name and the price of an existing item. */
  @Mapping(target = "price", qualifiedByName = "money")
  void updateEntity(ItemRequest request, @MappingTarget Item item);

  /**
   * Gives a price exactly two decimals, like the database column, so the answer to a create
   * shows {@code 49.90} and not {@code 49.9}. The request constraint allows no more than two.
   */
  @Named("money")
  default BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY);
  }
}
