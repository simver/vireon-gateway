package com.simxe.vireon.gateway;

import org.springframework.boot.actuate.endpoint.Show;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointProperties;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
class GatewayRouting {
  GatewayRouting(Environment env, GatewayProperties nativeRoutes, HealthEndpointProperties health) {
    int business = env.getProperty("server.port", Integer.class, 18080);
    int management = env.getProperty("management.server.port", Integer.class, -1);
    if (!nativeRoutes.getRoutes().isEmpty()
        || !nativeRoutes.getDefaultFilters().isEmpty()
        || env.getProperty(
            "spring.cloud.gateway.server.webflux.discovery.locator.enabled", Boolean.class, false)
        || env.getProperty(
            "spring.cloud.gateway.server.webflux.forwarded.enabled", Boolean.class, false)
        || env.getProperty(
            "spring.cloud.gateway.server.webflux.x-forwarded.enabled", Boolean.class, false)
        || env.containsProperty("spring.cloud.gateway.server.webflux.trusted-proxies")
        || !"none".equals(env.getProperty("server.forward-headers-strategy", "none"))) {
      throw new IllegalArgumentException("SECOND_ROUTE_AUTHORITY_FORBIDDEN");
    }
    if (management < 0
        || env.getProperty(
            "management.endpoint.health.probes.add-additional-paths", Boolean.class, false)
        || health.getGroup().values().stream()
            .anyMatch(
                group ->
                    group.getAdditionalPath() != null
                        || group.getShowDetails() != null && group.getShowDetails() != Show.NEVER
                        || group.getShowComponents() != null
                            && group.getShowComponents() != Show.NEVER)
        || business == management && business != 0
        || !"127.0.0.1".equals(env.getProperty("management.server.address"))
        || !"health".equals(env.getProperty("management.endpoints.web.exposure.include"))
        || !"never".equals(env.getProperty("management.endpoint.health.show-details"))
        || !"never".equals(env.getProperty("management.endpoint.health.show-components"))) {
      throw new IllegalArgumentException("INVALID_MANAGEMENT_ISOLATION");
    }
  }

  @Bean
  RouteLocator staticRoutes(RouteLocatorBuilder builder, GatewaySettings settings) {
    var routes = builder.routes();
    settings
        .routes()
        .forEach(
            (id, uri) -> {
              String prefix = "/api/" + id;
              routes.route(
                  id,
                  route ->
                      route
                          .predicate(
                              exchange -> {
                                String path = exchange.getRequest().getURI().getRawPath();
                                return path.equals(prefix) || path.startsWith(prefix + "/");
                              })
                          .metadata("response-timeout", settings.responseTimeout().toMillis())
                          .metadata("connect-timeout", settings.connectTimeoutMillis())
                          .uri(uri));
            });
    return routes.build();
  }

  @Bean
  HttpClientCustomizer boundedClient(GatewaySettings settings) {
    return client ->
        client
            .disableRetry(true)
            .followRedirect(false)
            .doOnConnected(
                connection ->
                    connection.addHandlerLast(
                        "upstreamProtocolErrors", new UpstreamProtocolErrors()))
            .option(
                io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS,
                settings.connectTimeoutMillis())
            .responseTimeout(settings.responseTimeout());
  }
}
