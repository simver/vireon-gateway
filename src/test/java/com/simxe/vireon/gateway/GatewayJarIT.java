package com.simxe.vireon.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Starts the packaged artifact, never a sibling project or test-classpath application. */
class GatewayJarIT {
  static final Path JAR = Path.of("target/vireon-gateway-0.1.0-SNAPSHOT.jar").toAbsolutePath();

  static int availablePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
      return socket.getLocalPort();
    }
  }

  static HttpResponse<String> get(int port, String path) throws Exception {
    try (HttpClient client =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
              .timeout(Duration.ofSeconds(3))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  static final class Running implements AutoCloseable {
    final Process process;
    final int port;
    final int management;
    final Path log;

    Running(String name, String... args) throws Exception {
      port = availablePort();
      management = availablePort();
      log = Path.of("target", name + ".log");
      var command =
          new ArrayList<>(
              List.of(
                  Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                  "-jar",
                  JAR.toString(),
                  "--server.port=" + port,
                  "--management.server.port=" + management,
                  "--spring.main.banner-mode=off"));
      command.addAll(List.of(args));
      process =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(log.toFile())
              .start();
    }

    void ready() throws Exception {
      long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (System.nanoTime() < end) {
        if (!process.isAlive()) fail("Gateway exited; see " + log);
        try {
          if (get(management, "/actuator/health").statusCode() == 200) return;
        } catch (IOException expectedDuringStartup) {
        }
        Thread.sleep(100);
      }
      fail("Gateway startup timeout; see " + log);
    }

    @Override
    public void close() throws Exception {
      process.destroy();
      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
      }
    }
  }

  @Test
  void explicitNeverHealthGroupRemainsAvailableWithoutDetails() throws Exception {
    try (var app =
        new Running(
            "jar-health-group-never",
            "--management.endpoint.health.group.readiness.include=*",
            "--management.endpoint.health.group.readiness.show-details=never",
            "--management.endpoint.health.group.readiness.show-components=never")) {
      app.ready();
      var response = get(app.management, "/actuator/health/readiness");
      assertEquals(200, response.statusCode());
      assertEquals("{\"status\":\"UP\"}", response.body());
    }
  }

  @ParameterizedTest
  @CsvSource({
    "readiness, show-details, always",
    "liveness, show-components, always",
    "readiness, show-details, when-authorized",
    "liveness, show-components, when-authorized"
  })
  void healthGroupsCannotOverrideNoDetails(String group, String property, String value)
      throws Exception {
    try (var app =
        new Running(
            "jar-invalid-group-" + group + "-" + property + "-" + value,
            "--management.endpoint.health.group." + group + ".include=*",
            "--management.endpoint.health.group." + group + "." + property + "=" + value)) {
      assertTrue(
          app.process.waitFor(25, TimeUnit.SECONDS), "Health group disclosure must reject startup");
      assertNotEquals(0, app.process.exitValue());
    }
  }

  @Test
  void healthAdditionalPathsCannotExposeManagementOnBusinessPort() throws Exception {
    for (var item :
        Map.of(
                "probe-aliases",
                    new String[] {"--management.endpoint.health.probes.add-additional-paths=true"},
                "custom-alias",
                    new String[] {
                      "--management.endpoint.health.group.custom.include=ping",
                      "--management.endpoint.health.group.custom.additional-path=server:/custom-health"
                    })
            .entrySet()) {
      try (var app = new Running("jar-invalid-" + item.getKey(), item.getValue())) {
        assertTrue(
            app.process.waitFor(12, TimeUnit.SECONDS),
            "Management aliases must reject startup: " + item.getKey());
        assertNotEquals(0, app.process.exitValue());
      }
    }
  }

  @Test
  void defaultArtifactHasNoActiveBusinessRoutes() throws Exception {
    try (var app = new Running("jar-default")) {
      app.ready();
      for (String path :
          List.of(
              "/api/iam",
              "/api/audit",
              "/api/system",
              "/api/qbc",
              "/actuator/health",
              "/livez",
              "/readyz",
              "/custom-health",
              "/internal/x")) assertEquals(404, get(app.port, path).statusCode());
      assertEquals(200, get(app.management, "/actuator/health/liveness").statusCode());
      assertEquals(200, get(app.management, "/actuator/health/readiness").statusCode());
    }
  }

  @Test
  void artifactRoutesAndFailsSafely() throws Exception {
    try (var backend = new ProtocolBackend();
        var app =
            new Running(
                "jar-routes",
                "--vireon.gateway.routes.iam=" + backend.uri(),
                "--vireon.gateway.response-timeout=400ms")) {
      app.ready();
      String marker = "protocol-" + UUID.randomUUID();
      try (HttpClient client = HttpClient.newHttpClient()) {
        var observed =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + app.port + "/api/iam/" + marker))
                    .header("Authorization", "Bearer " + marker)
                    .header("Cookie", "sample=" + marker)
                    .header("Origin", "https://" + marker + ".example")
                    .header("X-XSRF-TOKEN", marker)
                    .POST(HttpRequest.BodyPublishers.ofString(marker))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, observed.statusCode());
        assertTrue(observed.body().contains(marker));
      }
      assertEquals(201, get(app.port, "/api/iam/echo").statusCode());
      var malformed = get(app.port, "/api/iam/malformed");
      assertEquals(502, malformed.statusCode());
      assertEquals("{\"code\":\"BAD_GATEWAY\"}", malformed.body());
      assertEquals(504, get(app.port, "/api/iam/slow").statusCode());
      backend.server.close();
      var failure = get(app.port, "/api/iam/echo");
      assertEquals(502, failure.statusCode());
      assertEquals("{\"code\":\"BAD_GATEWAY\"}", failure.body());
      assertFalse(Files.readString(app.log).contains("synthetic"));
      assertFalse(
          Files.readString(app.log).contains(marker), "Protocol values leaked into runtime log");
    }
  }

  @Test
  void invalidAndSecondAuthorityConfigurationsFailClosed() throws Exception {
    Map<String, String[]> cases = new LinkedHashMap<>();
    cases.put("unavailable", new String[] {"--vireon.gateway.routes.qbc=http://127.0.0.1:1"});
    cases.put(
        "secret-uri",
        new String[] {"--vireon.gateway.routes.iam=http://user:startup-secret-marker@127.0.0.1:1"});
    cases.put(
        "second-authority",
        new String[] {
          "--spring.cloud.gateway.server.webflux.routes[0].id=rogue",
          "--spring.cloud.gateway.server.webflux.routes[0].uri=http://127.0.0.1:1",
          "--spring.cloud.gateway.server.webflux.routes[0].predicates[0]=Path=/**"
        });
    cases.put(
        "forwarded",
        new String[] {"--spring.cloud.gateway.server.webflux.x-forwarded.enabled=true"});
    cases.put("management", new String[] {"--management.server.port=-1"});
    for (var item : cases.entrySet()) {
      try (var app = new Running("jar-invalid-" + item.getKey(), item.getValue())) {
        assertTrue(
            app.process.waitFor(25, TimeUnit.SECONDS),
            "Invalid config must fail startup: " + item.getKey());
        assertNotEquals(0, app.process.exitValue());
        assertFalse(
            Files.readString(app.log).contains("startup-secret-marker"),
            "Startup diagnostic leaked rejected value");
      }
    }
  }
}
