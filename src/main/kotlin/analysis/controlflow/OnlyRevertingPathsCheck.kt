/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY, without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR a PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package analysis.controlflow

import datastructures.stdcollections.*
import spec.CVLKeywords
import utils.*
import vc.data.*
import vc.data.tacexprutil.asConstOrNull
import java.math.BigInteger

/**
 * Result of [checkIfAllPathsAreLastReverted].
 */
enum class AllPathsRevertedResult {
    /** At least one path from a root to a sink does not go through any reverting command. */
    NON_REVERTING_PATH_EXISTS,

    /**
     * All paths from roots to sinks go through a reverting command, and no loop was detected in the TAC.
     */
    ALL_REVERT_DEFINITIVE,

    /**
     * All paths from roots to sinks go through a reverting command, but the TAC contains at least one
     * loop (as indicated by a [TACMeta.END_LOOP] annotation). Hence, it could be the case that increasing
     * the loop unrolling bound (`--loop_iter`) might introduce a non-reverting path.
     */
    ALL_REVERT_BUT_LOOP_PRESENT,
}

/**
 * Checks whether all paths in [tac] from a root to a sink go through a reverting command.
 * A reverting command is one of:
 *  - assignment `lastReverted = true`
 *  - `assume false`
 *  - `assert false` tagged with [TACMeta.SYNTHETIC_LOOP_END] (loop-bound cutoff without `-assumeUnwindCond`)
 *
 * NB: this is a static check on the graph level; feasibility of paths is not considered.
 *
 * If all visible paths revert but the TAC contains a loop ([TACMeta.END_LOOP] annotation is present),
 * returns [AllPathsRevertedResult.ALL_REVERT_BUT_LOOP_PRESENT] rather than
 * [AllPathsRevertedResult.ALL_REVERT_DEFINITIVE], because the finite loop unrolling bound may have hidden
 * non-reverting paths that would appear with a higher bound.
 */
fun checkIfAllPathsAreLastReverted(tac: CoreTACProgram): AllPathsRevertedResult {
    fun isReverting(cmd: TACCmd.Simple): Boolean =
        (cmd as? TACCmd.Simple.AssigningCmd.AssignExpCmd)?.lhs?.meta?.get(TACSymbol.Var.KEYWORD_ENTRY)?.name == CVLKeywords.lastReverted.keyword &&
            (cmd.rhs as? TACExpr.Sym)?.getAsConst() == BigInteger.ONE

    fun isLoopAssertFalse(cmd: TACCmd.Simple): Boolean =
        (cmd as? TACCmd.Simple.AssertCmd)?.let {
            TACMeta.SYNTHETIC_LOOP_END in it.meta && (it.o.asConstOrNull == BigInteger.ZERO)
        } ?: false

    fun isAssumeFalse(cmd: TACCmd.Simple): Boolean =
        (cmd as? TACCmd.Simple.Assume)?.condExpr?.getAsConst() == BigInteger.ZERO

    val visited = tac.analysisCache.graph.roots.map { it.ptr }.toMutableSet()
    val queue = arrayDequeOf(visited)
    queue.consume { ptr ->
        val cmd = tac.analysisCache.graph.elab(ptr).cmd
        if (!isReverting(cmd) && !isLoopAssertFalse(cmd) && !isAssumeFalse(cmd)) {
            val succ = tac.analysisCache.graph.succ(ptr)
            if (succ.isEmpty()) { //sink
                return AllPathsRevertedResult.NON_REVERTING_PATH_EXISTS
            } else {
                succ.forEach {
                    if (visited.add(it)) {
                        queue += it
                    }
                }
            }
        }
    }

    val hasLoop = tac.parallelLtacStream().anyMatch { it.cmd.maybeAnnotation(TACMeta.END_LOOP) }
    return if (hasLoop) {
        AllPathsRevertedResult.ALL_REVERT_BUT_LOOP_PRESENT
    } else {
        AllPathsRevertedResult.ALL_REVERT_DEFINITIVE
    }
}
