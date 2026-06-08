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

package analysis.icfg

import analysis.*
import analysis.worklist.SimpleWorklist
import log.Logger
import log.LoggerTypes
import datastructures.stdcollections.*
import scene.IScene
import scene.ITACMethod
import tac.CallId
import utils.*
import vc.data.*
import vc.data.TACMeta.STORAGE_KEY
import java.math.BigInteger

private val logger = Logger(LoggerTypes.EXT_CALL_ANALYSIS)

object CalledContractResolver {
    private class Worker(private val m: ITACMethod, private val scene: IScene) {
        val g = m.code as CoreTACProgram
        private val graph = g.analysisCache.graph
        private val simplifier = object : ExpressionSimplifier(graph, graph.cache.def) {
            override fun interp(c: LTACCmdView<TACCmd.Simple.AssigningCmd>): TACExpr? {
                return c.wrapped.maybeNarrow<TACCmd.Simple.AssigningCmd.ByteLoad>()?.takeIf {
                     TACKeyword.MEMORY.isName { name -> it.cmd.base.namePrefix == name }
                }?.let {
                    scratchMemResolveHeuristic(it)?.let {
                        getAndResolve(it.wrapped).singleOrNull()?.address?.asTACSymbol()?.asSym()
                    }
                }
            }
        }
        private val defAnalysis = simplifier.nonTrivialDefAnalysis

        /** A view on a [BigInteger] that represents the address of an instance of a contract. */
        @JvmInline
        private value class ContractInstanceAddress(val address: BigInteger)

        sealed class ResolvableContractAddress {

            data class StorageSlot(val contractAddress: BigInteger, val slotNumber: BigInteger?) :
                ResolvableContractAddress()

            object THIS : ResolvableContractAddress()
            data class AlreadyResolved(val address: ContractInstanceAddress) : ResolvableContractAddress()
        }


        private fun handleStorageVariable(
            s: TACSymbol.Var,
            where: CmdPointer
        ): Either<LTACCmd, ResolvableContractAddress.StorageSlot?> {
            s.meta.find(TACMeta.SCALARIZATION_SORT).let { storageSort ->
                val storeOf = s.meta.find(STORAGE_KEY)
                if (storageSort != null && storeOf == null) {
                    return Either.Right(null)
                }

                return when (storageSort) {
                    is ScalarizationSort.Split -> Either.Right(
                        ResolvableContractAddress.StorageSlot(
                            storeOf!!,
                            storageSort.idx
                        )
                    )
                    is ScalarizationSort.Packed -> Either.Right(
                        ResolvableContractAddress.StorageSlot(
                            storeOf!!,
                            (storageSort.packedStart as? ScalarizationSort.Split)?.idx)
                    )
                    is ScalarizationSort.UnscalarizedBuffer -> Either.Right(null)
                    null -> {
                        // not storage - should go another level up
                        val def = defAnalysis.nontrivialDefSingleOrNull(s, where)
                        if (def == null) {
                            Either.Right(null)
                        } else {
                            Either.Left(graph.elab(def))
                        }
                    }
                }
            }
        }

        // TODO: Return null if the storage slot in question was modified
        /**
         * The command [p] is expected to hold the callee address value (either concretely or symbolically).
         * We currently support callee addresses from two main sources:
         * 1. `this` contract (i.e. in Solidity, `this.foo()` as opposed to the internal call variant `foo()`)
         * 2.  a contract whose addresses is saved in the storage.
         * This function converts [p] to an object that represents a resolvable address.
         */
        private fun getResolvableContractAddress(p: LTACCmd): ResolvableContractAddress? {
            fun rec(
                it_: LTACCmd
            ): ResolvableContractAddress? {
                var it = it_
                var addr: BigInteger? = null
                var addressResolvableFromStorage = false


                while (it.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd ||
                    it.cmd is TACCmd.Simple.AssigningCmd.WordLoad
                ) {
                    val c = it.cmd
                    val e = when (c) {
                        is TACCmd.Simple.AssigningCmd.AssignExpCmd -> c.rhs
                        is TACCmd.Simple.AssigningCmd.WordLoad -> {
                            addr = c.base.meta.find(STORAGE_KEY) ?:
                                    // When the meta disappears (why?), fallback to callee index based resolution
                                    g.procedures
                                        .find { proc -> proc.callId == it.ptr.block.calleeIdx }
                                        ?.procedureId?.address?.asBigInteger()
                            addressResolvableFromStorage = true
                            c.loc.asSym()
                        }
                        else -> error("Impossible to have $c - expecting an expression or storage load")
                    }
                    when (e) {
                        is TACExpr.BinOp.BWAnd -> {
                            // Still wrapped with a BW-And.
                            if (e.o2 is TACExpr.Sym.Var) {
                                // either it's a storage variable or it's `this` aka tacAddress:
                                if (e.o2.s == TACKeyword.ADDRESS.toVar()) {
                                    return ResolvableContractAddress.THIS
                                }
                                val res = handleStorageVariable(e.o2.s, it.ptr)
                                when (res) {
                                    is Either.Right -> return res.d
                                    is Either.Left -> it = res.d
                                }
                            } else {
                                return null
                            }
                        }
                        is TACExpr.Sym, is TACExpr.Apply -> {
                            val sym = if (e is TACExpr.Sym) {
                                e
                            } else if (e is TACExpr.Apply && (e.f as? TACExpr.TACFunctionSym.BuiltIn)?.bif is TACBuiltInFunction.OpaqueIdentity) {
                                // "see through" OpaqueIdentity
                                e.ops.single() as TACExpr.Sym
                            } else {
                               return null
                            }
                            if (sym is TACExpr.Sym.Var && sym.s.meta.find(STORAGE_KEY) != null) {
                                addressResolvableFromStorage = true
                            }
                            if (addressResolvableFromStorage) {
                                if (sym is TACExpr.Sym.Const) {
                                    if (addr == null) {
                                        logger.error("Could not find address of storage whose slot is ${sym.s.value}")
                                        return null
                                    }
                                    return ResolvableContractAddress.StorageSlot(addr, sym.s.value)
                                }
                                sym as TACExpr.Sym.Var
                                val res = handleStorageVariable(sym.s, it.ptr)
                                when (res) {
                                    is Either.Right -> return res.d
                                    is Either.Left -> it = res.d
                                }
                            } else {
                                // non storage case - maybe it's just our address, hard-coded, or an immutable (TODO)
                                when (sym) {
                                    is TACExpr.Sym.Const -> return ResolvableContractAddress.AlreadyResolved(
                                        ContractInstanceAddress(sym.s.value)
                                    )

                                    is TACExpr.Sym.Var -> {
                                        if (sym.s == TACKeyword.ADDRESS.toVar()) {
                                            return ResolvableContractAddress.THIS
                                        }
                                        return null
                                    }
                                }
                            }
                        }
                        else -> return null
                    }
                }
                return null
            }

            return rec(p)
        }

        private fun resolveSlot(addr: BigInteger?, slotNr: BigInteger?): Set<ContractInstanceAddress> {
            if (slotNr == null || addr == null) return emptySet()
            val targets = scene.getContract(addr).resolvedLinks.bySlot[slotNr] ?: return emptySet()
            return targets.mapToSet { ContractInstanceAddress(it) }
        }

        /**
         * Given a [cmd] representing an assignment of the callee contract address,
         * resolves it to a set of possible contract instance addresses.
         */
        fun getAndResolve(cmd: LTACCmd): Set<ContractInstanceAddress> =
            getResolvableContractAddress(cmd)?.let { resolvable ->
                when (resolvable) {
                    is ResolvableContractAddress.StorageSlot -> resolveSlot(
                        resolvable.contractAddress,
                        resolvable.slotNumber
                    )
                    ResolvableContractAddress.THIS -> setOf(ContractInstanceAddress(m.getContainingContract().instanceId))
                    is ResolvableContractAddress.AlreadyResolved -> setOf(resolvable.address)
                }
            }.orEmpty()

        /* if in ptr we have M[loc], loc is tacM0x40, and we look for the prior mstore.
         * if it was for a constant, return that constant.
         * Note that this cannot work if the read isn't dominated by the write which could happen if the callee is payable
            (or 'seems to be' payable).
         */
        private fun scratchMemResolveHeuristic(lc: LTACCmdView<TACCmd.Simple.AssigningCmd.ByteLoad>): LTACCmdView<*>? {
            val (loc, ptr) = lc.cmd.loc to lc.ptr
            fun isFP(v: TACSymbol.Var, _ptr: CmdPointer, callId: CallId) =
                defAnalysis.getDefAsExprIgnoreM0x40<TACExpr.Sym>(v, _ptr, callId)?.exp?.s == TACKeyword.MEM64.toVar(
                        callId
                    )
            // make sure loc is FP
            if (loc is TACSymbol.Const) return null
            if (!isFP(loc as TACSymbol.Var, ptr, ptr.block.getCallId())) return null

            // get the prior M store
            var mstorePtr: CmdPointer? = null
            val relevantPtrs = mutableListOf(ptr)
            SimpleWorklist.iterateWorklist(relevantPtrs) { p, next ->
                // necessary unsoundness for now - if predecessors all have return/revert commands, take only the one with the return command
                val preds = graph.pred(p)
                val predBlocks = preds.map { it.block }
                val predBlocksAreEndBlocks = predBlocks.all {
                    graph.elab(it).commands.any { c -> c.cmd is TACCmd.Simple.SummaryCmd && c.cmd.summ is ReturnSummary }
                }
                val predToContinueWith =
                    if (preds.size > 1 && predBlocks.toSet().size == preds.size && predBlocksAreEndBlocks) {
                        val returning =
                            preds.filter { graph.elab(it.block).commands.none { c -> c.snarrowOrNull<ReturnSummary>()?.ret is TACCmd.Simple.RevertCmd } }
                        if (returning.size == 1) {
                            returning.first()
                        } else {
                            null
                        }
                    } else {
                        preds.singleOrNull()
                    }
                // continue the chain...
                predToContinueWith?.let {
                    relevantPtrs.add(0, it) // append to the beginning
                    // don't add to next if we see the closes prior FP read
                    val cmd = graph.elab(it).cmd
                    if (cmd !is TACCmd.Simple.AssigningCmd.ByteStore || cmd.base != TACKeyword.MEMORY.toVar(ptr.block.getCallId())) {
                        next.add(it)
                    } else {
                        mstorePtr = it
                    }
                }
            }

            if (mstorePtr == null) return null
            val mstore = graph.elab(mstorePtr!!).cmd as TACCmd.Simple.AssigningCmd.ByteStore
            val storeLoc = mstore.loc
            if (storeLoc is TACSymbol.Const || !isFP(
                    storeLoc as TACSymbol.Var,
                    mstorePtr!!,
                    ptr.block.getCallId()
                )
            ) return null
            if (mstore.value is TACSymbol.Const) return LTACCmd(
                mstorePtr!!,
                mstore
            ).narrow<TACCmd.Simple.AssigningCmd.ByteStore>()

            // the value may be by itself a memory read from the callee. so call recursively.
            val defOfValue =
                defAnalysis.getDefCmd<TACCmd.Simple.AssigningCmd.ByteLoad>(mstore.value as TACSymbol.Var, mstorePtr!!)
            if (defOfValue == null) return defAnalysis.getDefCmd<TACCmd.Simple.AssigningCmd.AssignExpCmd>(
                mstore.value,
                mstorePtr!!
            )

            return scratchMemResolveHeuristic(defOfValue)
        }


        fun analyze(): MutableMap<CmdPointer, Set<BigInteger>> {
            val ret = mutableMapOf<CmdPointer, Set<BigInteger>>()
            g.code.keys.forEach { b ->
                graph.elab(b).commands.forEach command@{ c ->
                    if (c.cmd !is TACCmd.Simple.CallCore && (c.cmd !is TACCmd.Simple.SummaryCmd || c.cmd.summ !is CallSummary)) {
                        return@command
                    }
                    val to = if (c.cmd is TACCmd.Simple.CallCore) {
                        c.cmd.to
                    } else if (c.cmd is TACCmd.Simple.SummaryCmd && c.cmd.summ is CallSummary) {
                        c.cmd.summ.toVar
                    } else {
                        null
                    }
                    /* to is a const in two cases:
                        1. Precompiled contracts (addresses 1-10(?))
                        2. Linked libraries
                     */
                    if (to is TACSymbol.Const) {
                        ret[c.ptr] = setOf(to.value)
                    } else {
                        check(to is TACSymbol.Var)
                        defAnalysis.nontrivialDefSingleOrNull(to, c.ptr)?.let { defOfTo ->
                            val resolved = getAndResolve(graph.elab(defOfTo))
                            if (resolved.isNotEmpty()) {
                                ret[c.ptr] = resolved.map { it.address }.toSet()
                                return@command
                            }
                        }
                        simplifier.simplify(
                            to,
                            c.ptr,
                            true
                        ).getAsConst()?.let { resolved ->
                            ret[c.ptr] = setOf(resolved)
                            return@command
                        }
                    }
                }
            }

            return ret
        }
    }

    fun doAnalysis(m: ITACMethod, scene: IScene): CalleeResolution {
        return Worker(m, scene).analyze().mapValues { (_, addresses) ->
            addresses.mapToSet { CallGraphBuilder.CalledContract.FullyResolved.ConstantAddress(it) }
        }.let(::CalleeResolution)
    }
}
