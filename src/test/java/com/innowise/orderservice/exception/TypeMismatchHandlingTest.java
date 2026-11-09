package com.innowise.orderservice.exception;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.beans.PropertyChangeEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * The two shapes of the answer to a value that cannot be converted: the parameter has a name, or
 * the exception does not know it. The usual case, a parameter of a controller method, is tested
 * through MockMvc in {@link GlobalExceptionHandlerTest}; this test calls the handler directly
 * for the other kind of {@link TypeMismatchException}.
 */
class TypeMismatchHandlingTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
  private final ServletWebRequest request =
    new ServletWebRequest(new MockHttpServletRequest("GET", "/api/orders"));

  @Test
  void aKnownPropertyIsNamedAndTheValueIsNotRepeated() {
    TypeMismatchException error = new TypeMismatchException(
      new PropertyChangeEvent(this, "createdFrom", null, "secret-value"), Instant.class);

    ProblemDetail problem = answer(error);

    assertAll(
      () -> assertEquals(400, problem.getStatus()),
      () -> assertEquals("Invalid value of the parameter 'createdFrom'", problem.getDetail()),
      () -> assertFalse(problem.getDetail().contains("secret-value")));
  }

  @Test
  void anUnknownPropertyGetsAGenericDetailAndTheValueIsNotRepeated() {
    TypeMismatchException error = new TypeMismatchException("secret-value", Long.class);

    ProblemDetail problem = answer(error);

    assertAll(
      () -> assertEquals(400, problem.getStatus()),
      () -> assertEquals("Invalid value of a request parameter", problem.getDetail()),
      () -> assertFalse(problem.getDetail().contains("secret-value")));
  }

  private ProblemDetail answer(TypeMismatchException error) {
    ResponseEntity<Object> response =
      handler.handleTypeMismatch(error, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    return assertInstanceOf(ProblemDetail.class, response.getBody());
  }
}
