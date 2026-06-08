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

class TACBitwiseTest {

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun test1(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            /**
            * This code is generated from
            *  ```
            *  r1 = 18446744073709541616
            *  r2 = -10000
            *  r3 = r1 xor r2
            *  assert(r3 == 0)
            *  ```
            */
            val cfg = SbfTestDSL.makeCFG("test1") {
                bb(0) {
                    // 0xffffffffffffd8f0
                    r1 = 18446744073709541616UL
                    r2 = 10000UL
                    r3 = -1
                    // -10000 = 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffd8f0
                    BinOp.MUL(r2,r3)
                    BinOp.XOR(r1, r2)
                    assert(CondOp.EQ(r1, 0)) // r1 and r2 are equal
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
