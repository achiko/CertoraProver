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
import vc.data.*
import net.jqwik.api.*
import net.jqwik.kotlin.api.*
import org.junit.jupiter.api.*

class TACMathOpTest {
    private fun check(cfg: SbfCFG) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, true).use {
            //println("$cfg")
            val tacProg = toTAC(cfg)
            //println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    private fun checkSigned(
        a: Long,
        b: Long,
        op64: BinOp,
        opNative: SbfTestDSL.BlockBuilderScope.() -> Unit
    ) {
        val cfg = SbfTestDSL.makeCFG("test1") {
            bb(0) {
                // Convert the two values to native ints, and apply the native int operation, masking the result back to
                // 64 bits
                r1 = a
                r2 = 64
                "CVT_nativeint_u64_sext"()
                r6 = r0
                r1 = b
                r2 = 64
                "CVT_nativeint_u64_sext"()
                r1 = r6
                r2 = r0
                opNative()
                r1 = r0
                r2 = 64
                "CVT_nativeint_u64_mask"()
                // native result is in r0

                // Apply the 64-bit operation
                r1 = a
                r2 = b
                op64(r1, r2)
                // 64-bit result is in r1

                assert(CondOp.EQ(r0, r1))
                exit()
            }
        }
        check(cfg)
    }

    private fun checkUnsigned(
        a: ULong,
        b: ULong,
        op64: BinOp,
        opNative: SbfTestDSL.BlockBuilderScope.() -> Unit
    ) {
        val cfg = SbfTestDSL.makeCFG("test1") {
            bb(0) {
                // Apply the native int operation, masking the result back to 64 bits
                r1 = a
                r2 = b
                opNative()
                r1 = r0
                r2 = 64
                "CVT_nativeint_u64_mask"()
                // native result is in r0

                // Apply the 64-bit operation
                r1 = a
                r2 = b
                op64(r1, r2)
                // 64-bit result is in r1

                assert(CondOp.EQ(r0, r1))
                exit()
            }
        }
        check(cfg)
    }

    @Property(tries = 64)
    fun `signed add`(@ForAll a: Long, @ForAll b: Long) = checkSigned(a, b, BinOp.ADD, { "CVT_nativeint_u64_add"() })

    @Property(tries = 64)
    fun `signed sub`(@ForAll a: Long, @ForAll b: Long) = checkSigned(a, b, BinOp.SUB, { "CVT_nativeint_u64_sub"() })

    @Property(tries = 64)
    fun `signed mul`(@ForAll a: Long, @ForAll b: Long) = checkSigned(a, b, BinOp.MUL, { "CVT_nativeint_u64_mul"() })

    // There is no signed division in Sbf
    // @Property(tries = 64)
    // fun `signed div`(@ForAll a: Long, @ForAll b: Long) = checkSigned(a, b, BinOp.SDIV, { "CVT_nativeint_u64_sdiv"() })

    @Property(tries = 64)
    fun `signed shift left`(@ForAll a: Long) =
        checkSigned(a, 2, BinOp.LSH) {
            r2 = 4
            "CVT_nativeint_u64_mul"()
        }

    @Property(tries = 64)
    fun `signed shift right`(@ForAll a: Long) =
        checkSigned(a, 2, BinOp.ARSH) {
            r2 = 4
            "CVT_nativeint_u64_div"()
        }

    @Property(tries = 64)
    fun `unsigned add`(@ForAll a: ULong, @ForAll b: ULong) = checkUnsigned(a, b, BinOp.ADD, { "CVT_nativeint_u64_add"() })

    @Property(tries = 64)
    fun `unsigned sub`(@ForAll a: ULong, @ForAll b: ULong) = checkUnsigned(a, b, BinOp.SUB, { "CVT_nativeint_u64_sub"() })

    @Property(tries = 64)
    fun `unsigned mul`(@ForAll a: ULong, @ForAll b: ULong) = checkUnsigned(a, b, BinOp.MUL, { "CVT_nativeint_u64_mul"() })

    @Property(tries = 64)
    fun `unsigned div`(@ForAll a: ULong, @ForAll b: ULong) = checkUnsigned(a, b, BinOp.DIV, { "CVT_nativeint_u64_div"() })

    @Property(tries = 64)
    fun `unsigned shift left`(@ForAll a: ULong) =
        checkUnsigned(a, 2u, BinOp.LSH) {
            r2 = 4
            "CVT_nativeint_u64_mul"()
        }

    @Property(tries = 64)
    fun `unsigned shift right`(@ForAll a: ULong) =
        checkUnsigned(a, 2u, BinOp.RSH) {
            r2 = 4
            "CVT_nativeint_u64_div"()
        }
}
