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

package sbf.cfg

import sbf.callgraph.CVTCore

/** Address range [start, end) for a Solana account allocated by NONDET_SOLANA_ACCOUNT_SPACE **/
data class SolanaAccountRange(val index: Int, val start: ULong, val end: ULong)

/**
 * Scan [cfg] for all calls to NONDET_SOLANA_ACCOUNT_SPACE and compute the deterministic
 * address range for each allocated account.
 *
 * The allocator in [sbf.tac.TACFixedSizeBlockAllocator] assigns accounts in call order,
 * starting at [SBF_INPUT_START] with stride [SOLANA_ACCOUNT_SIZE].  We reproduce that
 * logic here so that the ranges are known before TAC generation begins.
 */
fun collectSolanaAccountRanges(cfg: SbfCFG): List<SolanaAccountRange> {
    var count = 0
    for (block in cfg.getBlocks().values) {
        for (inst in block.getInstructions()) {
            if (inst is SbfInstruction.Call && CVTCore.from(inst.name) == CVTCore.NONDET_SOLANA_ACCOUNT_SPACE) {
                count++
            }
        }
    }
    return List(count) { i ->
        val start = SBF_INPUT_START.toULong() + i.toULong() * SOLANA_ACCOUNT_SIZE.toULong()
        SolanaAccountRange(i, start, start + SOLANA_ACCOUNT_SIZE.toULong())
    }
}
