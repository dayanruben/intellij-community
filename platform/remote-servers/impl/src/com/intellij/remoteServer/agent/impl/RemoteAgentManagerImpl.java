// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.remoteServer.agent.impl;

import com.intellij.openapi.util.io.FileUtil;
import com.intellij.remoteServer.agent.RemoteAgent;
import com.intellij.remoteServer.agent.RemoteAgentManager;
import com.intellij.remoteServer.agent.RemoteAgentProxyFactory;
import com.intellij.util.Base64;
import com.intellij.util.PathUtil;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@ApiStatus.Internal
public class RemoteAgentManagerImpl extends RemoteAgentManager {

  private final RemoteAgentClassLoaderCache myClassLoaderCache = new RemoteAgentClassLoaderCache();

  @Override
  public <T extends RemoteAgent> T createAgent(RemoteAgentProxyFactory agentProxyFactory,
                                               List<Path> instanceLibraries,
                                               List<Class<?>> commonJarClasses,
                                               String specificsRuntimeModuleName,
                                               String specificsBuildJarPath,
                                               Class<T> agentInterface,
                                               String agentClassName,
                                               Class<?> pluginClass) throws Exception {

    Builder<T> builder = createAgentBuilder(agentProxyFactory, agentInterface, pluginClass)
      .withRtDependencies(commonJarClasses)
      .withInstanceLibraries(instanceLibraries)
      .withModuleDependency(specificsRuntimeModuleName, specificsBuildJarPath);

    return builder.buildAgent(agentClassName);
  }

  @Override
  public <T extends RemoteAgent> Builder<T> createAgentBuilder(@NotNull RemoteAgentProxyFactory agentProxyFactory,
                                                               @NotNull Class<T> agentInterface,
                                                               @NotNull Class<?> pluginClass) {
    return new AgentBuilderImpl<>(agentProxyFactory, agentInterface, pluginClass);
  }

  @Override
  public RemoteAgentProxyFactory createReflectiveThreadProxyFactory(ClassLoader callerClassLoader) {
    return new RemoteAgentReflectiveThreadProxyFactory(myClassLoaderCache, callerClassLoader);
  }

  private static class AgentBuilderImpl<T extends RemoteAgent> extends Builder<T> {
    private final List<Class<?>> myRtClasses = new ArrayList<>();
    private final List<Path> myInstanceLibraries = new ArrayList<>();
    private final List<Path> myModuleDependencies = new ArrayList<>();

    private final RemoteAgentProxyFactory myAgentProxyFactory;
    private final Class<T> myAgentInterface;

    private final String myAllPluginsRoot;
    private final boolean myRunningFromSources;

    AgentBuilderImpl(@NotNull RemoteAgentProxyFactory agentProxyFactory,
                            @NotNull Class<T> agentInterface,
                            @NotNull Class<?> pluginClass) {
      myAgentProxyFactory = agentProxyFactory;
      myAgentInterface = agentInterface;

      File plugin = new File(PathUtil.getJarPathForClass(pluginClass));
      myAllPluginsRoot = plugin.getParent();
      myRunningFromSources = plugin.isDirectory();
    }

    @Override
    public T buildAgent(@NotNull String agentClassName) throws Exception {
      @NotNull List<Path> libraries = listLibraryFiles();
      return myAgentProxyFactory.createProxy(libraries, myAgentInterface, agentClassName);
    }

    private @NotNull List<Path> listLibraryFiles() {
      List<Path> result = new ArrayList<>(myInstanceLibraries);

      List<Class<?>> allRtClasses = new ArrayList<>(myRtClasses);
      allRtClasses.add(RemoteAgent.class);
      allRtClasses.add(Base64.class);
      allRtClasses.add(myAgentInterface);

      for (Class<?> clazz : allRtClasses) {
        result.add(Path.of(PathUtil.getJarPathForClass(clazz)));
      }

      result.addAll(myModuleDependencies);

      return result;
    }

    @Override
    public Builder<T> withRtDependency(@NotNull Class<?> rtClass) {
      myRtClasses.add(rtClass);
      return this;
    }

    @Override
    public Builder<T> withInstanceLibraries(List<Path> libraries) {
      myInstanceLibraries.addAll(libraries);
      return this;
    }

    @Override
    public Builder<T> withModuleDependency(@NotNull String runtimeModuleName, @NotNull String buildPathToJar) {
      if (myRunningFromSources) {
        Path specificsModule = Path.of(myAllPluginsRoot).resolve(runtimeModuleName);
        myModuleDependencies.add(specificsModule);
      }
      else {
        Path specificsDir = Path.of(myAllPluginsRoot).resolve(FileUtil.toSystemDependentName(buildPathToJar));
        myModuleDependencies.add(specificsDir);
      }
      return this;
    }
  }
}
