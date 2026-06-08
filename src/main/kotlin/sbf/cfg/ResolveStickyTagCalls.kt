/*
 *     The Certora Prover
 *     Copyright (C) 2026  Certora Ltd.
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

package sbf.cfg

import sbf.SolanaConfig
import sbf.analysis.AnalysisRegisterTypes
import sbf.analysis.GenericScalarAnalysis
import sbf.callgraph.CVTCalltrace
import sbf.disassembler.GlobalVariables
import sbf.disassembler.SbfRegister
import sbf.domains.*
import sbf.sbfLogger
import datastructures.stdcollections.*
import utils.mapToSet

/**
 * Options for [resolveStickyTagCalls].
 */
data class StickyTagOpts(
    val runScalarAnalysis: Boolean = false,
    val assumeAndAssertConsumeStickyTag: Boolean = true
)

/**
 * For each basic block, find a [CVTCalltrace.STICKY_TAG] call and extract the tag (i.e., string).
 * That string is then attached as metadata to the next calltrace call in the same
 * block whose [CVTCalltrace.consumeStickyTag] is `true`. All calls to [CVTCalltrace.STICKY_TAG] are removed.
 */
fun resolveStickyTagCalls(
    cfg: MutableSbfCFG,
    globals: GlobalVariables,
    memSummaries: MemorySummaries,
    opts: StickyTagOpts = StickyTagOpts()
) {
    val hasCalltraceCall = cfg.getBlocks().values.any { b ->
        b.getInstructions().any { it is SbfInstruction.Call && it.name == CVTCalltrace.STICKY_TAG.function.name }
    }
    if (!hasCalltraceCall) {
        return
    }

    if (opts.runScalarAnalysis) {
        val sbfTypesFac = ConstantSetSbfTypeFactory(SolanaConfig.ScalarMaxVals.get().toULong())
        val scalarAnalysis = GenericScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac, CFGTransformScalarDomFac())
        val types = AnalysisRegisterTypes(scalarAnalysis)
        val getStringForR1: (LocatedSbfInstruction) -> String? = { locInst ->
            (types.typeAtInstruction(locInst, SbfRegister.R1) as? SbfType.PointerType.Global)?.global?.strValue
        }
        resolveStickyTagCalls(cfg, getStringForR1, opts)
    } else {
        val getStringForR1: (LocatedSbfInstruction) -> String? = { locInst ->
            val b = checkNotNull(cfg.getBlock(locInst.label))
            val r1 = Value.Reg(SbfRegister.R1)
            val defLocInst = findDefinitionInterBlock(b, r1, locInst.pos)
            val defInst = defLocInst?.inst
            if (defInst is SbfInstruction.Bin && defInst.op == BinOp.MOV && defInst.metaData.getVal(SbfMeta.SET_GLOBAL) != null) {
                (defInst.v as? Value.Imm)?.let { globals.findGlobalThatContains(it.v.toLong())?.strValue }
            } else {
                null
            }
        }
        resolveStickyTagCalls(cfg, getStringForR1, opts)
    }
}

/**
 * Search for [CVTCalltrace.STICKY_TAG] calls, resolve `r1` to a string using [getStringForR1], and propagate the
 * tag to the next [CVTCalltrace.consumeStickyTag] call in the same block.
 */
private fun resolveStickyTagCalls(
    cfg: MutableSbfCFG,
    getStringForR1: (LocatedSbfInstruction) -> String?,
    opts: StickyTagOpts
) {

    fun attachTag(locInst: LocatedSbfInstruction, tag: String): SbfInstruction =
        locInst.inst.copyInst(locInst.inst.metaData + (SbfMeta.STICKY_TAG to tag))

    for (block in cfg.getMutableBlocks().values) {
        var stickyTag: String? = null
        val remap = mutableMapOf<LocatedSbfInstruction, List<SbfInstruction>>()
        for (locInst in block.getLocatedInstructions()) {
            val inst = locInst.inst
            if (opts.assumeAndAssertConsumeStickyTag) {
                when {
                    inst is SbfInstruction.Assert -> {
                        val tag = stickyTag ?: continue
                        remap[locInst] = listOf(attachTag(locInst, tag))
                        stickyTag = null
                    }

                    inst is SbfInstruction.Assume && !inst.isLoweredAssume() -> {
                        val tag = stickyTag ?: continue
                        remap[locInst] = listOf(attachTag(locInst, tag))
                        stickyTag = null
                    }
                }
            }

            if (inst !is SbfInstruction.Call) {
                continue
            }
            val calltraceFn = CVTCalltrace.from(inst.name) ?: continue
            when {
                calltraceFn == CVTCalltrace.STICKY_TAG -> {
                    if (stickyTag != null) {
                        sbfLogger.warn { "STICKY_TAG \"$stickyTag\" was not consumed before being overwritten at $inst" }
                    }
                    check(SbfRegister.R1 in calltraceFn.strings.mapToSet { it.string.r })
                    stickyTag = getStringForR1(locInst)
                        ?: run {
                            sbfLogger.warn { "Cannot identify statically the string for $inst" }
                            null
                        }
                    remap[locInst] = emptyList() // this will remove the call to STICKY_TAG
                }

                calltraceFn.consumeStickyTag -> {
                    val tag = stickyTag ?: continue
                    remap[locInst] = listOf(attachTag(locInst, tag))
                    stickyTag = null
                }
            }
        }
        if (stickyTag != null) {
            sbfLogger.warn { "STICKY_TAG \"$stickyTag\" was not consumed in block ${block.getLabel()}" }
        }
        if (remap.isNotEmpty()) {
            block.replaceInstructions(remap)
        }
    }
}
