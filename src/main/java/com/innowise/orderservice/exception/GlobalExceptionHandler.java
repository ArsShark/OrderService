package com.innowise.orderservice.exception;

import com.innowise.orderservice.dto.response.FieldViolation;
import java.util.Comparator;
import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Translates exceptions into RFC 9457 {@link ProblemDetail} responses.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} gives the same format for the exceptions
 * raised by Spring MVC itself (malformed JSON, wrong HTTP method and so on). This class adds the
 * domain exceptions, request validation failures, the lost update of an order and a last-resort
 * handler.
 *
 * <p>Responses never contain rejected values or internal exception messages, because they may
 * hold an email. The messages of the domain exceptions are safe: they contain ids and statuses
 * only.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /**
   * 422: the request is well formed, but it asks for something the data does not allow. Written
   * as a code and not as a constant of {@link HttpStatus}, whose name for it changed.
   */
  private static final HttpStatusCode UNPROCESSABLE = HttpStatusCode.valueOf(422);

  private static final String INTERNAL_ERROR_DETAIL = "An unexpected error occurred";

  /** The detail of every 401: it never says why, so a missing and a wrong credential look alike. */
  private static final String AUTHENTICATION_REQUIRED = "Authentication is required";

  /** The detail of every 403. */
  private static final String ACCESS_DENIED = "Access is denied";

  private static final String LOST_UPDATE_DETAIL =
      "The order was changed by another request, try again";

  /** Maps "resource does not exist" exceptions to 404. */
  @ExceptionHandler({OrderNotFoundException.class, ItemNotFoundException.class})
  public ResponseEntity<ProblemDetail> handleNotFound(RuntimeException ex) {
    return reject(HttpStatus.NOT_FOUND, ex);
  }

  /** Maps a request that names an unavailable item or asks for a too large total to 422. */
  @ExceptionHandler({ItemNotAvailableException.class, OrderTotalTooLargeException.class})
  public ResponseEntity<ProblemDetail> handleUnprocessable(RuntimeException ex) {
    return reject(UNPROCESSABLE, ex);
  }

  /** Maps requests that the status of the order does not allow to 409. */
  @ExceptionHandler({
    OrderNotEditableException.class,
    OrderNotDeletableException.class,
    OrderStatusTransitionException.class})
  public ResponseEntity<ProblemDetail> handleConflict(RuntimeException ex) {
    return reject(HttpStatus.CONFLICT, ex);
  }

  /**
   * Maps a lost update to 409 with a constant detail. The order is changed under a row lock, so
   * this is the safety net of {@code @Version}; the exception message names the entity and is
   * not sent.
   */
  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ResponseEntity<ProblemDetail> handleLostUpdate(OptimisticLockingFailureException ex) {
    return reject(HttpStatus.CONFLICT, LOST_UPDATE_DETAIL, ex);
  }

  /** Maps a request to sort by a property that is not part of the API, or a bad range, to 400. */
  @ExceptionHandler({InvalidSortPropertyException.class, InvalidDateRangeException.class})
  public ResponseEntity<ProblemDetail> handleBadRequest(RuntimeException ex) {
    return reject(HttpStatus.BAD_REQUEST, ex);
  }

  /**
   * Handles database constraint violations: always a bug on our side (the checks of the service
   * come first), so a 500.
   *
   * <p>The exception itself is not logged: its message contains the failing row. Only the name
   * of the violated constraint is.
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(
      DataIntegrityViolationException ex) {
    LOGGER.error("Unexpected data integrity violation, constraint: {}", findConstraintName(ex));
    return internalError();
  }

  /**
   * Handles a caller that is not authenticated: 401 with a constant detail.
   *
   * <p>The exceptions of the filter chain get here through {@code ProblemDetailSecurityHandler};
   * the message of the exception is never sent, because it may describe the token.
   */
  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
    return reject(HttpStatus.UNAUTHORIZED, AUTHENTICATION_REQUIRED, ex);
  }

  /**
   * Handles an authenticated caller that has no right to the resource: 403 with a constant
   * detail. Without this handler the catch-all below would turn it into a 500.
   */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
    return reject(HttpStatus.FORBIDDEN, ACCESS_DENIED, ex);
  }

  /** Last resort: logs the exception with its stack trace and hides the details from the client. */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
    LOGGER.error("Unhandled exception", ex);
    return internalError();
  }

  /** Adds the list of invalid fields (without rejected values) to the 400 response. */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status,
      WebRequest request) {
    List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
        .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
        .sorted(Comparator.comparing(FieldViolation::field)
            .thenComparing(FieldViolation::message))
        .toList();
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
    problem.setProperty("errors", violations);
    return handleExceptionInternal(ex, problem, headers, status, request);
  }

  /**
   * Answers a parameter that cannot be converted (a bad status, an instant that is not ISO-8601,
   * a text instead of an id). Spring's own detail repeats the value of the client; this one
   * names only the parameter, which is part of our API.
   */
  @Override
  protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex,
      HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    String name = ex instanceof MethodArgumentTypeMismatchException mismatch
        ? mismatch.getName() : ex.getPropertyName();
    String detail = name == null
        ? "Invalid value of a request parameter"
        : String.format("Invalid value of the parameter '%s'", name);
    return handleExceptionInternal(ex, ProblemDetail.forStatusAndDetail(status, detail),
        headers, status, request);
  }

  private static ResponseEntity<ProblemDetail> reject(HttpStatusCode status, RuntimeException ex) {
    LOGGER.info("Request rejected with status {}: {}", status.value(), ex.getMessage());
    return ResponseEntity.status(status)
        .body(ProblemDetail.forStatusAndDetail(status, ex.getMessage()));
  }

  /** A rejection with a fixed detail: only the class of the exception is logged. */
  private static ResponseEntity<ProblemDetail> reject(HttpStatusCode status, String detail,
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

  /** Returns the violated constraint name from the cause chain, or null if there is none. */
  private static String findConstraintName(Throwable ex) {
    for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation) {
        return violation.getConstraintName();
      }
    }
    return null;
  }
}
