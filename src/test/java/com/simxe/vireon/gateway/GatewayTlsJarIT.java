package com.simxe.vireon.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real packaged Gateway, with a task CA and HTTPS on both sides. No trust bypass. */
class GatewayTlsJarIT {
  static Path directory;
  static String password;
  static SSLContext clientContext;

  static void keytool(String... args) throws Exception {
    var command = new ArrayList<String>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
    command.addAll(List.of(args));
    var builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.environment().put("GATEWAY_TLS_TEST_PASSWORD", password);
    var log = file("keytool-" + UUID.randomUUID() + ".log");
    var process = builder.redirectOutput(log.toFile()).start();
    try {
      assertTrue(process.waitFor(30, TimeUnit.SECONDS), "keytool timeout");
      assertEquals(0, process.exitValue(), "keytool failed");
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
      }
      assertFalse(
          new String(Files.readAllBytes(log), StandardCharsets.UTF_8).contains(password),
          "keytool exposed password");
    }
  }

  static Path file(String name) {
    return directory.resolve(name);
  }

  static void generate(String alias, boolean ca) throws Exception {
    keytool(
        "-genkeypair",
        "-alias",
        alias,
        "-keyalg",
        "RSA",
        "-keysize",
        "2048",
        "-dname",
        "CN=" + alias,
        "-validity",
        "7",
        "-ext",
        ca ? "BC=ca:true" : "BC=ca:false",
        "-keystore",
        file(alias + ".p12").toString(),
        "-storetype",
        "PKCS12",
        "-storepass:env",
        "GATEWAY_TLS_TEST_PASSWORD",
        "-noprompt");
  }

  @BeforeAll
  static void certificates() throws Exception {
    directory = Files.createTempDirectory(Path.of("target"), "gateway-tls-").toAbsolutePath();
    password = UUID.randomUUID().toString();
    for (String ca : List.of("trusted", "unknown")) {
      generate(ca, true);
      keytool(
          "-exportcert",
          "-rfc",
          "-alias",
          ca,
          "-keystore",
          file(ca + ".p12").toString(),
          "-storepass:env",
          "GATEWAY_TLS_TEST_PASSWORD",
          "-file",
          file(ca + ".pem").toString());
    }
    for (String name : List.of("valid", "untrusted", "wrong-san", "expired")) {
      generate(name, false);
      String issuer = name.equals("untrusted") ? "unknown" : "trusted";
      keytool(
          "-certreq",
          "-alias",
          name,
          "-keystore",
          file(name + ".p12").toString(),
          "-storepass:env",
          "GATEWAY_TLS_TEST_PASSWORD",
          "-file",
          file(name + ".csr").toString());
      keytool(
          "-gencert",
          "-alias",
          issuer,
          "-keystore",
          file(issuer + ".p12").toString(),
          "-storepass:env",
          "GATEWAY_TLS_TEST_PASSWORD",
          "-infile",
          file(name + ".csr").toString(),
          "-outfile",
          file(name + ".crt").toString(),
          "-ext",
          "BC=ca:false",
          "-ext",
          "EKU=serverAuth",
          "-ext",
          name.equals("wrong-san") ? "SAN=dns:wrong.invalid" : "SAN=ip:127.0.0.1,dns:localhost",
          "-startdate",
          name.equals("expired") ? "-3d" : "-1d",
          "-validity",
          "2");
      keytool(
          "-importcert",
          "-alias",
          issuer,
          "-file",
          file(issuer + ".pem").toString(),
          "-keystore",
          file(name + ".p12").toString(),
          "-storepass:env",
          "GATEWAY_TLS_TEST_PASSWORD",
          "-noprompt");
      keytool(
          "-importcert",
          "-alias",
          name,
          "-file",
          file(name + ".crt").toString(),
          "-keystore",
          file(name + ".p12").toString(),
          "-storepass:env",
          "GATEWAY_TLS_TEST_PASSWORD",
          "-noprompt");
    }
    KeyStore trust = KeyStore.getInstance("PKCS12");
    trust.load(null, null);
    try (var input = Files.newInputStream(file("trusted.pem"))) {
      trust.setCertificateEntry(
          "root",
          java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(input));
    }
    var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    managers.init(trust);
    clientContext = SSLContext.getInstance("TLS");
    clientContext.init(null, managers.getTrustManagers(), null);
  }

  static SSLContext serverContext(String name) throws Exception {
    var store = KeyStore.getInstance(file(name + ".p12").toFile(), password.toCharArray());
    var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keys.init(store, password.toCharArray());
    var context = SSLContext.getInstance("TLS");
    context.init(keys.getKeyManagers(), null, null);
    return context;
  }

  @ParameterizedTest
  @ValueSource(strings = {"valid", "untrusted", "wrong-san", "expired"})
  void validatesEachDownstreamCertificateAndPreservesSecureIngress(String certificate)
      throws Exception {
    var backend = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try {
      backend.setHttpsConfigurator(new HttpsConfigurator(serverContext(certificate)));
      backend.createContext(
          "/api/iam/probe",
          exchange -> {
            byte[] body = exchange.getRequestURI().toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var stream = exchange.getResponseBody()) {
              stream.write(body);
            }
          });
      backend.start();
      int gatewayPort = GatewayJarIT.availablePort(), managementPort = GatewayJarIT.availablePort();
      var builder =
          new ProcessBuilder(
              Path.of(System.getProperty("java.home"), "bin", "java").toString(),
              "-jar",
              GatewayJarIT.JAR.toString());
      var env = builder.environment();
      env.keySet()
          .removeIf(
              key ->
                  key.matches(
                      "(?:SPRING_|SERVER_|MANAGEMENT_|VIREON_).*|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS"));
      env.put("SERVER_PORT", Integer.toString(gatewayPort));
      env.put("MANAGEMENT_SERVER_PORT", Integer.toString(managementPort));
      env.put("MANAGEMENT_SERVER_SSL_ENABLED", "false");
      env.put("SERVER_SSL_ENABLED", "true");
      env.put("SERVER_SSL_KEY_STORE", file("valid.p12").toUri().toString());
      env.put("SERVER_SSL_KEY_STORE_TYPE", "PKCS12");
      env.put("SERVER_SSL_KEY_STORE_PASSWORD", password);
      env.put(
          "SPRING_APPLICATION_JSON",
          "{\"spring\":{\"cloud\":{\"gateway\":{\"server\":{\"webflux\":{\"httpclient\":{\"ssl\":{\"trusted-x509-certificates\":[\""
              + file("trusted.pem").toUri()
              + "\"]}}}}}}},\"vireon\":{\"gateway\":{\"routes\":{\"iam\":\"https://127.0.0.1:"
              + backend.getAddress().getPort()
              + "\"}}}}");
      Process process = null;
      var log = file(certificate + "-gateway.log");
      try {
        process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        boolean ready = false;
        while (System.nanoTime() < end && process.isAlive()) {
          try {
            if (GatewayJarIT.get(managementPort, "/actuator/health").statusCode() == 200) {
              ready = true;
              break;
            }
          } catch (IOException duringStartup) {
          }
          Thread.sleep(100);
        }
        assertTrue(ready, "TLS Gateway must start");
        try (var client =
            HttpClient.newBuilder()
                .sslContext(clientContext)
                .connectTimeout(Duration.ofSeconds(3))
                .build()) {
          var response =
              client.send(
                  HttpRequest.newBuilder(
                          URI.create(
                              "https://127.0.0.1:" + gatewayPort + "/api/iam/probe?value=retained"))
                      .timeout(Duration.ofSeconds(8))
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
          assertEquals(certificate.equals("valid") ? 200 : 502, response.statusCode());
          assertEquals(
              certificate.equals("valid")
                  ? "/api/iam/probe?value=retained"
                  : "{\"code\":\"BAD_GATEWAY\"}",
              response.body());
        }
      } finally {
        try {
          if (process != null) {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
              process.destroyForcibly();
              assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
          }
        } finally {
          if (Files.exists(log))
            assertFalse(
                Files.readString(log).contains(password), "TLS password must not enter logs");
        }
      }
      GatewayJarIT.assertBindable(gatewayPort);
      GatewayJarIT.assertBindable(managementPort);
    } finally {
      backend.stop(0);
    }
  }
}
