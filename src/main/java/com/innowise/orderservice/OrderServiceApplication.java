package com.innowise.orderservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * Entry point of the Order Service.
 *
 * <p>{@link UserDetailsServiceAutoConfiguration} is excluded on purpose: it creates an in-memory
 * user with a random password and prints that password to the log. This service never
 * authenticates through Spring Security's username and password support: callers bring an
 * access token of the Authentication Service.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class OrderServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(OrderServiceApplication.class, args);
  }

}
