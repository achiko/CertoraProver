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

package normalizer

import analysis.TACBlock
import analysis.dataflow.LiveVariableAnalysis
import utils.*
import vc.data.CoreTACProgram
import vc.data.TACCmd

/**
 * Gets rid of blocks of the form:
 * Lk = somePC
 * Jump Lk (==> Jump somePC)
 * where Lk is not live at the jump CmdPointer
 *
 * another kind of useless blocks are those that contain only a jumpdest command.
 *
 * This is useful for decomposing to methods: fallback functions start PC can be uniquely identified.
 */
object DummyJumpNodeNormalizer {
    private fun blockMatcherJumpsOnly(b: TACBlock, lva: LiveVariableAnalysis): Boolean {
        if (b.commands.size != 2) {
            return false
        }

        val (c1, c2) = b.commands

        // first command should be const assignment
        if (c1.cmd !is TACCmd.Simple.AssigningCmd.AssignExpCmd) {
            return false
        }

        val constPc = c1.cmd.rhs.getAsConst()?.toIntOrNull() ?: return false

        if (c2.cmd !is TACCmd.Simple.JumpCmd || c2.cmd.dst.origStartPc != constPc) {
            return false
        }

        if (c1.cmd.lhs in lva.liveVariablesAfter(c1.ptr)) {
            return false
        }

        return true
    }

    // solc6
    private fun blockMatcherJumpdestOnly(b: TACBlock): Boolean {
        return b.commands.size == 1 && b.commands.single().cmd is TACCmd.Simple.JumpdestCmd
    }



    fun normalize(p: CoreTACProgram): CoreTACProgram {
        val dummyJumpBlocks = p.analysisCache.graph.blocks.filter {
            blockMatcherJumpsOnly(it, p.analysisCache.lva) ||
                    (blockMatcherJumpdestOnly(it) && p.analysisCache.graph.succ(it.id).size == 1)
        }

        var pp = p

        dummyJumpBlocks.forEach { dummyJumpBlock ->
            val mut = p.toPatchingProgram()
            mut.removeBlockWithSingleSuccessor(dummyJumpBlock.id) // cannot double-replace same pointer, so therefore this ugly trick
            pp = mut.toCode(pp)
        }

        return pp
    }
}
