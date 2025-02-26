/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.python.smithy

import software.amazon.smithy.build.PluginContext
import software.amazon.smithy.build.SmithyBuildPlugin
import software.amazon.smithy.codegen.core.ReservedWordSymbolProvider
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.ServiceShape
import software.amazon.smithy.rust.codegen.client.smithy.EventStreamSymbolProvider
import software.amazon.smithy.rust.codegen.client.smithy.customize.CombinedCodegenDecorator
import software.amazon.smithy.rust.codegen.core.rustlang.RustReservedWordSymbolProvider
import software.amazon.smithy.rust.codegen.core.smithy.BaseSymbolMetadataProvider
import software.amazon.smithy.rust.codegen.core.smithy.CodegenTarget
import software.amazon.smithy.rust.codegen.core.smithy.SymbolVisitor
import software.amazon.smithy.rust.codegen.core.smithy.SymbolVisitorConfig
import software.amazon.smithy.rust.codegen.server.python.smithy.customizations.DECORATORS
import software.amazon.smithy.rust.codegen.server.smithy.ServerCodegenContext
import software.amazon.smithy.rust.codegen.server.smithy.customizations.ServerRequiredCustomizations
import software.amazon.smithy.rust.codegen.server.smithy.generators.protocol.ServerProtocolGenerator
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Rust with Python bindings Codegen Plugin.
 * This is the entrypoint for code generation, triggered by the smithy-build plugin.
 * `resources/META-INF.services/software.amazon.smithy.build.SmithyBuildPlugin` refers to this class by name which
 * enables the smithy-build plugin to invoke `execute` with all of the Smithy plugin context + models.
 */
class PythonCodegenServerPlugin : SmithyBuildPlugin {
    private val logger = Logger.getLogger(javaClass.name)

    override fun getName(): String = "rust-server-codegen-python"

    override fun execute(context: PluginContext) {
        // Suppress extremely noisy logs about reserved words
        Logger.getLogger(ReservedWordSymbolProvider::class.java.name).level = Level.OFF
        // Discover [RustCodegenDecorators] on the classpath. [RustCodegenDecorator] return different types of
        // customization. A customization is a function of:
        // - location (e.g. the mutate section of an operation)
        // - context (e.g. the of the operation)
        // - writer: The active RustWriter at the given location
        val codegenDecorator: CombinedCodegenDecorator<ServerProtocolGenerator, ServerCodegenContext> =
            CombinedCodegenDecorator.fromClasspath(
                context,
                CombinedCodegenDecorator(DECORATORS + ServerRequiredCustomizations()),
            )

        // PythonServerCodegenVisitor is the main driver of code generation that traverses the model and generates code
        logger.info("Loaded plugin to generate Rust/Python bindings for the server SSDK for projection ${context.projectionName}")
        PythonServerCodegenVisitor(context, codegenDecorator).execute()
    }

    companion object {
        /** SymbolProvider
         * When generating code, smithy types need to be converted into Rust types—that is the core role of the symbol provider
         *
         * The Symbol provider is composed of a base [SymbolVisitor] which handles the core functionality, then is layered
         * with other symbol providers, documented inline, to handle the full scope of Smithy types.
         */
        fun baseSymbolProvider(
            model: Model,
            serviceShape: ServiceShape,
            symbolVisitorConfig: SymbolVisitorConfig,
        ) =
            // Rename a set of symbols that do not implement `PyClass` and have been wrapped in
            // `aws_smithy_http_server_python::types`.
            PythonServerSymbolVisitor(model, serviceShape = serviceShape, config = symbolVisitorConfig)
                // Generate different types for EventStream shapes (e.g. transcribe streaming)
                .let {
                    EventStreamSymbolProvider(symbolVisitorConfig.runtimeConfig, it, model, CodegenTarget.SERVER)
                }
                // Add Rust attributes (like `#[derive(PartialEq)]`) to generated shapes
                .let { BaseSymbolMetadataProvider(it, model, additionalAttributes = listOf()) }
                // Streaming shapes need different derives (e.g. they cannot derive Eq)
                .let { PythonStreamingShapeMetadataProvider(it, model) }
                // Rename shapes that clash with Rust reserved words & and other SDK specific features e.g. `send()` cannot
                // be the name of an operation input
                .let { RustReservedWordSymbolProvider(it, model) }
    }
}
