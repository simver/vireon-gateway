package com.simxe.vireon.gateway;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Raw HTTP fixture: preserve repeated header evidence without client/server framework
 * normalization.
 */
final class ProtocolBackend implements AutoCloseable {
  final ServerSocket server;
  final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  final BlockingQueue<String> requests = new LinkedBlockingQueue<>();
  final AtomicInteger count = new AtomicInteger();
  final CompletableFuture<Boolean> cancelled = new CompletableFuture<>();
  final List<Socket> sockets = new CopyOnWriteArrayList<>();

  ProtocolBackend() throws IOException {
    server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
    executor.submit(
        () -> {
          while (!server.isClosed()) {
            try {
              Socket socket = server.accept();
              sockets.add(socket);
              executor.submit(() -> serve(socket));
            } catch (IOException stopped) {
              if (!server.isClosed()) throw new UncheckedIOException(stopped);
            }
          }
        });
  }

  String uri() {
    return "http://127.0.0.1:" + server.getLocalPort();
  }

  void serve(Socket socket) {
    try (socket) {
      socket.setSoTimeout(10000);
      InputStream reader = new BufferedInputStream(socket.getInputStream());
      String line = readLine(reader);
      if (line == null) return;
      StringBuilder request = new StringBuilder(line).append("\r\n");
      int length = 0;
      boolean chunked = false;
      String header;
      while ((header = readLine(reader)) != null && !header.isEmpty()) {
        request.append(header).append("\r\n");
        if (header.toLowerCase().startsWith("content-length:"))
          length = Integer.parseInt(header.substring(15).trim());
        if (header.equalsIgnoreCase("Transfer-Encoding: chunked")) chunked = true;
      }
      request.append("\r\n");
      ByteArrayOutputStream body = new ByteArrayOutputStream();
      if (chunked) {
        int chunk;
        while ((chunk = Integer.parseInt(readLine(reader).split(";", 2)[0], 16)) > 0) {
          body.write(reader.readNBytes(chunk));
          if (!readLine(reader).isEmpty()) throw new IOException("Invalid fixture chunk");
        }
        while (!(header = readLine(reader)).isEmpty()) {}
      } else {
        body.write(reader.readNBytes(length));
      }
      byte[] head = request.toString().getBytes(StandardCharsets.ISO_8859_1);
      request.append(body.toString(StandardCharsets.UTF_8));
      requests.add(request.toString());
      count.incrementAndGet();
      if (line.contains("/reset")) {
        socket.setSoLinger(true, 0);
        return;
      }
      if (line.contains("/slow")) {
        Thread.sleep(1800);
      }
      var output = socket.getOutputStream();
      if (line.contains("/malformed")) {
        output.write(
            "HTTP/1.1 XYZ Broken\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII));
        output.flush();
        return;
      }
      if (line.contains("/cancel")) {
        output.write(
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n4\r\npart\r\n"
                .getBytes(StandardCharsets.US_ASCII));
        output.flush();
        executor.submit(
            () -> {
              try {
                while (!socket.isClosed()) {
                  Thread.sleep(50);
                  output.write("1\r\nx\r\n".getBytes(StandardCharsets.US_ASCII));
                  output.flush();
                }
              } catch (IOException disconnected) {
                // The fixture reader observes cancellation; heartbeat cannot complete the
                // assertion.
              } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
              }
            });
        cancelled.complete(reader.read() == -1);
        return;
      }
      if (line.contains("/stream")) {
        output.write(
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n4\r\npart\r\n"
                .getBytes(StandardCharsets.US_ASCII));
        output.flush();
        Thread.sleep(1800);
        return;
      }
      int status =
          line.contains("/unauthorized")
              ? 401
              : line.contains("/forbidden")
                  ? 403
                  : line.contains("/unavailable") ? 503 : line.contains("/redirect") ? 302 : 201;
      ByteArrayOutputStream echoed = new ByteArrayOutputStream();
      echoed.write(head);
      echoed.write(body.toByteArray());
      byte[] response = echoed.toByteArray();
      String headers =
          "HTTP/1.1 "
              + status
              + " Fixture\r\nContent-Type: text/plain\r\nContent-Length: "
              + response.length
              + "\r\nSet-Cookie: sample=; Path=/api; Max-Age=0; Secure; HttpOnly; SameSite=Strict"
              + "\r\nSet-Cookie: sample=synthetic; Path=/api; Secure; HttpOnly; SameSite=Strict"
              + "\r\nSet-Cookie: csrf=synthetic; Path=/api/iam; SameSite=Strict"
              + "\r\nLocation: /api/iam/echo\r\nWWW-Authenticate: Bearer\r\nX-Hop-Response: remove-me\r\nConnection: close, X-Hop-Response\r\n\r\n";
      output.write(headers.getBytes(StandardCharsets.US_ASCII));
      output.write(response);
      output.flush();
    } catch (IOException ignored) {
      // Client cancellation and connection reset are intentional fixture scenarios.
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
    } finally {
      sockets.remove(socket);
    }
  }

  static String readLine(InputStream input) throws IOException {
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    int value;
    while ((value = input.read()) != -1) {
      if (value == '\n') {
        byte[] bytes = line.toByteArray();
        int count =
            bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
        return new String(bytes, 0, count, StandardCharsets.ISO_8859_1);
      }
      line.write(value);
      if (line.size() > 65536) throw new IOException("Fixture header limit");
    }
    return line.size() == 0 ? null : line.toString(StandardCharsets.ISO_8859_1);
  }

  @Override
  public void close() throws IOException {
    server.close();
    for (Socket socket : sockets) socket.close();
    executor.shutdownNow();
  }
}
