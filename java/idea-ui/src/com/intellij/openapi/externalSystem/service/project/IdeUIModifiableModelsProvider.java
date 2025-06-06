// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.externalSystem.service.project;

import com.intellij.facet.ModifiableFacetModel;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.externalSystem.service.project.manage.ProjectStructureUIModifiableModelsProvider;
import com.intellij.openapi.module.ModifiableModuleModel;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTable;
import com.intellij.openapi.roots.ui.configuration.ModulesConfigurator;
import com.intellij.openapi.roots.ui.configuration.projectRoot.LibrariesModifiableModel;
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectLibrariesConfigurable;
import com.intellij.packaging.artifacts.ModifiableArtifactModel;
import com.intellij.platform.workspace.storage.MutableEntityStorage;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

public class IdeUIModifiableModelsProvider extends AbstractIdeModifiableModelsProvider implements
                                                                                       ProjectStructureUIModifiableModelsProvider {
  private final ModifiableModuleModel myModel;
  private final ModulesConfigurator myModulesConfigurator;
  private final ModifiableArtifactModel myModifiableArtifactModel;
  private final LibrariesModifiableModel myLibrariesModel;

  public IdeUIModifiableModelsProvider(Project project,
                                       ModifiableModuleModel model,
                                       ModulesConfigurator modulesConfigurator,
                                       ModifiableArtifactModel modifiableArtifactModel) {
    super(project);
    myModel = model;
    myModulesConfigurator = modulesConfigurator;
    myModifiableArtifactModel = modifiableArtifactModel;

    ProjectLibrariesConfigurable configurable = modulesConfigurator.getProjectStructureConfigurable().getProjectLibrariesConfigurable();
    myLibrariesModel = configurable.getModelProvider().getModifiableModel();
  }

  @Override
  public @NotNull LibraryTable.ModifiableModel getModifiableProjectLibrariesModel() {
    return myLibrariesModel;
  }

  public ModifiableArtifactModel getModifiableArtifactModel() {
    return myModifiableArtifactModel;
  }

  @Override
  protected ModifiableModuleModel doGetModifiableModuleModel() {
    return myModel;
  }

  @Override
  protected ModifiableRootModel doGetModifiableRootModel(Module module) {
    return myModulesConfigurator.getOrCreateModuleEditor(module).getModifiableRootModel();
  }

  @Override
  protected ModifiableFacetModel doGetModifiableFacetModel(Module module) {
    return (ModifiableFacetModel)myModulesConfigurator.getFacetModel(module);
  }

  @Override
  protected Library.ModifiableModel doGetModifiableLibraryModel(Library library) {
    return myLibrariesModel.getLibraryModifiableModel(library);
  }

  @Override
  public void commit() {
  }

  @Override
  public void dispose() {
  }

  @Override
  public ModalityState getModalityStateForQuestionDialogs() {
    return ModalityState.defaultModalityState();
  }

  @Override
  public @NotNull MutableEntityStorage getActualStorageBuilder() {
    return MutableEntityStorage.create();
  }

  @Override
  @ApiStatus.Internal
  public boolean isLibrarySubstituted(@NotNull Library library) {
    return false;
  }
}
