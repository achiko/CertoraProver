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

package sbf.callgraph

import cvlr.CvlrFunctions
import sbf.cfg.Value
import sbf.disassembler.SbfRegister
import datastructures.stdcollections.*
import sbf.domains.MemSummaryArgument
import sbf.domains.MemSummaryArgumentType
import sbf.domains.MemorySummaries
import sbf.domains.MemorySummary
import utils.*

private const val CVT_save_scratch_registers = "CVT_save_scratch_registers"
private const val CVT_restore_scratch_registers = "CVT_restore_scratch_registers"
private const val CVT_nondet_account_info = "CVT_nondet_account_info"
private const val CVT_nondet_solana_account_space = "CVT_nondet_solana_account_space"
private const val CVT_mask_64 = "CVT_mask_64"
private const val CVT_is_account_written = "CVT_is_account_written"

sealed class CVTFunction(val function: ExternalFunction) {
    data class Core(val value: CVTCore): CVTFunction(value.function)
    data class Calltrace(val value: CVTCalltrace): CVTFunction(value.function)
    data class Nondet(val value: CVTNondet): CVTFunction(value.function)
    data class NativeInt(val value: CVTNativeInt): CVTFunction(value.function)
    data class U128Intrinsics(val value: CVTU128Intrinsics): CVTFunction(value.function)
    data class I128Intrinsics(val value: CVTI128Intrinsics): CVTFunction(value.function)

    companion object: ExternalLibrary<CVTFunction> {

        override fun from(name: String): CVTFunction? {
            CVTCore.from(name)?.run {
                return Core(this)
            }
            CVTCalltrace.from(name)?.run {
                return Calltrace(this)
            }
            CVTNondet.from(name)?.run {
                return Nondet(this)
            }
            CVTNativeInt.from(name)?.run {
                return NativeInt(this)
            }
            CVTU128Intrinsics.from(name)?.run {
                return U128Intrinsics(this)
            }
            CVTI128Intrinsics.from(name)?.run {
                return I128Intrinsics(this)
            }
            return null
        }

        override fun addSummaries(memSummaries: MemorySummaries) {
            CVTCore.addSummaries(memSummaries)
            CVTCalltrace.addSummaries(memSummaries)
            CVTNondet.addSummaries(memSummaries)
            CVTNativeInt.addSummaries(memSummaries)
            CVTU128Intrinsics.addSummaries(memSummaries)
            CVTI128Intrinsics.addSummaries(memSummaries)
        }
    }
}

enum class CVTCore(val function: ExternalFunction) {
    ASSUME(ExternalFunction(CvlrFunctions.CVT_assume, setOf(), setOf(Value.Reg(SbfRegister.R1)))),
    ASSERT(ExternalFunction(CvlrFunctions.CVT_assert, setOf(), setOf(Value.Reg(SbfRegister.R1)))),
    SATISFY(ExternalFunction(CvlrFunctions.CVT_satisfy, setOf(), setOf(Value.Reg(SbfRegister.R1)))),
    SANITY(ExternalFunction(CvlrFunctions.CVT_sanity, setOf(), setOf(Value.Reg(SbfRegister.R1)))),
    SAVE_SCRATCH_REGISTERS(ExternalFunction(CVT_save_scratch_registers,
        writeRegister = setOf(),
        readRegisters = SbfRegister.registersToSaveOrRestore.mapToSet { Value.Reg(it) })),
    RESTORE_SCRATCH_REGISTERS(ExternalFunction(CVT_restore_scratch_registers,
        writeRegister = SbfRegister.registersToSaveOrRestore.mapToSet { Value.Reg(it) },
        readRegisters = setOf())),
    NONDET_SOLANA_ACCOUNT_SPACE(ExternalFunction(CVT_nondet_solana_account_space,
        setOf(Value.Reg(SbfRegister.R0)),
        listOf(SbfRegister.R1).map{ Value.Reg(it)}.toSet())
    ),
    ALLOC_SLICE(ExternalFunction(CvlrFunctions.CVT_alloc_slice,
        setOf(Value.Reg(SbfRegister.R0)),
        listOf(SbfRegister.R1, SbfRegister.R2, SbfRegister.R3).map{ Value.Reg(it)}.toSet())
    ),
    IS_ACCOUNT_WRITTEN(ExternalFunction(CVT_is_account_written,
        setOf(Value.Reg(SbfRegister.R0)),
        listOf(SbfRegister.R1).map{ Value.Reg(it)}.toSet())
    ),
    // For tests
    MASK_64(ExternalFunction(CVT_mask_64,
        setOf(Value.Reg(SbfRegister.R0)),
        listOf(SbfRegister.R1).map{ Value.Reg(it)}.toSet())
    ),
    /** Deprecated **/
    NONDET_ACCOUNT_INFO(ExternalFunction(CVT_nondet_account_info, setOf(),setOf(Value.Reg(SbfRegister.R1))));

    companion object: ExternalLibrary<CVTCore>  {
        private val nameMap = CVTCore.entries.associateBy { it.function.name }

        override fun from(name: String) = nameMap[name]
        override fun addSummaries(memSummaries: MemorySummaries) {
            for (f in nameMap.values) {
                when (f) {
                    // No summaries
                    ASSERT, ASSUME, SATISFY, SANITY,
                    RESTORE_SCRATCH_REGISTERS, SAVE_SCRATCH_REGISTERS -> {}
                    // Summaries
                    NONDET_SOLANA_ACCOUNT_SPACE -> {
                        val summaryArgs = listOf(MemSummaryArgument(r = SbfRegister.R0, type = MemSummaryArgumentType.PTR_INPUT))
                        memSummaries.addSummary(f.function.name, MemorySummary(summaryArgs))
                    }
                    ALLOC_SLICE -> {
                        // This summary is sound, but it will case PTA errors (because of the type `ANY`). Thus, it should NOT be used by the pointer domain.
                        // The reason why the argument type is `ANY` is that the memory region is not fixed.
                        val summaryArgs = listOf(MemSummaryArgument(r = SbfRegister.R0, type = MemSummaryArgumentType.ANY))
                        memSummaries.addSummary(f.function.name, MemorySummary(summaryArgs))
                    }
                    IS_ACCOUNT_WRITTEN -> {
                        val summaryArgs = listOf(MemSummaryArgument(r = SbfRegister.R0, type = MemSummaryArgumentType.NUM))
                        memSummaries.addSummary(f.function.name, MemorySummary(summaryArgs))
                    }
                    MASK_64 -> {
                        val summaryArgs = listOf(MemSummaryArgument(r = SbfRegister.R0, type = MemSummaryArgumentType.NUM))
                        memSummaries.addSummary(f.function.name, MemorySummary(summaryArgs))
                    }
                    // Summary currently provided by configuration file
                    NONDET_ACCOUNT_INFO -> {}
                }
            }
        }
    }
}

enum class CVTCalltrace(val function: ExternalFunction,
                        // From all registers (r1-r5), which registers contain the string or strings passed to the function
                        val strings: Set<CalltraceStr>,
                        val consumeStickyTag: Boolean = false) {
    PRINT_U64_1(CexPrintValue(CvlrFunctions.CVT_calltrace_print_u64_1, 3), setOf(CalltraceStr(SbfRegister.R1))),
    PRINT_U64_2(CexPrintValue(CvlrFunctions.CVT_calltrace_print_u64_2, 4), setOf(CalltraceStr(SbfRegister.R1))),
    PRINT_U64_3(CexPrintValue(CvlrFunctions.CVT_calltrace_print_u64_3, 5), setOf(CalltraceStr(SbfRegister.R1))),
    PRINT_U128(CexPrintValue(CvlrFunctions.CVT_calltrace_print_u128, 4), setOf(CalltraceStr(SbfRegister.R1))),
    PRINT_I64_1(CexPrintValue(CvlrFunctions.CVT_calltrace_print_i64_1, 3), setOf(CalltraceStr(SbfRegister.R1))),
    PRINT_I64_2(CexPrintValue(CvlrFunctions.CVT_calltrace_print_i64_2, 4), setOf(CalltraceStr(SbfRegister.R1))),
    PRINT_I64_3(CexPrintValue(CvlrFunctions.CVT_calltrace_print_i64_3, 5), setOf(CalltraceStr(SbfRegister.R1))),
    PRINT_I128(CexPrintValue(CvlrFunctions.CVT_calltrace_print_i128, 4), setOf(CalltraceStr(SbfRegister.R1))),
    PRINT_U64_AS_FIXED(CexPrintValue(CvlrFunctions.CVT_calltrace_print_u64_as_fixed, 4), setOf(CalltraceStr(SbfRegister.R1))),
    PRINT_U64_AS_DECIMAL(CexPrintValue(CvlrFunctions.CVT_calltrace_print_u64_as_decimal, 4), setOf(CalltraceStr(SbfRegister.R1))),
    PRINT_TAG(ExternalFunction(CvlrFunctions.CVT_calltrace_print_tag, setOf(),
              listOf(SbfRegister.R1, SbfRegister.R2).map{ Value.Reg(it)}.toSet()),
              setOf(CalltraceStr(SbfRegister.R1))),
    PRINT_LOCATION(ExternalFunction(CvlrFunctions.CVT_calltrace_print_location, setOf(),
                   listOf(SbfRegister.R1, SbfRegister.R2, SbfRegister.R3).map{ Value.Reg(it)}.toSet()),
                   setOf(CalltraceStr(SbfRegister.R1))),
    ATTACH_LOCATION(ExternalFunction(CvlrFunctions.CVT_calltrace_attach_location, setOf(),
                    listOf(SbfRegister.R1, SbfRegister.R2, SbfRegister.R3).map{ Value.Reg(it)}.toSet()),
                    setOf(CalltraceStr(SbfRegister.R1))),
    SCOPE_START(ExternalFunction(CvlrFunctions.CVT_calltrace_scope_start, setOf(),
        listOf(SbfRegister.R1, SbfRegister.R2).map{ Value.Reg(it)}.toSet()),
        setOf(CalltraceStr(SbfRegister.R1))),
    SCOPE_END(ExternalFunction(CvlrFunctions.CVT_calltrace_scope_end, setOf(),
        listOf(SbfRegister.R1, SbfRegister.R2).map{ Value.Reg(it)}.toSet()),
        setOf(CalltraceStr(SbfRegister.R1))),
    PRINT_STRING(CexPrintValue(CvlrFunctions.CVT_calltrace_print_string, 4),
                 setOf(CalltraceStr(SbfRegister.R1), CalltraceStr(SbfRegister.R3))),
    /**
     *  `r1`-`r4` contains the four words of u64 bytes of the pubkey (from lowest to highest).
     *  This intrinsics does not have any string argument.
     */
    PRINT_PUBKEY(ExternalFunction(
        CvlrFunctions.CVT_calltrace_print_pubkey, setOf(),
        listOf(SbfRegister.R1, SbfRegister.R2, SbfRegister.R3, SbfRegister.R4).map{ Value.Reg(it) }.toSet()
        ),
        setOf(),
        consumeStickyTag = true
    ),
    RULE_LOCATION(ExternalFunction(CvlrFunctions.CVT_rule_location, setOf(),
                  listOf(SbfRegister.R1, SbfRegister.R2, SbfRegister.R3).map{ Value.Reg(it)}.toSet()),
                  setOf(CalltraceStr(SbfRegister.R1))),
    /**
     * Carries a tag that will be consumed by the next CVTCalltrace function requiring a tag.
     * Used as a workaround when a CVTCalltrace function needs more than 5 SBF registers:
     * the tag is passed separately, freeing up two registers for other arguments.
     **/
    STICKY_TAG(ExternalFunction(CvlrFunctions.CVT_calltrace_sticky_tag, setOf(),
        listOf(SbfRegister.R1, SbfRegister.R2).map{ Value.Reg(it) }.toSet()),
        setOf(CalltraceStr(SbfRegister.R1)));

    companion object: ExternalLibrary<CVTCalltrace>  {
        private val nameMap = CVTCalltrace.entries.associateBy { it.function.name }

        override fun from(name: String) = nameMap[name]
        override fun addSummaries(memSummaries: MemorySummaries) {
            // No summaries
        }
    }
}

private data class CexPrintValue(override val name: String, val numArgs: Byte):
    ExternalFunction(name,
        setOf(Value.Reg(SbfRegister.R0)),
        listOf(SbfRegister.R1, SbfRegister.R2,
            SbfRegister.R3, SbfRegister.R4,
            SbfRegister.R5).filter { it.value <= numArgs }.map{ Value.Reg(it)}.toSet())

/**
 * A literal string is represented by two registers:
 * [string] pointing to the address that contains the string and [len] the number of bytes.
 **/
data class CalltraceStr(val string: Value.Reg, val len: Value.Reg) {
    constructor(reg: SbfRegister): this(Value.Reg(reg), Value.Reg(SbfRegister.getByValue((reg.ordinal+1).toByte())))
}

enum class CVTU128Intrinsics(val function: ExternalFunction) {
    /// Follows standard SBF calling convention. Used by CVLR
    U128_NONDET(ExternalFunction(CvlrFunctions.CVT_nondet_u128,
        setOf(),
        setOf(Value.Reg(SbfRegister.R1)))),
    /// Follows Prover-Return-By-Heap calling convention. Call site must properly restore r0. Do not call directly from Rust.
    /// They can only be inserted by the Solana front-end
    U128_LEQ(ExternalFunction(CvlrFunctions.CVT_u128_leq,
        setOf(Value.Reg(SbfRegister.R0)),
        listOf(SbfRegister.R1, SbfRegister.R2, SbfRegister.R3, SbfRegister.R4).map{ Value.Reg(it)}.toSet())),
    U128_LT(ExternalFunction(CvlrFunctions.CVT_u128_lt,
        setOf(Value.Reg(SbfRegister.R0)),
        listOf(SbfRegister.R1, SbfRegister.R2, SbfRegister.R3, SbfRegister.R4).map{ Value.Reg(it)}.toSet())),
    U128_WRAPPING_SUBTRACTION(ExternalFunction(CvlrFunctions.CVT_u128_wrapping_sub,
        setOf(Value.Reg(SbfRegister.R0)),
        listOf(SbfRegister.R1, SbfRegister.R2, SbfRegister.R3, SbfRegister.R4).map{ Value.Reg(it)}.toSet())),
    U128_WRAPPING_ADDITION(ExternalFunction(CvlrFunctions.CVT_u128_wrapping_add,
        setOf(Value.Reg(SbfRegister.R0)),
        listOf(SbfRegister.R1, SbfRegister.R2, SbfRegister.R3, SbfRegister.R4).map{ Value.Reg(it)}.toSet())),
    /// Unused intrinsics
    U128_CEIL_DIV(ExternalFunction(CvlrFunctions.CVT_u128_ceil_div,
        setOf(),
        listOf(SbfRegister.R1, SbfRegister.R2, SbfRegister.R3, SbfRegister.R4, SbfRegister.R5).map{ Value.Reg(it)}.toSet()));


    companion object: ExternalLibrary<CVTU128Intrinsics>  {
        private val nameMap = CVTU128Intrinsics.entries.associateBy { it.function.name }

        override fun from(name: String) = nameMap[name]

        override fun addSummaries(memSummaries: MemorySummaries) {
            for (f in nameMap.values) {
                when (f) {
                    U128_LEQ, U128_LT -> {
                        val summaryArgs = listOf(
                            MemSummaryArgument(r = SbfRegister.R0, allocatedSpace = 16UL, type = MemSummaryArgumentType.PTR_HEAP),
                            // Used to restore r0: this type is dynamic since the value of *(r0+0) will be the value of r0
                            // before the call to the intrinsics. Thus, we mark its type conservatively as ANY but some analyses
                            // will need to be more precise.
                            MemSummaryArgument(r = SbfRegister.R0, offset = 0 , width = 8, type = MemSummaryArgumentType.ANY),
                            // Result of the comparison
                            MemSummaryArgument(r = SbfRegister.R0, offset = 8 , width = 8, type = MemSummaryArgumentType.NUM),
                        )
                        memSummaries.addSummary(f.function.name, MemorySummary(summaryArgs))
                    }
                    U128_CEIL_DIV, U128_NONDET  -> {
                        val summaryArgs = listOf(
                            MemSummaryArgument(r = SbfRegister.R1, offset = 0 , width = 8, type = MemSummaryArgumentType.NUM),
                            MemSummaryArgument(r = SbfRegister.R1, offset = 8 , width = 8, type = MemSummaryArgumentType.NUM)
                        )
                        memSummaries.addSummary(f.function.name, MemorySummary(summaryArgs))
                    }
                    U128_WRAPPING_SUBTRACTION, U128_WRAPPING_ADDITION -> {
                        val summaryArgs = listOf(
                            MemSummaryArgument(r = SbfRegister.R0, allocatedSpace = 24UL, type = MemSummaryArgumentType.PTR_HEAP),
                            // Used to restore r0: this type is dynamic since the value of *(r0+0) will be the value of r0
                            // before the call to the intrinsics. Thus, we mark its type conservatively as ANY but some analyses
                            // will need to be more precise.
                            MemSummaryArgument(r = SbfRegister.R0, offset = 0 , width = 8, type = MemSummaryArgumentType.ANY),
                            // resLow of the u128 arithmetic operation
                            MemSummaryArgument(r = SbfRegister.R0, offset = 8 , width = 8, type = MemSummaryArgumentType.NUM),
                            // resHigh of the u128 arithmetic operation
                            MemSummaryArgument(r = SbfRegister.R0, offset = 16 , width = 8, type = MemSummaryArgumentType.NUM)
                        )
                        memSummaries.addSummary(f.function.name, MemorySummary(summaryArgs))
                    }
                }
            }
        }
    }
}

enum class CVTI128Intrinsics(val function: ExternalFunction) {
    I128_NONDET(ExternalFunction(CvlrFunctions.CVT_nondet_i128, setOf(), setOf(Value.Reg(SbfRegister.R1))));

    companion object: ExternalLibrary<CVTI128Intrinsics>  {
        private val nameMap = CVTI128Intrinsics.entries.associateBy { it.function.name }

        override fun from(name: String) = nameMap[name]

        override fun addSummaries(memSummaries: MemorySummaries) {
            for (f in nameMap.values) {
                when (f) {
                    I128_NONDET  -> {
                        val summaryArgs = listOf(MemSummaryArgument(r = SbfRegister.R1, offset = 0 , width = 8, type = MemSummaryArgumentType.NUM),
                            MemSummaryArgument(r = SbfRegister.R1, offset = 8 , width = 8, type = MemSummaryArgumentType.NUM))
                        memSummaries.addSummary(f.function.name, MemorySummary(summaryArgs))
                    }
                }
            }
        }
    }
}

enum class CVTNativeInt(val function: ExternalFunction) {
    NATIVEINT_EQ(ExternalFunction(CvlrFunctions.CVT_nativeint_u64_eq,
        setOf(Value.Reg(SbfRegister.R0)),
        listOf(SbfRegister.R1, SbfRegister.R2).map{ Value.Reg(it)}.toSet())
    ),
    NATIVEINT_LT(ExternalFunction(CvlrFunctions.CVT_nativeint_u64_lt,
        setOf(Value.Reg(SbfRegister.R0)),
        listOf(SbfRegister.R1, SbfRegister.R2).map{ Value.Reg(it)}.toSet())
    ),
    NATIVEINT_LE(ExternalFunction(CvlrFunctions.CVT_nativeint_u64_le,
        setOf(Value.Reg(SbfRegister.R0)),
        listOf(SbfRegister.R1, SbfRegister.R2).map{ Value.Reg(it)}.toSet())
    ),
    NATIVEINT_SLT(ExternalFunction(CvlrFunctions.CVT_nativeint_u64_slt,
        setOf(Value.Reg(SbfRegister.R0)),
        listOf(SbfRegister.R1, SbfRegister.R2).map{ Value.Reg(it)}.toSet())
    ),
    NATIVEINT_SLE(ExternalFunction(CvlrFunctions.CVT_nativeint_u64_sle,
        setOf(Value.Reg(SbfRegister.R0)),
        listOf(SbfRegister.R1, SbfRegister.R2).map{ Value.Reg(it)}.toSet())
    ),
    NATIVEINT_ADD(ExternalFunction(CvlrFunctions.CVT_nativeint_u64_add,
        setOf(Value.Reg(SbfRegister.R0)),
        listOf(SbfRegister.R1, SbfRegister.R2).map{ Value.Reg(it)}.toSet())
    ),
    NATIVEINT_SUB(ExternalFunction(CvlrFunctions.CVT_nativeint_u64_sub,
        setOf(Value.Reg(SbfRegister.R0)),
        listOf(SbfRegister.R1, SbfRegister.R2).map{ Value.Reg(it)}.toSet())
    ),
    NATIVEINT_MUL(ExternalFunction(CvlrFunctions.CVT_nativeint_u64_mul,
        setOf(Value.Reg(SbfRegister.R0)),
        listOf(SbfRegister.R1, SbfRegister.R2).map{ Value.Reg(it)}.toSet())
    ),
    NATIVEINT_DIV(ExternalFunction(CvlrFunctions.CVT_nativeint_u64_div,
        setOf(Value.Reg(SbfRegister.R0)),
        listOf(SbfRegister.R1, SbfRegister.R2).map{ Value.Reg(it)}.toSet())
    ),
    NATIVEINT_CEIL_DIV(ExternalFunction(CvlrFunctions.CVT_nativeint_u64_div_ceil,
        setOf(Value.Reg(SbfRegister.R0)),
        listOf(SbfRegister.R1, SbfRegister.R2).map{ Value.Reg(it)}.toSet())
    ),
    NATIVEINT_MULDIV(ExternalFunction(CvlrFunctions.CVT_nativeint_u64_muldiv,
        setOf(Value.Reg(SbfRegister.R0)),
        listOf(SbfRegister.R1, SbfRegister.R2, SbfRegister.R3).map{ Value.Reg(it)}.toSet())
    ),
    NATIVEINT_MULDIV_CEIL(ExternalFunction(CvlrFunctions.CVT_nativeint_u64_muldiv_ceil,
        setOf(Value.Reg(SbfRegister.R0)),
        listOf(SbfRegister.R1, SbfRegister.R2, SbfRegister.R3).map{ Value.Reg(it)}.toSet())
    ),
    NATIVEINT_NONDET(ExternalFunction(CvlrFunctions.CVT_nativeint_u64_nondet,
        setOf(Value.Reg(SbfRegister.R0)),
        listOf(SbfRegister.R1, SbfRegister.R2).map{ Value.Reg(it)}.toSet())
    ),
    NATIVEINT_FROM_U128(ExternalFunction(CvlrFunctions.CVT_nativeint_u64_from_u128,
        setOf(Value.Reg(SbfRegister.R0)),
        listOf(SbfRegister.R1, SbfRegister.R2).map{ Value.Reg(it)}.toSet())
    ),
    NATIVEINT_FROM_U256(ExternalFunction(CvlrFunctions.CVT_nativeint_u64_from_u256,
        setOf(Value.Reg(SbfRegister.R0)),
        listOf(SbfRegister.R1, SbfRegister.R2, SbfRegister.R3, SbfRegister.R4).map{ Value.Reg(it)}.toSet())
    ),
    NATIVE_INTO_U128(ExternalFunction(CvlrFunctions.CVT_nativeint_u64_into_u128,
        setOf(Value.Reg(SbfRegister.R0)),
        listOf(SbfRegister.R1, SbfRegister.R2).map{ Value.Reg(it)}.toSet())),
    NATIVEINT_U64_MAX(ExternalFunction(CvlrFunctions.CVT_nativeint_u64_u64_max,
        setOf(Value.Reg(SbfRegister.R0)),
        setOf())
    ),
    NATIVEINT_U128_MAX(ExternalFunction(CvlrFunctions.CVT_nativeint_u64_u128_max,
        setOf(Value.Reg(SbfRegister.R0)),
        setOf())
    ),
    NATIVEINT_U256_MAX(ExternalFunction(CvlrFunctions.CVT_nativeint_u64_u256_max,
        setOf(Value.Reg(SbfRegister.R0)),
        setOf())
    ),
    NATIVEINT_SEXT(ExternalFunction(CvlrFunctions.CVT_nativeint_u64_sext,
        setOf(Value.Reg(SbfRegister.R0)),
        setOf(SbfRegister.R1, SbfRegister.R2).map{ Value.Reg(it)}.toSet())
    ),
    NATIVEINT_NEG(ExternalFunction(CvlrFunctions.CVT_nativeint_u64_neg,
        setOf(Value.Reg(SbfRegister.R0)),
        setOf(Value.Reg(SbfRegister.R1)))
    ),
    NATIVEINT_MASK(ExternalFunction(CvlrFunctions.CVT_nativeint_u64_mask,
        setOf(Value.Reg(SbfRegister.R0)),
        setOf(SbfRegister.R1, SbfRegister.R2).map{ Value.Reg(it)}.toSet())
    );

    companion object: ExternalLibrary<CVTNativeInt>  {
        private val nameMap = CVTNativeInt.entries.associateBy { it.function.name }

        override fun from(name: String) = nameMap[name]
        override fun addSummaries(memSummaries: MemorySummaries) {
            for (f in nameMap.values) {
                val summaryArgs = when (f) {
                    NATIVE_INTO_U128 ->
                        listOf(
                            MemSummaryArgument(r = SbfRegister.R0, type = MemSummaryArgumentType.ANY),
                            MemSummaryArgument(r = SbfRegister.R1, offset = 0, width = 8, type = MemSummaryArgumentType.NUM),
                            MemSummaryArgument(r = SbfRegister.R1, offset = 8, width = 8, type = MemSummaryArgumentType.NUM)
                        )
                    else ->
                        listOf(MemSummaryArgument(r = SbfRegister.R0, type = MemSummaryArgumentType.NUM))
                }
                memSummaries.addSummary(f.function.name, MemorySummary(summaryArgs))
            }
        }
    }
}

enum class CVTNondet(val function: ExternalFunction) {
    NONDET_U8(ExternalFunction(CvlrFunctions.CVT_nondet_u8,setOf(Value.Reg(SbfRegister.R0)), setOf())),
    NONDET_U16(ExternalFunction(CvlrFunctions.CVT_nondet_u16, setOf(Value.Reg(SbfRegister.R0)), setOf())),
    NONDET_U32(ExternalFunction(CvlrFunctions.CVT_nondet_u32, setOf(Value.Reg(SbfRegister.R0)), setOf())),
    NONDET_U64(ExternalFunction(CvlrFunctions.CVT_nondet_u64, setOf(Value.Reg(SbfRegister.R0)), setOf())),
    NONDET_USIZE(ExternalFunction(CvlrFunctions.CVT_nondet_usize, setOf(Value.Reg(SbfRegister.R0)), setOf())),
    NONDET_I8(ExternalFunction(CvlrFunctions.CVT_nondet_i8, setOf(Value.Reg(SbfRegister.R0)), setOf())),
    NONDET_I16(ExternalFunction(CvlrFunctions.CVT_nondet_i16, setOf(Value.Reg(SbfRegister.R0)), setOf())),
    NONDET_I32(ExternalFunction(CvlrFunctions.CVT_nondet_i32, setOf(Value.Reg(SbfRegister.R0)), setOf())),
    NONDET_I64(ExternalFunction(CvlrFunctions.CVT_nondet_i64, setOf(Value.Reg(SbfRegister.R0)), setOf()));

    companion object: ExternalLibrary<CVTNondet>  {
        private val nameMap = CVTNondet.entries.associateBy { it.function.name }

        override fun from(name: String) = nameMap[name]
        override fun addSummaries(memSummaries: MemorySummaries) {
            for (f in nameMap.values) {
                val summaryArgs = listOf(MemSummaryArgument(r = SbfRegister.R0, type = MemSummaryArgumentType.NUM))
                memSummaries.addSummary(f.function.name, MemorySummary(summaryArgs))
            }
        }
    }
}
