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

data class U128WrappingAddPattern(
    override val intrinsicName: String,
    override val instructions: List<LocatedSbfInstruction>,
    val params: Int128BinaryParams,
    val result: Int128OperationResult.TupleResult,
    val trailingStores: List<SbfInstruction> = emptyList()
): MathIntrinsicPattern

/** Replace u128-bit wrapping addition patterns with calls to `CVT_u128_wrapping_add` **/
val U128WrappingAddTransform = object : MathIntrinsicsTransform<U128WrappingAddPattern> {

    override val name: String = CvlrFunctions.CVT_u128_wrapping_add
    /**
     * Scan for u128-bit wrapping addition patterns inside [bb] and return the matched
     * instructions paired with their parameters.
     *
     * The pattern represents the computation (xLow:xHigh) + (yLow:yHigh) using five 64-bit instructions:
     * ```
     *   (1) resHigh = resHigh + xHigh               // add high halves eagerly  [resHigh == yHigh]
     *   (2) resLow  = yLow                          // assign yLow into resLow
     *   (3) resLow  = resLow + xLow                 // add low halves
     *   (4) carry   = select(yLow ugt resLow, 1, 0) // detect overflow in the low sum
     *   (5) resHigh = resHigh + carry               // propagate carry into high limb
     * ```
     *
     * The result registers are `resLow` and `resHigh`.
     * Note that `resHigh` is the same register as `yHigh`, and the register holding `xHigh`
     * is reused as `resLow` after instruction (2).
     */
    override fun matchInBlock(
        bb: SbfBasicBlock,
        equalAt: (LocatedSbfInstruction, Value, Value.Reg) -> Boolean,
        isMayLive: (SbfBasicBlock, Value.Reg, pos: Int) -> Boolean
    ): List<U128WrappingAddPattern> {
        val res = mutableListOf<U128WrappingAddPattern>()
        val insts = bb.getInstructions()
        for (locInst in bb.getLocatedInstructions()) {
            // Use the select (instruction 4) as the anchor point
            val (select, p4, yLow, resLow, carry) = matchSelect(locInst) ?: continue

            // Instruction 5: resHigh = resHigh + carry
            // Must be the first add using carry after the select instruction
            val inst5 = findFirstAfter(bb, p4,
                match = {
                    val inst = it.inst
                    inst is SbfInstruction.Bin && inst.op == BinOp.ADD && equalAt(it, inst.v, carry) },
                stop  = { it.inst.writeRegister.contains(carry) }
            ) ?: continue
            val resHigh = (inst5.inst as SbfInstruction.Bin).dst
            val p5 = inst5.pos

            dbg { "[4] $select" }
            dbg { "[5] $inst5: resHigh = resHigh + carry" }

            // Instruction 3: resLow = resLow + xLow
            // Must be the last write to resLow before the select
            val inst3 = findLastDefinition(bb, resLow, p4) {
                val inst = it.inst
                inst is SbfInstruction.Bin && inst.op == BinOp.ADD && inst.v is Value.Reg
            } ?: continue
            val xLow = (inst3.inst as SbfInstruction.Bin).v as Value.Reg
            val p3 = inst3.pos

            dbg { "[3] $inst3: resLow = resLow + xLow" }

            // Instruction 2: resLow = yLow
            // Must be the last write to resLow before instruction 3, with src == yLow
            val inst2 = findLastDefinition(bb, resLow, p3) {
                val inst = it.inst
                inst is SbfInstruction.Bin && inst.op == BinOp.MOV && equalAt(it, inst.v, yLow)
            } ?: continue
            val p2 = inst2.pos

            dbg { "[2] $inst2: resLow = yLow" }

            // Instruction 1: resHigh = resHigh + xHigh
            // Must be the last write to resHigh before instruction 2 (i.e., before xHigh's register
            // is overwritten by the assignment), ensuring xHigh still holds its original value at inst 1
            val inst1 = findLastDefinition(bb, resHigh, p2) {
                val inst = it.inst
                inst is SbfInstruction.Bin && inst.op == BinOp.ADD && inst.v is Value.Reg
            } ?: continue
            val xHigh = (inst1.inst as SbfInstruction.Bin).v as Value.Reg
            val p1 = inst1.pos

            dbg { "[1] $inst1: resHigh = resHigh + xHigh" }

            // All five instructions must be distinct
            if (setOf(p1, p2, p3, p4, p5).size != 5) {
                continue
            }

            dbg {"All five instructions are distinct" }

            // Resolve each input: if a non-pattern instruction in (firstUse, p5) clobbers the
            // register, fall back to reloading the value from its original stack slot.
            val patternPositions = setOf(p1, p2, p3, p4, p5)
            val xLowParam  = resolveInputParam(xLow,  p3, patternPositions, p5, insts) ?: continue
            val xHighParam = resolveInputParam(xHigh, p1, patternPositions, p5, insts) ?: continue
            val yLowParam  = resolveInputParam(yLow,  p2, patternPositions, p5, insts) ?: continue
            val yHighParam = resolveInputParam(resHigh, p1, patternPositions, p5, insts) ?: continue

            // Liveness: inputs that are not result registers must not be live after the pattern
            val writtenRegs = listOf(inst1.inst, inst2.inst, inst3.inst, select, inst5.inst).flatMapToSet { it.writeRegister }
            val inputsToCheck = setOf(xLow, xHigh, yLow) - setOf(resLow, resHigh)
            if (inputsToCheck.any { writtenRegs.contains(it) && isMayLive(bb, it, p5) }) {
                continue
            }

            // Non-pattern instructions cannot read registers written by pattern instructions.
            if (nonPatternReadsPatternWrites(bb, patternPositions, resLow, resHigh, isMayLive)) {
                continue
            }

            dbg { "Detected wrapping_add: resLow=$resLow resHigh=$resHigh" }

            // we only allow non-pattern stores interleaves with [1]-[5] if the stored value is resLow or resHigh
            val firstPos = minOf(p1, p2, p3, p4, p5)
            val trailingStoreLocInsts = collectTrailingStores(bb, insts, firstPos, p5, patternPositions) { memInstruction ->
                check(!memInstruction.isLoad){ "Expected a store memory instruction" }
                memInstruction.value == resLow || memInstruction.value == resHigh
            } ?: continue

            val newPattern =  U128WrappingAddPattern(
                intrinsicName = CvlrFunctions.CVT_u128_wrapping_add,
                instructions = (listOf(inst1, inst2, inst3, locInst, inst5) + trailingStoreLocInsts).sortedBy { it.pos },
                params = Int128BinaryParams(xLowParam, xHighParam, yLowParam, yHighParam),
                result = Int128OperationResult.TupleResult(resLow, resHigh),
                trailingStores = trailingStoreLocInsts.map { it.inst }
            )

            dbg { "Pattern: $newPattern" }
            res.add(newPattern)
        }
        return res
    }

    override fun lower(pattern: U128WrappingAddPattern, useDynFrames: Boolean) =
        lowerImpl(pattern.intrinsicName, pattern.params, pattern.result, useDynFrames) + pattern.trailingStores

    override fun abstractStateFilter(locInst: LocatedSbfInstruction): Boolean {
        val inst = locInst.inst
        return inst is SbfInstruction.Bin && (inst.op == BinOp.ADD || inst.op == BinOp.MOV)
    }

    /**
     * Try to match [locInst] as the select instruction of an u128 wrapping-add pattern.
     *  ```
     *  carry = select(yLow ugt resLow, 1, 0)
     *  ```
     *  or
     *  ```
     *  carry = select(resLow ult yLow, 1, 0)
     *  ```
     */
    private fun matchSelect(locInst: LocatedSbfInstruction): SelectAnchor? {
        val select = locInst.inst as? SbfInstruction.Select ?: return null
        if (select.trueVal != Value.Imm(1UL) || select.falseVal != Value.Imm(0UL)) {
            return null
        }
        val cond = select.cond
        val yLow: Value.Reg
        val resLow: Value.Reg
        when (cond.op) {
            CondOp.GT -> {
                // yLow ugt resLow  =>  yLow = cond.left, resLow = cond.right
                yLow = cond.left
                resLow = cond.right as? Value.Reg ?: return null
            }
            CondOp.LT -> {
                // resLow ult yLow  =>  resLow = cond.left, yLow = cond.right
                resLow = cond.left
                yLow = cond.right as? Value.Reg ?: return null
            }
            else -> {
                return null
            }
        }
        return SelectAnchor(select, locInst.pos, yLow, resLow, select.dst)
    }
}

private data class SelectAnchor(
    val select: SbfInstruction.Select,
    val p4: Int,
    val yLow: Value.Reg,
    val resLow: Value.Reg,
    val carry: Value.Reg
)
