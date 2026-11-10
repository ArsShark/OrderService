package com.innowise.orderservice.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Tests {@link ProblemDetailSecurityHandler}: the exception goes to the resolver of Spring MVC
 * with no handler, and when nobody answers, the response still gets an error status and never a
 * 200. The format of the body is tested with the real resolver in the integration tests of the
 * chain.
 */
@ExtendWith(MockitoExtension.class)
class ProblemDetailSecurityHandlerTest {

  @Mock
  private HandlerExceptionResolver resolver;

  private ProblemDetailSecurityHandler handler;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;

  @BeforeEach
  void createHandler() {
    handler = new ProblemDetailSecurityHandler(resolver);
    request = new MockHttpServletRequest();
    response = new MockHttpServletResponse();
  }

  @Test
  void anAuthenticationFailureGoesToTheResolverWithoutAHandler() {
    AuthenticationException exception = new InsufficientAuthenticationException("any");
    when(resolver.resolveException(request, response, null, exception))
      .thenReturn(new ModelAndView());

    handler.commence(request, response, exception);

    assertAll(
      () -> verify(resolver).resolveException(request, response, null, exception),
      // The resolver writes the answer; the handler must not add a status of its own.
      () -> assertEquals(200, response.getStatus()));
  }

  @Test
  void anAccessDeniedGoesToTheResolverWithoutAHandler() {
    AccessDeniedException exception = new AccessDeniedException("any");
    when(resolver.resolveException(request, response, null, exception))
      .thenReturn(new ModelAndView());

    handler.handle(request, response, exception);

    assertAll(
      () -> verify(resolver).resolveException(request, response, null, exception),
      () -> assertEquals(200, response.getStatus()));
  }

  @Test
  void unauthorizedIsSentWhenNobodyAnswersAnAuthenticationFailure() {
    AuthenticationException exception = new InsufficientAuthenticationException("any");
    when(resolver.resolveException(request, response, null, exception)).thenReturn(null);

    handler.commence(request, response, exception);

    assertEquals(401, response.getStatus());
  }

  @Test
  void forbiddenIsSentWhenNobodyAnswersAnAccessDenied() {
    AccessDeniedException exception = new AccessDeniedException("any");
    when(resolver.resolveException(request, response, null, exception)).thenReturn(null);

    handler.handle(request, response, exception);

    assertEquals(403, response.getStatus());
  }

  @Test
  void aResponseThatIsCommittedAlreadyIsNotTouched() {
    AccessDeniedException exception = new AccessDeniedException("any");
    when(resolver.resolveException(request, response, null, exception)).thenReturn(null);
    response.setCommitted(true);

    handler.handle(request, response, exception);

    assertEquals(200, response.getStatus());
  }

  @Test
  void aHandlerWithoutAResolverCannotBeCreated() {
    assertThrows(NullPointerException.class, () -> new ProblemDetailSecurityHandler(null));
  }
}
