package com.innowise.orderservice.service;

import com.innowise.orderservice.entity.Order;

/**
 * Who calls the service: the id and the email from the access token and whether the caller is an
 * admin. The service layer knows nothing about tokens; the controller builds this record.
 *
 * @param userId the profile id of the caller ({@code null} for an admin without a profile)
 * @param email  the email of the caller, the key of the lookup in the User Service
 * @param admin  whether the caller is an admin
 */
public record Caller(Long userId, String email, boolean admin) {

  /** Tells whether the caller may see and change the order: an admin, or its owner. */
  public boolean canAccess(Order order) {
    return admin || (userId != null && userId.equals(order.getUserId()));
  }

  /** Hides the email: it must not reach a log line by accident. */
  @Override
  public String toString() {
    return String.format("Caller[userId=%s, admin=%s]", userId, admin);
  }
}
