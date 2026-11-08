package com.innowise.orderservice;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.JSONObjectUtils;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;

/**
 * Keys for tests. No key is stored in the repository: one RSA key pair is generated when the
 * class is loaded and shared by all tests of the JVM. It plays the signing key of the
 * Authentication Service.
 */
public final class TestKeys {

  /** The key pair of the "Authentication Service" in tests. */
  public static final KeyPair KEY_PAIR = generateRsa(2048);

  /** The {@code kid} of {@link #KEY_PAIR}: its RFC 7638 thumbprint, as the real service does. */
  public static final String KEY_ID = thumbprint((RSAPublicKey) KEY_PAIR.getPublic());

  private TestKeys() {
  }

  /**
   * Generates an RSA key pair.
   *
   * @param bits the length of the modulus
   * @return a new key pair
   */
  public static KeyPair generateRsa(int bits) {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(bits);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("The JDK has no RSA", e);
    }
  }

  /**
   * Computes the key id the way the Authentication Service does.
   *
   * @param key a public key
   * @return the base64url thumbprint
   */
  public static String thumbprint(RSAPublicKey key) {
    try {
      return new RSAKey.Builder(key).build().computeThumbprint().toString();
    } catch (JOSEException e) {
      throw new IllegalStateException("The thumbprint cannot be computed", e);
    }
  }

  /**
   * Builds the document that {@code GET /.well-known/jwks.json} of the Authentication Service
   * returns for {@link #KEY_PAIR}.
   *
   * @return the JSON text of the key set; it holds the public key only
   */
  public static String jwksJson() {
    RSAKey jwk = new RSAKey.Builder((RSAPublicKey) KEY_PAIR.getPublic())
        .keyUse(KeyUse.SIGNATURE)
        .algorithm(JWSAlgorithm.RS256)
        .keyID(KEY_ID)
        .build();
    return JSONObjectUtils.toJSONString(new JWKSet(jwk).toJSONObject(true));
  }
}
