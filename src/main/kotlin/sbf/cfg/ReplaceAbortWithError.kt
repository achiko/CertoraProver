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

import datastructures.stdcollections.*
import sbf.SolanaConfig
import sbf.callgraph.AbortFunctions
import sbf.callgraph.SolanaFunction
import sbf.disassembler.SbfRegister

/**
 * Replace calls to functions that always fail (defined in [AbortFunctions]) with `assert(false)`.
 * It does the transformation only if [SolanaConfig.AssertOnPanic] is enabled.
 */
fun replaceAbortWithError(cfg: MutableSbfCFG) {
    if (!SolanaConfig.AssertOnPanic.get()) {
        return
    }

    for (block in cfg.getMutableBlocks().values) {
        val abortCalls = block.getLocatedInstructions().filter { locInst ->
            val inst = locInst.inst
            inst is SbfInstruction.Call && inst.name in AbortFunctions
        }
        if (abortCalls.isEmpty()) {
            continue
        }
        val replacementMap = abortCalls.associateWith { locInst ->
            val call = locInst.inst as SbfInstruction.Call
            assertFalse(call.name, call.metaData)
        }
        block.replaceInstructions(replacementMap)
    }
}

/**
 * Returns the list of SBF instructions that encode `assert(false); abort` with [functionName] as a comment.
 * We use r0 because it is always assumed to be overwritten after a call returns.
 * The trailing `abort` has `isTerminator() == true`, which lets DCE recognize that
 * everything after this sequence is unreachable.
 */
private fun assertFalse(functionName: String, metadata: MetaData): List<SbfInstruction> {
    return listOf(
        // R0 = 1
        SbfInstruction.Bin(BinOp.MOV, Value.Reg(SbfRegister.R0), Value.Imm(1UL), is64 = true),
        // assert(R0 == 0)
        SbfInstruction.Assert(
            Condition(CondOp.EQ, Value.Reg(SbfRegister.R0), right = Value.Imm(0UL)),
            metadata.plus(SbfMeta.COMMENT to functionName)
        ),
        // abort: isTerminator() == true, so DCE treats subsequent instructions as unreachable
        SolanaFunction.toCallInst(SolanaFunction.ABORT, metadata)
    )
}
