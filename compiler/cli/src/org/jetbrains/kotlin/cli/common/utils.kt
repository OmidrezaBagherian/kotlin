/*
 * Copyright 2010-2017 JetBrains s.r.o.
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

package org.jetbrains.kotlin.cli.common

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageUtil
import org.jetbrains.kotlin.cli.jvm.plugins.PluginCliParser
import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.isSubpackageOf
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.util.Logger
import org.jetbrains.kotlin.utils.KotlinPaths
import java.io.File
import kotlin.system.exitProcess

fun checkKotlinPackageUsage(configuration: CompilerConfiguration, files: Collection<KtFile>): Boolean {
    if (configuration.getBoolean(CLIConfigurationKeys.ALLOW_KOTLIN_PACKAGE)) {
        return true
    }
    val messageCollector = configuration.get(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
    return checkKotlinPackageUsage(messageCollector, files)
}

fun checkKotlinPackageUsage(messageCollector: MessageCollector, files: Collection<KtFile>): Boolean {
    val kotlinPackage = FqName("kotlin")
    for (file in files) {
        if (file.packageFqName.isSubpackageOf(kotlinPackage)) {
            messageCollector.report(
                CompilerMessageSeverity.ERROR,
                "Only the Kotlin standard library is allowed to use the 'kotlin' package",
                MessageUtil.psiElementToMessageLocation(file.packageDirective!!)
            )
            return false
        }
    }
    return true
}

fun getLibraryFromHome(
    paths: KotlinPaths?,
    getLibrary: (KotlinPaths) -> File,
    libraryName: String,
    messageCollector: MessageCollector,
    noLibraryArgument: String
): File? {
    if (paths != null) {
        val stdlibJar = getLibrary(paths)
        if (stdlibJar.exists()) {
            return stdlibJar
        }
    }

    messageCollector.report(
        CompilerMessageSeverity.STRONG_WARNING, "Unable to find " + libraryName + " in the Kotlin home directory. " +
                "Pass either " + noLibraryArgument + " to prevent adding it to the classpath, " +
                "or the correct '-kotlin-home'", null
    )
    return null
}

fun MessageCollector.toLogger(): Logger =
    object : Logger {
        override fun error(message: String) {
            report(CompilerMessageSeverity.ERROR, message)
        }

        override fun fatal(message: String): Nothing {
            report(CompilerMessageSeverity.ERROR, message)
            exitProcess(1)
        }

        override fun warning(message: String) {
            report(CompilerMessageSeverity.WARNING, message)
        }

        override fun log(message: String) {
            report(CompilerMessageSeverity.LOGGING, message)
        }
    }

fun tryLoadScriptingPluginFromCurrentClassLoader(configuration: CompilerConfiguration): Boolean = try {
    val pluginRegistrarClass = PluginCliParser::class.java.classLoader.loadClass(
        "org.jetbrains.kotlin.scripting.compiler.plugin.ScriptingCompilerConfigurationComponentRegistrar"
    )
    val pluginRegistrar = pluginRegistrarClass.newInstance() as? ComponentRegistrar
    if (pluginRegistrar != null) {
        configuration.add(ComponentRegistrar.PLUGIN_COMPONENT_REGISTRARS, pluginRegistrar)
        true
    } else false
} catch (_: Throwable) {
    // TODO: add finer error processing and logging
    false
}
