package com.innowise.orderservice.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * What an order shows about its owner. It is also the shape of the internal answer of the User
 * Service, which has more fields (birth date, flags, audit columns); the unknown ones are
 * ignored, so the User Service can add fields without breaking this service, and personal data
 * that an order does not need never leaves this class.
 *
 * <p>{@link #toString()} hides the personal data: the generated one would print the email.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserInfo(Long id, String name, String surname, String email) {

  @Override
  public String toString() {
    return String.format("UserInfo[id=%d]", id);
  }
}
