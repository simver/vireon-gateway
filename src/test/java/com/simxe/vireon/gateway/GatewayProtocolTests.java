package com.simxe.vireon.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

class GatewayProtocolTests {
  @Test
  void malformedDownstreamResponseIsBadGateway() throws Exception {
    var response = get("/api/iam/malformed");
    assertEquals(502, response.statusCode());
    assertEquals("{\"code\":\"BAD_GATEWAY\"}", response.body());
    assertEquals(201, get("/api/iam/echo").statusCode());
  }

  @Test
  void internalNumberFormatFailureRemainsInternalError() {
    var exchange =
        org.springframework.mock.web.server.MockServerWebExchange.from(
            org.springframework.mock.http.server.reactive.MockServerHttpRequest.get(
                "/api/iam/echo"));
    new GatewayErrors().handle(exchange, new NumberFormatException("internal")).block();
    assertEquals(500, exchange.getResponse().getStatusCode().value());
    assertEquals("{\"code\":\"INTERNAL_ERROR\"}", exchange.getResponse().getBodyAsString().block());
  }

  @ParameterizedTest
  @ValueSource(strings = {"X-CSRF-TOKEN", "x-XsRf-ToKeN", "cOoKiE", "ORIGIN", "Authorization"})
  void connectionCannotEraseAnySecurityHeader(String name) throws Exception {
    assertTrue(
        raw(
                "/api/iam/echo",
                name + ": evidence\r\nConnection: close\r\nConnection: X-Custom, " + name + "\r\n")
            .startsWith("HTTP/1.1 400"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
  void methodsAndBinaryChunkedBody(String method) throws Exception {
    byte[] payload = new byte[] {0, 1, (byte) 255, (byte) 128, 13, 10, 42};
    var response =
        CLIENT.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/iam/echo"))
                .header("Content-Type", "application/octet-stream")
                .method(
                    method,
                    HttpRequest.BodyPublishers.ofInputStream(
                        () -> new ByteArrayInputStream(payload)))
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(201, response.statusCode());
    assertArrayEquals(
        payload,
        Arrays.copyOfRange(
            response.body(), response.body().length - payload.length, response.body().length));
    assertTrue(
        new String(response.body(), StandardCharsets.ISO_8859_1)
            .startsWith(method + " /api/iam/echo HTTP/1.1"));
    assertTrue(response.headers().allValues("x-hop-response").isEmpty());
  }

  static ProtocolBackend backend;
  static ConfigurableApplicationContext context;
  static int port;
  static int management;
  static final HttpClient CLIENT =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(3))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  @BeforeAll
  static void start() throws Exception {
    backend = new ProtocolBackend();
    context =
        SpringApplication.run(
            GatewayApplication.class,
            "--server.port=0",
            "--management.server.port=0",
            "--vireon.gateway.routes.iam=" + backend.uri(),
            "--vireon.gateway.routes.audit=" + backend.uri(),
            "--vireon.gateway.response-timeout=400ms");
    port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
    management =
        context.getEnvironment().getRequiredProperty("local.management.port", Integer.class);
  }

  @AfterAll
  static void stop() throws Exception {
    if (context != null) context.close();
    if (backend != null) backend.close();
  }

  static HttpResponse<String> get(String path) throws Exception {
    return CLIENT.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(5))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/iam",
        "/api/iam/echo",
        "/api/audit",
        "/api/audit/echo",
        "/api/iam/a%20b?x=a%2Bb&x=c%20d"
      })
  void exactRawPathAndQuery(String path) throws Exception {
    var response = get(path);
    assertEquals(201, response.statusCode());
    assertTrue(response.body().startsWith("GET " + path + " HTTP/1.1"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/iamx",
        "/api/auditing",
        "/api/system",
        "/api/qbc/x",
        "/internal/iam/v1/introspect",
        "/actuator/health",
        "/actuator/gateway/routes"
      })
  void noOtherRoutes(String path) throws Exception {
    assertEquals(404, get(path).statusCode());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/iam//x",
        "/api/iam/../x",
        "/api/iam/./x",
        "/api/iam/a;x=1",
        "/api/iam/%2e%2e/x",
        "/api/iam/a%2fb",
        "/api/iam/%252f"
      })
  void rejectAmbiguousPaths(String path) throws Exception {
    assertEquals(400, get(path).statusCode());
  }

  @Test
  void cookiesBodyAndHeaders() throws Exception {
    var response =
        CLIENT.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/iam/echo"))
                .header("Content-Type", "application/json")
                .header("Cookie", "a=one; a=two")
                .header("Origin", "https://frontend.example")
                .header("X-XSRF-TOKEN", "synthetic")
                .POST(HttpRequest.BodyPublishers.ofString("{\"hello\":\"world\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(201, response.statusCode());
    assertTrue(response.body().endsWith("{\"hello\":\"world\"}"));
    assertTrue(response.body().contains("a=one; a=two"));
    assertTrue(response.body().contains("https://frontend.example"));
    var cookies = response.headers().allValues("set-cookie");
    assertEquals(3, cookies.size());
    assertTrue(cookies.getFirst().contains("Max-Age=0"));
    assertTrue(cookies.get(1).contains("Secure; HttpOnly; SameSite=Strict"));
  }

  @Test
  void noProxyOrIdentitySpoofing() throws Exception {
    var response =
        CLIENT.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/iam/echo"))
                .header("Forwarded", "for=203.0.113.5;proto=https")
                .header("X-Forwarded-Host", "evil.example")
                .header("X-Real-IP", "203.0.113.5")
                .header("X-User-Id", "admin")
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(201, response.statusCode());
    String body = response.body().toLowerCase(Locale.ROOT);
    assertFalse(body.contains("forwarded"));
    assertFalse(body.contains("x-real-ip"));
    assertFalse(body.contains("x-user-id"));
    assertTrue(body.contains("host: 127.0.0.1:"));
  }

  static String raw(String path, String headers) throws Exception {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5000);
      socket
          .getOutputStream()
          .write(
              ("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n" + headers + "\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
      return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void preserveRepeatedAuthenticationEvidence() throws Exception {
    String result =
        raw(
            "/api/iam/echo",
            "Authorization: Bearer one\r\nAuthorization: Bearer two\r\nCookie: a=one\r\nCookie: a=two\r\nOrigin: https://one.example\r\nOrigin: https://two.example\r\nX-XSRF-TOKEN: one\r\nX-XSRF-TOKEN: two\r\nConnection: close\r\n");
    assertTrue(result.startsWith("HTTP/1.1 201"));
    String lower = result.toLowerCase(Locale.ROOT);
    for (String evidence :
        List.of(
            "authorization: bearer one",
            "authorization: bearer two",
            "a=one",
            "a=two",
            "origin: https://one.example",
            "origin: https://two.example",
            "x-xsrf-token: one",
            "x-xsrf-token: two")) assertTrue(lower.contains(evidence), evidence);
  }

  @Test
  void connectionNominationsCannotEraseSecurityEvidence() throws Exception {
    assertTrue(
        raw(
                "/api/iam/echo",
                "Authorization: invalid\r\nConnection: close\r\nConnection: X-Custom, AuThOrIzAtIoN\r\n")
            .startsWith("HTTP/1.1 400"));
    String result = raw("/api/iam/echo", "X-Custom: hop-secret\r\nConnection: close, X-Custom\r\n");
    assertFalse(result.contains("hop-secret"));
  }

  @Test
  void downstreamStatusesAreNotWrapped() throws Exception {
    for (var item :
        Map.of("unauthorized", 401, "forbidden", 403, "unavailable", 503, "redirect", 302)
            .entrySet()) {
      var response = get("/api/iam/" + item.getKey());
      assertEquals(item.getValue(), response.statusCode());
      assertTrue(response.body().startsWith("GET /api/iam/"));
      assertEquals("Bearer", response.headers().firstValue("www-authenticate").orElseThrow());
    }
  }

  @Test
  void timeoutResetAndRecoveryWithoutRetry() throws Exception {
    int before = backend.count.get();
    var timeout =
        CLIENT.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/iam/slow"))
                .POST(HttpRequest.BodyPublishers.ofString("write-once"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(504, timeout.statusCode());
    assertEquals("{\"code\":\"GATEWAY_TIMEOUT\"}", timeout.body());
    assertEquals(before + 1, backend.count.get());
    assertEquals(502, get("/api/iam/reset").statusCode());
    assertEquals(before + 2, backend.count.get());
    assertEquals(201, get("/api/iam/echo").statusCode());
  }

  @Test
  void independentMinimalManagement() throws Exception {
    assertNotEquals(port, management);
    var health =
        CLIENT.send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + management + "/actuator/health"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, health.statusCode());
    assertEquals("{\"groups\":[\"liveness\",\"readiness\"],\"status\":\"UP\"}", health.body());
    var env =
        CLIENT.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + management + "/actuator/env"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(404, env.statusCode());
    var api =
        CLIENT.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + management + "/api/iam/echo"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(404, api.statusCode());
  }

  @Test
  void committedResponseTimesOutWithoutAppendingErrorJson() throws Exception {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5000);
      socket
          .getOutputStream()
          .write(
              ("GET /api/iam/stream HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
      String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(response.startsWith("HTTP/1.1 200"));
      assertTrue(response.contains("part"));
      assertFalse(response.contains("GATEWAY_TIMEOUT"));
      assertFalse(response.endsWith("0\r\n\r\n"));
    }
  }

  @Test
  void clientDisconnectCancelsUpstream() throws Exception {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(3000);
      socket
          .getOutputStream()
          .write(
              "GET /api/iam/cancel HTTP/1.1\r\nHost: localhost\r\n\r\n"
                  .getBytes(StandardCharsets.US_ASCII));
      BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
      String line;
      while ((line = reader.readLine()) != null && !line.equals("part")) {}
      assertEquals("part", line);
      Thread.sleep(700); // Longer than response timeout; heartbeat keeps upstream active.
      assertFalse(
          backend.cancelled.isDone(), "A live client must not be mistaken for cancellation");
    }
    assertTrue(backend.cancelled.get(2, java.util.concurrent.TimeUnit.SECONDS));
  }
}
