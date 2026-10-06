// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.j2k

import com.intellij.codeInsight.NullableNotNullManager
import com.intellij.codeInspection.dataFlow.DfaNullability
import com.intellij.codeInspection.dataFlow.DfaPsiUtil
import com.intellij.codeInspection.dataFlow.DfaUtil
import com.intellij.codeInspection.dataFlow.NullabilityProblemKind
import com.intellij.codeInspection.dataFlow.NullabilityProblemKind.NullabilityProblem
import com.intellij.codeInspection.dataFlow.NullabilityUtil
import com.intellij.codeInspection.dataFlow.StandardDataFlowRunner
import com.intellij.codeInspection.dataFlow.inference.JavaSourceInference
import com.intellij.codeInspection.dataFlow.interpreter.RunnerResult
import com.intellij.codeInspection.dataFlow.interpreter.StandardDataFlowInterpreter
import com.intellij.codeInspection.dataFlow.java.JavaDfaListener
import com.intellij.codeInspection.dataFlow.jvm.descriptors.PlainDescriptor
import com.intellij.codeInspection.dataFlow.lang.DfaListener
import com.intellij.codeInspection.dataFlow.lang.UnsatisfiedConditionProblem
import com.intellij.codeInspection.dataFlow.lang.ir.ControlFlow
import com.intellij.codeInspection.dataFlow.lang.ir.DfaInstructionState
import com.intellij.codeInspection.dataFlow.lang.ir.FlushFieldsInstruction
import com.intellij.codeInspection.dataFlow.lang.ir.ReturnInstruction
import com.intellij.codeInspection.dataFlow.memory.DfaMemoryState
import com.intellij.codeInspection.dataFlow.types.DfType
import com.intellij.codeInspection.dataFlow.value.DfaValue
import com.intellij.codeInspection.dataFlow.value.DfaVariableValue
import com.intellij.psi.CommonClassNames
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.JavaRecursiveElementWalkingVisitor
import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiArrayAccessExpression
import com.intellij.psi.PsiArrayInitializerExpression
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiAssignmentExpression
import com.intellij.psi.PsiBinaryExpression
import com.intellij.psi.PsiCall
import com.intellij.psi.PsiCallExpression
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassInitializer
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiConditionalExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiForeachStatement
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiMethodReferenceExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeCastExpression
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiVariable
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.impl.source.PsiClassReferenceType
import com.intellij.psi.impl.source.PsiExtensibleClass
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiTypesUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.psi.util.TypeConversionUtil
import com.intellij.util.JavaPsiConstructorUtil
import com.intellij.util.ThreeState
import com.siyeh.ig.psiutils.ExpressionUtils
import com.siyeh.ig.psiutils.MethodCallUtils
import org.jetbrains.annotations.ApiStatus
import com.intellij.codeInsight.Nullability as JavaNullability

@ApiStatus.Internal
class J2KDataflowNullability(private val file: PsiFile) {
    /**
     * A type in the flow graph: the type of a declaration, of a call result, or of a type argument.
     * [platformNullability] comes from an annotation or a framework, and the engine keeps it.
     * [dataflowNullability] is the join of the values that flow in.
     * [needsNotNull] tells that a use needs a not-null value.
     */
    private class TypeNode(val type: PsiType, val platformNullability: Nullability?, val wildcard: PsiWildcardType? = null) {
        var dataflowNullability: Nullability? = null
        var needsNotNull = false
        var result = Nullability.Default
        var printedByCaller = false
        val targets = LinkedHashSet<TypeNode>()
        val sources = LinkedHashSet<TypeNode>()
        var elements: List<TypeNode> = emptyList()

        fun join(other: Nullability): Boolean {
            val current = dataflowNullability
            val joined = when {
                current == null || current == other -> other
                current == Nullability.Nullable || other == Nullability.Nullable -> Nullability.Nullable
                else -> Nullability.Default
            }
            if (joined == current) return false
            dataflowNullability = joined
            return true
        }

        fun decide(): Nullability = platformNullability ?: when {
            dataflowNullability == Nullability.Nullable -> Nullability.Nullable
            needsNotNull || dataflowNullability == Nullability.NotNull -> Nullability.NotNull
            else -> Nullability.Default
        }
    }

    private val nodes = LinkedHashMap<PsiModifierListOwner, TypeNode>()

    /** Keyed by the method for a result without type arguments, otherwise by the call or the `new` expression. */
    private val callNodes = HashMap<PsiElement, TypeNode>()
    private val notPrinted = LinkedHashSet<PsiModifierListOwner>()
    private val methods = ArrayList<PsiMethod>()
    private val sinks = HashMap<PsiExpression, TypeNode>()
    private val visited = HashSet<PsiExpression>()
    private val originals = OriginalJavaSemanticResolver()
    private val originalFile by lazy { originals.originalElementOrSelf(file) }

    val decisions: Map<PsiModifierListOwner, Nullability> by lazy {
        collectNodes()
        collectDataflow()
        analyzeBodies()
        for ((expression, node) in sinks) {
            if (expression !in visited) addStaticDataflow(expression, node)
        }
        spreadPrintedByCaller()
        solve()
        resolve()
        nodes.filterKeys { it !in notPrinted }.mapValues { it.value.result }
    }

    fun applyTo(inferrer: J2KNullityInferrer) {
        if (decisions.isEmpty()) return
        for (owner in decisions.keys) {
            val node = nodes.getValue(owner)
            inferrer.write(node.type, node.result)
            writeElements(inferrer, node)
        }
        for ((key, node) in callNodes) if (key is PsiNewExpression) writeElements(inferrer, node)
    }

    private fun writeElements(inferrer: J2KNullityInferrer, node: TypeNode) {
        for (element in node.elements) {
            val type = if (element.wildcard == null) element.type else element.wildcard.bound ?: continue
            val printed = !TypeConversionUtil.isPrimitiveAndNotNull(type) && PsiUtil.resolveClassInClassTypeOnly(type) !is PsiTypeParameter
            if (printed && !element.printedByCaller && element.result != Nullability.Default) inferrer.write(type, element.result)
            writeElements(inferrer, element)
        }
    }

    private fun J2KNullityInferrer.write(type: PsiType, result: Nullability) {
        val element = (type as? PsiClassReferenceType)?.reference
        nullableTypes.remove(type)
        notNullTypes.remove(type)
        if (element != null) {
            nullableElements.remove(element)
            notNullElements.remove(element)
        }
        when (result) {
            Nullability.Nullable -> {
                nullableTypes.add(type)
                if (element != null) nullableElements.add(element)
            }

            Nullability.NotNull -> {
                notNullTypes.add(type)
                if (element != null) notNullElements.add(element)
            }

            Nullability.Default -> {}
        }
    }

    private fun collectNodes() {
        file.accept(object : JavaRecursiveElementWalkingVisitor() {
            override fun visitClass(aClass: PsiClass) {
                super.visitClass(aClass)
                if (aClass.isRecord) return
                val written = (aClass as? PsiExtensibleClass)?.ownMethods ?: return
                for (method in aClass.methods) {
                    if (method in written || method.body == null) continue
                    notPrinted += method
                    notPrinted += method.parameterList.parameters
                    methods += method
                    val returnType = method.returnType
                    if (!method.isConstructor && returnType != null) addNode(method, returnType)
                    for (parameter in method.parameterList.parameters) {
                        if (!parameter.isVarArgs) addNode(parameter, parameter.type)
                    }
                }
            }

            override fun visitField(field: PsiField) {
                super.visitField(field)
                if (field !is PsiEnumConstant) addNode(field, field.type)
            }

            override fun visitMethod(method: PsiMethod) {
                super.visitMethod(method)
                methods += method
                val returnType = method.returnType
                if (!method.isConstructor && returnType != null && method.containingClass?.isAnnotationType != true) {
                    addNode(method, returnType)
                }
            }

            override fun visitParameter(parameter: PsiParameter) {
                super.visitParameter(parameter)
                when (parameter.declarationScope) {
                    is PsiMethod -> if (!parameter.isVarArgs) addNode(parameter, parameter.type)
                    is PsiForeachStatement -> {
                        addNode(parameter, parameter.type)
                        notPrinted += parameter
                    }
                }
            }

            override fun visitLocalVariable(variable: PsiLocalVariable) {
                super.visitLocalVariable(variable)
                addNode(variable, variable.type)
            }
        })
    }

    private fun addNode(owner: PsiModifierListOwner, type: PsiType) {
        if (TypeConversionUtil.isPrimitiveAndNotNull(type) || PsiUtil.resolveClassInClassTypeOnly(type) is PsiTypeParameter) return
        if (PsiTreeUtil.getParentOfType(owner, PsiClass::class.java)?.isRecord == true || isJpaToManyDeclaration(owner)) return
        if (PsiTypesUtil.classNameEquals(type, CommonClassNames.JAVA_UTIL_OPTIONAL)) return
        val extensionNullability = when (owner) {
            is PsiParameter -> J2KNullabilityInferenceExtension.getNullability(owner)
            is PsiMethod -> J2KNullabilityInferenceExtension.getNullability(owner)
            is PsiField -> J2KNullabilityInferenceExtension.getNullability(owner)
            else -> null
        }?.takeIf { it != Nullability.Default }
        val info = NullableNotNullManager.getInstance(file.project).findEffectiveNullabilityInfo(owner)
        val node = TypeNode(type, extensionNullability ?: info?.takeUnless { it.isInferred }?.nullability?.toJ2K())
        if (info != null && info.isInferred && owner !is PsiParameter) info.nullability.toJ2K()?.let(node::join)
        node.elements = createElements(type, elementNullability = (owner as? PsiMethod)?.let(::extensionElementNullability))
        nodes[owner] = node
    }

    private fun extensionElementNullability(method: PsiMethod): Nullability? =
        J2KNullabilityInferenceExtension.getTypeArgumentNullability(method)?.takeIf { it != Nullability.Default }

    private fun createElements(
        type: PsiType,
        receiver: TypeNode? = null,
        elementNullability: Nullability? = null,
        callee: PsiMethod? = null,
    ): List<TypeNode> {
        val arguments = when (type) {
            is PsiArrayType -> listOf(type.componentType)
            is PsiClassType -> type.parameters.asList()
            else -> emptyList()
        }
        val typeParameters = (type as? PsiClassType)?.resolve()?.typeParameters
        return arguments.mapIndexed { index, argument ->
            val wildcard = argument as? PsiWildcardType
            val elementType = wildcard?.extendsBound ?: argument
            if (receiver != null && wildcard == null) receiverElement(receiver, elementType)?.let { return@mapIndexed it }
            val platformNullability = if (TypeConversionUtil.isPrimitiveAndNotNull(elementType)) {
                Nullability.NotNull
            } else {
                elementType.nullability.nullability().toJ2K() ?: elementNullability ?: boundNullability(typeParameters?.getOrNull(index))
            }
            TypeNode(elementType, platformNullability, wildcard).also {
                it.printedByCaller = callee != null && (PsiUtil.resolveClassInClassTypeOnly(elementType) as? PsiTypeParameter)?.owner == callee
                it.elements = createElements(elementType, receiver, callee = callee)
            }
        }
    }

    private fun spreadPrintedByCaller() {
        val elements = elementNodes()
        val queue = ArrayDeque(elements.filter { it.printedByCaller })
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            for (next in node.sources + node.targets) {
                if (next in elements && !next.printedByCaller) {
                    next.printedByCaller = true
                    queue += next
                }
            }
        }
    }

    private fun boundNullability(typeParameter: PsiTypeParameter?): Nullability? =
        if (typeParameter != null && getTypeParameterNullability(typeParameter) == JavaNullability.NOT_NULL) Nullability.NotNull else null

    private fun linkElements(source: TypeNode, target: TypeNode) {
        if (source === target) return
        for ((sourceElement, targetElement) in source.elements.zip(target.elements)) {
            val wildcard = targetElement.wildcard
            if (wildcard?.isSuper != true) edge(sourceElement, targetElement)
            if (wildcard == null || wildcard.isSuper) edge(targetElement, sourceElement)
            linkElements(sourceElement, targetElement)
        }
    }

    private fun JavaNullability.toJ2K(): Nullability? = when (this) {
        JavaNullability.NOT_NULL -> Nullability.NotNull
        JavaNullability.NULLABLE -> Nullability.Nullable
        else -> null
    }

    private fun collectDataflow() {
        val visitor = object : JavaRecursiveElementWalkingVisitor() {
            override fun visitVariable(variable: PsiVariable) {
                super.visitVariable(variable)
                val node = nodes[variable] ?: return
                variable.initializer?.let { addSink(it, node) }
            }

            override fun visitAssignmentExpression(expression: PsiAssignmentExpression) {
                super.visitAssignmentExpression(expression)
                val node = expressionNode(expression.lExpression) ?: return
                if (expression.operationTokenType == JavaTokenType.EQ) {
                    expression.rExpression?.let { addSink(it, node) }
                } else {
                    node.join(Nullability.NotNull)
                    if (PsiPrimitiveType.getUnboxedType(expression.lExpression.type) != null) node.needsNotNull = true
                }
            }

            override fun visitCallExpression(callExpression: PsiCallExpression) {
                super.visitCallExpression(callExpression)
                addArgumentSinks(callExpression)
                if (callExpression is PsiMethodCallExpression) addReceiverElementSinks(callExpression)
            }

            override fun visitForeachStatement(statement: PsiForeachStatement) {
                super.visitForeachStatement(statement)
                val variable = nodes[statement.iterationParameter] ?: return
                val iterated = expressionNode(statement.iteratedValue) ?: return
                val element = if (iterated.type is PsiArrayType) iterated.elements.firstOrNull() else receiverElement(iterated, iterableElementType)
                if (element != null) {
                    edge(element, variable)
                    linkElements(element, variable)
                }
            }

            override fun visitEnumConstant(enumConstant: PsiEnumConstant) {
                super.visitEnumConstant(enumConstant)
                addArgumentSinks(enumConstant)
            }

            override fun visitMethodReferenceExpression(expression: PsiMethodReferenceExpression) {
                super.visitMethodReferenceExpression(expression)
                val method = expression.resolve() as? PsiMethod ?: return
                for (parameter in method.parameterList.parameters) nodes[parameter]?.let(::joinUnknown)
            }

            override fun visitBinaryExpression(expression: PsiBinaryExpression) {
                super.visitBinaryExpression(expression)
                addNullComparisonDataflow(expression)
            }
        }
        file.accept(visitor)
        for (owner in notPrinted) (owner as? PsiMethod)?.body?.accept(visitor)

        for (method in methods) {
            val searchesAllUsers = searchesAllUsers(method)
            nodes[method]?.let { node ->
                if (method.body == null && !searchesAllUsers) node.join(Nullability.Default)
                for (statement in PsiUtil.findReturnStatements(method)) statement.returnValue?.let { addSink(it, node) }
            }
            addParameterDataflow(method)
            addOverrideDataflow(method)
            if (searchesAllUsers) addProjectDataflow(method)
        }
        for ((owner, node) in nodes) {
            if (owner is PsiField && searchesAllUsers(owner)) addProjectAssignmentDataflow(owner, node)
        }
    }

    private fun searchesAllUsers(member: PsiMember): Boolean = member.hasModifierProperty(PsiModifier.PACKAGE_LOCAL)

    private fun addSink(expression: PsiExpression, node: TypeNode) {
        when (val stripped = PsiUtil.skipParenthesizedExprDown(expression) ?: return) {
            is PsiConditionalExpression -> {
                stripped.thenExpression?.let { addSink(it, node) }
                stripped.elseExpression?.let { addSink(it, node) }
            }

            is PsiTypeCastExpression -> stripped.operand?.let { addSink(it, node) }
            else -> {
                sinks[stripped] = node
                expressionNode(stripped)?.let { linkElements(it, node) }
                val initializer = (stripped as? PsiNewExpression)?.arrayInitializer ?: stripped as? PsiArrayInitializerExpression
                val element = node.elements.firstOrNull()
                if (initializer != null && element != null) {
                    for (initializerElement in initializer.initializers) addSink(initializerElement, element)
                }
                if (stripped is PsiNewExpression && initializer == null) addNewArrayDataflow(stripped, node)
            }
        }
    }

    private fun addNewArrayDataflow(expression: PsiNewExpression, node: TypeNode) {
        val dimensions = expression.arrayDimensions
        var element = node.elements.firstOrNull()
        for (index in dimensions.indices) {
            val current = element ?: return
            current.join(if (index == dimensions.lastIndex) Nullability.Nullable else Nullability.NotNull)
            element = current.elements.firstOrNull()
        }
    }

    private fun addReceiverElementSinks(call: PsiMethodCallExpression) {
        val receiver = expressionNode(call.methodExpression.qualifierExpression) ?: return
        if (receiver.elements.isEmpty()) return
        val method = call.resolveMethod() ?: return
        val arguments = call.argumentList.expressions
        for ((index, parameter) in method.parameterList.parameters.withIndex()) {
            if (parameter.isVarArgs) break
            val argument = arguments.getOrNull(index) ?: break
            if (PsiUtil.skipParenthesizedExprDown(argument) in sinks) continue
            receiverElement(receiver, parameter.type)?.let { addSink(argument, it) }
        }
    }

    private fun receiverElement(receiver: TypeNode, type: PsiType?): TypeNode? {
        val typeParameter = PsiUtil.resolveClassInClassTypeOnly(type) as? PsiTypeParameter ?: return null
        val owner = typeParameter.owner as? PsiClass ?: return null
        val receiverClass = PsiUtil.resolveClassInClassTypeOnly(receiver.type) ?: return null
        val mapped = if (owner == receiverClass) {
            typeParameter
        } else {
            if (!receiverClass.isInheritor(owner, true)) return null
            val substitutor = TypeConversionUtil.getSuperClassSubstitutor(owner, receiverClass, PsiSubstitutor.EMPTY)
            PsiUtil.resolveClassInClassTypeOnly(substitutor.substitute(typeParameter)) as? PsiTypeParameter ?: return null
        }
        return receiver.elements.getOrNull(receiverClass.typeParameters.indexOf(mapped))
    }

    private val iterableElementType: PsiType? by lazy {
        val iterable = JavaPsiFacade.getInstance(file.project).findClass(CommonClassNames.JAVA_LANG_ITERABLE, file.resolveScope)
        iterable?.typeParameters?.firstOrNull()?.let { JavaPsiFacade.getElementFactory(file.project).createType(it) }
    }

    private fun addArgumentSinks(call: PsiCall) {
        val arguments = call.argumentList?.expressions ?: return
        val isVarArgCall = MethodCallUtils.isVarArgCall(call)
        for (argument in arguments) {
            val parameter = MethodCallUtils.getParameterForArgument(argument) ?: continue
            if (!parameter.isVarArgs) {
                nodes[parameter]?.let { addSink(argument, it) }
            } else if (!isVarArgCall) {
                variableNode(argument)?.needsNotNull = true
            }
        }
    }

    private fun addNullComparisonDataflow(expression: PsiBinaryExpression) {
        val operation = expression.operationTokenType
        if (operation != JavaTokenType.EQEQ && operation != JavaTokenType.NE) return
        val left = expression.lOperand
        val right = expression.rOperand ?: return
        val operand = when {
            ExpressionUtils.isNullLiteral(right) -> left
            ExpressionUtils.isNullLiteral(left) -> right
            else -> return
        }
        val reference = PsiUtil.skipParenthesizedExprDown(operand) as? PsiReferenceExpression ?: return
        val node = variableNode(reference) ?: return
        if (getExpressionDfaNullability(reference) == DfaNullability.NOT_NULL) return
        if (DfaPsiUtil.isAssertionEffectively(expression, operation == JavaTokenType.NE)) {
            node.needsNotNull = true
        } else {
            node.join(Nullability.Nullable)
        }
    }

    private fun addParameterDataflow(method: PsiMethod) {
        val hasUnseenCallers = !method.hasModifierProperty(PsiModifier.PRIVATE) && !searchesAllUsers(method)
        for (parameter in method.parameterList.parameters) {
            val node = nodes[parameter] ?: continue
            if (hasUnseenCallers) joinUnknown(node)
            if (method.body != null && JavaSourceInference.inferNullability(parameter) == JavaNullability.NOT_NULL) node.needsNotNull = true
        }
    }

    private fun joinUnknown(node: TypeNode) {
        node.join(Nullability.Default)
        for (element in node.elements) joinUnknown(element)
    }

    private fun addOverrideDataflow(method: PsiMethod) {
        val returnNode = nodes[method]
        for (superMethod in method.findSuperMethods()) {
            val superReturnNode = nodes[superMethod]
            if (returnNode != null && superReturnNode != null) {
                edge(returnNode, superReturnNode)
                linkElements(returnNode, superReturnNode)
            }
            for ((parameter, superParameter) in method.parameterList.parameters.zip(superMethod.parameterList.parameters)) {
                val parameterNode = nodes[parameter] ?: continue
                val superParameterNode = nodes[superParameter] ?: continue
                edge(parameterNode, superParameterNode)
                edge(superParameterNode, parameterNode)
                linkElements(parameterNode, superParameterNode)
                linkElements(superParameterNode, parameterNode)
            }
        }
    }

    private fun addProjectDataflow(method: PsiMethod) {
        val parameterNodes = method.parameterList.parameters.map { nodes[it] }
        if (parameterNodes.any { it != null }) {
            for (element in referencesOutsideFile(method)) {
                val arguments = (element.parent as? PsiCall)?.argumentList?.expressions
                if (arguments == null && element !is PsiMethodReferenceExpression) continue
                for ((index, node) in parameterNodes.withIndex()) {
                    if (node == null) continue
                    val argument = arguments?.getOrNull(index)
                    if (argument != null) addStaticDataflow(argument, node) else joinUnknown(node)
                }
            }
        }
        val returnNode = nodes[method] ?: return
        if (method.isConstructor || method.hasModifierProperty(PsiModifier.STATIC) || method.hasModifierProperty(PsiModifier.FINAL)) return
        for (overrider in OverridingMethodsSearch.search(originals.originalElementOrSelf(method)).findAll()) {
            if (overrider.containingFile == originalFile) continue
            val nullability = NullableNotNullManager.getNullability(overrider).takeIf { it != JavaNullability.UNKNOWN }
                ?: DfaUtil.inferMethodNullability(overrider)
            returnNode.join(nullability.toJ2K() ?: Nullability.Default)
        }
    }

    private fun addProjectAssignmentDataflow(field: PsiField, node: TypeNode) {
        for (element in referencesOutsideFile(field)) {
            val assignment = element.parent as? PsiAssignmentExpression ?: continue
            if (assignment.lExpression != element) continue
            val value = assignment.rExpression
            if (assignment.operationTokenType == JavaTokenType.EQ && value != null) addStaticDataflow(value, node) else node.join(Nullability.NotNull)
        }
    }

    private fun referencesOutsideFile(member: PsiMember): List<PsiElement> {
        val original = originals.originalElementOrSelf(member)
        return ReferencesSearch.search(original, original.useScope).findAll().map { it.element }.filter { it.containingFile != originalFile }
    }

    private fun analyzeBodies() {
        file.accept(object : JavaRecursiveElementWalkingVisitor() {
            override fun visitClass(aClass: PsiClass) {
                if (isConstructed(aClass)) analyzeConstruction(aClass)
                super.visitClass(aClass)
            }

            override fun visitMethod(method: PsiMethod) {
                if (method.isConstructor && isConstructed(method.containingClass)) return
                method.body?.let(::analyze)
            }

            override fun visitClassInitializer(initializer: PsiClassInitializer) {
                if (isConstructed(initializer.containingClass)) return
                analyze(initializer.body)
            }
        })
    }

    private fun isConstructed(aClass: PsiClass?): Boolean =
        aClass != null && aClass !is PsiTypeParameter && !aClass.isInterface && !aClass.isRecord &&
            !PsiUtil.isLocalOrAnonymousClass(aClass)

    private fun analyzeConstruction(aClass: PsiClass) {
        val runner = ConstructionRunner()
        val classDataflow = BodyDataflow()
        runner.checkAtReturn = false
        runner.fieldsToCheck = aClass.fields.filter { it.hasModifierProperty(PsiModifier.STATIC) && it in nodes }
        if (runner.analyzeBlockRecursively(aClass, listOf(runner.freshState()), classDataflow) != RunnerResult.OK) return
        classDataflow.commit()

        val instanceFields = aClass.fields.filter { !it.hasModifierProperty(PsiModifier.STATIC) && it in nodes }
        val initializedStates = classDataflow.endOfInitializerStates.ifEmpty { listOf(runner.freshState()) }
        val constructors = aClass.constructors
        if (constructors.isEmpty()) {
            for (state in initializedStates) runner.check(state, instanceFields)
        }
        for (constructor in constructors) {
            val body = constructor.body ?: continue
            val chained = JavaPsiConstructorUtil.isChainedConstructorCall(JavaPsiConstructorUtil.findThisOrSuperCallInConstructor(constructor))
            runner.checkAtReturn = true
            runner.fieldsToCheck = if (chained) emptyList() else instanceFields
            val states = if (chained) listOf(runner.freshState()) else initializedStates.map { it.createCopy() }
            val dataflow = BodyDataflow()
            if (runner.analyzeBlockRecursively(body, states, dataflow) == RunnerResult.OK) dataflow.commit()
        }
        for (field in runner.nullAtEnd) nodes[field]?.join(Nullability.Nullable)
    }

    private inner class ConstructionRunner : StandardDataFlowRunner(file.project, ThreeState.UNSURE) {
        var checkAtReturn = false
        var fieldsToCheck: List<PsiField> = emptyList()
        val nullAtEnd = HashSet<PsiField>()

        fun freshState(): DfaMemoryState = createMemoryState()

        fun check(state: DfaMemoryState, fields: List<PsiField>) {
            for (field in fields) {
                val value = PlainDescriptor.createVariableValue(factory, field)
                if (DfaNullability.fromDfType(state.getDfType(value)) == DfaNullability.NULL) nullAtEnd += field
            }
        }

        override fun createInterpreter(listener: DfaListener, flow: ControlFlow): StandardDataFlowInterpreter {
            flow.keepVariables { it is PlainDescriptor && it.psiElement in fieldsToCheck }
            return object : StandardDataFlowInterpreter(flow, listener) {
                override fun acceptInstruction(instructionState: DfaInstructionState): Array<DfaInstructionState> {
                    val instruction = instructionState.instruction
                    val isEnd = if (checkAtReturn) instruction is ReturnInstruction else instruction is FlushFieldsInstruction
                    if (isEnd) check(instructionState.memoryState, fieldsToCheck)
                    return super.acceptInstruction(instructionState)
                }
            }
        }
    }

    private fun analyze(body: PsiCodeBlock) {
        val dataflow = BodyDataflow()
        val result = StandardDataFlowRunner(file.project, ThreeState.UNSURE).analyzeMethodRecursively(body, dataflow)
        if (result == RunnerResult.OK) dataflow.commit()
    }

    private inner class BodyDataflow : JavaDfaListener {
        private val joins = ArrayList<Pair<TypeNode, Nullability>>()
        private val edges = ArrayList<Pair<TypeNode, TypeNode>>()
        private val needsNotNull = ArrayList<TypeNode>()
        private val pushed = HashSet<PsiExpression>()
        val endOfInitializerStates = ArrayList<DfaMemoryState>()

        override fun beforeInstanceInitializerEnd(state: DfaMemoryState) {
            endOfInitializerStates += state.createCopy()
        }

        override fun beforeExpressionPush(value: DfaValue, expression: PsiExpression, state: DfaMemoryState) {
            val target = sinks[expression] ?: return
            pushed += expression
            if (state.getDfType(value) == DfType.FAIL) return
            val nullability = if (TypeConversionUtil.isPrimitiveAndNotNull(expression.type)) {
                DfaNullability.NOT_NULL
            } else {
                DfaNullability.fromDfType(state.getDfTypeIncludingDerived(value))
            }
            when (nullability) {
                DfaNullability.NULL, DfaNullability.NULLABLE -> joins += target to Nullability.Nullable
                DfaNullability.NOT_NULL -> joins += target to Nullability.NotNull
                else -> when (NullabilityUtil.getExpressionNullability(expression, false)) {
                    JavaNullability.NOT_NULL -> joins += target to Nullability.NotNull
                    JavaNullability.NULLABLE -> joins += target to Nullability.Nullable
                    else -> {
                        val source = sourceNode(value, expression)
                        if (source != null) edges += source to target else joins += target to Nullability.Default
                    }
                }
            }
        }

        override fun onCondition(problem: UnsatisfiedConditionProblem, value: DfaValue, failed: ThreeState, state: DfaMemoryState) {
            if (problem !is NullabilityProblem<*> || problem.kind !in DEREFERENCE_KINDS) return
            val variable = (value as? DfaVariableValue)?.psiVariable as? PsiVariable ?: return
            val node = nodes[variable] ?: return
            val nullability = DfaNullability.fromDfType(state.getDfType(value))
            if (nullability == DfaNullability.UNKNOWN || nullability == DfaNullability.FLUSHED) needsNotNull += node
        }

        fun commit() {
            for ((node, nullability) in joins) node.join(nullability)
            for ((source, target) in edges) edge(source, target)
            for (node in needsNotNull) node.needsNotNull = true
            visited += pushed
        }
    }

    private fun sourceNode(value: DfaValue, expression: PsiExpression): TypeNode? {
        val variable = (value as? DfaVariableValue)?.psiVariable as? PsiModifierListOwner
        if (variable != null) nodes[variable]?.let { return it }
        return expressionNode(expression)
    }

    private fun expressionNode(expression: PsiExpression?): TypeNode? = when (val stripped = PsiUtil.skipParenthesizedExprDown(expression)) {
        is PsiReferenceExpression -> (stripped.resolve() as? PsiVariable)?.let { nodes[it] }
        is PsiMethodCallExpression -> calleeNode(stripped)
        is PsiArrayAccessExpression -> expressionNode(stripped.arrayExpression)?.elements?.firstOrNull()
        is PsiNewExpression -> newExpressionNode(stripped)
        else -> null
    }

    private fun newExpressionNode(expression: PsiNewExpression): TypeNode? {
        callNodes[expression]?.let { return it }
        val typeArguments = expression.classReference?.parameterList?.typeParameterElements ?: return null
        if (typeArguments.isEmpty()) return null
        val type = expression.type as? PsiClassType ?: return null
        return TypeNode(type, Nullability.NotNull).also {
            it.elements = createElements(type)
            callNodes[expression] = it
        }
    }

    private fun variableNode(expression: PsiExpression?): TypeNode? {
        val reference = PsiUtil.skipParenthesizedExprDown(expression) as? PsiReferenceExpression ?: return null
        val variable = reference.resolve() as? PsiVariable ?: return null
        return nodes[variable]
    }

    private fun calleeNode(call: PsiMethodCallExpression): TypeNode? {
        val method = call.resolveMethod() ?: return null
        nodes[method]?.let { return it }
        callNodes[call]?.let { return it }
        val type = method.returnType ?: return null
        val receiver = expressionNode(call.methodExpression.qualifierExpression)
        if (receiver != null) {
            receiverElement(receiver, type)?.let { return it }
            val elements = if (receiver.elements.isEmpty()) emptyList() else createElements(type, receiver, callee = method)
            if (elements.isNotEmpty()) {
                val nullability = effectiveNullability(method)
                return TypeNode(type, nullability).also {
                    if (nullability == null) it.join(Nullability.Default)
                    it.elements = elements
                    callNodes[call] = it
                }
            }
        }
        callNodes[method]?.let { return it }
        val enumValues = isEnumValues(method)
        if (method.containingFile == file && !enumValues) return null
        val nullability = if (enumValues) Nullability.NotNull else effectiveNullability(method) ?: return null
        val elementNullability = if (enumValues) Nullability.NotNull else extensionElementNullability(method)
        val elements = createElements(type, elementNullability = elementNullability, callee = method)
        return TypeNode(type, nullability).also {
            it.elements = elements
            callNodes[if (elements.isEmpty()) method else call] = it
        }
    }

    private fun isEnumValues(method: PsiMethod): Boolean =
        method.name == "values" && method.parameterList.isEmpty && method.hasModifierProperty(PsiModifier.STATIC) &&
            method.containingClass?.isEnum == true

    private fun effectiveNullability(method: PsiMethod): Nullability? =
        NullableNotNullManager.getInstance(file.project).findEffectiveNullabilityInfo(method)?.nullability?.toJ2K()

    private fun addStaticDataflow(expression: PsiExpression, node: TypeNode) {
        when (NullabilityUtil.getExpressionNullability(expression, true)) {
            JavaNullability.NOT_NULL -> node.join(Nullability.NotNull)
            JavaNullability.NULLABLE -> node.join(Nullability.Nullable)
            else -> {
                val source = expressionNode(expression)
                if (source != null) {
                    edge(source, node)
                    linkElements(source, node)
                } else {
                    node.join(Nullability.Default)
                }
            }
        }
    }

    private fun edge(source: TypeNode, target: TypeNode) {
        if (source === target) return
        source.targets += target
        target.sources += source
    }

    private fun allNodes(): List<TypeNode> = buildList {
        addAll(nodes.values)
        addAll(callNodes.values)
        addAll(elementNodes())
    }

    private fun elementNodes(): Set<TypeNode> {
        val elements = LinkedHashSet<TypeNode>()
        fun collect(node: TypeNode) {
            for (element in node.elements) if (elements.add(element)) collect(element)
        }
        for (node in nodes.values) collect(node)
        for (node in callNodes.values) collect(node)
        return elements
    }

    private fun solve() {
        val queue = ArrayDeque(allNodes())
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val nullability = node.platformNullability ?: node.dataflowNullability ?: continue
            for (target in node.targets) {
                if (target.platformNullability == null && target.join(nullability)) queue += target
            }
        }
    }

    private fun resolve() {
        val all = allNodes()
        for (node in all) node.result = node.decide()
        val queue = ArrayDeque(all.filter { it.result == Nullability.NotNull })
        while (queue.isNotEmpty()) {
            for (source in queue.removeFirst().sources) {
                if (source.platformNullability != null || source.needsNotNull || source.dataflowNullability == Nullability.Nullable) continue
                source.needsNotNull = true
                source.result = source.decide()
                queue += source
            }
        }
    }

    companion object {
        private val DEREFERENCE_KINDS: Set<NullabilityProblemKind<*>> = setOf(
            NullabilityProblemKind.callNPE,
            NullabilityProblemKind.callMethodRefNPE,
            NullabilityProblemKind.innerClassNPE,
            NullabilityProblemKind.templateNPE,
            NullabilityProblemKind.fieldAccessNPE,
            NullabilityProblemKind.arrayAccessNPE,
            NullabilityProblemKind.unboxingNullable,
            NullabilityProblemKind.unboxingMethodRefParameter,
            NullabilityProblemKind.passingToNotNullParameter,
            NullabilityProblemKind.passingToNotNullMethodRefParameter,
            NullabilityProblemKind.assigningToNotNull,
            NullabilityProblemKind.storingToNotNullArray,
        )
    }
}
