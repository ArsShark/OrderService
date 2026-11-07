package com.innowise.orderservice.dto.request;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Verifies the constraints declared on {@link ItemRequest}. */
class ItemRequestValidationTest extends AbstractDtoValidationTest {

  private static final BigDecimal PRICE = new BigDecimal("49.90");

  @Test
  void aValidRequestHasNoViolations() {
    assertTrue(violatedProperties(new ItemRequest("Keyboard", PRICE)).isEmpty());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  void aBlankNameIsRejected(String name) {
    assertEquals(Set.of("name"), violatedProperties(new ItemRequest(name, PRICE)));
  }

  @Test
  void theNameIsLimitedTo255Characters() {
    assertAll(
      () -> assertTrue(violatedProperties(new ItemRequest("a".repeat(255), PRICE)).isEmpty()),
      () -> assertEquals(Set.of("name"),
        violatedProperties(new ItemRequest("a".repeat(256), PRICE))));
  }

  @Test
  void aMissingPriceIsRejected() {
    assertEquals(Set.of("price"), violatedProperties(new ItemRequest("Keyboard", null)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "0.00", "0.01", "10", "99999.99"})
  void pricesFromZeroToTheLimitAreAccepted(String price) {
    assertTrue(violatedProperties(new ItemRequest("Keyboard", new BigDecimal(price))).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"-0.01", "-1", "100000", "100000.00", "1.001"})
  void aNegativePriceMoreThanFiveIntegerDigitsOrMoreThanTwoDecimalsIsRejected(String price) {
    assertEquals(Set.of("price"),
      violatedProperties(new ItemRequest("Keyboard", new BigDecimal(price))));
  }
}
