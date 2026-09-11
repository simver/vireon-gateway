package com.simxe.vireon.gateway;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
class EdgeBoundary implements WebFilter, Ordered {
  private final Environment environment;

  EdgeBoundary(Environment environment) {
    this.environment = environment;
  }

  private static final Set<String> SECURITY =
      Set.of(
          "authorization",
          "cookie",
          "origin",
          "x-xsrf-token",
          "x-csrf-token",
          "host",
          "content-length",
          "transfer-encoding");
  private static final Set<String> IDENTITY =
      Set.of(
          "x-real-ip",
          "x-user-id",
          "x-account-id",
          "x-roles",
          "x-permissions",
          "x-authenticated-user");

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String path = exchange.getRequest().getURI().getRawPath();
    var local = exchange.getRequest().getLocalAddress();
    int managementPort = environment.getProperty("local.management.port", Integer.class, -1);
    if (local != null && local.getPort() == managementPort) {
      if (!Set.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")
          .contains(path)) {
        return GatewayErrors.write(exchange, 404, "NOT_FOUND");
      }
    } else if (path.equals("/actuator") || path.startsWith("/actuator/")) {
      return GatewayErrors.write(exchange, 404, "NOT_FOUND");
    }
    String lower = path.toLowerCase(Locale.ROOT);
    if (path.contains("\\")
        || path.contains(";")
        || path.contains("//")
        || path.matches(".*(?:^|/)\\.\\.?(?:/|$).*")
        || lower.matches(".*%(?:2f|5c|2e|3b|25).*")) {
      return GatewayErrors.write(exchange, 400, "INVALID_REQUEST");
    }
    var nominated = new ArrayList<String>();
    for (String connection :
        exchange.getRequest().getHeaders().getOrEmpty(HttpHeaders.CONNECTION)) {
      for (String token : connection.split(",")) {
        String name = token.trim().toLowerCase(Locale.ROOT);
        if (SECURITY.contains(name)) return GatewayErrors.write(exchange, 400, "INVALID_REQUEST");
        nominated.add(name);
      }
    }
    var request =
        exchange
            .getRequest()
            .mutate()
            .headers(
                headers -> {
                  for (String name : new ArrayList<>(headers.headerNames())) {
                    String normalized = name.toLowerCase(Locale.ROOT);
                    if (normalized.equals("forwarded")
                        || normalized.startsWith("x-forwarded-")
                        || IDENTITY.contains(normalized)
                        || nominated.contains(normalized)) headers.remove(name);
                  }
                })
            .build();
    return chain.filter(exchange.mutate().request(request).build());
  }
}
