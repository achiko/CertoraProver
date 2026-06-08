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

package sbf

import cvlr.CvlrFunctions
import sbf.cfg.*
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*
import sbf.disassembler.GlobalVariables
import sbf.domains.MemorySummaries

class PromoteMathU128BinRelTest {

    private val globals = GlobalVariables(DefaultElfFileView)
    private val memSummaries = MemorySummaries()

    private fun promoteBinRel(
        cfg: MutableSbfCFG,
        useScalarAnalysis: Boolean = false,
        useLivenessAnalysis: Boolean = true
    ) {
        promoteMathIntrinsics(
            cfg,
            transformers = listOf(U128BinRelTransform),
            globals = globals,
            memSummaries,
            PromoteMathIntrinsicsOptions(useScalarAnalysis, useLivenessAnalysis)
        )
    }

    private fun countIntrinsicCalls(cfg: SbfCFG, name: String): Int =
        cfg.getBlocks().values.sumOf { bb ->
            bb.getInstructions().count { inst ->
                inst is SbfInstruction.Call && inst.name == name
            }
        }

    private fun countLtCalls(cfg: SbfCFG) = countIntrinsicCalls(cfg, CvlrFunctions.CVT_u128_lt)
    private fun countLeqCalls(cfg: SbfCFG) = countIntrinsicCalls(cfg, CvlrFunctions.CVT_u128_leq)

    // -------------------------------------------------------------------------
    // Structural tests — verify that the CFG is (or is not) transformed
    // -------------------------------------------------------------------------

    /**
     * Canonical LT pattern:
     * ```
     *   (1) tmpLow  = select(xLow  LT yLow,  1, 0)
     *   (2) tmpHigh = select(xHigh LT yHigh, 1, 0)
     *   (3) result  = select(xHigh EQ yHigh, tmpLow, tmpHigh)
     * ```
     */
    @Test
    fun `canonical LT pattern is promoted to CVT_u128_lt`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                // xLow=r1, xHigh=r2, yLow=r3, yHigh=r4
                select(r5, CondOp.LT(r1, r3), 1, 0)   // (1) tmpLow
                select(r6, CondOp.LT(r2, r4), 1, 0)   // (2) tmpHigh
                select(r7, CondOp.EQ(r2, r4), r5, r6) // (3) result
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteBinRel(cfg)
        println("After:\n$cfg")
        Assertions.assertEquals(1, countLtCalls(cfg))
    }

    /**
     * Canonical LE pattern: promoted to CVT_u128_leq.
     */
    @Test
    fun `canonical LE pattern is promoted to CVT_u128_leq`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                select(r5, CondOp.LE(r1, r3), 1, 0)
                select(r6, CondOp.LE(r2, r4), 1, 0)
                select(r7, CondOp.EQ(r2, r4), r5, r6)
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteBinRel(cfg)
        println("After:\n$cfg")
        Assertions.assertEquals(1, countLeqCalls(cfg))
    }

    /**
     * GT pattern: operands are swapped and promoted to CVT_u128_lt.
     */
    @Test
    fun `canonical GT pattern is promoted to CVT_u128_lt with swapped params`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                select(r5, CondOp.GT(r1, r3), 1, 0)
                select(r6, CondOp.GT(r2, r4), 1, 0)
                select(r7, CondOp.EQ(r2, r4), r5, r6)
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteBinRel(cfg)
        println("After:\n$cfg")
        Assertions.assertEquals(1, countLtCalls(cfg))
    }

    /**
     * GE pattern: operands are swapped and promoted to CVT_u128_leq.
     */
    @Test
    fun `canonical GE pattern is promoted to CVT_u128_leq with swapped params`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                select(r5, CondOp.GE(r1, r3), 1, 0)
                select(r6, CondOp.GE(r2, r4), 1, 0)
                select(r7, CondOp.EQ(r2, r4), r5, r6)
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteBinRel(cfg)
        println("After:\n$cfg")
        Assertions.assertEquals(1, countLeqCalls(cfg))
    }

    /**
     * The combining select (3) has a non-EQ condition: no match.
     */
    @Test
    fun `combining select with non-EQ condition is not promoted`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                select(r5, CondOp.LT(r1, r3), 1, 0)
                select(r6, CondOp.LT(r2, r4), 1, 0)
                select(r7, CondOp.LT(r2, r4), r5, r6)  // LT instead of EQ
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteBinRel(cfg)
        println("After:\n$cfg")
        Assertions.assertEquals(0, countLtCalls(cfg))
        Assertions.assertEquals(0, countLeqCalls(cfg))
    }

    /**
     * Instructions 1 and 2 use different operators: no match (both must use the same op).
     */
    @Test
    fun `mismatched ops in instructions 1 and 2 are not promoted`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                select(r5, CondOp.LT(r1, r3), 1, 0)   // LT
                select(r6, CondOp.LE(r2, r4), 1, 0)   // LE — mismatch
                select(r7, CondOp.EQ(r2, r4), r5, r6)
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteBinRel(cfg)
        println("After:\n$cfg")
        Assertions.assertEquals(0, countLtCalls(cfg))
        Assertions.assertEquals(0, countLeqCalls(cfg))
    }

    /**
     * The EQ condition in instruction 3 compares an unrelated register pair, not xHigh/yHigh:
     * the false-positive guard (lines 112-116) must reject this.
     */
    @Test
    fun `EQ condition on unrelated registers is not promoted`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                // xLow=r1, xHigh=r2, yLow=r3, yHigh=r4; unrelated pair r8,r9
                select(r5, CondOp.LT(r1, r3), 1, 0)
                select(r6, CondOp.LT(r2, r4), 1, 0)
                select(r7, CondOp.EQ(r8, r9), r5, r6) // EQ on r8,r9 — not xHigh/yHigh
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteBinRel(cfg, useScalarAnalysis = true)
        println("After:\n$cfg")
        Assertions.assertEquals(0, countLtCalls(cfg))
        Assertions.assertEquals(0, countLeqCalls(cfg))
    }

    /**
     * The EQ condition compares xHigh/yHigh in swapped order: still a valid match.
     */
    @Test
    fun `swapped EQ operands in combining select are still promoted`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                select(r5, CondOp.LT(r1, r3), 1, 0)
                select(r6, CondOp.LT(r2, r4), 1, 0)
                select(r7, CondOp.EQ(r4, r2), r5, r6) // r4 EQ r2 instead of r2 EQ r4
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteBinRel(cfg)
        println("After:\n$cfg")
        Assertions.assertEquals(1, countLtCalls(cfg))
    }

    /**
     * Between (1) and (2), a non-pattern instruction modifies yHigh (r4) using tmpLow (r5).
     * The matcher's [resolveInputParam] clobber check only looks at writes AFTER the
     * first pattern use of a register, so a write to r4 in `(p1, p2)` is invisible.
     *
     * If the pattern were promoted, the matcher removes (1), (2), (3) and leaves the
     * non-pattern `r4 += r5` in place. After removal, `r5` at the non-pattern position
     * holds its pre-pattern value (not the low-select carry that `(1)` would have
     * produced), so the lowered intrinsic sees a different yHigh than the original
     * 3-select would have computed. This is precisely the shape of a u128
     * overflowing_add overflow-check idiom — the matcher must not promote it.
     */
    @Test
    fun `non-pattern modification of yHigh between p1 and p2 is not promoted`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                // xLow=r1, xHigh=r2, yLow=r3, yHigh=r4; tmpLow=r5, tmpHigh=r6, result=r7
                select(r5, CondOp.LT(r1, r3), 1, 0)   // (1) tmpLow
                BinOp.ADD(r4, r5)                      // non-pattern: yHigh += tmpLow
                select(r6, CondOp.LT(r2, r4), 1, 0)   // (2) tmpHigh — yHigh is now derived
                select(r7, CondOp.EQ(r2, r4), r5, r6) // (3) combine
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteBinRel(cfg)
        println("After:\n$cfg")
        Assertions.assertEquals(0, countLtCalls(cfg))
        Assertions.assertEquals(0, countLeqCalls(cfg))
    }

    /**
     * Two independent LT patterns in the same block: both must be promoted.
     */
    @Test
    fun `two independent LT patterns in same block are both promoted`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                // Pattern 1: x=(r1,r2), y=(r3,r4)
                select(r5, CondOp.LT(r1, r3), 1, 0)
                select(r6, CondOp.LT(r2, r4), 1, 0)
                select(r7, CondOp.EQ(r2, r4), r5, r6)
                // Pattern 2: x=(r8,r9), y=(r1,r2)  (disjoint dst registers)
                select(r3, CondOp.LT(r8, r1), 1, 0)
                select(r4, CondOp.LT(r9, r2), 1, 0)
                select(r5, CondOp.EQ(r9, r2), r3, r4)
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteBinRel(cfg)
        println("After:\n$cfg")
        Assertions.assertEquals(2, countLtCalls(cfg))
    }

    // -------------------------------------------------------------------------
    // End-to-end correctness tests — promote + lower + TAC verify
    // -------------------------------------------------------------------------

    /** 3 LT 5 (as u128 with zero high halves): result = 1. **/
    @Test
    fun `3 lt 5 as u128 is true`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 3; r2 = 0  // x = (xLow=3, xHigh=0)
                r3 = 5; r4 = 0  // y = (yLow=5, yHigh=0)
                select(r5, CondOp.LT(r1, r3), 1, 0)
                select(r6, CondOp.LT(r2, r4), 1, 0)
                select(r7, CondOp.EQ(r2, r4), r5, r6)
                assert(CondOp.EQ(r7, 1UL))
                exit()
            }
        }
        promoteBinRel(cfg)
        Assertions.assertEquals(1, countLtCalls(cfg))
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }

    /** 5 LT 3 (as u128 with zero high halves): result = 0. **/
    @Test
    fun `5 lt 3 as u128 is false`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 5; r2 = 0
                r3 = 3; r4 = 0
                select(r5, CondOp.LT(r1, r3), 1, 0)
                select(r6, CondOp.LT(r2, r4), 1, 0)
                select(r7, CondOp.EQ(r2, r4), r5, r6)
                assert(CondOp.EQ(r7, 0UL))
                exit()
            }
        }
        promoteBinRel(cfg)
        Assertions.assertEquals(1, countLtCalls(cfg))
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }

    /** x LT x (equal values): result = 0. **/
    @Test
    fun `x lt x as u128 is false`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 7; r2 = 3   // x = (7, 3)
                r3 = 7; r4 = 3   // y = x
                select(r5, CondOp.LT(r1, r3), 1, 0)
                select(r6, CondOp.LT(r2, r4), 1, 0)
                select(r7, CondOp.EQ(r2, r4), r5, r6)
                assert(CondOp.EQ(r7, 0UL))
                exit()
            }
        }
        promoteBinRel(cfg)
        Assertions.assertEquals(1, countLtCalls(cfg))
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }

    /**
     * High halves differ: (1, 0) LT (2, 0) — high half dominates, result = 1
     * regardless of low halves.
     **/
    @Test
    fun `high half dominates comparison`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 99; r2 = 1  // x = (xLow=99, xHigh=1)
                r3 = 0;  r4 = 2  // y = (yLow=0,  yHigh=2)
                select(r5, CondOp.LT(r1, r3), 1, 0)
                select(r6, CondOp.LT(r2, r4), 1, 0)
                select(r7, CondOp.EQ(r2, r4), r5, r6)
                assert(CondOp.EQ(r7, 1UL))
                exit()
            }
        }
        promoteBinRel(cfg)
        Assertions.assertEquals(1, countLtCalls(cfg))
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }

    /** 3 LE 3 (equal): result = 1. **/
    @Test
    fun `3 leq 3 as u128 is true`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 3; r2 = 0
                r3 = 3; r4 = 0
                select(r5, CondOp.LE(r1, r3), 1, 0)
                select(r6, CondOp.LE(r2, r4), 1, 0)
                select(r7, CondOp.EQ(r2, r4), r5, r6)
                assert(CondOp.EQ(r7, 1UL))
                exit()
            }
        }
        promoteBinRel(cfg)
        Assertions.assertEquals(1, countLeqCalls(cfg))
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }

    @Test
    fun `r0 is clobbered by promoted intrinsic`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r0 = 42             // live value in r0 before the pattern
                r1 = 3; r2 = 0     // x = (xLow=3, xHigh=0)
                r3 = 5; r4 = 0     // y = (yLow=5, yHigh=0)
                select(r5, CondOp.LT(r1, r3), 1, 0)
                select(r6, CondOp.LT(r2, r4), 1, 0)
                select(r7, CondOp.EQ(r2, r4), r5, r6)
                assert(CondOp.EQ(r7, 1UL))  // 3 < 5 → 1 (correct)
                assert(CondOp.EQ(r0, 42UL)) // r0 should still be 42
                exit()
            }
        }
        println("$cfg")
        promoteBinRel(cfg)
        println("$cfg")
        Assertions.assertEquals(1, countLtCalls(cfg))
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }
}
