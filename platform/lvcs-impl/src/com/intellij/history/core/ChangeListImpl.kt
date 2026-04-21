// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.history.core

import com.intellij.history.ActivityId
import com.intellij.history.core.changes.Change
import com.intellij.history.core.changes.ChangeSet
import com.intellij.history.utils.LocalHistoryLog
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Clock
import com.intellij.openapi.util.NlsContexts
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import org.jetbrains.annotations.TestOnly
import kotlin.time.Duration.Companion.hours

internal class ChangeListImpl(private val storage: ChangeListStorage) : ChangeList {
  private var changeSetDepth = 0
  private var currentChangeSet: ChangeSet? = null

  private var intervalBetweenActivities = 12.hours.inWholeMilliseconds

  @Synchronized
  override fun nextId(): Long = storage.nextId()

  @Synchronized
  override fun addChange(c: Change) {
    assert(changeSetDepth != 0)
    currentChangeSet!!.addChange(c)
  }

  @Synchronized
  override fun beginChangeSet() {
    changeSetDepth++
    if (changeSetDepth > 1) return

    doBeginChangeSet()
  }

  private fun doBeginChangeSet() {
    currentChangeSet = ChangeSet(nextId(), Clock.getTime())
  }

  @Synchronized
  override fun forceBeginChangeSet(): ChangeSet? {
    val lastChangeSet = if (changeSetDepth > 0) doEndChangeSet(null, null) else null

    changeSetDepth++
    doBeginChangeSet()
    return lastChangeSet
  }

  @Synchronized
  override fun endChangeSet(name: @NlsContexts.Label String?, activityId: ActivityId?): ChangeSet? {
    LocalHistoryLog.LOG.assertTrue(changeSetDepth > 0, "not balanced 'begin/end-change set' calls")

    changeSetDepth--
    if (changeSetDepth > 0) return null

    return doEndChangeSet(name, activityId)
  }

  private fun doEndChangeSet(name: @NlsContexts.Label String?, activityId: ActivityId?): ChangeSet? {
    if (currentChangeSet!!.isEmpty) {
      currentChangeSet = null
      return null
    }

    val lastChangeSet = currentChangeSet
    lastChangeSet!!.name = name
    lastChangeSet.activityId = activityId
    lastChangeSet.lock()
    storage.writeNextSet(lastChangeSet)

    currentChangeSet = null

    return lastChangeSet
  }

  // todo synchronization issue: changeset may me modified while being iterated
  @Synchronized
  override fun iterChanges(): Iterable<ChangeSet> {
    return Iterable {
      object : Iterator<ChangeSet> {
        private val recursionGuard = IntOpenHashSet(1000)

        private var currentBlock: ChangeSetHolder? = null
        private var next = fetchNext()

        override fun hasNext(): Boolean = next != null

        override fun next(): ChangeSet {
          val result = next!!
          next = fetchNext()
          return result
        }

        private fun fetchNext(): ChangeSet? {
          if (currentBlock == null) {
            synchronized(this@ChangeListImpl) {
              currentBlock = if (currentChangeSet != null) {
                ChangeSetHolder(-1, currentChangeSet)
              }
              else {
                storage.readPrevious(-1, recursionGuard)
              }
            }
          }
          else {
            synchronized(this@ChangeListImpl) {
              currentBlock = storage.readPrevious(currentBlock!!.id, recursionGuard)
            }
          }
          return currentBlock?.changeSet
        }
      }
    }
  }

  override fun purgeObsolete(period: Long) {
    storage.purge(period, intervalBetweenActivities)
    storage.force()
  }

  @TestOnly
  override fun setIntervalBetweenActivities(value: Long) {
    intervalBetweenActivities = value
  }

  fun force(): Unit = storage.force()

  @Synchronized
  fun close(drop: Boolean) {
    if (!ApplicationManager.getApplication().isUnitTestMode) {
      LocalHistoryLog.LOG.assertTrue(currentChangeSet == null || currentChangeSet!!.isEmpty,
                                     "current changes won't be saved: $currentChangeSet")
    }
    storage.close(drop)
  }
}