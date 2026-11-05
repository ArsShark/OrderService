package com.innowise.orderservice.dto.request;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Verifies the constraints of {@link OrderRequest} and {@link OrderItemRequest}. */
class OrderRequestValidationTest extends AbstractDtoValidationTest {

  @Test
  void aValidRequestHasNoViolations() {
    OrderRequest request = new OrderRequest(
      List.of(new OrderItemRequest(1L, 2), new OrderItemRequest(2L, 1)));

    assertTrue(violatedProperties(request).isEmpty());
  }

  @Test
  void anEmptyOrMissingListOfLinesIsRejected() {
    assertAll(
      () -> assertEquals(Set.of("items"), violatedProperties(new OrderRequest(List.of()))),
      () -> assertEquals(Set.of("items"), violatedProperties(new OrderRequest(null))));
  }

  @Test
  void theListIsLimitedToFiftyLines() {
    assertAll(
      () -> assertTrue(violatedProperties(linesOf(50)).isEmpty()),
      () -> assertEquals(Set.of("items"), violatedProperties(linesOf(51))));
  }

  @Test
  void theSameItemTwiceIsRejected() {
    OrderRequest request = new OrderRequest(
      List.of(new OrderItemRequest(1L, 2), new OrderItemRequest(1L, 3)));

    assertEquals(Set.of("items"), violatedProperties(request));
  }

  @Test
  void aNullLineIsRejected() {
    List<OrderItemRequest> lines = new ArrayList<>();
    lines.add(null);

    Set<String> violated = violatedProperties(new OrderRequest(lines));

    assertTrue(violated.stream().allMatch(path -> path.startsWith("items")), violated.toString());
    assertEquals(1, violated.size());
  }

  @Test
  void aMissingOrNonPositiveItemIdIsRejected() {
    assertAll(
      () -> assertEquals(Set.of("items[0].itemId"), violatedProperties(
        new OrderRequest(List.of(new OrderItemRequest(null, 1))))),
      () -> assertEquals(Set.of("items[0].itemId"), violatedProperties(
        new OrderRequest(List.of(new OrderItemRequest(0L, 1))))),
      () -> assertEquals(Set.of("items[0].itemId"), violatedProperties(
        new OrderRequest(List.of(new OrderItemRequest(-5L, 1))))));
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 500, 1000})
  void aQuantityFromOneToAThousandIsAccepted(int quantity) {
    assertTrue(violatedProperties(
      new OrderRequest(List.of(new OrderItemRequest(1L, quantity)))).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, 1001})
  void aQuantityOutsideTheRangeIsRejected(int quantity) {
    assertEquals(Set.of("items[0].quantity"), violatedProperties(
      new OrderRequest(List.of(new OrderItemRequest(1L, quantity)))));
  }

  @Test
  void aMissingQuantityIsRejected() {
    assertEquals(Set.of("items[0].quantity"), violatedProperties(
      new OrderRequest(List.of(new OrderItemRequest(1L, null)))));
  }

  private static OrderRequest linesOf(int count) {
    return new OrderRequest(IntStream.rangeClosed(1, count)
      .mapToObj(id -> new OrderItemRequest((long) id, 1))
      .toList());
  }
}
