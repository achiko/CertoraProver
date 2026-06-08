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

import sbf.cfg.*
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*
import sbf.disassembler.GlobalVariables
import sbf.domains.MemorySummaries

private val globals = GlobalVariables(DefaultElfFileView)
private val memSummaries = MemorySummaries()

class TACResizeMaskedLoadsTest {

    /**
     * ```
     *   if (r1 == 0) {
     *       *(u32 *)(r10 - 56) = 0
     *   } else {
     *       *(u32 *)(r10 - 56) = 1
     *   }
     *   r2 = *(u64 *)(r10 - 56)
     *   r2 = r2 and 0x1
     *   assert(0<= r2 <= 1)
     * ```
     */
    @Test
    fun `write 0 or 1 as u32, load u64 masked to u32, assert value is 0 or 1`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                BinOp.ADD(r10, 4096)
                br(CondOp.EQ(r1, 0), 1, 2)
            }
            bb(1) {
                r10[-56, 4] = 0
                goto(3)
            }
            bb(2) {
                r10[-56, 4] = 1
                goto(3)
            }
            bb(3) {
                r2 = r10[-56]
                BinOp.AND(r2, 1)
                assert(CondOp.LE(r2, 1))
                assert(CondOp.GE(r2, 0))
                exit()
            }
        }

        println("Before resizeMaskedLoads\n$cfg")
        resizeMaskedLoads(cfg, globals, memSummaries)
        println("After resizeMaskedLoads\n$cfg")

        cfg.lowerBranchesIntoAssume()
        cfg.normalize()
        cfg.verify(true)

        val tacProg = toTAC(cfg)
        println(dumpTAC(tacProg))
        Assertions.assertEquals(true, verify(tacProg))
    }
}
