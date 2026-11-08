package com.innowise.orderservice.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Accepts only access tokens.
 *
 * <p>The Authentication Service signs access and refresh tokens with the same key and the same
 * issuer, so the signature cannot tell them apart. The claim {@code token_type} does: without
 * this check a refresh token, which lives for days, would work as an access token.
 */
public final class TokenTypeValidator implements OAuth2TokenValidator<Jwt> {

  /** The name of the claim that holds the type of the token. */
  public static final String CLAIM_TOKEN_TYPE = "token_type";

  /** The value of {@link #CLAIM_TOKEN_TYPE} in an access token. */
  public static final String ACCESS = "access";

  private static final OAuth2Error NOT_AN_ACCESS_TOKEN = new OAuth2Error(
      OAuth2ErrorCodes.INVALID_TOKEN, "The token is not an access token", null);

  /**
   * Checks the claim {@code token_type}.
   *
   * @param jwt a token whose signature has already been verified
   * @return success if the claim is the text {@code access}, a failure for every other value and
   *     for a missing claim
   */
  @Override
  public OAuth2TokenValidatorResult validate(Jwt jwt) {
    if (ACCESS.equals(jwt.getClaims().get(CLAIM_TOKEN_TYPE))) {
      return OAuth2TokenValidatorResult.success();
    }
    return OAuth2TokenValidatorResult.failure(NOT_AN_ACCESS_TOKEN);
  }
}
