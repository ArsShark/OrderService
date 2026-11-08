package com.innowise.orderservice.integration;

import com.innowise.orderservice.JwksTestServer;
import com.innowise.orderservice.TestKeys;
import com.innowise.orderservice.TestTokens;
import com.innowise.orderservice.TestcontainersConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * Base class of the integration tests: the whole application on a real PostgreSQL container.
 *
 * <p>All subclasses share one Spring context and with it one container, because the container is
 * a bean of the context and a second context would start a second one. So subclasses must not
 * change the context: no {@code @MockitoBean}, {@code @TestPropertySource},
 * {@code @DirtiesContext} or extra {@code @Import}. Properties that every test needs belong to
 * this class.
 *
 * <p>Hibernate statistics are on, so a test can count the SQL statements of an operation. The
 * User Service is played by {@link UserServiceStub}; its address is set below, and the internal
 * key of the application is {@link UserServiceStub#KEY}.
 *
 * <p>The tokens are verified the real way. A small HTTP server plays the key set endpoint of the
 * Authentication Service, and the application reads its address from {@code app.security.jwks-uri}
 * like in production. {@link #mockMvc} sends the token of an admin with every request; a test
 * that needs another caller sets its own {@code Authorization} header, which wins over this
 * default (see {@code TestSecurityConfiguration}).
 *
 * <p>The tests are not transactional on purpose. A test transaction would hide the commit, and
 * with it the constraints that fire at commit, the row locks and the concurrent requests that
 * later integration tests are written to check. Instead, {@link #resetState()} empties the
 * database, resets the clock, clears the stub of the User Service and closes the circuit
 * breaker before every test.
 */
@Tag("integration")
@SpringBootTest(properties = {
  "spring.jpa.properties.hibernate.generate_statistics=true",
  "app.internal.api-key=test-internal-key"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class,
  TestSecurityConfiguration.class})
abstract class AbstractIntegrationTest {

  /** The instant the test clock shows at the start of every test. */
  protected static final Instant START = Instant.parse("2026-11-02T10:00:00Z");

  /**
   * The token that {@link #mockMvc} sends by default: an admin, valid for ten years, so that a
   * test that moves the clock far ahead does not lose its caller.
   */
  protected static final String ADMIN_TOKEN = TestTokens.admin()
    .expiresAt(TestTokens.ISSUED_AT.plus(Duration.ofDays(3650)))
    .build();

  /** The email of the owner of the orders that the helpers insert. */
  protected static final String OWNER_EMAIL = "buyer@example.com";

  /**
   * The key set endpoint of the Authentication Service. It is never stopped: its threads are
   * daemons, so they do not keep the JVM alive.
   */
  private static final JwksTestServer JWKS_SERVER = JwksTestServer.start(TestKeys.jwksJson());

  /** Gives the context the address of the stub that plays the User Service. */
  @DynamicPropertySource
  static void userServiceAddress(DynamicPropertyRegistry registry) {
    registry.add("app.user-service.base-uri", UserServiceStub::baseUri);
    registry.add("app.security.jwks-uri", JWKS_SERVER::jwksUri);
  }

  @Autowired
  protected MockMvc mockMvc;

  @Autowired
  protected JdbcTemplate jdbcTemplate;

  @Autowired
  protected MutableClock clock;

  @Autowired
  protected CircuitBreaker userServiceCircuitBreaker;

  /**
   * Brings the shared state to the initial one: all tables are emptied and the identity counters
   * restart, so the first row of every test has the id 1; the clock shows {@link #START}.
   */
  @BeforeEach
  protected void resetState() {
    // Only the tables of the application, not the tables of Liquibase.
    jdbcTemplate.execute("TRUNCATE TABLE order_items, orders, items RESTART IDENTITY CASCADE");
    clock.set(START);
    UserServiceStub.reset();
    userServiceCircuitBreaker.reset();
  }

  /**
   * The value of the {@code Authorization} header for a token.
   *
   * @param token a compact serialized token
   * @return the header value
   */
  protected static String bearer(String token) {
    return String.format("Bearer %s", token);
  }

  /** Matches an error response in the RFC 9457 format, {@code application/problem+json}. */
  protected static ResultMatcher problemJson() {
    return MockMvcResultMatchers.content()
      .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
  }

  /** Counts the rows of a table of the application. */
  protected long countRows(String table) {
    Long count = jdbcTemplate.queryForObject(
      String.format("SELECT count(*) FROM %s", table), Long.class);
    return Objects.requireNonNull(count);
  }

  /** Inserts an item with plain SQL and returns its id. */
  protected long insertItem(String name, String price) {
    Long id = jdbcTemplate.queryForObject(
      "INSERT INTO items (name, price) VALUES (?, ?) RETURNING id",
      Long.class, name, new BigDecimal(price));
    return Objects.requireNonNull(id);
  }

  /** Inserts an order with plain SQL and returns its id. */
  protected long insertOrder(long userId, String status, String totalPrice) {
    Long id = jdbcTemplate.queryForObject(
      """
        INSERT INTO orders (user_id, user_email, status, total_price)
        VALUES (?, ?, ?, ?) RETURNING id
        """,
      Long.class, userId, OWNER_EMAIL, status, new BigDecimal(totalPrice));
    return Objects.requireNonNull(id);
  }

  /** Inserts an order line with plain SQL and returns its id. */
  protected long insertOrderItem(long orderId, long itemId, int quantity, String unitPrice) {
    Long id = jdbcTemplate.queryForObject(
      """
        INSERT INTO order_items (order_id, item_id, quantity, unit_price)
        VALUES (?, ?, ?, ?) RETURNING id
        """,
      Long.class, orderId, itemId, quantity, new BigDecimal(unitPrice));
    return Objects.requireNonNull(id);
  }
}
