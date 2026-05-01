// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.debugger.impl.frontend.util

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.launch

/**
 * A utility class designed to serialize and manage the execution of asynchronous RPC requests.
 * Each request is scheduled and executed sequentially to ensure thread-safe operation.
 */
internal class RequestsSerializer private constructor() {
  private val requests = Channel<Request>(Channel.UNLIMITED)

  fun <T> scheduleRequest(block: suspend () -> T): Deferred<T> {
    val completableRequest = CompletableRequest(block)
    requests.trySend(completableRequest)
    return completableRequest.result
  }

  fun performRequest(block: suspend () -> Unit) {
    val simpleRequest = SimpleRequest(block)
    requests.trySend(simpleRequest)
  }

  private sealed interface Request {
    suspend fun performRequest()
  }

  private class SimpleRequest(val request: suspend () -> Unit) : Request {
    override suspend fun performRequest() {
      try {
        request()
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (e: Throwable) {
        thisLogger().error("Error during request execution", e)
      }
    }
  }

  private class CompletableRequest<T>(val request: suspend () -> T) : Request {
    val result = CompletableDeferred<T>()

    override suspend fun performRequest() {
      val result = runCatching { request() }
      val exception = result.exceptionOrNull()
      if (exception is CancellationException) {
        throw exception
      }
      this.result.completeWith(result)
    }
  }

  companion object {
    fun create(cs: CoroutineScope): RequestsSerializer {
      val serializer = RequestsSerializer()
      cs.launch {
        serializer.requests.consumeEach { request ->
          request.performRequest()
        }
      }
      return serializer
    }
  }
}
