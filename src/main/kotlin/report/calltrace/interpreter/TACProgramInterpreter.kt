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

package report.calltrace.interpreter

import algorithms.topologicalOrderOrNull
import analysis.CmdPointer
import config.ReportTypes
import datastructures.stdcollections.*
import instrumentation.transformers.TACDSA
import log.*
import report.calltrace.printer.DebugAdapterPopAction
import report.calltrace.printer.DebugAdapterPushAction
import report.calltrace.printer.tryGetDebugAction
import rules.TWOSTAGE_META_BLOCKORIGIN
import rules.TWOSTAGE_META_VARORIGIN
import rules.fixedVariable
import solver.CounterexampleModel
import solver.InterpretedCounterexampleModel
import tac.DumpTime
import tac.MetaKey
import tac.NBId
import tac.Tag
import utils.*
import vc.data.*
import vc.data.tacexprutil.asSym
import vc.data.tacexprutil.asVar
import vc.data.tacexprutil.asVarOrNull
import verifier.PatchingProgramWrapper
import java.math.BigInteger
import kotlin.collections.mapNotNull

private val logger = Logger(LoggerTypes.TAC_PROGRAM_INTERPRETER)

/**
 * TAC (Three Address Code) program interpreter for two-stage optimization verification.
 *
 * This interpreter executes an unoptimized TAC program guided by the execution path from an optimized version,
 * attempting to reproduce counterexamples (CEX) found during SMT solving on the optimized program.
 *
 * Expects a [program] that has been prepared using [createInterpretedProgram] - i.e. a program
 * that a) has been sliced to the subgraph from the CEX over the optimized program (using destructive optimizations) and
 * b) has meta ( [SMT_MODEL_VALUE_OPTIMIZED]) on the command pointers that encodes the variables' value at that pointer.
 *
 * Control-flow is evaluated based on the values of the conditions in [TACCmd.Simple.JumpiCmd] commands.
 * When encountering a [TACCmd.Simple.JumpiCmd], but it's unclear which branch can be taken (i.e. the operand evaluated
 * to null). Then this algorithm takes _both_ paths, and terminates as soon as _one_ branch reaches
 * an assert false.
 */
class TACProgramInterpreter(
    /** The program over which the interpretation is performed, this is typically an unoptimized TAC program.*/
    val program: CoreTACProgram,
    /** The blocks that are allowed to be visited during exploration, default is the full set of NBIds.*/
    val allowedBlocks: Set<NBId> = program.code.keys.toSet(),
    /** The starting block for the interpretation, default is the program's entry point  */
    initialBlock: NBId = program.entryBlockId,
) {

    init {
        // Verify the program is acyclic (DAG) - loops are not supported in this interpreter
        if (topologicalOrderOrNull(program.blockgraph) == null) {
            error("The program isn't loop free.")
        }
    }

    val g = program.analysisCache.graph
    val initialState = DynamicEvaluationState(
        CmdPointer(initialBlock, 0),
        0U,
        TACMemoryModel(),
        listOf(initialBlock)
    )

    companion object {
        val SMT_MODEL_VALUE_OPTIMIZED = MetaKey<BigInteger>("smt.model.optimized.program")

        /**
         * Given an [unoptimizedProg] and an [optimizedProg] (and it's [optimizedModel]), create a sliced
         * program that can be consumed by the interpreter.
         *
         * 1. Adds the meta [SMT_MODEL_VALUE_OPTIMIZED] to the relevant commands
         * 2. Slices the program to the sub graph obtained when mapping the [optimizedModel] back to [unoptimizedProg]
         * 3. Runs DSA to prepare the program for interpretation.
         */
        fun createInterpretedProgram(
            unoptimizedProg: CoreTACProgram,
            optimizedModel: CounterexampleModel,
            optimizedProg: CoreTACProgram
        ): CoreTACProgram {
            val optimizedEvaluation = optimizedEvaluation(optimizedProg, optimizedModel)
            val allowedBlocks = optimizedProg.allowedBlocks(optimizedModel.reachableNBIds)
            val patcher = PatchingProgramWrapper(unoptimizedProg)
            optimizedEvaluation.forEachEntry { (ptr, value) ->
                val lcmd = unoptimizedProg.analysisCache.graph.toCommand(ptr)
                patcher.replace(ptr, lcmd.plusMeta(SMT_MODEL_VALUE_OPTIMIZED, value))
            }

            val reachableSubGraph =
                unoptimizedProg.analysisCache.graph.toSubGraph(toIsolate = allowedBlocks, prefixFilter = { _ -> true })
            patcher.limitTACProgramTo(reachableSubGraph.toBlockGraph(), allowedBlocks, assumeFalseOnDroppedLeaves = true)

            val reducedProgram = TACDSA.simplify(patcher.toCode())
            if (logger.isDebugEnabled) {
                ArtifactManagerFactory().dumpMandatoryCodeArtifacts(
                    reducedProgram,
                    ReportTypes.TAC_PROGRAM_INTERPRETER,
                    StaticArtifactLocation.Reports,
                    DumpTime.PRE_TRANSFORM
                )
                ArtifactManagerFactory().dumpMandatoryCodeArtifacts(
                    optimizedProg,
                    ReportTypes.TAC_PROGRAM_INTERPRETER,
                    StaticArtifactLocation.Reports,
                    DumpTime.POST_TRANSFORM
                )
            }
            return reducedProgram
        }

        /**
         * Given an [optimizedProg] and [optimizedModel] and creates a map from [CmdPointer] to [BigInteger].
         * When a key [CmdPointer] has value v in the map, it means that the variable [rules.fixedVariable]
         * at the command of [CmdPointer] can be fixed to v in the second round.
         */
        private fun optimizedEvaluation(
            optimizedProg: CoreTACProgram,
            optimizedModel: CounterexampleModel,
        ): Map<CmdPointer, BigInteger> {
            // We only use the values of a specific tag type from optimized
            // For instance, we don't want to use values from maps or uninterpreted sort.
            fun isValidTag(tag: Tag): Boolean = when(tag){
                is Tag.Bits -> true
                Tag.Bool -> true
                Tag.Int -> true

                is Tag.CVLArray.RawArray -> false
                is Tag.Map -> false
                is Tag.Move -> false
                is Tag.UserDefined -> false
                Tag.BlockchainState -> false
                is Tag.CVLArray.UserArray -> false
            }
            fun valueOf(sym: TACSymbol) =
                when (sym) {
                    is TACSymbol.Const -> sym.value
                    is TACSymbol.Var -> runIf(isValidTag(sym.tag)){ optimizedModel.tacAssignments[sym]?.asBigIntOrNull() }
                }

            return optimizedProg.code.flatMap { (_, v) ->
                v.flatMap { cmd ->
                    cmd.meta[TWOSTAGE_META_VARORIGIN]?.ptrs.orEmpty().mapNotNull { ptrInUnoptimized ->
                        /** A note regarding the +1 here: When adding the pointers to the program in
                        [rules.annotateWithTwoStageMeta] we also prepend the TWOSTAGE_META_BLOCKORIGIN command
                        to the block, this yields an offset that we correct here. */
                        ptrInUnoptimized + 1 `to?` when (cmd) {
                            is TACCmd.Simple.AssertCmd -> valueOf(cmd.o)
                            is TACCmd.Simple.AssumeCmd -> valueOf(cmd.cond)
                            is TACCmd.Simple.AssigningCmd -> valueOf(cmd.lhs)
                            else -> `impossible!`
                        }
                    }
                }
            }.toMap()
        }
    }

    /**
     * Adds a new block to the exploration queue if it meets validity criteria.
     * Blocks are only added if they:
     * 1. Are in the set of allowed blocks (from the optimized program)
     * 2. Haven't been visited yet in the current execution path (to prevent cycles - this should be true also
     * by the assumption that the graph is a DAG)
     *
     * This filtering ensures we only explore feasible paths that correspond to the optimized CEX.
     */
    private fun ArrayDeque<DynamicEvaluationState>.addNextBlock(state: DynamicEvaluationState, potentialNBId: NBId) {
        if (potentialNBId !in allowedBlocks) {
            return
        }
        logger.debug { "Proceeding from ${state.cmdPtr.block} to $potentialNBId" }
        check(potentialNBId !in state.executionPath) { "The block ${potentialNBId} has already been visited." }
        this.addFirst(
            state.copy(
                cmdPtr = CmdPointer(potentialNBId, 0),
                executionPath = state.executionPath + potentialNBId
            )
        )
    }

    private fun ArrayDeque<DynamicEvaluationState>.proceedToNextPtr(ptr: CmdPointer, env: DynamicEvaluationState) {
        ptr.nextPtrInBlock()?.let { next ->
            addFirst(env.copy(next))
        } ?: run {
            val block = ptr.block
            val currBlockCmd = g.elab(block).commands

            // Fallback to the successor of block graph in the case there is no control flow instruction in the block
            if (currBlockCmd.none { it.cmd is TACCmd.Simple.JumpiCmd || it.cmd is TACCmd.Simple.JumpCmd }) {
                g.succBlock(block).forEach { b ->
                    addNextBlock(env, b.id)
                }
            }
        }
    }

    /**
     * Main interpreter loop using breadth-first search through the program's control flow graph.
     * Explores multiple paths when conditional jumps have unknown conditions.
     * Returns the first state that reaches a failing assertion.
     */
    private fun interpret(): DynamicEvaluationState? {
        val queue = arrayDequeOf(initialState)
        queue.consume { curr ->
            val (ptr, cmd) = g.elab(curr.cmdPtr)
            // Early termination: If withUpdatedCallStack returns null, the path was invalid in terms of the call stack.
            val state = curr.withUpdatedCallStack(cmd) ?: return@consume

            val updatedExecEnv = when (cmd) {
                is TACCmd.Simple.AssigningCmd -> {
                    when (cmd) {
                        is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                            val rhs = cmd.rhs
                            with(rhs) {
                                val lhs = cmd.lhs
                                when (this) {
                                    is TACExpr.Select ->
                                        base.asVarOrNull?.let { base ->
                                            state.load(cmd.lhs, base, loc.asSym)
                                        } ?: state

                                    // We consider a store lhs = Store(base, loc, value)
                                    // as two statements:
                                    // lhs := base
                                    // lhs[loc] := value
                                    is TACExpr.Store ->
                                        state.storeExpression(lhs, base.toTACExpr())
                                            .memstore(
                                                value,
                                                lhs,
                                                loc.asSym,
                                                StoreType.FullWord
                                            )

                                    is TACExpr.MapDefinition -> state.kill(
                                        cmd.lhs //This is conservative approach, we kill the Map.
                                    )

                                    // A LongStore expression lhs = LongStore(dstMap, dstOffset, srcMap, srcOffset, length)
                                    // is equivalent to these two statements:
                                    // lhs := dstMap
                                    // ByteLongCopy(dstOffset, srcOffset, length, lhs, srcBase)
                                    is TACExpr.LongStore ->
                                        state.storeExpression(lhs, dstMap.toTACExpr())
                                            .byteCopy(
                                                srcMap.asVar,
                                                srcOffset.asSym,
                                                length.asSym,
                                                lhs,
                                                dstOffset.asSym
                                            )

                                    else -> {
                                        state.storeExpression(cmd.lhs, cmd.rhs)
                                    }
                                }
                            }
                        }

                        is TACCmd.Simple.AssigningCmd.AssignGasCmd,
                        is TACCmd.Simple.AssigningCmd.AssignHavocCmd,
                        is TACCmd.Simple.AssigningCmd.AssignMsizeCmd,
                        is TACCmd.Simple.AssigningCmd.AssignSha3Cmd,
                        is TACCmd.Simple.AssigningCmd.AssignSimpleSha3Cmd -> state

                        is TACCmd.Simple.AssigningCmd.WordLoad -> state.load(cmd.lhs, cmd.base, cmd.loc)
                        is TACCmd.Simple.AssigningCmd.ByteLoad -> state.load(cmd.lhs, cmd.base, cmd.loc)
                        is TACCmd.Simple.AssigningCmd.ByteStore -> state.memstore(
                            cmd.value.toTACExpr(),
                            cmd.base,
                            cmd.loc,
                            StoreType.FullWord
                        )

                        is TACCmd.Simple.AssigningCmd.ByteStoreSingle -> state.memstore(
                            cmd.value.toTACExpr(),
                            cmd.base,
                            cmd.loc,
                            StoreType.SingleByte
                        )

                    }.withValueFromOptimized(ptr)
                }

                is TACCmd.Simple.Assume -> {
                    // For assume commands, the condition must be true (otherwise path is infeasible)
                    // We'll let withValueFromOptimized handle the forcing if there's an optimized value
                    // Otherwise we force it to true here
                    if (valueFromOptimized(ptr) != null) {
                        // Optimized value exists, let withValueFromOptimized handle it
                        state.withValueFromOptimized(ptr)
                    } else {
                        // No optimized value, force to true for feasibility
                        state.forceAssume(cmd.condExpr, BigInteger.ONE)
                    }
                }

                is TACCmd.Simple.ByteLongCopy -> state.byteCopy(
                    cmd.srcBase,
                    cmd.srcOffset,
                    cmd.length,
                    cmd.dstBase,
                    cmd.dstOffset
                )

                is TACCmd.Simple.WordStore -> state.memstore(
                    cmd.value.toTACExpr(),
                    cmd.base,
                    cmd.loc,
                    StoreType.FullWord
                )

                // Control flow handling these either return null and add elements to the queue in addNextBlock
                // (jump commands) or terminate the execution (AssertCmd)
                is TACCmd.Simple.JumpCmd -> {
                    // Unconditional jump to destination block
                    queue.addNextBlock(state, cmd.dst)
                    null
                }

                is TACCmd.Simple.JumpiCmd -> {
                    // Conditional jump - evaluate condition and branch accordingly
                    val evaluated = state[cmd.cond]
                    if (evaluated != null) {
                        // Condition is known - take the appropriate branch
                        if (evaluated.isFalse()) {
                            queue.addNextBlock(state, cmd.elseDst)
                        } else {
                            queue.addNextBlock(state, cmd.dst)
                        }
                    } else {
                        // Condition unknown - explore both branches with forced conditions
                        // This creates two execution states: one where condition is true, one where it's false
                        logger.info { "Failed to evaluate condition of jumpi condition (got $evaluated) at $cmd" }
                        queue.addNextBlock(
                            state.copy(maybeInfeasible = true).forceAssume(
                                cmd.cond.toTACExpr(),
                                BigInteger.ONE
                            )!!, cmd.dst
                        )
                        queue.addNextBlock(
                            state.copy(maybeInfeasible = true).forceAssume(
                                cmd.cond.toTACExpr(),
                                BigInteger.ZERO
                            )!!, cmd.elseDst
                        )
                    }
                    null
                }

                is TACCmd.Simple.AssertCmd -> {
                    // Handle assertion - if it fails, we've found our counterexample
                    state.withValueFromOptimized(ptr).let { updated ->
                        val condition = updated[cmd.o]
                        if (condition.isFalse()) {
                            queue.clear()  // Stop exploring other paths
                            return@interpret updated  // Return the failing state as our result
                        } else {
                            updated.forceAssume(cmd.o.toTACExpr(), BigInteger.ONE)
                        }
                    }
                }

                else -> state
            }

            if (updatedExecEnv == null && !(cmd is TACCmd.Simple.JumpCmd || cmd is TACCmd.Simple.JumpiCmd)) {
                logger.debug { "End of interpreted path reached at $ptr." }
            }
            updatedExecEnv?.let { queue.proceedToNextPtr(ptr, it) }
        }

        Logger.regression { "SMT result produced SAT, interpretation yielded UNSAT." }
        return null
    }

    fun interpretProgram(): InterpreterResult? {
        val start = System.currentTimeMillis()
        val result = interpret() ?: return null
        val end = System.currentTimeMillis()
        logger.info { "TAC interpreter finished in ${(end - start) / 1000}s" }

        val reachableBlocks = result.executionPath.toSet()
        val unreachableBlocks = program.blockgraph.keys.minus(reachableBlocks)
        val tacAssignments =
            result.toTacAssignments()
        return InterpreterResult(
            cex = InterpretedCounterexampleModel(
                tacAssignments = tacAssignments,
                havocedVariables = program.getHavocedSymbols(),
                reachableNBIds = reachableBlocks,
                unreachableNBIds = unreachableBlocks
            ),
            maybeInfeasible = result.maybeInfeasible,
            smtValueConflict = result.smtValueConflict
        )
    }

    private fun CmdPointer.nextPtrInBlock() = if (this.pos + 1 < g.elab(this.block).commands.size) {
        this + 1
    } else {
        null
    }

    private fun valueFromOptimized(ptr: CmdPointer): BigInteger? = g.toCommand(ptr).meta[SMT_MODEL_VALUE_OPTIMIZED]

    inner class DynamicEvaluationState(
        val cmdPtr: CmdPointer,
        val currentCallStackDepth: UInt,
        val memory: TACMemoryModel,
        val executionPath: List<NBId>,
        val maybeInfeasible: Boolean = false,
        val smtValueConflict: Boolean = false
    ) : IMemory<DynamicEvaluationState> {
        /**
         * Maintains call stack depth consistency during interpretation.
         * This is critical for ensuring we only explore feasible execution paths.
         *
         * Takes the current [cmd] and if the [cmd] modifies the call stack
         * returns a copy with the modified [currentCallStackDepth].
         *
         * Any [DebugAdapterPushAction] will increase the size of the call stack by one
         * Any [DebugAdapterPopAction] decreases the size of the call stack by one by removing the first element.
         * If [currentCallStackDepth] was empty, it will return null. This would create an incorrectly balanced path and the
         * search can terminate early.
         *
         * If the [cmd] doesn't modify the call stack, i.e., the command doesn't pop or push an element onto
         * the stack, returns itself.
         */
        fun withUpdatedCallStack(cmd: TACCmd.Simple): DynamicEvaluationState? {
            val action = cmd.tryGetDebugAction()
            if (action is DebugAdapterPopAction) {
                if (this.currentCallStackDepth == 0U) {
                    logger.debug { "Pruned infeasible path due to incorrect call stack" }
                    return null
                }
                return this.copy(currentCallStackDepth = currentCallStackDepth - 1U)
            } else if (action is DebugAdapterPushAction) {
                return this.copy(currentCallStackDepth = currentCallStackDepth + 1U)
            }
            return this
        }

        fun copy(
            cmdPtr: CmdPointer = this.cmdPtr,
            currentCallStackDepth: UInt = this.currentCallStackDepth,
            memory: TACMemoryModel = this.memory,
            executionPath: List<NBId> = this.executionPath,
            maybeInfeasible: Boolean = this.maybeInfeasible,
            smtValueConflict: Boolean = this.smtValueConflict
        ): DynamicEvaluationState {
            return DynamicEvaluationState(
                cmdPtr,
                currentCallStackDepth,
                memory,
                executionPath,
                maybeInfeasible,
                smtValueConflict
            )
        }

        /**
         * Mechanism to fetch values from the optimized execution (SMT). When the unoptimized
         * interpretation cannot determine a value, the optimized value is chosen. If both
         * interpret and optimized execution have values for a variable, but they don't match,
         * the optimized value is used for further execution.
         */
        fun withValueFromOptimized(ptr: CmdPointer): DynamicEvaluationState {
            val optimized =
                valueFromOptimized(ptr) ?: // No value received from optimized, proceeding without checking conflicts.
                return this

            val command = g.toCommand(ptr)
            val variable = command.fixedVariable() ?: return this
            val interpreted = this[variable]
            val nextState = if (interpreted != null && interpreted != optimized) {
                logger.info {
                    "Contradiction: Value from optimized ($optimized) and interpreted ($interpreted) do not match at $ptr. " +
                        "Taking value from optimized for further interpretation along the current path."
                }
                this.copy(smtValueConflict = true)
            } else {
                this
            }

            /**
             * For assume commands, forceAssume ensures the condition equals the optimized value.
             * For other commands, we force the expression to match the optimized value and then store it.
             */
            return when (command) {
                is TACCmd.Simple.AssumeCmd -> {
                    // For assume, just force the condition to match optimized value
                    // No need to store separately since forceAssume already updates the memory model
                    nextState.forceAssume(command.cond.toTACExpr(), optimized) ?: nextState
                }

                is TACCmd.Simple.AssertCmd -> {
                    // For assert, force condition and store the variable
                    (nextState.forceAssume(command.o.toTACExpr(), optimized) ?: nextState)
                        .storeExpression(variable, optimized.asTACExpr(variable.tag))
                }

                is TACCmd.Simple.AssigningCmd -> {
                    // For assignments, force RHS if it's an expression assignment and store the result
                    val forced = if (command is TACCmd.Simple.AssigningCmd.AssignExpCmd) {
                        nextState.forceAssume(command.rhs, optimized) ?: nextState
                    } else {
                        nextState
                    }
                    forced.storeExpression(variable, optimized.asTACExpr(variable.tag))
                }

                else -> `impossible!`
            }
        }

        operator fun get(variable: TACSymbol) = this.value(variable)

        override fun kill(lhs: TACSymbol.Var) = this.copy(memory = memory.kill(lhs))

        override fun byteCopy(
            srcBase: TACSymbol.Var,
            srcOffset: TACSymbol,
            length: TACSymbol,
            dstBase: TACSymbol.Var,
            dstOffset: TACSymbol
        ) = this.copy(memory = memory.byteCopy(srcBase, srcOffset, length, dstBase, dstOffset))

        override fun memstore(
            storedExpr: TACExpr,
            base: TACSymbol.Var,
            location: TACSymbol,
            storeType: StoreType
        ) = this.copy(memory = memory.memstore(storedExpr, base, location, storeType))

        override fun load(
            lhs: TACSymbol.Var,
            base: TACSymbol.Var,
            location: TACSymbol
        ) = this.copy(memory = memory.load(lhs, base, location))

        override fun value(sym: TACSymbol.Var) = memory.value(sym)

        override fun toTacAssignments() = memory.toTacAssignments()

        override fun storeExpression(
            lhs: TACSymbol.Var,
            rhs: TACExpr
        ) = this.copy(memory = memory.storeExpression(lhs, rhs))

        override fun forceAssume(
            expr: TACExpr, expectedResult: BigInteger
        ) = memory.forceAssume(expr, expectedResult)?.let { this.copy(memory = it) }

        override fun checkConflict(expr: TACExpr, expectedValue: BigInteger): DynamicEvaluationState? =
            memory.checkConflict(expr, expectedValue)?.let { this.copy(memory = it) }
    }
}

private fun BigInteger?.isFalse(): Boolean = this != null && this == BigInteger.ZERO

/**
 * The optimized program keeps [TACCmd.Simple.AnnotationCmd] that mark all blocks in the unoptimized program
 * that the block in the optimized program originates from. This methods extract the set of blocks
 * from the optimized program.
 */
private fun CoreTACProgram.allowedBlocks(reachableBlocks: Set<NBId>) =
    this.code.filter { it.key in reachableBlocks }.values.flatMapToSet {
        it.mapNotNull { cmd ->
            cmd.maybeAnnotation(
                TWOSTAGE_META_BLOCKORIGIN
            )
        }
    }