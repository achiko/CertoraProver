/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ksp.hooks

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.*
import ksp.CertoraAnnotationProcessor

private fun tabs(n: Int) = "    ".repeat(n)
class OpcodeHooksProcessor(private val environment: SymbolProcessorEnvironment) : SymbolProcessor,
    CertoraAnnotationProcessor {

    override val annotationPackage = "vc.data.annotation"
    private val outputAnnotation = "OpcodeOutput"
    private val paramAnnotation = "OpcodeParameter"
    companion object {
        const val hookAnnotation = "HookableOpcode"
        const val onlyNoStorageSplittingProp = "onlyNoStorageSplitting"
        const val envParamAnnotation = "OpcodeEnvironmentParam"
    }

    private val tacSymbol = "vc.data.TACSymbol"
    private val tacSymbolVar = "$tacSymbol.Var"

    /**
     * An instrumentation for the code we need for hooks.
     * @param decl - The name of the opcode we generate classes/code for
     * @param summaryGenerator - The relevant part to the opcode of conversion function from TACCmd.EVM to the summary, including backup code for the params of the EVM command
     * @param summaryDeclaration - The declaration of the summary added in Simplifier using [summaryGenerator]
     * @param hookDeclaration - the TACHook pattern declaration
     * @param matchGenerator - the match function for the TACHook pattern
     */
    private data class Instrumentation(
        val decl: KSClassDeclaration,
        val summaryGenerator: () -> String,
        val summaryDeclaration: () -> String,
        val hookDeclaration: () -> String,
        val matchGenerator: () -> String
    )

    override fun isAnnotation(
        it: KSAnnotation,
        shortName: String,
        qualified: String,
    ) =
        it.shortName.asString() == shortName && it.annotationType.resolve().declaration.qualifiedName?.asString() == qualified

    sealed class ValueSource {
        data class CommandField(val fieldName: String) : ValueSource()
        data class EnvVariable(val variableName: String) : ValueSource()
        data class CustomBinding(val generatorClass: KSType) : ValueSource()
    }

    private data class FieldSpec(
        /**
         * the name of the field in the `TACCmd.Simple` or `TACCmd.EVM` instance
         */
        val valueSource: ValueSource,
        /**
         * The type of the field (expected to by TACSymbol.Var or TACSymbol
         */
        val type: String,
        /**
         * Is this an output
         */
        val isOutput: Boolean,
        /**
         * The name of the field in the hook/summary object (pretty printed name)
         */
        val fieldName: String,
        /**
         * Is this field an override from the super class
         */
        val isOverride: Boolean = false,
        /**
         * Is this implicit bound, context variable match?
         */
        val contextMatchName: String? = null
    )

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val opcodes = resolver.getSymbolsWithAnnotation("$annotationPackage.$hookAnnotation")
        val visitedFiles = mutableSetOf<KSFile>()
        val toGen = opcodes.map {
            val toRet = it as KSClassDeclaration
            if(toRet.classKind != ClassKind.CLASS || Modifier.DATA !in toRet.modifiers) {
                environment.logger.error("Non-data class declaration annotated with HookableOpcode", it)
            }
            toRet
        }.map { ret ->
            val annot = ret.annotations.find {
                it.isAnnotation(hookAnnotation)
            }!!
            val opcodeName = annot.arguments.find {
                it.name?.asString() == "name"
            }!!.value!!.toString()
            val declarationName = annot.arguments.find {
                it.name?.asString() == "hookname"
            }!!.value!!.toString().takeIf { it.isNotBlank() } ?: (opcodeName.substring(0, 1) + opcodeName.substring(1).lowercase())
            val dependentOnStorageSplitting = annot.arguments.find {
                it.name?.asString() == onlyNoStorageSplittingProp
            }!!.value!! as Boolean
            val props = ret.getDeclaredProperties().filter { prop ->
                prop.annotations.any { annot ->
                    annot.isAnnotation(outputAnnotation) || annot.isAnnotation(paramAnnotation)
                }
            }.toList()
            @Suppress("UNCHECKED_CAST")
            val extraInterfaces = annot.arguments.find {
                it.name?.asString() == "additionalInterfaces"
            }!!.value!! as List<KSType>
            if(props.isEmpty()) {
                environment.logger.error("No properties found in hookable opcode", ret)
                throw IllegalStateException("rip")
            }
            ret.containingFile?.let(visitedFiles::add)
                ?: environment.logger.warn("Could not find Kotlin file containing ${ret.qualifiedName}", ret)
            val (outputs, params) = props.partition {
                it.hasAnnotation("OpcodeOutput")
            }
            if(outputs.size > 1) {
                environment.logger.error("Multitple outputs declared for opcode", ret)
            }

            val summaryName = "${declarationName}OpcodeSummary"

            val matchName = "${declarationName}Match"

            val summaryFields = outputs.map {
                FieldSpec(
                    valueSource = ValueSource.CommandField(it.simpleName.asString()),
                    fieldName = "output",
                    type = tacSymbolVar,
                    isOutput = true
                )
            } + params.map {
                val name = it.annotations.find {
                    it.isAnnotation(paramAnnotation)
                }?.arguments?.find {
                    it.name?.asString() == "name"
                }?.value?.toString()!!.takeIf {
                    it.isNotBlank()
                } ?: it.simpleName.asString()
                FieldSpec(
                    valueSource = ValueSource.CommandField(it.simpleName.asString()),
                    fieldName = name,
                    type = it.type.resolve().declaration.qualifiedName?.asString()!!,
                    isOutput = false
                )
            } + FieldSpec(
                valueSource = ValueSource.EnvVariable("address"),
                fieldName = "hostContract",
                type = tacSymbolVar,
                isOutput = false,
                isOverride = true,
                contextMatchName = "executingContractVar"
            ) + ret.annotations.filter {
                it.isAnnotation(envParamAnnotation)
            }.map {
                val nm = it.arguments.find {
                    it.name?.asString() == "paramName"
                }!!
                FieldSpec(
                    fieldName = nm.value.toString(),
                    isOverride = false,
                    isOutput = false,
                    type = tacSymbolVar,
                    contextMatchName = null,
                    valueSource = ValueSource.CustomBinding(
                        it.arguments.find {
                            it.name?.asString() == "generator"
                        }!!.value!!.let {
                            it as KSType
                        }
                    )
                )
            }

            val summaryGenerator = gen@{ ->
                val needsParamBackup = outputs.isNotEmpty() && params.isNotEmpty()
                val isClause = "is ${ret.qualifiedName!!.asString()}"
                // that is, we have some subset of fields that need to be backed up
                val backupCommands = mutableListOf<Pair<String, String>>()
                /*
                  name of the variables which hold the custom generation commands
                 */
                val customGeneration = mutableListOf<String>()
                /*
                  Code that binds the generation binding
                 */
                val customGenerationBindings = mutableListOf<String>()
                val binding = summaryFields.map { fieldSpec ->
                    val commandFieldAccess = when(fieldSpec.valueSource) {
                        is ValueSource.CommandField -> "c.${fieldSpec.valueSource.fieldName}"
                        is ValueSource.EnvVariable -> fieldSpec.valueSource.variableName
                        is ValueSource.CustomBinding -> {
                            val outputName = "${fieldSpec.fieldName}Sym"
                            val commandGeneration = "${fieldSpec.fieldName}Cmds"
                            customGenerationBindings.add("""
                                val ($outputName, $commandGeneration) = ${fieldSpec.valueSource.generatorClass.declaration.qualifiedName!!.asString()}().bindEnvironmentParam(c)
                            """.trimIndent())
                            customGeneration.add(commandGeneration)
                            outputName
                        }
                    }
                    val fieldValue = if(needsParamBackup && !fieldSpec.isOutput && fieldSpec.valueSource !is ValueSource.CustomBinding) {
                        val backupVar = "backupFor${fieldSpec.fieldName}"
                        backupCommands.add(backupVar to """
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                               lhs = $backupVar,
                               rhs = $commandFieldAccess.asSym()
                            )
                        """.trimIndent())
                        backupVar
                    } else {
                        commandFieldAccess
                    }
                    "${fieldSpec.fieldName} = $fieldValue"
                }
                check(!needsParamBackup || (backupCommands.isNotEmpty() && backupCommands.size < summaryFields.size)) {
                    "A summary $summaryName either does not need param backup (needsParamBackup=$needsParamBackup), or has a sufficient number of backup commands (backupCommands=$backupCommands, summaryFields=$summaryFields)"
                }

                // certain hook opcodes depend on DISABLING storage splitting
                // thus, we make sure that only for those opcodes, we add to the code the check
                // whether storage splitting is enabled or not
                fun wrapWithConditionalOnStorageSplitting(txt: String) =
                    if (dependentOnStorageSplitting) {
                        """if (!Config.EnableStorageSplitting.get()) {
                            $txt
                        } else { null }
                    """.trimIndent()
                    } else {
                        txt
                    }

                val instanceCreation = "vc.data.$summaryName(${binding.joinToString(", ")})"
                if(!needsParamBackup && customGenerationBindings.isEmpty()) {
                    return@gen "$isClause -> ${wrapWithConditionalOnStorageSplitting("null to $instanceCreation")}"
                }
                val tmpGeneration = backupCommands.joinToString("\n") { (varName, _) ->
                    val generation = """
                         TACKeyword.TMP(Tag.Bit256, "!$varName").toUnique("!")
                    """.trimIndent()
                    """
                        val $varName = $generation
                    """.trimIndent()
                } + "\n" + customGenerationBindings.joinToString("\n ")
                val generatedPrefix = if(backupCommands.isEmpty()) {
                    check(customGeneration.isNotEmpty())
                    if (customGeneration.size == 1) {
                        customGeneration.single()
                    } else {
                        """
                            CommandWithRequiredDecls.mergeMany(${customGeneration.joinToString(", ")}
                        """.trimIndent()
                    }
                } else {
                    val backupCmds = """
                        CommandWithRequiredDecls(listOf(
                            ${
                            backupCommands.joinToString(",\n") {
                                it.second
                            }
                        }
                         ), setOf(${
                            backupCommands.joinToString(", ") {
                                it.first
                            }
                        }))
                    """.trimIndent()
                    if(customGeneration.isEmpty()) {
                        backupCmds
                    } else {
                        """
                            CommandWithRequiredDecls.mergeMany($backupCmds, ${customGeneration.joinToString(", ")})
                        """.trimIndent()
                    }
                }
                """
                    $isClause -> {
                        ${wrapWithConditionalOnStorageSplitting(
                            """$tmpGeneration
                            $generatedPrefix to $instanceCreation"""
                        )}
                    }
                """.trimIndent()
            }

            val contextParam = summaryFields.filter {
                it.contextMatchName != null
            }

            val hookDeclaration = { ->
                val hookParameters = summaryFields.mapNotNull { fld ->
                    if(fld.contextMatchName != null) {
                        return@mapNotNull null
                    }
                    val fieldName = fld.fieldName
                    "val $fieldName: VMParam.Named"
                }

                val theCommand = "lc.cmd"

                val matchBinding = summaryFields.mapNotNull { fld ->
                    if(fld.valueSource is ValueSource.CommandField || fld.valueSource is ValueSource.CustomBinding) {
                        val fieldName = fld.fieldName
                        "$fieldName to $theCommand.summ.${fieldName}.asSym().inject()"
                    } else {
                        null
                    }
                }


                val contextMatching = if(contextParam.isEmpty()) {
                    ""
                } else {
                    ", " + contextParam.joinToString(", ") {
                        "${it.contextMatchName!!} = $theCommand.summ.${it.fieldName}"
                    }
                }

                """
                |data class $declarationName(
                |    ${hookParameters.joinToString(",\n|${tabs(1)}")},
                |): TACHookPattern.OpcodeHook() {
                |    override fun matches(lc: analysis.LTACCmd, context: CoreTACProgram): HookMatch.OpcodeMatch? =
                |        if($theCommand is TACCmd.Simple.SummaryCmd && $theCommand.summ is $summaryName) {
                |            $matchName(substitutions = mapOf(
                |                ${matchBinding.joinToString(",\n|${tabs(4)}")}
                |            )$contextMatching)
                |        } else {
                |            null
                |        }
                |}
                """.trimMargin()
            }
            val matchGenerator = {
                val contextPrefix = contextParam.joinToString("") {
                    check(it.isOverride)
                    "override val ${it.contextMatchName!!}: ${it.type},"
                }

                """
                    |data class $matchName(
                    |    $contextPrefix
                    |    override val substitutions: Map<VMParam.Named, HookValue> = emptyMap()
                    |): HookMatch.OpcodeMatch()
                """.trimMargin()
            }

            val extraSuperTypes = if(extraInterfaces.isNotEmpty()) {
                extraInterfaces.joinToString(", ", prefix = ", ") { it: KSType ->
                    it.declaration.qualifiedName?.asString()!!
                }
            } else {
                ""
            }

            val summaryDeclaration = { ->
                val summaryNames = summaryFields.map { fld ->
                    val prefix = if(fld.isOverride) {
                        "override "
                    } else {
                        ""
                    }
                    "${prefix}val ${fld.fieldName}: ${fld.type}"
                }.joinToString(", ")

                val variableFields = summaryFields.mapNotNull { fld ->
                    val nm = fld.fieldName
                    val typ = fld.type
                    if(typ == tacSymbolVar) {
                        nm to (nm to "f($nm)")
                    } else if(typ == tacSymbol) {
                        val castExpr = "$nm as? $tacSymbolVar"
                        nm to (castExpr to "($castExpr)?.let(f) ?: $nm")
                    } else {
                        null
                    }
                }
                val variableRefs = variableFields.map {
                    it.second.first
                }

                val copyImpl = """
                    this.copy(
                       ${variableFields.joinToString(",\n") { (nm, fld) ->
                           "$nm = ${fld.second}"
                        }}
                    )
                """.trimIndent()

                """
                    @kotlinx.serialization.Serializable
                    data class $summaryName($summaryNames) : OpcodeSummary()$extraSuperTypes {
                       override val opcode: String = "$opcodeName"
                       override val annotationDesc: String = "Opcode summary for $opcodeName - ${variableFields.joinToString(", ") { (nm, _) -> "$nm = $${nm}" }}"

                       override val variables: Set<$tacSymbolVar> = setOfNotNull(${variableRefs.joinToString(", ")})
                       override fun transformSymbols(f: ($tacSymbolVar) -> $tacSymbolVar) : $summaryName {
                          return $copyImpl
                       }
                    }
                """.trimIndent()
            }
            Instrumentation(
                decl = ret,
                hookDeclaration = hookDeclaration,
                matchGenerator = matchGenerator,
                summaryDeclaration = summaryDeclaration,
                summaryGenerator = summaryGenerator
            )
        }.toList()
        if(toGen.isEmpty()) {
            return emptyList()
        }
        // Generate the summary declarations
        val dependencies = Dependencies(true, *visitedFiles.toTypedArray())
        environment.codeGenerator.createNewFile(dependencies, "vc.data", "OpcodeSummary", "kt").bufferedWriter().use {
            it.write("""
                package vc.data

                import datastructures.stdcollections.*

                ${toGen.joinToString("\n\n") {
                        it.summaryDeclaration()
                }}
            """.trimIndent())
        }
        environment.codeGenerator.createNewFile(dependencies, "vc.data", "OpcodeHooks", "kt").bufferedWriter().use {
            it.write("""
                |package vc.data

                |import spec.cvlast.VMParam
                |import datastructures.stdcollections.*

                |${toGen.joinToString("\n\n") {
                    it.hookDeclaration()
                }}

                |${toGen.joinToString("\n\n") {
                    it.matchGenerator()
                }}
                """.trimMargin())
        }

        environment.codeGenerator.createNewFile(dependencies, "analysis", "OpcodeSummaryGenerator", "kt").bufferedWriter().use {
            it.write("""
                package analysis

                import config.Config
                import vc.data.TACSummary
                import vc.data.TACCmd
                import vc.data.TACKeyword
                import tac.Tag
                import datastructures.stdcollections.*
                import vc.data.TACSymbol

                fun toOpcodeSummary(c: TACCmd, address: TACSymbol.Var): Pair<CommandWithRequiredDecls<TACCmd.Simple>?, TACSummary>? {
                   return when(c) {
                      ${toGen.joinToString("\n") {
                          it.summaryGenerator()
                        }}
                      else -> null
                   }
                }
            """.trimIndent())
        }
        return listOf()
    }

}
