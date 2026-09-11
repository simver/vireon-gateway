package com.simxe.vireon.gateway;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vireon.gateway", ignoreUnknownFields = false)
public record GatewaySettings(
    Map<String, URI> routes, Integer connectTimeoutMillis, Duration responseTimeout) {
  public GatewaySettings {
    routes = routes == null ? Map.of() : Map.copyOf(routes);
    connectTimeoutMillis = connectTimeoutMillis == null ? 2000 : connectTimeoutMillis;
    responseTimeout = responseTimeout == null ? Duration.ofSeconds(10) : responseTimeout;
    if (connectTimeoutMillis < 1
        || connectTimeoutMillis > 60000
        || responseTimeout.toMillis() < 1
        || responseTimeout.toMillis() > 120000) {
      throw new IllegalArgumentException("INVALID_GATEWAY_TIMEOUT");
    }
    for (var entry : routes.entrySet()) {
      URI uri = entry.getValue();
      if (!Set.of("iam", "audit").contains(entry.getKey())
          || !Set.of("http", "https").contains(uri.getScheme())
          || uri.getHost() == null
          || uri.getRawUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || !uri.getRawPath().isEmpty()
          || uri.getPort() == 0
          || uri.getPort() > 65535) {
        throw new IllegalArgumentException("INVALID_GATEWAY_ROUTE");
      }
    }
  }
}
