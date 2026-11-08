package com.innowise.orderservice.config;

import java.net.URI;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings that the service needs to verify the access tokens of the Authentication Service.
 *
 * <p>Both values are checked when the settings are bound, so a wrong value stops the startup
 * instead of failing on the first request.
 *
 * @param jwksUri the address of the JSON Web Key Set of the Authentication Service
 *     ({@code AUTH_JWKS_URI}); it must be the internal address of that service, never the public
 *     address of the Gateway, which does not route it. The default depends on the Spring profile
 *     and is defined in the profile files
 * @param jwtIssuer the expected value of the {@code iss} claim ({@code JWT_ISSUER}); it must be
 *     the same as in the Authentication Service
 */
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(String jwksUri, String jwtIssuer) {

  /**
   * Checks the settings.
   *
   * @throws IllegalArgumentException if the issuer is blank or the address is not an absolute
   *     {@code http} or {@code https} URI with a host
   */
  public SecurityProperties {
    if (jwtIssuer == null || jwtIssuer.isBlank()) {
      throw new IllegalArgumentException("app.security.jwt-issuer must not be blank");
    }
    requireHttpUri(jwksUri);
  }

  private static void requireHttpUri(String value) {
    String message = "app.security.jwks-uri must be an absolute http or https URI with a host";
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(message, e);
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    boolean httpScheme = scheme.equals("http") || scheme.equals("https");
    if (!httpScheme || uri.getHost() == null) {
      throw new IllegalArgumentException(message);
    }
  }
}
