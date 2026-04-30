// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.debugger.impl.frontend.tests

import com.intellij.platform.debugger.impl.frontend.util.RequestsSerializer
import com.intellij.platform.util.coroutines.childScope
import com.intellij.testFramework.LoggedErrorProcessor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

internal class RequestsSerializerTest {
  @Test
  fun `scheduled requests are executed sequentially`() = runSerializerTest { serializer ->
    val events = CopyOnWriteArrayList<String>()
    val firstStarted = CompletableDeferred<Unit>()
    val releaseFirstRequest = CompletableDeferred<Unit>()
    val secondStarted = CompletableDeferred<Unit>()

    val firstRequest = serializer.scheduleRequest {
      events += "first-started"
      firstStarted.complete(Unit)
      releaseFirstRequest.await()
      events += "first-finished"
      1
    }
    val secondRequest = serializer.scheduleRequest {
      events += "second-started"
      secondStarted.complete(Unit)
      2
    }

    firstStarted.await()
    assertEquals(listOf("first-started"), events)
    assertFalse(secondStarted.isCompleted)

    releaseFirstRequest.complete(Unit)
    assertEquals(1, firstRequest.await())
    assertEquals(2, secondRequest.await())

    assertEquals(listOf("first-started", "first-finished", "second-started"), events)
  }

  @Test
  fun `failed scheduled request completes exceptionally and does not block the queue`() = runSerializerTest { serializer ->
    val events = CopyOnWriteArrayList<String>()
    val requestAfterFailureCompleted = CompletableDeferred<Unit>()

    val failingRequest = serializer.scheduleRequest<Int> {
      events += "failing"
      throw IllegalStateException("boom")
    }
    serializer.performRequest {
      events += "after-failure"
      requestAfterFailureCompleted.complete(Unit)
    }
    val successfulRequest = serializer.scheduleRequest {
      events += "successful"
      42
    }

    try {
      failingRequest.await()
      fail("Expected the scheduled request to fail")
    }
    catch (t: IllegalStateException) {
      assertEquals(IllegalStateException("boom").message, t.message)
    }

    requestAfterFailureCompleted.await()
    assertEquals(42, successfulRequest.await())
    assertEquals(listOf("failing", "after-failure", "successful"), events)
  }

  @Test
  fun `failed perform request is logged and does not block the queue`() = runSerializerTest { serializer ->
    val events = CopyOnWriteArrayList<String>()
    val loggedErrors = CopyOnWriteArrayList<Throwable>()
    val requestAfterFailureCompleted = CompletableDeferred<Unit>()

    val errorProcessor = LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
      override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> {
        if (t != null) {
          loggedErrors += t
        }
        return Action.NONE
      }
    })
    errorProcessor.use {
      serializer.performRequest {
        events += "failing"
        throw IllegalStateException("boom")
      }
      serializer.performRequest {
        events += "after-failure"
        requestAfterFailureCompleted.complete(Unit)
      }
      val successfulRequest = serializer.scheduleRequest {
        events += "successful"
        42
      }

      requestAfterFailureCompleted.await()
      assertEquals(42, successfulRequest.await())
    }

    assertEquals(listOf("failing", "after-failure", "successful"), events)
    assertTrue(loggedErrors.map { it.message }.contains("boom"))
  }

  private fun runSerializerTest(test: suspend (RequestsSerializer) -> Unit) = runBlocking {
    val serializerScope = childScope("RequestsSerializerTest")
    val serializer = RequestsSerializer.create(serializerScope)

    try {
      withTimeout(5.seconds) {
        test(serializer)
      }
    }
    finally {
      serializerScope.coroutineContext.job.cancelAndJoin()
    }
  }
}
