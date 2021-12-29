/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.wasm.dce

import org.jetbrains.kotlin.backend.wasm.WasmBackendContext
import org.jetbrains.kotlin.ir.backend.js.dce.UsefulDeclarationProcessor
import org.jetbrains.kotlin.ir.backend.js.dce.UselessDeclarationsProcessor
import org.jetbrains.kotlin.ir.backend.js.utils.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.js.config.JSConfigurationKeys
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.utils.addIfNotNull

internal fun eliminateDeadDeclarations(
    modules: Iterable<IrModuleFragment>,
    context: WasmBackendContext,
    exportedDeclarations: Set<FqName>,
    removeUnusedAssociatedObjects: Boolean = true,
) {

    val allRoots = buildRoots(modules, context, exportedDeclarations)

    val printReachabilityInfo = true
//        context.configuration.getBoolean(JSConfigurationKeys.PRINT_REACHABILITY_INFO) ||
//                java.lang.Boolean.getBoolean("kotlin.wasm.dce.print.reachability.info")

    val usefulDeclarations = UsefulDeclarationProcessor(printReachabilityInfo).let { processor ->
        val dceBodyVisitor = processor.createWasmBodyVisitor(context)
        processor.usefulDeclarations(
            bodyVisitor = dceBodyVisitor,
            roots = allRoots,
            context = context,
            removeUnusedAssociatedObjects = removeUnusedAssociatedObjects
        )
    }

    val uselessDeclarationsProcessor = UselessDeclarationsProcessor(removeUnusedAssociatedObjects, usefulDeclarations, context)
    modules.forEach { module ->
        module.files.forEach {
            it.acceptVoid(uselessDeclarationsProcessor)
        }
    }
}
//
//private fun IrField.isConstant(): Boolean {
//    return correspondingPropertySymbol?.owner?.isConst ?: false
//}
//
//private fun IrDeclaration.addRootsTo(to: MutableCollection<IrDeclaration>, context: WasmBackendContext) {
//    when {
//        this is IrProperty -> {
//            backingField?.addRootsTo(to, context)
//            getter?.addRootsTo(to, context)
//            setter?.addRootsTo(to, context)
//        }
//        isEffectivelyExternal() -> {
//            to += this
//        }
//        isExported(context) -> {
//            to += this
//        }
//        this is IrField -> {
//            // TODO: simplify
//            if ((initializer != null && !isKotlinPackage() || correspondingPropertySymbol?.owner?.isExported(context) == true) && !isConstant()) {
//                to += this
//            }
//        }
//        this is IrSimpleFunction -> {
//            if (correspondingPropertySymbol?.owner?.isExported(context) == true) {
//                to += this
//            }
//        }
//    }
//}

private fun buildRoots(modules: Iterable<IrModuleFragment>, context: WasmBackendContext, exportedDeclarations: Set<FqName>): Iterable<IrDeclaration> {
    val rootDeclarations = mutableListOf<IrDeclaration>()

//    rootDeclarations.addAll(context.testEntryPoints)
//    rootDeclarations.addIfNotNull(context.testFun?.owner)
    exportedDeclarations.toString()

    rootDeclarations.add(context.irBuiltIns.throwableClass.owner)

    modules.forEach { module ->
        module.files.forEach { file ->
            file.declarations.forEach { declaration ->
                if (declaration.isJsExport() || declaration.isEffectivelyExternal()) {
                    rootDeclarations.add(declaration)
                }

//                if (declaration is IrFunction && declaration.fqNameWhenAvailable in exportedDeclarations) {
//                    rootDeclarations.add(declaration)
//                }
            }
        }
    }

//    val allFiles = (modules.flatMap { it.files } + context.packageLevelJsModules + context.externalPackageFragment.values)
//    allFiles.forEach {
//        it.declarations.forEach {
//            it.addRootsTo(rootDeclarations, context)
//        }
//    }
//
//    rootDeclarations += context.testFunsPerFile.values

//    val dceRuntimeDiagnostic = context.dceRuntimeDiagnostic
//    if (dceRuntimeDiagnostic != null) {
//        rootDeclarations += dceRuntimeDiagnostic.unreachableDeclarationMethod(context).owner
//    }

    // TODO: Generate calls to main as IR->IR lowering and reference coroutineEmptyContinuation directly
    JsMainFunctionDetector(context).getMainFunctionOrNull(modules.last())?.let { mainFunction ->
        rootDeclarations += mainFunction
//        if (mainFunction.isLoweredSuspendFunction(context)) {
//            rootDeclarations += context.coroutineEmptyContinuation.owner
//        }
    }

    return rootDeclarations
}


//
//internal fun RuntimeDiagnostic.unreachableDeclarationMethod(context: WasmBackendContext) =
//    when (this) {
//        RuntimeDiagnostic.LOG -> context.intrinsics.jsUnreachableDeclarationLog
//        RuntimeDiagnostic.EXCEPTION -> context.intrinsics.jsUnreachableDeclarationException
//    }

internal fun IrField.isKotlinPackage() =
    fqNameWhenAvailable?.asString()?.startsWith("kotlin") == true

