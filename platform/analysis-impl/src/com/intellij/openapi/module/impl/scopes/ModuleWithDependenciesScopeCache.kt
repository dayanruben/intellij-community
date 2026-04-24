// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.module.impl.scopes

import com.intellij.concurrency.ConcurrentCollectionFactory
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.ThrottledLogger
import com.intellij.openapi.module.Module
import com.intellij.openapi.util.Disposer
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.intellij.util.containers.ConcurrentIntObjectMap
import org.jetbrains.annotations.ApiStatus
import java.util.concurrent.ConcurrentHashMap

@ApiStatus.Internal
@Service(Level.PROJECT)
internal class ModuleWithDependenciesScopeCache {
  private val throttledLogger = ThrottledLogger(Logger.getInstance(ModuleScopeProviderImpl::class.java), 1000)
  private val cachedScopes: ConcurrentHashMap<Module, ConcurrentIntObjectMap<GlobalSearchScope>> = ConcurrentHashMap()

  @RequiresReadLock
  fun getCachedScope(module: Module, @ScopeConstant options: Int): GlobalSearchScope {
    ThreadingAssertions.assertReadAccess()
    if (cachedScopes[module] == null) {
      Disposer.register(module) { cachedScopes.remove(module) }
    }
    val modulesScope = cachedScopes.getOrPut(module) { ConcurrentCollectionFactory.createConcurrentIntObjectMap() }
    if (modulesScope[options] == null) {
      throttledLogger.debug("Creating scope for module " + module.getName() + " with options " + options, Throwable())
    }
    return modulesScope.computeIfAbsent(options) { ModuleWithDependenciesScope(module, options) }
  }

  fun clear() {
    cachedScopes.clear()
  }
}
