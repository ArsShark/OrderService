package com.innowise.orderservice.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the User Service is and how long to wait for it.
 *
 * @param baseUri the internal address of the User Service, for example
 *     {@code http://user-service:8082}; it comes from {@code USER_SERVICE_URI}, with a default
 *     per Spring profile, and is never the address of the API Gateway
 * @param connectTimeout how long to wait for the connection to open
 * @param readTimeout how long to wait for the answer after the request was sent
 */
@ConfigurationProperties(prefix = "app.user-service")
public record UserServiceProperties(
  String baseUri,
  @DefaultValue("2s") Duration connectTimeout,
  @DefaultValue("5s") Duration readTimeout) {

  /** Refuses a missing address and timeouts that are not positive, so the startup fails. */
  public UserServiceProperties {
    if (baseUri == null || baseUri.isBlank()) {
      throw new IllegalArgumentException("app.user-service.base-uri must not be blank");
    }
    if (connectTimeout.isZero() || connectTimeout.isNegative()) {
      throw new IllegalArgumentException("app.user-service.connect-timeout must be positive");
    }
    if (readTimeout.isZero() || readTimeout.isNegative()) {
      throw new IllegalArgumentException("app.user-service.read-timeout must be positive");
    }
  }
}
