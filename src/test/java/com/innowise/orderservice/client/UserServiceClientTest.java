package com.innowise.orderservice.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.innowise.orderservice.config.InternalApiProperties;
import com.innowise.orderservice.config.UserServiceClientConfig;
import com.innowise.orderservice.config.UserServiceProperties;
import com.innowise.orderservice.dto.response.UserInfo;
import com.innowise.orderservice.exception.UserServiceUnavailableException;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

/**
 * Tests of {@link UserServiceClient} against a real HTTP server (WireMock): what goes out on the
 * wire and how every kind of answer is classified.
 *
 * <p>One server serves the whole class and is reset before every test. The client of
 * {@link #setUp()} has a generous read timeout, so a slow first request of a cold server cannot
 * end a test; a short timeout is used only in the one test that proves the timeout, and there
 * the stub delay is ten times longer.
 */
class UserServiceClientTest {

  private static final String KEY = "unit-test-key";
  private static final String PATH = "/internal/users/by-email";
  private static final String EMAIL = "anna.petrova@example.com";

  @RegisterExtension
  static WireMockExtension userService = WireMockExtension.newInstance()
    .options(WireMockConfiguration.wireMockConfig().dynamicPort())
    .build();

  private UserServiceClient client;

  @BeforeEach
  void setUp() {
    client = clientFor(userService.baseUrl(), Duration.ofSeconds(10));
  }

  @Test
  void findsTheProfileAndSendsTheKeyAndTheEmail() {
    userService.stubFor(get(urlPathEqualTo(PATH)).withQueryParam("email", equalTo(EMAIL))
      .willReturn(okJson(profileJson(7, EMAIL))));

    Optional<UserInfo> found = client.findByEmail(EMAIL);

    assertAll(
      () -> assertEquals(new UserInfo(7L, "Anna", "Petrova", EMAIL), found.orElseThrow()),
      () -> userService.verify(1, getRequestedFor(urlPathEqualTo(PATH))
        .withHeader("X-Internal-Key", equalTo(KEY))
        .withQueryParam("email", equalTo(EMAIL))));
  }

  /** A plus sign must travel as %2B; a plain plus in a query string would arrive as a space. */
  @Test
  void anEmailWithAPlusSignReachesTheServerIntact() {
    String email = "anna+shop@example.com";
    userService.stubFor(get(urlPathEqualTo(PATH)).withQueryParam("email", equalTo(email))
      .willReturn(okJson(profileJson(8, email))));

    assertEquals(8L, client.findByEmail(email).orElseThrow().id());
  }

  @Test
  void nobodyWithThisEmailIsAnEmptyAnswerNotAnError() {
    userService.stubFor(get(urlPathEqualTo(PATH))
      .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/problem+json")
        .withBody("{\"status\":404}")));

    assertTrue(client.findByEmail(EMAIL).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 500, 502, 503})
  void everyOtherStatusMeansTheServiceCannotHelp(int status) {
    userService.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(status)));

    UserServiceUnavailableException thrown = assertThrows(UserServiceUnavailableException.class,
      () -> client.findByEmail(EMAIL));

    assertFalse(thrown.getMessage().contains(EMAIL));
  }

  @Test
  void aBodyThatIsNotAProfileMeansTheServiceCannotHelp() {
    userService.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("{}")));

    assertThrows(UserServiceUnavailableException.class, () -> client.findByEmail(EMAIL));
  }

  @Test
  void anEmptyBodyOrGarbageMeansTheServiceCannotHelp() {
    userService.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)));
    assertThrows(UserServiceUnavailableException.class, () -> client.findByEmail(EMAIL));

    userService.resetAll();
    userService.stubFor(get(urlPathEqualTo(PATH)).willReturn(
      aResponse().withStatus(200).withHeader("Content-Type", "application/json")
        .withBody("this is not json")));
    assertThrows(UserServiceUnavailableException.class, () -> client.findByEmail(EMAIL));
  }

  @Test
  void aDroppedConnectionMeansTheServiceCannotHelp() {
    userService.stubFor(get(urlPathEqualTo(PATH))
      .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

    assertThrows(UserServiceUnavailableException.class, () -> client.findByEmail(EMAIL));
  }

  @Test
  void aRefusedConnectionMeansTheServiceCannotHelp() throws IOException {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    UserServiceClient offline = clientFor(
      String.format("http://localhost:%d", closedPort), Duration.ofSeconds(10));

    assertThrows(UserServiceUnavailableException.class, () -> offline.findByEmail(EMAIL));
  }

  @Test
  void anAnswerThatTakesTooLongMeansTheServiceCannotHelp() {
    userService.stubFor(get(urlPathEqualTo(PATH))
      .willReturn(okJson(profileJson(7, EMAIL)).withFixedDelay(5000)));
    UserServiceClient impatient = clientFor(userService.baseUrl(), Duration.ofMillis(500));

    long started = System.nanoTime();
    assertThrows(UserServiceUnavailableException.class, () -> impatient.findByEmail(EMAIL));
    Duration waited = Duration.ofNanos(System.nanoTime() - started);

    assertTrue(waited.compareTo(Duration.ofSeconds(4)) < 0,
      String.format("the client waited %s for an answer that took 5 s", waited));
  }

  private static UserServiceClient clientFor(String baseUri, Duration readTimeout) {
    UserServiceProperties properties =
      new UserServiceProperties(baseUri, Duration.ofSeconds(2), readTimeout);
    RestClient restClient = new UserServiceClientConfig()
      .userServiceRestClient(properties, new InternalApiProperties(KEY));
    return new UserServiceClient(restClient);
  }

  /** The answer of the User Service: more fields than the application needs. */
  private static String profileJson(long id, String email) {
    return """
      {"id":%d,"name":"Anna","surname":"Petrova","birthDate":"1990-05-17",
       "email":"%s","active":true,"createdAt":"2026-10-01T10:00:00Z",
       "updatedAt":"2026-10-01T10:00:00Z"}""".formatted(id, email);
  }
}
