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

import config.ConfigScope
import sbf.callgraph.MutableSbfCallGraph
import sbf.cfg.*
import sbf.disassembler.*
import sbf.domains.MemorySummaries
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*

class TACRentTest {

    /**
     * ELF view that places the RENT sysvar public key at address [RENT_SYSVAR_ADDR].
     *
     * The 32-byte key is represented as four little-endian u64 words:
     *   [5862609301215225606, 9219231539345853473, 4971307250928769624, 2329533411]
     * which base58-encode to "SysvarRent111111111111111111111111111111111".
     */
    private object RentSysvarElfView : IElfFileView {
        override fun isLittleEndian() = true
        override fun sbpfVersion() = SbpfVersion.SBF
        override fun useDynamicFrames() = false
        override fun isGlobalVariable(address: ElfAddress) = (address == RENT_SYSVAR_ADDR)
        override fun isReadOnlyGlobalVariable(address: ElfAddress) = (address == RENT_SYSVAR_ADDR)

        override fun getAsConstantString(address: ElfAddress, size: Long): String {
            if (address != RENT_SYSVAR_ADDR) {
                return ""
            }
            val words = longArrayOf(5862609301215225606L, 9219231539345853473L, 4971307250928769624L, 2329533411L)
            return words.flatMap { toBytes(it).toList() }.map { (it.toInt() and 0xFF).toChar() }.joinToString("")
        }

        override fun getAsConstantNum(address: ElfAddress, size: Long): Long? = null
    }

    companion object {
        private const val RENT_SYSVAR_ADDR = 788956L
    }

    /**
     * ```
     * r1 = r10 - 200
     * sol_get_rent_sysvar()   // writes lamports(u64), exemption_threshold(f64), burn_percent(u8) into r1
     * r2 = *(u8*)(r1+16)      // burn_percent
     * assert(r2 <= 100)
     * ```
     * The Rent model adds `inRange(burn_percent, 0, 100)`, so the assertion must hold.
     */
    @Test
    fun test1() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = r10
                BinOp.SUB(r1, 200)
                "sol_get_rent_sysvar"()
                r2 = r1[16, 1]
                assert(CondOp.LE(r2, 100))
                exit()
            }
        }
        println("$cfg")
        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }


    /**
     * Similar to [test1] but uses `sol_get_sysvar` instead of `sol_get_rent_sysvar`.
     *
     * ```
     * r1 = RENT_SYSVAR_ADDR   // global pointer to the RENT sysvar public key
     * r2 = r10 - 200           // output buffer
     * sol_get_sysvar()         // resolves sysvar ID from r1, writes into r2
     * r3 = *(u8*)(r2+16)       // burn_percent
     * assert(r3 <= 100)
     * ```
     *
     * Global inference (with [SolanaConfig.AggressiveGlobalDetection]) is needed to
     * recognise the immediate value [RENT_SYSVAR_ADDR] as a global pointer so that
     * [sbf.callgraph.SolGetSysvar.getSysvarId] can identify the sysvar as RENT.
     */
    @Test
    fun test2() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = RENT_SYSVAR_ADDR
                r2 = r10
                BinOp.SUB(r2, 200)
                r3 = 0
                r4 = 17
                "sol_get_sysvar"()
                r3 = r2[16, 1]
                assert(CondOp.LE(r3, 100))
                exit()
            }
        }
        println("$cfg")
        val globals = GlobalVariables(RentSysvarElfView)
        val memSummaries = MemorySummaries()
        val prog = MutableSbfCallGraph(mutableListOf(cfg), setOf(cfg.getName()), globals)
        Assertions.assertEquals(true, verify(toTACWithGlobalInference(prog, memSummaries)))
    }

    /**
     * Calls `sol_get_rent_sysvar` twice and asserts both calls return the same value in r0.
     *
     * ```
     * r1 = r10 - 200
     * sol_get_rent_sysvar()   // first call; r0 = return code
     * r6 = r0                  // save r0
     * r1 = r10 - 200
     * sol_get_rent_sysvar()   // second call; r0 must equal r6
     * assert(r0 == r6)
     * ```
     */
    @Test
    fun `call get_rent_sysvar twice should return same result`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = r10
                BinOp.SUB(r1, 200)
                "sol_get_rent_sysvar"()
                r6 = r0
                r1 = r10
                BinOp.SUB(r1, 200)
                "sol_get_rent_sysvar"()
                assert(CondOp.EQ(r0, r6))
                exit()
            }
        }
        println("$cfg")
        ConfigScope(SolanaConfig.TACRentDeterministicReturn, true).use {
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(true, verify(tacProg))
        }
    }

    @Test
    fun `call get_rent_sysvar twice can return different result`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = r10
                BinOp.SUB(r1, 200)
                "sol_get_rent_sysvar"()
                r6 = r0
                r1 = r10
                BinOp.SUB(r1, 200)
                "sol_get_rent_sysvar"()
                assert(CondOp.EQ(r0, r6))
                exit()
            }
        }
        println("$cfg")
        ConfigScope(SolanaConfig.TACRentDeterministicReturn, false).use {
            val tacProg = toTAC(cfg)
            println(dumpTAC(tacProg))
            Assertions.assertEquals(false, verify(tacProg))
        }
    }
}
