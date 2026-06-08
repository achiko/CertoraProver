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

package analysis.opt.overflow

import analysis.ExprView
import analysis.enarrow
import datastructures.stdcollections.*
import vc.data.ConcurrentPatchingProgram
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TACExpr


object JumpLeNormalizer {
    /**
     * A simple transformation to dedup our recipes (and so making the matcher have less patterns to match).
     * It already assumes there are no "greater-than" comparison , and that none of the "less-than" comparisons are
     * negated.
     *
     * The only thing that remains, is to turn an `Lt` to an `Le` when it's used in a jump condition:
     * ```
     * x := a < b
     * if (x) jump to block1 else to block2
     * ```
     * to
     * ```
     * y := b <= a
     * if (y) jump to block2 else to block1
     * ```
     * For example, if the pattern to prove no-overflow is indeed `b <= a`, we'd match it here even if it appears
     * reversed. This is not a problem if no-overflow is stated via assumes, because then, by previous normalization
     * (specifically no gt, no ge, and no negation normalization), we'll see:
     * ```
     * assume b <= a
     * ```
     * but can't see the equivalent:
     * ```
     * assume !(a < b)
     * ```
     */
    fun normalize(code: CoreTACProgram): CoreTACProgram {
        val g = code.analysisCache.graph
        val patcher = ConcurrentPatchingProgram(code)
        g.blockGraph.keys
            .parallelStream()
            .filter { g.succ(it).size == 2 }
            .forEach { b ->
                val cmds = g.elab(b).commands
                val (jumpPtr, jumpCmd) = cmds.last()
                if (jumpCmd !is TACCmd.Simple.JumpiCmd) {
                    return@forEach
                }

                /**
                 * does the actual replacement, creating a new assignment with the new condition, and changing the jump
                 * condition accordingly.
                 */
                fun replace(lcmd: ExprView<TACExpr.BinRel>, build: (TACExpr, TACExpr) -> TACExpr) {
                    val tempVar = patcher.newTempVar("rev", lcmd.lhs.tag)
                    patcher.insertAfter(
                        lcmd.ptr,
                        listOf(
                            /**
                             * We explicitly do not use a copy here, as we do not want to keep the meta.
                             * In particular, we cannot keep the meta [rules.TWOSTAGE_META_VARORIGIN] on tempVar
                             * as the result is flipped.
                             */
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                tempVar,
                                build(lcmd.exp.o2, lcmd.exp.o1)
                            )
                        )
                    )
                    patcher.replace(
                        jumpPtr,
                        jumpCmd.copy(
                            cond = tempVar,
                            dst = jumpCmd.elseDst,
                            elseDst = jumpCmd.dst
                        )
                    )
                }

                var cond = jumpCmd.cond

                @Suppress("UnconditionalJumpStatementInLoop")
                for (lcmd in g.iterateRevBlock(jumpPtr)) {
                    val cmd = lcmd.cmd
                    if (cmd.getLhs() != cond) {
                        continue
                    }
                    if (lcmd.cmd !is TACCmd.Simple.AssigningCmd.AssignExpCmd) {
                        break
                    }
                    when (val rhs = lcmd.cmd.rhs) {
                        is TACExpr.Sym.Var -> {
                            cond = rhs.s
                            continue
                        }
                        is TACExpr.BinRel.Lt -> replace(lcmd.enarrow(), TACExpr.BinRel::Le)
                        is TACExpr.BinRel.Slt -> replace(lcmd.enarrow(), TACExpr.BinRel::Sle)
                        else -> Unit
                    }
                    break
                }
            }

        return patcher.toCode()
    }

}
