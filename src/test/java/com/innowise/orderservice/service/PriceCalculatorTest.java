package com.innowise.orderservice.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.innowise.orderservice.TestEntities;
import com.innowise.orderservice.entity.Item;
import com.innowise.orderservice.entity.OrderItem;
import com.innowise.orderservice.exception.OrderTotalTooLargeException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PriceCalculatorTest {

  private static final Item ITEM = TestEntities.item(1L, "Keyboard", "1.00");

  private final PriceCalculator calculator = new PriceCalculator();

  @Test
  void theTotalIsTheSumOfPriceTimesQuantityWithTwoDecimals() {
    List<OrderItem> lines = List.of(
      TestEntities.line(ITEM, 2, "49.90"),
      TestEntities.line(ITEM, 3, "0.10"));

    assertEquals(new BigDecimal("100.10"), calculator.total(lines));
  }

  @Test
  void anOrderWithoutLinesCostsNothing() {
    assertEquals(new BigDecimal("0.00"), calculator.total(List.of()));
  }

  @Test
  void aWholeNumberPriceStillGivesTwoDecimals() {
    assertEquals(new BigDecimal("30.00"),
      calculator.total(List.of(TestEntities.line(ITEM, 3, "10.00"))));
  }

  @Test
  void theLargestValueOfTheColumnIsAcceptedAndOneCentMoreIsNot() {
    List<OrderItem> atTheLimit = List.of(TestEntities.line(ITEM, 1, "9999999999.99"));
    List<OrderItem> overTheLimit = List.of(
      TestEntities.line(ITEM, 1, "9999999999.99"),
      TestEntities.line(ITEM, 1, "0.01"));

    assertAll(
      () -> assertEquals(new BigDecimal("9999999999.99"), calculator.total(atTheLimit)),
      () -> assertThrows(OrderTotalTooLargeException.class,
        () -> calculator.total(overTheLimit)));
  }

  @Test
  void theLimitsOfTheRequestKeepEveryOrderInsideTheColumn() {
    // 50 lines of 1000 pieces at the highest price a request may carry (99999.99).
    List<OrderItem> biggest = java.util.stream.IntStream.range(0, 50)
      .mapToObj(i -> TestEntities.line(ITEM, 1000, "99999.99"))
      .toList();

    assertEquals(new BigDecimal("4999999500.00"), calculator.total(biggest));
  }
}
