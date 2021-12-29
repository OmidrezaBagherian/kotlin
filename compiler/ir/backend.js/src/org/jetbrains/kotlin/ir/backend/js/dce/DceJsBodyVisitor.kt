/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.dce

import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.JsStatementOrigins
import org.jetbrains.kotlin.ir.backend.js.utils.invokeFunForLambda
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.primaryConstructor

internal fun UsefulDeclarationProcessor.createJsBodyVisitor(context: JsIrBackendContext) =
    object : UsefulDeclarationProcessor.BodyVisitorBase(context) {
        override fun visitCall(expression: IrCall) {
            super.visitCall(expression)

            when (expression.symbol) {
                context.intrinsics.jsBoxIntrinsic -> {
                    val inlineClass = context.inlineClassesUtils.getInlinedClass(expression.getTypeArgument(0)!!)!!
                    val constructor = inlineClass.declarations.filterIsInstance<IrConstructor>().single { it.isPrimary }
                    constructor.enqueue("intrinsic: jsBoxIntrinsic")
                }
                context.intrinsics.jsClass -> {
                    val ref = expression.getTypeArgument(0)!!.classifierOrFail.owner as IrDeclaration
                    ref.enqueue("intrinsic: jsClass")
                    referencedJsClasses += ref
                    // When class reference provided as parameter to external function
                    // It can be instantiated by external JS script
                    // Need to leave constructor for this
                    // https://youtrack.jetbrains.com/issue/KT-46672
                    // TODO: Possibly solution with origin is not so good
                    //  There is option with applying this hack to jsGetKClass
                    if (expression.origin == JsStatementOrigins.CLASS_REFERENCE) {
                        // Maybe we need to filter primary constructor
                        // Although at this time, we should have only primary constructor
                        (ref as IrClass)
                            .constructors
                            .forEach {
                                it.enqueue("intrinsic: jsClass (constructor)")
                            }
                    }
                }
                context.reflectionSymbols.getKClassFromExpression -> {
                    val ref = expression.getTypeArgument(0)?.classOrNull ?: context.irBuiltIns.anyClass
                    referencedJsClassesFromExpressions += ref.owner
                }
                context.intrinsics.jsObjectCreate -> {
                    val classToCreate = expression.getTypeArgument(0)!!.classifierOrFail.owner as IrClass
                    classToCreate.enqueue("intrinsic: jsObjectCreate")
                    constructedClasses += classToCreate
                }
                context.intrinsics.jsEquals -> {
                    equalsMethod.enqueue("intrinsic: jsEquals")
                }
                context.intrinsics.jsToString -> {
                    toStringMethod.enqueue("intrinsic: jsToString")
                }
                context.intrinsics.jsHashCode -> {
                    hashCodeMethod.enqueue("intrinsic: jsHashCode")
                }
                context.intrinsics.jsPlus -> {
                    if (expression.getValueArgument(0)?.type?.classOrNull == context.irBuiltIns.stringClass) {
                        toStringMethod.enqueue("intrinsic: jsPlus")
                    }
                }
                context.intrinsics.jsConstruct -> {
                    val callType = expression.getTypeArgument(0)!!
                    val constructor = callType.getClass()!!.primaryConstructor
                    constructor!!.enqueue("ctor call from jsConstruct-intrinsic")
                }
                context.intrinsics.es6DefaultType -> {
                    //same as jsClass
                    val ref = expression.getTypeArgument(0)!!.classifierOrFail.owner as IrDeclaration
                    ref.enqueue("intrinsic: jsClass")
                    referencedJsClasses += ref

                    //Generate klass in `val currResultType = resultType || klass`
                    val arg = expression.getTypeArgument(0)!!
                    val klass = arg.getClass()
                    if (klass != null) {
                        constructedClasses.add(klass)
                    }
                }
                context.intrinsics.jsInvokeSuspendSuperType,
                context.intrinsics.jsInvokeSuspendSuperTypeWithReceiver,
                context.intrinsics.jsInvokeSuspendSuperTypeWithReceiverAndParam -> {
                    invokeFunForLambda(expression)
                        .enqueue("intrinsic: suspendSuperType")
                }
            }
        }
    }