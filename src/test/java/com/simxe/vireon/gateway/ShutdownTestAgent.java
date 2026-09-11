package com.simxe.vireon.gateway;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Test-only JVM shutdown trigger; never packaged in the application artifact. */
public final class ShutdownTestAgent {
  public static void premain(String ignored) {
    Thread.ofPlatform()
        .daemon()
        .name("test-shutdown-trigger")
        .start(
            () -> {
              try {
                var input =
                    new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                if ("shutdown".equals(input.readLine())) System.exit(0);
              } catch (Exception failure) {
                throw new IllegalStateException("Test shutdown trigger failed", failure);
              }
            });
  }
}
