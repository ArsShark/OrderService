package com.innowise.orderservice.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.innowise.orderservice.client.UserServiceClient;
import com.innowise.orderservice.config.CircuitBreakerProperties;
import com.innowise.orderservice.config.ResilienceConfig;
import com.innowise.orderservice.dto.response.UserInfo;
import com.innowise.orderservice.exception.UserServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests of {@link UserInfoResolver} with a mocked client and a real circuit breaker. The
 * breaker is small (window 4, at least 2 calls, 50 percent, two probes) and never leaves the open
 * state by itself (one hour): the tests switch its state with {@code transitionTo...State()}, so
 * no test waits for a clock.
 */
@ExtendWith(MockitoExtension.class)
class UserInfoResolverTest {

  private static final UserInfo ANNA = new UserInfo(7L, "Anna", "Petrova", "anna@example.com");

  @Mock
  private UserServiceClient client;

  private CircuitBreaker breaker;
  private UserInfoResolver resolver;

  @BeforeEach
  void createResolver() {
    breaker = new ResilienceConfig().userServiceCircuitBreaker(
      new CircuitBreakerProperties(4, 2, 50, Duration.ofHours(1), 2));
    resolver = new UserInfoResolver(client, breaker);
  }

  @Test
  void aFoundProfileANobodyAndAFailureAreThreeDifferentResults() {
    when(client.findByEmail("anna@example.com")).thenReturn(Optional.of(ANNA));
    when(client.findByEmail("gone@example.com")).thenReturn(Optional.empty());
    when(client.findByEmail("down@example.com")).thenThrow(new UserServiceUnavailableException());

    assertAll(
      () -> assertEquals(UserLookup.found(ANNA), resolver.lookup("anna@example.com")),
      () -> assertEquals(UserLookup.notFound(), resolver.lookup("gone@example.com")),
      () -> assertEquals(UserLookup.unavailable(), resolver.lookup("down@example.com")),
      () -> assertTrue(UserLookup.notFound().available()),
      () -> assertEquals(false, UserLookup.unavailable().available()));
  }

  @Test
  void anUnknownEmailIsNeverCountedAsAFailure() {
    when(client.findByEmail("gone@example.com")).thenReturn(Optional.empty());

    for (int i = 0; i < 10; i++) {
      resolver.lookup("gone@example.com");
    }

    assertAll(
      () -> assertEquals(CircuitBreaker.State.CLOSED, breaker.getState()),
      () -> assertEquals(0, breaker.getMetrics().getNumberOfFailedCalls()));
  }

  @Test
  void failuresOpenTheBreakerAndThenTheClientIsNotCalledAnyMore() {
    when(client.findByEmail("down@example.com")).thenThrow(new UserServiceUnavailableException());

    UserLookup first = resolver.lookup("down@example.com");
    UserLookup second = resolver.lookup("down@example.com");
    UserLookup third = resolver.lookup("down@example.com");

    assertAll(
      () -> assertEquals(CircuitBreaker.State.OPEN, breaker.getState()),
      () -> assertEquals(UserLookup.unavailable(), first),
      () -> assertEquals(UserLookup.unavailable(), second),
      () -> assertEquals(UserLookup.unavailable(), third),
      // The third call was refused by the breaker, without a call to the client.
      () -> verify(client, times(2)).findByEmail("down@example.com"));
  }

  @Test
  void anOpenBreakerAnswersEveryEmailOfAListWithoutAnyCall() {
    breaker.transitionToOpenState();

    Map<String, UserLookup> results = resolver.lookup(List.of("a@example.com", "b@example.com"));

    assertAll(
      () -> assertEquals(Map.of("a@example.com", UserLookup.unavailable(),
        "b@example.com", UserLookup.unavailable()), results),
      () -> verifyNoInteractions(client));
  }

  @Test
  void goodProbesInTheHalfOpenStateCloseTheBreakerAgain() {
    when(client.findByEmail("anna@example.com")).thenReturn(Optional.of(ANNA));
    breaker.transitionToOpenState();
    breaker.transitionToHalfOpenState();

    resolver.lookup("anna@example.com");
    resolver.lookup("anna@example.com");

    assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
  }

  @Test
  void failedProbesInTheHalfOpenStateOpenTheBreakerAgain() {
    when(client.findByEmail("down@example.com")).thenThrow(new UserServiceUnavailableException());
    breaker.transitionToOpenState();
    breaker.transitionToHalfOpenState();

    resolver.lookup("down@example.com");
    resolver.lookup("down@example.com");

    assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
  }

  @Test
  void theFirstFailureOfAListStopsTheRestOfTheList() {
    when(client.findByEmail("a@example.com")).thenThrow(new UserServiceUnavailableException());

    Map<String, UserLookup> results =
      resolver.lookup(List.of("a@example.com", "b@example.com", "c@example.com"));

    assertAll(
      () -> assertEquals(3, results.size()),
      () -> assertTrue(results.values().stream().noneMatch(UserLookup::available)),
      () -> verify(client).findByEmail("a@example.com"),
      () -> verifyNoMoreInteractions(client));
  }

  @Test
  void everyDistinctEmailIsAskedOnceInTheOrderOfFirstAppearance() {
    when(client.findByEmail("a@example.com")).thenReturn(Optional.of(ANNA));
    when(client.findByEmail("b@example.com")).thenReturn(Optional.empty());

    Map<String, UserLookup> results =
      resolver.lookup(List.of("a@example.com", "a@example.com", "b@example.com"));

    assertAll(
      () -> assertEquals(List.of("a@example.com", "b@example.com"),
        List.copyOf(results.keySet())),
      () -> verify(client, times(1)).findByEmail("a@example.com"),
      () -> verify(client, times(1)).findByEmail("b@example.com"));
  }

  @Test
  void anEmptyListCostsNothing() {
    assertAll(
      () -> assertTrue(resolver.lookup(List.of()).isEmpty()),
      () -> verifyNoInteractions(client));
  }

  @Test
  void aBugOfTheClientIsNotHiddenAsAMissingUser() {
    when(client.findByEmail("a@example.com")).thenThrow(new IllegalStateException("bug"));

    assertThrows(IllegalStateException.class, () -> resolver.lookup("a@example.com"));
  }
}
