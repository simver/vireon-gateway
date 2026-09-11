package com.simxe.vireon.gateway;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

@Component
@Order(-2)
class GatewayErrors implements WebExceptionHandler {
  @Override
  public Mono<Void> handle(ServerWebExchange exchange, Throwable failure) {
    if (exchange.getResponse().isCommitted()) return Mono.error(failure);
    int status = 500;
    if (failure instanceof ResponseStatusException response)
      status = response.getStatusCode().value();
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof TimeoutException
          || cause instanceof io.netty.handler.timeout.ReadTimeoutException
          || cause instanceof io.netty.channel.ConnectTimeoutException) {
        status = 504;
        break;
      }
      if (cause instanceof java.io.IOException
          || cause instanceof reactor.netty.http.client.PrematureCloseException) status = 502;
    }
    String code =
        switch (status) {
          case 400 -> "INVALID_REQUEST";
          case 404 -> "NOT_FOUND";
          case 502 -> "BAD_GATEWAY";
          case 504 -> "GATEWAY_TIMEOUT";
          default -> "INTERNAL_ERROR";
        };
    return write(exchange, status, code);
  }

  static Mono<Void> write(ServerWebExchange exchange, int status, String code) {
    var response = exchange.getResponse();
    response.setRawStatusCode(status);
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    response.getHeaders().setCacheControl("no-store");
    byte[] bytes = ("{\"code\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
    return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
  }
}
