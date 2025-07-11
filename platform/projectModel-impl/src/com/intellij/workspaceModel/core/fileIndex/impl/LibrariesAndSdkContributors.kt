// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.workspaceModel.core.fileIndex.impl

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.roots.impl.RootFileValidityChecker
import com.intellij.openapi.roots.impl.libraries.LibraryEx
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.workspace.storage.EntityPointer
import com.intellij.platform.workspace.storage.EntityStorage
import com.intellij.platform.workspace.storage.WorkspaceEntity
import com.intellij.util.SmartList
import com.intellij.util.asSafely
import com.intellij.util.containers.MultiMap
import com.intellij.workspaceModel.core.fileIndex.EntityStorageKind
import com.intellij.workspaceModel.core.fileIndex.WorkspaceFileKind
import com.intellij.workspaceModel.core.fileIndex.WorkspaceFileSet
import com.intellij.workspaceModel.core.fileIndex.WorkspaceFileSetData
import com.intellij.workspaceModel.ide.impl.legacyBridge.sdk.SdkBridgeImpl.Companion.findSdk
import com.intellij.workspaceModel.ide.legacyBridge.ModuleDependencyIndex
import com.intellij.workspaceModel.ide.legacyBridge.ModuleDependencyListener
import java.util.*

internal class LibrariesAndSdkContributors(
  private val project: Project,
  private val fileSets: MutableMap<VirtualFile, StoredFileSetCollection>,
  private val fileSetsByPackagePrefix: PackagePrefixStorage,
  private val projectRootManager: ProjectRootManagerEx,
  parentDisposable: Disposable,
) : ModuleDependencyListener, ProjectRootManagerEx.ProjectJdkListener {
  private val sdkRoots = MultiMap.create<Sdk, VirtualFile>()
  private val libraryRoots = MultiMap<Library, VirtualFile>(IdentityHashMap())
  private val moduleDependencyIndex = ModuleDependencyIndex.getInstance(project)
  private var registeredProjectSdk: Sdk? = null
  private var includeProjectSdk = false
  
  init {
    moduleDependencyIndex.addListener(this)
    projectRootManager.addProjectJdkListener(this)
    Disposer.register(parentDisposable) {
      moduleDependencyIndex.removeListener(this)
      projectRootManager.removeProjectJdkListener(this)
    }
  }

  fun registerFileSets(libraryTablesRegistrar: LibraryTablesRegistrar) {
    var noSdkIsUsed = true
    for (sdk in ProjectJdkTable.getInstance().allJdks) {
      if (moduleDependencyIndex.hasDependencyOn(sdk)) {
        registerSdkRoots(sdk)
        noSdkIsUsed = false
      }
    }
    if (noSdkIsUsed) {
      registerProjectSdkRoots()
    }
    for (libraryTable in libraryTablesRegistrar.customLibraryTables.asSequence() + libraryTablesRegistrar.libraryTable) {
      for (library in libraryTable.libraries) {
        if (moduleDependencyIndex.hasDependencyOn(library)) {
          registerLibraryRoots(library)
        }
      }
    }
  }

  private fun registerProjectSdkRoots() {
    unregisterProjectSdkRoots()
    includeProjectSdk = true
    val projectSdk = ProjectRootManager.getInstance(project).projectSdk
    if (projectSdk != null) {
      registeredProjectSdk = projectSdk
      registerSdkRoots(projectSdk)
    }
  }

  private fun registerLibraryRoots(library: Library) {
    fun registerLibraryRoots(
      rootType: OrderRootType,
      kind: WorkspaceFileKind,
      pointer: GlobalLibraryPointer,
      data: WorkspaceFileSetData,
    ) {
      val entityRefToFileSet by lazy(LazyThreadSafetyMode.NONE) {
        fileSetsByPackagePrefix.computeIfAbsent("") { LinkedHashMap() }
      }

      for (root in library.getFiles(rootType)) {
        if (RootFileValidityChecker.ensureValid(root, library, null)) {
          val fileSet = WorkspaceFileSetImpl(root, kind, pointer, EntityStorageKind.MAIN, data)
          fileSets.putValue(root, fileSet)
          entityRefToFileSet.computeIfAbsent(fileSet.entityPointer) { SmartList() }.add(fileSet)
          libraryRoots.putValue(library, root)
        }
      }
    }

    val pointer = GlobalLibraryPointer(library)
    registerLibraryRoots(OrderRootType.CLASSES, WorkspaceFileKind.EXTERNAL, pointer, LibraryRootFileSetData(null))
    registerLibraryRoots(OrderRootType.SOURCES, WorkspaceFileKind.EXTERNAL_SOURCE, pointer, LibrarySourceRootFileSetData(null))
    (library as? LibraryEx)?.excludedRoots?.forEach {
      if (RootFileValidityChecker.ensureValid(it, library, null)) {
        fileSets.putValue(it, ExcludedFileSet.ByFileKind(WorkspaceFileKindMask.EXTERNAL, pointer))
        libraryRoots.putValue(library, it)
      }
    }
  }

  private fun registerSdkRoots(sdk: Sdk) {
    val virtualFileManager = VirtualFileManager.getInstance()

    fun registerSdkRoots(rootType: OrderRootType, kind: WorkspaceFileKind, pointer: SdkPointer, data: WorkspaceFileSetData) {
      for (url in sdk.rootProvider.getUrls(rootType)) {
        val root = virtualFileManager.findFileByUrl(url)
        if (root != null && RootFileValidityChecker.ensureValid(root, sdk, null)) {
          val fileSet = WorkspaceFileSetImpl(root, kind, pointer, EntityStorageKind.MAIN, data)
          fileSets.putValue(root, fileSet)
          fileSetsByPackagePrefix.addFileSet("", fileSet)
          sdkRoots.putValue(sdk, root)
        }
      }
    }

    val pointer = SdkPointer(sdk)
    registerSdkRoots(OrderRootType.CLASSES, WorkspaceFileKind.EXTERNAL, pointer, LibraryRootFileSetData(null))
    registerSdkRoots(OrderRootType.SOURCES, WorkspaceFileKind.EXTERNAL_SOURCE, pointer, LibrarySourceRootFileSetData(null))
  }

  private fun unregisterSdkRoots(sdk: Sdk) {
    val roots = sdkRoots.remove(sdk) ?: return
    for (root in roots) {
      fileSets.removeValueIf(root) { fileSet -> (fileSet.entityPointer as? SdkPointer)?.sdk == sdk }
      fileSetsByPackagePrefix.removeByPrefixAndPointer("", SdkPointer(sdk))
    }
  }

  private fun unregisterLibraryRoots(library: Library) {
    val roots = libraryRoots.remove(library) ?: return
    for (root in roots) {
      fileSets.removeValueIf(root) { fileSet -> (fileSet.entityPointer as? GlobalLibraryPointer)?.library === library }
      fileSetsByPackagePrefix.removeByPrefixAndPointer("", GlobalLibraryPointer(library))
    }
  }

  override fun firstDependencyOnSdkAdded() {
    unregisterProjectSdkRoots()
  }

  private fun unregisterProjectSdkRoots() {
    registeredProjectSdk?.let { unregisterSdkRoots(it) }
    registeredProjectSdk = null
    includeProjectSdk = false
  }

  override fun lastDependencyOnSdkRemoved() {
    registerProjectSdkRoots()
  }

  override fun addedDependencyOn(library: Library) {
    if (shouldListen(library)) {
      registerLibraryRoots(library)
    }
  }

  private fun shouldListen(library: Library) = library.table?.tableLevel != LibraryTablesRegistrar.PROJECT_LEVEL

  override fun referencedLibraryChanged(library: Library) {
    if (shouldListen(library)) {
      unregisterLibraryRoots(library)
      registerLibraryRoots(library)
    }
  }

  override fun removedDependencyOn(library: Library) {
    if (shouldListen(library)) {
      unregisterLibraryRoots(library)
    }
  }

  override fun addedDependencyOn(sdk: Sdk) {
    registerSdkRoots(sdk)
  }

  override fun referencedSdkChanged(sdk: Sdk) {
    unregisterSdkRoots(sdk)
    registerSdkRoots(sdk)
  }

  override fun removedDependencyOn(sdk: Sdk) {
    unregisterSdkRoots(sdk)
  }

  override fun projectJdkChanged() {
    if (includeProjectSdk) {
      registerProjectSdkRoots()
    }
  }

  companion object {
    internal fun getGlobalLibrary(fileSet: WorkspaceFileSet): Library? {
      return fileSet.asSafely<WorkspaceFileSetImpl>()?.entityPointer.asSafely<GlobalLibraryPointer>()?.library
    }

    /** Consider using [com.intellij.workspaceModel.ide.impl.legacyBridge.sdk.SdkBridgeImpl.findSdk] instead */
    internal fun getSdk(fileSet: WorkspaceFileSet): Sdk? {
      return fileSet.asSafely<WorkspaceFileSetImpl>()?.entityPointer.asSafely<SdkPointer>()?.sdk
    }

    fun isPlaceholderReference(entityPointer: EntityPointer<WorkspaceEntity>): Boolean {
      return entityPointer is GlobalLibraryPointer || entityPointer is SdkPointer
    }
  }
}

private class GlobalLibraryPointer(val library: Library) : EntityPointer<WorkspaceEntity> {
  override fun resolve(storage: EntityStorage): WorkspaceEntity? = null
  override fun isPointerTo(entity: WorkspaceEntity): Boolean = false

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as GlobalLibraryPointer
    return library === other.library
  }

  override fun hashCode(): Int {
    return System.identityHashCode(library)
  }
}

private class SdkPointer(val sdk: Sdk) : EntityPointer<WorkspaceEntity> {
  override fun resolve(storage: EntityStorage): WorkspaceEntity? = null
  override fun isPointerTo(entity: WorkspaceEntity): Boolean = false

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as SdkPointer
    return sdk === other.sdk
  }

  override fun hashCode(): Int {
    return sdk.hashCode()
  }
}