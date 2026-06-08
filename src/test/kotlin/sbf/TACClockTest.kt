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
import sbf.callgraph.MutableSbfCallGraph
import sbf.cfg.*
import sbf.disassembler.*
import sbf.domains.MemorySummaries
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*
import org.junit.jupiter.params.*
import org.junit.jupiter.params.provider.*

class TACClockTest {

    /**
     * ELF view that places the CLOCK sysvar public key at address [CLOCK_SYSVAR_ADDR].
     *
     * The 32-byte key is represented as four little-endian Long words:
     *   [-3930297668494579962, -5305770971630447064, 6660062555789614731, 559633779]
     * which base58-encode to "SysvarC1ock11111111111111111111111111111111".
     */
    private object ClockSysvarElfView : IElfFileView {
        override fun isLittleEndian() = true
        override fun sbpfVersion() = SbpfVersion.SBF
        override fun useDynamicFrames() = false
        override fun isGlobalVariable(address: ElfAddress) = (address == CLOCK_SYSVAR_ADDR)
        override fun isReadOnlyGlobalVariable(address: ElfAddress) = (address == CLOCK_SYSVAR_ADDR)

        override fun getAsConstantString(address: ElfAddress, size: Long): String {
            if (address != CLOCK_SYSVAR_ADDR) {
                return ""
            }
            val words = longArrayOf(-3930297668494579962, -5305770971630447064, 6660062555789614731, 559633779)
            return words.flatMap { toBytes(it).toList() }.map { (it.toInt() and 0xFF).toChar() }.joinToString("")
        }

        override fun getAsConstantNum(address: ElfAddress, size: Long): Long? = null
    }

    companion object {
        private const val CLOCK_SYSVAR_ADDR = 788956L
    }

    /**
     * ```
     * r1 = r10 - 200
     * *r1 = 0, *(r1+8) = 1, *(r1+16) = 2, *(r1+24) = 3, *(r1+32) = 4
     * sol_set_clock_sysvar()
     * memset(r1, 0, 40)
     * r1 = r10 - 400
     * sol_get_clock_sysvar()
     * assert(*(r1+16) == 2)
     * ```
     */
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun test1(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            val cfg = SbfTestDSL.makeCFG("entrypoint") {
                bb(0) {
                    r1 = r10
                    BinOp.SUB(r1, 200)
                    r1[0] = 0
                    r1[8] = 1
                    r1[16] = 2
                    r1[24] = 3
                    r1[32] = 4
                    "sol_set_clock_sysvar"()
                    r2 = 0
                    r3 = 40
                    "sol_memset_" ()
                    r1 = r10
                    BinOp.SUB(r1, 400)
                    "sol_get_clock_sysvar"()
                    r2 = r1[16]
                    assert(CondOp.EQ(r2, 2))
                    exit()
                }
            }
            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }


    /** Similar to test1 but we pass heap to sol_set_clock_sysvar instead of stack.**/
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun test2(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            val cfg = SbfTestDSL.makeCFG("entrypoint") {
                bb(0) {
                    "__rust_alloc"()
                    r1 = r0
                    r1[0] = 0
                    r1[8] = 1
                    r1[16] = 2
                    r1[24] = 3
                    r1[32] = 4
                    "sol_set_clock_sysvar"()
                    r2 = 0
                    r3 = 40
                    "sol_memset_" ()
                    r1 = r10
                    BinOp.SUB(r1, 400)
                    "sol_get_clock_sysvar"()
                    r2 = r1[16]
                    assert(CondOp.EQ(r2, 2))
                    exit()
                }
            }
            println("$cfg")
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }


    /**
     * Similar to [test1] but uses `sol_get_sysvar` instead of `sol_get_clock_sysvar`.
     *
     * ```
     * r1 = r10 - 200
     * *r1 = 0, *(r1+8) = 1, *(r1+16) = 2, *(r1+24) = 3, *(r1+32) = 4
     * sol_set_clock_sysvar()
     * r1 = CLOCK_SYSVAR_ADDR   // global pointer to the CLOCK sysvar public key
     * r2 = r10 - 400           // output buffer
     * r3 = 0
     * r4 = 40
     * sol_get_sysvar()
     * assert(*(r2+16) == 2)
     * ```
     *
     * Global inference (with [SolanaConfig.AggressiveGlobalDetection]) is needed to
     * recognize the immediate value [CLOCK_SYSVAR_ADDR] as a global pointer so that
     * [sbf.callgraph.SolGetSysvar.getSysvarId] can identify the sysvar as CLOCK.
     */
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun test3(sound: Boolean) {
        ConfigScope(SolanaConfig.TACSoundSignedMath, sound).use {
            val cfg = SbfTestDSL.makeCFG("entrypoint") {
                bb(0) {
                    r1 = r10
                    BinOp.SUB(r1, 200)
                    r1[0] = 0
                    r1[8] = 1
                    r1[16] = 2
                    r1[24] = 3
                    r1[32] = 4
                    "sol_set_clock_sysvar"()
                    r1 = CLOCK_SYSVAR_ADDR
                    r2 = r10
                    BinOp.SUB(r2, 400)
                    r3 = 0
                    r4 = 40
                    "sol_get_sysvar"()
                    r3 = r2[16]
                    assert(CondOp.EQ(r3, 2))
                    exit()
                }
            }
            println("$cfg")
            val globals = GlobalVariables(ClockSysvarElfView)
            val memSummaries = MemorySummaries()
            val prog = MutableSbfCallGraph(mutableListOf(cfg), setOf(cfg.getName()), globals)
            Assertions.assertEquals(true, verify(toTACWithGlobalInference(prog, memSummaries)))
        }
    }
    /**
     * Calls `sol_get_clock_sysvar` twice and asserts both calls return the same value in r0.
     *
     * ```
     * r1 = r10 - 200
     * sol_get_clock_sysvar()   // first call; r0 = return code
     * r6 = r0                  // save r0
     * r1 = r10 - 200
     * sol_get_clock_sysvar()   // second call; r0 must equal r6
     * assert(r0 == r6)
     * ```
     */
    @Test
    fun `call get_clock_sysvar twice should return same result`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = r10
                BinOp.SUB(r1, 200)
                "sol_get_clock_sysvar"()
                r6 = r0
                r1 = r10
                BinOp.SUB(r1, 200)
                "sol_get_clock_sysvar"()
                assert(CondOp.EQ(r0, r6))
                exit()
            }
        }
        println("$cfg")
        ConfigScope(SolanaConfig.TACClockDeterministicReturn, true).use {
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @Test
    fun `call get_clock_sysvar twice can return different result`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = r10
                BinOp.SUB(r1, 200)
                "sol_get_clock_sysvar"()
                r6 = r0
                r1 = r10
                BinOp.SUB(r1, 200)
                "sol_get_clock_sysvar"()
                assert(CondOp.EQ(r0, r6))
                exit()
            }
        }
        println("$cfg")
        ConfigScope(SolanaConfig.TACClockDeterministicReturn, false).use {
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(false, verify(tacProg))
        }
    }

}
