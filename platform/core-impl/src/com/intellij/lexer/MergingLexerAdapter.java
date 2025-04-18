/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.lexer;

import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;

public class MergingLexerAdapter extends MergingLexerAdapterBase {
  private final TokenSet myTokenSet;
  private final MergeFunction myMergeFunction = new MyMergeFunction();

  public MergingLexerAdapter(Lexer original, TokenSet tokensToMerge){
    super(original);
    myTokenSet = tokensToMerge;
  }

  @Override
  public MergeFunction getMergeFunction() {
    return myMergeFunction;
  }

  private class MyMergeFunction implements MergeFunction {
      @Override
      public IElementType merge(IElementType type, Lexer originalLexer) {
        if (!myTokenSet.contains(type)) {
          return type;
        }

        while (true) {
          IElementType tokenType = originalLexer.getTokenType();
          if (tokenType != type) break;
          originalLexer.advance();
        }
        return type;
      }
  }
}