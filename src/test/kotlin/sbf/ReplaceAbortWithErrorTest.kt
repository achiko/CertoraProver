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

import config.ConfigScope
import sbf.cfg.*
import sbf.testing.SbfTestDSL
import org.junit.jupiter.api.*

class ReplaceAbortWithErrorTest {

    private fun getAsserts(cfg: SbfCFG): List<SbfInstruction.Assert> =
        cfg.getBlocks().values.flatMap { block ->
            block.getInstructions().filterIsInstance<SbfInstruction.Assert>()
        }

    private fun hasCallTo(cfg: SbfCFG, name: String): Boolean =
        cfg.getBlocks().values.any { block ->
            block.getInstructions().any { inst -> inst is SbfInstruction.Call && inst.name == name }
        }

    @Test
    fun `call to abort function should be replaced with assert(false)`() {
        val abortFn = "core::panicking::panic"
        val cfg = SbfTestDSL.makeCFG("test", normalize = false) {
            bb(0) {
                r1 = 42
                abortFn()
                exit()
            }
        }

        println("Before: $cfg")
        ConfigScope(SolanaConfig.AssertOnPanic, true).use {
            replaceAbortWithError(cfg)
        }
        println("After: $cfg")

        val asserts = getAsserts(cfg)
        Assertions.assertTrue(asserts.isNotEmpty()) { "Expected assert(false) to be inserted" }
        Assertions.assertFalse(hasCallTo(cfg, abortFn)) { "Expected call to $abortFn to be removed" }

        val comment = asserts.first().metaData.getVal(SbfMeta.COMMENT)
        Assertions.assertEquals(abortFn, comment) { "Expected comment to be the abort function name" }
    }

    @Test
    fun `a call to a non-abort function should not be replaced`() {
        val normalFn = "some::normal::function"
        val cfg = SbfTestDSL.makeCFG("test", normalize = false) {
            bb(0) {
                normalFn()
                exit()
            }
        }

        println("Before: $cfg")
        ConfigScope(SolanaConfig.AssertOnPanic, true).use {
            replaceAbortWithError(cfg)
        }
        println("After: $cfg")

        Assertions.assertTrue(getAsserts(cfg).isEmpty()) { "Expected no assert(false) for non-abort function" }
        Assertions.assertTrue(hasCallTo(cfg, normalFn)) { "Expected call to $normalFn to remain" }
    }
}
