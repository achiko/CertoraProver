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

package report.callresolution

import analysis.icfg.CallGraphBuilder.CalledContract
import analysis.icfg.SummaryStack
import config.Config
import com.certora.collect.*
import datastructures.stdcollections.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import log.*
import report.TreeViewLocation
import report.TreeViewRepJsonObjectBuilder
import report.TreeViewReportable
import report.put
import scene.IClonedContract
import scene.IContractClassIdentifiers
import scene.ISceneIdentifiers
import utils.*
import vc.data.CoreTACProgram
import vc.data.SnippetCmd.EVMSnippetCmd.StorageSnippet
import vc.data.TACCmd
import java.math.BigInteger

/**
 * Target of a call command, which the tool tries to resolve.
 */
@Treapable
sealed class Callee: TreeViewReportable {

    protected abstract fun MutableMap<String, String>.buildCalleeInfoMap()

    /**
     * Maps attributes ([String]s) to descriptions/details thereof ([String]s). These attributes should include
     * information about the resolution of this [Callee].
     * Each entry is intended to be shown at the report's Call Resolution table.
     */
    val info: Map<String, String> by lazy {
        buildMap {
            buildCalleeInfoMap()
        }
    }

    protected fun MutableMap<String, String>.buildCalleeResolveInfoMap(
        calleeResolveStatus: String,
        vararg toolTips: String?
    ) {
        put("callee resolution", calleeResolveStatus)
        toolTips.filterNotNull().forEachIndexed { tipIdx, tip ->
            put("callee resolution hint (${tipIdx + 1})", tip)
        }
    }

    /**
     * Effectively how this [Callee] should be displayed in the CallResolution table.
     */
    abstract fun toUIString(): String

    /**
     * Callee of an internal call, which is always resolved.
     */
    data class Internal(val methodSignature: String): Callee() {
        override fun MutableMap<String, String>.buildCalleeInfoMap() {}
        override fun toUIString(): String {
            return methodSignature
        }
    }

    /**
     * Callee of an external call.
     */
    sealed class External: Callee()

    data class Resolved(
        override val resolvedSighash: BigInteger, override val methodSignature: String?, val calleeName: String
    ) : External(), WithResolvedSighashInfo {
        override fun MutableMap<String, String>.buildCalleeInfoMap() {
            buildCalleeResolveInfoMap("both callee contract and sighash are resolved")
        }
        override fun toUIString(): String {
            return "$calleeName.$methodSignatureOrSighash"
        }
    }

    data class ResolvedToFallback(
        val calleeName: String
    ) : External() {
        override fun MutableMap<String, String>.buildCalleeInfoMap() {
            buildCalleeResolveInfoMap("both callee contract and sighash are resolved")
        }
        override fun toUIString(): String {
            return "$calleeName.fallback"
        }
    }

    sealed class UnresolvedCall: External()

    private interface WithResolvedSighashInfo {
        val resolvedSighash: BigInteger
        val resolvedSighashHex: String
            get() = resolvedSighash.let { "0x${it.toString(16)}" }
        val methodSignature: String?
        val methodSignatureOrSighash: String
            get() = methodSignature ?: "[sighash=$resolvedSighashHex]"
    }

    private interface WithUnresolvedSighashToolTip {
        val sigResolution: Set<BigInteger?>
        val sighashToolTip: String?
            get() = if (sigResolution.size > 1) {
                "the Prover resolved more than one callee sighash: " +
                    "${sigResolution.joinToString(separator = ", ") { it?.let { "0x${it.toString(16)}" } ?: "fallback" }}; " +
                    "is that useful?"
            } else {
                check(sigResolution.isEmpty()) {
                    "Expected $sigResolution to be empty; size 1 is expected when the sighash is resolved"
                }
                null
            }
    }

    sealed class UnresolvedCalleeAddress: UnresolvedCall() {

        abstract val unresolvedAddressStrategy: CalleeContractResolveStrategy

        val storagePathLabel: String?
            get() = (unresolvedAddressStrategy as? CalleeContractResolveStrategy.Resolved)?.let {
                "${it.storageContractName}.${it.storageDisplayPath}"
            }

        /** The resolved 4-byte selector hex string (e.g., "0x6ffce53"), or `null` if unresolved. */
        val calledSelector: String?
            get() = (this as? WithResolvedSighashInfo)?.resolvedSighashHex

        override val treeViewRepBuilder = TreeViewRepJsonObjectBuilder {
            put(CallResolutionTableReportView.Attribute.NAME(), toUIString())
            put(CallResolutionTableReportView.Attribute.JUMP_TO_DEFINITION(), TreeViewLocation.Empty)
            calledSelector?.let {
                put(CallResolutionTableReportView.Attribute.SELECTOR(), it)
            }
            (unresolvedAddressStrategy as? CalleeContractResolveStrategy.Resolved)?.let { resolved ->
                putJsonObject(CallResolutionTableReportView.Attribute.STORAGE_PATH()) {
                    put("baseContract", resolved.storageContractName)
                    put("path", resolved.storageDisplayPath)
                    putJsonArray("alternativeCallees") {
                        resolved.alternativeCallees.forEach { add(JsonPrimitive(it)) }
                    }
                }
            }
        }

        data class ResolvedSighash(
            override val resolvedSighash: BigInteger,
            override val unresolvedAddressStrategy: CalleeContractResolveStrategy,
            override val methodSignature: String?
        ) : UnresolvedCalleeAddress(), WithResolvedSighashInfo {

            override fun toUIString(): String {
                return "$UNKNOWN.$methodSignatureOrSighash"
            }

            override fun MutableMap<String, String>.buildCalleeInfoMap() {
                buildCalleeResolveInfoMap(
                    "callee contract unresolved; callee sighash resolved",
                    unresolvedAddressStrategy.toolTip
                )
                storagePathLabel?.let { put("storage path", it) }
            }
        }

        data class ResolvedToFallback(
            override val unresolvedAddressStrategy: CalleeContractResolveStrategy,
        ) : UnresolvedCalleeAddress() {

            override fun toUIString(): String {
                return "$UNKNOWN.fallback"
            }

            override fun MutableMap<String, String>.buildCalleeInfoMap() {
                buildCalleeResolveInfoMap(
                    "callee contract unresolved; callee sighash resolved",
                    unresolvedAddressStrategy.toolTip
                )
                storagePathLabel?.let { put("storage path", it) }
            }
        }

        data class UnresolvedSighash(
            override val sigResolution: Set<BigInteger?>,
            override val unresolvedAddressStrategy: CalleeContractResolveStrategy
        ) : UnresolvedCalleeAddress(), WithUnresolvedSighashToolTip {
            override fun toUIString(): String {
                return "$UNKNOWN.$UNKNOWN"
            }

            override fun MutableMap<String, String>.buildCalleeInfoMap() {
                buildCalleeResolveInfoMap("both callee contract and sighash are unresolved", sighashToolTip)
                storagePathLabel?.let { put("storage path", it) }
            }
        }
    }

    /**
     * Resolved new instance of a create/create2 command.
     */
    sealed class ResolvedCreatedContract : UnresolvedCall() {

        abstract val newInstanceOfContract: String

        /**
         * [newInstanceOfContract] is the source contract from which the resolved callee address had been generated from
         * via a Create EVM opcode. That is, the callee address is an instance of [newInstanceOfContract] contract.
         * [methodSignature] is null in case the scene does not have a function whose
         * sighash is [resolvedSighash] in [newInstanceOfContract]. In this case, if [Config.DispatchOnCreated] were enabled,
         * this would have lead to an empty DISPATCHER summary (which the user should avoid).
         */
        data class ResolvedSighash(
            override val newInstanceOfContract: String,
            override val resolvedSighash: BigInteger,
            override val methodSignature: String?
        ) : ResolvedCreatedContract(), WithResolvedSighashInfo {

            override fun MutableMap<String, String>.buildCalleeInfoMap() {
                buildCalleeResolveInfoMap("callee contract resolved to a newly created instance of $newInstanceOfContract, callee sighash resolved")
            }

            override fun toUIString(): String {
                return "$newInstanceOfContract.$methodSignatureOrSighash"
            }
        }

        data class UnresolvedSighash(
            override val newInstanceOfContract: String,
            override val sigResolution: Set<BigInteger?>,
        ) : ResolvedCreatedContract(), WithUnresolvedSighashToolTip {

            override fun toUIString(): String {
                return "$newInstanceOfContract.$UNKNOWN"
            }

            override fun MutableMap<String, String>.buildCalleeInfoMap() {
                buildCalleeResolveInfoMap(
                    "callee contract resolved to a newly created instance of $newInstanceOfContract, " +
                        "callee sighash unresolved", sighashToolTip
                )
            }
        }
    }

    /**
     * Is there anything the user can do to make the Prover resolve the callee contract?
     */
    @KSerializable
    @Treapable
    sealed class CalleeContractResolveStrategy : AmbiSerializable {

        companion object {
            operator fun invoke(
                storageLoadSnippet: StorageSnippet.LoadSnippet?,
                alternativeCallees: List<String>,
                getContract: (BigInteger) -> IContractClassIdentifiers
            ): CalleeContractResolveStrategy =
                if (storageLoadSnippet != null) {
                    val contractName = getContract(storageLoadSnippet.contractInstance).name
                    val pathStr = storageLoadSnippet.displayPath.toPartiallyIndexedString()
                    Resolved(contractName, pathStr, alternativeCallees)
                } else {
                    Unavailable
                }
        }

        /**
         * Suggests resolving this call via a spec-level `links` block, or `null` if no storage path is known.
         */
        val toolTip: String?
            get() {
                val resolved = this as? Resolved ?: return null
                val targets = if (resolved.alternativeCallees.isNotEmpty()) {
                    ". Possible targets: ${resolved.alternativeCallees.joinToString(", ")}"
                } else {
                    ""
                }
                return "To resolve this call, add a 'links' entry for '${resolved.storageDisplayPath}' " +
                    "in contract '${resolved.storageContractName}'$targets " +
                    "(use 'using' aliases for contract names)"
            }

        @KSerializable
        object Unavailable : CalleeContractResolveStrategy() {
            fun readResolve(): Any = Unavailable
            override fun hashCode() = hashObject(this)
        }

        @KSerializable
        data class Resolved(
            val storageContractName: String,
            val storageDisplayPath: String,
            val alternativeCallees: List<String>
        ) : CalleeContractResolveStrategy()
    }

    sealed class ResolvedCalleeAddress: UnresolvedCall() {
        abstract val contractId: BigInteger
        abstract val contractName: String

        /**
         * [resolvedSighash] does not have a corresponding method in [contractName].
         * This might happen, e.g., when the user linked the wrong contract.
         */
        data class ResolvedSighashNotInScene(
            override val contractId: BigInteger,
            override val contractName: String,
            override val resolvedSighash: BigInteger,
            override val methodSignature: String?,
            val unresolvedAddressStrategy: CalleeContractResolveStrategy
        ) : ResolvedCalleeAddress(), WithResolvedSighashInfo {

            override fun toUIString(): String {
                return "$contractName.$methodSignatureOrSighash"
            }

            override fun MutableMap<String, String>.buildCalleeInfoMap() {
                buildCalleeResolveInfoMap(
                    "both callee contract and sighash resolved, but the contract does not have " +
                        "a function with that sighash",
                    unresolvedAddressStrategy.toolTip
                )
            }
        }

        /**
         * [sigResolution] does not contain exactly one sighash, thus
         * the callee sighash is unresolved.
         */
        data class UnresolvedSighash(
            override val contractId: BigInteger,
            override val contractName: String,
            override val sigResolution: Set<BigInteger?>
        ) : ResolvedCalleeAddress(), WithUnresolvedSighashToolTip {

            override fun toUIString(): String {
                return "$contractName.$UNKNOWN"
            }

            override fun MutableMap<String, String>.buildCalleeInfoMap() {
                buildCalleeResolveInfoMap(
                    "callee contract resolved; callee sighash unresolved",
                    sighashToolTip
                )
            }
        }
    }

    override val treeViewRepBuilder = TreeViewRepJsonObjectBuilder {
        put(CallResolutionTableReportView.Attribute.NAME(), toUIString())
        put(
            CallResolutionTableReportView.Attribute.JUMP_TO_DEFINITION(),
            TreeViewLocation.Empty  // TODO - pass real location
        )
    }

    companion object {

        const val UNKNOWN = "[?]"

        /**
         * [methodsBlockSignature] the invoked method's signature as appears in the methods block.
         */
        operator fun invoke(
            summStart: SummaryStack.SummaryStart,
            prog: CoreTACProgram,
            scene: ISceneIdentifiers,
            ruleName: String,
            methodsBlockSignature: String?
        ): List<Callee> {

            /**
             * Iterates over [prog] to find a [StorageSnippet.LoadSnippet], whose underlying storage slot matches the
             * storage read id of [calleeResolution].
             */
            fun getMatchingLoadCmd(calleeResolution: CalledContract?): StorageSnippet.LoadSnippet? =
                if (calleeResolution is CalledContract.WithStorageReadId) {
                    val storageReadId: Int = calleeResolution.storageReadId
                    prog.analysisCache.graph.commands.firstNotNullOfOrNull {
                        if (it.cmd is TACCmd.Simple.AnnotationCmd
                            && it.cmd.annot.v is StorageSnippet.LoadSnippet
                            && it.cmd.annot.v.linkableStorageReadId is StorageSnippet.LoadSnippet.Id
                            && it.cmd.annot.v.linkableStorageReadId.id == storageReadId
                        ) {
                            it.cmd.annot.v
                        } else {
                            null
                        }
                    }
                } else {
                    null
                }

            /**
             * Returns a method signature whose sighash is [resolvedSighash] and exists in any contract in [contracts].
             */
            fun resolveMethodSignatureInContracts(
                contracts: List<IContractClassIdentifiers>?,
                resolvedSighash: BigInteger?
            ): String? = contracts?.firstOrNull()?.methods?.get(resolvedSighash)?.soliditySignature

            /**
             * Returns a strategy to resolve the callee address using linking.
             */
            fun resolveStrategy(
                contracts: List<IContractClassIdentifiers>?,
                calleeResolution: CalledContract?
            ): CalleeContractResolveStrategy {
                val storageLoadSnippet = getMatchingLoadCmd(calleeResolution)
                val alternativeCallees = contracts?.mapNotNull {
                    if (it !is IClonedContract) {
                        it.name
                    } else {
                        null
                    }
                }.orEmpty()
                return CalleeContractResolveStrategy(
                    storageLoadSnippet,
                    alternativeCallees
                ) { scene.getContract(it) }
            }

            fun createUnresolvedContractAddressCallee(
                resolvedSighash: BigInteger?,
                calleeResolution: CalledContract?,
                sigResolution: Set<BigInteger?>,
                rulePrefixMsg: String,
                strategyOverride: CalleeContractResolveStrategy? = null
            ): Callee {
                val contracts = resolvedSighash?.let { sighash -> scene.getContracts(sighash) }
                val unresolvedAddressStrategy = strategyOverride ?: resolveStrategy(
                    contracts,
                    calleeResolution
                )
                return when {
                    sigResolution.size != 1 -> {
                        Logger.regression { "$rulePrefixMsg Callee contract unresolved, callee sighash unresolved" }
                        UnresolvedCalleeAddress.UnresolvedSighash(
                            sigResolution, unresolvedAddressStrategy
                        )
                    }

                    else -> {
                        if (resolvedSighash == null) {
                            UnresolvedCalleeAddress.ResolvedToFallback(unresolvedAddressStrategy)
                        } else {
                            val methodSignature =
                                methodsBlockSignature ?: resolveMethodSignatureInContracts(
                                    contracts,
                                    resolvedSighash
                                )
                            Logger.regression { "$rulePrefixMsg Callee contract unresolved, callee sighash resolved, method signature is ${methodSignature ?: UNKNOWN}" }
                            UnresolvedCalleeAddress.ResolvedSighash(
                                resolvedSighash, unresolvedAddressStrategy, methodSignature
                            )
                        }
                    }
                }
            }

            return when (summStart) {
                is SummaryStack.SummaryStart.External -> {
                    val callNode = summStart.callNode
                    val callTarget = callNode.callTarget
                    val sigResolution = callNode.sigResolution
                    val resolvedSighash = sigResolution.singleOrNull()
                    val rulePrefixMsg = "Rule $ruleName:"
                    callTarget.map { calleeResolution ->
                        when (calleeResolution) {
                            is CalledContract.WithContractId -> {
                                val resolvedAddress = calleeResolution.contractId
                                // In the FullyResolved case, the callee might get invalidated, so it is possible it
                                // will be missing from the scene. See CallTarget.Invalidated.
                                val contract = scene.getContractOrNull(resolvedAddress)
                                when {
                                    sigResolution.size != 1 -> {
                                        Logger.regression { "$rulePrefixMsg Callee contract resolved to ${contract?.name ?: UNKNOWN}, callee sighash unresolved" }
                                        ResolvedCalleeAddress.UnresolvedSighash(
                                            resolvedAddress, contract?.name ?: UNKNOWN, sigResolution
                                        )
                                    }
                                    else -> {
                                        if (resolvedSighash == null) {
                                            Logger.regression { "$rulePrefixMsg Callee contract resolved to ${contract?.name ?: UNKNOWN}, callee sighash resolved to the fallback (or receive) function" }
                                            ResolvedToFallback(contract?.name ?: UNKNOWN)
                                        } else {
                                            val methodSignature = methodsBlockSignature ?: contract?.getMethodOrFallback(
                                                resolvedSighash
                                            )?.soliditySignature
                                            if (methodSignature != null) {
                                                Logger.regression { "$rulePrefixMsg Callee contract resolved to ${contract?.name ?: UNKNOWN}, callee sighash resolved, method signature is $methodSignature" }
                                                Resolved(resolvedSighash, methodSignature, contract?.name ?: UNKNOWN)
                                            } else {
                                                val contracts =
                                                    scene.getContracts(resolvedSighash)
                                                val soliditySignature = resolveMethodSignatureInContracts(
                                                    contracts,
                                                    resolvedSighash
                                                )
                                                val unresolvedAddressStrategy = resolveStrategy(contracts, calleeResolution)
                                                Logger.regression { "$rulePrefixMsg Callee contract resolved to ${contract?.name ?: UNKNOWN}, callee sighash resolved, method signature is ${soliditySignature ?: UNKNOWN}" }
                                                ResolvedCalleeAddress.ResolvedSighashNotInScene(
                                                    resolvedAddress,
                                                    contract?.name ?: UNKNOWN,
                                                    resolvedSighash,
                                                    soliditySignature,
                                                    unresolvedAddressStrategy
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            is CalledContract.CreatedReference.Resolved -> {
                                val srcContractOfResolvedCreatedAddress = scene.getContract(calleeResolution.tgtConntractId)
                                when (resolvedSighash) {
                                    null -> {
                                        Logger.regression { "$rulePrefixMsg Callee contract resolved to a new instance of ${srcContractOfResolvedCreatedAddress.name}, callee sighash unresolved" }
                                        ResolvedCreatedContract.UnresolvedSighash(
                                            srcContractOfResolvedCreatedAddress.name,
                                            sigResolution
                                        )
                                    }

                                    else -> {
                                        val methodSignature =
                                            methodsBlockSignature ?: srcContractOfResolvedCreatedAddress.getMethodBySigHash(
                                                resolvedSighash
                                            )?.soliditySignature
                                        Logger.regression {
                                            "$rulePrefixMsg Callee contract resolved to a new instance of ${srcContractOfResolvedCreatedAddress.name}," +
                                                " callee sighash resolved, method signature is ${methodSignature ?: UNKNOWN}"
                                        }
                                        ResolvedCreatedContract.ResolvedSighash(
                                            srcContractOfResolvedCreatedAddress.name,
                                            resolvedSighash,
                                            methodSignature
                                        )
                                    }
                                }
                            }
                            is CalledContract.UnresolvedImmutable -> {
                                val contractName = scene.getContract(calleeResolution.hostContractId).name
                                val alternativeCallees = resolvedSighash?.let { scene.getContracts(it) }
                                    ?.mapNotNull { if (it !is IClonedContract) { it.name } else { null } }
                                    .orEmpty()
                                val strategy = CalleeContractResolveStrategy.Resolved(
                                    contractName,
                                    calleeResolution.immutableName,
                                    alternativeCallees
                                )
                                createUnresolvedContractAddressCallee(resolvedSighash, calleeResolution, sigResolution, rulePrefixMsg, strategy)
                            }
                            is CalledContract.UnresolvedRead,
                            is CalledContract.CreatedReference.Unresolved,
                            is CalledContract.Unresolved,
                            is CalledContract.SymbolicInput,
                            is CalledContract.InternalFunctionSummaryOutput,
                            is CalledContract.SymbolicOutput -> {
                                createUnresolvedContractAddressCallee(resolvedSighash, calleeResolution, sigResolution, rulePrefixMsg)
                            }
                        }
                    }
                }
                is SummaryStack.SummaryStart.Internal -> {
                    val methodSignature = "(internal) " + summStart.methodSignature.toString()
                    Logger.regression { "Callee contract resolved, callee sighash resolved, method signature is $methodSignature" }
                    listOf(Internal(methodSignature = methodSignature))
                }
            }
        }
    }
}
