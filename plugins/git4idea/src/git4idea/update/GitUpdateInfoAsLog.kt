// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.update

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsBundle
import com.intellij.openapi.vcs.VcsException
import com.intellij.ui.content.TabGroupId
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.text.DateFormatUtil
import com.intellij.vcs.log.CommitId
import com.intellij.vcs.log.VcsLogFilterCollection
import com.intellij.vcs.log.VcsLogFilterCollection.*
import com.intellij.vcs.log.VcsLogRangeFilter
import com.intellij.vcs.log.data.DataPack
import com.intellij.vcs.log.data.DataPackChangeListener
import com.intellij.vcs.log.data.VcsLogData
import com.intellij.vcs.log.impl.MainVcsLogUiProperties
import com.intellij.vcs.log.impl.VcsLogContentUtil
import com.intellij.vcs.log.impl.VcsLogManager
import com.intellij.vcs.log.impl.VcsProjectLog
import com.intellij.vcs.log.ui.MainVcsLogUi
import com.intellij.vcs.log.ui.VcsLogColorManager
import com.intellij.vcs.log.ui.VcsLogUiEx
import com.intellij.vcs.log.ui.VcsLogUiImpl
import com.intellij.vcs.log.util.containsAll
import com.intellij.vcs.log.visible.VcsLogFiltererImpl
import com.intellij.vcs.log.visible.VisiblePack
import com.intellij.vcs.log.visible.VisiblePackChangeListener
import com.intellij.vcs.log.visible.VisiblePackRefresherImpl
import com.intellij.vcs.log.visible.filters.VcsLogFilterObject
import git4idea.GitRevisionNumber
import git4idea.history.GitHistoryUtils
import git4idea.merge.MergeChangeCollector
import git4idea.repo.GitRepository
import java.util.*
import java.util.concurrent.CompletableFuture

private val LOG = logger<GitUpdateInfoAsLog>()

class GitUpdateInfoAsLog(private val project: Project,
                         private val ranges: Map<GitRepository, HashRange>) {

  private val projectLog: VcsProjectLog = VcsProjectLog.getInstance(project)

  private var notificationShown: Boolean = false // accessed only from EDT

  class NotificationData(val updatedFilesCount: Int,
                         val receivedCommitsCount: Int,
                         val filteredCommitsCount: Int?,
                         val viewCommitAction: Runnable,
                         val ranges: Map<GitRepository, HashRange>)

  private class CommitsAndFiles(val updatedFilesCount: Int, val receivedCommitsCount: Int)

  @RequiresBackgroundThread
  fun calculateDataAndCreateLogTab(): NotificationData? {
    val commitsAndFiles = calculateDataFromGit() ?: return null
    if (!VcsProjectLog.ensureLogCreated(project)) {
      return null
    }

    val rangeFilter = createRangeFilter()

    if (isPathFilterSet()) {
      val commitCount = waitForLogRefreshAndCalculate() ?: return null
      return NotificationData(commitsAndFiles.updatedFilesCount, commitsAndFiles.receivedCommitsCount, commitCount,
                              getViewCommitsAction(rangeFilter), ranges)
    }

    // if no path filters is set, we don't need the log to show the notification
    // => schedule the log tab and return the data
    runInEdt { findOrCreateLogUi(rangeFilter, false) }
    return NotificationData(commitsAndFiles.updatedFilesCount, commitsAndFiles.receivedCommitsCount, null,
                            getViewCommitsAction(rangeFilter), ranges)
  }

  private fun isPathFilterSet(): Boolean {
    return project.service<GitUpdateProjectInfoLogProperties>().getFilterValues(STRUCTURE_FILTER.name) != null
  }

  @RequiresBackgroundThread
  private fun waitForLogRefreshAndCalculate(): Int? {
    val dataSupplier = CompletableFuture<Int>()
    runInEdt {
      projectLog.logManager?.let { logManager ->
        val listener = object : DataPackChangeListener {
          override fun onDataPackChange(dataPack: DataPack) {
            createLogTabAndCalculateIfRangesAreReachable(dataPack, logManager, dataSupplier, this)
          }
        }
        logManager.dataManager.addDataPackChangeListener(listener)
        createLogTabAndCalculateIfRangesAreReachable(logManager.dataManager.dataPack, logManager, dataSupplier, listener)
      } ?: dataSupplier.complete(null)
    }

    ProgressIndicatorUtils.awaitWithCheckCanceled(dataSupplier)
    return dataSupplier.get()
  }

  @RequiresEdt
  private fun createLogTabAndCalculateIfRangesAreReachable(dataPack: DataPack,
                                                           logManager: VcsLogManager,
                                                           dataSupplier: CompletableFuture<Int>,
                                                           listener: DataPackChangeListener) {
    if (!notificationShown && areRangesInDataPack(projectLog, dataPack)) {
      notificationShown = true
      projectLog.dataManager?.removeDataPackChangeListener(listener)
      val logUiFactory = object : MyLogUiFactory(logManager.colorManager, createRangeFilter()) {
        override fun createLogUi(project: Project, logData: VcsLogData): MainVcsLogUi {
          val logUi = super.createLogUi(project, logData)
          logUi.refresher.addVisiblePackChangeListener(MyVisiblePackChangeListener(logUi, dataSupplier))
          return logUi
        }
      }
      createLogUi(logManager, logUiFactory, select = false)
    }
  }

  private fun areRangesInDataPack(log: VcsProjectLog, dataPack: DataPack): Boolean {
    return dataPack.containsAll(ranges.asIterable().map { CommitId(it.value.end, it.key.root) }, log.dataManager!!.storage)
  }

  private fun calculateDataFromGit(): CommitsAndFiles? {
    val updatedCommitsCount = calcUpdatedCommitsCount()
    if (updatedCommitsCount == 0) {
      return null
    }
    val updatedFilesCount = calcUpdatedFilesCount()
    return CommitsAndFiles(updatedFilesCount, updatedCommitsCount)
  }

  private fun findOrCreateLogUi(rangeFilter: VcsLogRangeFilter, select: Boolean) {
    if (ApplicationManager.getApplication().isUnitTestMode) {
      return
    }
    val logManager = projectLog.logManager
    if (logManager == null) {
      if (select) {
        VcsLogContentUtil.showLogIsNotAvailableMessage(project)
      }
      return
    }
    val logUi = VcsLogContentUtil.findLogUi(project, VcsLogUiEx::class.java, select) {
      isUpdateTabId(it.id) && it.filterUi.filters.get(RANGE_FILTER) == rangeFilter
    }
    if (logUi != null) return

    createLogUi(logManager, MyLogUiFactory(logManager.colorManager, rangeFilter), select)
  }

  private fun getViewCommitsAction(rangeFilter: VcsLogRangeFilter): Runnable {
    return Runnable { findOrCreateLogUi(rangeFilter, true) }
  }

  private fun createRangeFilter(): VcsLogRangeFilter {
    return VcsLogFilterObject.fromRange(ranges.values.map {
      VcsLogRangeFilter.RefRange(it.start.asString(), it.end.asString())
    })
  }

  private fun createLogUi(logManager: VcsLogManager, logUiFactory: MyLogUiFactory, select: Boolean) {
    val tabName = DateFormatUtil.formatDateTime(System.currentTimeMillis())
    val ui = logManager.createLogUi(logUiFactory)
    VcsLogContentUtil.openLogTab(project, logManager, tabGroupId, ui, { tabName }, select)
  }

  private val tabGroupId = TabGroupId("Update Info", VcsBundle.messagePointer("vcs.update.tab.name"))
  private val updateTabPrefix = "git-update-project-info-"
  private fun generateUpdateTabId() = updateTabPrefix + UUID.randomUUID()
  private fun isUpdateTabId(id: String): Boolean = id.startsWith(updateTabPrefix)

  private open inner class MyLogUiFactory(val colorManager: VcsLogColorManager, val rangeFilter: VcsLogRangeFilter)
    : VcsLogManager.VcsLogUiFactory<MainVcsLogUi> {
    override fun createLogUi(project: Project, logData: VcsLogData): MainVcsLogUi {
      val logId = generateUpdateTabId()
      val properties = MyPropertiesForRange(rangeFilter, project.service<GitUpdateProjectInfoLogProperties>())
      val vcsLogFilterer = VcsLogFiltererImpl(logData)
      val initialGraphOptions = properties[MainVcsLogUiProperties.GRAPH_OPTIONS]
      val refresher = VisiblePackRefresherImpl(project, logData, VcsLogFilterObject.collection(rangeFilter), initialGraphOptions,
                                               vcsLogFilterer, logId)

      // null for initial filters means that filters will be loaded from properties: saved filters + the range filter which we've just set
      return VcsLogUiImpl(logId, logData, colorManager, properties, refresher, null)
    }
  }

  private class MyPropertiesForRange(val rangeFilter: VcsLogRangeFilter, val mainProperties: GitUpdateProjectInfoLogProperties)
    : MainVcsLogUiProperties by mainProperties {
    private val filters = mutableMapOf<String, List<String>>()
    private var explicitlyRemovedPathsFilter = false

    override fun getFilterValues(filterName: String): List<String>? {
      when (filterName) {
        RANGE_FILTER.name -> return ArrayList(rangeFilter.getTextPresentation())
        STRUCTURE_FILTER.name, ROOT_FILTER.name -> return getPathsFilterValues(filterName)
        else -> return filters[filterName]
      }
    }

    private fun getPathsFilterValues(filterName: String): List<String>? {
      if (explicitlyRemovedPathsFilter) return null
      return filters[filterName] ?: mainProperties.getFilterValues(filterName)
    }

    override fun saveFilterValues(filterName: String, values: List<String>?) {
      if (values != null) {
        filters[filterName] = values
      }
      else {
        filters.remove(filterName)
      }

      if (filterName == STRUCTURE_FILTER.name || filterName == ROOT_FILTER.name) {
        explicitlyRemovedPathsFilter = values == null
      }
    }
  }

  private fun calcUpdatedCommitsCount(): Int {
    return calcCount { repository, range ->
      GitHistoryUtils.collectTimedCommits(project, repository.root,
                                          "${range.start.asString()}..${range.end.asString()}").size
    }
  }

  private fun calcUpdatedFilesCount(): Int {
    return calcCount { repository, range ->
      MergeChangeCollector(project, repository, GitRevisionNumber(range.start.asString())).calcUpdatedFilesCount()
    }
  }

  private fun calcCount(sizeForRepo: (GitRepository, HashRange) -> Int): Int {
    var result = 0
    for ((repository, range) in ranges) {
      try {
        result += sizeForRepo(repository, range)
      }
      catch (e: VcsException) {
        LOG.warn("Couldn't collect commits in root ${repository.root} in range $range", e)
      }
    }
    return result
  }

  private inner class MyVisiblePackChangeListener(val logUi: MainVcsLogUi,
                                                  val dataSupplier: CompletableFuture<Int>) : VisiblePackChangeListener {

    override fun onVisiblePackChange(visiblePack: VisiblePack) {
      runInEdt {
        if (!dataSupplier.isDone && areFiltersEqual(visiblePack.filters, logUi.filterUi.filters)) {
          logUi.refresher.removeVisiblePackChangeListener(this)
          dataSupplier.complete(visiblePack.visibleGraph.visibleCommitCount)
        }
      }
    }
  }
}

private fun areFiltersEqual(filters1: VcsLogFilterCollection, filters2: VcsLogFilterCollection): Boolean {
  if (filters1 === filters2) return true
  if (filters1.filters.size != filters2.filters.size) return false
  return filters1.filters.all { it == filters2.get(it.key) }
}
