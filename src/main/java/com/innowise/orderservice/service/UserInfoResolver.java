package com.innowise.orderservice.service;

import com.innowise.orderservice.client.UserServiceClient;
import com.innowise.orderservice.dto.response.UserInfo;
import com.innowise.orderservice.exception.UserServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Finds the owners of orders in the User Service, through the circuit breaker. It never throws
 * because of the User Service: an order is worth showing without its owner, so a failure becomes
 * {@link UserLookup#unavailable()}.
 *
 * <p>For a list of emails every distinct email is asked once. The first failure of a call stops
 * the rest of that call: a User Service that hangs would otherwise cost one timeout per email, and
 * a page of 100 orders could wait for minutes. This class has no transaction on purpose: it makes
 * HTTP calls, and a transaction around them would hold a database connection.
 */
@Component
public class UserInfoResolver {

  private static final Logger LOGGER = LoggerFactory.getLogger(UserInfoResolver.class);

  private final UserServiceClient client;
  private final CircuitBreaker breaker;

  public UserInfoResolver(UserServiceClient client,
    @Qualifier("userServiceCircuitBreaker") CircuitBreaker breaker) {
    this.client = client;
    this.breaker = breaker;
  }

  /** Looks one email up. */
  public UserLookup lookup(String email) {
    return lookup(List.of(email)).get(email);
  }

  /**
   * Looks up every distinct email once.
   *
   * @param emails the emails of the owners; duplicates are asked once
   * @return the result for every distinct email, in the order of first appearance
   */
  public Map<String, UserLookup> lookup(Collection<String> emails) {
    Map<String, UserLookup> results = new LinkedHashMap<>();
    boolean failed = false;
    for (String email : new LinkedHashSet<>(emails)) {
      if (failed) {
        results.put(email, UserLookup.unavailable());
        continue;
      }
      try {
        Optional<UserInfo> user = breaker.executeSupplier(() -> client.findByEmail(email));
        results.put(email, user.map(UserLookup::found).orElseGet(UserLookup::notFound));
      } catch (CallNotPermittedException e) {
        LOGGER.warn("The circuit breaker of the user service is open: no user data in the answer");
        failed = true;
        results.put(email, UserLookup.unavailable());
      } catch (UserServiceUnavailableException e) {
        // The client has logged the reason.
        failed = true;
        results.put(email, UserLookup.unavailable());
      }
    }
    return results;
  }
}
