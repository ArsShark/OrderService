package com.innowise.orderservice.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Answers the two security failures of the filter chain, 401 and 403, in the same RFC 9457
 * format as every other error of the service.
 *
 * <p>A filter runs before Spring MVC, so {@code @RestControllerAdvice} does not see its
 * exceptions by itself. This class hands the exception to the {@link HandlerExceptionResolver} of
 * Spring MVC with no handler, which runs the exception handlers of the advice. So the format is
 * written in one place, {@code GlobalExceptionHandler}, for the errors of controllers and of the
 * security chain alike.
 *
 * <p>It never calls {@code response.sendError}: that would start an ERROR dispatch to the error
 * page and replace our body. If nobody can answer the exception, the response gets the bare
 * status, so a failure of the error handling never turns into a 200.
 */
public class ProblemDetailSecurityHandler implements AuthenticationEntryPoint,
    AccessDeniedHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(ProblemDetailSecurityHandler.class);

  private final HandlerExceptionResolver resolver;

  /**
   * Creates the handler.
   *
   * @param resolver the exception resolver of Spring MVC (the composite that includes the
   *     {@code @ExceptionHandler} support)
   */
  public ProblemDetailSecurityHandler(HandlerExceptionResolver resolver) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
  }

  /** Answers a request that needs authentication: 401. */
  @Override
  public void commence(HttpServletRequest request, HttpServletResponse response,
      AuthenticationException authException) {
    delegate(request, response, authException, HttpStatus.UNAUTHORIZED);
  }

  /** Answers a request of an authenticated caller that has no right to it: 403. */
  @Override
  public void handle(HttpServletRequest request, HttpServletResponse response,
      AccessDeniedException accessDeniedException) {
    delegate(request, response, accessDeniedException, HttpStatus.FORBIDDEN);
  }

  private void delegate(HttpServletRequest request, HttpServletResponse response,
      Exception exception, HttpStatus fallback) {
    ModelAndView resolved = resolver.resolveException(request, response, null, exception);
    if (resolved == null) {
      LOGGER.error("No exception handler answered {}, sending the bare status {}",
          exception.getClass().getSimpleName(), fallback.value());
      if (!response.isCommitted()) {
        response.setStatus(fallback.value());
      }
    }
  }
}
