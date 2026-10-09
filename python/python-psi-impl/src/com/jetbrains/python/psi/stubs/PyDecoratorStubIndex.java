// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.psi.stubs;

import com.intellij.openapi.project.Project;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.ProjectScope;
import com.intellij.psi.stubs.StringStubIndexExtension;
import com.intellij.psi.stubs.StubIndex;
import com.intellij.psi.stubs.StubIndexKey;
import com.jetbrains.python.psi.PyDecorator;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * Python Decorator stub index.
 * Decorators are indexed by name
 * @author Ilya.Kazakevich
 */
public final class PyDecoratorStubIndex extends StringStubIndexExtension<PyDecorator> {
  /**
   * Key to search for python decorators
   */
  public static final StubIndexKey<String, PyDecorator> KEY = StubIndexKey.createIndexKey("Python.Decorator");

  public static Collection<PyDecorator> find(@NotNull String name, @NotNull Project project) {
    return find(name, project, ProjectScope.getAllScope(project));
  }

  public static Collection<PyDecorator> find(@NotNull String name, @NotNull Project project, @NotNull GlobalSearchScope scope) {
    return StubIndex.getElements(KEY, name, project, scope, PyDecorator.class);
  }

  @Override
  public @NotNull StubIndexKey<String, PyDecorator> getKey() {
    return KEY;
  }
}
