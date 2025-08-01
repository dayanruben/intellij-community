// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.maven.wizards

import com.intellij.ide.util.EditorHelper
import com.intellij.openapi.GitSilentFileAdderProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.observation.launchTracked
import com.intellij.platform.backend.observation.trackActivityBlocking
import com.intellij.platform.eel.fs.createTemporaryDirectory
import com.intellij.platform.eel.getOrThrow
import com.intellij.platform.eel.provider.asNioPath
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.util.text.VersionComparatorUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.VisibleForTesting
import org.jetbrains.idea.maven.dom.MavenDomUtil
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel
import org.jetbrains.idea.maven.execution.MavenRunner
import org.jetbrains.idea.maven.execution.MavenRunnerParameters
import org.jetbrains.idea.maven.model.MavenArchetype
import org.jetbrains.idea.maven.model.MavenConstants
import org.jetbrains.idea.maven.model.MavenConstants.MODEL_VERSION_4_1_0
import org.jetbrains.idea.maven.model.MavenId
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectBundle
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.statistics.MavenActionsUsagesCollector
import org.jetbrains.idea.maven.statistics.MavenActionsUsagesCollector.trigger
import org.jetbrains.idea.maven.utils.MavenActivityKey
import org.jetbrains.idea.maven.utils.MavenLog
import org.jetbrains.idea.maven.utils.MavenUtil
import org.jetbrains.idea.maven.utils.NioFiles
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.pathString

open class MavenModuleBuilderHelper(
  protected val myProjectId: MavenId,
  protected val myAggregatorProject: MavenProject?,
  private val myParentProject: MavenProject?,
  private val myInheritGroupId: Boolean,
  private val myInheritVersion: Boolean,
  private val myArchetype: MavenArchetype?,
  private val myPropertiesToCreateByArtifact: Map<String, String>?,
  protected val myCommandName: @NlsContexts.Command String?,
) {

  @Service(Service.Level.PROJECT)
  private class CoroutineService(val coroutineScope: CoroutineScope)

  open fun configure(project: Project, root: VirtualFile, isInteractive: Boolean) {
    project.trackActivityBlocking(MavenActivityKey) {
      doConfigure(project, root, isInteractive)
    }
  }

  open fun doConfigure(project: Project, root: VirtualFile, isInteractive: Boolean) {
    trigger(project, MavenActionsUsagesCollector.CREATE_MAVEN_PROJECT)

    val psiFiles = if (myAggregatorProject != null) arrayOf(getPsiFile(project, myAggregatorProject.file)) else PsiFile.EMPTY_ARRAY

    val pom = WriteCommandAction.writeCommandAction(project, *psiFiles).withName(myCommandName).compute<VirtualFile?, RuntimeException> {
      val vcsFileAdder = GitSilentFileAdderProvider.create(project)
      var file: VirtualFile? = null
      try {
        try {
          file = root.findChild(MavenConstants.POM_XML)
          file?.delete(this)
          file = root.createChildData(this, MavenConstants.POM_XML)
          if (myAggregatorProject == null) {
            root.createChildDirectory(this, MavenConstants.MVN_CONFIG_DIR)
          }
          vcsFileAdder.markFileForAdding(file)
          MavenUtil.runOrApplyMavenProjectFileTemplate(project, file, myProjectId, isInteractive)
        }
        catch (e: IOException) {
          showError(project, e)
          return@compute file
        }

        updateProjectPom(project, file)
      }
      finally {
        vcsFileAdder.finish()
      }

      if (myAggregatorProject != null) {
        setPomPackagingForAggregatorProject(project, file)
      }
      file
    }

    if (pom == null) return

    if (myAggregatorProject == null) {
      val manager = MavenProjectsManager.getInstance(project)
      manager.addManagedFilesOrUnignoreNoUpdate(listOf(pom))
    }

    if (myArchetype == null) {
      try {
        VfsUtil.createDirectories(root.path + "/src/main/java")
        VfsUtil.createDirectories(root.path + "/src/main/resources")
        VfsUtil.createDirectories(root.path + "/src/test/java")
      }
      catch (e: IOException) {
        MavenLog.LOG.info(e)
      }
    }

    MavenLog.LOG.info("${this.javaClass.simpleName} forceUpdateAllProjectsOrFindAllAvailablePomFiles")
    MavenProjectsManager.getInstance(project).forceUpdateAllProjectsOrFindAllAvailablePomFiles()

    val cs = project.service<CoroutineService>().coroutineScope
    cs.launchTracked {
      // execute when current dialog is closed (e.g. Project Structure)
      withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
        if (!pom.isValid) {
          showError(project, RuntimeException("Project is not valid"))
          return@withContext
        }
        writeIntentReadAction {
          EditorHelper.openInEditor(getPsiFile(project, pom)!!)
        }
        if (myArchetype != null) generateFromArchetype(project, pom)
      }
    }
  }

  protected fun setPomPackagingForAggregatorProject(project: Project, file: VirtualFile) {
    val aggregatorProjectFile = myAggregatorProject!!.file
    val model = MavenDomUtil.getMavenDomProjectModel(project, aggregatorProjectFile)
    if (model != null) {
      model.packaging.stringValue = "pom"
      val psiFile = getPsiFile(project, file)

      val useSubprojects = useSubprojects(model)
      if (useSubprojects) {
        val subproject = model.subprojects.addSubproject()
        subproject.value = psiFile
      }
      else {
        val module = model.modules.addModule()
        module.value = psiFile
      }

      unblockAndSaveDocuments(project, aggregatorProjectFile)
    }
  }

  private fun useSubprojects(model: MavenDomProjectModel): Boolean {
    // if any subprojects exist, add subproject; if modules exist, add module; if none exist, check modelVersion
    if (model.subprojects.subprojects.any()) return true
    if (model.modules.modules.any()) return false
    val modelVersion = model.modelVersion.value
    return VersionComparatorUtil.compare(modelVersion, MODEL_VERSION_4_1_0) >= 0
  }

  protected fun updateProjectPom(project: Project, pom: VirtualFile) {
    if (myParentProject == null) return

    WriteCommandAction.writeCommandAction(project).withName(myCommandName).run<RuntimeException> {
      PsiDocumentManager.getInstance(project).commitAllDocuments()
      val model = MavenDomUtil.getMavenDomProjectModel(project, pom)
      if (model == null) return@run

      MavenDomUtil.updateMavenParent(model, myParentProject)

      if (myInheritGroupId) {
        val el = model.groupId.xmlElement
        el?.delete()
      }
      if (myInheritVersion) {
        val el = model.version.xmlElement
        el?.delete()
      }

      CodeStyleManager.getInstance(project).reformat(getPsiFile(project, pom)!!)

      val pomFiles: MutableList<VirtualFile> = ArrayList(2)
      pomFiles.add(pom)

      if (!FileUtil.namesEqual(MavenConstants.POM_XML, myParentProject.file.name)) {
        pomFiles.add(myParentProject.file)
        MavenProjectsManager.getInstance(project).scheduleForceUpdateMavenProject(myParentProject)
      }
      unblockAndSaveDocuments(project, *pomFiles.toTypedArray())
    }
  }

  private suspend fun generateFromArchetype(project: Project, pom: VirtualFile) {
    trigger(project, MavenActionsUsagesCollector.CREATE_MAVEN_PROJECT_FROM_ARCHETYPE)

    val eel = project.getEelDescriptor().toEelApi()

    val workingDir: Path = try {
      eel.fs.createTemporaryDirectory()
        .suffix("tmp")
        .prefix("archetype")
        .deleteOnExit(true)
        .getOrThrow { throw IOException(it.message) }.asNioPath()
    }
    catch (e: IOException) {
      showError(project, e)
      return
    }

    val mavenVersion = MavenUtil.getMavenVersion(project)
    val archetypePluginVersion = if (StringUtil.compareVersionNumbers(mavenVersion, "3.6.3") >= 0) "RELEASE" else "3.1.2"

    val params = MavenRunnerParameters(
      false, workingDir.pathString, null as String?,
      listOf("org.apache.maven.plugins:maven-archetype-plugin:$archetypePluginVersion:generate"),
      emptyList())

    val runner = MavenRunner.getInstance(project)
    val settings = runner.state.clone()

    val props = settings.mavenProperties
    props["interactiveMode"] = "false"
    if (null != myPropertiesToCreateByArtifact) {
      props.putAll(myPropertiesToCreateByArtifact)
    }

    withContext(Dispatchers.Default) {
      runner.run(params, settings) { copyGeneratedFiles(workingDir, pom, project, props["artifactId"]) }
    }
  }

  @VisibleForTesting
  fun copyGeneratedFiles(workingDir: Path, pom: VirtualFile, project: Project, artifactId: String?) {
    var artifactId = artifactId
    val vcsFileAdder = GitSilentFileAdderProvider.create(project)
    try {
      try {
        artifactId = artifactId ?: myProjectId.artifactId
        if (artifactId != null) {
          val sourceDir = workingDir.resolve(artifactId)
          val targetDir = pom.parent.toNioPath()
          vcsFileAdder.markFileForAdding(targetDir, true) // VFS is refreshed below
          NioFiles.copyRecursively(sourceDir, targetDir)
        }
        FileUtil.delete(workingDir)
      }
      catch (e: Exception) {
        showError(project, e)
        return
      }

      pom.parent.refresh(false, false)
      pom.refresh(false, false)
      updateProjectPom(project, pom)

      LocalFileSystem.getInstance().refreshWithoutFileWatcher(true)
    }
    finally {
      vcsFileAdder.finish()
    }
  }

  companion object {
    private fun unblockAndSaveDocuments(project: Project, vararg files: VirtualFile) {
      val fileDocumentManager = FileDocumentManager.getInstance()
      val psiDocumentManager = PsiDocumentManager.getInstance(project)
      for (file in files) {
        val document = fileDocumentManager.getDocument(file)
        if (document == null) continue
        psiDocumentManager.doPostponedOperationsAndUnblockDocument(document)
        fileDocumentManager.saveDocument(document)
      }
    }

    @JvmStatic
    protected fun getPsiFile(project: Project, pom: VirtualFile): PsiFile? {
      return PsiManager.getInstance(project).findFile(pom)
    }

    @JvmStatic
    protected fun showError(project: Project, e: Throwable) {
      MavenUtil.showError(project, MavenProjectBundle.message("notification.title.failed.to.create.maven.project"), e)
    }
  }
}
