package com.innowise.orderservice.dto.response;

/**
 * One request validation failure, reported in the {@code errors} extension of a
 * {@code ProblemDetail} response.
 *
 * <p>The rejected value is deliberately not included: it may be an email or a card number.
 *
 * @param field name of the invalid request property
 * @param message description of the violated rule
 */
public record FieldViolation(String field, String message) {
}
