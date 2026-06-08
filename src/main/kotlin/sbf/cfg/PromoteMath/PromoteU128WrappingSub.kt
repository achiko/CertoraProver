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

import cvlr.CvlrFunctions
import datastructures.stdcollections.*
import log.Logger
import log.LoggerTypes
import utils.flatMapToSet

private val logger = Logger(LoggerTypes.SBF_MATH_PROMOTION)
private fun dbg(msg: () -> Any) { logger.info(msg)}


data class U128WrappingSubPattern(
    override val intrinsicName: String,
    override val instructions: List<LocatedSbfInstruction>,
    val params: Int128BinaryParams,
    val result: Int128OperationResult.TupleResult,
    /** Non-pattern store instructions interleaved between the four core instructions that store
     *  resLow or resHigh.  They are removed along with the core instructions and re-emitted
     *  verbatim after the wrapping_sub call so they read the correct result values. */
    val trailingStores: List<SbfInstruction> = emptyList()
): MathIntrinsicPattern

/** Replace u128-bit wrapping subtraction patterns with calls to `CVT_u128_wrapping_sub` **/
val U128WrappingSubTransform = object : MathIntrinsicsTransform<U128WrappingSubPattern> {
    override val name: String = CvlrFunctions.CVT_u128_wrapping_sub
    /**
     * Scans for u128-bit wrapping subtraction patterns inside [bb] and returns the matched
     * instructions paired with their parameters.
     *
     * The pattern represents the computation (xLow:xHigh) - (yLow:yHigh) using four 64-bit instructions:
     * ```
     *   (1) xHigh  = xHigh  - yHigh               // subtract high halves
     *   (2) borrow = select(yLow ugt xLow, 1, 0)  // compute borrow for the low subtraction
     *   (3) xHigh  = xHigh  - borrow              // apply borrow  [must come after (1) and (2)]
     *   (4) xLow   = xLow   - yLow                // subtract low halves (this is independent from the rest)
     * ```
     *
     * The result registers are `resHigh` = `xHigh` and `resLow` = `xLow`.
     *
     * A pattern is only recognized if, after the last of the four instructions, no register in
     * {`xLow`, `xHigh`, `yLow`, `yHigh`} that is not a result register ({`resLow`, `resHigh`}) is alive.
     */
    override fun matchInBlock(
        bb: SbfBasicBlock,
        equalAt: (LocatedSbfInstruction, Value, Value.Reg) -> Boolean,
        isMayLive: (SbfBasicBlock, Value.Reg, pos: Int) -> Boolean
    ): List<U128WrappingSubPattern> {
        val res = mutableListOf<U128WrappingSubPattern>()
        val insts = bb.getInstructions()
        for (locInst in bb.getLocatedInstructions()) {
            // Use the select (instruction 2) as the anchor point
            val select = locInst.inst as? SbfInstruction.Select ?: continue
            val p2 = locInst.pos

            // Instruction 2: borrow = select(yLow ugt xLow, 1, 0)
            val (yLow, xLow) = extractBorrowOperands(select) ?: continue
            val borrow = select.dst

            dbg {"[2] $select: borrow = select (yLow ugt xLow, 1, 0)"}

            // Instruction 3: xHigh = xHigh - borrow
            // Must be the first SUB using borrow after instruction 2
            val inst3Loc = findFirstAfter(bb, p2,
                match = { val inst = it.inst
                          inst is SbfInstruction.Bin && inst.op == BinOp.SUB && equalAt(it, inst.v, borrow) },
                stop  = { it.inst.writeRegister.contains(borrow) }
            ) ?: continue
            val xHigh = (inst3Loc.inst as SbfInstruction.Bin).dst
            val p3 = inst3Loc.pos

            dbg {"[3] ${inst3Loc.inst}: xHigh = xHigh - borrow"}

            // Instruction 1: xHigh = xHigh - yHigh
            // Must be the last write to xHigh before instruction 3
            val inst1Loc = findLastDefinition(bb, xHigh, p3) {
                val inst = it.inst
                inst is SbfInstruction.Bin && inst.op == BinOp.SUB && inst.v is Value.Reg
            } ?: continue
            val yHigh = (inst1Loc.inst as SbfInstruction.Bin).v as? Value.Reg ?: continue
            val p1 = inst1Loc.pos

            dbg {"[1] ${inst1Loc.inst}: yHigh = yHigh - borrow"}

            // Instruction 4: xLow = xLow - yLow, must come after the SELECT so that the SELECT
            // sees the original xLow (not the post-subtraction value).
            val inst4Loc = findFirstAfter(bb, p2,
                match = { val inst = it.inst
                          inst is SbfInstruction.Bin && inst.op == BinOp.SUB && equalAt(it, inst.dst, xLow) && equalAt(it, inst.v, yLow) },
                stop  = { it.inst.writeRegister.contains(xLow) || it.inst.writeRegister.contains(yLow) }
            ) ?: continue
            val p4 = inst4Loc.pos

            dbg {"[4] ${inst4Loc.inst}: xLow = xLow - yLow"}

            // All four instructions must be distinct
            if (setOf(p1, p2, p3, p4).size != 4) {
                continue
            }

            dbg {"All four instructions are distinct" }

            val resLow = xLow
            val resHigh = xHigh
            val lastPos = maxOf(p1, p2, p3, p4)

            // Resolve each input: if a non-pattern instruction in (firstUse, lastPos) clobbers
            // the register, fall back to reloading the value from its original stack slot.
            val patternPositions = setOf(p1, p2, p3, p4)
            val firstLowUse = minOf(p2, p4)
            val xLowParam  = resolveInputParam(xLow,  firstLowUse, patternPositions, lastPos, insts) ?: continue
            val xHighParam = resolveInputParam(xHigh, p1,          patternPositions, lastPos, insts) ?: continue
            val yLowParam  = resolveInputParam(yLow,  firstLowUse, patternPositions, lastPos, insts) ?: continue
            val yHighParam = resolveInputParam(yHigh, p1,          patternPositions, lastPos, insts) ?: continue

            // Liveness: registers that are not result registers and are modified then it must not be live after the pattern.
            val writtenRegs = listOf(inst1Loc.inst, select, inst3Loc.inst, inst4Loc.inst).flatMapToSet { it.writeRegister }
            val inputsToCheck = setOf(xLow, xHigh, yLow, yHigh) - setOf(resLow, resHigh)
            if (inputsToCheck.any {
                writtenRegs.contains(it) &&
                isMayLive(bb, it, lastPos)
            }) {
                continue
            }

            // Non-pattern instructions cannot read registers written by pattern instructions.
            if (nonPatternReadsPatternWrites(bb, patternPositions, resLow, resHigh, isMayLive)) {
                continue
            }

            dbg { "Detected wrapping_sub: resLow=$resLow resHigh=$resHigh" }

            val firstPos = minOf(p1, p2, p3, p4)
            val trailingStoreLocInsts = collectTrailingStores(bb, insts, firstPos, lastPos, patternPositions) { memInstruction ->
                check(!memInstruction.isLoad){ "Expected a store memory instruction" }
                memInstruction.value == resLow || memInstruction.value == resHigh
            }
            if (trailingStoreLocInsts == null) {
                dbg { "Rejected pattern because there is an interleaved store" }
                continue
            }

            val newPattern = U128WrappingSubPattern(
                intrinsicName = CvlrFunctions.CVT_u128_wrapping_sub,
                instructions = (listOf(inst1Loc, locInst, inst3Loc, inst4Loc) + trailingStoreLocInsts).sortedBy { it.pos },
                params = Int128BinaryParams(xLowParam, xHighParam, yLowParam, yHighParam),
                result = Int128OperationResult.TupleResult(resLow, resHigh),
                trailingStores = trailingStoreLocInsts.map { it.inst }
            )

            dbg { "Pattern: $newPattern" }

            res.add(newPattern)
        }
        return res
    }

    override fun lower(pattern: U128WrappingSubPattern, useDynFrames: Boolean) =
        lowerImpl(pattern.intrinsicName, pattern.params, pattern.result, useDynFrames) + pattern.trailingStores

    override fun abstractStateFilter(locInst: LocatedSbfInstruction): Boolean {
        val inst = locInst.inst
        return inst is SbfInstruction.Bin && inst.op == BinOp.SUB
    }

    /**
     * Returns (yLow, xLow) if [select] has the shape `dst = select(yLow ugt xLow, 1, 0)`.
     * Also recognizes the symmetric form `xLow ult yLow`.
     */
    private fun extractBorrowOperands(select: SbfInstruction.Select): Pair<Value.Reg, Value.Reg>? {
        if (select.trueVal != Value.Imm(1UL) || select.falseVal != Value.Imm(0UL)) {
            return null
        }
        val cond = select.cond
        return when (cond.op) {
            CondOp.GT -> {
                // yLow ugt xLow  =>  yLow = cond.left, xLow = cond.right
                val xLow = cond.right as? Value.Reg ?: return null
                Pair(cond.left, xLow)
            }
            CondOp.LT -> {
                // xLow ult yLow  =>  xLow = cond.left, yLow = cond.right
                val yLow = cond.right as? Value.Reg ?: return null
                Pair(yLow, cond.left)
            }
            else -> null
        }
    }

}


