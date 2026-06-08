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

import log.Logger
import log.LoggerTypes
import sbf.SolanaConfig
import sbf.analysis.AnalysisCacheOptions
import sbf.analysis.AnalysisRegisterTypes
import sbf.analysis.GenericScalarAnalysis
import sbf.disassembler.GlobalVariables
import sbf.domains.ConstantSet
import sbf.domains.ConstantSetSbfTypeFactory
import sbf.domains.ISbfTypeFactory
import sbf.domains.MemorySummaries
import sbf.domains.ScalarRegisterStackEqualityDomainFactory
import kotlin.toULong
import datastructures.stdcollections.*
import log.regression
import sbf.analysis.LivenessAnalysis

private val logger = Logger(LoggerTypes.SBF_MATH_PROMOTION)
private fun dbg(msg: () -> Any) {
    logger.info(msg)
}

interface MathIntrinsicPattern {
    /** Name of the math intrinsics **/
    val intrinsicName: String

    /** Instructions that implement a recognized math intrinsics pattern **/
    val instructions: List<LocatedSbfInstruction>
}

/**
 * A CFG-to-CFG transformation that promotes sequences of low-level instructions
 * implementing a math operation into a call to a special intrinsic function.
 */
interface MathIntrinsicsTransform<Pattern : MathIntrinsicPattern> {

    /** A name of this transformer, used in debug logs **/
    val name: String

    /** Recognize the low-level instruction pattern in a block **/
    fun matchInBlock(
        bb: SbfBasicBlock,
        equalAt: (locInst: LocatedSbfInstruction, value: Value, reg: Value.Reg) -> Boolean,
        isMayLive: (SbfBasicBlock, Value.Reg, pos: Int) -> Boolean
    ): List<Pattern>

    /**
     * Lowers a recognized [pattern] into a sequence of instructions that call the
     * corresponding intrinsic function, replacing the original implementation.
     */
    fun lower(pattern: Pattern, useDynFrames: Boolean): List<SbfInstruction>

    /**
     * Returns true for instructions at which the scalar analysis should cache its abstract state.
     *
     * When scalar analysis is enabled, `matchInBlock` uses cached abstract states to
     * extract register equalities, improving pattern matching precision beyond
     * syntactic register equality. Caching at every instruction is expensive, so this filter
     * lets each transformer declare the specific instructions where cached state is needed —
     * typically the anchor instruction(s) that [matchInBlock] queries for equalities.
     */
    fun abstractStateFilter(locInst: LocatedSbfInstruction): Boolean
}

data class PromoteMathIntrinsicsOptions(
    /** Whether to use scalar analysis or not during pattern matching **/
    val runScalarAnalysis: Boolean,
    /** Whether to run the liveness analysis **/
    val runLivenessAnalysis: Boolean
)

/**
 * Applies each transformer in `transformers` to `cfg`, replacing recognized math intrinsic patterns
 * with the instruction sequences produced by `transformer.lower`.
 *
 * All patterns from all transformers are collected before any block is modified, since pattern
 * matching uses equality information that would be invalidated by earlier replacements.
 * Overlapping patterns are discarded (highest position wins). Non-overlapping patterns are
 * applied from highest to lowest instruction position so that replacements do not invalidate
 * the stored positions of other patterns in the same block.
 */
fun promoteMathIntrinsics(
    cfg: MutableSbfCFG,
    transformers: List<MathIntrinsicsTransform<out MathIntrinsicPattern>>,
    globals: GlobalVariables,
    memSummaries: MemorySummaries,
    opts: PromoteMathIntrinsicsOptions = PromoteMathIntrinsicsOptions(true, true)
) {

    val types = if (opts.runScalarAnalysis) {
        val sbfTypeFac: ISbfTypeFactory<ConstantSet, ConstantSet> =
            ConstantSetSbfTypeFactory(SolanaConfig.ScalarMaxVals.get().toULong())
        val fwdAnalysis = GenericScalarAnalysis(
            cfg,
            globals,
            memSummaries,
            sbfTypeFac,
            // We do not make promoteMathIntrinsics generic on the scalar analysis because it needs this domain in particular.
            ScalarRegisterStackEqualityDomainFactory()
        )
        val abstractStateFilter = { locInst: LocatedSbfInstruction ->
            transformers.any { it.abstractStateFilter(locInst) }
        }

        AnalysisRegisterTypes(fwdAnalysis, AnalysisCacheOptions(abstractStateFilter))
    } else {
        null
    }

    val liveness = if (opts.runLivenessAnalysis) { LivenessAnalysis(cfg) } else { null }

    val isMayLive = { bb: SbfBasicBlock, reg: Value.Reg, pos: Int ->
            val locInst = bb.getLocatedInstructions()[pos]
            val liveRegsAfter = liveness?.getLiveRegistersAtInst(bb.getLabel(), before = false)?.get(locInst)
            if (liveRegsAfter != null) {
                reg in liveRegsAfter
            } else {
                isMayLiveAfter(bb, reg, pos)
            }
        }

    /**
     * Return true if `value` and `reg` hold the same value at `locInst`:
     * first by syntactic register equality, then via scalar-analysis equivalence classes.
     **/
    val equalAt = { locInst: LocatedSbfInstruction, value: Value, reg: Value.Reg ->
        value is Value.Reg && (
            value == reg ||
            types?.getAbstractState(locInst)
                ?.getRegisterStackEqualityDomain()
                ?.getEqualities(reg)
                ?.let { value in it }
            ?: false
        )
    }


    val useDynFrames = globals.elf.useDynamicFrames()
    val replacementCount = mutableMapOf<String, Int>()
    dbg { "Proceeding with Promote Math analysis in ${cfg.getName()}" }
    for (bb in cfg.getMutableBlocks().values) {
        // Collect all (oldInsts, newInsts) replacements from every transformer before modifying
        // the block, since matchInBlock uses equalAt which relies on instruction positions that
        // would be invalidated by earlier replacements.
        val allReplacements = transformers.flatMap { transformer ->
            collectReplacements(transformer, bb, equalAt, isMayLive, useDynFrames)
                .also {
                    replacementCount.getOrPut(transformer.name) { 0 }.let { oldCount: Int ->
                        replacementCount[transformer.name] = oldCount + it.size
                    }
                }
        }

        // Process replacements from highest to lowest first instruction position, skipping any
        // whose instructions overlap with an already-accepted replacement.
        val usedPositions = mutableSetOf<Int>()
        for ((oldInsts, newInsts) in allReplacements.sortedByDescending { it.first.first().pos }) {
            val positions = oldInsts.map { it.pos }.toSet()
            if (positions.any { it in usedPositions }) {
                dbg { "Skipped math pattern because it overlaps with another already processed pattern" }
                continue
            }
            usedPositions += positions
            // Remove instructions from highest to lowest position so that each removal
            // does not shift the indices of the instructions still to be removed
            for (locInst in oldInsts.sortedByDescending { it.pos }) {
                bb.removeAt(locInst.pos)
            }
            // Insert new instructions at the position vacated by the last old instruction.
            // Removing n instructions from highest to lowest shifts that slot down by (n-1).
            val insertPos = oldInsts.last().pos - (oldInsts.size - 1)
            for ((offset, inst) in newInsts.withIndex()) {
                bb.add(insertPos + offset, inst)
            }
        }
    }
    Logger.regression {
        replacementCount.entries.joinToString(separator = "\n") { "Transformer ${it.key} replaced ${it.value} patterns in ${cfg.getName()}" }
    }
}

/**
 * Collects all (oldInsts, newInsts) replacements produced by a single [transformer] for one block.
 * The type parameter [P] ensures `matchInBlock` and `lower` are called on the same transformer
 * with a consistent pattern type, even when [promoteMathIntrinsics] holds a heterogeneous list.
 */
private fun <P : MathIntrinsicPattern> collectReplacements(
    transformer: MathIntrinsicsTransform<P>,
    bb: SbfBasicBlock,
    equalAt: (LocatedSbfInstruction, Value, Value.Reg) -> Boolean,
    isMayLive: (SbfBasicBlock, Value.Reg, pos: Int) -> Boolean,
    useDynFrames: Boolean
): List<Pair<List<LocatedSbfInstruction>, List<SbfInstruction>>> =
    transformer.matchInBlock(bb, equalAt, isMayLive)
        .map { pattern -> pattern.instructions to transformer.lower(pattern, useDynFrames) }

