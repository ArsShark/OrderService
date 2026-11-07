package com.innowise.orderservice.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.innowise.orderservice.TestEntities;
import com.innowise.orderservice.entity.Order;
import org.junit.jupiter.api.Test;

class CallerTest {

  private static final Order ORDER = TestEntities.order(5L, 7L, "buyer@example.com");

  @Test
  void anOwnerAndAnAdminMayAccessTheOrderButAnotherUserMayNot() {
    assertAll(
      () -> assertTrue(new Caller(7L, "buyer@example.com", false).canAccess(ORDER)),
      () -> assertTrue(new Caller(1L, "admin@example.com", true).canAccess(ORDER)),
      () -> assertTrue(new Caller(null, "admin@example.com", true).canAccess(ORDER)),
      () -> assertFalse(new Caller(8L, "other@example.com", false).canAccess(ORDER)));
  }

  @Test
  void aCallerWithoutAProfileOwnsNothing() {
    assertFalse(new Caller(null, "someone@example.com", false).canAccess(ORDER));
  }

  @Test
  void theEmailDoesNotReachTheStringForm() {
    assertFalse(new Caller(7L, "buyer@example.com", false).toString().contains("buyer"));
  }
}
