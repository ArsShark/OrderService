package com.innowise.orderservice;

import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Signs tokens that look like the access tokens of the Authentication Service.
 *
 * <p>The claims are {@code sub}, {@code user_id}, {@code role}, {@code email}, {@code jti},
 * {@code iss}, {@code iat}, {@code exp} and {@code token_type}, and the header has {@code alg},
 * {@code typ} and {@code kid}, as in the contract of the real service. The builder starts from a
 * valid token of a user; a test changes only what it wants to break, or removes a claim by
 * setting it to {@code null}.
 */
public final class TestTokens {

  /** The issuer of the tokens, the default of {@code JWT_ISSUER}. */
  public static final String ISSUER = "authentication-service";

  /** The default {@code iat}: the start time of the clock of the integration tests. */
  public static final Instant ISSUED_AT = Instant.parse("2026-11-02T10:00:00Z");

  /** The lifetime of an access token. */
  public static final Duration ACCESS_TTL = Duration.ofMinutes(15);

  private TestTokens() {
  }

  /**
   * Starts a valid access token of a user: account 1, profile 7, role {@code USER}.
   *
   * @return a builder
   */
  public static Builder access() {
    return new Builder();
  }

  /**
   * Starts a valid access token of an admin: account 2, no profile, role {@code ADMIN}.
   *
   * @return a builder
   */
  public static Builder admin() {
    return new Builder().subject("2").userId(null).role("ADMIN").email("admin@example.com");
  }

  /** Builds one token. */
  public static final class Builder {

    private String issuer = ISSUER;
    private String subject = "1";
    private Long userId = 7L;
    private String role = "USER";
    private String email = "user@example.com";
    private String tokenType = "access";
    private Instant issuedAt = ISSUED_AT;
    private Instant expiresAt = ISSUED_AT.plus(ACCESS_TTL);
    private KeyPair keyPair = TestKeys.KEY_PAIR;
    private String keyId = TestKeys.KEY_ID;

    private Builder() {
    }

    /** Sets {@code iss}; {@code null} leaves the claim out. */
    public Builder issuer(String value) {
      this.issuer = value;
      return this;
    }

    /** Sets {@code sub}, the id of the account. */
    public Builder subject(String value) {
      this.subject = value;
      return this;
    }

    /** Sets {@code user_id}, the id of the profile; {@code null} leaves the claim out. */
    public Builder userId(Long value) {
      this.userId = value;
      return this;
    }

    /** Sets {@code role}; {@code null} leaves the claim out. */
    public Builder role(String value) {
      this.role = value;
      return this;
    }

    /** Sets {@code email}; {@code null} leaves the claim out. */
    public Builder email(String value) {
      this.email = value;
      return this;
    }

    /** Sets {@code token_type}; {@code null} leaves the claim out. */
    public Builder tokenType(String value) {
      this.tokenType = value;
      return this;
    }

    /** Sets {@code iat}. */
    public Builder issuedAt(Instant value) {
      this.issuedAt = value;
      return this;
    }

    /** Sets {@code exp}; {@code null} leaves the claim out. */
    public Builder expiresAt(Instant value) {
      this.expiresAt = value;
      return this;
    }

    /** Signs with another key pair, keeping the {@code kid} of the real key unless changed. */
    public Builder signedWith(KeyPair value) {
      this.keyPair = value;
      return this;
    }

    /** Sets the {@code kid} of the header. */
    public Builder keyId(String value) {
      this.keyId = value;
      return this;
    }

    /**
     * Signs the token with RS256.
     *
     * @return the compact serialized token
     */
    public String build() {
      JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
          .id(UUID.randomUUID().toString())
          .subject(subject)
          .issuedAt(issuedAt);
      if (issuer != null) {
        claims.issuer(issuer);
      }
      if (expiresAt != null) {
        claims.expiresAt(expiresAt);
      }
      if (userId != null) {
        claims.claim("user_id", userId);
      }
      if (role != null) {
        claims.claim("role", role);
      }
      if (email != null) {
        claims.claim("email", email);
      }
      if (tokenType != null) {
        claims.claim("token_type", tokenType);
      }
      String kid = keyId;
      JwtEncoder encoder = NimbusJwtEncoder
          .withKeyPair((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate())
          .jwkPostProcessor(jwk -> jwk.keyID(kid))
          .build();
      return encoder.encode(JwtEncoderParameters.from(claims.build())).getTokenValue();
    }
  }
}
