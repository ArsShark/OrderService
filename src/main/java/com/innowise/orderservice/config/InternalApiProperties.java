package com.innowise.orderservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the calls to the internal endpoints of the other services.
 *
 * <p>{@link #toString()} is overridden on purpose: the generated one would print the key.
 *
 * @param apiKey the shared key sent in the header {@code X-Internal-Key}; it comes from the
 *     environment variable {@code INTERNAL_API_KEY}, has no default, and an empty value stops
 *     the startup
 */
@ConfigurationProperties(prefix = "app.internal")
public record InternalApiProperties(String apiKey) {

  /** Refuses a missing key, so the startup fails instead of every call being refused later. */
  public InternalApiProperties {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalArgumentException("app.internal.api-key must not be blank");
    }
  }

  @Override
  public String toString() {
    return "InternalApiProperties[apiKey=***]";
  }
}
