package com.innowise.orderservice.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Translates exceptions into RFC 9457 {@link ProblemDetail} responses.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} gives the same format for the exceptions
 * raised by Spring MVC itself (malformed JSON, wrong HTTP method, wrong parameter type and so
 * on). This class adds the security failures and a last-resort handler; the domain exceptions
 * and the request validation arrive in the next commit.
 *
 * <p>Responses never contain rejected values or internal exception messages, because they may
 * hold an email.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private static final String INTERNAL_ERROR_DETAIL = "An unexpected error occurred";

  /** The detail of every 401: it never says why, so a missing and a wrong credential look alike. */
  private static final String AUTHENTICATION_REQUIRED = "Authentication is required";

  /** The detail of every 403. */
  private static final String ACCESS_DENIED = "Access is denied";

  /**
   * Handles a caller that is not authenticated: 401 with a constant detail.
   *
   * <p>The exceptions of the filter chain get here through {@code ProblemDetailSecurityHandler};
   * the message of the exception is never sent, because it may describe the token.
   */
  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
    return rejectSecurity(HttpStatus.UNAUTHORIZED, AUTHENTICATION_REQUIRED, ex);
  }

  /**
   * Handles an authenticated caller that has no right to the resource: 403 with a constant
   * detail. Without this handler the catch-all below would turn it into a 500.
   */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
    return rejectSecurity(HttpStatus.FORBIDDEN, ACCESS_DENIED, ex);
  }

  /** Last resort: logs the exception with its stack trace and hides the details from the client. */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
    LOGGER.error("Unhandled exception", ex);
    return internalError();
  }

  /** A security rejection: the detail is fixed, only the class of the exception is logged. */
  private static ResponseEntity<ProblemDetail> rejectSecurity(HttpStatus status, String detail,
      Exception ex) {
    LOGGER.info("Request rejected with status {}: {}", status.value(),
        ex.getClass().getSimpleName());
    return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
  }

  private static ResponseEntity<ProblemDetail> internalError() {
    HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
    return ResponseEntity.status(status)
        .body(ProblemDetail.forStatusAndDetail(status, INTERNAL_ERROR_DETAIL));
  }
}
