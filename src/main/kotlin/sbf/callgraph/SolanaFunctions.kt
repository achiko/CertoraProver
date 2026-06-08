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

import sbf.cfg.Value
import sbf.disassembler.*
import datastructures.stdcollections.*
import sbf.analysis.IRegisterTypes
import sbf.analysis.readGlobalVarValues
import sbf.cfg.LocatedSbfInstruction
import sbf.cfg.SbfInstruction
import sbf.cfg.MetaData
import sbf.domains.INumValue
import sbf.domains.IOffset
import sbf.domains.MemSummaryArgument
import sbf.domains.MemSummaryArgumentType
import sbf.domains.MemorySummaries
import sbf.domains.MemorySummary
import sbf.domains.SbfType
import sbf.domains.ScalarValueProvider
import sbf.sbfLogger
import sbf.support.base58Encode

/**
 *  Solana syscalls
 *
 *  All functions are defined here
 *  https://github.com/solana-labs/solana/blob/master/sdk/program/src/syscalls/definitions.rs#L39
 * **/

// To avoid clashes with user-defined functions
const val MAX_SYSCALL_FUNCTIONS = 1000

enum class SolanaFunction(val syscall: ExternalFunction) {
    ABORT(ExternalFunction(
        name = "abort")),
    SOL_LOG(ExternalFunction(
        name = "sol_log_",
        readRegisters = setOf(Value.Reg(SbfRegister.R1), Value.Reg(SbfRegister.R2)))),
    SOL_LOG_64(ExternalFunction(
        name = "sol_log_64_",
        readRegisters = listOf(
            SbfRegister.R1, SbfRegister.R2,
            SbfRegister.R3, SbfRegister.R4, SbfRegister.R5).map{ Value.Reg(it)}.toSet())),
    SOL_LOG_COMPUTE_UNITS(ExternalFunction(
        name = "sol_log_compute_units_")),
    SOL_ALLOC_FREE(ExternalFunction(
        name = "sol_alloc_free_",
        writeRegister = setOf(Value.Reg(SbfRegister.R0)),
        readRegisters = setOf(Value.Reg(SbfRegister.R1), Value.Reg(SbfRegister.R2)))),
    SOL_PANIC(ExternalFunction(
        name = "sol_panic_",
        readRegisters = listOf(
            SbfRegister.R1, SbfRegister.R2,
            SbfRegister.R3, SbfRegister.R4).map{ Value.Reg(it)}.toSet())),
    SOL_CREATE_PROGRAM_ADDRESS(ExternalFunction(
        name = "sol_create_program_address",
        writeRegister = setOf(Value.Reg(SbfRegister.R0)),
        readRegisters = listOf(
            SbfRegister.R1, SbfRegister.R2,
            SbfRegister.R3, SbfRegister.R4).map{ Value.Reg(it)}.toSet())),
    SOL_INVOKE_SIGNED_C(ExternalFunction(
        name = "sol_invoke_signed_c",
        writeRegister = setOf(Value.Reg(SbfRegister.R0)),
        readRegisters = listOf(
            SbfRegister.R1, SbfRegister.R2,
            SbfRegister.R3, SbfRegister.R4, SbfRegister.R5).map{ Value.Reg(it)}.toSet())),
    SOL_INVOKE_SIGNED_RUST(ExternalFunction(
        name = "sol_invoke_signed_rust",
        writeRegister = setOf(Value.Reg(SbfRegister.R0)),
        readRegisters = listOf(
            SbfRegister.R1, SbfRegister.R2,
            SbfRegister.R3, SbfRegister.R4, SbfRegister.R5).map{ Value.Reg(it)}.toSet())),
    SOL_MEMCPY(ExternalFunction(
        name = "sol_memcpy_",
        writeRegister = setOf(Value.Reg(SbfRegister.R0)),
        readRegisters = listOf(
            SbfRegister.R1, SbfRegister.R2, SbfRegister.R3).map{ Value.Reg(it)}.toSet())),
    /**
     * This is not an actual solana syscall, but it is convenient to pretend that it is.
     * ```
     *     void memcpy_zext(void *dst, const void *src, size_t i);
     * ```
     * Copies the first (low bits assuming little-endian) i bytes from src to dst, and sets to zero the remaining
     * bytes in the destination up to 8 bytes.
     *
     * - r1 is dst
     * - r2 is src
     * - r3 is i
     *
     * Semantics:
     * ```
     * - For 0 <= k < min(i, 8):   dst[k] = src[k]
     * - For i <= k < 8:           dst[k] = 0
     * ```
     */
    SOL_MEMCPY_ZEXT(ExternalFunction(
        name = "sol_memcpy_zext",
        writeRegister = setOf(),
        readRegisters = listOf(
            SbfRegister.R1, SbfRegister.R2, SbfRegister.R3).map{ Value.Reg(it)}.toSet())),
    /**
     * This is not an actual solana syscall, but it is convenient to pretend that it is.
     * ```
     *     void memcpy_trunc(void *dst, const void *src, size_t i);
     * ```
     * Copies the first (low bits assuming little-endian) i bytes from src to dst.
     *
     * - r1 is dst
     * - r2 is src
     * - r3 is i
     *
     * Semantics:
     * ```
     * - For 0 <= k < min(i, 8):   dst[k] = src[k]
     * ```
     */
    SOL_MEMCPY_TRUNC(ExternalFunction(
        name = "sol_memcpy_trunc",
        writeRegister = setOf(),
        readRegisters = listOf(
            SbfRegister.R1, SbfRegister.R2, SbfRegister.R3).map{ Value.Reg(it)}.toSet())),
    SOL_MEMMOVE(ExternalFunction(
        name = "sol_memmove_",
        writeRegister = setOf(Value.Reg(SbfRegister.R0)),
        readRegisters = listOf(
            SbfRegister.R1, SbfRegister.R2, SbfRegister.R3).map{ Value.Reg(it)}.toSet())),
    SOL_MEMSET(ExternalFunction(
        name = "sol_memset_",
        writeRegister = setOf(Value.Reg(SbfRegister.R0)),
        readRegisters = listOf(
            SbfRegister.R1, SbfRegister.R2, SbfRegister.R3).map{ Value.Reg(it)}.toSet())),
    SOL_MEMCMP(ExternalFunction(
        name = "sol_memcmp_",
        writeRegister = setOf(Value.Reg(SbfRegister.R0)),
        readRegisters = listOf(
            SbfRegister.R1, SbfRegister.R2, SbfRegister.R3).map{ Value.Reg(it)}.toSet())),
    SOL_GET_CLOCK_SYSVAR(ExternalFunction(
        name = "sol_get_clock_sysvar",
        writeRegister = setOf(Value.Reg(SbfRegister.R0)),
        readRegisters = setOf(Value.Reg(SbfRegister.R1)))),
    // This is not an actual solana syscall, but it is convenient to pretend that it is.
    SOL_SET_CLOCK_SYSVAR(ExternalFunction(
        name = "sol_set_clock_sysvar",
        writeRegister = setOf(Value.Reg(SbfRegister.R0)),
        readRegisters = setOf(Value.Reg(SbfRegister.R1)))),
    SOL_CURVE_VALIDATE_POINT(ExternalFunction(
        name = "sol_curve_validate_point",
        readRegisters = setOf(Value.Reg(SbfRegister.R1), Value.Reg(SbfRegister.R2)))),
    SOL_CURVE_GROUP_OP(ExternalFunction(
        name = "sol_curve_group_op",
        readRegisters = listOf(
            SbfRegister.R1, SbfRegister.R2,
            SbfRegister.R3, SbfRegister.R4, SbfRegister.R5).map{ Value.Reg(it)}.toSet())),
    SOL_GET_STACK_HEIGHT(ExternalFunction(
        name = "sol_get_stack_height",
        writeRegister = setOf(Value.Reg(SbfRegister.R0)))),
    SOL_GET_PROCESSED_SIBLING_INSTRUCTION(ExternalFunction(
        name = "sol_get_processed_sibling_instruction",
        readRegisters = listOf(
            SbfRegister.R1, SbfRegister.R2,
            SbfRegister.R3, SbfRegister.R4, SbfRegister.R5).map{ Value.Reg(it)}.toSet())),
    SOL_GET_RENT_SYSVAR(ExternalFunction(
        name = "sol_get_rent_sysvar",
        writeRegister = setOf(Value.Reg(SbfRegister.R0)),
        readRegisters = setOf(Value.Reg(SbfRegister.R1)))),
    SOL_GET_SYSVAR(ExternalFunction(
        name = "sol_get_sysvar",
        writeRegister = setOf(Value.Reg(SbfRegister.R0)),
        readRegisters = setOf(Value.Reg(SbfRegister.R1), Value.Reg(SbfRegister.R2),
            Value.Reg(SbfRegister.R3),Value.Reg(SbfRegister.R4)))),
    /**
     * This is not an actual solana syscall, but it is convenient to pretend that it is.
     * It has the same signature as `sol_get_sysvar` but we know that R2 points to a buffer that stores
     * a `Rent` struct. Note that there is already a Solana function called `sol_get_rent_sysvar`.
     * We use a different name because the number of parameters is different, but semantically they are the same.
     */
    CVT_SOL_GET_RENT_SYSVAR(ExternalFunction(
        name = "cvt_sol_get_rent_sysvar",
        writeRegister = setOf(Value.Reg(SbfRegister.R0)),
        readRegisters = setOf(Value.Reg(SbfRegister.R1), Value.Reg(SbfRegister.R2),
            Value.Reg(SbfRegister.R3),Value.Reg(SbfRegister.R4)))),
    /**
     * This is not an actual solana syscall, but it is convenient to pretend that it is.
     * It has the same signature as `sol_get_sysvar` but we know that R2 points to a buffer that stores
     * a `Clock` struct. Note that there is already a Solana function called `sol_get_clock_sysvar`.
     * We use a different name because the number of parameters is different, but semantically they are the same.
     */
    CVT_SOL_GET_CLOCK_SYSVAR(ExternalFunction(
        name = "cvt_sol_get_clock_sysvar",
        writeRegister = setOf(Value.Reg(SbfRegister.R0)),
        readRegisters = setOf(Value.Reg(SbfRegister.R1), Value.Reg(SbfRegister.R2),
            Value.Reg(SbfRegister.R3),Value.Reg(SbfRegister.R4)))),
    SOL_GET_FEES_SYSVAR(ExternalFunction(
        name = "sol_get_fees_sysvar",
        readRegisters = setOf(Value.Reg(SbfRegister.R1)))),
    SOL_SET_RETURN_DATA(ExternalFunction(
        name = "sol_set_return_data",
        readRegisters = setOf(Value.Reg(SbfRegister.R1), Value.Reg(SbfRegister.R2)))),
    SOL_GET_RETURN_DATA(ExternalFunction(
        name = "sol_get_return_data",
        readRegisters = listOf(
            SbfRegister.R1, SbfRegister.R2, SbfRegister.R3).map{ Value.Reg(it)}.toSet()));

    companion object: ExternalLibrary<SolanaFunction> {
        init {
            check(SolanaFunction.entries.size < MAX_SYSCALL_FUNCTIONS) {"Exceeded maximum number of Solana syscalls"}
        }

        private val nameMap = SolanaFunction.entries.associateBy { it.syscall.name }
        private val valueMap = SolanaFunction.entries.associateBy { it.ordinal }
        override fun from(name: String) = nameMap[name]
        fun from(value: Int) = valueMap[value]

        fun toCallInst(function: SolanaFunction, metadata: MetaData = MetaData()) =
            SbfInstruction.Call(name = function.syscall.name, metaData = metadata)

        override fun addSummaries(memSummaries: MemorySummaries) {
            for (f in nameMap.values) {
                when (f) {
                    // These are already natively understood by the prover
                    ABORT, SOL_PANIC -> {}
                    SOL_MEMCMP,
                    SOL_MEMCPY,
                    SOL_MEMCPY_ZEXT,
                    SOL_MEMCPY_TRUNC,
                    SOL_MEMMOVE,
                    SOL_MEMSET -> {}
                    // No summaries
                    SOL_LOG, SOL_LOG_64, SOL_LOG_COMPUTE_UNITS -> {}
                    // These syscalls doesn't need to be summarized because either they are always called by wrappers that are
                    // already summarized or the default summary is enough.
                    SOL_ALLOC_FREE -> {}
                    SOL_CREATE_PROGRAM_ADDRESS, SOL_INVOKE_SIGNED_C, SOL_INVOKE_SIGNED_RUST -> {}
                    SOL_CURVE_VALIDATE_POINT, SOL_CURVE_GROUP_OP,
                    SOL_GET_STACK_HEIGHT, SOL_GET_PROCESSED_SIBLING_INSTRUCTION,
                    SOL_GET_FEES_SYSVAR, SOL_SET_RETURN_DATA, SOL_GET_RETURN_DATA -> {}
                    // Syscalls that require summaries
                    SOL_GET_CLOCK_SYSVAR, SOL_SET_CLOCK_SYSVAR-> {
                        val summaryArgs = listOf(
                            MemSummaryArgument(r = SbfRegister.R0, type = MemSummaryArgumentType.ANY),
                            MemSummaryArgument(r = SbfRegister.R1, offset = 0, width = 8, type = MemSummaryArgumentType.NUM),
                            MemSummaryArgument(r = SbfRegister.R1, offset = 8, width = 8, type = MemSummaryArgumentType.NUM),
                            MemSummaryArgument(r = SbfRegister.R1, offset = 16, width = 8, type = MemSummaryArgumentType.NUM),
                            MemSummaryArgument(r = SbfRegister.R1, offset = 24, width = 8, type = MemSummaryArgumentType.NUM),
                            MemSummaryArgument(r = SbfRegister.R1, offset = 32, width = 8, type = MemSummaryArgumentType.NUM))
                        memSummaries.addSummary(f.syscall.name, MemorySummary(summaryArgs))
                    }
                    SOL_GET_RENT_SYSVAR -> {
                        val summaryArgs = listOf(
                            MemSummaryArgument(r = SbfRegister.R0, type = MemSummaryArgumentType.ANY),
                            MemSummaryArgument(r = SbfRegister.R1, offset = 0, width = 8, type = MemSummaryArgumentType.NUM),   /* u64 */
                            MemSummaryArgument(r = SbfRegister.R1, offset = 8, width = 8, type = MemSummaryArgumentType.NUM),   /* f64 */
                            MemSummaryArgument(r = SbfRegister.R1, offset = 16, width = 1, type = MemSummaryArgumentType.NUM))  /* u8 */
                        memSummaries.addSummary(f.syscall.name, MemorySummary(summaryArgs))
                    }
                    SOL_GET_SYSVAR -> {
                        val summaryArgs = listOf(
                            MemSummaryArgument(r = SbfRegister.R0, type = MemSummaryArgumentType.NUM),
                            /**
                             * Depending on the content of R1, R2 is a buffer that either contains a Clock (5xu64) or Rent (u64/f64/u8).
                             * Thus, this summary is callsite-dependent.
                             * The Solana front-end will resolve all calls to `sol_get_sysvar` and replace with calls to either
                             * `sol_get_sysvar_rent` or `sol_get_sysvar_clock` so that
                             * we can apply memory summaries in a callsite-independent way.
                             **/
                        )
                        memSummaries.addSummary(f.syscall.name, MemorySummary(summaryArgs))
                    }
                    CVT_SOL_GET_RENT_SYSVAR -> {
                        val summaryArgs = listOf(
                            MemSummaryArgument(r = SbfRegister.R0, type = MemSummaryArgumentType.NUM),
                            MemSummaryArgument(r = SbfRegister.R2, offset = 0, width = 8, type = MemSummaryArgumentType.NUM),
                            MemSummaryArgument(r = SbfRegister.R2, offset = 8, width = 8, type = MemSummaryArgumentType.NUM),
                            MemSummaryArgument(r = SbfRegister.R2, offset = 16, width = 1, type = MemSummaryArgumentType.NUM)
                        )
                        memSummaries.addSummary(f.syscall.name, MemorySummary(summaryArgs))
                    }
                    CVT_SOL_GET_CLOCK_SYSVAR -> {
                        val summaryArgs = listOf(
                            MemSummaryArgument(r = SbfRegister.R0, type = MemSummaryArgumentType.NUM),
                            MemSummaryArgument(r = SbfRegister.R2, offset = 0, width = 8, type = MemSummaryArgumentType.NUM),
                            MemSummaryArgument(r = SbfRegister.R2, offset = 8, width = 8, type = MemSummaryArgumentType.NUM),
                            MemSummaryArgument(r = SbfRegister.R2, offset = 16, width = 8, type = MemSummaryArgumentType.NUM),
                            MemSummaryArgument(r = SbfRegister.R2, offset = 24, width = 8, type = MemSummaryArgumentType.NUM),
                            MemSummaryArgument(r = SbfRegister.R2, offset = 32, width = 8, type = MemSummaryArgumentType.NUM)
                        )
                        memSummaries.addSummary(f.syscall.name, MemorySummary(summaryArgs))
                    }
                }
            }
        }
    }

}

enum class SolanaSysVarId {
    CLOCK,
    RENT
}


/**
 * Helper to resolve the [SolanaSysVarId] for a `sol_get_sysvar` call.
 *
 * `sol_get_sysvar(sysvar_id: *const u8, var_addr: *mut u8, offset: u64, length: u64) -> u64`
 *
 * R1 points to a global variable that encodes the sysvar identity as a 32-byte public key.
 * R2 is the output buffer where the sysvar data is written.
 *
 * [getSysvarId] reads R1 at the call site, looks up the global variable it points to, decodes the
 * 32-byte key, and returns the corresponding [SolanaSysVarId].  Returns null if R1 is
 * not a known global pointer or the key is not a recognised sysvar.
 *
 * Callers should use the returned id to apply a callsite-specific summary for R2, since the layout
 * of the output buffer depends on which sysvar is being fetched (e.g. RENT writes
 * `{lamports_per_byte_year: u64, exemption_threshold: f64, burn_percent: u8}` at R2, while CLOCK
 * writes five u64 fields).
 **/
class SolGetSysvar {

    private val cache = mutableMapOf<LocatedSbfInstruction, SolanaSysVarId>()

    @Suppress("unused")
    fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset> , ScalarDomain: ScalarValueProvider<TNum, TOffset>> getId(
        locInst: LocatedSbfInstruction,
        scalars: ScalarDomain,
        globals: GlobalVariables
    ): SolanaSysVarId? {
        cache[locInst]?.let { return it }
        val id = getSysvarId(locInst, scalars, globals) ?: return null
        cache[locInst] = id
        return id
    }

    @Suppress("unused")
    fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset> > getId(
        locInst: LocatedSbfInstruction,
        types: IRegisterTypes<TNum, TOffset>,
        globals: GlobalVariables
    ): SolanaSysVarId? {
        cache[locInst]?.let { return it }
        val id = getSysvarId(locInst, types, globals) ?: return null
        cache[locInst] = id
        return id
    }

    companion object {

        fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> getSysvarId(
            locInst: LocatedSbfInstruction,
            types: IRegisterTypes<TNum, TOffset>,
            globals: GlobalVariables
        ): SolanaSysVarId? {
            val type1 = types.typeAtInstruction(locInst, SbfRegister.R1)
            val type3 = types.typeAtInstruction(locInst, SbfRegister.R3)
            val type4 = types.typeAtInstruction(locInst, SbfRegister.R4)
            return getSysvarIdImpl(locInst, type1, type3, type4, globals)
        }

        fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset> , ScalarDomain: ScalarValueProvider<TNum, TOffset>> getSysvarId(
            locInst: LocatedSbfInstruction,
            scalars: ScalarDomain,
            globals: GlobalVariables
        ): SolanaSysVarId?  {
            val type1 = scalars.getAsScalarValue(Value.Reg(SbfRegister.R1)).type()
            val type3 = scalars.getAsScalarValue(Value.Reg(SbfRegister.R3)).type()
            val type4 = scalars.getAsScalarValue(Value.Reg(SbfRegister.R4)).type()
            return getSysvarIdImpl(locInst, type1, type3, type4, globals)
        }

        private fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>>
            getSysvarIdImpl(
            locInst: LocatedSbfInstruction,
            sysVarIdTy: SbfType<TNum, TOffset>,
            offsetTy: SbfType<TNum, TOffset>,
            lengthTy: SbfType<TNum, TOffset>,
            globals: GlobalVariables
        ): SolanaSysVarId? {
            val inst = locInst.inst
            check(inst is SbfInstruction.Call)
            val solFunction = SolanaFunction.from(inst.name) ?: return null
            if (solFunction != SolanaFunction.SOL_GET_SYSVAR) {
                return null
            }
            val global = (sysVarIdTy as? SbfType.PointerType.Global)?.global ?: run {
                sbfLogger.warn { "Cannot determine the value of r1 at $inst. Expected to be a global variable that contains CLOCK_ID or RENT_ID" }
                return null
            }
            val offset = (offsetTy as? SbfType.NumType)?.value?.toLongOrNull()
            if (offset != 0L) {
                sbfLogger.warn { "Skipped $inst because only zero offsets are supported (offset=$offset)" }
            }
            val len = (lengthTy as? SbfType.NumType)?.value?.toLongOrNull()
            // Converting globalValues to base58 is more expensive but makes the code more maintainable.
            val id = base58Encode(readGlobalVarValues(global.address, 32, 8, globals.elf)
                .flatMap { toBytes(it).toList() }.toByteArray())
            return when (id) {
                "SysvarRent111111111111111111111111111111111" ->
                    SolanaSysVarId.RENT.takeIf { len == 17L } ?: run {
                        sbfLogger.warn { "Skipped $inst because rent expects size of 17 bytes (u64/f64/bool)" }
                        null
                    }
                "SysvarC1ock11111111111111111111111111111111" ->
                    SolanaSysVarId.CLOCK.takeIf { len == 40L } ?: run {
                        sbfLogger.warn { "Skipped $inst because clock expects size of 40 bytes" }
                        null
                    }
                else -> {
                    sbfLogger.warn { "Unsupported $id in $inst" }
                    null
                }
            }
        }
    }
}
