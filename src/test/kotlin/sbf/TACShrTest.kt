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
import config.ConfigScope.Companion.invoke
import sbf.cfg.*
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*
import org.junit.jupiter.params.*
import org.junit.jupiter.params.provider.*

class TACShrTest {


    /**
     * This code is generated from
     *  ```
     *  let x:i64 = nondet()
     *  cvt_assume(x>=0)
     *  cvt_assume(x< i64::max())
     *  ```
     *  The test is a non-vacuity test (i.e., assert(false) is reachable)
     *
     *  When [SolanaConfig.TACSoundSignedMath] is false this test is vacuous because after ARSH `r1` should be
     *  either all zeros or all ones (`-1`) but it cannot be `1`
     */
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun test1(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            // The "unsound" mode does not sign-extend the shifted value
            val bitsToTest = if (sound) { -1L } else { 1L }
            /*
             * r1 = r1 xor -1 is a trick to do `r1 = ~r1`
             * r1 = r1 ashr 63 gets the `MSB(r1)`
             */
            val cfg = SbfTestDSL.makeCFG("entrypoint") {
                bb(0) {
                    r2 = r1
                    BinOp.XOR(r1, -1)
                    BinOp.ARSH(r1, 63)
                    assume(CondOp.EQ(r1, bitsToTest)) // assume MSB(r2) == 0 (i.e., positive number)
                    assume(CondOp.LT(r2, Long.MAX_VALUE))  // assume r2 < MAX
                    r3 = 1
                    assert(CondOp.EQ(r3, 0)) // assert(false)
                    exit()
                }
            }
            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(false, verify(tacProg))
        }
    }

    /** Same test as test1 using the lazy TAC encoding but with option [SolanaConfig.UseTACSignedMath] enabled **/
    fun test2() {
        ConfigScope(SolanaConfig.TACSoundSignedMath, false).use {
            ConfigScope(SolanaConfig.UseTACSignedMath, true).use {
                val cfg = SbfTestDSL.makeCFG("entrypoint") {
                    bb(0) {
                        r2 = r1
                        BinOp.XOR(r1, -1)  // r1 xor -1 = ~r1
                        BinOp.ARSH(r1, 63) // if all zeros then MSB(r1)=0, if all ones then MSB(r1)=1
                        assume(CondOp.EQ(r1, -1)) // if ~r1 is all ones then MSB(r1) == 0 (non-negative number)
                        assume(CondOp.LT(r2, Long.MAX_VALUE))  // assume r2 < MAX
                        r3 = 1
                        assert(CondOp.EQ(r3, 0)) // assert(false)
                        exit()
                    }
                }
                println("$cfg")
                val tacProg = toTAC(cfg)
                println(dumpTAC(tacProg))
                Assertions.assertEquals(false, verify(tacProg))
            }
        }
    }
}
