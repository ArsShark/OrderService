package com.innowise.orderservice.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.innowise.orderservice.controller.ItemController;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * Pins the method security of the public API controllers.
 *
 * <p>The URL rules of {@code SecurityConfig} are the first line of defence; every handler method
 * also declares its own rule, so the controller shows who may call it and a changed URL matcher
 * cannot leave a method open. The test fails when a handler has no {@code @PreAuthorize}, and
 * when a rule differs from the table below.
 */
class ControllerMethodSecurityTest {

  @Test
  void everyHandlerMethodOfTheItemControllerDeclaresItsRule() {
    Map<String, String> expected = Map.of(
      "create", AccessRules.ADMIN_ONLY,
      "getById", AccessRules.ANY_ROLE,
      "getAll", AccessRules.ANY_ROLE,
      "update", AccessRules.ADMIN_ONLY,
      "delete", AccessRules.ADMIN_ONLY);

    assertEquals(expected, rulesOf(ItemController.class));
  }

  @Test
  void theTableCoversEveryHandlerOfTheControllers() {
    assertAll(
      () -> assertEquals(5, handlerMethods(ItemController.class).size()));
  }

  private static Map<String, String> rulesOf(Class<?> controller) {
    Map<String, String> rules = new TreeMap<>();
    for (Method method : handlerMethods(controller)) {
      PreAuthorize rule = method.getAnnotation(PreAuthorize.class);
      assertNotNull(rule, String.format("%s.%s has no @PreAuthorize",
        controller.getSimpleName(), method.getName()));
      rules.put(method.getName(), rule.value());
    }
    return rules;
  }

  private static List<Method> handlerMethods(Class<?> controller) {
    return Arrays.stream(controller.getDeclaredMethods())
      .filter(method -> method.isAnnotationPresent(GetMapping.class)
        || method.isAnnotationPresent(PostMapping.class)
        || method.isAnnotationPresent(PutMapping.class)
        || method.isAnnotationPresent(PatchMapping.class)
        || method.isAnnotationPresent(DeleteMapping.class))
      .toList();
  }
}
