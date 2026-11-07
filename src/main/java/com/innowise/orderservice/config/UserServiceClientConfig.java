package com.innowise.orderservice.config;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.DefaultUriBuilderFactory.EncodingMode;

/**
 * Builds the {@link RestClient} that calls the internal endpoints of the User Service.
 *
 * <p>The client always sends the internal key in the header {@code X-Internal-Key}; the User
 * Service accepts the calls of {@code /internal/**} only with it. Both timeouts are set: a request
 * of a client must not hang on a service that does not answer.
 */
@Configuration
@EnableConfigurationProperties({UserServiceProperties.class, InternalApiProperties.class})
public class UserServiceClientConfig {

  /** The header with the shared key of the internal endpoints. */
  public static final String INTERNAL_KEY_HEADER = "X-Internal-Key";

  /**
   * Creates the client of the User Service.
   *
   * <p>HTTP/1.1 is forced: the JDK client would first try to upgrade a plain HTTP connection to
   * HTTP/2, which some servers answer badly. URI variables are encoded strictly
   * ({@code VALUES_ONLY}): an email such as {@code anna+shop@example.com} must reach the server
   * with the plus sign as {@code %2B}, because a plain plus in a query string means a space.
   *
   * @param properties the address and the timeouts
   * @param internal the shared key of the internal endpoints
   * @return a client with the base address, the key and the timeouts set
   */
  @Bean
  public RestClient userServiceRestClient(UserServiceProperties properties,
    InternalApiProperties internal) {
    HttpClient httpClient = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(properties.connectTimeout())
      .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.readTimeout());
    DefaultUriBuilderFactory uriFactory = new DefaultUriBuilderFactory(properties.baseUri());
    uriFactory.setEncodingMode(EncodingMode.VALUES_ONLY);
    return RestClient.builder()
      .uriBuilderFactory(uriFactory)
      .requestFactory(requestFactory)
      .defaultHeader(INTERNAL_KEY_HEADER, internal.apiKey())
      .build();
  }
}
