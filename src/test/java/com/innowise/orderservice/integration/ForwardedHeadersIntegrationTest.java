package com.innowise.orderservice.integration;

import static com.innowise.orderservice.integration.ApiSupport.annaToken;
import static com.innowise.orderservice.integration.ApiSupport.as;
import static com.innowise.orderservice.integration.ApiSupport.itemJson;
import static com.innowise.orderservice.integration.ApiSupport.json;
import static com.innowise.orderservice.integration.ApiSupport.orderJson;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Checks that the links of the service follow the {@code X-Forwarded-*} headers of the API
 * Gateway: a client that reaches the service through the Gateway must see the address of the
 * Gateway in the {@code Location} header, not the internal host and port of the service. The
 * setting behind it is {@code server.forward-headers-strategy: framework}; the Gateway sends the
 * headers and drops the ones that a client sends itself, so this service may trust them.
 */
class ForwardedHeadersIntegrationTest extends AbstractIntegrationTest {

  private static final String ANNA = annaToken();

  @BeforeEach
  void createTheCatalogue() {
    insertItem("Keyboard", "49.90");
  }

  @Test
  void theLocationOfACreatedOrderUsesTheHostAndProtocolOfTheGateway() throws Exception {
    String location = locationOf(placeOrderRequest()
      .header("X-Forwarded-Host", "shop.example.test")
      .header("X-Forwarded-Proto", "https"));

    assertEquals("https://shop.example.test/api/orders/1", location);
  }

  @Test
  void theLocationOfACreatedItemUsesTheHostAndProtocolOfTheGateway() throws Exception {
    String location = locationOf(json(post("/api/items"), itemJson("Mouse", "19.90"))
      .header("X-Forwarded-Host", "shop.example.test")
      .header("X-Forwarded-Proto", "https"));

    assertEquals("https://shop.example.test/api/items/2", location);
  }

  @Test
  void thePortOfTheGatewayIsPartOfTheLocation() throws Exception {
    String location = locationOf(placeOrderRequest()
      .header("X-Forwarded-Host", "shop.example.test")
      .header("X-Forwarded-Proto", "https")
      .header("X-Forwarded-Port", "8443"));

    assertEquals("https://shop.example.test:8443/api/orders/1", location);
  }

  @Test
  void withoutForwardedHeadersTheLocationKeepsTheHostOfTheRequest() throws Exception {
    String orderLocation = locationOf(placeOrderRequest());
    String itemLocation = locationOf(json(post("/api/items"), itemJson("Mouse", "19.90")));

    assertAll(
      () -> assertEquals("http://localhost/api/orders/1", orderLocation),
      () -> assertEquals("http://localhost/api/items/2", itemLocation));
  }

  private static MockHttpServletRequestBuilder placeOrderRequest() {
    return json(as(post("/api/orders"), ANNA), orderJson(1, 1));
  }

  private String locationOf(MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc.perform(request)
      .andExpect(status().isCreated())
      .andReturn()
      .getResponse()
      .getHeader(HttpHeaders.LOCATION);
  }
}
