// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.jetbrains.python.formatter;

import com.intellij.formatting.*;
import com.intellij.lang.ASTNode;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.impl.source.tree.LeafElement;
import com.intellij.psi.impl.source.tree.TreeUtil;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ObjectUtils;
import com.jetbrains.python.PyElementTypes;
import com.jetbrains.python.PyTokenTypes;
import com.jetbrains.python.PythonCodeStyleService;
import com.jetbrains.python.PythonDialectsTokenSetProvider;
import com.jetbrains.python.ast.*;
import com.jetbrains.python.ast.impl.PyPsiUtilsCore;
import com.jetbrains.python.ast.impl.PyUtilCore;
import com.jetbrains.python.pyi.PyiUtilCore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static com.jetbrains.python.formatter.PyCodeStyleSettings.DICT_ALIGNMENT_ON_COLON;
import static com.jetbrains.python.formatter.PyCodeStyleSettings.DICT_ALIGNMENT_ON_VALUE;


public class PyBlock implements ASTBlock {
  private static final TokenSet STATEMENT_OR_DECLARATION = PythonDialectsTokenSetProvider.getInstance().getStatementTokens();


  private static final TokenSet ourListElementTypes = TokenSet.create(PyElementTypes.LIST_LITERAL_EXPRESSION,
                                                                      PyElementTypes.LIST_COMP_EXPRESSION,
                                                                      PyElementTypes.DICT_LITERAL_EXPRESSION,
                                                                      PyElementTypes.DICT_COMP_EXPRESSION,
                                                                      PyElementTypes.SET_LITERAL_EXPRESSION,
                                                                      PyElementTypes.SET_COMP_EXPRESSION,
                                                                      PyElementTypes.ARGUMENT_LIST,
                                                                      PyElementTypes.PARAMETER_LIST,
                                                                      PyElementTypes.TUPLE_EXPRESSION,
                                                                      PyElementTypes.PARENTHESIZED_EXPRESSION,
                                                                      PyElementTypes.SUBSCRIPTION_EXPRESSION,
                                                                      PyElementTypes.GENERATOR_EXPRESSION,
                                                                      PyElementTypes.SEQUENCE_PATTERN,
                                                                      PyElementTypes.MAPPING_PATTERN,
                                                                      PyElementTypes.PATTERN_ARGUMENT_LIST,
                                                                      PyElementTypes.TYPE_PARAMETER_LIST);

  private static final TokenSet ourCollectionLiteralTypes = TokenSet.create(PyElementTypes.LIST_LITERAL_EXPRESSION,
                                                                            PyElementTypes.LIST_COMP_EXPRESSION,
                                                                            PyElementTypes.DICT_LITERAL_EXPRESSION,
                                                                            PyElementTypes.DICT_COMP_EXPRESSION,
                                                                            PyElementTypes.SET_LITERAL_EXPRESSION,
                                                                            PyElementTypes.SET_COMP_EXPRESSION);

  private static final TokenSet ourHangingIndentOwners = TokenSet.create(PyElementTypes.LIST_LITERAL_EXPRESSION,
                                                                         PyElementTypes.LIST_COMP_EXPRESSION,
                                                                         PyElementTypes.DICT_LITERAL_EXPRESSION,
                                                                         PyElementTypes.DICT_COMP_EXPRESSION,
                                                                         PyElementTypes.SET_LITERAL_EXPRESSION,
                                                                         PyElementTypes.SET_COMP_EXPRESSION,
                                                                         PyElementTypes.ARGUMENT_LIST,
                                                                         PyElementTypes.PARAMETER_LIST,
                                                                         PyElementTypes.TUPLE_EXPRESSION,
                                                                         PyElementTypes.PARENTHESIZED_EXPRESSION,
                                                                         PyElementTypes.GENERATOR_EXPRESSION,
                                                                         PyElementTypes.FUNCTION_DECLARATION,
                                                                         PyElementTypes.CALL_EXPRESSION,
                                                                         PyElementTypes.FROM_IMPORT_STATEMENT,
                                                                         PyElementTypes.SEQUENCE_PATTERN,
                                                                         PyElementTypes.MAPPING_PATTERN,
                                                                         PyElementTypes.PATTERN_ARGUMENT_LIST,
                                                                         PyElementTypes.WITH_STATEMENT,
                                                                         PyElementTypes.TYPE_PARAMETER_LIST);

  private static final boolean ALIGN_CONDITIONS_WITHOUT_PARENTHESES = false;

  private final PyBlock myParent;
  private final Alignment myAlignment;
  private final Indent myIndent;
  private final ASTNode myNode;
  private final Wrap myWrap;
  private final PyBlockContext myContext;
  private List<PyBlock> mySubBlocks = null;
  private Map<ASTNode, PyBlock> mySubBlockByNode = null;
  private final boolean myEmptySequence;

  // Shared among multiple children sub-blocks
  private Alignment myChildAlignment = null;
  private Alignment myDictAlignment = null;
  private Wrap myDictWrapping = null;
  private Wrap myListWrapping = null;
  private Wrap mySetWrapping = null;
  private Wrap myTupleWrapping = null;
  private Wrap myFromImportWrapping = null;
  private Wrap myParameterListWrapping = null;
  private Wrap myArgumentListWrapping = null;

  public PyBlock(@Nullable PyBlock parent,
                 @NotNull ASTNode node,
                 @Nullable Alignment alignment,
                 @NotNull Indent indent,
                 @Nullable Wrap wrap,
                 @NotNull PyBlockContext context) {
    myParent = parent;
    myAlignment = alignment;
    myIndent = indent;
    myNode = node;
    myWrap = wrap;
    myContext = context;
    myEmptySequence = isEmptySequence(node);

    final CommonCodeStyleSettings settings = myContext.getSettings();
    final PyCodeStyleSettings pySettings = myContext.getPySettings();
    if (node.getElementType() == PyElementTypes.DICT_LITERAL_EXPRESSION) {
      myDictAlignment = Alignment.createAlignment(true);
      myDictWrapping = Wrap.createWrap(pySettings.DICT_WRAPPING, true);
    }
    else if (node.getElementType() == PyElementTypes.LIST_LITERAL_EXPRESSION) {
      myListWrapping = Wrap.createWrap(pySettings.LIST_WRAPPING, pySettings.LIST_NEW_LINE_AFTER_LEFT_BRACKET);
    }
    else if (node.getElementType() == PyElementTypes.SET_LITERAL_EXPRESSION) {
      mySetWrapping = Wrap.createWrap(pySettings.SET_WRAPPING, pySettings.SET_NEW_LINE_AFTER_LEFT_BRACE);
    }
    else if (node.getElementType() == PyElementTypes.TUPLE_EXPRESSION &&
             node.getTreeParent().getElementType() == PyElementTypes.PARENTHESIZED_EXPRESSION) {
      myTupleWrapping = Wrap.createWrap(pySettings.TUPLE_WRAPPING, pySettings.TUPLE_NEW_LINE_AFTER_LEFT_PARENTHESIS);
    }
    else if (node.getElementType() == PyElementTypes.FROM_IMPORT_STATEMENT) {
      myFromImportWrapping = Wrap.createWrap(pySettings.FROM_IMPORT_WRAPPING, false);
    }
    else if (node.getElementType() == PyElementTypes.PARAMETER_LIST) {
      myParameterListWrapping = Wrap.createWrap(settings.METHOD_PARAMETERS_WRAP, settings.METHOD_PARAMETERS_LPAREN_ON_NEXT_LINE);
    }
    else if (node.getElementType() == PyElementTypes.ARGUMENT_LIST) {
      myArgumentListWrapping = Wrap.createWrap(settings.CALL_PARAMETERS_WRAP, settings.CALL_PARAMETERS_LPAREN_ON_NEXT_LINE);
    }
  }

  @Override
  public @NotNull ASTNode getNode() {
    return myNode;
  }

  @Override
  public @NotNull TextRange getTextRange() {
    return myNode.getTextRange();
  }

  private Alignment getAlignmentForChildren() {
    if (myChildAlignment == null) {
      myChildAlignment = Alignment.createAlignment();
    }
    return myChildAlignment;
  }

  @Override
  public @NotNull List<Block> getSubBlocks() {
    if (mySubBlocks == null) {
      mySubBlockByNode = buildSubBlocks();
      mySubBlocks = new ArrayList<>(mySubBlockByNode.values());
    }
    return Collections.unmodifiableList(mySubBlocks);
  }

  private @Nullable PyBlock getSubBlockByNode(@NotNull ASTNode node) {
    return mySubBlockByNode.get(node);
  }

  private @Nullable PyBlock getSubBlockByIndex(int index) {
    return mySubBlocks.get(index);
  }

  private @NotNull Map<ASTNode, PyBlock> buildSubBlocks() {
    final Map<ASTNode, PyBlock> blocks = new LinkedHashMap<>();
    for (ASTNode child: getSubBlockNodes()) {

      final IElementType childType = child.getElementType();

      if (child.getTextRange().isEmpty()) continue;

      if (childType == TokenType.WHITE_SPACE) {
        continue;
      }

      blocks.put(child, buildSubBlock(child));
    }
    return Collections.unmodifiableMap(blocks);
  }

  protected @NotNull Iterable<ASTNode> getSubBlockNodes() {
    if (myNode.getElementType() == PyElementTypes.BINARY_EXPRESSION) {
      final ArrayList<ASTNode> result = new ArrayList<>();
      collectChildrenOperatorAndOperandNodes(myNode, result);
      return result;
    }
    return Arrays.asList(myNode.getChildren(null));
  }

  private static void collectChildrenOperatorAndOperandNodes(@NotNull ASTNode node, @NotNull List<ASTNode> result) {
    if (node.getElementType() == PyElementTypes.BINARY_EXPRESSION) {
      for (ASTNode child : node.getChildren(null)) {
        collectChildrenOperatorAndOperandNodes(child, result);
      }
    }
    else {
      result.add(node);
    }
  }

  private @NotNull PyBlock buildSubBlock(@NotNull ASTNode child) {
    final IElementType parentType = myNode.getElementType();

    final ASTNode grandParentNode = myNode.getTreeParent();
    final IElementType grandparentType = grandParentNode == null ? null : grandParentNode.getElementType();

    final IElementType childType = child.getElementType();
    Wrap childWrap = null;
    Indent childIndent = Indent.getNoneIndent();
    Alignment childAlignment = null;

    final PyCodeStyleSettings settings = myContext.getPySettings();

    if (childType == PyElementTypes.STATEMENT_LIST) {
      if (hasLineBreaksBeforeInSameParent(child, 1) || needLineBreakInStatement()) {
        childIndent = Indent.getNormalIndent();
      }
    }
    else if (childType == PyElementTypes.CASE_CLAUSE) {
      childIndent = Indent.getNormalIndent();
    }
    else if (childType == PyElementTypes.IMPORT_ELEMENT) {
      if (parentType == PyElementTypes.FROM_IMPORT_STATEMENT) {
        childWrap = myFromImportWrapping;
      }
      else {
        childWrap = Wrap.createWrap(WrapType.NORMAL, true);
      }
      childIndent = Indent.getNormalIndent();
    }
    if (childType == PyTokenTypes.END_OF_LINE_COMMENT && (parentType == PyElementTypes.FROM_IMPORT_STATEMENT ||
                                                          parentType == PyElementTypes.MATCH_STATEMENT)) {
      childIndent = Indent.getNormalIndent();
    }

    // TODO merge it with the following check of needListAlignment()
    if (ourListElementTypes.contains(parentType)) {
      // wrapping in non-parenthesized tuple expression is not allowed (PY-1792)
      if (!(childType == PyElementTypes.TUPLE_EXPRESSION && parentType == PyElementTypes.PARENTHESIZED_EXPRESSION) &&
          (parentType != PyElementTypes.TUPLE_EXPRESSION || grandparentType == PyElementTypes.PARENTHESIZED_EXPRESSION) &&
          !PyTokenTypes.ALL_BRACES.contains(childType) &&
          childType != PyTokenTypes.COMMA &&
          !isSliceOperand(child) /*&& !isSubscriptionOperand(child)*/) {
        childWrap = Wrap.createWrap(WrapType.NORMAL, true);
      }
      if (needListAlignment(child) && !myEmptySequence) {
        childAlignment = getAlignmentForChildren();
      }
      if (childType == PyTokenTypes.END_OF_LINE_COMMENT) {
        childIndent = Indent.getNormalIndent();
      }
    }

    if (parentType == PyElementTypes.BINARY_EXPRESSION) {
      if (childType != PyElementTypes.BINARY_EXPRESSION) {
        final PyBlock topmostBinary = findTopmostBinaryExpressionBlock(child);
        assert topmostBinary != null;
        final PyBlock binaryParentBlock = topmostBinary.myParent;
        final ASTNode binaryParentNode = binaryParentBlock.myNode;
        final IElementType binaryParentType = binaryParentNode.getElementType();
        // TODO check for comprehensions explicitly here
        if (ourListElementTypes.contains(binaryParentType) && needListAlignment(child) && !myEmptySequence) {
          childAlignment = binaryParentBlock.getChildAlignment();
        }
        final boolean parenthesised = binaryParentType == PyElementTypes.PARENTHESIZED_EXPRESSION;
        if (binaryParentType != PyElementTypes.RETURN_STATEMENT &&
            binaryParentType != PyElementTypes.YIELD_EXPRESSION) {
          if (childAlignment == null && topmostBinary != null &&
              !(parenthesised && isIfCondition(binaryParentNode)) &&
              !(isCondition(topmostBinary.myNode) && !ALIGN_CONDITIONS_WITHOUT_PARENTHESES)) {
            childAlignment = topmostBinary.getAlignmentForChildren();
          }
          // We omit indentation for the binary expression itself in this case (similarly to PyTupleExpression inside
          // PyParenthesisedExpression) because we indent individual operands and operators inside rather than
          // the whole contained expression.
          childIndent = parenthesised ? Indent.getContinuationIndent() : Indent.getContinuationWithoutFirstIndent();
        }
        else {
          childIndent = Indent.getNormalIndent();
        }
      }
    }
    else if (parentType == PyElementTypes.OR_PATTERN) {
      childAlignment = getAlignmentForChildren();
    }
    else if (parentType == PyElementTypes.SEQUENCE_PATTERN || parentType == PyElementTypes.MAPPING_PATTERN) {
      if (PyTokenTypes.CLOSE_BRACES.contains(childType) && !settings.HANG_CLOSING_BRACKETS ||
          PyTokenTypes.OPEN_BRACES.contains(childType)) {
        childIndent = Indent.getNoneIndent();
      }
      else {
        childIndent = Indent.getNormalIndent();
      }
    }
    else if (ourCollectionLiteralTypes.contains(parentType) ||
             parentType == PyElementTypes.TUPLE_EXPRESSION && grandparentType == PyElementTypes.PARENTHESIZED_EXPRESSION) {
      if ((PyTokenTypes.CLOSE_BRACES.contains(childType) && !settings.HANG_CLOSING_BRACKETS) || 
          PyTokenTypes.OPEN_BRACES.contains(childType)) {
        childIndent = Indent.getNoneIndent();
      }
      else if (settings.USE_CONTINUATION_INDENT_FOR_COLLECTION_AND_COMPREHENSIONS) {
        childIndent = Indent.getContinuationIndent();
      }
      else {
        childIndent = Indent.getNormalIndent();
      }
    }
    else if (parentType == PyElementTypes.STRING_LITERAL_EXPRESSION) {
      if (PyTokenTypes.STRING_NODES.contains(childType) || childType == PyElementTypes.FSTRING_NODE) {
        if (childType == PyTokenTypes.TRIPLE_QUOTED_STRING &&
            myContext.getPySettings().FORMAT_INJECTED_FRAGMENTS &&
            isInsideInjection(child)) {
          return createInjectedBlock(child, myContext.getPySettings().ADD_INDENT_INSIDE_INJECTIONS
                                            ? Indent.getNormalIndent()
                                            : Indent.getNoneIndent());
        }
        childAlignment = getAlignmentForChildren();
      }
    }
    else if (parentType == PyElementTypes.FROM_IMPORT_STATEMENT) {
      if (myNode.findChildByType(PyTokenTypes.LPAR) != null) {
        if (childType == PyElementTypes.IMPORT_ELEMENT) {
          if (settings.ALIGN_MULTILINE_IMPORTS) {
            childAlignment = getAlignmentForChildren();
          }
          else {
            childIndent = Indent.getNormalIndent();
          }
        }
        if (childType == PyTokenTypes.RPAR) {
          childIndent = Indent.getNoneIndent();
          // Don't have hanging indent and is not going to have it due to the setting about opening parenthesis
          if (!hasHangingIndent(myNode.getPsi()) && !settings.FROM_IMPORT_NEW_LINE_AFTER_LEFT_PARENTHESIS) {
            childAlignment = getAlignmentForChildren();
          }
          else if (settings.HANG_CLOSING_BRACKETS) {
            childIndent = Indent.getNormalIndent();
          }
        }
      }
    }
    else if (isValueOfKeyValuePair(child)) {
      childIndent = Indent.getNormalIndent();
    }
    // TODO: merge with needChildAlignment()
    //Align elements vertically if there is an argument in the first line of parenthesized expression
    else if (!hasHangingIndent(myNode.getPsi()) &&
             (parentType == PyElementTypes.PARENTHESIZED_EXPRESSION ||
              (parentType == PyElementTypes.ARGUMENT_LIST && myContext.getSettings().ALIGN_MULTILINE_PARAMETERS_IN_CALLS) ||
              (parentType == PyElementTypes.PARAMETER_LIST && myContext.getSettings().ALIGN_MULTILINE_PARAMETERS)) &&
             !isIndentNext(child) &&
             !hasLineBreaksBeforeInSameParent(myNode.getFirstChildNode(), 1) &&
             !ourListElementTypes.contains(childType)) {

      if (!PyTokenTypes.ALL_BRACES.contains(childType)) {
        childAlignment = getAlignmentForChildren();
        childIndent = Indent.getNormalIndent();
      }
      else if (childType == PyTokenTypes.RPAR) {
        if (settings.HANG_CLOSING_BRACKETS) {
          childIndent = settings.USE_CONTINUATION_INDENT_FOR_COLLECTION_AND_COMPREHENSIONS ? Indent.getContinuationIndent() : Indent.getNormalIndent();
        } else {
          childIndent = Indent.getNoneIndent();
        }
      }
    }
    // Note that colons are aligned together with bounds and stride
    else if (myNode.getElementType() == PyElementTypes.SLICE_ITEM) {
      childAlignment = getChildAlignment();
    }
    else if (parentType == PyElementTypes.GENERATOR_EXPRESSION || parentType == PyElementTypes.PARENTHESIZED_EXPRESSION) {
      if (childType == PyElementTypes.TUPLE_EXPRESSION) {
        if (isIndentNext(child.getTreeParent())) {
          childIndent = Indent.getNormalIndent();
        }
      } else {
        final boolean tupleOrGenerator = parentType == PyElementTypes.GENERATOR_EXPRESSION ||
                                         myNode.getPsi(PyAstParenthesizedExpression.class)
                                           .getContainedExpression() instanceof PyAstTupleExpression;
        if ((childType == PyTokenTypes.RPAR && !(tupleOrGenerator && settings.HANG_CLOSING_BRACKETS)) ||
            !hasLineBreaksBeforeInSameParent(child, 1)) {
          childIndent = Indent.getNoneIndent();
        }
        // Operands and operators of a binary expression have their own indent, no need to increase it by indenting the expression itself
        else if (childType == PyElementTypes.BINARY_EXPRESSION && parentType == PyElementTypes.PARENTHESIZED_EXPRESSION) {
          childIndent = Indent.getNoneIndent();
        }
        else {
          final boolean useWiderIndent = isIndentNext(child) || settings.USE_CONTINUATION_INDENT_FOR_COLLECTION_AND_COMPREHENSIONS;
          childIndent = useWiderIndent ? Indent.getContinuationIndent() : Indent.getNormalIndent();
        }
      }
    }
    else if (parentType == PyElementTypes.ARGUMENT_LIST ||
             parentType == PyElementTypes.PATTERN_ARGUMENT_LIST ||
             parentType == PyElementTypes.PARAMETER_LIST) {
      if (childType == PyTokenTypes.RPAR && !settings.HANG_CLOSING_BRACKETS) {
        childIndent = Indent.getNoneIndent();
      }
      else if ((parentType == PyElementTypes.PARAMETER_LIST && settings.USE_CONTINUATION_INDENT_FOR_PARAMETERS) ||
               (parentType == PyElementTypes.ARGUMENT_LIST && settings.USE_CONTINUATION_INDENT_FOR_ARGUMENTS) ||
               argumentMayHaveSameIndentAsFollowingStatementList()) {
        childIndent = Indent.getContinuationIndent();
      }
      else {
        childIndent = Indent.getNormalIndent();
      }
    }
    else if (parentType == PyElementTypes.SUBSCRIPTION_EXPRESSION) {
      final PyAstExpression indexExpression = ((PyAstSubscriptionExpression)myNode.getPsi()).getIndexExpression();
      if (indexExpression != null && child == indexExpression.getNode()) {
        childIndent = Indent.getNormalIndent();
      }
    }
    else if (parentType == PyElementTypes.REFERENCE_EXPRESSION) {
      if (child != myNode.getFirstChildNode()) {
        childIndent = Indent.getNoneIndent();
        if (hasLineBreaksBeforeInSameParent(child, 1)) {
          if (isInControlStatement()) {
            childIndent = Indent.getContinuationIndent();
          }
          else {
            PyBlock b = myParent;
            while (b != null) {
              if (b.getNode().getPsi() instanceof PyAstParenthesizedExpression ||
                  b.getNode().getPsi() instanceof PyAstArgumentList ||
                  b.getNode().getPsi() instanceof PyAstParameterList) {
                childAlignment = getAlignmentOfChild(b, 1);
                break;
              }
              b = b.myParent;
            }
          }
        }
      }
    }
    childWrap = getSpecialWrapForContainers(child, childType, childWrap, parentType);

    if (isAfterStatementList(child) &&
        !hasLineBreaksBeforeInSameParent(child, 2) &&
        child.getElementType() != PyTokenTypes.END_OF_LINE_COMMENT) {
      // maybe enter was pressed and cut us from a previous (nested) statement list
      childIndent = Indent.getNormalIndent();
    }

    if (settings.DICT_ALIGNMENT == DICT_ALIGNMENT_ON_VALUE) {
      // Align not the whole value but its left bracket if it starts with it
      if (isValueOfKeyValuePairOfDictLiteral(child) && !isOpeningBracket(child.getFirstChildNode())) {
        childAlignment = myParent.myDictAlignment;
      }
      else if (isValueOfKeyValuePairOfDictLiteral(myNode) && isOpeningBracket(child)) {
        childAlignment = myParent.myParent.myDictAlignment;
      }
    }
    else if (myContext.getPySettings().DICT_ALIGNMENT == DICT_ALIGNMENT_ON_COLON) {
      if (isChildOfKeyValuePairOfDictLiteral(child) && childType == PyTokenTypes.COLON) {
        childAlignment = myParent.myDictAlignment;
      }
    }

    if (parentType == PyElementTypes.WITH_STATEMENT && isInsideWithStatementParentheses(myNode, child)) {
      if (needListAlignment(child)) {
        childAlignment = getAlignmentForChildren();
      }
      else {
        childIndent = Indent.getNormalIndent();
      }
      if (childType == PyTokenTypes.RPAR && !settings.HANG_CLOSING_BRACKETS) {
        childIndent = Indent.getNoneIndent();
      }
    }

    ASTNode prev = child.getTreePrev();
    while (prev != null && prev.getElementType() == TokenType.WHITE_SPACE) {
      if (prev.textContains('\\') &&
          !childIndent.equals(Indent.getContinuationIndent(false)) &&
          !childIndent.equals(Indent.getContinuationIndent(true))) {
        childIndent = isIndentNext(child) ? Indent.getContinuationIndent() : Indent.getNormalIndent();
        break;
      }
      prev = prev.getTreePrev();
    }

    // Don't wrap anything inside f-string fragments
    if (TreeUtil.findParent(child, PyElementTypes.FSTRING_FRAGMENT) != null) {
      childWrap = Wrap.createWrap(WrapType.NONE, false);
    }

    return new PyBlock(this, child, childAlignment, childIndent, childWrap, myContext);
  }

  private @Nullable Wrap getSpecialWrapForContainers(@NotNull ASTNode child,
                                           @NotNull IElementType childType,
                                           @Nullable Wrap childWrap,
                                           @Nullable IElementType parentType) {
    if (childType != PyTokenTypes.COMMA && childType != PyTokenTypes.END_OF_LINE_COMMENT) {
      // (...)
      if (childType != PyTokenTypes.LPAR && childType != PyTokenTypes.RPAR) {
        if (parentType == PyElementTypes.PARAMETER_LIST) return myParameterListWrapping;
        if (parentType == PyElementTypes.ARGUMENT_LIST) return myArgumentListWrapping;
        if (parentType == PyElementTypes.TUPLE_EXPRESSION && !isInsideSubscriptionExpression(child)) return myTupleWrapping;
      }
      // [...]
      if (childType != PyTokenTypes.LBRACKET && childType != PyTokenTypes.RBRACKET) {
        if (parentType == PyElementTypes.LIST_LITERAL_EXPRESSION && !isInsideSubscriptionExpression(child)) return myListWrapping;
      }
      // {...}
      if (childType != PyTokenTypes.LBRACE && childType != PyTokenTypes.RBRACE) {
        if (parentType == PyElementTypes.SET_LITERAL_EXPRESSION) return mySetWrapping;
      }
    }
    if (childType == PyElementTypes.KEY_VALUE_EXPRESSION && isChildOfDictLiteral(child)) {
      return myDictWrapping;
    }
    return childWrap;
  }

  private static boolean isInsideSubscriptionExpression(ASTNode child) {
    return PsiTreeUtil.getParentOfType(child.getPsi(), PyAstSubscriptionExpression.class) != null;
  }

  private static boolean isInsideWithStatementParentheses(@NotNull ASTNode withStatement, @NotNull ASTNode node) {
    ASTNode openingParenthesis = withStatement.findChildByType(PyTokenTypes.LPAR);
    if (openingParenthesis == null) {
      return false;
    }
    if (node.getStartOffset() < openingParenthesis.getStartOffset()) {
      return false;
    }
    ASTNode closingParenthesis = withStatement.findChildByType(PyTokenTypes.RPAR);
    if (closingParenthesis != null) {
      return node.getStartOffset() <= closingParenthesis.getStartOffset();
    }
    ASTNode afterParentheses = ObjectUtils.chooseNotNull(withStatement.findChildByType(PyTokenTypes.COLON),
                                                         withStatement.findChildByType(PyElementTypes.STATEMENT_LIST));
    return afterParentheses == null || node.getStartOffset() < afterParentheses.getStartOffset();
  }

  private static boolean isIfCondition(@NotNull ASTNode node) {
    @NotNull PsiElement element = node.getPsi();
    final PyAstIfPart ifPart = ObjectUtils.tryCast(element.getParent(), PyAstIfPart.class);
    return ifPart != null && ifPart.getCondition() == element && !ifPart.isElif();
  }

  private static boolean isCondition(@NotNull ASTNode node) {
    final PsiElement element = node.getPsi();
    final PyAstConditionalStatementPart conditionalStatement =
      ObjectUtils.tryCast(element.getParent(), PyAstConditionalStatementPart.class);
    return conditionalStatement != null && conditionalStatement.getCondition() == element;
  }

  private @Nullable PyBlock findTopmostBinaryExpressionBlock(@NotNull ASTNode child) {
    assert child.getElementType() != PyElementTypes.BINARY_EXPRESSION;
    PyBlock parentBlock = this;
    PyBlock alignmentOwner = null;
    while (parentBlock != null && parentBlock.myNode.getElementType() == PyElementTypes.BINARY_EXPRESSION) {
      alignmentOwner = parentBlock;
      parentBlock = parentBlock.myParent;
    }
    return alignmentOwner;
  }

  private static boolean isOpeningBracket(@Nullable ASTNode node) {
    return node != null && PyTokenTypes.OPEN_BRACES.contains(node.getElementType()) && node == node.getTreeParent().getFirstChildNode();
  }

  private static boolean isValueOfKeyValuePairOfDictLiteral(@NotNull ASTNode node) {
    return isValueOfKeyValuePair(node) && isChildOfDictLiteral(node.getTreeParent());
  }

  private static boolean isChildOfKeyValuePairOfDictLiteral(@NotNull ASTNode node) {
    return isChildOfKeyValuePair(node) && isChildOfDictLiteral(node.getTreeParent());
  }

  private static boolean isChildOfDictLiteral(@NotNull ASTNode node) {
    final ASTNode nodeParent = node.getTreeParent();
    return nodeParent != null && nodeParent.getElementType() == PyElementTypes.DICT_LITERAL_EXPRESSION;
  }

  private static boolean isChildOfKeyValuePair(@NotNull ASTNode node) {
    final ASTNode nodeParent = node.getTreeParent();
    return nodeParent != null && nodeParent.getElementType() == PyElementTypes.KEY_VALUE_EXPRESSION;
  }

  private static boolean isValueOfKeyValuePair(@NotNull ASTNode node) {
    return isChildOfKeyValuePair(node) && node.getTreeParent().getPsi(PyAstKeyValueExpression.class).getValue() == node.getPsi();
  }

  private static boolean isEmptySequence(@NotNull ASTNode node) {
    return node.getPsi() instanceof PyAstSequenceExpression && ((PyAstSequenceExpression)node.getPsi()).isEmpty();
  }

  private boolean argumentMayHaveSameIndentAsFollowingStatementList() {
    if (myNode.getElementType() != PyElementTypes.ARGUMENT_LIST) {
      return false;
    }
    // This check is supposed to prevent PEP8's error: Continuation line with the same indent as next logical line
    final PsiElement header = getControlStatementHeader(myNode);
    if (header instanceof PyAstStatementListContainer) {
      final PyAstStatementList statementList = ((PyAstStatementListContainer)header).getStatementList();
      return PyUtilCore.onSameLine(header, myNode.getPsi()) && !PyUtilCore.onSameLine(header, statementList);
    }
    return false;
  }

  // Check https://www.python.org/dev/peps/pep-0008/#indentation
  private static boolean hasHangingIndent(@NotNull PsiElement elem) {
    if (elem instanceof PyAstCallExpression) {
      final PyAstArgumentList argumentList = ((PyAstCallExpression)elem).getArgumentList();
      return argumentList != null && hasHangingIndent(argumentList);
    }
    else if (elem instanceof PyAstFunction) {
      return hasHangingIndent(((PyAstFunction)elem).getParameterList());
    }

    final PsiElement firstChild;
    if (elem instanceof PyAstFromImportStatement) {
      firstChild = ((PyAstFromImportStatement)elem).getLeftParen();
    }
    else if (elem instanceof PyAstWithStatement) {
      firstChild = PyPsiUtilsCore.getFirstChildOfType(elem, PyTokenTypes.LPAR);
    }
    else {
      firstChild = elem.getFirstChild();
    }
    if (firstChild == null) {
      return false;
    }
    final IElementType elementType = elem.getNode().getElementType();
    final ASTNode firstChildNode = firstChild.getNode();
    if (ourHangingIndentOwners.contains(elementType) && PyTokenTypes.OPEN_BRACES.contains(firstChildNode.getElementType())) {
      if (hasLineBreakAfterIgnoringComments(firstChildNode)) {
        return true;
      }
      final PsiElement firstItem = getFirstItem(elem);
      if (firstItem == null) {
        return !PyTokenTypes.CLOSE_BRACES.contains(elem.getLastChild().getNode().getElementType());
      }
      else {
        if (firstItem instanceof PyAstNamedParameter) {
          final PyAstExpression defaultValue = ((PyAstNamedParameter)firstItem).getDefaultValue();
          return defaultValue != null && hasHangingIndent(defaultValue);
        }
        else if (firstItem instanceof PyAstKeywordArgument) {
          final PyAstExpression valueExpression = ((PyAstKeywordArgument)firstItem).getValueExpression();
          return valueExpression != null && hasHangingIndent(valueExpression);
        }
        else if (firstItem instanceof PyAstKeyValueExpression) {
          final PyAstExpression value = ((PyAstKeyValueExpression)firstItem).getValue();
          return value != null && hasHangingIndent(value);
        }
        else if (firstItem instanceof PyAstWithItem) {
          PyAstExpression contextExpression = ((PyAstWithItem)firstItem).getExpression();
          return hasHangingIndent(contextExpression);
        }
        return hasHangingIndent(firstItem);
      }
    }
    else {
      return false;
    }
  }

  private static @Nullable PsiElement getFirstItem(@NotNull PsiElement elem) {
    PsiElement[] items = PsiElement.EMPTY_ARRAY;
    if (elem instanceof PyAstSequenceExpression) {
      items = ((PyAstSequenceExpression)elem).getElements();
    }
    else if (elem instanceof PyAstParameterList) {
      items = ((PyAstParameterList)elem).getParameters();
    }
    else if (elem instanceof PyAstArgumentList) {
      items = ((PyAstArgumentList)elem).getArguments();
    }
    else if (elem instanceof PyAstFromImportStatement) {
      items = ((PyAstFromImportStatement)elem).getImportElements();
    }
    else if (elem instanceof PyAstWithStatement) {
      items = ((PyAstWithStatement)elem).getWithItems();
    }
    else if (elem instanceof PyAstParenthesizedExpression) {
      final PyAstExpression containedExpression = ((PyAstParenthesizedExpression)elem).getContainedExpression();
      if (containedExpression instanceof PyAstTupleExpression) {
        items = ((PyAstTupleExpression)containedExpression).getElements();
      }
      else if (containedExpression != null) {
        return containedExpression;
      }
    }
    else if (elem instanceof PyAstComprehensionElement) {
      return ((PyAstComprehensionElement)elem).getResultExpression();
    }
    return ArrayUtil.getFirstElement(items);
  }

  private static boolean breaksAlignment(IElementType type) {
    return type != PyElementTypes.BINARY_EXPRESSION;
  }

  private static Alignment getAlignmentOfChild(@NotNull PyBlock b, int childNum) {
    if (b.getSubBlocks().size() > childNum) {
      final ChildAttributes attributes = b.getChildAttributes(childNum);
      return attributes.getAlignment();
    }
    return null;
  }

  private static boolean isIndentNext(@NotNull ASTNode child) {
    final PsiElement psi = PsiTreeUtil.getParentOfType(child.getPsi(), PyAstStatement.class);

    return psi instanceof PyAstIfStatement ||
           psi instanceof PyAstForStatement ||
           psi instanceof PyAstWithStatement ||
           psi instanceof PyAstClass ||
           psi instanceof PyAstFunction ||
           psi instanceof PyAstTryExceptStatement ||
           psi instanceof PyAstElsePart ||
           psi instanceof PyAstIfPart ||
           psi instanceof PyAstWhileStatement;
  }

  private static boolean isSubscriptionOperand(@NotNull ASTNode child) {
    return child.getTreeParent().getElementType() == PyElementTypes.SUBSCRIPTION_EXPRESSION &&
           child.getPsi() == ((PyAstSubscriptionExpression)child.getTreeParent().getPsi()).getOperand();
  }

  private boolean isInControlStatement() {
    return getControlStatementHeader(myNode) != null;
  }

  private static @Nullable PsiElement getControlStatementHeader(@NotNull ASTNode node) {
    final PyAstStatementPart statementPart = PsiTreeUtil.getParentOfType(node.getPsi(), PyAstStatementPart.class, false, PyAstStatementList.class);
    if (statementPart != null) {
      return statementPart;
    }
    final PyAstWithItem withItem = PsiTreeUtil.getParentOfType(node.getPsi(), PyAstWithItem.class);
    if (withItem != null) {
      return withItem.getParent();
    }
    return null;
  }

  private boolean isSliceOperand(@NotNull ASTNode child) {
    if (myNode.getPsi() instanceof PyAstSubscriptionExpression subscription &&
        subscription.getIndexExpression() instanceof PyAstSliceItem) {
      final PyAstExpression operand = subscription.getOperand();
      return operand.getNode() == child;
    }
    return false;
  }

  private static boolean isAfterStatementList(@NotNull ASTNode child) {
    final PsiElement prev = child.getPsi().getPrevSibling();
    if (!(prev instanceof PyAstStatement)) {
      return false;
    }
    final PsiElement lastChild = PsiTreeUtil.getDeepestLast(prev);
    return lastChild.getParent() instanceof PyAstStatementList;
  }

  private boolean needListAlignment(@NotNull ASTNode child) {
    final IElementType childType = child.getElementType();
    if (PyTokenTypes.OPEN_BRACES.contains(childType)) {
      return false;
    }
    if (PyTokenTypes.CLOSE_BRACES.contains(childType)) {
      final ASTNode prevNonSpace = findPrevNonSpaceNode(child);
      if (prevNonSpace != null &&
          prevNonSpace.getElementType() == PyTokenTypes.COMMA &&
          myContext.getMode() == FormattingMode.ADJUST_INDENT) {
        return true;
      }
      return !hasHangingIndent(myNode.getPsi())
             && !(myNode.getElementType() == PyElementTypes.DICT_LITERAL_EXPRESSION &&
                  myContext.getPySettings().DICT_NEW_LINE_AFTER_LEFT_BRACE)
             && !(myNode.getElementType() == PyElementTypes.LIST_LITERAL_EXPRESSION &&
                  myContext.getPySettings().LIST_NEW_LINE_AFTER_LEFT_BRACKET)
             && !(myNode.getElementType() == PyElementTypes.SET_LITERAL_EXPRESSION &&
                  myContext.getPySettings().SET_NEW_LINE_AFTER_LEFT_BRACE)
             && !(myNode.getElementType() == PyElementTypes.PARENTHESIZED_EXPRESSION &&
                  prevNonSpace != null && prevNonSpace.getElementType() == PyElementTypes.TUPLE_EXPRESSION &&
                  myContext.getPySettings().TUPLE_NEW_LINE_AFTER_LEFT_PARENTHESIS);
    }
    if (myNode.getElementType() == PyElementTypes.ARGUMENT_LIST) {
      if (!myContext.getSettings().ALIGN_MULTILINE_PARAMETERS_IN_CALLS || hasHangingIndent(myNode.getPsi())) {
        return false;
      }
      if (childType == PyTokenTypes.COMMA) {
        return false;
      }
      return true;
    }
    if (myNode.getElementType() == PyElementTypes.PARAMETER_LIST) {
      return !hasHangingIndent(myNode.getPsi()) && myContext.getSettings().ALIGN_MULTILINE_PARAMETERS;
    }
    if (myNode.getElementType() == PyElementTypes.SUBSCRIPTION_EXPRESSION) {
      return false;
    }
    if (childType == PyTokenTypes.COMMA) {
      return false;
    }
    if (myNode.getElementType() == PyElementTypes.PARENTHESIZED_EXPRESSION) {
      if (childType == PyElementTypes.STRING_LITERAL_EXPRESSION) {
        return true;
      }
      if (childType != PyElementTypes.TUPLE_EXPRESSION && childType != PyElementTypes.GENERATOR_EXPRESSION) {
        return false;
      }
    }
    if (myNode.getElementType() == PyElementTypes.WITH_STATEMENT) {
      return myNode.findChildByType(PyTokenTypes.LPAR) != null && !hasHangingIndent(myNode.getPsi());
    }
    if (myNode.getElementType() == PyElementTypes.TUPLE_EXPRESSION) {
      final ASTNode prevNode = myNode.getTreeParent();
      if (prevNode.getElementType() != PyElementTypes.PARENTHESIZED_EXPRESSION) {
        return false;
      }
    }
    return myContext.getPySettings().ALIGN_COLLECTIONS_AND_COMPREHENSIONS && !hasHangingIndent(myNode.getPsi());
  }

  private static @Nullable ASTNode findPrevNonSpaceNode(@NotNull ASTNode node) {
    do {
      node = node.getTreePrev();
    }
    while (isWhitespace(node));
    return node;
  }

  private static boolean isWhitespace(@Nullable ASTNode node) {
    return node != null && (node.getElementType() == TokenType.WHITE_SPACE || PyTokenTypes.WHITESPACE.contains(node.getElementType()));
  }

  private static boolean hasLineBreaksBeforeInSameParent(@NotNull ASTNode node, int minCount) {
    final ASTNode treePrev = node.getTreePrev();
    return (treePrev != null && isWhitespaceWithLineBreaks(TreeUtil.findLastLeaf(treePrev), minCount)) ||
           // Can happen, e.g. when you delete a statement from the beginning of a statement list
           isWhitespaceWithLineBreaks(node.getFirstChildNode(), minCount);
  }

  private static boolean hasLineBreakAfterIgnoringComments(@NotNull ASTNode node) {
    for (ASTNode next = TreeUtil.nextLeaf(node); next != null; next = TreeUtil.nextLeaf(next)) {
      if (isWhitespace(next)) {
        if (next.textContains('\n')) {
          return true;
        }
      }
      else if (next.getElementType() == PyTokenTypes.END_OF_LINE_COMMENT) {
        return true;
      }
      else {
        break;
      }
    }
    return false;
  }

  private static boolean isWhitespaceWithLineBreaks(@Nullable ASTNode node, int minCount) {
    if (isWhitespace(node)) {
      if (minCount == 1) {
        return node.textContains('\n');
      }
      final String prevNodeText = node.getText();
      int count = 0;
      for (int i = 0; i < prevNodeText.length(); i++) {
        if (prevNodeText.charAt(i) == '\n') {
          count++;
          if (count == minCount) {
            return true;
          }
        }
      }
    }
    return false;
  }

  @Override
  public @Nullable Wrap getWrap() {
    return myWrap;
  }

  @Override
  public @Nullable Indent getIndent() {
    assert myIndent != null;
    return myIndent;
  }

  @Override
  public @Nullable Alignment getAlignment() {
    return myAlignment;
  }

  @Override
  public @Nullable Spacing getSpacing(@Nullable Block child1, @NotNull Block child2) {
    final CommonCodeStyleSettings settings = myContext.getSettings();
    final PyCodeStyleSettings pySettings = myContext.getPySettings();
    if (child1 instanceof ASTBlock && child2 instanceof ASTBlock) {
      final ASTNode node1 = ((ASTBlock)child1).getNode();
      ASTNode node2 = ((ASTBlock)child2).getNode();
      final IElementType childType1 = node1.getElementType();
      final PsiElement psi1 = node1.getPsi();

      PsiElement psi2 = node2.getPsi();

      if (psi2 instanceof PyAstStatementList) {
        // Quite odd getSpacing() doesn't get called with child1=null for the first statement
        // in the statement list of a class, yet it does get called for the preceding colon and
        // the statement list itself. Hence we have to handle blank lines around methods here in
        // addition to SpacingBuilder.
        if (myNode.getElementType() == PyElementTypes.CLASS_DECLARATION) {
          final PyAstStatement[] statements = ((PyAstStatementList)psi2).getStatements();
          if (statements.length > 0 && statements[0] instanceof PyAstFunction) {
            return getBlankLinesForOption(pySettings.BLANK_LINES_BEFORE_FIRST_METHOD);
          }
        }
        if (childType1 == PyTokenTypes.COLON && (needLineBreakInStatement())) {
          return Spacing.createSpacing(0, 0, 1, true, settings.KEEP_BLANK_LINES_IN_CODE);
        }
      }

      // pycodestyle.py enforces at most 2 blank lines only between comments directly
      // at the top-level of a file, not inside if, try/except, etc.
      if (psi1 instanceof PsiComment && myNode.getPsi() instanceof PsiFile) {
        return Spacing.createSpacing(0, 0, 1, true, 2);
      }

      // skip not inline comments to handles blank lines between various declarations
      if (psi2 instanceof PsiComment && hasLineBreaksBeforeInSameParent(node2, 1)) {
        final PsiElement nonCommentAfter = PyPsiUtilsCore.getNextNonCommentSibling(psi2, true);
        if (nonCommentAfter != null) {
          psi2 = nonCommentAfter;
        }
      }
      node2 = psi2.getNode();
      final IElementType childType2 = psi2.getNode().getElementType();
      child2 = getSubBlockByNode(node2);

      if (isInsideFStringFragmentWithEqualsSign(myNode)) {
        return Spacing.getReadOnlySpacing();
      }
      if (childType1 == PyTokenTypes.FSTRING_FRAGMENT_START) {
        final LeafElement firstLeaf = TreeUtil.findFirstLeaf(node2);
        if (firstLeaf != null && firstLeaf.getElementType() == PyTokenTypes.LBRACE) {
          return Spacing.createSpacing(1, 1, 0, false, 0);
        }
      }

      if (childType2 == PyTokenTypes.FSTRING_FRAGMENT_END) {
        final ASTNode lastLeaf = TreeUtil.findLastLeaf(node1);
        if (lastLeaf != null && lastLeaf.getElementType() == PyTokenTypes.RBRACE) {
          return Spacing.createSpacing(1, 1, 0, false, 0);
        }
      }

      if ((childType1 == PyTokenTypes.EQ || childType2 == PyTokenTypes.EQ)) {
        final PyAstNamedParameter namedParameter = ObjectUtils.tryCast(myNode.getPsi(), PyAstNamedParameter.class);
        if (namedParameter != null && namedParameter.getAnnotation() != null) {
          return Spacing.createSpacing(1, 1, 0, settings.KEEP_LINE_BREAKS, settings.KEEP_BLANK_LINES_IN_CODE);
        }
      }

      if (psi1 instanceof PyAstImportStatementBase) {
        if (psi2 instanceof PyAstImportStatementBase) {
          final Boolean leftImportIsGroupStart = psi1.getCopyableUserData(PythonCodeStyleService.IMPORT_GROUP_BEGIN);
          final Boolean rightImportIsGroupStart = psi2.getCopyableUserData(PythonCodeStyleService.IMPORT_GROUP_BEGIN);
          // Cleanup user data, it's no longer needed
          psi1.putCopyableUserData(PythonCodeStyleService.IMPORT_GROUP_BEGIN, null);
          // Don't remove IMPORT_GROUP_BEGIN from the element psi2 yet, because spacing is constructed pairwise:
          // it might be needed on the next iteration.
          //psi2.putCopyableUserData(IMPORT_GROUP_BEGIN, null);
          if (rightImportIsGroupStart != null) {
            return Spacing.createSpacing(0, 0, 2, true, 1);
          }
          else if (leftImportIsGroupStart != null) {
            // It's a trick to keep spacing consistent when new import statement is inserted
            // at the beginning of an import group, i.e. if there is a blank line before the next
            // import we want to save it, but remove line *after* inserted import.
            return Spacing.createSpacing(0, 0, 1, false, 0);
          }
        }
        if (psi2 instanceof PyAstStatement && !(psi2 instanceof PyAstImportStatementBase)) {
          if (PyUtilCore.isTopLevel(psi1)) {
            // If there is any function or class after a top-level import, it should be top-level as well
            if (PyElementTypes.CLASS_OR_FUNCTION.contains(childType2)) {
              return getBlankLinesForOption(Math.max(settings.BLANK_LINES_AFTER_IMPORTS,
                                                     pySettings.BLANK_LINES_AROUND_TOP_LEVEL_CLASSES_FUNCTIONS));
            }
            return getBlankLinesForOption(settings.BLANK_LINES_AFTER_IMPORTS);
          }
          else {
            return getBlankLinesForOption(pySettings.BLANK_LINES_AFTER_LOCAL_IMPORTS);
          }
        }
      }

      if (psi2 instanceof PyAstImportStatementBase && PyUtilCore.isAssignmentToModuleLevelDunderName(psi1)) {
        // blank line between module-level dunder name and import statement
        return Spacing.createSpacing(0, 0, 2, settings.KEEP_LINE_BREAKS, settings.KEEP_BLANK_LINES_IN_DECLARATIONS);
      }

      if ((PyElementTypes.CLASS_OR_FUNCTION.contains(childType1) && STATEMENT_OR_DECLARATION.contains(childType2)) ||
          STATEMENT_OR_DECLARATION.contains(childType1) && PyElementTypes.CLASS_OR_FUNCTION.contains(childType2)) {
        if (PyUtilCore.isTopLevel(psi1)) {
          return getBlankLinesForOption(pySettings.BLANK_LINES_AROUND_TOP_LEVEL_CLASSES_FUNCTIONS);
        }
      }
    }
    return myContext.getSpacingBuilder().getSpacing(this, child1, child2);
  }

  private @NotNull Spacing getBlankLinesForOption(int minBlankLines) {
    final int lineFeeds = minBlankLines + 1;
    return Spacing.createSpacing(0, 0, lineFeeds,
                                 myContext.getSettings().KEEP_LINE_BREAKS,
                                 myContext.getSettings().KEEP_BLANK_LINES_IN_DECLARATIONS);
  }

  private boolean needLineBreakInStatement() {
    final PsiElement psiElement = myNode.getPsi();
    final boolean isInStubFile = PyiUtilCore.isInsideStub(psiElement);
    if (psiElement instanceof PyAstStatementListContainer) {
      final PyAstStatement statement = PsiTreeUtil.getParentOfType(psiElement, PyAstStatement.class);
      if (statement != null) {
        final Collection<PyAstStatementPart> parts = PsiTreeUtil.collectElementsOfType(statement, PyAstStatementPart.class);
        return (parts.size() == 1 && myContext.getPySettings().NEW_LINE_AFTER_COLON && !isInStubFile) ||
               (parts.size() > 1 && myContext.getPySettings().NEW_LINE_AFTER_COLON_MULTI_CLAUSE);
      }
      else {
        return myContext.getPySettings().NEW_LINE_AFTER_COLON && !isInStubFile;
      }
    }
    return false;
  }

  @Override
  public @NotNull ChildAttributes getChildAttributes(int newChildIndex) {
    int statementListsBelow = 0;

    if (newChildIndex > 0) {
      // always pass decision to a sane block from top level from file or definition
      if (myNode.getPsi() instanceof PyAstFile || myNode.getElementType() == PyTokenTypes.COLON) {
        return ChildAttributes.DELEGATE_TO_PREV_CHILD;
      }

      final PyBlock insertAfterBlock = getSubBlockByIndex(newChildIndex - 1);

      final ASTNode prevNode = insertAfterBlock.getNode();
      final PsiElement prevElt = prevNode.getPsi();

      // TODO Use the same approach for other list-like constructs
      if (myNode.getElementType() == PyElementTypes.WITH_STATEMENT && isInsideWithStatementParentheses(myNode, prevNode)) {
        ASTNode openingParenthesis = myNode.findChildByType(PyTokenTypes.LPAR);
        for (int i = newChildIndex - 1; i >= 0 ; i--) {
          PyBlock prevBlock = mySubBlocks.get(i);
          if (prevBlock.myNode == openingParenthesis) {
            break;
          }
          if (prevBlock.getAlignment() != null) {
            return new ChildAttributes(Indent.getNormalIndent(), prevBlock.getAlignment());
          }
        }
        return new ChildAttributes(Indent.getNormalIndent(), null);
      }

      // stmt lists, parts and definitions should also think for themselves
      if (prevElt instanceof PyAstStatementList) {
        if (dedentAfterLastStatement((PyAstStatementList)prevElt)) {
          return new ChildAttributes(Indent.getNoneIndent(), getChildAlignment());
        }
        return ChildAttributes.DELEGATE_TO_PREV_CHILD;
      }
      else if (prevElt instanceof PyAstStatementPart) {
        return ChildAttributes.DELEGATE_TO_PREV_CHILD;
      }

      ASTNode lastChild = insertAfterBlock.getNode();

      //In case of a dedent(or multiple dedents) the cursor doesn't belong to the correct block, instead
      //formatter chooses the biggest enclosing incomplete block as a parent (see FormatProcessor.getParentFor).
      //To get the correct indent, we have to descend into the inner incomplete block:
      //each time we see an error element inside a statement we delegate to the previous child.
      while (lastChild != null) {
        final IElementType lastType = lastChild.getElementType();
        if (lastType == PyElementTypes.STATEMENT_LIST && hasLineBreaksBeforeInSameParent(lastChild, 1)) {
          if (dedentAfterLastStatement((PyAstStatementList)lastChild.getPsi())) {
            break;
          }
          statementListsBelow++;
        }
        else if (statementListsBelow > 0 && lastChild.getPsi() instanceof PsiErrorElement) {
          statementListsBelow++;
        }
        if (myNode.getElementType() == PyElementTypes.STATEMENT_LIST && lastChild.getPsi() instanceof PsiErrorElement) {
          return ChildAttributes.DELEGATE_TO_PREV_CHILD;
        }
        lastChild = getLastNonSpaceChild(lastChild, true);
      }
    }

    // HACKETY-HACK
    // If a multi-step dedent follows the cursor position (see testMultiDedent()),
    // the whitespace (which must be a single Py:LINE_BREAK token) gets attached
    // to the outermost indented block (because we may not consume the DEDENT
    // tokens while parsing inner blocks). The policy is to put the indent to
    // the innermost block, so we need to resolve the situation here. Nested
    // delegation sometimes causes NPEs in formatter core, so we calculate the
    // correct indent manually.
    if (statementListsBelow > 0) { // was 1... strange
      @SuppressWarnings("ConstantConditions") final int indent = myContext.getSettings().getIndentOptions().INDENT_SIZE;
      return new ChildAttributes(Indent.getSpaceIndent(indent * statementListsBelow), null);
    }

    /*
    // it might be something like "def foo(): # comment" or "[1, # comment"; jump up to the real thing
    if (_node instanceof PsiComment || _node instanceof PsiWhiteSpace) {
      get
    }
    */


    final Indent childIndent = getChildIndent(newChildIndex);
    final Alignment childAlignment = getChildAlignment();
    return new ChildAttributes(childIndent, childAlignment);
  }

  private static boolean dedentAfterLastStatement(@NotNull PyAstStatementList statementList) {
    final PyAstStatement[] statements = statementList.getStatements();
    if (statements.length == 0) {
      return false;
    }
    final PyAstStatement last = statements[statements.length - 1];
    return last instanceof PyAstReturnStatement || last instanceof PyAstRaiseStatement || last instanceof PyAstPassStatement || isEllipsis(last);
  }

  private static boolean isEllipsis(@NotNull PyAstStatement statement) {
    if (statement instanceof PyAstExpressionStatement) {
      final PyAstExpression expression = ((PyAstExpressionStatement)statement).getExpression();
      if (expression instanceof PyAstEllipsisLiteralExpression) {
        return true;
      }
    }

    return false;
  }

  private @Nullable Alignment getChildAlignment() {
    // TODO merge it with needListAlignment(ASTNode)
    final IElementType nodeType = myNode.getElementType();
    if (ourListElementTypes.contains(nodeType) ||
        nodeType == PyElementTypes.SLICE_ITEM ||
        nodeType == PyElementTypes.STRING_LITERAL_EXPRESSION) {
      if (isInControlStatement()) {
        return null;
      }
      final PsiElement elem = myNode.getPsi();
      if (elem instanceof PyAstParameterList && !myContext.getSettings().ALIGN_MULTILINE_PARAMETERS) {
        return null;
      }
      if ((elem instanceof PyAstSequenceExpression || elem instanceof PyAstComprehensionElement) &&
          !myContext.getPySettings().ALIGN_COLLECTIONS_AND_COMPREHENSIONS) {
        return null;
      }
      if (elem instanceof PyAstParenthesizedExpression) {
        final PyAstExpression parenthesized = ((PyAstParenthesizedExpression)elem).getContainedExpression();
        if (parenthesized instanceof PyAstTupleExpression && !myContext.getPySettings().ALIGN_COLLECTIONS_AND_COMPREHENSIONS) {
          return null;
        }
      }
      if (elem instanceof PyAstDictLiteralExpression) {
        final PyAstKeyValueExpression lastElement = ArrayUtil.getLastElement(((PyAstDictLiteralExpression)elem).getElements());
        if (lastElement == null || lastElement.getValue() == null /* incomplete */) {
          return null;
        }
      }
      if (elem instanceof PyAstWithStatement && PyPsiUtilsCore.getFirstChildOfType(elem, PyTokenTypes.LPAR) == null) {
        return null;
      }
      return getAlignmentForChildren();
    }
    return null;
  }

  private @NotNull Indent getChildIndent(int newChildIndex) {
    final IElementType parentType = myNode.getElementType();
    final ASTNode afterNode = getAfterNode(newChildIndex);
    final ASTNode lastChild = getLastNonSpaceChild(myNode, false);
    if (lastChild != null && lastChild.getElementType() == PyElementTypes.STATEMENT_LIST && mySubBlocks.size() >= newChildIndex) {
      if (afterNode == null) {
        return Indent.getNoneIndent();
      }

      // handle pressing Enter after colon and before first statement in
      // existing statement list
      if (afterNode.getElementType() == PyElementTypes.STATEMENT_LIST || afterNode.getElementType() == PyTokenTypes.COLON) {
        return Indent.getNormalIndent();
      }

      // handle pressing Enter after colon when there is nothing in the
      // statement list
      final ASTNode lastFirstChild = lastChild.getFirstChildNode();
      if (lastFirstChild != null && lastFirstChild == lastChild.getLastChildNode() && lastFirstChild.getPsi() instanceof PsiErrorElement) {
        return Indent.getNormalIndent();
      }
    }
    if (parentType == PyElementTypes.MATCH_STATEMENT && afterNode != null && afterNode.getElementType() == PyTokenTypes.COLON) {
      return Indent.getNormalIndent();
    }

    if (afterNode != null && afterNode.getElementType() == PyElementTypes.KEY_VALUE_EXPRESSION) {
      final PyAstKeyValueExpression keyValue = (PyAstKeyValueExpression)afterNode.getPsi();
      if (keyValue != null && keyValue.getValue() == null) {  // incomplete
        return Indent.getContinuationIndent();
      }
    }

    // constructs that imply indent for their children
    final PyCodeStyleSettings settings = myContext.getPySettings();
    if ((parentType == PyElementTypes.PARAMETER_LIST && settings.USE_CONTINUATION_INDENT_FOR_PARAMETERS) ||
        (parentType == PyElementTypes.ARGUMENT_LIST && settings.USE_CONTINUATION_INDENT_FOR_ARGUMENTS)) {
      return Indent.getContinuationIndent();
    }
    if (ourCollectionLiteralTypes.contains(parentType) || parentType == PyElementTypes.TUPLE_EXPRESSION) {
      return settings.USE_CONTINUATION_INDENT_FOR_COLLECTION_AND_COMPREHENSIONS ? Indent.getContinuationIndent() : Indent.getNormalIndent();
    }
    else if (ourListElementTypes.contains(parentType) || myNode.getPsi() instanceof PyAstStatementPart) {
      return Indent.getNormalIndent();
    }

    if (afterNode != null) {
      ASTNode wsAfter = afterNode.getTreeNext();
      while (wsAfter != null && wsAfter.getElementType() == TokenType.WHITE_SPACE) {
        if (wsAfter.getText().indexOf('\\') >= 0) {
          return Indent.getNormalIndent();
        }
        wsAfter = wsAfter.getTreeNext();
      }
    }
    return Indent.getNoneIndent();
  }

  private @Nullable ASTNode getAfterNode(int newChildIndex) {
    if (newChildIndex == 0) {  // block text contains backslash line wrappings, child block list not built
      return null;
    }
    int prevIndex = newChildIndex - 1;
    while (prevIndex > 0 && getSubBlockByIndex(prevIndex).getNode().getElementType() == PyTokenTypes.END_OF_LINE_COMMENT) {
      prevIndex--;
    }
    return getSubBlockByIndex(prevIndex).getNode();
  }

  private static ASTNode getLastNonSpaceChild(@NotNull ASTNode node, boolean acceptError) {
    ASTNode lastChild = node.getLastChildNode();
    while (lastChild != null &&
           (lastChild.getElementType() == TokenType.WHITE_SPACE || (!acceptError && lastChild.getPsi() instanceof PsiErrorElement))) {
      lastChild = lastChild.getTreePrev();
    }
    return lastChild;
  }

  @Override
  public boolean isIncomplete() {
    // if there's something following us, we're not incomplete
    if (!PsiTreeUtil.hasErrorElements(myNode.getPsi())) {
      if (myNode.getPsi() instanceof PsiFile) {
        return false;
      }
      PsiElement element = myNode.getPsi().getNextSibling();
      while (element instanceof PsiWhiteSpace) {
        element = element.getNextSibling();
      }
      if (element != null) {
        return false;
      }
    }

    final ASTNode lastChild = getLastNonSpaceChild(myNode, false);
    if (lastChild != null) {
      if (lastChild.getElementType() == PyElementTypes.STATEMENT_LIST) {
        // only multiline statement lists are considered incomplete
        final ASTNode statementListPrev = lastChild.getTreePrev();
        if (statementListPrev != null && statementListPrev.getText().indexOf('\n') >= 0) {
          return true;
        }
      }
      if (lastChild.getElementType() == PyElementTypes.BINARY_EXPRESSION) {
        final PyAstBinaryExpression binaryExpression = (PyAstBinaryExpression)lastChild.getPsi();
        if (binaryExpression.getRightExpression() == null) {
          return true;
        }
      }
      if (isIncompleteCall(lastChild)) return true;
    }

    if (myNode.getPsi() instanceof PyAstArgumentList argumentList) {
      return argumentList.getClosingParen() == null;
    }
    if (isIncompleteCall(myNode) || isIncompleteExpressionWithBrackets(myNode.getPsi())) {
      return true;
    }

    return false;
  }

  private static boolean isIncompleteCall(@NotNull ASTNode node) {
    if (node.getElementType() == PyElementTypes.CALL_EXPRESSION) {
      final PyAstCallExpression callExpression = (PyAstCallExpression)node.getPsi();
      final PyAstArgumentList argumentList = callExpression.getArgumentList();
      if (argumentList == null || argumentList.getClosingParen() == null) {
        return true;
      }
    }
    return false;
  }

  private static boolean isIncompleteExpressionWithBrackets(@NotNull PsiElement elem) {
    if (elem instanceof PyAstSequenceExpression || elem instanceof PyAstComprehensionElement || elem instanceof PyAstParenthesizedExpression) {
      return PyTokenTypes.OPEN_BRACES.contains(elem.getFirstChild().getNode().getElementType()) &&
             !PyTokenTypes.CLOSE_BRACES.contains(elem.getLastChild().getNode().getElementType());
    }
    return false;
  }

  @Override
  public boolean isLeaf() {
    return myNode.getFirstChildNode() == null;
  }

  private @NotNull PyFormattableInjectedBlock createInjectedBlock(@NotNull ASTNode child, @NotNull Indent indent) {
    return new PyFormattableInjectedBlock(this, child, getAlignmentForChildren(),
                                          Wrap.createWrap(WrapType.NONE, false),
                                          indent, myContext.getSettings().getRootSettings(),
                                          myContext);
  }

  private static boolean isInsideInjection(@NotNull ASTNode node) {
    ASTNode parent = node.getTreeParent();
    return parent != null && InjectedLanguageManager.getInstance(node.getPsi().getProject()).hasInjections(parent.getPsi());
  }

  private static final TokenSet stopAtTokens = TokenSet.orSet(TokenSet.create(PyElementTypes.FSTRING_NODE),
                                                              PythonDialectsTokenSetProvider.getInstance().getStatementTokens());

  private static boolean isInsideFStringFragmentWithEqualsSign(@NotNull ASTNode node) {
    final ASTNode fStringFragmentParent = node.getElementType() == PyElementTypes.FSTRING_FRAGMENT
                                    ? node
                                    : TreeUtil.findParent(node, TokenSet.create(PyElementTypes.FSTRING_FRAGMENT), stopAtTokens);
    if (fStringFragmentParent == null) return false;
    return fStringFragmentParent.findChildByType(PyTokenTypes.EQ) != null;
  }
}