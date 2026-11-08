package com.innowise.orderservice.security;

/**
 * The expressions of {@code @PreAuthorize} on the controllers, in one place, so a test can pin
 * which rule each handler declares.
 *
 * <p>They only check the role. Whose order it is, is decided by the order service from the
 * caller built out of the token: the order is loaded anyway, so one query answers it and a
 * foreign order stays indistinguishable from a missing one.
 */
public final class AccessRules {

  /** Only an administrator. */
  public static final String ADMIN_ONLY = "hasRole('ADMIN')";

  /** Any caller with a role of this service: an administrator or a user. */
  public static final String ANY_ROLE = "hasAnyRole('ADMIN', 'USER')";

  private AccessRules() {
  }
}
