// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:JvmName("DomTestFixtures")

package com.intellij.util.xml.impl

import com.intellij.openapi.application.EDT
import com.intellij.openapi.module.Module
import com.intellij.openapi.util.Disposer
import com.intellij.pom.java.LanguageLevel
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.testFramework.junit5.fixture.testFixture
import com.intellij.testFramework.setUpJdk
import com.intellij.workspaceModel.ide.legacyBridge.impl.java.JAVA_MODULE_ENTITY_TYPE_ID_NAME
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly

/**
 * Creates an opened project with one Java module and a mock JDK 1.7.
 * Declare it in a static field to share one project between the tests of a class.
 */
@TestOnly
fun domModuleFixture(): TestFixture<Module> = testFixture("domModuleFixture") {
  val pathFixture = tempPathFixture()
  val projectFixture = projectFixture(pathFixture, openAfterCreation = true)
  val module = projectFixture.moduleFixture(pathFixture, addPathToSourceRoot = true, moduleTypeId = JAVA_MODULE_ENTITY_TYPE_ID_NAME).init()
  val jdkDisposable = Disposer.newDisposable("domModuleFixture JDK")
  withContext(Dispatchers.EDT) {
    setUpJdk(LanguageLevel.JDK_1_7, module.project, module, jdkDisposable)
  }
  initialized(module) {
    withContext(Dispatchers.EDT) {
      Disposer.dispose(jdkDisposable)
    }
  }
}

/**
 * Creates a [DomTestFixture] for the module of [moduleFixture].
 * Declare it in an instance field, so that each test gets a new event record and a new disposable.
 */
@TestOnly
fun domTestFixture(moduleFixture: TestFixture<Module>): TestFixture<DomTestFixture> = testFixture("domTestFixture") { context ->
  val module = moduleFixture.init()
  val disposable = Disposer.newCheckedDisposable(context.uniqueId)
  val fixture = withContext(Dispatchers.EDT) {
    DomTestFixture(module, disposable)
  }
  initialized(fixture) {
    withContext(Dispatchers.EDT) {
      Disposer.dispose(disposable)
    }
  }
}
