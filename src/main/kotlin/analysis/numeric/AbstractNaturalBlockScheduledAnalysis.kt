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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package analysis.numeric

import datastructures.stdcollections.*
import algorithms.SimpleDominanceAnalysis
import analysis.CmdPointer
import analysis.GenericTACCommandGraph
import analysis.GraphPathConditions
import analysis.LTACCmdGen
import analysis.PathCondition
import analysis.TACBlockGen
import analysis.dataflow.GenericLiveVariableAnalysis
import analysis.getNaturalLoopsGeneric
import analysis.worklist.IWorklistScheduler
import analysis.worklist.StatefulWorklistIteration
import analysis.worklist.StepResult
import tac.NBId
import utils.*
import vc.data.TACCmd
import java.util.stream.Stream

/** A worklist-based interval analysis */
abstract class AbstractNaturalBlockScheduledAnalysis<W, T: TACCmd, U: LTACCmdGen<T>, V: TACBlockGen<T, U>, G>(
    private val graph: G,
) where G: GenericTACCommandGraph<T, U, V>,
        G: GraphPathConditions {

    abstract val dom: SimpleDominanceAnalysis<NBId>
    abstract val lva: GenericLiveVariableAnalysis<T, U, V, G>
    abstract val invariantHeuristic: LoopInvariantHeuristic<G, W>

    abstract fun step(cmd: U, s: W): W?
    abstract fun propagate(cmd: U, s: W, pc: PathCondition): W?
    abstract fun joinOp(pre: W, new: W, widen: Boolean): W
    abstract fun prepareBlockOut(s: W): W

    abstract val scheduler: IWorklistScheduler<NBId>
    abstract val initialState: W

    private val inState = mutableMapOf<CmdPointer, W>()
    private val outState = mutableMapOf<CmdPointer, W>()

    // Maps loop header |-> loop
    private val loopsByHead by lazy {
        getNaturalLoopsGeneric(graph, dom).groupBy { it.head }
    }

    /**
     * @return the state before executing the command at [ptr].
     *         If [ptr] is not reachable this returns null
     */
    fun inState(ptr: CmdPointer): W? = inState[ptr]

    /**
     * @return the state after executing the command at [ptr].
     *         If [ptr] is not reachable this returns null
     */
    fun outState(ptr: CmdPointer): W? = outState[ptr]

    /**
     * @return all states before executing each command in the graph
     */
    fun parallelStreamStates() : Stream<Pair<CmdPointer, W>> {
        return inState.entries.parallelStream().map { (ptr, state) -> ptr to state }
    }


    protected fun runAnalysis() {
        graph.rootBlocks.forEach {
            inState[CmdPointer(it.id, 0)] = initialState
        }
        (object : StatefulWorklistIteration<NBId, Unit, Unit>() {
            override val scheduler: IWorklistScheduler<NBId> =
                this@AbstractNaturalBlockScheduledAnalysis.scheduler

            override fun reduce(results: List<Unit>) {}

            override fun process(it: NBId): StepResult<NBId, Unit, Unit> {
                return this.cont(iterBlock(it))
            }
        }).submit(graph.rootBlocks.map { it.id })
    }

    private fun stepBlock(block: NBId): W? {
        val commands = graph.elab(block).commands

        var state = inState[CmdPointer(block, 0)]!!
        for (cmd in commands) {
            inState[cmd.ptr] = state
            state = step(cmd, state) ?: return null
            outState[cmd.ptr] = state
        }

        return state
    }

    private fun iterBlock(block: NBId): Set<NBId> {
        val next = mutableSetOf<NBId>()

        val blockOut = stepBlock(block) ?: return next

        for ((succ, cond) in graph.pathConditionsOf(block)) {
            val fst = graph.elab(succ).commands.last()

            // If this is null, then the path from [block] to [succ] is infeasible
            val propagated = propagate(fst, blockOut, cond) ?: continue

            // We need to guess (relational) invariants at loop headers.
            // One reason we need to do this is because for a loop that iterates from 0 to K,
            // we may have the condition (i != K). The invariant we need in this situation
            // is something like i <= K (it's actually more complicated, see [guessLoopInvariants],
            val nextWithGuessedInvariants = loopsByHead[succ]?.singleOrNull { block !in it.body }?.let { enteringLoop ->
                invariantHeuristic.guessLoopInvariants(graph, enteringLoop, propagated)
            } ?: propagated

            val succPtr = CmdPointer(succ, 0)
            if (succPtr !in inState) {
                inState[succPtr] = nextWithGuessedInvariants
                next.add(succ)
            } else {
                val prevState = inState[succPtr]!!
                val isBackJump = loopsByHead[succ]?.any { block in it.body } == true

                val joined = joinOp(prevState, nextWithGuessedInvariants, widen = isBackJump)

                if (joined != prevState) {
                    inState[succPtr] = joined
                    next.add(succ)
                }
            }
        }

        return next
    }
}
