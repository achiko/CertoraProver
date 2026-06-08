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

package sbf

import config.*
import sbf.cfg.*
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*
import org.junit.jupiter.params.*
import org.junit.jupiter.params.provider.*

class TACDivTest {
    /** 64-bits unsigned division **/
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun test1(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            /**
            *   r2 := 10
            *   r3 := 2
            *   r2 := r2 /u r4
            *   assert(r2 == 5)
            */

            val cfg = SbfTestDSL.makeCFG("test1") {
                bb(0) {
                    r2 = 10UL
                    r4 = 2UL
                    BinOp.DIV(r2, r4)
                    assert(CondOp.EQ(r2, 5UL))
                    exit()
                }

            }

            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    /** 64-bits unsigned division **/
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun test2(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            /**
             *   r2 := -10
             *   r3 := -2
             *   r2 := r2 /u r4
             *   assert(r2 == 0)
             */
            val cfg = SbfTestDSL.makeCFG("test2") {
                bb(0) {
                    r2 = (-10L).toULong()
                    r4 = (-2L).toULong()
                    BinOp.DIV(r2, r4)
                    assert(CondOp.EQ(r2, 0UL))
                    exit()
                }

            }

            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    /** 128-bits unsigned division **/
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun test3(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            /**
            *   r1 := r10 - 104
            *   r2 := 10  // 1st operand, low 64
            *   r3 := 0   // 1st operand, high 64
            *   r4 := 2   // 1st operand, low 64
            *   r5 := 0   // 1st operand, high 64
            *   __udivti3 // 128bit unsigned integer division (0*2^64 + 10) /u (0*2^64 + 2) = 5
            *   r2 := *(u64 *) (r1 + 0) // result, low 64
            *   r3 := *(u64 *) (r1 + 8) // result, high 64
            *   assert(r2 == 5)
            *   assert(r3 == 0)
            */
            val cfg = SbfTestDSL.makeCFG("test3") {
                bb(0) {
                    r1 = r10
                    BinOp.SUB(r1, 104)
                    r2 = 10UL
                    r3 = 0U
                    r4 = 2UL
                    r5 = 0U
                    "__udivti3"()
                    r2 = r1[0]
                    r3 = r1[8]
                    assert(CondOp.EQ(r2, 5UL))
                    assert(CondOp.EQ(r3, 0UL))
                    exit()
                }

            }

            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    /** 128-bits unsigned division **/
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun test4(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            /**
            *   r1 := r10 - 104
            *   r2 := -10  // 1st operand, low 64
            *   r3 := 0   // 1st operand, high 64
            *   r4 := -2   // 1st operand, low 64
            *   r5 := 0   // 1st operand, high 64
            *   __udivti3 // 128bit unsigned integer division (0*2^64 - 10) /u (0*2^64 - 2) = -10 /u -2 = 0
            *   r2 := *(u64 *) (r1 + 0) // result, low 64
            *   r3 := *(u64 *) (r1 + 8) // result, high 64
            *   assert(r2 == 0)
            *   assert(r3 == 0)
            */
            val cfg = SbfTestDSL.makeCFG("test4") {
                bb(0) {
                    r1 = r10
                    BinOp.SUB(r1, 104)
                    r2 = (-10L).toULong()
                    r3 = 0U
                    r4 = (-2L).toULong()
                    r5 = 0U
                    "__udivti3"()
                    r2 = r1[0]
                    r3 = r1[8]
                    assert(CondOp.EQ(r2, 0UL))
                    assert(CondOp.EQ(r3, 0UL))
                    exit()
                }

            }

            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }
    /** 128-bits signed division **/
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun test5(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            /**
             *   r1 := r10 - 104
             *   r2 := -10  // 1st operand, low 64
             *   r3 := -1  // 1st operand, high 64
             *   r4 := -2   // 1st operand, low 64
             *   r5 := -1  // 1st operand, high 64
             *   __divti3 // 128bit signed integer division ((-1 << 64) | -10) /s (-1 << 64 | -2) = -10 /s -2 = 5
             *   r2 := *(u64 *) (r1 + 0) // result, low 64
             *   r3 := *(u64 *) (r1 + 8) // result, high 64
             *   assert(r2 == 5)
            *   assert(r3 == 0)
            */
            val cfg = SbfTestDSL.makeCFG("test5") {
                bb(0) {
                    r1 = r10
                    BinOp.SUB(r1, 104)
                    r2 = -10L
                    r3 = -1L
                    r4 = -2L
                    r5 = -1L
                    "__divti3"()
                    r2 = r1[0]
                    r3 = r1[8]
                    assert(CondOp.EQ(r2, 5UL))
                    // Signed division havoc the highest bits of the result
                    //assert(CondOp.EQ(r3, 0UL))
                    exit()
                }
            }

            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    /** 128-bits unsigned division **/
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun test6(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            /**
                *   r1 := r10 - 104
                *   r2 := 0  // 1st operand, low 64
                *   r3 := 2   // 1st operand, high 64
                *   r4 := 0   // 1st operand, low 64
                *   r5 := 1   // 1st operand, high 64
                *   __udivti3 // 128bit unsigned integer division  (2*2^64 + 0) /u (1*2^64+0) = 2
                *   r2 := *(u64 *) (r1 + 0) // result, low 64
                *   r3 := *(u64 *) (r1 + 8) // result, high 64
                *   assert(r2 == 2)
                *   assert(r3 == 0)
                */
            val cfg = SbfTestDSL.makeCFG("test6") {
                bb(0) {
                    r1 = r10
                    BinOp.SUB(r1, 104)
                    r2 = 0
                    r3 = 2UL
                    r4 = 0
                    r5 = 1U
                    "__udivti3"()
                    r2 = r1[0]
                    r3 = r1[8]
                    assert(CondOp.EQ(r2, 2UL))
                    assert(CondOp.EQ(r3, 0UL))
                    exit()
                }
            }

            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    /** 128-bits unsigned division **/
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun test7(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            /**
                *   r1 := r10 - 104
                *   r2 := 0  // 1st operand, low 64
                *   r3 := 4   // 1st operand, high 64
                *   r4 := 2   // 1st operand, low 64
                *   r5 := 0   // 1st operand, high 64
                *   __udivti3 // 128bit unsigned integer division  (4*2^64 + 0) /u (0*2^64+ 2) = 2*2^64
                *   r2 := *(u64 *) (r1 + 0) // result, low 64
                *   r3 := *(u64 *) (r1 + 8) // result, high 64
                *   assert(r2 == 0)
                *   assert(r3 == 2)
                */
            val cfg = SbfTestDSL.makeCFG("test7") {
                bb(0) {
                    r1 = r10
                    BinOp.SUB(r1, 104)
                    r2 = 0
                    r3 = 4UL
                    r4 = 2UL
                    r5 = 0
                    "__udivti3"()
                    r2 = r1[0]
                    r3 = r1[8]
                    assert(CondOp.EQ(r2, 0UL))
                    assert(CondOp.EQ(r3, 2UL))
                    exit()
                }
            }

            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    /** 128-bits unsigned division **/
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun test8(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            /**
                *   r1 := r10 - 104
                *   r2 := 10  // 1st operand, low 64
                *   r3 := 4   // 1st operand, high 64
                *   r4 := 5   // 1st operand, low 64
                *   r5 := 2   // 1st operand, high 64
                *   __udivti3 // 128bit unsigned integer division  (4*2^64 + 10) /u (2*2^64+ 5) = 2  (w/ rounding)
                *   r2 := *(u64 *) (r1 + 0) // result, low 64
                *   r3 := *(u64 *) (r1 + 8) // result, high 64
                *   assert(r2 == 2)
                *   assert(r3 == 0)
                */
            val cfg = SbfTestDSL.makeCFG("test8") {
                bb(0) {
                    r1 = r10
                    BinOp.SUB(r1, 104)
                    r2 = 10
                    r3 = 4
                    r4 = 5
                    r5 = 2
                    "__udivti3"()
                    r2 = r1[0]
                    r3 = r1[8]
                    assert(CondOp.EQ(r2, 2UL))
                    assert(CondOp.EQ(r3, 0UL))
                    exit()
                }
            }

            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }
}
