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

class PromoteMathU128WrappingSubTest {

    private fun hasWrappingSubCall(cfg: SbfCFG): Boolean =
        cfg.getBlocks().values.any { bb ->
            bb.getInstructions().any { inst ->
                inst is SbfInstruction.Call && inst.name == CvlrFunctions.CVT_u128_wrapping_sub
            }
        }

    private fun countWrappingSubCalls(cfg: SbfCFG): Int =
        cfg.getBlocks().values.sumOf { bb ->
            bb.getInstructions().count { inst ->
                inst is SbfInstruction.Call && inst.name == CvlrFunctions.CVT_u128_wrapping_sub
            }
        }

    private val globals = GlobalVariables(DefaultElfFileView)
    private val memSummaries = MemorySummaries()

    fun promoteU128WrappingSub(
        cfg: MutableSbfCFG,
        globals: GlobalVariables,
        memSummaries: MemorySummaries,
        useScalarAnalysis: Boolean = false,
        useLivenessAnalysis: Boolean = true
    ) {
        promoteMathIntrinsics(
            cfg,
            transformers = listOf(U128WrappingSubTransform),
            globals = globals,
            memSummaries,
            PromoteMathIntrinsicsOptions(useScalarAnalysis, useLivenessAnalysis)
        )
    }
    // -------------------------------------------------------------------------
    // Structural tests — verify that the CFG is (or is not) transformed
    // -------------------------------------------------------------------------

    /** Canonical order (1)(2)(3)(4): pattern is recognized and promoted. **/
    @Test
    fun `canonical order is promoted`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 5; r2 = 0; r3 = 3; r4 = 0
                BinOp.SUB(r2, r4)                          // (1) xHigh -= yHigh
                select(r5, CondOp.GT(r3, r1), 1, 0) // (2) borrow = yLow ugt xLow
                BinOp.SUB(r2, r5)                          // (3) xHigh -= borrow
                BinOp.SUB(r1, r3)                          // (4) xLow  -= yLow
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteU128WrappingSub(cfg, globals, memSummaries)
        println("After:\n$cfg")
        Assertions.assertTrue(hasWrappingSubCall(cfg))
    }

    /** Instruction (4) appears before the others: promotion fails. **/
    @Test
    fun `inst4 before others cannot be promoted`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 5; r2 = 0; r3 = 3; r4 = 0
                BinOp.SUB(r1, r3)                          // (4) first
                BinOp.SUB(r2, r4)                          // (1)
                select(r5, CondOp.GT(r3, r1), 1, 0) // (2)
                BinOp.SUB(r2, r5)                          // (3)
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteU128WrappingSub(cfg, globals, memSummaries)
        println("After:\n$cfg")
        Assertions.assertFalse(hasWrappingSubCall(cfg))
    }

    /** Symmetric borrow form `xLow ult yLow` instead of `yLow ugt xLow`: still promoted. **/
    @Test
    fun `symmetric borrow form (LT) is promoted`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 5; r2 = 0; r3 = 3; r4 = 0
                BinOp.SUB(r2, r4)
                select(r5, CondOp.LT(r1, r3), 1, 0)  // xLow ult yLow
                BinOp.SUB(r2, r5)
                BinOp.SUB(r1, r3)
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteU128WrappingSub(cfg, globals, memSummaries)
        println("After:\n$cfg")
        Assertions.assertTrue(hasWrappingSubCall(cfg))
    }

    /** Wrong select condition (EQ instead of GT/LT): not a borrow, no promotion. **/
    @Test
    fun `wrong select condition op is not promoted`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 5; r2 = 0; r3 = 3; r4 = 0
                BinOp.SUB(r2, r4)
                select(r5, CondOp.EQ(r3, r1), 1, 0) // wrong: EQ, not GT/LT
                BinOp.SUB(r2, r5)
                BinOp.SUB(r1, r3)
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteU128WrappingSub(cfg, globals, memSummaries)
        println("After:\n$cfg")
        Assertions.assertFalse(hasWrappingSubCall(cfg))
    }

    /** Wrong select true-value (2 instead of 1): borrow shape unrecognized, no promotion. **/
    @Test
    fun `wrong select true-value is not promoted`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 5; r2 = 0; r3 = 3; r4 = 0
                BinOp.SUB(r2, r4)
                select(r5, CondOp.GT(r3, r1), 2, 0)  // wrong: trueVal=2, not 1
                BinOp.SUB(r2, r5)
                BinOp.SUB(r1, r3)
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteU128WrappingSub(cfg, globals, memSummaries)
        println("After:\n$cfg")
        Assertions.assertFalse(hasWrappingSubCall(cfg))
    }

    /**
     * Between (2) and (3), a non-pattern instruction reads the borrow register (r5).
     * The matcher's `findFirstAfter` for (3) stops only on **writes** to the borrow,
     * so a non-pattern READ slips through.
     *
     * If the pattern were promoted, the matcher removes (1)..(4) and leaves the
     * non-pattern `r6 = r5` in place. After removal, `r5` at the non-pattern position
     * holds its pre-pattern value (not the borrow flag that `(2)` would have
     * produced), so `r6` would differ between the original and lowered code. The
     * matcher must not promote this pattern.
     */
    @Test
    fun `non-pattern read of borrow between (2) and (3) is not promoted`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 5; r2 = 0; r3 = 3; r4 = 0
                BinOp.SUB(r2, r4)                          // (1) xHigh -= yHigh
                select(r5, CondOp.GT(r3, r1), 1, 0)       // (2) borrow
                r6 = r5                                     // non-pattern: reads borrow
                BinOp.SUB(r2, r5)                          // (3) xHigh -= borrow
                BinOp.SUB(r1, r3)                          // (4) xLow -= yLow
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteU128WrappingSub(cfg, globals, memSummaries)
        println("After:\n$cfg")
        Assertions.assertFalse(hasWrappingSubCall(cfg))
    }

    /** Borrow register used in ADD rather than SUB for instruction (3): no promotion. **/
    @Test
    fun `borrow used in ADD instead of SUB is not promoted`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 5; r2 = 0; r3 = 3; r4 = 0
                BinOp.SUB(r2, r4)
                select(r5, CondOp.GT(r3, r1), 1, 0)
                BinOp.ADD(r2, r5)  // wrong: ADD, not SUB
                BinOp.SUB(r1, r3)
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteU128WrappingSub(cfg, globals, memSummaries)
        println("After:\n$cfg")
        Assertions.assertFalse(hasWrappingSubCall(cfg))
    }

    @Test
    fun `without scalar analysis cannot promote`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r10[-24] = 3
                r3 = r10[-24]
                r1 = 5; r2 = 0; r4 = 0
                BinOp.SUB(r2, r4)                          // (1) xHigh -= yHigh
                select(r5, CondOp.GT(r3, r1), 1, 0) // (2) borrow = yLow ugt xLow
                BinOp.SUB(r2, r5)                          // (3) xHigh -= borrow
                r7 = r10[-24]
                BinOp.SUB(r1, r7)                          // (4) xLow  -= yLow
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteU128WrappingSub(cfg, globals, memSummaries, false)
        println("After:\n$cfg")
        Assertions.assertFalse(hasWrappingSubCall(cfg))
    }

    @Test
    fun `with scalar analysis can promote`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r10[-24] = 3
                r3 = r10[-24]
                r1 = 5; r2 = 0; r4 = 0
                BinOp.SUB(r2, r4)                          // (1) xHigh -= yHigh
                select(r5, CondOp.GT(r3, r1), 1, 0) // (2) borrow = yLow ugt xLow
                BinOp.SUB(r2, r5)                          // (3) xHigh -= borrow
                r7 = r10[-24]
                BinOp.SUB(r1, r7)                          // (4) xLow  -= yLow
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteU128WrappingSub(cfg, globals, memSummaries, true)
        println("After:\n$cfg")
        Assertions.assertTrue(hasWrappingSubCall(cfg))
    }

    /**
     * Two independent patterns in the same block using disjoint register pairs:
     * - Pattern 1: (xLow=r1, xHigh=r2) − (yLow=r3, yHigh=r4), borrow in r5
     * - Pattern 2: (xLow=r6, xHigh=r7) − (yLow=r8, yHigh=r9), borrow in r5
     * Both must be promoted, producing exactly two calls.
     */
    @Test
    fun `two independent patterns in same block are both promoted`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                // Pattern 1
                r1 = 5; r2 = 0; r3 = 3; r4 = 0
                BinOp.SUB(r2, r4)                          // (1) xHigh -= yHigh
                select(r5, CondOp.GT(r3, r1), 1, 0)        // (2) borrow = yLow ugt xLow
                BinOp.SUB(r2, r5)                          // (3) xHigh -= borrow
                BinOp.SUB(r1, r3)                          // (4) xLow  -= yLow
                // Pattern 2
                r6 = 10; r7 = 0; r8 = 4; r9 = 0
                BinOp.SUB(r7, r9)                          // (1) xHigh -= yHigh
                select(r5, CondOp.GT(r8, r6), 1, 0)        // (2) borrow = yLow ugt xLow
                BinOp.SUB(r7, r5)                          // (3) xHigh -= borrow
                BinOp.SUB(r6, r8)                          // (4) xLow  -= yLow
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteU128WrappingSub(cfg, globals, memSummaries)
        println("After:\n$cfg")
        Assertions.assertEquals(2, countWrappingSubCalls(cfg))
    }

    // -------------------------------------------------------------------------
    // End-to-end correctness tests — promote + lower + TAC verify
    // -------------------------------------------------------------------------

    /** 5 - 3 = 2 (no wrapping): resLow=2, resHigh=0. **/
    @Test
    fun `5 minus 3 equals 2`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 5; r2 = 0; r3 = 3; r4 = 0
                BinOp.SUB(r2, r4)
                select(r5, CondOp.GT(r3, r1), 1, 0)
                BinOp.SUB(r2, r5)
                BinOp.SUB(r1, r3)
                assert(CondOp.EQ(r1, 2UL))
                assert(CondOp.EQ(r2, 0UL))
                exit()
            }
        }
        promoteU128WrappingSub(cfg, globals, memSummaries)
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(1, countWrappingSubCalls(cfg))
        Assertions.assertEquals(true, verify(tacProg))
    }

    /** 5 - 3 = 2 (no wrapping): resLow=2, resHigh=0. **/
    @Test
    fun `5 minus 3 equals 2 with register clobbered by non-pattern instruction`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r10[-24] = 3 // yLow
                r10[-32] = 0 // yHigh
                r1 = 5  // xLow
                r2 = 0  // xHigh
                //r3 = 3  // yLow
                r3 = r10[-24]
                r4 = r10[-32]  // yHigh
                BinOp.SUB(r2, r4) // xHigh - yHigh
                select(r5, CondOp.GT(r3, r1), 1, 0)
                BinOp.SUB(r2, r5) // xHigh - yHigh - carry
                r4 = r10[-24]  // yLow
                BinOp.SUB(r1, r4) // xLow - yLow
                //BinOp.SUB(r1, r3) // xLow - yLow
                assert(CondOp.EQ(r1, 2UL))
                assert(CondOp.EQ(r2, 0UL))
                exit()
            }
        }
        promoteU128WrappingSub(cfg, globals, memSummaries, true)
        println("After:\n$cfg")
        Assertions.assertEquals(1, countWrappingSubCalls(cfg))
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }


    /** x - x = 0: both halves of the result are zero. **/
    @Test
    fun `x minus x equals 0`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 42; r2 = 7; r3 = 42; r4 = 7
                BinOp.SUB(r2, r4)
                select(r5, CondOp.GT(r3, r1), 1, 0)
                BinOp.SUB(r2, r5)
                BinOp.SUB(r1, r3)
                assert(CondOp.EQ(r1, 0UL))
                assert(CondOp.EQ(r2, 0UL))
                exit()
            }
        }
        promoteU128WrappingSub(cfg, globals, memSummaries)
        Assertions.assertEquals(1, countWrappingSubCalls(cfg))
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }

    /**
     * 0 - 1 wraps to (2^64-1, 2^64-1): both halves should be 0xFFFF_FFFF_FFFF_FFFF.
     *
     * The mask trick is used to avoid the prover sign-extending the immediate -1 to 256 bits:
     * `CVT_mask_64(-1)` forces r0 = 0xFFFF_FFFF_FFFF_FFFF as a 64-bit value.
     */
    @Test
    fun `0 minus 1 wraps to max u128`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 0; r2 = 0; r3 = 1; r4 = 0
                BinOp.SUB(r2, r4)
                select(r5, CondOp.GT(r3, r1), 1, 0)
                BinOp.SUB(r2, r5)
                BinOp.SUB(r1, r3)
                // resLow is in r1 and resHigh is in r2 after promotion
                r6 = r1                                    // save resLow
                r7 = r2                                    // save resHigh
                r1 = -1
                "CVT_mask_64"()                            // r0 = 0xFFFF_FFFF_FFFF_FFFF
                assert(CondOp.EQ(r6, r0))
                assert(CondOp.EQ(r7, r0))
                exit()
            }
        }
        promoteU128WrappingSub(cfg, globals, memSummaries)
        Assertions.assertEquals(1, countWrappingSubCalls(cfg))
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }


}
