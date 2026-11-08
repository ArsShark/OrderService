package com.innowise.orderservice.config;

import com.innowise.orderservice.security.FailClosedJwtDecoder;
import com.innowise.orderservice.security.RoleAuthoritiesConverter;
import com.innowise.orderservice.security.TokenTypeValidator;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * Creates the decoder that verifies the access tokens and the converter that reads their role.
 *
 * <p>This service is a resource server: it never signs a token. The public key comes from the
 * JSON Web Key Set of the Authentication Service. The key is fetched on the first token and not
 * at startup, so the start order of the services does not matter, and it is cached.
 *
 * <p>The beans are created as plain method calls too ({@code new JwtConfig().jwtDecoder(...)}),
 * so unit tests can build the very same decoder without a Spring context.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
public class JwtConfig {

  /** Time to connect to the Authentication Service and time to wait for its answer. */
  static final Duration JWKS_TIMEOUT = Duration.ofSeconds(2);

  /** The largest answer that is read; a key set with one key is about half a kilobyte. */
  static final int JWKS_SIZE_LIMIT_BYTES = 64 * 1024;

  /**
   * How long the key set is used before it is fetched again. The Authentication Service sends
   * {@code max-age=300}, the same five minutes.
   */
  static final Duration JWKS_CACHE_TTL = Duration.ofMinutes(5);

  /** How long a request waits while another request fetches the key set. */
  static final Duration JWKS_REFRESH_WAIT = Duration.ofSeconds(5);

  /** The shortest time between two fetches of the key set; see {@link #jwkSource(String)}. */
  static final Duration JWKS_MIN_FETCH_INTERVAL = Duration.ofSeconds(30);

  /**
   * Creates the decoder that checks the signature (RS256 only), the issuer, the time and the type
   * of a token.
   *
   * <p>The time comes from the application {@link Clock}, not from the system clock, and there is
   * no clock skew: the default of {@link JwtTimestampValidator} is 60 seconds, which would keep an
   * expired token alive for another minute. A token without an {@code exp} claim is rejected.
   * The same rules are used in the Authentication Service, so both services agree on when a token
   * ends.
   *
   * @param properties the address of the key set and the expected issuer
   * @param clock the application clock
   * @return the decoder; it never throws anything but a {@code BadJwtException} for a token that
   *     is not accepted
   */
  @Bean
  public JwtDecoder jwtDecoder(SecurityProperties properties, Clock clock) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(jwkSource(properties.jwksUri()))
        .jwsAlgorithm(SignatureAlgorithm.RS256)
        .build();
    JwtTimestampValidator timestampValidator = new JwtTimestampValidator(Duration.ZERO);
    timestampValidator.setClock(clock);
    timestampValidator.setAllowEmptyExpiryClaim(false);
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
        List.<OAuth2TokenValidator<Jwt>>of(
            timestampValidator,
            new JwtIssuerValidator(properties.jwtIssuer()),
            new TokenTypeValidator())));
    return new FailClosedJwtDecoder(decoder);
  }

  /**
   * Creates the converter from a verified token to an authentication. The principal name is the
   * claim {@code sub} (the id of the account); the authority comes from the claim {@code role}.
   *
   * @return the converter
   */
  @Bean
  public JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(new RoleAuthoritiesConverter());
    return converter;
  }

  /**
   * Creates the source of the public key.
   *
   * <p>It is not the default of {@code NimbusJwtDecoder.withJwkSetUri(...)}, for two reasons. The
   * default turns the rate limit of the key source off, so every token with an unknown {@code kid}
   * would make this service call the Authentication Service again, and anybody can send such a
   * token without logging in. Here the key source refreshes the key set because of an unknown key
   * at most once per {@link #JWKS_MIN_FETCH_INTERVAL}; the other such tokens are rejected without
   * a call. The default also waits up to 30 seconds for the answer; here the connection and the
   * answer are limited to {@link #JWKS_TIMEOUT} each and the answer to
   * {@link #JWKS_SIZE_LIMIT_BYTES}, so a stopped or a slow Authentication Service cannot hang the
   * requests.
   *
   * <p>Nothing is fetched here: the first fetch happens when the first token is verified.
   *
   * @param jwksUri the address of the key set
   * @return the source
   * @throws IllegalArgumentException if the address is not a valid URL
   */
  static JWKSource<SecurityContext> jwkSource(String jwksUri) {
    URL url;
    try {
      url = URI.create(jwksUri).toURL();
    } catch (MalformedURLException e) {
      throw new IllegalArgumentException("app.security.jwks-uri is not a valid URL", e);
    }
    int timeoutMillis = Math.toIntExact(JWKS_TIMEOUT.toMillis());
    DefaultResourceRetriever retriever =
        new DefaultResourceRetriever(timeoutMillis, timeoutMillis, JWKS_SIZE_LIMIT_BYTES);
    return JWKSourceBuilder.<SecurityContext>create(url, retriever)
        .cache(JWKS_CACHE_TTL.toMillis(), JWKS_REFRESH_WAIT.toMillis())
        .refreshAheadCache(false)
        .rateLimited(JWKS_MIN_FETCH_INTERVAL.toMillis())
        .build();
  }
}
