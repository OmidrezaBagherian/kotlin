/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.mpp.pm20

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.BasePluginExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptionsImpl
import org.jetbrains.kotlin.gradle.plugin.*
import org.jetbrains.kotlin.gradle.plugin.mpp.*
import org.jetbrains.kotlin.gradle.plugin.mpp.filterModuleName
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

open class KotlinJvmVariant(containingModule: KotlinGradleModule, fragmentName: String) :
    KotlinGradlePublishedVariantWithRuntime(containingModule, fragmentName) {
    override val compilationData: KotlinJvmVariantCompilationData by lazy { KotlinJvmVariantCompilationData(this) }

    override val platformType: KotlinPlatformType
        get() = KotlinPlatformType.jvm
}

class KotlinJvmVariantCompilationData(val variant: KotlinJvmVariant) : KotlinVariantCompilationDataInternal<KotlinJvmOptions> {
    override val owner: KotlinJvmVariant get() = variant

    // TODO pull out to the variant
    override val kotlinOptions: KotlinJvmOptions = KotlinJvmOptionsImpl()
}

internal fun KotlinGradleVariant.ownModuleName(): String {
    val project = containingModule.project
    val baseName = project.extensions.getByType(BasePluginExtension::class.java).archivesName.orNull
        ?: project.name
    val suffix = if (containingModule.moduleClassifier == null) "" else "_${containingModule.moduleClassifier}"
    return filterModuleName("$baseName$suffix")
}

class KotlinMappedJvmCompilationFactory(
    target: KotlinJvmTarget
) : KotlinJvmCompilationFactory(target) {
    override fun create(name: String): KotlinJvmCompilation {
        val module = target.project.kpmModules.maybeCreate(name)
        val variant = module.fragments.create(target.name, KotlinJvmVariant::class.java)

        return KotlinJvmCompilation(
            VariantMappedCompilationDetailsWithRuntime(variant, target),
        )
    }
}