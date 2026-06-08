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
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import sbf.analysis.runGlobalInferenceAnalysis
import sbf.callgraph.CVTCalltrace
import sbf.callgraph.MutableSbfCallGraph
import sbf.cfg.*
import sbf.disassembler.*
import sbf.domains.MemorySummaries
import sbf.testing.SbfTestDSL

/** Address of the global string variable used as the sticky tag in all tests. */
private const val TAG_ADDR = 976432L
private const val TAG_STR  = "my_tag"
private val TAG_LEN = TAG_STR.length + 1   // including null terminator

private val memSummaries = MemorySummaries()

private val stickyTagFn   = CVTCalltrace.STICKY_TAG.function.name
private val printPubkeyFn = CVTCalltrace.PRINT_PUBKEY.function.name

/**
 * ELF view that exposes [TAG_ADDR] as a read-only global string containing [TAG_STR].
 * All other addresses are not globals.
 */
private object StickyTagElfFileView : IElfFileView {
    override fun isLittleEndian() = true
    override fun sbpfVersion() = SbpfVersion.SBF
    override fun useDynamicFrames() = false
    override fun isGlobalVariable(address: ElfAddress) = (address == TAG_ADDR)
    override fun isReadOnlyGlobalVariable(address: ElfAddress) = (address == TAG_ADDR)
    override fun getAsConstantString(address: ElfAddress, size: Long) =
        if (address == TAG_ADDR) TAG_STR else ""
    override fun getAsConstantNum(address: ElfAddress, size: Long): Long? = null
}

private fun runGIAAndResolveStickyTags(
    cfg: MutableSbfCFG,
    elf: IElfFileView = StickyTagElfFileView,
    opts: StickyTagOpts = StickyTagOpts()
): MutableSbfCFG {
    println("Before\n$cfg")
    val prog = MutableSbfCallGraph(listOf(cfg), setOf(cfg.getName()), GlobalVariables(elf))
    val newProg = ConfigScope(SolanaConfig.AggressiveGlobalDetection, true).use {
        runGlobalInferenceAnalysis(prog, memSummaries)
    }
    val mutCFG = newProg.getCallGraphRootSingleOrFail().clone(cfg.getName())
    resolveStickyTagCalls(mutCFG, newProg.getGlobals(), memSummaries, opts)
    println("After\n$mutCFG")
    return mutCFG
}

/** Returns true iff any block in [cfg] contains a call to [CVTCalltrace.STICKY_TAG]. */
private fun hasStickyTagCall(cfg: SbfCFG) =
    cfg.getBlocks().values.any { bb ->
        bb.getInstructions().any { it is SbfInstruction.Call && it.name == stickyTagFn }
    }

/** Returns the [SbfMeta.STICKY_TAG] metadata value on [inst], or null if absent. */
private fun getStickyTagMeta(inst: SbfInstruction): String? =
    inst.metaData.getVal(SbfMeta.STICKY_TAG)

class ResolveStickyTagCallsTest {

    // -----------------------------------------------------------------------
    // PRINT_PUBKEY tests
    // -----------------------------------------------------------------------

    @Test
    fun `sticky tag before print_pubkey is removed and tag is attached`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = TAG_ADDR
                r2 = TAG_LEN
                stickyTagFn()
                r1 = 0
                r2 = 0
                r3 = 0
                r4 = 0
                printPubkeyFn()
                exit()
            }
        }

        val result = runGIAAndResolveStickyTags(cfg)

        Assertions.assertFalse(hasStickyTagCall(result), "STICKY_TAG call should be removed")
        val printPubkeyInst = result.getBlocks().values.flatMap { it.getInstructions() }
            .filterIsInstance<SbfInstruction.Call>()
            .first { it.name == printPubkeyFn }
        Assertions.assertEquals(TAG_STR, getStickyTagMeta(printPubkeyInst),
            "PRINT_PUBKEY should carry the sticky tag")
    }

    @Test
    fun `second print_pubkey after first does not get the tag`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = TAG_ADDR
                r2 = TAG_LEN
                stickyTagFn()
                r1 = 0
                r2 = 0
                r3 = 0
                r4 = 0
                printPubkeyFn()   // first consumer — gets the tag
                printPubkeyFn()   // second consumer — no tag left
                exit()
            }
        }

        val result = runGIAAndResolveStickyTags(cfg)

        val calls = result.getBlocks().values.flatMap { it.getInstructions() }
            .filterIsInstance<SbfInstruction.Call>()
            .filter { it.name == printPubkeyFn }
        Assertions.assertEquals(2, calls.size)
        Assertions.assertEquals(TAG_STR, getStickyTagMeta(calls[0]), "first PRINT_PUBKEY should have the tag")
        Assertions.assertNull(getStickyTagMeta(calls[1]), "second PRINT_PUBKEY should have no tag")
    }

    // -----------------------------------------------------------------------
    // Assert / Assume tests
    // -----------------------------------------------------------------------

    @Test
    fun `sticky tag before assert is consumed`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = TAG_ADDR
                r2 = TAG_LEN
                stickyTagFn()
                assert(CondOp.NE(r0, 0))
                exit()
            }
        }

        val result = runGIAAndResolveStickyTags(cfg)

        Assertions.assertFalse(hasStickyTagCall(result))
        val assertInst = result.getBlocks().values.flatMap { it.getInstructions() }
            .filterIsInstance<SbfInstruction.Assert>()
            .first()
        Assertions.assertEquals(TAG_STR, getStickyTagMeta(assertInst),
            "Assert should carry the sticky tag")
    }

    @Test
    fun `sticky tag before assume is consumed`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = TAG_ADDR
                r2 = TAG_LEN
                stickyTagFn()
                assume(CondOp.NE(r0, 0))
                exit()
            }
        }

        val result = runGIAAndResolveStickyTags(cfg)

        Assertions.assertFalse(hasStickyTagCall(result))
        val assumeInst = result.getBlocks().values.flatMap { it.getInstructions() }
            .filterIsInstance<SbfInstruction.Assume>()
            .first()
        Assertions.assertEquals(TAG_STR, getStickyTagMeta(assumeInst),
            "Assume should carry the sticky tag")
    }

    @Test
    fun `assume and assert do not consume tag when option is disabled`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = TAG_ADDR
                r2 = TAG_LEN
                stickyTagFn()
                assert(CondOp.NE(r0, 0))
                exit()
            }
        }

        val result = runGIAAndResolveStickyTags(cfg,
            opts = StickyTagOpts(assumeAndAssertConsumeStickyTag = false))

        val assertInst = result.getBlocks().values.flatMap { it.getInstructions() }
            .filterIsInstance<SbfInstruction.Assert>()
            .first()
        Assertions.assertNull(getStickyTagMeta(assertInst),
            "Assert should not carry the tag when the option is disabled")
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Test
    fun `no sticky tag call leaves cfg unchanged`() {
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = 0
                r2 = 0
                r3 = 0
                r4 = 0
                printPubkeyFn()
                exit()
            }
        }

        // No STICKY_TAG call — resolveStickyTagCalls is a no-op; no need for GIA
        resolveStickyTagCalls(cfg, GlobalVariables(DefaultElfFileView), memSummaries)

        val printPubkeyInst = cfg.getBlocks().values.flatMap { it.getInstructions() }
            .filterIsInstance<SbfInstruction.Call>()
            .first { it.name == printPubkeyFn }
        Assertions.assertNull(getStickyTagMeta(printPubkeyInst),
            "PRINT_PUBKEY should have no tag when there was no STICKY_TAG call")
    }

    @Test
    fun `sticky tag is removed even when R1 cannot be resolved`() {
        // Use DefaultElfFileView: isGlobalVariable returns false for TAG_ADDR, so GIA will not
        // annotate mov r1, TAG_ADDR with SET_GLOBAL, and the non-scalar path cannot resolve R1.
        val cfg = SbfTestDSL.makeCFG("entrypoint") {
            bb(0) {
                r1 = TAG_ADDR
                r2 = TAG_LEN
                stickyTagFn()
                r1 = 0
                r2 = 0
                r3 = 0
                r4 = 0
                printPubkeyFn()
                exit()
            }
        }

        val result = runGIAAndResolveStickyTags(cfg, elf = DefaultElfFileView)

        Assertions.assertFalse(hasStickyTagCall(result), "STICKY_TAG call should always be removed")
        val printPubkeyInst = result.getBlocks().values.flatMap { it.getInstructions() }
            .filterIsInstance<SbfInstruction.Call>()
            .first { it.name == printPubkeyFn }
        Assertions.assertNull(getStickyTagMeta(printPubkeyInst),
            "PRINT_PUBKEY should have no tag when R1 could not be resolved")
    }
}
