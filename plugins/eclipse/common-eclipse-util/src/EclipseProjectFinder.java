// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.idea.eclipse;

import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.Processor;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class EclipseProjectFinder implements EclipseXml {
  public static void findModuleRoots(final List<? super String> paths, final String rootPath, @Nullable Processor<? super String> progressUpdater) {
    if (progressUpdater != null) {
      progressUpdater.process(rootPath);
    }

    final String project = findProjectName(rootPath);
    if (project != null) {
      paths.add(rootPath);
    }

    final File root = new File(rootPath);
    if (root.isDirectory()) {
      final File[] files = root.listFiles();
      if (files != null) {
        for (File file : files) {
          findModuleRoots(paths, file.getPath(), progressUpdater);
        }
      }
    }
  }

  public static @Nullable String findProjectName(String rootPath) {
    String name = null;
    final File file = new File(rootPath, DOT_PROJECT_EXT);
    if (file.isFile()) {
      try {
        name = JDOMUtil.load(file).getChildText(NAME_TAG);
        if (StringUtil.isEmptyOrSpaces(name)) {
          return null;
        }
        name = name.replace("\n", " ").trim();
      }
      catch (JDOMException | IOException e) {
        return null;
      }
    }
    return name;
  }

  public static @Nullable LinkedResource findLinkedResource(@NotNull String projectPath, @NotNull String relativePath) {
    String independentPath = FileUtil.toSystemIndependentName(relativePath);
    @NotNull String resourceName = independentPath;
    final int idx = independentPath.indexOf('/');
    if (idx != -1) {
      resourceName = independentPath.substring(0, idx);
    }
    final File file = new File(projectPath, DOT_PROJECT_EXT);
    if (file.isFile()) {
      try {
        for (Element o : JDOMUtil.load(file).getChildren(LINKED_RESOURCES)) {
          for (Element l : o.getChildren(LINK)) {
            if (Comparing.strEqual(l.getChildText(NAME_TAG), resourceName)) {
              LinkedResource linkedResource = new LinkedResource();
              final String relativeToLinkedResourcePath =
                independentPath.length() > resourceName.length() ? independentPath.substring(resourceName.length()) : "";

              final Element locationURI = l.getChild("locationURI");
              if (locationURI != null) {
                linkedResource.setURI(FileUtil.toSystemIndependentName(locationURI.getText()) + relativeToLinkedResourcePath);
              }

              final Element location = l.getChild("location");
              if (location != null) {
                linkedResource.setLocation(FileUtil.toSystemIndependentName(location.getText()) + relativeToLinkedResourcePath);
              }
              return linkedResource;
            }
          }
        }
      }
      catch (Exception ignore) {
      }
    }
    return null;
  }

  public static class LinkedResource {
    private String myURI;
    private String myLocation;

    public @NotNull String getVariableName() {
      final int idx = myURI.indexOf('/');
      return idx > -1 ? myURI.substring(0, idx) : myURI;
    }

    public @Nullable String getRelativeToVariablePath() {
      final int idx = myURI.indexOf('/');
      return idx > -1 && idx + 1 < myURI.length() ? myURI.substring(idx + 1) : null;
    }

    public boolean containsPathVariable() {
      return myURI != null;
    }

    public void setURI(String URI) {
      myURI = URI;
    }

    public String getLocation() {
      return myLocation;
    }

    public void setLocation(String location) {
      myLocation = location;
    }
  }
}