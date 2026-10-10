// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.psi.search;

import com.intellij.psi.search.searches.ExtensibleQueryFactory;
import com.intellij.util.Query;
import com.jetbrains.python.psi.PyClass;


public final class PyClassInheritorsSearch extends ExtensibleQueryFactory<PyClass, PyClassInheritorsSearch.SearchParameters> {
  public static final PyClassInheritorsSearch INSTANCE = new PyClassInheritorsSearch();

  public static class SearchParameters {
    private final PyClass mySuperClass;
    private final boolean myCheckDeepInheritance;

    public SearchParameters(final PyClass superClass, final boolean checkDeepInheritance) {
      mySuperClass = superClass;
      myCheckDeepInheritance = checkDeepInheritance;
    }

    public PyClass getSuperClass() {
      return mySuperClass;
    }

    public boolean isCheckDeepInheritance() {
      return myCheckDeepInheritance;
    }
  }

  private PyClassInheritorsSearch() {
    super("Pythonid");
  }

  public static Query<PyClass> search(final PyClass superClass, final boolean checkDeepInheritance) {
    final SearchParameters parameters = new SearchParameters(superClass, checkDeepInheritance);
    return INSTANCE.createUniqueResultsQuery(parameters);
  }
}
