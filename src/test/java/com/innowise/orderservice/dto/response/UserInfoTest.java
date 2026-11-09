package com.innowise.orderservice.dto.response;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** The profile of an owner holds personal data, so its string form must not show it. */
class UserInfoTest {

  @Test
  void theStringFormHasTheIdAndNoPersonalData() {
    UserInfo user = new UserInfo(7L, "Zinaida", "Quarterman", "zinaida@example.com");

    String text = user.toString();

    assertAll(
      () -> assertEquals("UserInfo[id=7]", text),
      () -> assertFalse(text.contains("Zinaida")),
      () -> assertFalse(text.contains("Quarterman")),
      () -> assertFalse(text.contains("zinaida@example.com")));
  }
}
