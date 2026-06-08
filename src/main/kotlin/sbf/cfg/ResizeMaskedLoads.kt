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

package sbf.cfg

import sbf.analysis.AnalysisRegisterTypes
import sbf.cfg.SbfMeta.NARROWED_LOAD
import sbf.domains.AbstractDomain
import sbf.domains.INumValue
import sbf.domains.IOffset
import sbf.domains.SbfType
import sbf.domains.ScalarValueProvider
import datastructures.stdcollections.*
import org.jetbrains.annotations.TestOnly
import sbf.SolanaConfig
import sbf.analysis.AnalysisCacheOptions
import sbf.analysis.GenericScalarAnalysis
import sbf.analysis.IAnalysis
import sbf.disassembler.GlobalVariables
import sbf.domains.CFGTransformScalarDomFac
import sbf.domains.ConstantSetSbfTypeFactory
import sbf.domains.MemorySummaries
import kotlin.toULong

/**
 * Narrow or widen loads in [cfg] that satisfy [pred].
 *
 **/
@TestOnly
fun resizeMaskedLoads(
    cfg: MutableSbfCFG,
    globals: GlobalVariables,
    memSummaries: MemorySummaries,
    pred: (SbfInstruction.Mem) -> Boolean = { true }
)  {

    val sbfTypesFac = ConstantSetSbfTypeFactory(SolanaConfig.ScalarMaxVals.get().toULong())
    val scalarAnalysis = GenericScalarAnalysis(
        cfg,
        globals,
        memSummaries,
        sbfTypesFac,
        CFGTransformScalarDomFac()
    )

    for (b in cfg.getMutableBlocks().values) {
        resizeMaskedLoads(b, scalarAnalysis, pred)
    }
}

/**
 * Narrow or widen loads in [b] that satisfy [pred].
 **/
fun<D, TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> resizeMaskedLoads(
    b: MutableSbfBasicBlock,
    scalarAnalysis: IAnalysis<D>,
    pred: (SbfInstruction.Mem) -> Boolean
) where D: AbstractDomain<D>,
        D: ScalarValueProvider<TNum, TOffset> {

    // We need to recompute the instruction-level invariants for `b` at each loop iteration
    val types = AnalysisRegisterTypes(
        scalarAnalysis,
        options = AnalysisCacheOptions.typesAndStatesAt {
            val inst = it.inst
            inst is SbfInstruction.Mem && inst.isLoad
        }
    )

    // Add some annotations required by `narrowMaskedLoad` and `widenMaskedLoad`
    findMismatchedLoads(b, types).forEach { locInst ->
        // don't invalidate other locInst's pos since we replace one instruction with another
        b.replaceInstruction(locInst.pos, locInst.inst)
    }
    findWiderStoredLoads(b, types).forEach { locInst ->
        b.replaceInstruction(locInst.pos, locInst.inst)
    }

    // We traverse in reverse order so that `locInst`'s `pos` are not invalidated when they
    // are processed
    for (locInst in b.getLocatedInstructions().reversed()) {
        val inst = locInst.inst
        if (inst is SbfInstruction.Mem && inst.isLoad && pred(inst)) {
            narrowMaskedLoad(b, locInst)
        }
    }

    // Narrowing only fires on 8-byte loads; widening only on 1/2/4-byte loads, so the
    // two passes operate on disjoint instructions.
    for (locInst in b.getLocatedInstructions().reversed()) {
        val inst = locInst.inst
        if (inst is SbfInstruction.Mem && inst.isLoad && pred(inst)) {
            widenMaskedLoad(b, locInst)
        }
    }

    // Remove annotations added above
    b.removeAnnotations(listOf(SbfMeta.MISMATCHED_LOAD, SbfMeta.WIDER_STORED_LOAD))
}

/**
 * Optimizes 8-byte loads followed by a `0xFF`, `0xFFFF`, or `0xFFFF_FFFF` mask by narrowing the load width
 * to 1, 2, or 4 bytes, respectively.
 *
 * For instance, replace this pattern
 * ```
 * *(u8*) (r10 -1592) = ...
 * ...
 * r1 = *(u64*) (r10 -1592)
 * r1 := r1 and 255
 * ```
 * with
 * ```
 * *(u8*) (r10 -1592) = ...
 * ...
 * r1 = *(u8*) (r10 + -1592)
 * ```
 */
private fun narrowMaskedLoad(block: MutableSbfBasicBlock, loadLocInst: LocatedSbfInstruction): Boolean {
    check(block.getLabel() == loadLocInst.label)
    val loadInst = loadLocInst.inst
    check(loadInst is SbfInstruction.Mem && loadInst.isLoad)

    var lhs = loadInst.value as Value.Reg

    // It requires first to call `annotateMismatchedLoads`
    val numBytesLastStore = loadInst.metaData.getVal(SbfMeta.MISMATCHED_LOAD) ?: return false

    // Only optimize 8-byte loads
    if (loadInst.access.width.toInt() != 8) {
        return false
    }

    // Find the single use of the destination register after the load
    var use = getNextUseInterBlock(block, loadLocInst.pos+1, lhs)
        ?: return false

    // The transformation must be intra-block
    if (use.label != block.getLabel()) {
        return false
    }

    // Skip optionally an assignment
    val useInst = use.inst
    if (useInst is SbfInstruction.Bin && useInst.op == BinOp.MOV) {
        use = getNextUseInterBlock(block, use.pos + 1, useInst.dst)
            ?: return false
        if (use.label != block.getLabel()) {
            return false
        }
        lhs = useInst.dst
    }

    // Check if the use is a mask with `0x1`, `0xFF`, `0xFFFF`, or `0xFFFF_FFFF`
    val narrowWidth: Short = when {
        numBytesLastStore >= 1 && (isMaskWith0x1(use.inst, lhs) || isMaskWith0xFF(use.inst, lhs)) -> 1
        numBytesLastStore >= 2 && isMaskWith0xFFFF(use.inst, lhs) -> 2
        numBytesLastStore >= 4 && isMaskWith0xFFFFFFFF(use.inst, lhs) -> 4
        else -> return false
    }

    // Replace load with narrowed version
    val narrowedLoad = loadInst.copy(
        access = loadInst.access.copy(width = narrowWidth),
        metaData = loadInst.metaData + NARROWED_LOAD()
    )
    block.replaceInstruction(loadLocInst.pos, narrowedLoad)

    // Remove the now-redundant mask instruction
    block.removeAt(use.pos)

    return true
}

/**
 * Inverse of [narrowMaskedLoad]. Widens a narrow load (width 1/2/4) when a previous store at the
 * same stack address was wider, and the load is *immediately* followed by a mask matching the
 * narrow width.
 *
 * For instance, replace this pattern
 * ```
 * *(u64*) (r10-600) = r2     // wide store
 * ...
 * r2 = *(u8*)  (r10-600)     // narrow load
 * r2 = r2 & 0xFF             // mask matching the narrow width
 * ```
 * with
 * ```
 * *(u64*) (r10-600) = r2
 * ...
 * r2 = *(u64*) (r10-600)     // widened to match the store
 * r2 = r2 & 0xFF             // mask preserved
 * ```
 */
private fun widenMaskedLoad(block: MutableSbfBasicBlock, loadLocInst: LocatedSbfInstruction): Boolean {
    check(block.getLabel() == loadLocInst.label)
    val loadInst = loadLocInst.inst
    check(loadInst is SbfInstruction.Mem && loadInst.isLoad)

    val targetWidth = loadInst.metaData.getVal(SbfMeta.WIDER_STORED_LOAD) ?: return false
    val narrowWidth = loadInst.access.width.toInt()
    if (narrowWidth >= targetWidth) {
        return false
    }
    val lhs = loadInst.value as Value.Reg

    // Mask must be the instruction immediately following the load.
    val instructions = block.getLocatedInstructions()
    if (loadLocInst.pos + 1 >= instructions.size) {
        return false
    }
    val nextInst = instructions[loadLocInst.pos + 1].inst
    val expectedMask = when (narrowWidth) {
        1 -> 0xFFUL
        2 -> 0xFFFFUL
        4 -> 0xFFFFFFFFUL
        else -> return false
    }
    if (!isMaskWithImmediate(nextInst, lhs, expectedMask)) {
        return false
    }

    val widenedLoad = loadInst.copy(access = loadInst.access.copy(width = targetWidth.toShort()))
    block.replaceInstruction(loadLocInst.pos, widenedLoad)
    return true
}


private fun isMaskWithImmediate(inst: SbfInstruction, reg: Value.Reg, mask: ULong): Boolean {
    if (inst !is SbfInstruction.Bin || inst.op != BinOp.AND || inst.dst != reg) {
        return false
    }
    val imm = inst.v as? Value.Imm ?: return false
    return imm.v == mask
}

private fun isMaskWith0x1(inst: SbfInstruction, reg: Value.Reg) =
    isMaskWithImmediate(inst, reg, mask = 0x1UL)

private fun isMaskWith0xFF(inst: SbfInstruction, reg: Value.Reg) =
    isMaskWithImmediate(inst, reg, mask = 0xFFUL)

private fun isMaskWith0xFFFF(inst: SbfInstruction, reg: Value.Reg) =
    isMaskWithImmediate(inst, reg, mask = 0xFFFFUL)

private fun isMaskWith0xFFFFFFFF(inst: SbfInstruction, reg: Value.Reg) =
    isMaskWithImmediate(inst, reg, mask = 0xFFFFFFFFUL)

/**
 * Return load instructions that might not match its corresponding last store.
 *
 * Candidates are 8-byte loads from the stack where the actual last store was narrower (1, 2, or 4 bytes).
 * Selection is heuristic-based: false negatives (missed candidates) are acceptable, but false positives
 * (wrongly narrowed loads) will not affect soundness. Soundness is preserved in two ways:
 *   1. A narrowed load must still agree with a mask operation of the matching width.
 *   2. If the narrowed load does not match the last store, the PTA will report an error.
 **/
private fun<D, TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> findMismatchedLoads(
    b: SbfBasicBlock,
    types: AnalysisRegisterTypes<D, TNum, TOffset>,
): List<LocatedSbfInstruction>
where D: AbstractDomain<D>,
      D: ScalarValueProvider<TNum, TOffset> {

    val newInsts = mutableListOf<LocatedSbfInstruction>()
    for (locInst in b.getLocatedInstructions()) {
        val inst = locInst.inst
        if (inst !is SbfInstruction.Mem || !inst.isLoad) {
            continue
        }
        val width = inst.access.width.toULong()
        // We will only optimize 8-byte loads
        if (width != 8UL) {
            continue
        }

        val baseTy = types.typeAtInstruction(locInst, inst.access.base) as? SbfType.PointerType.Stack
            ?: continue
        val resolvedOffset = baseTy.offset.add(inst.access.offset.toLong()).toLongOrNull()
            ?: continue
        val scalarAbsVal = types.getAbstractState(locInst) ?: continue

        var mismatchedLoad = false
        /**
         * Heuristic #1 to determine that a load should be narrowed:
         *   If the 8-byte scalar value at `resolvedOffset` is top but the scalar value
         *   at the same offset with a narrower width n in {1, 2, 4} is non-top, then the last
         *   store was of width n. Iterate largest-first to pick the widest matching store width.
         */
        if (scalarAbsVal.getStackContent(resolvedOffset, 8).isTop()) {
            for (n in listOf(4, 2,1)) {
                if (!scalarAbsVal.getStackContent(resolvedOffset, n.toByte()).isTop()) {
                    newInsts.add(locInst.copy(inst = inst.copy(metaData = inst.metaData + (SbfMeta.MISMATCHED_LOAD to n))))
                    mismatchedLoad = true
                    break
                }
            }
        }

        /**
         * Heuristic #2 to determine that a load should be narrowed:
         *   Given an 8-byte load from the stack where the full 8 bytes may be initialized,
         *   find the smallest n in {1, 2, 4} such that bytes [offset+n, offset+8) are NOT
         *   initialized, inferring that any previous store could only write up to n bytes.
         */
        if (!mismatchedLoad && scalarAbsVal.mayStackBeInitialized(resolvedOffset, 8UL)) {
            for (n in listOf(1, 2, 4)) {
                if (!scalarAbsVal.mayStackBeInitialized(resolvedOffset + n, 8UL - n.toULong())) {
                    // The last store was likely at offset=`resolvedOffset` and with=`n`
                    newInsts.add(locInst.copy(inst = inst.copy(metaData = inst.metaData + (SbfMeta.MISMATCHED_LOAD to n))))
                    break
                }
            }
        }
    }
    return newInsts
}

/**
 * Return narrow loads (width 1/2/4 from the stack) whose previous store at the same offset was
 * likely wider. Selection is heuristic-based: false positives only cause an extra-wide load whose
 * upper bytes are masked away by the surviving `and`, so soundness is preserved.
 */
private fun<D, TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> findWiderStoredLoads(
    b: SbfBasicBlock,
    types: AnalysisRegisterTypes<D, TNum, TOffset>,
): List<LocatedSbfInstruction>
where D: AbstractDomain<D>,
      D: ScalarValueProvider<TNum, TOffset> {

    val newInsts = mutableListOf<LocatedSbfInstruction>()
    for (locInst in b.getLocatedInstructions()) {
        val inst = locInst.inst
        if (inst !is SbfInstruction.Mem || !inst.isLoad) {
            continue
        }
        val width = inst.access.width.toInt()
        if (width != 1 && width != 2 && width != 4) {
            continue
        }

        val baseTy = types.typeAtInstruction(locInst, inst.access.base) as? SbfType.PointerType.Stack
            ?: continue
        val resolvedOffset = baseTy.offset.add(inst.access.offset.toLong()).toLongOrNull()
            ?: continue
        val scalarAbsVal = types.getAbstractState(locInst) ?: continue

        // Pick the largest target width in {8, 4, 2} (greater than `width`) such that bytes
        // immediately after the load's window may be initialized -- evidence the store was wider.
        for (target in listOf(8, 4, 2)) {
            if (target <= width) {
                break
            }
            val tailSize = (target - width).toULong()
            if (scalarAbsVal.mayStackBeInitialized(resolvedOffset + width, tailSize)) {
                newInsts.add(
                    locInst.copy(inst = inst.copy(metaData = inst.metaData + (SbfMeta.WIDER_STORED_LOAD to target)))
                )
                break
            }
        }
    }
    return newInsts
}
