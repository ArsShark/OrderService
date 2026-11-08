package com.innowise.orderservice.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Makes every failure of the decoder a rejected token.
 *
 * <p>Spring Security separates two kinds of failure. A {@link BadJwtException} means that the
 * token is wrong, and the resource server answers 401. Any other {@link JwtException} means that
 * the token could not be checked, for example because the key set of the Authentication Service
 * cannot be fetched or the rate limit of the key source was reached. The resource server turns
 * that into an {@code AuthenticationServiceException}, which its filter rethrows, and the client
 * gets a 500 with a stack trace in the log.
 *
 * <p>This service has no reason to treat the two differently: a token that cannot be checked is
 * not accepted, and the client gets the same 401 as for a wrong token. The failure is logged as a
 * warning with the class names only, because the messages of the underlying exceptions contain
 * the address of the key set. Other exceptions are not caught: they are bugs and stay visible as
 * a 500.
 */
public final class FailClosedJwtDecoder implements JwtDecoder {

  /** The message of the exception for a token that could not be checked; it is a constant. */
  public static final String MESSAGE = "The token could not be verified";

  private static final Logger LOGGER = LoggerFactory.getLogger(FailClosedJwtDecoder.class);

  private final JwtDecoder delegate;

  /**
   * Wraps a decoder.
   *
   * @param delegate the decoder that does the work
   */
  public FailClosedJwtDecoder(JwtDecoder delegate) {
    this.delegate = delegate;
  }

  /**
   * Decodes and verifies a token.
   *
   * @param token the compact serialized token
   * @return the verified token
   * @throws BadJwtException if the token is wrong or could not be checked
   */
  @Override
  public Jwt decode(String token) throws JwtException {
    try {
      return delegate.decode(token);
    } catch (BadJwtException e) {
      throw e;
    } catch (JwtException e) {
      Throwable cause = e.getCause();
      LOGGER.warn("A token could not be verified, the key set is probably not available: {} {}",
          e.getClass().getSimpleName(),
          cause == null ? "" : cause.getClass().getSimpleName());
      throw new BadJwtException(MESSAGE, e);
    }
  }
}
