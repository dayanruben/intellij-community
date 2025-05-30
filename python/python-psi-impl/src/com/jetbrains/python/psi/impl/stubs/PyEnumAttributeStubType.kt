package com.jetbrains.python.psi.impl.stubs

import com.intellij.psi.stubs.StubInputStream
import com.intellij.psi.stubs.StubOutputStream
import com.intellij.psi.util.QualifiedName
import com.jetbrains.python.codeInsight.dataflow.scope.ScopeUtil
import com.jetbrains.python.psi.*
import com.jetbrains.python.psi.impl.PyPsiUtils
import com.jetbrains.python.psi.resolve.PyResolveUtil
import com.jetbrains.python.psi.stubs.PyEnumAttributeStub
import com.jetbrains.python.psi.stubs.PyLiteralKind

class PyEnumAttributeStubType : CustomTargetExpressionStubType<PyEnumAttributeStub>() {
  override fun createStub(psi: PyTargetExpression): PyEnumAttributeStub? {
    if (ScopeUtil.getScopeOwner(psi) !is PyClass) return null

    val callExpr = PyPsiUtils.flattenParens(psi.findAssignedValue()) as? PyCallExpression ?: return null
    val callee = callExpr.callee as? PyReferenceExpression ?: return null
    val calleeFqn = PyResolveUtil.resolveImportedElementQNameLocally(callee).firstOrNull()

    val argument = callExpr.arguments.singleOrNull() ?: return null
    val isMember = when (calleeFqn) {
      PyKnownDecorator.ENUM_MEMBER.qualifiedName -> true
      PyKnownDecorator.ENUM_NONMEMBER.qualifiedName -> false
      else -> return null
    }
    return PyEnumAttributeStubImpl(PyLiteralKind.fromExpression(argument), isMember)
  }

  override fun deserializeStub(stream: StubInputStream): PyEnumAttributeStub {
    val literalKind = PyLiteralKind.deserialize(stream)
    val isMember = stream.readBoolean()
    return PyEnumAttributeStubImpl(literalKind, isMember)
  }
}

private class PyEnumAttributeStubImpl(
  override val literalKind: PyLiteralKind?,
  override val isMember: Boolean,
) : PyEnumAttributeStub {

  override fun getTypeClass(): Class<out PyCustomStubType<*, *>> = PyEnumAttributeStubType::class.java

  override fun serialize(stream: StubOutputStream) {
    PyLiteralKind.serialize(stream, literalKind)
    stream.writeBoolean(isMember)
  }

  override fun getCalleeName(): QualifiedName? = null
  
  override fun toString(): String {
    return "PyEnumAttributeStub(literalKind=$literalKind, isMember=$isMember)"
  }
}