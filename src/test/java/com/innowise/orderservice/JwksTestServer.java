package com.innowise.orderservice;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tiny HTTP server that plays the key set endpoint of the Authentication Service.
 *
 * <p>It lets tests go the real way of {@code jwk-set-uri}: a real HTTP request, the real cache
 * and the real rate limit. A test can count the requests, make the server answer with an error or
 * with garbage, make it hang, or stop it. The server listens on the loopback address and a free
 * port.
 */
public final class JwksTestServer implements AutoCloseable {

  private static final String PATH = "/.well-known/jwks.json";
  private static final long HANG_LIMIT_SECONDS = 10;

  private final HttpServer server;
  private final ExecutorService executor =
      Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "jwks-test-server");
        thread.setDaemon(true);
        return thread;
      });
  private final AtomicInteger requests = new AtomicInteger();
  private final AtomicBoolean stopped = new AtomicBoolean();
  private final CountDownLatch release = new CountDownLatch(1);
  private volatile String body;
  private volatile int status = 200;
  private volatile boolean hanging;

  private JwksTestServer(String body) throws IOException {
    this.body = body;
    this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    this.server.createContext(PATH, this::handle);
    this.server.setExecutor(executor);
    this.server.start();
  }

  /**
   * Starts a server that answers every request with the given key set.
   *
   * @param jwksJson the body of the answer, for example {@link TestKeys#jwksJson()}
   * @return the running server
   */
  public static JwksTestServer start(String jwksJson) {
    try {
      return new JwksTestServer(jwksJson);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Returns the address of the key set, the value for {@code app.security.jwks-uri}.
   *
   * @return an {@code http} address on the loopback interface
   */
  public String jwksUri() {
    return String.format("http://%s:%d%s",
        server.getAddress().getAddress().getHostAddress(), server.getAddress().getPort(), PATH);
  }

  /**
   * Returns how many requests the server has received since it started, answered or not.
   *
   * @return the number of requests
   */
  public int requestCount() {
    return requests.get();
  }

  /**
   * Changes the answer for the next requests.
   *
   * @param newStatus the HTTP status
   * @param newBody the body
   */
  public void respondWith(int newStatus, String newBody) {
    this.status = newStatus;
    this.body = newBody;
  }

  /** Makes the server accept requests and never answer, until it is stopped. */
  public void hang() {
    this.hanging = true;
  }

  /** Stops the server; the port is closed afterwards. It is safe to call it twice. */
  public void stop() {
    if (stopped.compareAndSet(false, true)) {
      release.countDown();
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @Override
  public void close() {
    stop();
  }

  private void handle(HttpExchange exchange) throws IOException {
    requests.incrementAndGet();
    try (exchange) {
      if (hanging) {
        awaitRelease();
        return;
      }
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
  }

  private void awaitRelease() {
    try {
      release.await(HANG_LIMIT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
