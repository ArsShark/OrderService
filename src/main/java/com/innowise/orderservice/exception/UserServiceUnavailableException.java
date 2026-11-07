package com.innowise.orderservice.exception;

/**
 * Thrown when the User Service cannot give an answer: no connection, a timeout, an error status,
 * a refused internal key or a body that cannot be used. An unknown email is not an error of this
 * kind: it is a normal answer. The message never names an address or an email.
 */
public class UserServiceUnavailableException extends RuntimeException {

  public UserServiceUnavailableException() {
    super("The user service is not available");
  }
}
