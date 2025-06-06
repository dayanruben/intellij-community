package com.jetbrains.python.psi.impl;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.containers.ContainerUtil;
import com.jetbrains.python.codeInsight.stdlib.PyDataclassTypeProvider;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.resolve.PyResolveContext;
import com.jetbrains.python.psi.resolve.RatedResolveResult;
import com.jetbrains.python.psi.types.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

import static com.jetbrains.python.codeInsight.controlflow.PyTypeAssertionEvaluator.createAssertionType;
import static com.jetbrains.python.psi.PyUtil.as;
import static com.jetbrains.python.psi.impl.PySequencePatternImpl.wrapInListType;

public class PyCapturePatternImpl extends PyElementImpl implements PyCapturePattern {
  public PyCapturePatternImpl(ASTNode astNode) {
    super(astNode);
  }

  @Override
  protected void acceptPyVisitor(PyElementVisitor pyVisitor) {
    pyVisitor.visitPyCapturePattern(this);
  }

  @Override
  public @Nullable PyType getType(@NotNull TypeEvalContext context, TypeEvalContext.@NotNull Key key) {
    return getCaptureType(this, context);
  }

  static Set<String> SPECIAL_BUILTINS = Set.of(
    "bool", "bytearray", "bytes", "dict", "float", "frozenset", "int", "list", "set", "str", "tuple");

  /**
   * Determines what type this pattern would have if it was a capture pattern (like a bare name or _).
   * <p>
   * In pattern matching, a capture pattern takes on the type of the entire matched expression,
   * regardless of any specific pattern constraints.
   * <p>
   * For example:
   * <pre>{@code
   * x: int | str
   * match x:
   *     case a:         # This is a capture pattern
   *         # Here 'a' has type int | str
   *     case str():     # This is a class pattern
   *         # Capture type: int | str (same as what 'case a:' would get)
   *         # Regular getType: str
   *
   * y: int
   * match y:
   *     case str() as a:
   *         # Capture type: int (same as what 'case a:' would get)
   *         # Regular getType: intersect(int, str) (just 'str' for now)
   * }</pre>
   * @see PyPattern#getType(TypeEvalContext, TypeEvalContext.Key) 
   */
  static @Nullable PyType getCaptureType(@NotNull PyPattern pattern, @NotNull TypeEvalContext context) {
    final PyElement parentPattern = PsiTreeUtil.getParentOfType(
      pattern,                   // Capture corresponds to:
      PyCaseClause.class,        // - Subject of a match statement
      PySingleStarPattern.class, // - Type of parent sequence pattern
      PyDoubleStarPattern.class, // - Type of parent mapping pattern
      PyKeyValuePattern.class,   // - Value type of parent mapping pattern
      PySequencePattern.class,   // - Iterated item type of sequence
      PyClassPattern.class,      // - Attribute type in the corresponding class
      PyKeywordPattern.class     // - Attribute type in the corresponding class
    );

    if (parentPattern instanceof PyCaseClause caseClause) {
      final PyMatchStatement matchStatement = as(caseClause.getParent(), PyMatchStatement.class);
      if (matchStatement == null) return null;

      final PyExpression subject = matchStatement.getSubject();
      if (subject == null) return null;

      PyType subjectType = context.getType(subject);
      for (PyCaseClause cs : matchStatement.getCaseClauses()) {
        if (cs == caseClause) break;
        if (cs.getPattern() == null) continue;
        if (cs.getGuardCondition() != null && !PyEvaluator.evaluateAsBoolean(cs.getGuardCondition(), false)) continue;
        if (cs.getPattern().canExcludePatternType(context)) {
          subjectType = Ref.deref(createAssertionType(subjectType, context.getType(cs.getPattern()), false, context));
        }
      }

      return subjectType;
    }
    if (parentPattern instanceof PySingleStarPattern starPattern) {
      final PySequencePattern sequenceParent = as(starPattern.getParent(), PySequencePattern.class);
      if (sequenceParent == null) return null;
      final PyType sequenceType = PySequencePatternImpl.getSequenceCaptureType(sequenceParent, context);
      final PyType iteratedType = PyTypeUtil.toStream(sequenceType)
        .flatMap(it -> starPattern.getCapturedTypesFromSequenceType(it, context).stream()).collect(PyTypeUtil.toUnion());
      return wrapInListType(iteratedType, pattern);
    }
    if (parentPattern instanceof PyDoubleStarPattern) {
      final PyMappingPattern mappingParent = as(parentPattern.getParent(), PyMappingPattern.class);
      if (mappingParent == null) return null;
      var mappingType = PyTypeUtil.convertToType(context.getType(mappingParent), "typing.Mapping", pattern, context);
      if (mappingType instanceof PyCollectionType collectionType) {
        final PyClass dict = PyBuiltinCache.getInstance(pattern).getClass("dict");
        return dict != null ? new PyCollectionTypeImpl(dict, false, collectionType.getElementTypes()) : null;
      }
      return null;
    }
    if (parentPattern instanceof PyKeyValuePattern keyValuePattern) {
      final PyMappingPattern mappingParent = as(keyValuePattern.getParent(), PyMappingPattern.class);
      if (mappingParent == null) return null;

      return PyTypeUtil.toStream(getCaptureType(mappingParent, context)).map(type -> {
        if (type instanceof PyTypedDictType typedDictType) {
          if (context.getType(keyValuePattern.getKeyPattern()) instanceof PyLiteralType l &&
              l.getExpression() instanceof PyStringLiteralExpression str) {
            return typedDictType.getElementType(str.getStringValue());
          }
        }

        PyType mappingType = PyTypeUtil.convertToType(type, "typing.Mapping", pattern, context);
        if (mappingType == null) {
          return PyNeverType.NEVER;
        }
        else if (mappingType instanceof PyCollectionType collectionType) {
          return collectionType.getElementTypes().get(1);
        }
        return null;
      }).collect(PyTypeUtil.toUnion());
    }
    if (parentPattern instanceof PySequencePattern sequencePattern) {
      final PyType sequenceType = PySequencePatternImpl.getSequenceCaptureType(sequencePattern, context);
      if (sequenceType == null) return null;

      // This is done to skip group- and as-patterns
      final var sequenceMember = PsiTreeUtil.findFirstParent(pattern, el -> el.getParent() == sequencePattern);
      final List<PyPattern> elements = sequencePattern.getElements();
      final int idx = elements.indexOf(sequenceMember);
      final int starIdx = ContainerUtil.indexOf(elements, it2 -> it2 instanceof PySingleStarPattern);
      
      return PyTypeUtil.toStream(sequenceType).map(it -> {
        if (it instanceof PyTupleType tupleType && !tupleType.isHomogeneous()) {
          if (starIdx == -1 || idx < starIdx) {
            return tupleType.getElementType(idx);
          }
          else {
            final int starSpan = tupleType.getElementCount() - elements.size();
            return tupleType.getElementType(idx + starSpan);
          }
        }
        var upcast = PyTypeUtil.convertToType(it, "typing.Sequence", pattern, context);
        if (upcast instanceof PyCollectionType collectionType) {
          return collectionType.getIteratedItemType();
        }
        return null;
      }).collect(PyTypeUtil.toUnion());
    }
    if (parentPattern instanceof PyClassPattern classPattern) {
      final List<PyPattern> arguments = classPattern.getArgumentList().getPatterns();
      int index = arguments.indexOf(pattern);
      if (index < 0) return null;
      
      // capture type can be a union like: list[int] | list[str]
      return PyTypeUtil.toStream(context.getType(classPattern)).map(type -> {
        if (type instanceof PyClassType classType) {
          if (SPECIAL_BUILTINS.contains(classType.getClassQName())) {
            if (index == 0) {
              return classType;
            }
            return null;
          }

          List<String> matchArgs = getMatchArgs(classType, context);
          if (matchArgs == null || matchArgs.size() > arguments.size()) return null;

          final PyTypedElement instanceAttribute = as(resolveTypeMember(classType, matchArgs.get(index), context), PyTypedElement.class);
          if (instanceAttribute == null) return null;

          return PyTypeChecker.substitute(context.getType(instanceAttribute), PyTypeChecker.unifyReceiver(classType, context), context);
        }
        return null;
      }).collect(PyTypeUtil.toUnion());
    }
    if (parentPattern instanceof PyKeywordPattern keywordPattern) {
      final PyClassPattern classPattern = PsiTreeUtil.getParentOfType(keywordPattern, PyClassPattern.class);
      if (classPattern == null) return null;

      if (context.getType(classPattern) instanceof PyClassType classType) {
        final PyTypedElement instanceAttribute = as(resolveTypeMember(classType, keywordPattern.getKeyword(), context), PyTypedElement.class);
        if (instanceAttribute == null) return null;
        return context.getType(instanceAttribute);
      }
      return null;
    }
    return null;
  }
  
  static @Nullable List<@NotNull String> getMatchArgs(@NotNull PyClassType type, @NotNull TypeEvalContext context) {
    final PyClass cls = type.getPyClass();
    List<String> matchArgs = cls.getOwnMatchArgs();
    if (matchArgs == null) {
      matchArgs = PyDataclassTypeProvider.Companion.getGeneratedMatchArgs(cls, context);
    }
    return matchArgs;
  }

  @Nullable
  static PsiElement resolveTypeMember(@NotNull PyType type, @NotNull String name, @NotNull TypeEvalContext context) {
    final PyResolveContext resolveContext = PyResolveContext.defaultContext(context);
    final List<? extends RatedResolveResult> results = type.resolveMember(name, null, AccessDirection.READ, resolveContext);
    return !ContainerUtil.isEmpty(results) ? results.get(0).getElement() : null;
  }
}
