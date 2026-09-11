package com.simxe.vireon.gateway;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.HttpObject;
import java.io.IOException;

/** Marks decoder failures only on the downstream HTTP client connection. */
final class UpstreamProtocolErrors extends ChannelInboundHandlerAdapter {
  @Override
  public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
    if (message instanceof HttpObject http && http.decoderResult().isFailure()) {
      http.setDecoderResult(
          DecoderResult.failure(
              new IOException("INVALID_UPSTREAM_HTTP_RESPONSE", http.decoderResult().cause())));
    }
    context.fireChannelRead(message);
  }
}
