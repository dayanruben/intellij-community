// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.daemon.impl

import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.observation.ActivityKey
import com.intellij.platform.backend.observation.ActivityTracker
import com.intellij.platform.backend.observation.trackActivity
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.annotations.Nls
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * JUnit5 counterpart of [WaitForActivityTrackersInJUnit4HighlightingTest].
 *
 * Verifies that [CodeInsightTestFixtureImpl.instantiateAndRun] waits for activity trackers
 * when invoked through the JUnit5 [codeInsightFixture] wrapper.
 */
@TestApplication
class WaitForActivityTrackersHighlightingTest {
  companion object {
    private val tempDirFixture = tempPathFixture()
    private val projectFixture = projectFixture(tempDirFixture, openAfterCreation = true)

    @Suppress("unused")
    private val moduleFixture = projectFixture.moduleFixture(tempDirFixture, addPathToSourceRoot = true)
  }

  private val codeInsightFixture by codeInsightFixture(projectFixture, tempDirFixture)

  private object TestActivityKey : ActivityKey {
    override val presentableName: @Nls String get() = "Test background activity"
  }

  @Test
  fun `doHighlighting waits for activity keys`() = timeoutRunBlocking(timeout = 30.seconds) {
    val project = projectFixture.get()
    codeInsightFixture.configureByText("test.txt", "hello")

    val activityStartedLatch = CountDownLatch(1)
    val activityCompleted = AtomicBoolean(false)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    try {
      scope.launch {
        project.trackActivity(TestActivityKey) {
          activityStartedLatch.countDown()
          delay(5.seconds)
          activityCompleted.set(true)
        }
      }

      activityStartedLatch.await()
      codeInsightFixture.doHighlighting()

      assertTrue(activityCompleted.get(), "Activity tracker should have completed before highlighting")
    }
    finally {
      scope.cancel()
    }
  }

  @Test
  fun `doHighlighting waits for activity tracker EP`(@TestDisposable disposable: Disposable) = timeoutRunBlocking(timeout = 30.seconds) {
    codeInsightFixture.configureByText("test.txt", "hello")

    val completion = CompletableDeferred<Unit>()

    val tracker = object : ActivityTracker {
      override val presentableName: String = "Test EP tracker"
      override suspend fun isInProgress(project: Project): Boolean = !completion.isCompleted
      override suspend fun awaitConfiguration(project: Project) = completion.await()
    }

    ExtensionTestUtil.maskExtensions(
      ExtensionPointName.create<ActivityTracker>("com.intellij.activityTracker"),
      listOf(tracker),
      disposable,
    )

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      scope.launch {
        delay(5.seconds)
        completion.complete(Unit)
      }

      codeInsightFixture.doHighlighting()

      assertTrue(completion.isCompleted, "ActivityTracker EP should have completed before highlighting")
    }
    finally {
      scope.cancel()
    }
  }

  @Test
  fun `doHighlighting skips activity key when mustWaitForSmartMode is false`(@TestDisposable disposable: Disposable): Unit = timeoutRunBlocking(timeout = 30.seconds) {
    val project = projectFixture.get()
    codeInsightFixture.configureByText("test.txt", "hello")

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    try {
      scope.launch {
        project.trackActivity(TestActivityKey) {
          awaitCancellation()
        }
      }

      CodeInsightTestFixtureImpl.mustWaitForSmartMode(false, disposable)
      codeInsightFixture.doHighlighting()
    }
    finally {
      scope.cancel()
    }
  }

  @Test
  fun `doHighlighting skips activity tracker EP when mustWaitForSmartMode is false`(@TestDisposable disposable: Disposable): Unit = timeoutRunBlocking(timeout = 30.seconds) {
    codeInsightFixture.configureByText("test.txt", "hello")

    val tracker = object : ActivityTracker {
      override val presentableName: String = "Test EP tracker (never completes)"
      override suspend fun isInProgress(project: Project): Boolean = true
      override suspend fun awaitConfiguration(project: Project) = awaitCancellation()
    }

    ExtensionTestUtil.maskExtensions(
      ExtensionPointName.create<ActivityTracker>("com.intellij.activityTracker"),
      listOf(tracker),
      disposable,
    )

    CodeInsightTestFixtureImpl.mustWaitForSmartMode(false, disposable)
    codeInsightFixture.doHighlighting()
  }
}
