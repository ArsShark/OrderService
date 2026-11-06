package com.innowise.orderservice.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.util.List;

/**
 * Plays the User Service for the integration tests: one WireMock server for the whole run, like
 * the one Spring context that uses it. The address is given to the context by
 * {@link AbstractIntegrationTest}; {@link #reset()} runs before every test, so no stub or recorded
 * request leaks from one test into another. WireMock answers 404 to a request that has no stub,
 * which looks like "nobody has this email".
 */
final class UserServiceStub {

  /** The key the application is configured with in the tests. */
  static final String KEY = "test-internal-key";

  static final String PATH = "/internal/users/by-email";

  private static final WireMockServer SERVER =
    new WireMockServer(WireMockConfiguration.options().dynamicPort());

  static {
    SERVER.start();
    Runtime.getRuntime().addShutdownHook(new Thread(SERVER::stop));
  }

  private UserServiceStub() {
  }

  static String baseUri() {
    return SERVER.baseUrl();
  }

  static void reset() {
    SERVER.resetAll();
  }

  /** The profile of this email exists. The body has more fields than the application uses. */
  static void respondWithUser(String email, long id, String name, String surname) {
    String body = """
      {"id":%d,"name":"%s","surname":"%s","email":"%s","birthDate":"1990-01-01","active":true}"""
      .formatted(id, name, surname, email);
    SERVER.stubFor(WireMock.get(WireMock.urlPathEqualTo(PATH))
      .withQueryParam("email", WireMock.equalTo(email))
      .willReturn(WireMock.okJson(body)));
  }

  /** Like {@link #respondWithUser}, but the answer comes after the given number of milliseconds. */
  static void respondSlowlyWithUser(String email, long id, String name, String surname,
    int delayMillis) {
    String body = """
      {"id":%d,"name":"%s","surname":"%s","email":"%s"}""".formatted(id, name, surname, email);
    SERVER.stubFor(WireMock.get(WireMock.urlPathEqualTo(PATH))
      .withQueryParam("email", WireMock.equalTo(email))
      .willReturn(WireMock.okJson(body).withFixedDelay(delayMillis)));
  }

  /** The answer for every email: the status and no body. */
  static void respondWithStatus(int status) {
    SERVER.stubFor(WireMock.get(WireMock.urlPathEqualTo(PATH))
      .willReturn(WireMock.aResponse().withStatus(status)));
  }

  /** How many lookups reached the server since the last reset. */
  static int requestsReceived() {
    return requests().size();
  }

  static List<LoggedRequest> requests() {
    return SERVER.findAll(WireMock.getRequestedFor(WireMock.urlPathEqualTo(PATH)));
  }
}
