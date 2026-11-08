package com.innowise.orderservice.security;

import com.innowise.orderservice.service.Caller;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Builds the {@link Caller} of the service layer from the verified access token.
 *
 * <p>The caller is the only thing the order service knows about the person who sends a request:
 * the id of the profile ({@code user_id}), the email and whether the role is {@code ADMIN}. They
 * come from the verified token and never from the request. Anything that is not a verified token
 * (another kind of authentication) is refused, so a mistake in the configuration closes the
 * door instead of opening it.
 *
 * <p>An admin has no profile unless somebody gave him one, so his token may have no
 * {@code user_id}. {@link #from(Authentication)} accepts that, because an admin may read and
 * change every order. {@link #owner(Authentication)} is for the operations that need an owner
 * (placing an order, listing own orders): no profile means no owner, whatever the role.
 */
public final class Callers {

  private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";
  private static final String USER_ID_CLAIM = "user_id";
  private static final String EMAIL_CLAIM = "email";

  private Callers() {
  }

  /**
   * Builds the caller of an operation that works on orders by their id.
   *
   * @param authentication the authentication of the request
   * @return the caller; {@code userId} and {@code email} are {@code null} when the token has none
   * @throws AccessDeniedException if the authentication is not a verified access token
   */
  public static Caller from(Authentication authentication) {
    Jwt jwt = tokenOf(authentication);
    boolean admin = authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .anyMatch(ADMIN_AUTHORITY::equals);
    return new Caller(userIdOf(jwt), emailOf(jwt), admin);
  }

  /**
   * Builds the caller of an operation that needs an owner: the token must carry the id of a
   * profile and an email.
   *
   * @param authentication the authentication of the request
   * @return the caller with a user id and an email
   * @throws AccessDeniedException if the authentication is not a verified access token, or the
   *     token has no {@code user_id} or no {@code email}
   */
  public static Caller owner(Authentication authentication) {
    Caller caller = from(authentication);
    if (caller.userId() == null || caller.email() == null) {
      throw new AccessDeniedException("The token has no profile");
    }
    return caller;
  }

  private static Jwt tokenOf(Authentication authentication) {
    if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
      return jwt;
    }
    throw new AccessDeniedException("The caller is not authenticated with an access token");
  }

  /** The claim is a number in a real token; anything else counts as "no profile". */
  private static Long userIdOf(Jwt jwt) {
    Object value = jwt.getClaim(USER_ID_CLAIM);
    return value instanceof Number number ? number.longValue() : null;
  }

  private static String emailOf(Jwt jwt) {
    String email = jwt.getClaimAsString(EMAIL_CLAIM);
    return email == null || email.isBlank() ? null : email;
  }
}
