package com.simxe.vireon.gateway;

import org.springframework.boot.SpringBootExceptionReporter;
import org.springframework.context.ConfigurableApplicationContext;

/** Binding failures can contain rejected credential-bearing URI values. */
public final class SafeStartupReporter implements SpringBootExceptionReporter {
  public SafeStartupReporter(ConfigurableApplicationContext context) {}

  @Override
  public boolean reportException(Throwable failure) {
    System.err.println("GATEWAY_STARTUP_FAILED: check configuration and dependency compatibility");
    return true;
  }
}
