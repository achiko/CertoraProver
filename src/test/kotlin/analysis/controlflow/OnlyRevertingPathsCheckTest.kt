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

package analysis.controlflow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tac.Tag
import vc.data.*
import java.math.BigInteger

/**
 * Unit tests for [checkIfAllPathsAreLastReverted].
 *
 * Programs are built synthetically using [TACProgramBuilder]. The three reverting command forms tested here are:
 *  - `assume(false)` ([TACCmd.Simple.AssumeCmd] with a false constant)
 *  - `assert(false)` tagged with [TACMeta.SYNTHETIC_LOOP_END]
 *  - The presence of a [TACMeta.END_LOOP] annotation to distinguish a definitive all-revert from one where a loop
 *    may have hidden non-reverting paths.
 */
internal class OnlyRevertingPathsCheckTest : TACBuilderAuxiliaries() {

    /** A false constant suitable for use in assume/assert commands. */
    private val falseSym = TACSymbol.Const(BigInteger.ZERO, Tag.Bool)

    /**
     * A single non-reverting sink block → there is a non-reverting path.
     *
     * Graph: [NopCmd] (sink)
     */
    @Test
    fun nonRevertingPath() {
        val prog = TACProgramBuilder {
            nop
        }.code
        assertEquals(
            AllPathsRevertedResult.NON_REVERTING_PATH_EXISTS,
            checkIfAllPathsAreLastReverted(prog)
        )
    }

    /**
     * The only path goes through `assume(false)` and never reaches a non-reverting sink.
     * No loop annotation present → definitive all-revert.
     *
     * Graph: [AssumeCmd(false)] (sink, pruned by the BFS)
     */
    @Test
    fun allRevertAssumeFalseDefinitive() {
        val prog = TACProgramBuilder {
            addCmd(TACCmd.Simple.AssumeCmd(falseSym, ""))
        }.code
        assertEquals(
            AllPathsRevertedResult.ALL_REVERT_DEFINITIVE,
            checkIfAllPathsAreLastReverted(prog)
        )
    }

    /**
     * An `assert(false)` tagged with [TACMeta.SYNTHETIC_LOOP_END] counts as a reverting command.
     * No [TACMeta.END_LOOP] annotation → definitive all-revert.
     *
     * Graph: [AssertCmd(false) + SYNTHETIC_LOOP_END meta] (sink, pruned)
     */
    @Test
    fun allRevertSyntheticLoopEndDefinitive() {
        val prog = TACProgramBuilder {
            assert(falseSym, "")
            addMetaToLastCmd(TACMeta.SYNTHETIC_LOOP_END, 0)
        }.code
        assertEquals(
            AllPathsRevertedResult.ALL_REVERT_DEFINITIVE,
            checkIfAllPathsAreLastReverted(prog)
        )
    }

    /**
     * Diamond: one branch has `assume(false)` (reverts), the other has a plain NopCmd (non-reverting sink).
     * → At least one non-reverting path exists.
     *
     * Graph: root → [AssumeCmd(false)] sink
     *             → [NopCmd] sink
     */
    @Test
    fun diamondOneRevertingBranch() {
        val prog = TACProgramBuilder {
            jumpCond(x)
            jump {
                nop  // non-reverting sink
            }
            jump {
                addCmd(TACCmd.Simple.AssumeCmd(falseSym, ""))
            }
        }.code
        assertEquals(
            AllPathsRevertedResult.NON_REVERTING_PATH_EXISTS,
            checkIfAllPathsAreLastReverted(prog)
        )
    }

    /**
     * Diamond: both branches have `assume(false)`. No loop annotation → definitive all-revert.
     *
     * Graph: root → [AssumeCmd(false)]
     *             → [AssumeCmd(false)]
     */
    @Test
    fun diamondBothRevertNoLoop() {
        val prog = TACProgramBuilder {
            jumpCond(x)
            jump {
                addCmd(TACCmd.Simple.AssumeCmd(falseSym, ""))
            }
            jump {
                addCmd(TACCmd.Simple.AssumeCmd(falseSym, ""))
            }
        }.code
        assertEquals(
            AllPathsRevertedResult.ALL_REVERT_DEFINITIVE,
            checkIfAllPathsAreLastReverted(prog)
        )
    }

    /**
     * All visible paths revert (BFS prunes at `assume(false)`), but a [TACMeta.END_LOOP] annotation is present
     * in the program (e.g. left by the loop unroller on a successor block that the BFS never visits).
     * → Qualified result: non-reverting paths may exist with a higher loop unrolling bound.
     *
     * Graph: Block0: [AssumeCmd(false), JumpCmd → Block1]
     *        Block1: [AnnotationCmd(END_LOOP)]  ← reachable in the TAC but BFS-pruned at Block0
     */
    @Test
    fun allRevertButLoopAnnotationPresent() {
        val prog = TACProgramBuilder {
            addCmd(TACCmd.Simple.AssumeCmd(falseSym, ""))
            jump(1) {
                addCmd(TACCmd.Simple.AnnotationCmd(TACMeta.END_LOOP))
            }
        }.code
        assertEquals(
            AllPathsRevertedResult.ALL_REVERT_BUT_LOOP_PRESENT,
            checkIfAllPathsAreLastReverted(prog)
        )
    }
}