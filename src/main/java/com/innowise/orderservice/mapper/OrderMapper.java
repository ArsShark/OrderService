package com.innowise.orderservice.mapper;

import com.innowise.orderservice.dto.request.OrderItemRequest;
import com.innowise.orderservice.dto.response.OrderItemResponse;
import com.innowise.orderservice.dto.response.OrderResponse;
import com.innowise.orderservice.entity.Item;
import com.innowise.orderservice.entity.Order;
import com.innowise.orderservice.entity.OrderItem;
import java.math.BigDecimal;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;

/**
 * Converts between orders and their DTOs.
 *
 * <p>A whole order is not built from its request by MapStruct: the owner comes from the token,
 * the prices from the catalog and the total from the calculator. The mapper builds a single line
 * from a request line, an item and the price to keep, and the service assembles the order.
 *
 * <p>Reading the name of the item of a line initializes a lazy reference, so the mapper must be
 * called inside the transaction of the service.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface OrderMapper {

  OrderResponse toResponse(Order order);

  @Mapping(target = "itemId", source = "item.id")
  @Mapping(target = "itemName", source = "item.name")
  @Mapping(target = "lineTotal", source = "line", qualifiedByName = "lineTotal")
  OrderItemResponse toLineResponse(OrderItem line);

  /** The price of a whole line: the price of one piece times the quantity. */
  @Named("lineTotal")
  default BigDecimal lineTotal(OrderItem line) {
    return line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity()));
  }

  /**
   * Builds a line from a request line.
   *
   * @param request   the item id and the quantity asked for
   * @param item      the item of the line, already loaded and checked
   * @param unitPrice the price of one piece to keep in the line
   */
  @Mapping(target = "item", source = "item")
  @Mapping(target = "quantity", source = "request.quantity")
  @Mapping(target = "unitPrice", source = "unitPrice")
  OrderItem toLine(OrderItemRequest request, Item item, BigDecimal unitPrice);
}
