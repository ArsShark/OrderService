package com.innowise.orderservice.service;

import com.innowise.orderservice.dto.response.UserInfo;

/**
 * The result of looking up the owner of an order. There are three cases, and the difference
 * between the last two matters to the client:
 *
 * <ul>
 *   <li>found: {@code user} is set, {@code available} is {@code true};
 *   <li>the User Service answered that nobody has this email (for example the profile was
 *       deleted): {@code user} is {@code null}, {@code available} is {@code true};
 *   <li>the User Service could not answer (down, too slow, refused, breaker open):
 *       {@code user} is {@code null}, {@code available} is {@code false}.
 * </ul>
 *
 * @param user the profile, or {@code null}
 * @param available whether the User Service gave an answer at all
 */
public record UserLookup(UserInfo user, boolean available) {

  public static UserLookup found(UserInfo user) {
    return new UserLookup(user, true);
  }

  public static UserLookup notFound() {
    return new UserLookup(null, true);
  }

  public static UserLookup unavailable() {
    return new UserLookup(null, false);
  }
}
