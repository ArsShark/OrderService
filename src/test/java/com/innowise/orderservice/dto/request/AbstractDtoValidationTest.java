package com.innowise.orderservice.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/** Shared Bean Validation setup for DTO tests. No Spring context is needed. */
abstract class AbstractDtoValidationTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void createValidator() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void closeValidator() {
    factory.close();
  }

  /** Returns the paths of the properties that have at least one constraint violation. */
  protected static Set<String> violatedProperties(Object dto) {
    return validator.validate(dto).stream()
      .map(violation -> violation.getPropertyPath().toString())
      .collect(Collectors.toSet());
  }
}
