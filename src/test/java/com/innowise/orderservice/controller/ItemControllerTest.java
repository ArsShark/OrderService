package com.innowise.orderservice.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.innowise.orderservice.config.ClockConfig;
import com.innowise.orderservice.config.JwtConfig;
import com.innowise.orderservice.config.SecurityConfig;
import com.innowise.orderservice.dto.request.ItemRequest;
import com.innowise.orderservice.dto.response.ItemResponse;
import com.innowise.orderservice.exception.ItemNotFoundException;
import com.innowise.orderservice.service.ItemService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP contract tests of {@link ItemController}: paths, status codes, headers and JSON shape.
 * The service is a mock, so these tests do not touch a database; the full flow is covered by the
 * integration tests.
 *
 * <p>The security chain is real. The default caller is an admin; the tests of the other role set
 * their own. The decoder of the tokens is real too but never used here, because
 * {@code @WithMockUser} authenticates before it.
 */
@WebMvcTest(controllers = ItemController.class,
  excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import({SecurityConfig.class, JwtConfig.class, ClockConfig.class})
@WithMockUser(roles = "ADMIN")
class ItemControllerTest {

  private static final Instant NOW = Instant.parse("2030-01-01T10:15:30Z");

  private static final String VALID_ITEM_JSON = """
    {"name": "Keyboard", "price": 49.90}
    """;

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private ItemService itemService;

  private static ItemResponse item(long id) {
    return new ItemResponse(id, "Keyboard", new BigDecimal("49.90"), NOW, NOW);
  }

  @Test
  void createReturns201WithLocationAndBody() throws Exception {
    when(itemService.create(any(ItemRequest.class))).thenReturn(item(3));

    mockMvc.perform(post("/api/items")
      .contentType(MediaType.APPLICATION_JSON)
      .content(VALID_ITEM_JSON)).andExpectAll(
      status().isCreated(),
      header().string(HttpHeaders.LOCATION, "http://localhost/api/items/3"),
      jsonPath("$.id").value(3),
      jsonPath("$.name").value("Keyboard"),
      jsonPath("$.price").value(49.90));

    ArgumentCaptor<ItemRequest> request = ArgumentCaptor.forClass(ItemRequest.class);
    verify(itemService).create(request.capture());
    assertEquals(new ItemRequest("Keyboard", new BigDecimal("49.90")), request.getValue());
  }

  @Test
  void createRejectsAnInvalidBodyWith400AndNeverEchoesTheValues() throws Exception {
    String body = """
      {"name": "", "price": -5.123}
      """;

    mockMvc.perform(post("/api/items")
      .contentType(MediaType.APPLICATION_JSON)
      .content(body)).andExpectAll(
      status().isBadRequest(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.errors[0].field").value("name"),
      jsonPath("$.errors[1].field").value("price"),
      content().string(not(containsString("5.123"))));

    verifyNoInteractions(itemService);
  }

  @Test
  void getByIdReturns200() throws Exception {
    when(itemService.getById(3L)).thenReturn(item(3));

    mockMvc.perform(get("/api/items/3")).andExpectAll(
      status().isOk(),
      jsonPath("$.id").value(3),
      jsonPath("$.price").value(49.90));
  }

  @Test
  void getByIdAnswers404ForAMissingItem() throws Exception {
    when(itemService.getById(9L)).thenThrow(new ItemNotFoundException(9L));

    mockMvc.perform(get("/api/items/9")).andExpectAll(
      status().isNotFound(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.detail").value("Item with id 9 not found"));
  }

  @Test
  void getByIdAnswers400ForANonNumericId() throws Exception {
    mockMvc.perform(get("/api/items/abc")).andExpectAll(
      status().isBadRequest(),
      jsonPath("$.detail").value("Invalid value of the parameter 'id'"));

    verifyNoInteractions(itemService);
  }

  @Test
  void getAllReturnsAPageInTheStablePageShape() throws Exception {
    Pageable pageable = PageRequest.of(0, 20, Sort.by("id"));
    when(itemService.getAll(any(Pageable.class)))
      .thenReturn(new PageImpl<>(List.of(item(1), item(2)), pageable, 2));

    mockMvc.perform(get("/api/items")).andExpectAll(
      status().isOk(),
      jsonPath("$.content.length()").value(2),
      jsonPath("$.page").value(0),
      jsonPath("$.size").value(20),
      jsonPath("$.totalElements").value(2),
      jsonPath("$.totalPages").value(1));
  }

  @Test
  void getAllSortsByIdWhenNothingIsAskedAndCapsThePageSizeAt100() throws Exception {
    when(itemService.getAll(any(Pageable.class)))
      .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

    mockMvc.perform(get("/api/items").param("size", "1000")).andExpect(status().isOk());

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(itemService).getAll(pageable.capture());
    assertAll(
      () -> assertEquals(100, pageable.getValue().getPageSize()),
      () -> assertEquals(Sort.by("id"), pageable.getValue().getSort()));
  }

  @Test
  void getAllAppendsIdToAnAllowedSort() throws Exception {
    when(itemService.getAll(any(Pageable.class)))
      .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

    mockMvc.perform(get("/api/items").param("sort", "price,desc")).andExpect(status().isOk());

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(itemService).getAll(pageable.capture());
    assertEquals(Sort.by(Sort.Order.desc("price"), Sort.Order.asc("id")),
      pageable.getValue().getSort());
  }

  @Test
  void getAllRejectsAPropertyThatIsNotInTheWhitelistWithoutEchoingIt() throws Exception {
    mockMvc.perform(get("/api/items").param("sort", "deleted")).andExpectAll(
      status().isBadRequest(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      content().string(not(containsString("deleted"))));

    verifyNoInteractions(itemService);
  }

  @Test
  void updateReturns200() throws Exception {
    when(itemService.update(eq(3L), any(ItemRequest.class))).thenReturn(item(3));

    mockMvc.perform(put("/api/items/3")
      .contentType(MediaType.APPLICATION_JSON)
      .content(VALID_ITEM_JSON)).andExpectAll(
      status().isOk(),
      jsonPath("$.id").value(3));
  }

  @Test
  void updateAnswers404ForAMissingItem() throws Exception {
    when(itemService.update(eq(9L), any(ItemRequest.class)))
      .thenThrow(new ItemNotFoundException(9L));

    mockMvc.perform(put("/api/items/9")
      .contentType(MediaType.APPLICATION_JSON)
      .content(VALID_ITEM_JSON)).andExpect(status().isNotFound());
  }

  @Test
  void deleteReturns204WithoutABody() throws Exception {
    mockMvc.perform(delete("/api/items/3")).andExpectAll(
      status().isNoContent(),
      content().string(""));

    verify(itemService).delete(3L);
  }

  @Test
  void deleteAnswers404ForAMissingItem() throws Exception {
    doThrow(new ItemNotFoundException(9L)).when(itemService).delete(9L);

    mockMvc.perform(delete("/api/items/9")).andExpect(status().isNotFound());
  }

  // ---- the user role: reads, never changes

  @Test
  @WithMockUser(roles = "USER")
  void aUserReadsItems() throws Exception {
    when(itemService.getById(3L)).thenReturn(item(3));
    when(itemService.getAll(any(Pageable.class)))
      .thenReturn(new PageImpl<>(List.of(item(3)), PageRequest.of(0, 20), 1));

    assertAll(
      () -> mockMvc.perform(get("/api/items/3")).andExpect(status().isOk()),
      () -> mockMvc.perform(get("/api/items")).andExpect(status().isOk()));
  }

  @Test
  @WithMockUser(roles = "USER")
  void aUserCannotChangeItemsAndTheServiceIsNeverCalled() throws Exception {
    assertAll(
      () -> mockMvc.perform(post("/api/items")
          .contentType(MediaType.APPLICATION_JSON).content(VALID_ITEM_JSON))
        .andExpectAll(status().isForbidden(), jsonPath("$.detail").value("Access is denied")),
      () -> mockMvc.perform(put("/api/items/3")
          .contentType(MediaType.APPLICATION_JSON).content(VALID_ITEM_JSON))
        .andExpect(status().isForbidden()),
      () -> mockMvc.perform(delete("/api/items/3")).andExpect(status().isForbidden()));

    verifyNoInteractions(itemService);
  }
}
