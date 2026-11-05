package com.innowise.orderservice.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The lines of an order must not refer to the same item twice (one line per item and order). */
@Documented
@Constraint(validatedBy = UniqueItemsValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface UniqueItems {

  String message() default "must not contain the same item twice";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
