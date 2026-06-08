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

package sbf.cfg

import sbf.SolanaConfig
import sbf.callgraph.SbfCallGraph
import sbf.domains.MemorySummaries
import sbf.disassembler.GlobalVariables
import datastructures.stdcollections.*

/**
 * Simple (local) CFG optimizations that help the pointer analysis.
 * These optimizations do not use any semantic analysis.
 * Thus, they can be applied before inlining/slicing happens.
 */
fun runSimplePTAOptimizations(cfg: MutableSbfCFG, globals: GlobalVariables) {
    if (SolanaConfig.optimisticNoMemmove()) {
        removeMemmove(cfg)
        cfg.verify(false, "after removing memmove")
    }
    unhoistMemFunctions(cfg)
    cfg.verify(false, "after unhoisting memcpy")
    cfg.mergeBlocks()
    cfg.verify(false, "after merging blocks ")
    unhoistStoresAndLoads(cfg, globals)
    cfg.verify(false, "after unhoisting stores")
    cfg.removeEmptyBlocks()
    cfg.verify(false, "after remove empty blocks")
    unhoistCalltraceFunctions(cfg)
    cfg.verify(false, "after unhoisting calltrace functions")
}

/**
 * CFG optimizations that help the pointer analysis.
 *
 * Some of these optimizations require the scalar analysis, so they should be run after
 * [prog] has been inlined and sliced for better precision.
 *
 * Note that each optimization runs a scalar analysis since the program can change from one optimization
 * to another.
 **/
fun runPTAOptimizations(prog: SbfCallGraph, memSummaries: MemorySummaries, iteration: UInt): SbfCallGraph {
    return prog.transformSingleEntry { entryCFG ->
        val optEntryCFG = entryCFG.clone(entryCFG.getName())
        promoteMemcpy(optEntryCFG, prog.getGlobals(), memSummaries)
        removeUselessDefinitions(optEntryCFG)
        promoteMemset(optEntryCFG, prog.getGlobals(), memSummaries)
        unhoistPromotedMemcpy(optEntryCFG)
        optEntryCFG.simplify(prog.getGlobals())
        if (iteration == 0U) {
            // Run this pass only once
            splitWideStores(optEntryCFG, prog.getGlobals(), memSummaries)
        }
        optEntryCFG.normalize()
        optEntryCFG.verify(true, "after PTA optimizations")
        optEntryCFG
    }
}

/** CFG optimizations that should be executed only once **/
fun runPostSlicingOptimizations(prog: SbfCallGraph, memSummaries: MemorySummaries): SbfCallGraph {
    return prog.transformSingleEntry { entryCFG ->
        val optEntryCFG = entryCFG.clone(entryCFG.getName())
        // prerequisite for detectOverflowPatterns
        simplifyBools(optEntryCFG)
        optEntryCFG.verify(false, "[after simplifyBools]")
        detectOverflowPatterns(optEntryCFG)
        optEntryCFG.verify(false, "[after markAddWithOverflow]")
        simplifyByteSwapInsts(optEntryCFG)
        optEntryCFG.verify(false, "[after simplifyByteSwapInsts]")
        if (SolanaConfig.PromoteMathIntrinsics.get()) {
            promoteMathIntrinsics(
                optEntryCFG,
                transformers = listOf(
                    U128WrappingSubTransform,
                    U128WrappingAddTransform,
                    U128BinRelTransform
                ),
                globals = prog.getGlobals(),
                memSummaries
            )
            optEntryCFG.verify(false, "[after promoteMathIntrinsics]")
        }
        markLoadedAsNumForPTA(optEntryCFG)
        optEntryCFG.verify(false, "[after markAddWithOverflow]")
        optEntryCFG.normalize()
        optEntryCFG.verify(true, "after post-slicing optimizations")
        optEntryCFG
    }
}
