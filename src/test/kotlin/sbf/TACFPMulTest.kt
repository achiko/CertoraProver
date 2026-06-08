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

import config.*
import sbf.cfg.*
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*

/** Tests for [sbf.tac.SummarizeFPCompilerRt.summarizeMuldf3] via the `__muldf3` compiler runtime call. **/
class TACFPMulTest {

    companion object {
        // IEEE 754 f64 bit patterns
        private val f64NaN    = 0x7FF8_0000_0000_0000UL   // some NaN
        private val f64PlusInf = 0x7FF0_0000_0000_0000UL  // +∞
        private val f64PlusZero = 0UL                     // +0
        private val f64One    = 0x3FF0_0000_0000_0000UL   // 1.0
        private val f64Two    = 0x4000_0000_0000_0000UL   // 2.0
        private val f64Three  = 0x4008_0000_0000_0000UL   // 3.0
        private val f64Six    = 0x4018_0000_0000_0000UL   // 6.0
        // smallest positive subnormal = 2^-1074 (bit pattern = 1)
        private val f64MinSubnormal = 1UL
    }

    /** NaN × normal → NaN (case 1: isf64NaN(arg1)) **/
    @Test
    fun nanTimesNormal() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, false).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = f64NaN
                    r2 = f64One
                    "__muldf3"()
                    r6 = r0
                    r1 = r6
                    r2 = r6
                    "__unorddf2"()
                    assert(CondOp.EQ(r0, 1UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    /** normal × NaN → NaN (case 1: isf64NaN(arg2)) **/
    @Test
    fun normalTimesNan() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, false).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = f64One
                    r2 = f64NaN
                    "__muldf3"()
                    r6 = r0
                    r1 = r6
                    r2 = r6
                    "__unorddf2"()
                    assert(CondOp.EQ(r0, 1UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    /** +∞ × +0 → NaN (case 2: isf64Inf(arg1) and isf64Zero(arg2)) **/
    @Test
    fun infTimesZero() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, false).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = f64PlusInf
                    r2 = f64PlusZero
                    "__muldf3"()
                    r6 = r0
                    r1 = r6
                    r2 = r6
                    "__unorddf2"()
                    assert(CondOp.EQ(r0, 1UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    /** +0 × +∞ → NaN (case 2: isf64Zero(arg1) and isf64Inf(arg2)) **/
    @Test
    fun zeroTimesInf() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, false).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = f64PlusZero
                    r2 = f64PlusInf
                    "__muldf3"()
                    r6 = r0
                    r1 = r6
                    r2 = r6
                    "__unorddf2"()
                    assert(CondOp.EQ(r0, 1UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    /** +0 × +0 → +0 (case 4: isf64Zero(arg1) returns arg1) **/
    @Test
    fun zeroTimesZero() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, false).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = f64PlusZero
                    r2 = f64PlusZero
                    "__muldf3"()
                    assert(CondOp.EQ(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    /** +0 × normal → +0 (case 4: isf64Zero(arg1) returns arg1) **/
    @Test
    fun zeroTimesNormal() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, false).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = f64PlusZero
                    r2 = f64Three
                    "__muldf3"()
                    assert(CondOp.EQ(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    /** normal × +0 → +0 (case 5: isf64Zero(arg2) returns arg2) **/
    @Test
    fun normalTimesZero() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, false).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = f64Three
                    r2 = f64PlusZero
                    "__muldf3"()
                    assert(CondOp.EQ(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    /** 2.0 × 3.0 → 6.0 (case 6: isTwo(arg1) → multipleByTwo(arg2)) **/
    @Test
    fun twoTimesNormal() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, false).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = f64Two
                    r2 = f64Three
                    "__muldf3"()
                    assert(CondOp.EQ(r0, f64Six))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    /** 3.0 × 2.0 → 6.0 (case 7: isTwo(arg2) → multipleByTwo(arg1)) **/
    @Test
    fun normalTimesTwo() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, false).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = f64Three
                    r2 = f64Two
                    "__muldf3"()
                    assert(CondOp.EQ(r0, f64Six))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    /** 2.0 × subnormal → non-NaN (default case: subnormal is excluded from the isTwo(arg1) path) **/
    @Test
    fun twoTimesSubnormal() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, false).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = f64Two
                    r2 = f64MinSubnormal
                    "__muldf3"()
                    r6 = r0
                    r1 = r6
                    r2 = r6
                    "__unorddf2"()
                    assert(CondOp.EQ(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    /** subnormal × 2.0 → non-NaN (default case: subnormal is excluded from the isTwo(arg2) path) **/
    @Test
    fun subnormalTimesTwo() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, false).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = f64MinSubnormal
                    r2 = f64Two
                    "__muldf3"()
                    r6 = r0
                    r1 = r6
                    r2 = r6
                    "__unorddf2"()
                    assert(CondOp.EQ(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    /** normal × normal → non-NaN (default case: nonNaNV) **/
    @Test
    fun normalTimesNormal() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, false).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = f64One
                    r2 = f64Three
                    "__muldf3"()
                    r6 = r0
                    r1 = r6
                    r2 = r6
                    "__unorddf2"()
                    assert(CondOp.EQ(r0, 0UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @Test
    fun `7f64 x 9f64 = 63f64`() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, true).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    r1 = 7UL
                    "__floatundidf"()
                    r6 = r0              // r6 = f64(7)
                    r1 = 9UL
                    "__floatundidf"()
                    r7 = r0              // r7 = f64(9)
                    r1 = r6
                    r2 = r7
                    "__muldf3"()
                    r8 = r0              // r8 = f64(63)
                    r1 = r8
                    "__fixunsdfdi"()
                    assert(CondOp.EQ(r0, 63UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @Test
    fun `7f64 x n where n is between 0 and 100`() {
        ConfigScope(SolanaConfig.UseTACFPDualEncoding, true).use {
            val cfg = SbfTestDSL.makeCFG("test") {
                bb(0) {
                    "CVT_nondet_u64"()
                    r6 = r0              // r6 = nondet u64
                    assume(CondOp.LE(r6, 100UL))
                    r1 = r6
                    "__floatundidf"()
                    r7 = r0              // r7 = f64(nondet)
                    r1 = 7UL
                    "__floatundidf"()
                    r8 = r0              // r8 = f64(7)
                    r1 = r7
                    r2 = r8
                    "__muldf3"()
                    r9 = r0              // r9 = f64(7 * nondet)
                    r1 = r9
                    "__fixunsdfdi"()
                    assert(CondOp.LE(r0, 700UL))
                    exit()
                }
            }
            val tacProg = toTAC(cfg)
            Assertions.assertEquals(true, verify(tacProg))
        }
    }
}
