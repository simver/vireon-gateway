package com.simxe.vireon.gateway;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GatewaySettingsTests {
  @Test
  void noRoutesByDefault() {
    assertTrue(new GatewaySettings(null, null, null).routes().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://localhost/path",
        "http://user:password@localhost",
        "http://localhost?x=1",
        "http://localhost#x",
        "ftp://localhost",
        "http://localhost:0",
        "http://localhost:65536"
      })
  void rejectInvalidOrigins(String uri) {
    assertThrows(
        IllegalArgumentException.class,
        () -> new GatewaySettings(Map.of("iam", URI.create(uri)), null, null));
  }

  @ParameterizedTest
  @ValueSource(strings = {"system", "qbc", "iamx", "../iam"})
  void rejectUnavailableRoutes(String id) {
    assertThrows(
        IllegalArgumentException.class,
        () -> new GatewaySettings(Map.of(id, URI.create("http://127.0.0.1:1234")), null, null));
  }

  @Test
  void boundTimeouts() {
    assertThrows(IllegalArgumentException.class, () -> new GatewaySettings(null, 0, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new GatewaySettings(null, null, Duration.ofSeconds(-1)));
    assertThrows(IllegalArgumentException.class, () -> new GatewaySettings(null, 60001, null));
  }
}
