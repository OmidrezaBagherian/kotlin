/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.dce

import org.jetbrains.kotlin.backend.common.ir.isMemberOfOpenClass
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.backend.js.JsCommonBackendContext
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.export.isExported
import org.jetbrains.kotlin.ir.backend.js.utils.associatedObject
import org.jetbrains.kotlin.ir.backend.js.utils.getJsName
import org.jetbrains.kotlin.ir.backend.js.utils.getJsNameOrKotlinName
import org.jetbrains.kotlin.ir.backend.js.utils.isAssociatedObjectAnnotatedAnnotation
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import java.util.*

class UsefulDeclarationProcessor(private val printReachabilityInfo: Boolean) {
    abstract inner class BodyVisitorBase(protected val context: JsCommonBackendContext) : IrElementVisitorVoid {
        protected val toStringMethod =
            context.irBuiltIns.anyClass.owner.declarations.filterIsInstance<IrFunction>().single { it.name.asString() == "toString" }
        protected val equalsMethod =
            context.irBuiltIns.anyClass.owner.declarations.filterIsInstance<IrFunction>().single { it.name.asString() == "equals" }
        protected val hashCodeMethod =
            context.irBuiltIns.anyClass.owner.declarations.filterIsInstance<IrFunction>().single { it.name.asString() == "hashCode" }


        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitFunctionAccess(expression: IrFunctionAccessExpression) {
            super.visitFunctionAccess(expression)

            expression.symbol.owner.enqueue("function access")
        }

        override fun visitRawFunctionReference(expression: IrRawFunctionReference) {
            super.visitRawFunctionReference(expression)

            expression.symbol.owner.enqueue("raw function access")
        }

        override fun visitVariableAccess(expression: IrValueAccessExpression) {
            super.visitVariableAccess(expression)

            expression.symbol.owner.enqueue("variable access")
        }

        override fun visitFieldAccess(expression: IrFieldAccessExpression) {
            super.visitFieldAccess(expression)

            expression.symbol.owner.enqueue("field access")
        }

        override fun visitStringConcatenation(expression: IrStringConcatenation) {
            super.visitStringConcatenation(expression)

            toStringMethod.enqueue("string concatenation")
        }
    }

    private fun IrDeclaration.enqueue(
        from: IrDeclaration?,
        description: String?,
        isContagious: Boolean = true,
        altFromFqn: String? = null
    ) {
        // Ignore non-external IrProperty because we don't want to generate code for them and codegen doesn't support it.
        if (this is IrProperty && !this.isExternal) return

        // TODO check that this is overridable
        // it requires fixing how functions with default arguments is handled
        val isContagiousOverridableDeclaration = isContagious && this is IrOverridableDeclaration<*> && this.isMemberOfOpenClass

        if (printReachabilityInfo) {
            val fromFqn = (from as? IrDeclarationWithName)?.fqNameWhenAvailable?.asString() ?: altFromFqn ?: "<unknown>"
            val toFqn = (this as? IrDeclarationWithName)?.fqNameWhenAvailable?.asString() ?: "<unknown>"

            val comment = (description ?: "") + (if (isContagiousOverridableDeclaration) "[CONTAGIOUS!]" else "")
            val v = "\"$fromFqn\" -> \"$toFqn\"" + (if (comment.isBlank()) "" else " // $comment")

            reachabilityInfo.add(v)
        }

        if (isContagiousOverridableDeclaration) {
            contagiousReachableDeclarations.add(this as IrOverridableDeclaration<*>)
        }

        if (this !in result) {
            result.add(this)
            queue.addLast(this)
        }
    }

    fun IrDeclaration.enqueue(description: String, isContagious: Boolean = true) {
        enqueue(this, description, isContagious)
    }

    private val nestedDeclarationVisitor = object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitBody(body: IrBody) = Unit // Skip

        override fun visitDeclaration(declaration: IrDeclarationBase) {
            declaration.enqueue(declaration.parentClassOrNull, "roots' nested declaration")
            super.visitDeclaration(declaration)
        }
    }

    // This collection contains declarations whose reachability should be propagated to overrides.
    // Overriding uncontagious declaration will not lead to becoming a declaration reachable.
    // By default, all declarations treated as contagious, it's not the most efficient, but it's safest.
    // In case when we access a declaration through a fake-override declaration, the original (real) one will not be marked as contagious,
    // so, later, other overrides will not be processed unconditionally only because it overrides a reachable declaration.
    //
    // The collection must be a subset of [result] set.
    private val contagiousReachableDeclarations = hashSetOf<IrOverridableDeclaration<*>>()
    val constructedClasses = hashSetOf<IrClass>()
    private val reachabilityInfo: MutableSet<String> = if (printReachabilityInfo) linkedSetOf() else Collections.emptySet()
    private val queue = ArrayDeque<IrDeclaration>()
    private val result = hashSetOf<IrDeclaration>()
    val referencedJsClasses = hashSetOf<IrDeclaration>()
    val referencedJsClassesFromExpressions = hashSetOf<IrClass>()

    fun usefulDeclarations(
        bodyVisitor: BodyVisitorBase,
        roots: Iterable<IrDeclaration>,
        context: JsCommonBackendContext,
        removeUnusedAssociatedObjects: Boolean,
    ): Set<IrDeclaration> {

        val classesWithObjectAssociations = hashSetOf<IrClass>()

        // use withInitialIr to avoid ConcurrentModificationException in dce-driven lowering when adding roots' nested declarations (members)
        // Add roots
        roots.forEach {
            it.enqueue(null, null, altFromFqn = "<ROOT>")
        }

        // Add roots' nested declarations
        roots.forEach { rootDeclaration ->
            rootDeclaration.acceptChildren(nestedDeclarationVisitor, null)
        }

        while (queue.isNotEmpty()) {
            while (queue.isNotEmpty()) {
                val declaration = queue.pollFirst()

                if (declaration is IrClass) {
                    declaration.superTypes.forEach {
                        (it.classifierOrNull as? IrClassSymbol)?.owner?.enqueue("superTypes")
                    }

                    if (declaration.isObject && /*declaration.isExported(context as JsIrBackendContext)*/ false) {
                        context.mapping.objectToGetInstanceFunction[declaration]!!
                            .enqueue(declaration, "Exported object getInstance function")
                    }

                    declaration.annotations.forEach {
                        val annotationClass = it.symbol.owner.constructedClass
                        if (annotationClass.isAssociatedObjectAnnotatedAnnotation) {
                            classesWithObjectAssociations += declaration
                            annotationClass.enqueue("@AssociatedObject annotated annotation class")
                        }
                    }
                }

                if (declaration is IrSimpleFunction && declaration.isFakeOverride) {
                    declaration.resolveFakeOverride()?.enqueue("real overridden fun", isContagious = false)
                }

                // Collect instantiated classes.
                if (declaration is IrConstructor) {
                    declaration.constructedClass.let {
                        it.enqueue("constructed class")
                        constructedClasses += it
                    }
                }

                val body = when (declaration) {
                    is IrFunction -> declaration.body
                    is IrField -> declaration.initializer
                    is IrVariable -> declaration.initializer
                    else -> null
                }

                body?.acceptVoid(bodyVisitor)
            }

            fun IrOverridableDeclaration<*>.findOverriddenContagiousDeclaration(): IrOverridableDeclaration<*>? {
                for (overriddenSymbol in this.overriddenSymbols) {
                    val overriddenDeclaration = overriddenSymbol.owner as? IrOverridableDeclaration<*> ?: continue

                    if (overriddenDeclaration in contagiousReachableDeclarations) return overriddenDeclaration

                    overriddenDeclaration.findOverriddenContagiousDeclaration()?.let {
                        return it
                    }
                }

                return null
            }

            //Handle objects, constructed via `findAssociatedObject` annotation
            referencedJsClassesFromExpressions += constructedClasses.filterDescendantsOf(referencedJsClassesFromExpressions) // Grow the set of possible results of instance::class expression
            for (klass in classesWithObjectAssociations) {
                if (removeUnusedAssociatedObjects && klass !in referencedJsClasses && klass !in referencedJsClassesFromExpressions) continue

                for (annotation in klass.annotations) {
                    val annotationClass = annotation.symbol.owner.constructedClass
                    if (removeUnusedAssociatedObjects && annotationClass !in referencedJsClasses) continue

                    annotation.associatedObject()?.let { obj ->
                        context.mapping.objectToGetInstanceFunction[obj]?.enqueue(klass, "associated object factory")
                    }
                }
            }

            for (klass in constructedClasses) {
                // TODO a better way to support inverse overrides.
                for (declaration in ArrayList(klass.declarations)) {
                    if (declaration in result) continue

                    if (declaration is IrOverridableDeclaration<*>) {
                        declaration.findOverriddenContagiousDeclaration()?.let {
                            declaration.enqueue(it, "overrides useful declaration")
                        }
                    }

                    if (declaration is IrSimpleFunction && declaration.getJsNameOrKotlinName().asString() == "valueOf") {
                        declaration.enqueue(klass, "valueOf")
                    }

                    // A hack to support `toJson` and other js-specific members
                    if (declaration.getJsName() != null ||
                        declaration is IrField && declaration.correspondingPropertySymbol?.owner?.getJsName() != null ||
                        declaration is IrSimpleFunction && declaration.correspondingPropertySymbol?.owner?.getJsName() != null
                    ) {
                        declaration.enqueue(klass, "annotated by @JsName")
                    }

                    // A hack to enforce property lowering.
                    // Until a getter is accessed it doesn't get moved to the declaration list.
                    if (declaration is IrProperty) {
                        declaration.getter?.run {
                            findOverriddenContagiousDeclaration()?.let { enqueue(it, "(getter) overrides useful declaration") }
                        }
                        declaration.setter?.run {
                            findOverriddenContagiousDeclaration()?.let { enqueue(it, "(setter) overrides useful declaration") }
                        }
                    }
                }
            }

            context.additionalExportedDeclarations
                .forEach { it.enqueue(null, "from additionalExportedDeclarations", altFromFqn = "<ROOT>") }
        }

        if (printReachabilityInfo) {
            reachabilityInfo.forEach(::println)
        }

        return result
    }

}

private fun Collection<IrClass>.filterDescendantsOf(bases: Collection<IrClass>): Collection<IrClass> {
    val visited = hashSetOf<IrClass>()
    val baseDescendants = hashSetOf<IrClass>()
    baseDescendants += bases

    fun overridesAnyBase(klass: IrClass): Boolean {
        if (klass in baseDescendants) return true
        if (klass in visited) return false

        visited += klass

        klass.superTypes.forEach {
            (it.classifierOrNull as? IrClassSymbol)?.owner?.let {
                if (overridesAnyBase(it)) {
                    baseDescendants += klass
                    return true
                }
            }
        }

        return false
    }

    return this.filter { overridesAnyBase(it) }
}