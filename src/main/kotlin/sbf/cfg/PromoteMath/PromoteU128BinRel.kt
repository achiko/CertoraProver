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

private val logger = Logger(LoggerTypes.SBF_MATH_PROMOTION)
private fun dbg(msg: () -> Any) {
    logger.info(msg)
}

data class U128BinRelPattern(
    override val intrinsicName: String,
    override val instructions: List<LocatedSbfInstruction>,
    val params: Int128BinaryParams,
    val result: Int128OperationResult.SingleResult,
    /** Non-pattern store instructions interleaved between the pattern instructions that store
     *  the result. They are removed along with the pattern instructions and re-emitted
     *  verbatim after the gt call so they read the correct result value. */
    val trailingStores: List<SbfInstruction> = emptyList()
) : MathIntrinsicPattern

/** Replaces u128 binary relation comparison patterns (LT, LE, GT, and GE) with calls to intrinsics `CVT_u128_(lt,leq)`.
 *  For GT and GE the parameters are swapped so that we don't need gt and ge as intrinsic.
 * **/
val U128BinRelTransform = object : MathIntrinsicsTransform<U128BinRelPattern> {
    override val name: String = "BinaryRelation"

    val matchingOperations = setOf(CondOp.LT, CondOp.LE, CondOp.GT, CondOp.GE)

    /**
     * Scans for u128 binary relation using [matchingOperations] patterns inside [bb] and returns the matched
     * instructions paired with their parameters.
     *
     * We detect the following pattern (for any operand <OP> in [matchingOperations])
     * ```
     *   (1) tmpLow = select(xLow <OP> yLow, 1, 0)    // low comparison
     *   (2) tmpHigh = select(xHigh <OP> yHigh, 1, 0) // high comparison
     *   (3) result = select(xHigh eq yHigh, tmpLow, tmpHigh) // combine
     * ```
     *
     * Note: The result register can be the same as tmpLow or tmpHigh (in-place update).
     * The result register contains 1 if (xLow:xHigh) <OP> (yLow:yHigh), 0 otherwise.
     */
    override fun matchInBlock(
        bb: SbfBasicBlock,
        equalAt: (LocatedSbfInstruction, Value, Value.Reg) -> Boolean,
        isMayLive: (SbfBasicBlock, Value.Reg, pos: Int) -> Boolean
    ): List<U128BinRelPattern> {
        dbg { "=== Starting U128Gt pattern matching in block ${bb.getLabel()} ===" }
        dbg { "=== Block $bb ===" }
        val res = mutableListOf<U128BinRelPattern>()
        val insts = bb.getInstructions()
        val locInsts = bb.getLocatedInstructions()

        // Try to match the 3-select pattern for u128 greater-than
        // result = select(xHigh eq yHigh, tmpLow, tmpHigh)
        for (i3 in locInsts.indices) {
            val locInst3 = locInsts[i3]
            val inst3 = locInst3.inst as? SbfInstruction.Select ?: continue

            // Final select must be an equality check
            val cond3 = inst3.cond
            if (cond3.op != CondOp.EQ) {
                continue
            }

            val tmpLow = inst3.trueVal as? Value.Reg ?: continue
            val tmpHigh = inst3.falseVal as? Value.Reg ?: continue
            val result = inst3.dst
            val p3 = locInst3.pos

            dbg { "[3] $inst3" }

            val p1Sel = matchSelect(bb, tmpLow, p3) { sel ->
                (sel.cond.op in matchingOperations) && sel.cond.right is Value.Reg
            } ?: continue

            dbg { "[1] $p1Sel xLow: ${p1Sel.x}, yLow: ${p1Sel.y}" }
            val p2Sel = matchSelect(bb, tmpHigh, p3) { sel ->
                // Both select operations must have the same op
                p1Sel.op == sel.cond.op && sel.cond.right is Value.Reg
            } ?: continue
            dbg { "[2] $p2Sel xHigh: ${p2Sel.x}, yHigh: ${p2Sel.y}" }

            val p1 = p1Sel.locIns.pos
            val xLow = p1Sel.x
            val yLow = p1Sel.y
            val xHigh = p2Sel.x
            val yHigh = p2Sel.y

            // Verify that instruction 3's equality condition compares xHigh and yHigh
            // (either order, since EQ is symmetric). Without this check, a false positive
            // match could occur when some unrelated register pair happens to produce tmpHigh.
            val directMatch = equalAt(locInst3, cond3.left, xHigh) && equalAt(locInst3, cond3.right, yHigh)
            val swappedMatch = equalAt(locInst3, cond3.left, yHigh) && equalAt(locInst3, cond3.right, xHigh)
            if (!directMatch && !swappedMatch) {
                continue
            }

            val p2 = p2Sel.locIns.pos

            // All three pattern instructions must be at distinct positions
            if (setOf(p1, p2, p3).size != 3) {
                continue
            }

            val lastPos = maxOf(p1, p2, p3)
            val firstPos = minOf(p1, p2, p3)

            // Resolve parameters
            val patternPositions = setOf(p1, p2, p3)
            val xLowParam = resolveInputParam(xLow, p1, patternPositions, lastPos, insts) ?: continue
            val xHighParam = resolveInputParam(xHigh, p2, patternPositions, lastPos, insts) ?: continue
            val yLowParam = resolveInputParam(yLow, p1, patternPositions, lastPos, insts) ?: continue
            val yHighParam = resolveInputParam(yHigh, p2, patternPositions, lastPos, insts) ?: continue

            // Non-pattern instructions cannot read registers written by pattern instructions.
            if (nonPatternReadsPatternWrites(bb, patternPositions, result, isMayLive)) {
                continue
            }

            dbg { "Detected compact u128 binary relation pattern: result=$result" }

            val trailingStoreLocInsts = collectTrailingStores(bb, insts, firstPos, lastPos, patternPositions) { memInstruction ->
                check(!memInstruction.isLoad){ "Expected a store memory instruction" }
                memInstruction.value == result
            } ?: continue
            val (intrinsic, params) = matchingIntrinsicAndParams(p1Sel.op, Int128BinaryParams(xLowParam, xHighParam, yLowParam, yHighParam))
            val newPattern = U128BinRelPattern(
                intrinsicName = intrinsic,
                instructions = (listOf(
                    p2Sel.locIns,
                    p1Sel.locIns,
                    locInst3
                ) + trailingStoreLocInsts).sortedBy { it.pos },
                params = params,
                result = Int128OperationResult.SingleResult(result),
                trailingStores = trailingStoreLocInsts.map { it.inst }
            )

            dbg { "Compact Pattern: $newPattern" }

            res.add(newPattern)
        }

        return res
    }

    /**
     * Returns the matching intrinsic name for [op] and for GE and GT,
     * swaps the operand and the params.
     */
    private fun matchingIntrinsicAndParams(op: CondOp, params: Int128BinaryParams): Pair<String, Int128BinaryParams> {
        return when (op) {
            CondOp.SLT ,
            CondOp.SLE,
            CondOp.SGT,
            CondOp.SGE,
            CondOp.EQ,
            CondOp.NE -> error("Unexpected operation when matching comparison binary operations")

            CondOp.LT -> CvlrFunctions.CVT_u128_lt to params
            CondOp.LE -> CvlrFunctions.CVT_u128_leq to params

            CondOp.GE,
            CondOp.GT,-> matchingIntrinsicAndParams(op.swap(), params.swap())
        }
    }

    override fun lower(pattern: U128BinRelPattern, useDynFrames: Boolean) =
        lowerImpl(pattern.intrinsicName, pattern.params, pattern.result, useDynFrames) + pattern.trailingStores

    override fun abstractStateFilter(locInst: LocatedSbfInstruction): Boolean {
        val inst = locInst.inst as? SbfInstruction.Select ?: return false
        return inst.cond.op == CondOp.EQ && inst.trueVal is Value.Reg && inst.falseVal is Value.Reg
    }

    /**
     * Finds a select instruction with the given dst register and true/false values.
     */
    private fun matchSelect(
        bb: SbfBasicBlock,
        dst: Value.Reg,
        beforePos: Int = Int.MAX_VALUE,
        predicate: (SbfInstruction.Select) -> Boolean
    ): SelectIns? {
        val locIns = findLastDefinition(bb, dst, beforePos) { locInst ->
            val inst = locInst.inst
            inst is SbfInstruction.Select &&
                (inst.trueVal as? Value.Imm)?.v == 1UL &&
                (inst.falseVal as? Value.Imm)?.v == 0UL &&
                predicate(inst)
        } ?: return null

        val ins = locIns.inst as SbfInstruction.Select
        val (x, y) = ins.cond.left to ins.cond.right as Value.Reg
        return SelectIns(locIns, ins.cond.op, x, y)
    }
}

private data class SelectIns(
    val locIns: LocatedSbfInstruction,
    val op: CondOp,
    val x: Value.Reg,
    val y: Value.Reg
)
