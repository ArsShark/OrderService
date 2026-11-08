package com.innowise.orderservice.security;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Turns the claim {@code role} of an access token into one authority with the prefix
 * {@code ROLE_}.
 *
 * <p>Only the two roles that the Authentication Service issues are accepted, written exactly as
 * it writes them: {@code ADMIN} and {@code USER}. Anything else gives no authority at all, so a
 * token with an unknown, misspelled, lowercase or composite role ({@code "ADMIN USER"}) opens
 * nothing and every rule that asks for a role denies it. The default converter of Spring
 * Security is not used because it would split such a value into several authorities.
 */
public final class RoleAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

  /** The name of the claim that holds the role. */
  public static final String CLAIM_ROLE = "role";

  private static final Set<String> ROLES = Set.of("ADMIN", "USER");

  /**
   * Reads the role of a token.
   *
   * @param jwt a token that has already been verified
   * @return one authority, {@code ROLE_ADMIN} or {@code ROLE_USER}, or an empty list
   */
  @Override
  public Collection<GrantedAuthority> convert(Jwt jwt) {
    Object role = jwt.getClaims().get(CLAIM_ROLE);
    if (role instanceof String name && ROLES.contains(name)) {
      return List.of(new SimpleGrantedAuthority(String.format("ROLE_%s", name)));
    }
    return List.of();
  }
}
