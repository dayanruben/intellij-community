// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gradle.service.settings;

import com.intellij.openapi.externalSystem.service.settings.AbstractImportFromExternalSystemControl;
import com.intellij.openapi.externalSystem.util.ExternalSystemSettingsControl;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings;
import org.jetbrains.plugins.gradle.settings.GradleSettings;
import org.jetbrains.plugins.gradle.settings.GradleSettingsListener;
import org.jetbrains.plugins.gradle.util.GradleConstants;
import org.jetbrains.plugins.gradle.util.GradleUtil;

public class ImportFromGradleControl
  extends AbstractImportFromExternalSystemControl<GradleProjectSettings, GradleSettingsListener, GradleSettings>
{
  public ImportFromGradleControl() {
    super(GradleConstants.SYSTEM_ID, new GradleSettings(ProjectManager.getInstance().getDefaultProject()), getInitialProjectSettings(), true);
  }

  private static @NotNull GradleProjectSettings getInitialProjectSettings() {
    GradleProjectSettings result = new GradleProjectSettings();
    String gradleHome = GradleUtil.getLastUsedGradleHome();
    if (!StringUtil.isEmpty(gradleHome)) {
      result.setGradleHome(gradleHome);
    }
    return result;
  }
  
  @Override
  protected @NotNull ExternalSystemSettingsControl<GradleProjectSettings> createProjectSettingsControl(@NotNull GradleProjectSettings settings) {
    return new GradleProjectSettingsControl(settings);
  }

  @Override
  protected @Nullable ExternalSystemSettingsControl<GradleSettings> createSystemSettingsControl(@NotNull GradleSettings settings) {
    return new GradleSystemSettingsControl(settings);
  }

  @Override
  protected void onLinkedProjectPathChange(@NotNull String path) {
    ((GradleProjectSettingsControl)getProjectSettingsControl()).update(path, false);
  }

  @Override
  public void setCurrentProject(@Nullable Project currentProject) {
    super.setCurrentProject(currentProject);
    ((GradleProjectSettingsControl)getProjectSettingsControl()).setCurrentProject(currentProject);
  }
}
