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

package spec

import bridge.*
import bridge.types.DescriptionAnnotation
import bridge.types.SolidityTypeDescription
import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE
import log.*
import scene.*
import spec.cvlast.*
import utils.*
import java.math.BigInteger

private val logger = Logger(LoggerTypes.COMMON)

/**
 * Translates old conf-file `link` (`state`), `struct_link` (`structLinkingInfo`), and legacy struct linking
 * (`legacyStructLinking`) into [ResolvedLinks] format.
 *
 * - `state` (slot→instanceId) becomes [ResolvedLinks.scalars].
 * - `structLinkingInfo` (fieldName→instanceId) is resolved by walking the storage layout to find
 *   struct members matching the field name. Top-level struct fields become scalars; fields nested
 *   inside arrays or mappings become wildcard links.
 * - `legacyStructLinking` (slotOffset→instanceId) is resolved similarly, matching by struct member
 *   slot offset rather than name.
 */
fun translateConfLinksToResolvedLinks(src: ContractInstanceInSDC): ResolvedLinks {
    val scalarLinks: MutableMap<LinkAccessPath, Set<ContractId>> =
        src.state.entries.associateTo(mutableMapOf()) { (slot, id) -> LinkAccessPath.Root(slot) to setOf(id) }

    if (src.structLinkingInfo.isEmpty() && src.legacyStructLinking.isEmpty()) {
        return if (scalarLinks.isEmpty()) { ResolvedLinks.EMPTY } else { ResolvedLinks(scalars = scalarLinks) }
    }
    val storageLayout = src.storageLayout ?: run {
        // Old solc versions (pre-0.6.5) lack storage layout. Fall back to legacy struct linking
        // which matches by struct field offset alone, without knowing the indexable structure.
        val legacy = src.legacyStructLinking.mapValues { setOf(it.value) }
        return if (scalarLinks.isEmpty() && legacy.isEmpty()) {
            ResolvedLinks.EMPTY
        } else {
            ResolvedLinks(scalars = scalarLinks, legacyStructLinks = legacy)
        }
    }

    val wildcardLinks = mutableMapOf<LinkAccessPath, WildcardLink>()
    val arrayMetadata = mutableMapOf<LinkAccessPath, IndexableType.Array>()

    val matches = storageLayout.storage.flatMap { storageSlot ->
        storageSlot.descriptor?.let {
            findStructLinkMatches(
                it, LinkAccessPath.Root(storageSlot.slot),
                src.structLinkingInfo, src.legacyStructLinking,
                arrayMetadata
            )
        }.orEmpty()
    }
    for ((resolved, targetId) in matches) {
        val targets = setOf(targetId)
        when (resolved) {
            is ResolvedSlotInfo.Scalar -> scalarLinks[resolved.path] = targets
            is ResolvedSlotInfo.Wildcard -> wildcardLinks[resolved.path] = resolved.link.copy(targets = targets)
            is ResolvedSlotInfo.Element -> error("Conf struct links only produce Scalar or Wildcard")
        }
    }

    return ResolvedLinks(
        scalars = scalarLinks,
        wildcardLinks = wildcardLinks,
        arrayMetadata = arrayMetadata
    )
}

/** Returns the [IndexableType] and element type for array/mapping types, or null for non-indexable types. */
private fun SolidityTypeDescription.indexableInfo(): Pair<IndexableType, SolidityTypeDescription>? = when (this) {
    is SolidityTypeDescription.Array ->
        IndexableType.Array(dynamicArrayBaseType.sizeInWords(), IndexableType.Array.SizeKind.Dynamic) to dynamicArrayBaseType
    is SolidityTypeDescription.StaticArray ->
        IndexableType.Array(staticArrayBaseType.sizeInWords(), IndexableType.Array.SizeKind.Static(staticArraySize)) to staticArrayBaseType
    is SolidityTypeDescription.Mapping ->
        IndexableType.Mapping to mappingValueType
    else -> null
}

private fun findStructLinkMatches(
    typeDesc: SolidityTypeDescription,
    basePath: LinkAccessPath,
    structLinkingInfo: Map<String, BigInteger>,
    legacyStructLinking: Map<BigInteger, BigInteger>,
    arrayMetadata: MutableMap<LinkAccessPath, IndexableType.Array>
): List<Pair<ResolvedSlotInfo, BigInteger>> = when (typeDesc) {
    is SolidityTypeDescription.UserDefined.Struct -> typeDesc.structMembers.flatMap { member ->
        val memberSlot = member.type.storageAnnotation().slot
            ?: return@flatMap emptyList()
        val targetByName = structLinkingInfo[member.name]
        val targetByOffset = legacyStructLinking[memberSlot]
        if (targetByName != null && targetByOffset != null && targetByName != targetByOffset) {
            logger.warn {
                "Disagreement on struct link for member '${member.name}' at offset $memberSlot: " +
                    "named=$targetByName, legacy=$targetByOffset. Using named."
            }
        }
        val targetId = targetByName ?: targetByOffset
        val memberBasePath = basePath.withStructOffset(memberSlot)
        val match = targetId?.let {
            val slotInfo = if (!memberBasePath.isIndexable()) {
                ResolvedSlotInfo.Scalar(memberBasePath)
            } else {
                ResolvedSlotInfo.Wildcard(memberBasePath, WildcardLink(targets = emptySet()))
            }
            listOf(slotInfo to it)
        }.orEmpty()
        match + findStructLinkMatches(
            member.type, memberBasePath,
            structLinkingInfo, legacyStructLinking,
            arrayMetadata
        )
    }
    is SolidityTypeDescription.Array,
    is SolidityTypeDescription.StaticArray,
    is SolidityTypeDescription.Mapping -> {
        val (indexableKind, elementType) = typeDesc.indexableInfo() ?: error("unreachable")
        val newBasePath = when (indexableKind) {
            is IndexableType.Mapping -> LinkAccessPath.MapAccess(basePath)
            is IndexableType.Array -> {
                val path = LinkAccessPath.ArrayAccess(basePath)
                arrayMetadata[path] = indexableKind
                path
            }
        }
        findStructLinkMatches(
            elementType, newBasePath,
            structLinkingInfo, legacyStructLinking,
            arrayMetadata
        )
    }
    else -> emptyList()
}

/**
 * Resolves spec-level link entries and sets them on the scene's contract classes via [IContractLinkInfo.setResolvedLinks].
 * Spec link entries and conf link entries (`state`) are mutually exclusive — this is enforced by the AST builder.
 */
fun attachSpecLinks(scene: IScene, cvl: CVL, instances: List<ContractInstanceInSDC>) {
    val instancesByName = instances.associateBy { it.name }
    val aliasToAddress: Map<String, BigInteger> = cvl.importedContracts.mapNotNull { imp ->
        instancesByName[imp.solidityContractName.name]?.let { imp.solidityContractVarId to it.address }
    }.toMap()

    val linksByAddress: Map<BigInteger, List<CVLLinkEntry>> = cvl.linkEntries.groupBy { entry ->
        aliasToAddress[entry.sourceContractAlias]!! // validated by typechecker
    }

    for (contract in scene.getContracts()) {
        val linkInfo = contract as? IContractLinkInfo ?: continue
        val src = (contract as? IContractWithSource)?.src ?: continue
        val specEntries = linksByAddress[src.address] ?: continue

        val (immutableEntries, storageEntries) = specEntries.partition { it.isImmutable }

        val scalarLinks = mutableMapOf<LinkAccessPath, Set<BigInteger>>()
        val elementLinks = mutableMapOf<LinkAccessPath, MutableList<ElementLink>>()
        val wildcardLinks = mutableMapOf<LinkAccessPath, WildcardLink>()
        val arrayMetadata = mutableMapOf<LinkAccessPath, IndexableType.Array>()

        // Storage layout existence is guaranteed by the typechecker for contracts with link entries
        val storageLayout = checkNotNull(src.storageLayout) {
            "Storage layout required for spec link entries on ${contract.name}"
        }
        for (entry in storageEntries) {
            val resolved = resolvePathToSlot(storageLayout, entry.fieldPath, arrayMetadata)
            val targetAddresses = entry.targets.mapToSet { aliasToAddress[it]!! }
            when (resolved) {
                is ResolvedSlotInfo.Scalar ->
                    scalarLinks[resolved.path] = targetAddresses
                is ResolvedSlotInfo.Element ->
                    elementLinks.getOrPut(resolved.path) { mutableListOf() }
                        .add(resolved.link.copy(targets = targetAddresses))
                is ResolvedSlotInfo.Wildcard ->
                    wildcardLinks[resolved.path] = resolved.link.copy(targets = targetAddresses)
            }
        }

        stampConcreteIndices(wildcardLinks, elementLinks)

        val immutableLinks = immutableEntries.associate { entry ->
            (entry.fieldPath.single() as CVLLinkPathSegment.Field).name to
                entry.targets.mapToSet { aliasToAddress[it]!! }
        }

        linkInfo.setResolvedLinks(ResolvedLinks(
            scalarLinks, elementLinks, wildcardLinks, immutableLinks,
            arrayMetadata = arrayMetadata
        ))
    }
}

/**
 * Sets [WildcardLink.concreteIndicesToIgnore] on wildcard entries that coexist with concrete element links
 * at the same [LinkAccessPath].
 */
private fun stampConcreteIndices(
    wildcardLinks: MutableMap<LinkAccessPath, WildcardLink>,
    elementLinks: Map<LinkAccessPath, List<ElementLink>>
) {
    for ((path, wcLink) in wildcardLinks) {
        val concreteEntries = elementLinks[path] ?: continue
        val indices = concreteEntries.mapToSet { it.indexValues }
        if (indices.isNotEmpty()) {
            wildcardLinks[path] = wcLink.copy(concreteIndicesToIgnore = indices)
        }
    }
}

/**
 * Result of resolving a CVL link field path against the storage layout.
 * - [Scalar]: a direct slot (no indexable access), e.g. `a.x` at slot 3 → `Root(3)`
 * - [Element]: a concrete-index path through one or more arrays/mappings, e.g. `a.m[5].x`
 * - [Wildcard]: a wildcard path through one or more arrays/mappings, e.g. `a.m[_].x`
 *
 * Targets are left empty here — the caller ([attachSpecLinks]) fills them in.
 */
private sealed class ResolvedSlotInfo {
    data class Scalar(val path: LinkAccessPath) : ResolvedSlotInfo() {
        init {
            check(path is LinkAccessPath.Root) { "Scalar link must resolve to a Root path, got $path" }
        }
    }
    data class Element(val path: LinkAccessPath, val link: ElementLink) : ResolvedSlotInfo()
    data class Wildcard(val path: LinkAccessPath, val link: WildcardLink) : ResolvedSlotInfo()
}

private fun SolidityTypeDescription.storageAnnotation(): DescriptionAnnotation.StorageAnnotation =
    annotations.filterIsInstance<DescriptionAnnotation.StorageAnnotation>().single()

private fun SolidityTypeDescription.sizeInWords(): BigInteger =
    storageAnnotation().numberOfBytes divRoundUp EVM_WORD_SIZE

/**
 * Entry point for resolving a CVL link field path (e.g. `myMapping[5].balances[_].addr`) to a
 * [ResolvedSlotInfo] containing the structural [LinkAccessPath] and link kind.
 *
 * Looks up the first field in the storage layout to obtain the root slot number and type descriptor,
 * then delegates to [resolveSegments] to walk the remaining segments.
 *
 * The storage layout and descriptors are guaranteed to exist by the typechecker.
 */
private fun resolvePathToSlot(
    storageLayout: StorageLayout,
    fieldPath: List<CVLLinkPathSegment.Resolved>,
    arrayMetadata: MutableMap<LinkAccessPath, IndexableType.Array>
): ResolvedSlotInfo {
    require(fieldPath.isNotEmpty())
    val first = fieldPath.first() as CVLLinkPathSegment.Field
    val topSlot = storageLayout.storage.firstOrNull { it.label == first.name }
        ?: error("Unreachable: field '${first.name}' should have been validated by the typechecker")
    return resolveSegments(LinkAccessPath.Root(topSlot.slot), topSlot.descriptor!!, fieldPath.drop(1).iterator(), arrayMetadata = arrayMetadata)
}

/**
 * Walks path segments, building a [LinkAccessPath] and classifying the result:
 *
 * - **[CVLLinkPathSegment.Field]**: advances [basePath] via [LinkAccessPath.withStructOffset].
 *   For pre-indexable paths (where [basePath] is a [LinkAccessPath.Root]) this advances the slot
 *   number; for post-indexable paths it wraps in [LinkAccessPath.StructAccess].
 * - **[CVLLinkPathSegment.Index]**: wraps [basePath] in [LinkAccessPath.ArrayAccess] or
 *   [LinkAccessPath.MapAccess] and accumulates the concrete index value in [concreteIndices].
 * - **[CVLLinkPathSegment.Wildcard]**: same wrapping, no index accumulation.
 *
 * When all segments are consumed, the result kind depends on the path:
 * - [ResolvedSlotInfo.Scalar] if no indexable was encountered (path has no indexable type)
 * - [ResolvedSlotInfo.Element] if concrete indices were accumulated
 * - [ResolvedSlotInfo.Wildcard] if the path is indexable but has no concrete indices
 */
private fun resolveSegments(
    basePath: LinkAccessPath,
    typeDesc: SolidityTypeDescription,
    segmentIter: Iterator<CVLLinkPathSegment.Resolved>,
    concreteIndices: List<LinkIndexValue> = emptyList(),
    arrayMetadata: MutableMap<LinkAccessPath, IndexableType.Array>
): ResolvedSlotInfo {
    if (!segmentIter.hasNext()) {
        return if (!basePath.isIndexable()) {
            ResolvedSlotInfo.Scalar(basePath)
        } else if (concreteIndices.isEmpty()) {
            ResolvedSlotInfo.Wildcard(basePath, WildcardLink(emptySet()))
        } else {
            ResolvedSlotInfo.Element(basePath, ElementLink(concreteIndices, emptySet()))
        }
    }
    return when (val seg = segmentIter.next()) {
        is CVLLinkPathSegment.Field -> {
            val (offset, memberType) = resolveStructField(typeDesc, seg.name)
            resolveSegments(basePath.withStructOffset(offset), memberType, segmentIter, concreteIndices, arrayMetadata)
        }
        is CVLLinkPathSegment.Index, is CVLLinkPathSegment.Wildcard -> {
            val (indexableType, elementType) = typeDesc.indexableInfo()
                ?: error("Unreachable: expected array or mapping type for ${seg::class.simpleName} segment")
            val indexablePath = when (indexableType) {
                is IndexableType.Mapping -> LinkAccessPath.MapAccess(basePath)
                is IndexableType.Array -> {
                    val path = LinkAccessPath.ArrayAccess(basePath)
                    arrayMetadata[path] = indexableType
                    path
                }
            }
            val newIndices = if (seg is CVLLinkPathSegment.Index) {
                concreteIndices + seg.value
            } else {
                concreteIndices
            }
            resolveSegments(indexablePath, elementType, segmentIter, newIndices, arrayMetadata)
        }
    }
}

/**
 * Resolves a struct field name to its storage slot offset (in words) and the field's type descriptor.
 * Used by [resolveSegments] to advance through struct members.
 */
private fun resolveStructField(
    typeDesc: SolidityTypeDescription,
    name: String
): Pair<BigInteger, SolidityTypeDescription> {
    val struct = typeDesc as? SolidityTypeDescription.UserDefined.Struct
        ?: error("Unreachable: expected struct type for .$name")
    val member = struct.structMembers.firstOrNull { it.name == name }
        ?: error("Unreachable: field '$name' not found in struct")
    val offset = member.type.storageAnnotation().slot
        ?: error("Unreachable: no slot for struct member '$name'")
    return offset to member.type
}
