package com.innowise.orderservice.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.innowise.orderservice.entity.OrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.sql.SQLException;
import java.util.Set;
import java.util.stream.Stream;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tests of {@link GlobalExceptionHandler}.
 *
 * <p>The handler is exercised through a standalone MockMvc and a throwaway controller, so no
 * Spring context is started and the tests do not depend on the real controllers.
 */
class GlobalExceptionHandlerTest {

  private ThrowingController controller;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    controller = new ThrowingController();
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.setMessageInterpolator(new ParameterMessageInterpolator());
    validator.afterPropertiesSet();
    mockMvc = MockMvcBuilders.standaloneSetup(controller)
      .setControllerAdvice(new GlobalExceptionHandler())
      .setValidator(validator)
      .build();
  }

  static Stream<RuntimeException> notFoundExceptions() {
    return Stream.of(new OrderNotFoundException(5L), new ItemNotFoundException(7L));
  }

  static Stream<RuntimeException> unprocessableExceptions() {
    return Stream.of(new ItemNotAvailableException(3L), new OrderTotalTooLargeException());
  }

  static Stream<RuntimeException> conflictExceptions() {
    return Stream.of(
      new OrderNotEditableException(5L, OrderStatus.PAID),
      new OrderNotDeletableException(5L, OrderStatus.PAID),
      new OrderStatusTransitionException(OrderStatus.PAID, OrderStatus.CANCELLED));
  }

  static Stream<RuntimeException> badRequestExceptions() {
    return Stream.of(
      new InvalidSortPropertyException(Set.of("id", "status")),
      new InvalidDateRangeException());
  }

  @ParameterizedTest
  @MethodSource("notFoundExceptions")
  void mapsNotFoundExceptionsTo404(RuntimeException exception) throws Exception {
    controller.exceptionToThrow = exception;

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().isNotFound(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(404),
      jsonPath("$.detail").value(exception.getMessage()));
  }

  @ParameterizedTest
  @MethodSource("unprocessableExceptions")
  void mapsExceptionsAboutTheDataTo422(RuntimeException exception) throws Exception {
    controller.exceptionToThrow = exception;

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().is(422),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(422),
      jsonPath("$.detail").value(exception.getMessage()));
  }

  @ParameterizedTest
  @MethodSource("conflictExceptions")
  void mapsExceptionsAboutTheStatusTo409(RuntimeException exception) throws Exception {
    controller.exceptionToThrow = exception;

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().isConflict(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(409),
      jsonPath("$.detail").value(exception.getMessage()));
  }

  @ParameterizedTest
  @MethodSource("badRequestExceptions")
  void mapsBadRequestsTo400(RuntimeException exception) throws Exception {
    controller.exceptionToThrow = exception;

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().isBadRequest(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(400),
      jsonPath("$.detail").value(exception.getMessage()));
  }

  /** The message of the exception names the entity, so it is not sent. */
  @Test
  void mapsALostUpdateTo409WithAConstantDetail() throws Exception {
    controller.exceptionToThrow = new OptimisticLockingFailureException(
      "Row was updated or deleted by another transaction: Order#5");

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().isConflict(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.detail").value("The order was changed by another request, try again"),
      content().string(not(containsString("Order#5"))));
  }

  @Test
  void mapsAnIntegrityViolationTo500WithoutRowData() throws Exception {
    controller.exceptionToThrow = integrityViolation("ck_orders_status");

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().isInternalServerError(),
      jsonPath("$.detail").value("An unexpected error occurred"),
      content().string(not(containsString("buyer@example.com"))));
  }

  @Test
  void mapsAnIntegrityViolationWithoutConstraintNameTo500() throws Exception {
    controller.exceptionToThrow = new DataIntegrityViolationException("no known cause");

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().isInternalServerError(),
      jsonPath("$.detail").value("An unexpected error occurred"));
  }

  @Test
  void hidesInternalDetailsOfUnexpectedExceptions() throws Exception {
    controller.exceptionToThrow = new IllegalStateException("secret internal detail");

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().isInternalServerError(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.detail").value("An unexpected error occurred"),
      content().string(not(containsString("secret internal detail"))));
  }

  @Test
  void listsInvalidFieldsWithoutRejectedValues() throws Exception {
    String body = "{\"name\": \"\", \"email\": \"not-an-email\"}";

    mockMvc.perform(post("/test/validated").contentType(MediaType.APPLICATION_JSON)
      .content(body)).andExpectAll(
      status().isBadRequest(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.detail").value("Request validation failed"),
      jsonPath("$.errors.length()").value(2),
      jsonPath("$.errors[0].field").value("email"),
      jsonPath("$.errors[1].field").value("name"),
      content().string(not(containsString("not-an-email"))));
  }

  @Test
  void answersMalformedJsonWith400() throws Exception {
    String body = "{\"name\": \"John\", \"email\": ";

    mockMvc.perform(post("/test/validated").contentType(MediaType.APPLICATION_JSON)
      .content(body)).andExpectAll(
      status().isBadRequest(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(400),
      content().string(not(containsString("John"))));
  }

  @Test
  void answersWrongPathVariableTypeWith400() throws Exception {
    mockMvc.perform(get("/test/items/abc")).andExpectAll(
      status().isBadRequest(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(400),
      jsonPath("$.detail").value("Invalid value of the parameter 'id'"));
  }

  /** Spring's own detail would repeat the value of the client; ours names only the parameter. */
  @Test
  void doesNotEchoTheValueOfAParameterThatCannotBeConverted() throws Exception {
    mockMvc.perform(get("/test/filter").param("status", "BOGUS-VALUE")).andExpectAll(
      status().isBadRequest(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.detail").value("Invalid value of the parameter 'status'"),
      content().string(not(containsString("BOGUS-VALUE"))));
  }

  @Test
  void answersUnsupportedMethodWith405() throws Exception {
    mockMvc.perform(post("/test/items/1")).andExpectAll(
      status().isMethodNotAllowed(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(405));
  }

  /** The constant detail never says why, and the message of the exception is not sent. */
  @Test
  void mapsAuthenticationFailuresTo401WithAConstantDetail() throws Exception {
    controller.exceptionToThrow = new BadCredentialsException("token secret-value expired");

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().isUnauthorized(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(401),
      jsonPath("$.detail").value("Authentication is required"),
      content().string(not(containsString("secret-value"))));
  }

  /** Without an explicit handler the catch-all would turn this into a 500. */
  @Test
  void mapsAccessDeniedTo403WithAConstantDetail() throws Exception {
    controller.exceptionToThrow = new AccessDeniedException("order 42 belongs to user 9");

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().isForbidden(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(403),
      jsonPath("$.detail").value("Access is denied"),
      content().string(not(containsString("order 42"))));
  }

  /** Builds the exception Spring raises for a violated constraint, with a row in its message. */
  private static DataIntegrityViolationException integrityViolation(String constraint) {
    SQLException sqlException = new SQLException("Key (user_email)=(buyer@example.com) is bad");
    return new DataIntegrityViolationException("could not execute statement",
      new ConstraintViolationException("could not execute statement", sqlException, constraint));
  }

  /** Request body with two constrained fields. */
  public record SampleRequest(@NotBlank String name, @Email String email) {
  }

  /** Controller whose only job is to throw what a test asks for. */
  @RestController
  @RequestMapping("/test")
  static class ThrowingController {

    RuntimeException exceptionToThrow;

    @GetMapping("/throw")
    String throwConfiguredException() {
      throw exceptionToThrow;
    }

    @GetMapping("/items/{id}")
    String item(@PathVariable Long id) {
      return "ok";
    }

    @GetMapping("/filter")
    String filter(@RequestParam OrderStatus status) {
      return "ok";
    }

    @PostMapping("/validated")
    String validated(@Valid @RequestBody SampleRequest request) {
      return "ok";
    }
  }
}
