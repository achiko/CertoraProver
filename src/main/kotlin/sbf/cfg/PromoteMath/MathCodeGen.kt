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

import allocator.Allocator
import datastructures.stdcollections.*
import sbf.callgraph.CVTCore
import sbf.disassembler.SbfRegister

/**
 * An input operand for a 128-bit intrinsic: either a value held in a live register at the call
 * site, or a value that must be reloaded from a stack slot because the register
 * holding it was overwritten by a non-pattern instruction between the operand's first use and the
 * call to the 128-bit intrinsic.
 */
sealed class RegOrStack {
    /** The value is held in [reg] at the call site. */
    data class Reg(val reg: Value.Reg) : RegOrStack()
    /** The value must be reloaded from [loc] **/
    data class Stack(val loc: Deref) : RegOrStack() {
        init {
            // This syntactic restriction is for simplicity
            check(loc.base == Value.Reg(SbfRegister.R10))
        }
    }
}

/**
 * Operands and result registers for a 128-bit binary intrinsic.
 *
 * The 128-bit operands x and y are each represented as a pair of 64-bit registers
 * (low and high halves).
 *
 * Inputs ([xLow], [xHigh], [yLow], [yHigh]) are [RegOrStack] because a non-pattern instruction
 * may overwrite the register holding an input between its first use and the call site. In that
 * case, the value must be reloaded from the stack slot where it was originally stored.
 */
data class Int128BinaryParams(
    val xLow: RegOrStack,
    val xHigh: RegOrStack,
    val yLow: RegOrStack,
    val yHigh: RegOrStack
){
    fun swap() = Int128BinaryParams(yLow, yHigh, xLow, xHigh)
}

sealed interface Int128OperationResult{
    fun lower(): List<SbfInstruction>

    data class TupleResult(
        val resLow: Value.Reg,
        val resHigh: Value.Reg
    ) : Int128OperationResult {
        init {
            check(resLow != resHigh)
        }

        override fun lower(): List<SbfInstruction> {
            val r0 = Value.Reg(SbfRegister.R0)
            return if (resLow == r0) {
                // load first resHigh to avoid clobbering r0
                // Since resLow is r0, we don't restore the old value of r0
                listOf (
                    SbfInstruction.Mem(Deref(8, r0, 16), resHigh, true),
                    SbfInstruction.Mem(Deref(8, r0, 8), resLow, true)
                )
            } else if (resHigh == r0) {
                // load first resLow to avoid clobbering r0
                // Since resHigh is r0, we don't restore the old value of r0
                listOf (
                    SbfInstruction.Mem(Deref(8, r0, 8), resLow, true),
                    SbfInstruction.Mem(Deref(8, r0, 16), resHigh, true)
                )
            } else {
                // resLow and resHigh are different from r0 so we need to restore the old value of r0
                listOf(
                    SbfInstruction.Mem(Deref(8, r0, 8), resLow, true),
                    SbfInstruction.Mem(Deref(8, r0, 16), resHigh, true),
                    SbfInstruction.Mem(Deref(8, r0, 0), r0, true)
                )
            }
        }
    }

    data class SingleResult(
        val res: Value.Reg,
    ) : Int128OperationResult {
        override fun lower(): List<SbfInstruction> {
            val r0 = Value.Reg(SbfRegister.R0)
            return if (res == r0) {
                // We don't restore the old value of r0
                listOf (
                    SbfInstruction.Mem(Deref(8, r0, 8), res, true)
                )

            } else {
                // We restore the old value of r0
                listOf (
                    SbfInstruction.Mem(Deref(8, r0, 8), res, true),
                    SbfInstruction.Mem(Deref(8, r0, 0), r0, true)
                )
            }
        }
    }
}

/**
 * Lowers a call to a math intrinsic with [Int128BinaryParams] into a sequence of SBF instructions.
 *
 * The generated code:
 * 1. Saves scratch registers and adjusts the stack frame (prologue).
 * 2. Spills the operands (xLow, xHigh, yLow, yHigh) onto the stack.
 * 3. Loads the operands into the argument registers (r1–r4) and calls [intrinsicName].
 * 4. Restores the stack frame and scratch registers (epilogue).
 * 5. Reads the result from the pointer returned in r0 into [Int128OperationResult.TupleResult.resLow] and [Int128OperationResult.TupleResult.resHigh],
 *    or [Int128OperationResult.SingleResult.res]
 *
 * For instance, given this code
 * ```
 * 	r1 = 0; r2 = 0; r3 = 3; r4 = 5
 *  (*) r2 = r2 + r1
 *  (*) r1 = r3
 *  (*) r1 = r1 + r4
 *  (*) r5 = select(r3 ugt r1, 1, 0)
 *  (*) r2 = r2 + r5
 * ```
 * is transformed into
 * ```
 *  r1 = 0; r2 = 0; r3 = 3; r4 = 5
 *  call CVT_save_scratch_registers
 *  r10 = r10 + 4096
 *  *(u64 *) (r10 + -8)  = r4
 *  *(u64 *) (r10 + -16) = r1
 *  *(u64 *) (r10 + -24) = r3
 *  *(u64 *) (r10 + -32) = r2
 *  r6 = r1; r7 = r2; r8 = r3; r9 = r4
 *  r1 = *(u64 *) (r10 + -8)
 *  r2 = *(u64 *) (r10 + -16)
 *  r3 = *(u64 *) (r10 + -24)
 *  r4 = *(u64 *) (r10 + -32)
 *  call CVT_u128_wrapping_add
 *  r1 = r6; r2 = r7; r3 = r8; r4 = r9
 *  r10 = r10 - 4096
 *  call CVT_restore_scratch_registers
 *  r1 = *(u64 *) (r0 + 8)
 *  r2 = *(u64 *) (r0 + 16)
 *  r0 = *(u64 *) (r0 + 0)  <-- it must be the last
 * ```
 **/
fun lowerImpl(
    intrinsicName: String,
    intrinsicParams: Int128BinaryParams,
    intrinsicResult: Int128OperationResult,
    useDynFrames: Boolean
): List<SbfInstruction> {

    val regs = SbfRegister.entries.map { Value.Reg(it) }

    val xLow = intrinsicParams.xLow
    val xHigh = intrinsicParams.xHigh
    val yLow = intrinsicParams.yLow
    val yHigh = intrinsicParams.yHigh

    val frameLowering = if (useDynFrames) {
        DynamicFrameLowering(32UL) // Allocate 4 local variables
    } else {
        StaticFrameLowering(SBF_STACK_FRAME_SIZE.toULong())
    }

    val prologue = frameLowering.emitPrologue()

    // For a Reg operand: spill the register to the local stack slot so it survives the call.
    // For a Stack operand: nothing to spill: the value will be reloaded from its original
    // stack slot (with an adjusted offset) directly in prepareArgsForCall.
    fun RegOrStack.saveInstruction(localIdx: Short): SbfInstruction? = when (this) {
        is RegOrStack.Reg -> SbfInstruction.Mem(Deref(8, regs[10], frameLowering.offsetOfLocalVar(localIdx)), reg, false)
        is RegOrStack.Stack -> null
    }

    // For a Reg operand: load from the local stack slot into the argument register.
    // For a Stack operand: load from the original stack slot, adjusting the offset to account
    // for the r10 change introduced by the prologue.
    fun RegOrStack.loadInstruction(argReg: Value.Reg, localIdx: Short): SbfInstruction = when (this) {
        is RegOrStack.Reg -> SbfInstruction.Mem(Deref(8, regs[10], frameLowering.offsetOfLocalVar(localIdx)), argReg, true)
        is RegOrStack.Stack -> {
            val adjustedOffsetLong = loc.offset.toLong() - frameLowering.stackAdjustment()
            check (adjustedOffsetLong in Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong())
            SbfInstruction.Mem(
                Deref(8, regs[10], adjustedOffsetLong.toShort()),
                argReg,
                true,
                MetaData(SbfMeta.MATH_PROMOTION())
            )
        }
    }

    // saveOperandsOnStack could be empty if all registers must be reloaded from the stack
    val saveOperandsOnStack = listOfNotNull(
        xLow.saveInstruction(0),
        xHigh.saveInstruction(1),
        yLow.saveInstruction(2),
        yHigh.saveInstruction(3)
    )

    val saveIntrinsicsArgs = listOf (
        SbfInstruction.Bin(BinOp.MOV, regs[6], regs[1], true),
        SbfInstruction.Bin(BinOp.MOV, regs[7], regs[2], true),
        SbfInstruction.Bin(BinOp.MOV, regs[8], regs[3], true),
        SbfInstruction.Bin(BinOp.MOV, regs[9], regs[4], true)
    )

    val prepareArgsForCall = listOf(
        xLow.loadInstruction(regs[1], 0),
        xHigh.loadInstruction(regs[2], 1),
        yLow.loadInstruction(regs[3], 2),
        yHigh.loadInstruction(regs[4], 3)
    )

    val callToIntrinsics = listOf (SbfInstruction.Call(intrinsicName))

    val restoreIntrinsicsArgs = listOf(
        SbfInstruction.Bin(BinOp.MOV, regs[1], regs[6], true),
        SbfInstruction.Bin(BinOp.MOV, regs[2], regs[7], true),
        SbfInstruction.Bin(BinOp.MOV, regs[3], regs[8], true),
        SbfInstruction.Bin(BinOp.MOV, regs[4], regs[9], true)
    )

    val epilogue = frameLowering.emitEpilogue()

    return prologue +
        saveOperandsOnStack +
        saveIntrinsicsArgs +
        prepareArgsForCall +
        callToIntrinsics +
        restoreIntrinsicsArgs +
        epilogue +
        intrinsicResult.lower()

}

private fun createFrameAdjustment(op: BinOp, frameSize: ULong) = SbfInstruction.Bin(
    op,
    Value.Reg(SbfRegister.R10),
    Value.Imm(frameSize),
    is64 = true
)

private interface FrameLowering {
    /** Save scratch registers and update r10 **/
    fun emitPrologue(): List<SbfInstruction>
    /** Restore scratch registers and update r10 **/
    fun emitEpilogue(): List<SbfInstruction>
    /** Return the offset to be added to r10 where the i-th local variable is stored (starting from 0)**/
    fun offsetOfLocalVar(i: Short): Short
    /** Signed amount by which r10 increases after the prologue (negative means r10 decreases) **/
    fun stackAdjustment(): Long
}

private class StaticFrameLowering(val frameSize: ULong): FrameLowering {
    private val metadata: MetaData
    init {
        val callId = Allocator.getFreshId(Allocator.Id.INTERNAL_FUNC)
        check(callId >= 0) {"expected non-negative call id"}
        metadata = MetaData(SbfMeta.CALL_ID to callId.toULong())
    }

    override fun emitPrologue() =
        listOf(
            SbfInstruction.Call(name = CVTCore.SAVE_SCRATCH_REGISTERS.function.name, metaData= metadata),
            createFrameAdjustment(BinOp.ADD, frameSize)
        )
    override fun emitEpilogue() =
        listOf(
            createFrameAdjustment(BinOp.SUB, frameSize),
            SbfInstruction.Call(name = CVTCore.RESTORE_SCRATCH_REGISTERS.function.name, metaData= metadata),
        )

    override fun offsetOfLocalVar(i: Short): Short {
        // The frame grows upward so we need negative offsets
        // 1st local at r10 - 8, 2nd local at r10 - 16, and so on
        return (-((i+1)*8)).toShort()
    }
    override fun stackAdjustment(): Long = frameSize.toLong()
}

private class DynamicFrameLowering(val frameSize: ULong): FrameLowering {
    private val metadata: MetaData
    init {
        val callId = Allocator.getFreshId(Allocator.Id.INTERNAL_FUNC)
        check(callId >= 0) {"expected non-negative call id"}
        metadata = MetaData(SbfMeta.CALL_ID to callId.toULong())
    }

    override fun emitPrologue() =
        listOf(
            SbfInstruction.Call(name = CVTCore.SAVE_SCRATCH_REGISTERS.function.name, metaData= metadata),
            createFrameAdjustment(BinOp.ADD, (-frameSize.toLong()).toULong())
        )
    override fun emitEpilogue() =
        listOf(
            SbfInstruction.Call(name = CVTCore.RESTORE_SCRATCH_REGISTERS.function.name, metaData= metadata),
        )
    override fun offsetOfLocalVar(i: Short): Short {
        // The frame grows downwards so we need positive offsets
        // 1st local variable at r10, 2nd local variable at r0 + 8, and so on.
        return (i*8).toShort()
    }
    override fun stackAdjustment(): Long = -frameSize.toLong()
}
