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

import analysis.CommandWithRequiredDecls
import analysis.SimpleCmdsWithDecls
import analysis.storage.StorageAnalysisResult.NonIndexedPath
import datastructures.stdcollections.*
import vc.data.tacexprutil.ExprUnfolder
import evm.*
import scene.*
import tac.*
import utils.*
import vc.data.*
import vc.data.TACMeta.BIT_WIDTH
import vc.data.TACMeta.SCALARIZATION_SORT
import vc.data.TACMeta.STABLE_STORAGE_FAMILY
import vc.data.TACMeta.STABLE_STORAGE_PATH
import vc.data.TACMeta.STORAGE_TYPE
import vc.data.TACProgramCombiners.andThen
import java.math.BigInteger

private val cvlHashCmd: (TACSymbol.Var, BigInteger, List<TACSymbol>) -> TACCmd.Simple.AssigningCmd =
    { lhs, length, args ->
        TACCmd.Simple.AssigningCmd.AssignExpCmd(lhs, TACExpr.SimpleHash(
            length = TACSymbol.lift(length).asSym(),
            args = args.map { it.asSym() },
            hashFamily = HashFamily.Keccack
        ))
    }

/**
 * Bundles a [CVLTACProgram] that computes a storage slot key,
 * along with the variable holding the final key value.
 */
internal data class SlotComputation(
    val program: CVLTACProgram,
    val keyVar: TACSymbol.Var,
)

// ──────────────────────────────────────────────────────────────────────────────
// Extension functions on CVLCompiler for link compilation
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Compute the TAC commands that produce the storage slot key for an element link.
 * Walks the [LinkAccessPath] recursively:
 * - [LinkAccessPath.Root]: literal slot constant
 * - [LinkAccessPath.ArrayAccess]: `keccak(base) + index * elementSize` (dynamic) or `base + index * elementSize` (static)
 * - [LinkAccessPath.MapAccess]: `keccak(key . base)` (64-byte concatenation)
 * - [LinkAccessPath.StructAccess]: `base + offset`
 *
 * **Note:** The keccak used here is the prover's *symbolic* hash function, not the concrete keccak256.
 */
internal fun CVLCompiler.computeSlotKey(
    link: ElementLink, path: LinkAccessPath, env: CVLCompiler.CompilationEnvironment,
    arrayMetadata: Map<LinkAccessPath, IndexableType.Array>
): SlotComputation {
    val indexSyms = link.indexValues.map { resolveIndexSymbol(it) }
    val (slotCmds, slotSym) = path.computeSlot(indexSyms.iterator(), "",
        makeHashCmd = cvlHashCmd,
        arrayMetadata = arrayMetadata
    )

    val slotProg = slotCmds.toProg("slotKey", env)
    return SlotComputation(program = slotProg, keyVar = slotSym as TACSymbol.Var)
}

/**
 * Recursively walks a [LinkAccessPath] and returns TAC commands to compute the storage slot key
 * for a given index symbol, along with the symbol holding the final slot value.
 *
 * [makeHashCmd] abstracts over pipeline-stage differences:
 * - `AssignExpCmd(SimpleHash(...))` at the CVL compilation stage
 * - `AssignSimpleSha3Cmd` at the Summarizer stage
 */
internal fun LinkAccessPath.computeSlot(
    indexIter: Iterator<TACSymbol>,
    suffix: String,
    makeHashCmd: (lhs: TACSymbol.Var, length: BigInteger, args: List<TACSymbol>) -> TACCmd.Simple.AssigningCmd,
    arrayMetadata: Map<LinkAccessPath, IndexableType.Array> = emptyMap()
): Pair<SimpleCmdsWithDecls, TACSymbol> {
    fun unfold(prefix: String, expr: TACExpr): Pair<SimpleCmdsWithDecls, TACSymbol> {
        val res = ExprUnfolder.unfoldToSingleVar(prefix, expr)
        return CommandWithRequiredDecls(res.cmds, res.newVars.toSet()) to res.e.s
    }

    return when (this) {
        is LinkAccessPath.Root -> SimpleCmdsWithDecls() to TACSymbol.lift(slot)

        is LinkAccessPath.StructAccess -> {
            val (baseCmds, baseSym) = base.computeSlot(indexIter, suffix, makeHashCmd, arrayMetadata)
            if (offset == BigInteger.ZERO) {
                baseCmds to baseSym
            } else {
                val (addCmds, addSym) = unfold("struct$suffix",
                    TACExpr.Vec.Add(baseSym.asSym(), TACSymbol.lift(offset).asSym())
                )
                (baseCmds andThen addCmds) to addSym
            }
        }

        is LinkAccessPath.ArrayAccess -> {
            val (baseCmds, baseSym) = base.computeSlot(indexIter, suffix, makeHashCmd, arrayMetadata)
            val indexSym = indexIter.next()
            val arrayInfo = arrayMetadata.getValue(this)

            val dataStart: Pair<SimpleCmdsWithDecls, TACSymbol> = when (arrayInfo.sizeKind) {
                is IndexableType.Array.SizeKind.Dynamic -> {
                    val hashVar = TACKeyword.TMP(Tag.Bit256, "arrayHash$suffix")
                    CommandWithRequiredDecls(
                        listOf(makeHashCmd(hashVar, EVM_WORD_SIZE, listOf(baseSym))),
                        setOf(hashVar)
                    ) to hashVar
                }
                is IndexableType.Array.SizeKind.Static -> SimpleCmdsWithDecls() to baseSym
            }

            val (offsetCmds, offsetSym) = unfold("arrayMul$suffix",
                TACExpr.Vec.Mul(indexSym.asSym(), TACSymbol.lift(arrayInfo.elementSizeInWords).asSym())
            )

            val (slotCmds, slotSym) = unfold("arraySlot$suffix",
                TACExpr.Vec.Add(dataStart.second.asSym(), offsetSym.asSym())
            )
            (baseCmds andThen dataStart.first andThen offsetCmds andThen slotCmds) to slotSym
        }

        is LinkAccessPath.MapAccess -> {
            val (baseCmds, baseSym) = base.computeSlot(indexIter, suffix, makeHashCmd, arrayMetadata)
            val indexSym = indexIter.next()
            val hashVar = TACKeyword.TMP(Tag.Bit256, "mapHash$suffix")
            (baseCmds andThen CommandWithRequiredDecls(
                listOf(makeHashCmd(hashVar, EVM_WORD_SIZE * BigInteger.TWO, listOf(indexSym, baseSym))),
                setOf(hashVar)
            )) to hashVar
        }
    }
}

/**
 * Resolves element links (array and mapping) via wordmap Select expressions.
 * Emits `assume(select(wordmap, slotKey) == address)` for each element link entry.
 * Returns a list of [CVLTACProgram]s, one per link entry (plus optional extras like bounds constraints).
 */
internal fun CVLCompiler.resolveElementLinks(
    contract: IContractClass,
    storageVars: Set<TACSymbol.Var>,
    scene: IScene,
    linkMap: Map<LinkAccessPath, List<ElementLink>>,
    env: CVLCompiler.CompilationEnvironment,
    arrayMetadata: Map<LinkAccessPath, IndexableType.Array>,
): CVLTACProgram {
    // Group wordmaps by their LinkAccessPath to correctly distinguish different indexable structures.
    // When multiple wordmaps share the same path (packed struct fields in the same slot), prefer the
    // address-typed one since link entries always target address fields.
    //
    // We build the index from STABLE_STORAGE_PATH (primary) but also consider STABLE_STORAGE_FAMILY
    // for aliased storage (e.g., `mapping storage m; if(*) { m = a; } else { m = b; }`).
    // In such cases, multiple logical paths share a single wordmap, and only one is chosen as the
    // STABLE_STORAGE_PATH. The family contains all equivalent paths.
    val wordmapVars = storageVars.filter { it.tag.isMapType() }
    val allVarsByPath = mutableMapOf<LinkAccessPath, MutableSet<TACSymbol.Var>>()
    var rootWordmap: TACSymbol.Var? = null
    for (wm in wordmapVars) {
        val familyPaths = wm.meta.find(STABLE_STORAGE_FAMILY)?.storagePaths
        if (familyPaths != null) {
            for (nip in familyPaths) {
                allVarsByPath.getOrPut(nip.toLinkAccessPath()) { mutableSetOf() }.add(wm)
            }
        } else {
            val path = wm.meta.find(STABLE_STORAGE_PATH)?.toLinkAccessPath()
            if (path != null) {
                allVarsByPath.getOrPut(path) { mutableSetOf() }.add(wm)
            } else {
                rootWordmap = rootWordmap ?: wm
            }
        }
    }
    val wordmapsByPath = allVarsByPath.mapValues { (_, vars) ->
        vars.singleOrNull() ?: vars.firstOrNull { sv ->
            val descr = sv.meta.find(STORAGE_TYPE)
            descr is spec.cvlast.typedescriptors.EVMTypeDescriptor.address ||
                descr is spec.cvlast.typedescriptors.EVMTypeDescriptor.EVMContractTypeDescriptor
        } ?: vars.first()
    }

    return linkMap.flatMap { (path, entries) ->
        val wordmap = wordmapsByPath[path]
        if (wordmap == null) {
            logger.info { "No wordmap found for path=$path in ${contract.name}. Available paths: ${wordmapsByPath.keys}" }
            return@flatMap emptyList()
        }
        val label = if (path.isArrayIndexable()) { "Array" } else { "Mapping" }
        val lenSymByBaseSlot = mutableMapOf<BigInteger, Pair<TACSymbol, Set<TACSymbol.Var>>>()
        entries.map { entry ->
            val slotInfo = computeSlotKey(entry, path, env, arrayMetadata)
            val selectExpr = TACExpr.Select(
                base = wordmap.asSym(),
                loc = slotInfo.keyVar.asSym(),
                tag = Tag.Bit256
            )
            val (linkCondExpr, addressDecls) = buildAddressDisjunction(selectExpr, entry.targets, scene)
            val targetNames = formatContractNames(scene, entry.targets)

            // Compute array bounds conditions (if any dynamic arrays exist in the path)
            val boundsResult = computeArrayBoundsConditions(
                entry, path, contract, storageVars, lenSymByBaseSlot, env,
                wordmapsByPath, rootWordmap, arrayMetadata
            )

            // Emit conditional linking: assume(bounds => linking)
            // If no bounds conditions, just assume the linking directly.
            val assumeExpr = if (boundsResult.conditions.isEmpty()) {
                linkCondExpr
            } else {
                // boundsConj = bound0 && bound1 && ...
                val boundsConj: TACExpr = boundsResult.conditions.reduce { a, b -> TACExpr.BinBoolOp.LAnd(a, b) }
                // bounds => linking  ≡  !bounds || linking
                TACExpr.BinBoolOp.LOr(TACExpr.UnaryExp.LNot(boundsConj, Tag.Bool), linkCondExpr)
            }
            val tmpVar = TACKeyword.TMP(Tag.Bool, "elementLinkSetup")
            val assumeProg = wrapWithCVL(
                CommandWithRequiredDecls(
                    listOf(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(lhs = tmpVar, rhs = assumeExpr),
                        TACCmd.Simple.AssumeCmd(tmpVar, "element linking")
                    ),
                    setOf(wordmap, tmpVar) + addressDecls
                ),
                "$label element link ${contract.name}.${wordmap.meta.find(STABLE_STORAGE_PATH)}${entry.indexValues.joinToString("") { "[$it]" }}={$targetNames}"
            ).toProg("elementLink", env)

            slotInfo.program merge boundsResult.program merge assumeProg
        }
    }.fold(CVLTACProgram.empty("elemLinks")) { acc, program -> acc merge program }
}

/**
 * Computes array bounds conditions for element link entries with dynamic arrays.
 * Returns a [BoundsResult] containing the TAC program to compute array lengths
 * and the bound-check condition expressions (`len > index` for each nesting level).
 *
 * These conditions are used by the caller to emit conditional linking:
 * `assume(bounds => linking)` rather than unconditionally constraining array sizes.
 */
internal data class BoundsResult(
    val program: CVLTACProgram,
    val conditions: List<TACExpr>
)

internal fun CVLCompiler.computeArrayBoundsConditions(
    link: ElementLink,
    path: LinkAccessPath,
    contract: IContractClass,
    storageVars: Set<TACSymbol.Var>,
    lenSymByBaseSlot: MutableMap<BigInteger, Pair<TACSymbol, Set<TACSymbol.Var>>>,
    env: CVLCompiler.CompilationEnvironment,
    wordmapsByPath: Map<LinkAccessPath, TACSymbol.Var> = emptyMap(),
    rootWordmap: TACSymbol.Var? = null,
    arrayMetadata: Map<LinkAccessPath, IndexableType.Array> = emptyMap()
): BoundsResult {
    val indexables = path.collectIndexableAccesses()
    if (indexables.isEmpty()) {
        return BoundsResult(CVLTACProgram.empty("not an array"), emptyList())
    }

    val allCmds = mutableListOf<TACCmd.Spec>()
    val allDecls = mutableSetOf<TACSymbol.Var>()
    val boundConditions = mutableListOf<TACExpr>()
    val indexSyms = link.indexValues.map { resolveIndexSymbol(it) }

    for ((i, indexable) in indexables.withIndex()) {
        val arrayAccess = indexable as? LinkAccessPath.ArrayAccess ?: continue
        val arrayInfo = arrayMetadata[arrayAccess] ?: continue
        if (arrayInfo.sizeKind is IndexableType.Array.SizeKind.Static) {
            continue
        }

        val baseSlot = path.rootSlot()
        val indexSym = indexSyms[i]

        val lenSym: TACSymbol = if (i == 0) {
            // Outermost indexable array: its length is a scalarized storage variable
            val (sym, lenDecls) = lenSymByBaseSlot.getOrPut(baseSlot) {
                val lengthVar = storageVars.filter { sv ->
                    val sort = sv.meta.find(SCALARIZATION_SORT) ?: return@filter false
                    sort != ScalarizationSort.UnscalarizedBuffer &&
                        extractBaseSlot(sort) == baseSlot &&
                        sv.meta.find(BIT_WIDTH) == EVM_BITWIDTH256
                }.singleOrNull()
                    ?: error("Could not find array length storage variable for slot $baseSlot in ${contract.name}")
                lengthVar to setOf(lengthVar)
            }
            allDecls.addAll(lenDecls)
            sym
        } else {
            // Nested array: read length from parent wordmap via slot computation
            val parentPath = indexables[i - 1]
            val parentWordmap = wordmapsByPath[parentPath] ?: rootWordmap ?: continue
            allDecls.add(parentWordmap)
            val outerIndices = indexSyms.take(i)
            val (slotCmds, slotSym) = parentPath.computeSlot(outerIndices.iterator(), "len$i",
                makeHashCmd = cvlHashCmd,
                arrayMetadata = arrayMetadata
            )
            allCmds.addAll(slotCmds.cmds)
            allDecls.addAll(slotCmds.varDecls)
            val lenVar = TACKeyword.TMP(Tag.Bit256, "nestedLen$i")
            allDecls.add(lenVar)
            allCmds.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = lenVar,
                rhs = TACExpr.Select(base = parentWordmap.asSym(), loc = slotSym.asSym(), tag = Tag.Bit256)
            ))
            lenVar
        }

        val tmpVar = TACKeyword.TMP(Tag.Bool, "arrayIndexBound$i")
        allDecls.add(tmpVar)
        allCmds.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(
            lhs = tmpVar,
            rhs = TACExpr.BinRel.Gt(o1 = lenSym.asSym(), o2 = indexSym.asSym())
        ))
        boundConditions.add(tmpVar.asSym())
    }

    val program = if (allCmds.isEmpty()) {
        CVLTACProgram.empty("no dynamic arrays")
    } else {
        wrapWithCVL(
            CommandWithRequiredDecls(allCmds, allDecls),
            "Array length computations for ${contract.name} slot ${path.rootSlot()} ${link.indexValues}"
        ).toProg("arrayBoundsCompute", env)
    }
    return BoundsResult(program, boundConditions)
}

// ──────────────────────────────────────────────────────────────────────────────
// Top-level helper functions for link compilation
// ──────────────────────────────────────────────────────────────────────────────

private val logger = log.Logger(log.LoggerTypes.SPEC)

internal fun storageVarIsContractOrAddress(storageVar: TACStorageSlot) = (storageVar.storageType as? TACStorageType.IntegralType)?.descriptor.let { descr ->
    descr is spec.cvlast.typedescriptors.EVMTypeDescriptor.EVMContractTypeDescriptor || descr == spec.cvlast.typedescriptors.EVMTypeDescriptor.address
}

internal tailrec fun extractBaseSlot(sSort: ScalarizationSort): BigInteger? = when (sSort) {
    is ScalarizationSort.Packed -> extractBaseSlot(sSort.packedStart)
    is ScalarizationSort.Split -> sSort.idx
    ScalarizationSort.UnscalarizedBuffer -> null
}

internal fun formatContractNames(scene: IScene, ids: Collection<BigInteger>): String =
    ids.joinToString(", ") { scene.getContract(it).name }

/**
 * Build an equality disjunction: `lhs == addr1 || lhs == addr2 || ...` for the given [targetIds].
 * Returns the disjunction expression and the set of address variable declarations needed.
 */
internal fun buildAddressDisjunction(
    lhs: TACExpr,
    targetIds: Collection<BigInteger>,
    scene: IScene
): Pair<TACExpr, Set<TACSymbol.Var>> {
    require(targetIds.isNotEmpty()) { "buildAddressDisjunction requires at least one target" }
    val decls = mutableSetOf<TACSymbol.Var>()
    val expr = targetIds.map<BigInteger, TACExpr> { id ->
        val addrSym = scene.getContract(id).addressSym as TACSymbol
        if (addrSym is TACSymbol.Var) {
            decls.add(addrSym)
        }
        TACExpr.BinRel.Eq(lhs, addrSym.asSym())
    }.reduce { acc, eq -> TACExpr.BinBoolOp.LOr(acc, eq) }
    return expr to decls
}

/** Convert a [NonIndexedPath] to a [LinkAccessPath] for use as a grouping/lookup key. */
internal fun NonIndexedPath.toLinkAccessPath(): LinkAccessPath = when (this) {
    is NonIndexedPath.Root -> LinkAccessPath.Root(slot)
    is NonIndexedPath.ArrayAccess -> base.toLinkAccessPath().let { LinkAccessPath.ArrayAccess(it) }
    is NonIndexedPath.StaticArrayAccess -> base.toLinkAccessPath().let { LinkAccessPath.ArrayAccess(it) }
    is NonIndexedPath.MapAccess -> base.toLinkAccessPath().let { LinkAccessPath.MapAccess(it) }
    is NonIndexedPath.StructAccess -> base.toLinkAccessPath().withStructOffset(offset)
}

/** Collects all indexable accesses (array/mapping) in the path, in order from outermost to innermost. */
private fun LinkAccessPath.collectIndexableAccesses(): List<LinkAccessPath> = when (this) {
    is LinkAccessPath.Root -> emptyList()
    is LinkAccessPath.ArrayAccess -> base.collectIndexableAccesses() + this
    is LinkAccessPath.MapAccess -> base.collectIndexableAccesses() + this
    is LinkAccessPath.StructAccess -> base.collectIndexableAccesses()
}
