package com.innowise.orderservice.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

/**
 * Checks the profile files without starting Spring: the {@code docker} profile gives a container
 * its addresses, every default can be overridden by an environment variable, and no file holds a
 * credential. A mistake in a property name would otherwise show only when the container starts.
 */
class SpringProfilesTest {

  private static final String DOCKER_FILE = "application-docker.yml";
  private static final String LOCAL_FILE = "application-local.yml";
  private static final String BASE_FILE = "application.yml";

  @Test
  void dockerDefaultsAreTheServiceNamesOfTheComposeNetwork() {
    PropertySourcesPropertyResolver resolver = resolverFor(DOCKER_FILE, Map.of());

    assertAll(
        () -> assertEquals("jdbc:postgresql://postgres:5432/order_service_db",
            resolver.getProperty("spring.datasource.url")),
        () -> assertEquals("http://authentication-service:8081/.well-known/jwks.json",
            resolver.getProperty("app.security.jwks-uri")),
        () -> assertEquals("http://user-service:8082",
            resolver.getProperty("app.user-service.base-uri")));
  }

  @Test
  void environmentVariablesOverrideEveryDockerDefault() {
    PropertySourcesPropertyResolver resolver = resolverFor(DOCKER_FILE, Map.of(
        "DB_HOST", "db.internal",
        "DB_PORT", "6543",
        "DB_NAME", "orders",
        "AUTH_JWKS_URI", "http://auth.internal:9001/jwks",
        "USER_SERVICE_URI", "http://users.internal:9002"));

    assertAll(
        () -> assertEquals("jdbc:postgresql://db.internal:6543/orders",
            resolver.getProperty("spring.datasource.url")),
        () -> assertEquals("http://auth.internal:9001/jwks",
            resolver.getProperty("app.security.jwks-uri")),
        () -> assertEquals("http://users.internal:9002",
            resolver.getProperty("app.user-service.base-uri")));
  }

  @Test
  void dockerDefaultsPassTheChecksOfTheSettings() {
    PropertySourcesPropertyResolver resolver = resolverFor(DOCKER_FILE, Map.of());
    String jwksUri = resolver.getProperty("app.security.jwks-uri");
    String baseUri = resolver.getProperty("app.user-service.base-uri");

    assertAll(
        () -> assertDoesNotThrow(
            () -> new SecurityProperties(jwksUri, "authentication-service")),
        () -> assertDoesNotThrow(() -> new UserServiceProperties(
            baseUri, Duration.ofSeconds(2), Duration.ofSeconds(5))));
  }

  @Test
  void bothProfilesDefineTheSameKeys() {
    assertEquals(keysOf(LOCAL_FILE), keysOf(DOCKER_FILE));
  }

  @Test
  void theBaseConfigurationHasNoDatasourceUrl() {
    assertFalse(keysOf(BASE_FILE).contains("spring.datasource.url"));
  }

  @ParameterizedTest
  @ValueSource(strings = {DOCKER_FILE, LOCAL_FILE})
  void profileFilesHoldNoCredentials(String file) {
    Set<String> keys = keysOf(file);

    assertTrue(keys.stream().noneMatch(SpringProfilesTest::looksLikeACredential), keys::toString);
  }

  private static boolean looksLikeACredential(String key) {
    String lower = key.toLowerCase(Locale.ROOT);
    return lower.contains("password") || lower.contains("username") || lower.contains("secret")
        || lower.contains("api-key");
  }

  /** The values of the file, with the given environment variables in front of them. */
  private static PropertySourcesPropertyResolver resolverFor(
      String file, Map<String, Object> environment) {
    MutablePropertySources sources = new MutablePropertySources();
    sources.addFirst(new MapPropertySource("environment", new HashMap<>(environment)));
    for (PropertySource<?> source : load(file)) {
      sources.addLast(source);
    }
    return new PropertySourcesPropertyResolver(sources);
  }

  private static Set<String> keysOf(String file) {
    Set<String> keys = new TreeSet<>();
    for (PropertySource<?> source : load(file)) {
      keys.addAll(List.of(((EnumerablePropertySource<?>) source).getPropertyNames()));
    }
    return keys;
  }

  private static List<PropertySource<?>> load(String file) {
    try {
      return new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
