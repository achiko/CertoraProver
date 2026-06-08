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

package icfg

import allocator.Allocator
import analysis.icfg.CallGraphBuilder
import analysis.storage.StorageAnalysis
import datastructures.stdcollections.*
import log.Logger
import log.LoggerTypes
import scene.*
import tac.MetaKey
import utils.*
import vc.data.*
import java.io.Serializable

private val logger = Logger(LoggerTypes.INLINER)

/** Converts a TAC-level [StorageAnalysis.AnalysisPath] to a [LinkAccessPath] for link matching. */
internal fun StorageAnalysis.AnalysisPath.toLinkAccessPath(): LinkAccessPath = when (this) {
    is StorageAnalysis.AnalysisPath.Root -> LinkAccessPath.Root(slot)
    is StorageAnalysis.AnalysisPath.ArrayAccess -> base.toLinkAccessPath().let(LinkAccessPath::ArrayAccess)
    is StorageAnalysis.AnalysisPath.StaticArrayAccess -> base.toLinkAccessPath().let(LinkAccessPath::ArrayAccess)
    is StorageAnalysis.AnalysisPath.MapAccess -> base.toLinkAccessPath().let(LinkAccessPath::MapAccess)
    is StorageAnalysis.AnalysisPath.StructAccess -> base.toLinkAccessPath().withStructOffset(offset.words)
    is StorageAnalysis.AnalysisPath.WordOffset -> error("WordOffset should have been resolved to StructAccess during canonicalization")
}

/**
 * Resolved storage link info attached to SummaryCmd by [StorageLinkResolver].
 * Holds one [Item] per wildcard-covered storage path that resolved at this call site.
 * Multiple items arise when the dispatched address may come from different storage variables
 * depending on control flow (e.g., `if (flag) registryA[k] else registryB[k]`).
 */
data class ResolvedLinkInfo(
    val items: List<Item>
) : TransformableSymEntity<ResolvedLinkInfo>, AllocatorIdEntity<ResolvedLinkInfo> {
    data class Item(val linkPath: LinkAccessPath, val storageLoc: TACSymbol, val storageReadId: Int) : Serializable

    companion object {
        val META_KEY: MetaKey<ResolvedLinkInfo> = MetaKey("tac.resolved.link.info")
    }
    override fun transformSymbols(f: (TACSymbol) -> TACSymbol): ResolvedLinkInfo =
        copy(items = items.map { it.copy(storageLoc = f(it.storageLoc)) })

    override fun mapId(f: (Allocator.Id, Int) -> Int): ResolvedLinkInfo =
        copy(items = items.map { it.copy(storageReadId = f(Allocator.Id.STORAGE_READ, it.storageReadId)) })
}

/**
 * Resolves storage reads to contract instance IDs using [TACMeta.ACCESS_PATHS] metadata
 * produced by storage analysis and the [ResolvedLinks] attached to each contract.
 */
object StorageLinkResolver {

    private data class LinkMatch(
        val targets: Set<ContractId>,
        val isWildcardCovered: Boolean,
        val resolvedItems: List<ResolvedLinkInfo.Item>
    )

    /** Checks whether a command has a STORAGE_KEY on the relevant operand (WordLoad base or AssignExpCmd source var). */
    private fun hasStorageKey(cmd: TACCmd.Simple): Boolean = when (cmd) {
        is TACCmd.Simple.AssigningCmd.WordLoad ->
            cmd.meta.containsKey(TACMeta.IS_STORAGE_ACCESS) && cmd.base.meta.containsKey(TACMeta.STORAGE_KEY)
        is TACCmd.Simple.AssigningCmd.AssignExpCmd ->
            (cmd.rhs as? TACExpr.Sym.Var)?.s?.meta?.containsKey(TACMeta.STORAGE_KEY) == true
        else -> false
    }

    fun resolve(m: TACMethod): CoreTACProgram {
        val prog = m.code as CoreTACProgram
        val contract = m.getContainingContract()
        val links = contract.resolvedLinks
        if (links.isEmpty()) {
            return prog
        }

        val resolutions = prog.parallelLtacStream().filter {
            it.cmd.meta.containsKey(CallGraphBuilder.ContractStorageRead.META_KEY) && hasStorageKey(it.cmd)
        }.mapNotNull {
            val (keySource, storageLoc) = when (it.cmd) {
                is TACCmd.Simple.AssigningCmd.WordLoad -> it.cmd.base to it.cmd.loc
                is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                    val src = (it.cmd.rhs as TACExpr.Sym.Var).s
                    src to src // keySource and storageLoc are the same for AssignExpCmd
                }
                else -> `impossible!`
            }
            if (keySource.meta.find(TACMeta.STORAGE_KEY) != contract.instanceId) {
                return@mapNotNull null
            }
            val accessPaths = (storageLoc as? TACSymbol.Var)?.meta?.find(TACMeta.ACCESS_PATHS)
            val storageReadId = it.cmd.meta.find(CallGraphBuilder.ContractStorageRead.META_KEY)!!.id

            val matches = accessPaths?.paths?.mapNotNull { path ->
                val linkPath = path.toLinkAccessPath()
                matchAccessPath(linkPath, links)?.let { (targets, isWildcardCovered) ->
                    Triple(targets, isWildcardCovered, ResolvedLinkInfo.Item(linkPath, storageLoc, storageReadId))
                }
            }.orEmpty()
            matches.takeIf { it.isNotEmpty() }?.let {
                val allTargets = it.flatMapToSet { (targets, _, _) -> targets }
                val anyWildcard = it.any { (_, wc, _) -> wc }
                val items = it.map { (_, _, item) -> item }
                val linkMatch = LinkMatch(allTargets, anyWildcard, items)
                logger.info {
                    "Resolved storage read at $it to targets ${linkMatch.targets} (wildcard=${linkMatch.isWildcardCovered})"
                }
                storageReadId to linkMatch
            }
        }.toList().distinctBy { it.first }.toMap()

        fun CallGraphBuilder.CalledContract.resolvedMatch(): Pair<Int, LinkMatch>? {
            val id = (this as? CallGraphBuilder.CalledContract.UnresolvedRead)?.storageReadId ?: return null
            return id `to?` resolutions[id]
        }

        /** Resolve a single [CallGraphBuilder.CalledContract.UnresolvedRead] to a single [CallGraphBuilder.CalledContract.FullyResolved.StorageLink]. Used for return linking. */
        fun CallGraphBuilder.CalledContract.tryResolve(): CallGraphBuilder.CalledContract {
            val (id, match) = resolvedMatch() ?: return this
            return match.targets.singleOrNull()?.let {
                CallGraphBuilder.CalledContract.FullyResolved.StorageLink(it, id, wildcardCovered = match.isWildcardCovered)
            } ?: this
        }

        /** Resolve an [CallGraphBuilder.CalledContract.UnresolvedRead] to a set of [CallGraphBuilder.CalledContract.FullyResolved.StorageLink] targets (all of them). Used for call target dispatch. */
        fun CallGraphBuilder.CalledContract.tryResolveAll(): Set<CallGraphBuilder.CalledContract> {
            val (id, match) = resolvedMatch() ?: return setOf(this)
            return match.targets.mapToSet {
                CallGraphBuilder.CalledContract.FullyResolved.StorageLink(it, id, wildcardCovered = match.isWildcardCovered)
            }
        }

        val patching = prog.toPatchingProgram()
        prog.parallelLtacStream().filter { lcmd ->
            lcmd.cmd is TACCmd.Simple.ReturnCmd
                || lcmd.cmd is TACCmd.Simple.ReturnSymCmd
                || (lcmd.cmd is TACCmd.Simple.SummaryCmd && lcmd.cmd.summ is CallSummary)
        }.mapNotNull { lcmd ->
            when (lcmd.cmd) {
                is TACCmd.Simple.ReturnSymCmd,
                is TACCmd.Simple.ReturnCmd -> {
                    val linking = lcmd.cmd.meta.find(TACMeta.RETURN_LINKING) ?: return@mapNotNull null
                    lcmd.ptr to lcmd.cmd.withMeta(lcmd.cmd.meta + (TACMeta.RETURN_LINKING to linking.copy(
                        byOffset = linking.byOffset.mapValues {
                            it.value.tryResolve()
                        }
                    )))
                }
                is TACCmd.Simple.SummaryCmd -> {
                    check(lcmd.cmd.summ is CallSummary) {
                        "Picked up summary command $lcmd that was *not* a call summary"
                    }
                    val callSumm = lcmd.cmd.summ
                    val newCallTarget = callSumm.callTarget.flatMapToSet { target ->
                        target.tryResolveAll()
                    }
                    // Attach resolved link info for all wildcard-covered storage links
                    // (used by Summarizer for precedence assumptions).
                    val resolvedItems = callSumm.callTarget
                        .filterIsInstance<CallGraphBuilder.CalledContract.UnresolvedRead>()
                        .flatMap { unresolved ->
                            resolutions[unresolved.storageReadId]
                                ?.takeIf { it.isWildcardCovered }
                                ?.resolvedItems
                                .orEmpty()
                        }
                    val newCmd = lcmd.cmd.copy(
                        summ = callSumm.copy(
                            callTarget = newCallTarget,
                            callConvention = callSumm.callConvention.copy(
                                input = callSumm.callConvention.input.copy(
                                    rangeToDecomposedArg = callSumm.callConvention.input.rangeToDecomposedArg.mapValues { (_, arg) ->
                                        arg.withContractReference(
                                            arg.contractReference?.tryResolve()
                                        )
                                    }
                                )
                            )
                        )
                    ).let { cmd ->
                        if (resolvedItems.isNotEmpty()) {
                            cmd.withMeta(cmd.meta + (ResolvedLinkInfo.META_KEY to ResolvedLinkInfo(resolvedItems)))
                        } else {
                            cmd
                        }
                    }
                    lcmd.ptr to newCmd
                }
                else -> `impossible!`
            }
        }.sequential().forEach { (where, new) ->
            patching.replaceCommand(where, listOf(new))
        }
        return patching.toCode(prog)
    }

    /**
     * Matches a [LinkAccessPath] against [ResolvedLinks] to find linked contract targets.
     * Returns the set of target contracts and whether a wildcard link covers the path.
     */
    private fun matchAccessPath(
        linkPath: LinkAccessPath,
        links: ResolvedLinks
    ): Pair<Set<ContractId>, Boolean>? {
        // Scalar slot: check scalars map first
        links.scalars[linkPath]?.let { targets ->
            return targets to false
        }

        // Element links (array and mapping): combine concrete + wildcard at the same path
        val concreteTargets = links.elementLinks[linkPath]?.flatMapToSet { it.targets }
        val wildcardTargets = links.wildcardLinks[linkPath]?.targets
        val allTargets = concreteTargets.orEmpty() + wildcardTargets.orEmpty()
        if (allTargets.isNotEmpty()) {
            return allTargets to !wildcardTargets.isNullOrEmpty()
        }

        // Legacy fallback: for old solc without storage layout, match by struct field offset alone
        if (links.legacyStructLinks.isNotEmpty()) {
            links.legacyStructLinks[linkPath.topStructOffset()]?.let { targets ->
                return targets to true
            }
        }

        return null
    }
}
