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

import datastructures.stdcollections.*
import log.Logger
import log.LoggerTypes
import sbf.SolanaConfig
import sbf.analysis.AnalysisRegisterTypes
import sbf.disassembler.SbfRegister
import sbf.domains.*

private val logger = Logger(LoggerTypes.SBF_MEMCPY_PROMOTION)
private fun dbg(msg: () -> Any) { logger.debug(msg)}

/**
 *  Return true if the store commute over all instructions between [loadLocInst] and [storeLocInst]
 *
 *  The lifted memcpy will be inserted **before the first load**.
 *
 *  If the loaded memory address is overwritten between the load and the store we are okay
 *  (see test19 in PromoteStoresToMemcpyTest.kt).
 *  However, if the stored memory address is overwritten between the load and store then we are not okay and
 *  the sequence of loads and stores shouldn't be lifted to a memcpy (see test20 in PromoteStoresToMemcpyTest.kt).
 **/
fun <D, TNum, TOffset> isSafeToCommuteStore(
    bb: SbfBasicBlock,
    @Suppress("UNUSED_PARAMETER") load: MemAccess,
    loadLocInst: LocatedSbfInstruction,
    store: MemAccess,
    storeLocInst: LocatedSbfInstruction,
    types: AnalysisRegisterTypes<D, TNum, TOffset>,
    useDynFrames: Boolean
): Boolean
    where TNum : INumValue<TNum>,
          TOffset : IOffset<TOffset>,
          D : AbstractDomain<D>, D : ScalarValueProvider<TNum, TOffset> {

    val name = "isSafeToCommuteStore"

    check(loadLocInst.label == bb.getLabel())  {
        "can only promote pairs of load-store within the same block $loadLocInst"
    }
    check(storeLocInst.label == bb.getLabel()) {
        "can only promote pairs of load-store within the same block $storeLocInst"
    }

    val loadInst = loadLocInst.inst
    check(loadInst is SbfInstruction.Mem) { "$name: $loadLocInst should be a load"}
    val storeInst = storeLocInst.inst
    check(storeInst is SbfInstruction.Mem) {"$name: $storeLocInst should be a store"}


    val storeBaseReg = storeInst.access.base
    val storeRange = FiniteInterval.mkInterval(store.offset, store.width.toLong())

    val betweenInsts = bb.getLocatedInstructions().subList(loadLocInst.pos + 1, storeLocInst.pos)

    dbg { "$name: $storeInst up to $loadInst?" }
    val aliases = loadInst.writeRegister.toMutableSet()


    for (inst in betweenInsts.map { it.inst }) {
        // check no instruction can modify the loaded register
        if (!inst.isRestoreScratchRegisters() &&
            inst.writeRegister.intersect(loadInst.writeRegister).isNotEmpty()) {
            dbg { "\t$name: $inst might modify the loaded register" }
            return false
        }

        // check no instruction can modify the store's base register
        if (!inst.isRestoreScratchRegisters() &&
            !inst.isStackPush(useDynFrames) &&
            !inst.isStackPop(useDynFrames) &&
            inst.writeRegister.contains(storeBaseReg)) {
            dbg { "\t$name: $inst might modify the base register of the store" }
            return false
        }

        if (inst is SbfInstruction.Bin && inst.op == BinOp.MOV &&
            inst.readRegisters.intersect(aliases).isNotEmpty()) {
            aliases.add(inst.dst)
        }

        // check that the loaded register cannot be used directly or indirectly by assume/assert
        if ((inst.isAssertOrSatisfy() || inst is SbfInstruction.Assume) &&
            inst.readRegisters.intersect(aliases).isNotEmpty()) {
            dbg { "\t$name: $inst might affect the loaded register" }
            return false
        }
    }

    /**
     * Check that [norm] does not overlap with [range]
     **/
    fun stackNoOverlap(norm: MemAccess, interInst: SbfInstruction, range: FiniteInterval): Boolean {
        val noOverlap = !norm.overlap(range)
        if (noOverlap) {
            dbg { "\t$name OK: $interInst is stack and $storeInst is stack but no overlap." }
        } else {
            dbg { "\t$name FAIL: $interInst is stack and $storeInst is stack and they overlap." }
        }
        return noOverlap
    }

    fun commuteOverMemcpy(locInst: LocatedSbfInstruction, range: FiniteInterval): Boolean {
        val inst = locInst.inst
        val memAccesses = normalizeMemcpy(locInst, types) ?: run {
            dbg { "\t$name FAIL: cannot statically determine length in $inst" }
            return false
        }
        val (normSrc, normDest) = memAccesses
        for (normSrcOrDst in listOf(normSrc, normDest)) {
            if (normSrcOrDst.region != MemAccessRegion.STACK) {
                dbg { "\t$name FAIL: stores do not commute over memcpy for now" }
                return false
            }
            if (!stackNoOverlap(normSrcOrDst, inst, range)) {
                return false
            }
        }
        return true
    }

    // Check that any intermediate store/load/memcpy cannot read/write to the same bytes as the
    // store instruction
    return when (store.region.resolve()) {
        MemAccessRegion.STACK -> {
            betweenInsts.all { locInst ->
                val inst = locInst.inst
                when {
                    inst is SbfInstruction.Mem -> {
                        val normAccess = normalizeLoadOrStore(locInst, types)
                        when (normAccess.region.resolve()) {
                            MemAccessRegion.STACK -> {
                                stackNoOverlap(normAccess, inst, storeRange)
                            }
                            MemAccessRegion.NON_STACK -> {
                                dbg { "\t$name OK: $inst is non-stack and $storeInst is stack" }
                                true
                            }
                            MemAccessRegion.ANY -> {
                                dbg { "\t$name FAIL: $inst is any memory and $storeInst is stack" }
                                false
                            }
                        }
                    }
                    inst.isMemcpy() ->  {
                        commuteOverMemcpy(locInst, storeRange)
                    }
                    else -> true
                }
            }
        }
        MemAccessRegion.NON_STACK -> {
            betweenInsts.all { locInst ->
                val inst = locInst.inst
                when {
                    inst is SbfInstruction.Mem -> {
                        val normAccess = normalizeLoadOrStore(locInst, types)
                        when (normAccess.region.resolve()) {
                            MemAccessRegion.STACK -> {
                                dbg { "\t$name OK: $inst is stack and $storeInst is non-stack" }
                                true
                            }
                            MemAccessRegion.NON_STACK -> {
                                if (normAccess.reg != storeBaseReg.r) {
                                    dbg { "\t$name FAIL: $inst is non-stack and $storeInst is non-stack" }
                                    return@all false
                                }
                                val noOverlap = !normAccess.overlap(storeRange)
                                if (noOverlap) {
                                    dbg { "\t$name OK: $inst non-stack, same register, no overlap." }
                                } else {
                                    dbg { "\t$name FAIL: $inst non-stack, same register, overlap."}
                                }
                                noOverlap
                            }
                            MemAccessRegion.ANY -> {
                                dbg { "\t$name FAIL: $inst is any memory and $storeInst is non-stack" }
                                false
                            }
                        }
                    }
                    inst.isMemcpy() -> {
                        dbg { "\t$name FAIL: stores do not commute over memcpy for now" }
                        false
                    }
                    else -> true
                }
            }
        }
        MemAccessRegion.ANY -> {
            dbg { "\t$name: $storeInst on unknown memory" }
            betweenInsts.none { it.inst is SbfInstruction.Mem || it.inst.isMemcpy() }
        }
    }.also { if (it) {
             dbg { "$name OK" }
        }
    }
}

// If optimistic mode, treat ANY non-stack region as NON_STACK
private fun MemAccessRegion.resolve() =
    if (SolanaConfig.optimisticMemcpyPromotion() && this == MemAccessRegion.ANY) {
        MemAccessRegion.NON_STACK
    } else {
        this
    }

/**
 * Return true if folding the load/store pair [loadLocInst]/[storeLocInst] into [memcpy] would not
 * alter program semantics.
 *
 * The lifted memcpy will be inserted before the pattern's earliest load.  This effectively moves the
 * new pair's load and store earlier than their original positions, so two interferences must be
 * ruled out across the gap between the pattern's first load and [loadLocInst]:
 *
 *  1. No intermediate store overlaps [loadMemAccess]'s bytes — otherwise the lifted memcpy would
 *     read stale data.
 *
 *  2. No intermediate load *or* store accesses [storeMemAccess]'s bytes — once the memcpy writes
 *     those bytes early, an intermediate read would observe the new value instead of the old, and
 *     an intermediate write would be silently overwritten by the memcpy in the rewrite.
 */
fun <D, TNum, TOffset> isSafeToCommuteLoadStorePair(
    bb: SbfBasicBlock,
    memcpy: MemcpyPattern,
    loadMemAccess: MemAccess,
    loadLocInst: LocatedSbfInstruction,
    storeMemAccess: MemAccess,
    storeLocInst: LocatedSbfInstruction,
    types: AnalysisRegisterTypes<D, TNum, TOffset>
): Boolean
    where TNum: INumValue<TNum>,
          TOffset: IOffset<TOffset>,
          D: AbstractDomain<D>, D: ScalarValueProvider<TNum, TOffset> {

    if (memcpy.getLoads().isEmpty()) {
        return true
    }
    val firstLoadPos = memcpy.getLoads().first().pos
    // The pattern is built only when a store is processed -- loads only populate
    // `defLoads`.  So by the time we reach here we know four positions:
    //
    //   L1 = firstLoadPos     : first-added pair's load (part of `memcmpy`).
    //   S1                    : first-added pair's store; already processed earlier in the scan (part of `memcpy`).
    //   L2 = loadLocInst.pos  : the new pair's load.
    //   S2 = storeLocInst.pos : the new pair's store, currently being processed.
    //
    // with L1 < S1,  L2 < S2 (loads precede their stores) and S1 < S2 (S1 was processed
    // before S2 without the pattern being reset).  That leaves three valid orderings:
    //
    //   Case 1: L1 < L2 < S1 < S2   (e.g. load r1; load r2; store r1; store r2)
    //   Case 2: L2 < L1 < S1 < S2   (e.g. load r1; load r2; store r2; store r1)
    //   Case 3: L1 < S1 < L2 < S2   (e.g. load r1; store r1; load r2; store r2)
    //
    // After promotion the lifted memcpy is inserted at min(L1, L2), so the window of
    // instructions whose execution time relative to the new pair's bytes changes is
    // [min(L1, L2), S2).  In every case, [from, to] below is exactly that window:
    //
    //   Case 1 / Case 3:  from = L1, to = S2
    //   Case 2:           from = L2, to = S2
    //
    // The lambda skips four kinds of instruction: the new pair's own load and store (L2,
    // S2), and any pattern member load or store (L1, S1, and any other pairs in between).
    // Pattern members are part of the lifted memcpy's combined source/destination range
    // and were already validated by canBePromoted's noOverlap check; checking them here
    // would falsely reject patterns whose stride is smaller than their width, where the
    // new pair's range and a prior pattern store's range legitimately overlap.
    val from = minOf(firstLoadPos, loadLocInst.pos)
    val to   = storeLocInst.pos
    val loadRange = FiniteInterval.mkInterval(loadMemAccess.offset, loadMemAccess.width.toLong())
    val loadRegion = loadMemAccess.region.resolve()
    val storeRange = FiniteInterval.mkInterval(storeMemAccess.offset, storeMemAccess.width.toLong())
    val storeRegion = storeMemAccess.region.resolve()

    // A base register is "stable" across [from, to) if no instruction in that range writes to it.
    fun regStable(reg: SbfRegister): Boolean {
        val regValue = Value.Reg(reg)
        return bb.getLocatedInstructions().subList(from, to).none { regValue in it.inst.writeRegister }
    }
    val isLoadRegStable = regStable(loadMemAccess.reg)
    val isStoreRegStable = regStable(storeMemAccess.reg)

    // Pattern members are part of the lifted memcpy's combined source/destination ranges.
    // They must not be re-checked here. Treating them as ordinary intermediate accesses would cause false rejections.
    val patternLoads = memcpy.getLoads().toSet()
    val patternStores = memcpy.getStores().toSet()

    fun mayOverlap(
        targetReg: SbfRegister,
        targetRegion: MemAccessRegion,
        targetRange: FiniteInterval,
        isTargetRegStable: Boolean,
        normAccess: MemAccess
    ): Boolean {
        val accessRegion = normAccess.region.resolve()
        return when {
            // If we don't know the regions then we conservatively assume they may overlap
            targetRegion == MemAccessRegion.ANY   || accessRegion == MemAccessRegion.ANY -> true
            // Both non-stack: if the two accesses use the same base register and that
            // register's value is unchanged across the safety window, their addresses are
            // determined entirely by their offsets, and we can compare them precisely.
            // Otherwise, we cannot reason about the addresses, so assume they overlap.
            targetRegion != MemAccessRegion.STACK && accessRegion != MemAccessRegion.STACK ->
                if (isTargetRegStable && targetReg == normAccess.reg) {
                    normAccess.overlap(targetRange)
                } else {
                    true
                }
            // If both accesses are on the stack then we check whether they overlap
            targetRegion == MemAccessRegion.STACK && accessRegion == MemAccessRegion.STACK -> normAccess.overlap(targetRange)
            // otherwise, they are from different regions so they cannot overlap
            else -> false
        }
    }

    return bb.getLocatedInstructions().subList(from, to).all { locInst ->
        val inst = locInst.inst
        when {
            inst !is SbfInstruction.Mem -> true
            // Skip the new pair's own load/store (they cannot conflict with themselves) and
            // any pattern members (their ranges are already accounted for by the lifted
            // memcpy's combined intervals -- see `patternLoads` / `patternStores` above).
            locInst == loadLocInst || locInst == storeLocInst -> true
            locInst in patternLoads || locInst in patternStores -> true
            else -> {
                val normAccess = normalizeLoadOrStore(locInst, types)
                // (1) An intermediate store that overwrites the new load's source bytes
                val isSrcOverwritten = !inst.isLoad &&
                    mayOverlap(loadMemAccess.reg, loadRegion, loadRange, isLoadRegStable, normAccess)
                // (2) An intermediate load or store that touches the new store's destination bytes
                val isDstTouched =
                    mayOverlap(storeMemAccess.reg, storeRegion, storeRange, isStoreRegStable, normAccess)
                !isSrcOverwritten && !isDstTouched
            }
        }
    }
}
