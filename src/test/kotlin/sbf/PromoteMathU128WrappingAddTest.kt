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

class PromoteMathU128WrappingAddTest {

    private fun countWrappingAddCalls(cfg: SbfCFG): Int =
        cfg.getBlocks().values.sumOf { bb ->
            bb.getInstructions().count { inst ->
                inst is SbfInstruction.Call && inst.name == CvlrFunctions.CVT_u128_wrapping_add
            }
        }

    private val globals = GlobalVariables(DefaultElfFileView)
    private val memSummaries = MemorySummaries()

    fun promoteU128WrappingAdd(
        cfg: MutableSbfCFG,
        globals: GlobalVariables,
        memSummaries: MemorySummaries,
        useScalarAnalysis: Boolean = false,
        useLivenessAnalysis: Boolean = true
    ) {
        promoteMathIntrinsics(
            cfg,
            transformers = listOf(U128WrappingAddTransform),
            globals = globals,
            memSummaries,
            PromoteMathIntrinsicsOptions(useScalarAnalysis, useLivenessAnalysis)
        )
    }

    // -------------------------------------------------------------------------
    // Structural tests — verify that the CFG is (or is not) transformed
    // -------------------------------------------------------------------------

    /**
     * Canonical order (1)(2)(3)(4)(5): pattern is recognized and promoted.
     *
     * Register layout:
     *   r1 = xHigh (= 0), r2 = yHigh (= 0) = resHigh, r3 = yLow (= 3), r4 = xLow (= 5)
     *
     * Pattern:
     * ```
     *   (1) r2 = r2 + r1           resHigh += xHigh
     *   (2) r1 = r3                resLow  = yLow
     *   (3) r1 = r1 + r4           resLow  += xLow
     *   (4) r5 = select(r3 ugt r1, 1, 0)  carry = yLow ugt resLow
     *   (5) r2 = r2 + r5           resHigh += carry
     * ```
     */
    @Test
    fun `canonical order is promoted`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 0; r2 = 0; r3 = 3; r4 = 5
                BinOp.ADD(r2, r1)                 // (1) resHigh += xHigh
                r1 = r3                                       // (2) resLow = yLow
                BinOp.ADD(r1, r4)                 // (3) resLow += xLow
                select(r5, CondOp.GT(r3, r1), 1, 0)  // (4) carry = yLow ugt resLow
                BinOp.ADD(r2, r5)                 // (5) resHigh += carry
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteU128WrappingAdd(cfg, globals, memSummaries)
        println("After:\n$cfg")
        Assertions.assertTrue(countWrappingAddCalls(cfg) > 0)
    }

    /** Instruction (4) — the select — appears before the others: promotion fails. **/
    @Test
    fun `select before other instructions cannot be promoted`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 0; r2 = 0; r3 = 3; r4 = 5
                select(r5, CondOp.GT(r3, r1), 1, 0)  // (4) first — no prior (1)(2)(3)
                BinOp.ADD(r2, r1)                // (1)
                r1 = r3                                       // (2)
                BinOp.ADD(r1, r4)                // (3)
                BinOp.ADD(r2, r5)                // (5)
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteU128WrappingAdd(cfg, globals, memSummaries)
        println("After:\n$cfg")
        Assertions.assertFalse(countWrappingAddCalls(cfg) > 0)
    }

    /** Symmetric carry form `resLow ult yLow` instead of `yLow ugt resLow`: still promoted. **/
    @Test
    fun `symmetric carry form (LT) is promoted`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 0; r2 = 0; r3 = 3; r4 = 5
                BinOp.ADD(r2, r1)
                r1 = r3
                BinOp.ADD(r1, r4)
                select(r5, CondOp.LT(r1, r3), 1, 0)  // resLow ult yLow
                BinOp.ADD(r2, r5)
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteU128WrappingAdd(cfg, globals, memSummaries)
        println("After:\n$cfg")
        Assertions.assertTrue(countWrappingAddCalls(cfg) > 0)
    }

    /** Wrong select condition (EQ instead of GT/LT): not a carry, no promotion. **/
    @Test
    fun `wrong select condition op is not promoted`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 0; r2 = 0; r3 = 3; r4 = 5
                BinOp.ADD(r2, r1)
                r1 = r3
                BinOp.ADD(r1, r4)
                select(r5, CondOp.EQ(r3, r1), 1, 0)  // wrong: EQ, not GT/LT
                BinOp.ADD(r2, r5)
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteU128WrappingAdd(cfg, globals, memSummaries)
        println("After:\n$cfg")
        Assertions.assertFalse(countWrappingAddCalls(cfg) > 0)
    }

    /** Wrong select true-value (2 instead of 1): carry shape unrecognized, no promotion. **/
    @Test
    fun `wrong select true-value is not promoted`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 0; r2 = 0; r3 = 3; r4 = 5
                BinOp.ADD(r2, r1)
                r1 = r3
                BinOp.ADD(r1, r4)
                select(r5, CondOp.GT(r3, r1), 2, 0)  // wrong: trueVal=2, not 1
                BinOp.ADD(r2, r5)
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteU128WrappingAdd(cfg, globals, memSummaries)
        println("After:\n$cfg")
        Assertions.assertFalse(countWrappingAddCalls(cfg) > 0)
    }

    /** Carry register used in SUB rather than ADD for instruction (5): no promotion. **/
    @Test
    fun `carry used in SUB instead of ADD is not promoted`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 0; r2 = 0; r3 = 3; r4 = 5
                BinOp.ADD(r2, r1)
                r1 = r3
                BinOp.ADD(r1, r4)
                select(r5, CondOp.GT(r3, r1), 1, 0)
                BinOp.SUB(r2, r5)  // wrong: SUB, not ADD
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteU128WrappingAdd(cfg, globals, memSummaries)
        println("After:\n$cfg")
        Assertions.assertFalse(countWrappingAddCalls(cfg) > 0)
    }

    @Test
    fun `without scalar analysis cannot be promoted`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r10[-24] = 3
                r3 = r10[-24]
                r1 = 0; r2 = 0; r4 = 5
                BinOp.ADD(r2, r1)           // (1) resHigh += xHigh
                r7 = r10[-24]                            // non-pattern: r7 = yLow value
                r1 = r7                                  // (2) resLow = r7 (not r3)
                BinOp.ADD(r1, r4)           // (3) resLow += xLow
                select(r5, CondOp.GT(r3, r1), 1, 0)  // (4) carry = yLow(r3) ugt resLow
                BinOp.ADD(r2, r5)           // (5) resHigh += carry
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteU128WrappingAdd(cfg, globals, memSummaries, false)
        println("After:\n$cfg")
        Assertions.assertFalse(countWrappingAddCalls(cfg) > 0)
    }

    @Test
    fun `with scalar analysis can be promoted`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r10[-24] = 3
                r3 = r10[-24]
                r1 = 0; r2 = 0; r4 = 5
                BinOp.ADD(r2, r1)
                r7 = r10[-24]
                r1 = r7
                BinOp.ADD(r1, r4)
                select(r5, CondOp.GT(r3, r1), 1, 0)
                BinOp.ADD(r2, r5)
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteU128WrappingAdd(cfg, globals, memSummaries, true)
        println("After:\n$cfg")
        Assertions.assertTrue(countWrappingAddCalls(cfg) > 0)
    }

    /**
     * Between (4) and (5), a non-pattern instruction reads the carry register (r5).
     * The matcher's `findFirstAfter` for (5) stops only on **writes** to the carry,
     * so a non-pattern READ of the carry slips through.
     *
     * If the pattern were promoted, the matcher removes (1)..(5) and leaves the
     * non-pattern `r6 = r5` in place. After removal, `r5` at the non-pattern position
     * holds its pre-pattern value (not the low-add carry that `(4)` would have
     * produced), so `r6` would differ between the original and lowered code. The
     * matcher must not promote this pattern.
     */
    @Test
    fun `non-pattern read of carry between (4) and (5) is not promoted`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 0; r2 = 0; r3 = 3; r4 = 5
                BinOp.ADD(r2, r1)                       // (1) resHigh += xHigh
                r1 = r3                                  // (2) resLow = yLow
                BinOp.ADD(r1, r4)                       // (3) resLow += xLow
                select(r5, CondOp.GT(r3, r1), 1, 0)    // (4) carry
                r6 = r5                                  // non-pattern: reads carry
                BinOp.ADD(r2, r5)                       // (5) resHigh += carry
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteU128WrappingAdd(cfg, globals, memSummaries)
        println("After:\n$cfg")
        Assertions.assertEquals(0, countWrappingAddCalls(cfg))
    }

    /**
     * Two independent patterns in the same block using disjoint register sets:
     * - Pattern 1: xLow=r4, xHigh=r1, yLow=r3, yHigh=r2, carry=r5
     * - Pattern 2: xLow=r9, xHigh=r6, yLow=r8, yHigh=r7, carry=r5
     * Both must be promoted, producing exactly two calls.
     */
    @Test
    fun `two independent patterns in same block are both promoted`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                // Pattern 1
                r1 = 0; r2 = 0; r3 = 3; r4 = 5
                BinOp.ADD(r2, r1)                 // (1) resHigh += xHigh
                r1 = r3                                       // (2) resLow = yLow
                BinOp.ADD(r1, r4)                 // (3) resLow += xLow
                select(r5, CondOp.GT(r3, r1), 1, 0)  // (4) carry
                BinOp.ADD(r2, r5)                 // (5) resHigh += carry
                // Pattern 2
                r6 = 0; r7 = 0; r8 = 4; r9 = 10
                BinOp.ADD(r7, r6)                 // (1)
                r6 = r8                                       // (2)
                BinOp.ADD(r6, r9)                 // (3)
                select(r5, CondOp.GT(r8, r6), 1, 0) // (4)
                BinOp.ADD(r7, r5)                 // (5)
                exit()
            }
        }
        println("Before:\n$cfg")
        promoteU128WrappingAdd(cfg, globals, memSummaries)
        println("After:\n$cfg")
        Assertions.assertEquals(2, countWrappingAddCalls(cfg))
    }

    // -------------------------------------------------------------------------
    // End-to-end correctness tests — promote + lower + TAC verify
    // -------------------------------------------------------------------------

    /** 5 + 3 = 8 (no carry): resLow=8, resHigh=0. **/
    @Test
    fun `5 plus 3 equals 8`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 0; r2 = 0; r3 = 3; r4 = 5
                BinOp.ADD(r2, r1)
                r1 = r3
                BinOp.ADD(r1, r4)
                select(r5, CondOp.GT(r3, r1), 1, 0)
                BinOp.ADD(r2, r5)
                assert(CondOp.EQ(r1, 8UL))
                assert(CondOp.EQ(r2, 0UL))
                exit()
            }
        }
        promoteU128WrappingAdd(cfg, globals, memSummaries)
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(1, countWrappingAddCalls(cfg))
        Assertions.assertEquals(true, verify(tacProg))
    }

    /** 5 + 3 = 8 with yLow reloaded from the stack between pattern instructions. **/
    @Test
    fun `5 plus 3 equals 8 with register clobbered by non-pattern instruction`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r10[-24] = 3  // yLow
                r10[-32] = 0  // yHigh
                r1 = 0        // xHigh
                r2 = r10[-32] // yHigh
                r3 = r10[-24] // yLow
                r4 = 5        // xLow
                BinOp.ADD(r2, r1)
                r1 = r3
                BinOp.ADD(r1, r4)
                select(r5, CondOp.GT(r3, r1), 1, 0)
                r3 = r10[-24]  // non-pattern: clobbers r3 (yLow)
                BinOp.ADD(r2, r5)
                assert(CondOp.EQ(r1, 8UL))
                assert(CondOp.EQ(r2, 0UL))
                exit()
            }
        }
        promoteU128WrappingAdd(cfg, globals, memSummaries, true)
        println("After:\n$cfg")
        Assertions.assertEquals(1, countWrappingAddCalls(cfg))
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }

    /** x + x = 2x (no carry in low half): both halves double. **/
    @Test
    fun `x plus x equals 2x`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 7; r2 = 7; r3 = 42; r4 = 42  // x = (xLow=42, xHigh=7) = y
                BinOp.ADD(r2, r1)
                r1 = r3
                BinOp.ADD(r1, r4)
                select(r5, CondOp.GT(r3, r1), 1, 0)
                BinOp.ADD(r2, r5)
                assert(CondOp.EQ(r1, 84UL))
                assert(CondOp.EQ(r2, 14UL))
                exit()
            }
        }
        promoteU128WrappingAdd(cfg, globals, memSummaries)
        Assertions.assertEquals(1, countWrappingAddCalls(cfg))
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }

    /**
     * UINT64_MAX + 1 produces a carry: resLow=0, resHigh=1.
     *
     * The mask trick is used to avoid the prover sign-extending the immediate -1 to 256 bits:
     * `CVT_mask_64(-1)` forces r0 = 0xFFFF_FFFF_FFFF_FFFF as a 64-bit value.
     */
    @Test
    fun `low half overflow produces carry`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 0; r2 = 0; r3 = 1; r4 = -1  // xHigh=0, yHigh=0, yLow=1, xLow=UINT64_MAX
                BinOp.ADD(r2, r1)
                r1 = r3
                BinOp.ADD(r1, r4)
                select(r5, CondOp.GT(r3, r1), 1, 0)
                BinOp.ADD(r2, r5)
                // r1 = resLow = 0, r2 = resHigh = 1
                assert(CondOp.EQ(r1, 0UL))
                assert(CondOp.EQ(r2, 1UL))
                exit()
            }
        }
        promoteU128WrappingAdd(cfg, globals, memSummaries)
        Assertions.assertEquals(1, countWrappingAddCalls(cfg))
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }
}
