package com.innowise.orderservice.dto.response;

import com.innowise.orderservice.entity.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * An order together with its owner: the answer of every endpoint except the deletion.
 *
 * <p>{@code user} is {@code null} in two different cases, told apart by
 * {@code userInfoAvailable}. If it is {@code true}, the User Service answered and nobody has the
 * email of the order (for example the profile was deleted). If it is {@code false}, the User
 * Service could not answer (it is down or too slow, or its circuit breaker is open); the order is
 * complete and correct, only the user data is missing, and asking again later may fill it.
 *
 * @param id the id of the order
 * @param userId the id of the owner
 * @param status the status of the order
 * @param totalPrice the total, calculated by the server
 * @param items the lines with the prices of the time of the order
 * @param createdAt when the order was created
 * @param updatedAt when the order was changed last
 * @param user the owner, if the User Service gave a profile
 * @param userInfoAvailable whether the User Service gave an answer at all
 */
public record OrderDetailsResponse(
  Long id,
  Long userId,
  OrderStatus status,
  BigDecimal totalPrice,
  List<OrderItemResponse> items,
  Instant createdAt,
  Instant updatedAt,
  UserInfo user,
  boolean userInfoAvailable) {
}
