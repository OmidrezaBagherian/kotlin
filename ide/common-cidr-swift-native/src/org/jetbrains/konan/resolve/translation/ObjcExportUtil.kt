package org.jetbrains.konan.resolve.translation

import org.jetbrains.kotlin.backend.konan.objcexport.ObjCExportWarningCollector
import org.jetbrains.kotlin.backend.konan.objcexport.ObjcExportHeaderGeneratorMobile
import org.jetbrains.kotlin.idea.FrontendInternals
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.idea.resolve.frontendService
import org.jetbrains.kotlin.resolve.deprecation.DeprecationResolver

@OptIn(FrontendInternals::class)
fun generateObjCHeaderLines(
    frameworkName: String,
    resolutionFacade: ResolutionFacade
): List<String> {
    val moduleDescriptor = resolutionFacade.moduleDescriptor
    val deprecationResolver = resolutionFacade.frontendService<DeprecationResolver>()

    val generator = ObjcExportHeaderGeneratorMobile.createInstance(
        KtFileTranslator.ObjCExportConfiguration(frameworkName),
        ObjCExportWarningCollector.SILENT,
        moduleDescriptor.builtIns,
        listOf(moduleDescriptor),
        deprecationResolver
    )

    generator.translateModule()
    return generator.build()
}