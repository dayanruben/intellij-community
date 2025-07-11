// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.requirements

import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.python.pyproject.PY_PROJECT_TOML
import com.intellij.testFramework.TestDataPath
import com.jetbrains.python.requirements.inspections.tools.NotInstalledRequirementInspection
import com.jetbrains.python.sdk.pythonSdk

@TestDataPath("\$CONTENT_ROOT/../testData/requirements/inspections")
class UnsatisfiedRequirementInspectionTest : PythonDependencyTestCase() {
  fun testUnsatisfiedRequirement() {
    doMultiFileTest("requirements.txt")
    assertContainsElements(myFixture.availableIntentions.map { it.text }, "Install package mypy")
  }

  fun testPyProjectTomlUnsatisfiedRequirement() {
    doMultiFileTest(PY_PROJECT_TOML)
    val warnings = myFixture.doHighlighting(HighlightSeverity.WARNING)
    assertTrue("[build-system] should not have unsatisfied inspection", warnings.none { it.text == "poetry-core" })
    listOf("mypy").forEach { unsatisfiedPackage ->
      val warning = warnings.single { it.text == unsatisfiedPackage }
      assertEquals("Package $unsatisfiedPackage is not installed", warning.description)
    }
  }


  fun testEmptyRequirementsFile() {
    doMultiFileTest("requirements.txt")
    assertContainsElements(myFixture.availableIntentions.map { it.text }, "Add imported packages to requirements…")
  }

  private fun doMultiFileTest(filename: String) {
    myFixture.copyDirectoryToProject(getTestName(false), "")
    myFixture.configureFromTempProjectFile(filename)
    getPythonSdk(myFixture.file)!!
    myFixture.enableInspections(NotInstalledRequirementInspection::class.java)
    myFixture.checkHighlighting(true, false, true, false)
  }

  override fun setUp() {
    super.setUp()
    InspectionProfileImpl.INIT_INSPECTIONS = true
    myFixture.project.pythonSdk = projectDescriptor.sdk
  }

  override fun tearDown() {
    InspectionProfileImpl.INIT_INSPECTIONS = false
    super.tearDown()
  }


  override fun getBasePath(): String {
    return super.getBasePath() + "inspections/"
  }
}