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

package scene

import com.certora.collect.TreapMap
import com.certora.collect.treapMapOf
import datastructures.stdcollections.*
import spec.cvlast.LinkIndexValue
import utils.*
import java.io.Serializable
import java.math.BigInteger

/** The numeric index of a storage slot in the EVM storage layout. */
typealias StorageSlotNum = BigInteger

/**
 * A recursive path that captures the structural shape of a storage access for link matching.
 * Mirrors [analysis.storage.StorageAnalysis.AnalysisPath] but carries only static structural info
 * (no TAC-level symbols).
 */
sealed class LinkAccessPath : Serializable {
    abstract fun rootSlot(): StorageSlotNum

    fun topStructOffset(): BigInteger = when (this) {
        is StructAccess -> offset
        else -> BigInteger.ZERO
    }

    data class Root(val slot: StorageSlotNum) : LinkAccessPath() {
        override fun rootSlot() = slot
    }
    data class ArrayAccess(val base: LinkAccessPath) : LinkAccessPath() {
        override fun rootSlot() = base.rootSlot()
    }
    data class MapAccess(val base: LinkAccessPath) : LinkAccessPath() {
        override fun rootSlot() = base.rootSlot()
    }
    data class StructAccess(val base: LinkAccessPath, val offset: BigInteger) : LinkAccessPath() {
        override fun rootSlot() = base.rootSlot()
    }

    /** Whether this path contains an indexable (array or mapping) step. */
    fun isIndexable(): Boolean = when (this) {
        is Root -> false
        is ArrayAccess, is MapAccess -> true
        is StructAccess -> base.isIndexable()
    }

    /** Whether the outermost indexable step is an array (not a mapping). */
    fun isArrayIndexable(): Boolean = when (this) {
        is Root, is MapAccess -> false
        is ArrayAccess -> true
        is StructAccess -> base.isArrayIndexable()
    }

    /**
     * Advances this path by a struct field offset. For [Root] paths, the offset is absorbed into
     * the slot number (matching EVM's flat SLOAD). For paths inside an indexable, wraps in
     * [StructAccess]. Collapses nested [StructAccess] by adding offsets.
     */
    fun withStructOffset(offset: BigInteger): LinkAccessPath = when {
        offset == BigInteger.ZERO -> this
        this is Root -> Root(slot + offset)
        this is StructAccess -> StructAccess(base, this.offset + offset)
        else -> StructAccess(this, offset)
    }
}

/** Distinguishes array vs mapping indexable types and carries array-specific metadata. */
sealed interface IndexableType : Serializable {
    data class Array(val elementSizeInWords: BigInteger, val sizeKind: SizeKind) : IndexableType {
        sealed interface SizeKind : Serializable {
            data object Dynamic : SizeKind {
                private fun readResolve(): Any = Dynamic
            }
            data class Static(val size: BigInteger) : SizeKind
        }
    }
    data object Mapping : IndexableType {
        private fun readResolve(): Any = Mapping
    }
}

/**
 * Identifies a concrete element link entry (array or mapping) in a spec links block.
 * Whether this is an array or mapping link is determined by the [LinkAccessPath] key
 * ([LinkAccessPath.ArrayAccess] vs [LinkAccessPath.MapAccess]).
 * @param indexValues the restricted index/key values from the link path (one per nesting level)
 * @param targets set of target contract instance IDs
 */
data class ElementLink(
    val indexValues: List<LinkIndexValue>,
    val targets: Set<ContractId>
) : Serializable

/**
 * A wildcard link entry: all elements of an array/mapping dispatch to the given targets.
 * Whether this is an array or mapping link is determined by the [LinkAccessPath] key.
 * @param targets set of target contract instance IDs
 * @param concreteIndicesToIgnore index lists that have concrete link entries and should be excluded from the wildcard
 */
data class WildcardLink(
    val targets: Set<ContractId>,
    val concreteIndicesToIgnore: Set<List<LinkIndexValue>> = emptySet()
) : Serializable

data class ResolvedLinks(
    val scalars: Map<LinkAccessPath, Set<ContractId>> = emptyMap(),
    val elementLinks: Map<LinkAccessPath, List<ElementLink>> = emptyMap(),
    val wildcardLinks: Map<LinkAccessPath, WildcardLink> = emptyMap(),
    /** Immutable variable links: varname → set of target contract instance IDs. */
    val immutables: Map<String, Set<ContractId>> = emptyMap(),
    /**
     * Legacy struct linking for old solc versions (pre-0.6.5) without storage layout.
     * Maps struct field offset → target contract ID. Matches any indexable element access
     * with the given struct offset, regardless of the specific path structure.
     */
    val legacyStructLinks: Map<BigInteger, Set<ContractId>> = emptyMap(),
    /** Array compilation metadata (element size, static/dynamic) keyed by the [LinkAccessPath.ArrayAccess] path. */
    val arrayMetadata: Map<LinkAccessPath, IndexableType.Array> = emptyMap()
) : Serializable {
    fun isEmpty() = scalars.isEmpty() && elementLinks.isEmpty() && wildcardLinks.isEmpty() &&
        immutables.isEmpty() && legacyStructLinks.isEmpty() && arrayMetadata.isEmpty()
    /** Merged view keyed by slot. Unions values for overlapping root slots. */
    val bySlot: TreapMap<StorageSlotNum, Set<ContractId>> by lazy {
        val acc = mutableMapOf<StorageSlotNum, MutableSet<ContractId>>()
        fun add(slot: StorageSlotNum, targets: Set<ContractId>) {
            acc.getOrPut(slot) { mutableSetOf() }.addAll(targets)
        }
        for ((path, targets) in scalars) { add(path.rootSlot(), targets) }
        for ((path, entries) in elementLinks) { add(path.rootSlot(), entries.flatMapToSet { it.targets }) }
        for ((path, entry) in wildcardLinks) { add(path.rootSlot(), entry.targets) }
        acc.entries.fold(treapMapOf<StorageSlotNum, Set<ContractId>>()) { map, (k, v) -> map.put(k, v) }
    }

    companion object {
        val EMPTY = ResolvedLinks()
    }
}

interface IContractLinkInfo {
    fun setResolvedLinks(links: ResolvedLinks)
}
