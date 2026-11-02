package com.innowise.orderservice;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Pins the logging convention: the code uses the SLF4J API and the backend is Log4j2. If a
 * starter brings Logback back onto the classpath, SLF4J may pick it and this test fails.
 */
class LoggingBackendTest {

  @Test
  void slf4jIsBackedByLog4j2() {
    assertEquals("org.apache.logging.slf4j.Log4jLoggerFactory",
      LoggerFactory.getILoggerFactory().getClass().getName());
  }
}
