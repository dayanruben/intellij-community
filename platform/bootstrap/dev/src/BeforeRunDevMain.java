// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.bootstrap.dev;

import com.intellij.util.lang.PathClassLoader;
import org.jetbrains.annotations.ApiStatus;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Runs the main class {@code -Dintellij.build.dev.server.before.run.main.class} names, then the launcher that
 * {@code -Dintellij.build.dev.server.main.class} names: {@link PreBuiltDevMain} or the legacy {@code DevMainKt}.
 */
@ApiStatus.Internal
public final class BeforeRunDevMain {
  private static final String BEFORE_RUN_MAIN_CLASS_PROPERTY = "intellij.build.dev.server.before.run.main.class";
  private static final String MAIN_CLASS_PROPERTY = "intellij.build.dev.server.main.class";

  private BeforeRunDevMain() { }

  static void main(String[] rawArgs) throws Throwable {
    var beforeRunMainClass = System.getProperty(BEFORE_RUN_MAIN_CLASS_PROPERTY);
    if (beforeRunMainClass == null || beforeRunMainClass.isBlank()) {
      throw new IllegalStateException("System property '" + BEFORE_RUN_MAIN_CLASS_PROPERTY + "' is not set");
    }

    if (!(BeforeRunDevMain.class.getClassLoader() instanceof PathClassLoader classLoader)) {
      throw new IllegalStateException("The current class loader is not a PathClassLoader");
    }

    try (var tempClassLoader = new URLClassLoader(classLoader.getUrls().toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
      var mainClass = tempClassLoader.loadClass(beforeRunMainClass);
      MethodHandles.publicLookup()
        .findStatic(mainClass, "main", MethodType.methodType(void.class, String[].class))
        .invokeExact(rawArgs);
    }

    var mainClassName = System.getProperty(MAIN_CLASS_PROPERTY);
    if (mainClassName == null || mainClassName.isBlank()) {
      throw new IllegalStateException("System property '" + MAIN_CLASS_PROPERTY + "' is not set");
    }
    // the legacy DevMainKt declares a package-private main in another package
    var mainClass = classLoader.loadClass(mainClassName);
    MethodHandles.privateLookupIn(mainClass, MethodHandles.lookup())
      .findStatic(mainClass, "main", MethodType.methodType(void.class, String[].class))
      .invokeExact(rawArgs);
  }
}
