package com.innowise.orderservice.client;

import com.innowise.orderservice.dto.response.UserInfo;
import com.innowise.orderservice.exception.UserServiceUnavailableException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Asks the User Service for the profile of a user by email
 * ({@code GET /internal/users/by-email}).
 *
 * <p>There are exactly two kinds of answer. A profile is found, or the User Service says that
 * nobody has this email (404): both are normal and returned as an {@link Optional}. Everything
 * else (no connection, a timeout, an error status, a refused key, a body without a profile) is a
 * {@link UserServiceUnavailableException}. The difference matters for the circuit breaker above:
 * only the second kind says that the service is in trouble.
 *
 * <p>The cause is logged with the status or the class name only: the messages of the HTTP client
 * hold the address with the email in the query string.
 */
@Component
public class UserServiceClient {

  private static final Logger LOGGER = LoggerFactory.getLogger(UserServiceClient.class);

  private final RestClient restClient;

  public UserServiceClient(@Qualifier("userServiceRestClient") RestClient restClient) {
    this.restClient = restClient;
  }

  /**
   * Finds a profile by email. The User Service normalizes the email, so its case and surrounding
   * spaces do not matter.
   *
   * @param email the email of the user
   * @return the profile, or empty if nobody has this email
   * @throws UserServiceUnavailableException if no usable answer came
   */
  public Optional<UserInfo> findByEmail(String email) {
    UserInfo user;
    try {
      user = restClient.get()
        .uri("/internal/users/by-email?email={email}", email)
        .retrieve()
        .body(UserInfo.class);
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    } catch (RestClientException e) {
      throw unavailable(e);
    }
    if (user == null || user.id() == null) {
      LOGGER.error("The user service answered without a profile");
      throw new UserServiceUnavailableException();
    }
    return Optional.of(user);
  }

  private static UserServiceUnavailableException unavailable(RestClientException error) {
    if (error instanceof RestClientResponseException response) {
      int status = response.getStatusCode().value();
      if (status == 401 || status == 403) {
        LOGGER.error("The user service refused the internal key (status {})", status);
      } else {
        LOGGER.warn("The user service answered with status {}", status);
      }
    } else if (error instanceof ResourceAccessException access && isTimeout(access)) {
      LOGGER.warn("The call to the user service ran out of time ({})", rootClass(access));
    } else {
      LOGGER.warn("The call to the user service failed ({})", rootClass(error));
    }
    return new UserServiceUnavailableException();
  }

  private static boolean isTimeout(ResourceAccessException error) {
    for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
        return true;
      }
    }
    return false;
  }

  private static String rootClass(Throwable error) {
    Throwable root = error;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    return root.getClass().getSimpleName();
  }
}
