package com.innowise.orderservice.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;

/**
 * Makes {@code MockMvc} of the integration tests send the token of an admin with every request.
 *
 * <p>The default request of the builder is merged into every request, and a header that the
 * request sets itself wins. So a test that needs another caller (a user, a bad token) sets its
 * own {@code Authorization} header, and all other tests need no change. A customizer is used
 * instead of {@code @WithMockUser} because the security context of that annotation lives in the
 * thread of the test, and the concurrency tests send requests from other threads.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestSecurityConfiguration {

  /**
   * The customizer that adds the default {@code Authorization} header.
   *
   * @return the customizer
   */
  @Bean
  MockMvcBuilderCustomizer adminByDefault() {
    return builder -> builder.defaultRequest(get("/")
        .header(HttpHeaders.AUTHORIZATION, AbstractIntegrationTest.bearer(
            AbstractIntegrationTest.ADMIN_TOKEN)));
  }
}
