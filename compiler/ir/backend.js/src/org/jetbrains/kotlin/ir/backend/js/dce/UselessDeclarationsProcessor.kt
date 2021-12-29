/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.dce

import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.backend.js.JsCommonBackendContext
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.ir.JsIrBuilder
import org.jetbrains.kotlin.ir.backend.js.isKotlinPackage
import org.jetbrains.kotlin.ir.backend.js.unreachableDeclarationMethod
import org.jetbrains.kotlin.ir.backend.js.utils.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.js.config.RuntimeDiagnostic

class UselessDeclarationsProcessor(
    private val removeUnusedAssociatedObjects: Boolean,
    private val usefulDeclarations: Set<IrDeclaration>,
    private val context: JsCommonBackendContext,
    ) : IrElementVisitorVoid {
    override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
    }

    override fun visitFile(declaration: IrFile) {
        process(declaration)
    }

    private fun IrConstructorCall.shouldKeepAnnotation(): Boolean {
        associatedObject()?.let { obj ->
            if (obj !in usefulDeclarations) return false
        }
        return true
    }

    override fun visitClass(declaration: IrClass) {
        process(declaration)
        // Remove annotations for `findAssociatedObject` feature, which reference objects eliminated by the DCE.
        // Otherwise `JsClassGenerator.generateAssociatedKeyProperties` will try to reference the object factory (which is removed).
        // That will result in an error from the Namer. It cannot generate a name for an absent declaration.
        if (removeUnusedAssociatedObjects && declaration.annotations.any { !it.shouldKeepAnnotation() }) {
            declaration.annotations = declaration.annotations.filter { it.shouldKeepAnnotation() }
        }
    }

    // TODO bring back the primary constructor fix
    private fun process(container: IrDeclarationContainer) {
        container.declarations.transformFlat { member ->
            if (member !in usefulDeclarations) {
                member.processUselessDeclaration()
            } else {
                member.acceptVoid(this)
                null
            }
        }
    }

    private fun IrDeclaration.processUselessDeclaration(): List<IrDeclaration>? {
        return when {
            context.dceRuntimeDiagnostic != null -> {
                processWithDiagnostic()
                return null
            }
            else -> emptyList()
        }
    }

    private fun RuntimeDiagnostic.removingBody(): Boolean {
        return this != RuntimeDiagnostic.LOG
    }

    private fun IrDeclaration.processWithDiagnostic() {
        when (this) {
            is IrFunction -> processFunctionWithDiagnostic()
            is IrField -> processFieldWithDiagnostic()
            is IrDeclarationContainer -> declarations.forEach { it.processWithDiagnostic() }
        }
    }

    private fun IrFunction.processFunctionWithDiagnostic() {
        val dceRuntimeDiagnostic = context.dceRuntimeDiagnostic!!

        val isRemovingBody = dceRuntimeDiagnostic.removingBody()
        val targetMethod = dceRuntimeDiagnostic.unreachableDeclarationMethod(context as JsIrBackendContext)
        val call = JsIrBuilder.buildCall(
            target = targetMethod,
            type = targetMethod.owner.returnType
        )

        if (isRemovingBody) {
            body = context.irFactory.createBlockBody(
                UNDEFINED_OFFSET,
                UNDEFINED_OFFSET
            )
        }

        body?.prependFunctionCall(call)
    }

    private fun IrField.processFieldWithDiagnostic() {
        if (initializer != null && isKotlinPackage()) {
            initializer = null
        }
    }
}