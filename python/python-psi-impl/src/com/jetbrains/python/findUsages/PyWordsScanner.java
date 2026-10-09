// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.findUsages;

import com.intellij.lang.cacheBuilder.DefaultWordsScanner;
import com.intellij.lang.cacheBuilder.VersionedWordsScanner;
import com.intellij.lang.cacheBuilder.WordOccurrence;
import com.intellij.psi.tree.TokenSet;
import com.intellij.util.Processor;
import com.jetbrains.python.PyTokenTypes;
import com.jetbrains.python.lexer.PythonLexer;
import org.jetbrains.annotations.NotNull;


class PyWordsScanner extends VersionedWordsScanner {
  private volatile DefaultWordsScanner myDelegate;

  @Override
  public void processWords(@NotNull CharSequence fileText, @NotNull Processor<? super WordOccurrence> processor) {
    DefaultWordsScanner delegate = myDelegate;
    if (delegate == null) {
      myDelegate = delegate = new DefaultWordsScanner(new PythonLexer(),
                                                      TokenSet.create(PyTokenTypes.IDENTIFIER),
                                                      TokenSet.create(PyTokenTypes.END_OF_LINE_COMMENT),
                                                      PyTokenTypes.STRING_NODES);
    }
    delegate.processWords(fileText, processor);
  }

  @Override
  public int getVersion() {
    return super.getVersion() + 1;
  }
}
