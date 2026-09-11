package com.simxe.vireon.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Starts the packaged artifact, never a sibling project or test-classpath application. */
class GatewayJarIT {
  static final Path JAR = Path.of("target/vireon-gateway-0.1.0-SNAPSHOT.jar").toAbsolutePath();
  static String testedJarHash;

  static String jarHash() throws Exception {
    return HexFormat.of()
        .withUpperCase()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(JAR)));
  }

  @BeforeAll
  static void identifyTestedArtifact() throws Exception {
    testedJarHash = jarHash();
  }

  @AfterAll
  static void recordTestedArtifact() throws Exception {
    assertEquals(testedJarHash, jarHash(), "Application artifact changed during JAR tests");
    Path evidence =
        Path.of(System.getProperty("gateway.verificationDirectory", "target"), "failsafe-reports");
    Files.createDirectories(evidence);
    Files.writeString(evidence.resolve("tested-artifact.sha256"), testedJarHash);
  }

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
      this(name, availablePort(), availablePort(), null, args);
    }

    Running(String name, int port, int management, Path agent, String... args) throws Exception {
      this.port = port;
      this.management = management;
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
      if (agent != null) command.add(1, "-javaagent:" + agent.toAbsolutePath());
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

  static void assertBindable(int port) throws IOException {
    try (var socket = new ServerSocket()) {
      socket.setReuseAddress(true);
      socket.bind(new InetSocketAddress("127.0.0.1", port));
    }
  }

  @ParameterizedTest
  @CsvSource({"business", "management"})
  void occupiedPortsFailWithoutDisturbingOwnerOrLeakingProcess(String kind) throws Exception {
    try (var owner = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
      int occupied = owner.getLocalPort();
      int free = availablePort();
      try (var app =
          new Running(
              "jar-port-conflict-" + kind,
              kind.equals("business") ? occupied : free,
              kind.equals("management") ? occupied : free,
              null)) {
        var descendants = app.process.descendants().toList();
        assertTrue(
            app.process.waitFor(30, TimeUnit.SECONDS), "Port conflict must terminate startup");
        assertNotEquals(0, app.process.exitValue());
        assertFalse(app.process.isAlive());
        assertTrue(descendants.stream().noneMatch(ProcessHandle::isAlive));
        assertBindable(free);
        assertFalse(owner.isClosed());
        try (var contender = new ServerSocket()) {
          assertThrows(
              BindException.class,
              () -> contender.bind(new InetSocketAddress("127.0.0.1", occupied)));
        }
        owner.setSoTimeout(1000);
        try (var probe = new Socket("127.0.0.1", occupied);
            var accepted = owner.accept()) {
          assertTrue(accepted.isConnected(), "Original port owner must still accept connections");
        }
      }
    }
  }

  static Path shutdownAgent() throws IOException {
    Path path = Path.of("target", "shutdown-test-agent.jar");
    var manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().putValue("Premain-Class", ShutdownTestAgent.class.getName());
    String resource = ShutdownTestAgent.class.getName().replace('.', '/') + ".class";
    try (var jar = new JarOutputStream(Files.newOutputStream(path), manifest);
        var bytes = ShutdownTestAgent.class.getClassLoader().getResourceAsStream(resource)) {
      assertNotNull(bytes);
      jar.putNextEntry(new JarEntry(resource));
      bytes.transferTo(jar);
      jar.closeEntry();
    }
    return path;
  }

  static void awaitLog(Running app, String expected) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
    while (System.nanoTime() < deadline) {
      if (Files.readString(app.log).contains(expected)) return;
      assertTrue(app.process.isAlive(), "Exited before " + expected + "; see " + app.log);
      Thread.sleep(50);
    }
    fail("Missing " + expected + "; see " + app.log);
  }

  @ParameterizedTest
  @CsvSource({"completes, 8s", "expires, 1s"})
  void jvmShutdownDrainsOrExpiresInflightRequestAndReleasesPorts(String outcome, String grace)
      throws Exception {
    boolean completes = outcome.equals("completes");
    var received = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var backend = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
      var backendWork =
          CompletableFuture.runAsync(
              () -> {
                try (var connection = backend.accept()) {
                  var reader =
                      new BufferedReader(
                          new InputStreamReader(
                              connection.getInputStream(), StandardCharsets.US_ASCII));
                  String line;
                  while ((line = reader.readLine()) != null && !line.isEmpty()) {}
                  received.countDown();
                  if (!release.await(40, TimeUnit.SECONDS))
                    throw new IOException("Backend release timed out");
                  if (completes) {
                    connection
                        .getOutputStream()
                        .write(
                            "HTTP/1.1 200 OK\r\nContent-Length: 7\r\nConnection: close\r\n\r\ndrained"
                                .getBytes(StandardCharsets.US_ASCII));
                    connection.getOutputStream().flush();
                  }
                } catch (Exception e) {
                  throw new CompletionException(e);
                }
              });
      try (var app =
              new Running(
                  "jar-shutdown-" + outcome,
                  availablePort(),
                  availablePort(),
                  shutdownAgent(),
                  "--vireon.gateway.routes.iam=http://127.0.0.1:" + backend.getLocalPort(),
                  "--vireon.gateway.response-timeout=30s",
                  "--logging.level.org.springframework.boot.reactor.netty.GracefulShutdown=INFO",
                  "--logging.level.org.springframework.context.support.DefaultLifecycleProcessor=INFO",
                  "--spring.lifecycle.timeout-per-shutdown-phase=" + grace);
          var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
        app.ready();
        var response =
            client.sendAsync(
                HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + app.port + "/api/iam/inflight"))
                    .timeout(Duration.ofSeconds(20))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(
            received.await(5, TimeUnit.SECONDS), "Request must reach upstream before shutdown");
        var descendants = app.process.descendants().toList();
        app.process.getOutputStream().write("shutdown\n".getBytes(StandardCharsets.UTF_8));
        app.process.getOutputStream().flush();
        awaitLog(app, "Commencing graceful shutdown");
        assertThrows(
            ConnectException.class,
            () -> get(app.port, "/api/iam/new"),
            "Shutdown must refuse new requests");
        if (completes) {
          assertTrue(app.process.isAlive(), "In-flight request must hold graceful shutdown open");
          assertFalse(response.isDone());
          release.countDown();
          var drained = response.get(5, TimeUnit.SECONDS);
          assertEquals(200, drained.statusCode());
          assertEquals("drained", drained.body());
        }
        assertTrue(
            app.process.waitFor(15, TimeUnit.SECONDS),
            "JVM shutdown must be bounded; see " + app.log);
        assertEquals(0, app.process.exitValue());
        if (!completes) {
          var failure =
              assertThrows(ExecutionException.class, () -> response.get(2, TimeUnit.SECONDS));
          assertInstanceOf(IOException.class, failure.getCause());
          assertTrue(
              Files.readString(app.log).contains("after timeout of 1000ms"),
              "Lifecycle timeout must expire the request");
        }
        assertTrue(descendants.stream().noneMatch(ProcessHandle::isAlive));
        assertBindable(app.port);
        assertBindable(app.management);
      } finally {
        release.countDown();
        backend.close();
        backendWork.get(5, TimeUnit.SECONDS);
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
