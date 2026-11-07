package com.innowise.orderservice.service;

import com.innowise.orderservice.dto.request.OrderRequest;
import com.innowise.orderservice.dto.response.OrderDetailsResponse;
import com.innowise.orderservice.dto.response.OrderResponse;
import com.innowise.orderservice.mapper.OrderMapper;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * The entry point of the service layer for the controllers: every answer except the deletion
 * carries the owner of the order.
 *
 * <p>The order of the steps is the point of this class. First {@link OrderService} does its work
 * in its own transaction and commits; only then {@link UserInfoResolver} calls the User Service.
 * So an HTTP call never holds a database connection, and an order that is saved stays saved
 * however the call ends: if the User Service cannot answer, the client gets the order with
 * {@code userInfoAvailable = false} and not an error that would invite a second POST. This class
 * has no transaction on purpose.
 */
@Service
public class OrderFacade {

  private final OrderService orderService;
  private final UserInfoResolver userInfoResolver;
  private final OrderMapper orderMapper;

  public OrderFacade(OrderService orderService, UserInfoResolver userInfoResolver,
    OrderMapper orderMapper) {
    this.orderService = orderService;
    this.userInfoResolver = userInfoResolver;
    this.orderMapper = orderMapper;
  }

  /** Creates an order of the caller; see {@link OrderService#create}. */
  public OrderDetailsResponse create(Caller caller, OrderRequest request) {
    return withUser(orderService.create(caller, request));
  }

  /** Returns an order of the caller (an admin: any order); see {@link OrderService#getById}. */
  public OrderDetailsResponse getById(Long id, Caller caller) {
    return withUser(orderService.getById(id, caller));
  }

  /** Returns a page of the orders of every user. For admins. */
  public Page<OrderDetailsResponse> findAll(OrderFilter filter, Pageable pageable) {
    return withUsers(orderService.findAll(filter, pageable));
  }

  /** Returns a page of the orders of one user. */
  public Page<OrderDetailsResponse> findOwn(Long userId, OrderFilter filter, Pageable pageable) {
    return withUsers(orderService.findOwn(userId, filter, pageable));
  }

  /** Replaces the lines of an order; see {@link OrderService#update}. */
  public OrderDetailsResponse update(Long id, Caller caller, OrderRequest request) {
    return withUser(orderService.update(id, caller, request));
  }

  /** Cancels an order; see {@link OrderService#cancel}. */
  public OrderDetailsResponse cancel(Long id, Caller caller) {
    return withUser(orderService.cancel(id, caller));
  }

  /** Deletes an order softly. The answer has no body, so the User Service is not asked. */
  public void delete(Long id, Caller caller) {
    orderService.delete(id, caller);
  }

  private OrderDetailsResponse withUser(OrderResponse order) {
    UserLookup lookup = userInfoResolver.lookup(order.userEmail());
    return orderMapper.toDetails(order, lookup.user(), lookup.available());
  }

  /** Asks the User Service once for every distinct owner of the page. */
  private Page<OrderDetailsResponse> withUsers(Page<OrderResponse> page) {
    List<String> emails = page.getContent().stream().map(OrderResponse::userEmail).toList();
    Map<String, UserLookup> owners = userInfoResolver.lookup(emails);
    return page.map(order -> {
      UserLookup lookup = owners.get(order.userEmail());
      return orderMapper.toDetails(order, lookup.user(), lookup.available());
    });
  }
}
