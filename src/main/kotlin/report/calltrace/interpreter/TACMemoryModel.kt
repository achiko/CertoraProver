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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package report.calltrace.interpreter

import analysis.opt.ConstantPropagatorAndSimplifier
import com.certora.collect.TreapMap
import com.certora.collect.removeAllKeys
import com.certora.collect.retainAll
import com.certora.collect.toTreapMap
import com.certora.collect.treapMapOf
import datastructures.stdcollections.toMap
import evm.EVM_WORD_SIZE
import tac.Tag
import vc.data.TACSymbol
import vc.data.state.TACValue
import java.math.BigInteger
import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE_INT
import report.calltrace.interpreter.Pointer.*
import tac.isMapType
import utils.ModZm.Companion.inBounds
import utils.ModZm.Companion.lowOnes
import utils.`impossible!`
import utils.letIf
import vc.data.ReverseTACExpr
import vc.data.TACExpr
import vc.data.TACExprFactUntyped.safeMathNarrow
import vc.data.asTACExpr
import vc.data.getOperands
import vc.data.tacexprutil.TACExprFactBasicSimp.BWAnd
import vc.data.tacexprutil.TACExprFactBasicSimp.BWNot
import vc.data.tacexprutil.TACExprFactBasicSimp.IntAdd
import vc.data.tacexprutil.asConst
import vc.data.tacexprutil.asConstOrNull
import vc.data.tacexprutil.asVar
import vc.data.tacexprutil.asVarOrNull

/**
 * Represents the type of memory store operation.
 * FullWord: Stores a complete 32-byte EVM word
 * SingleByte: Stores a single byte at a specific offset within a word
 */
enum class StoreType {
    FullWord,
    SingleByte
}

/**
 * Core interface for memory model operations in the TAC interpreter.
 * Provides abstract operations for managing memory cells and their contents
 * during symbolic execution.
 *
 * Type parameter T represents the concrete memory implementation type.
 */
interface IMemory<T> {
    /**
     * Performs a deep copy of memory regions, models long copy in TAC.
     *
     * Copies data from srcBase[srcOffset, srcOffset+length] to
     * dstBase[dstOffset, dstOffset+length].
     *
     * In the case that one of [srcOffset], [dstOffset] or [length] isn't concrete, invalidates all pointers
     * to [dstBase] as we do not know which range in memory was overwritten.
     */
    fun byteCopy(
        srcBase: TACSymbol.Var,
        srcOffset: TACSymbol,
        length: TACSymbol,
        dstBase: TACSymbol.Var,
        dstOffset: TACSymbol
    ): T

    /**
     * Stores a value into memory, modeling memory stores for WordStore, ByteStore and ByteStoreSingle.
     *
     * Stores the expression [storedExpr] into memory of [base] at location [location]
     * The [storeType] dictates if it is a single byte store or a full word (i.e. ByteStoreSingle vs Word/ByteStore)
     *
     * Updates the memory model such that afterwards there is a pointer [Memory] that points
     * to the cell that was pointed to by the pointer Var([storedExpr]).
     *
     * In case the [storedExpr] is a constant, a fresh cell is created with that constant directly and the [Memory]
     * pointer will point to this cell. This is equivalent to decompose this store statement into two statement, i.e.,
     * introduce a temporary tac variable and store the constant first.
     */
    fun memstore(
        storedExpr: TACExpr,
        base: TACSymbol.Var,
        location: TACSymbol,
        storeType: StoreType
    ): T

    /**
     * Loads a value from memory, modeling EVM MLOAD operation.
     *
     * Checks if there is a [Memory] pointer for [base] at [location] and let this cell be pointed
     * to by Var([lhs])
     */
    fun load(lhs: TACSymbol.Var, base: TACSymbol.Var, location: TACSymbol): T

    /**
     * Invalidates/removes the pointer for the given variable.
     * Used when a variable's value becomes unknown or invalid.
     */
    fun kill(lhs: TACSymbol.Var): T

    /**
     * Updates the memory model on assumes ([vc.data.TACCmd.Simple.AssumeExpCmd], [vc.data.TACCmd.Simple.AssumeCmd]
     * - and also [smtlibutils.data.Cmd.Assert]). Expects the expression [expr] to evaluate to [expectedResult].
     *
     * Returns null if there is a conflict in memory. Due to the expected result. Interpretation will
     * then not proceed with the current trace.
     */
    fun forceAssume(expr: TACExpr, expectedResult: BigInteger): T?

    /**
     * Default implementation to get the value of a symbol.
     * If the symbol is already a constant, no need to defer to the memory model.
     */
    fun value(sym: TACSymbol): BigInteger? {
        return when (sym) {
            is TACSymbol.Const -> sym.value
            is TACSymbol.Var -> value(sym)
        }
    }

    /**
     * Gets the value from the memory model (if existing).
     */
    fun value(sym: TACSymbol.Var): BigInteger?

    /**
     * Converts the memory model to a map that can be used by the remaining of the
     * pipeline.
     */
    fun toTacAssignments(): Map<TACSymbol.Var, TACValue>

    /**
     * Store the expression [rhs] into [lhs] in memory.
     *
     * This method evaluates the expression upon storing it.
     */
    fun storeExpression(lhs: TACSymbol.Var, rhs: TACExpr): T

    /**
     * Computes the current value for [expr]. Returns itself (unchanged) in the case
     * a) the expression cannot be evaluated
     * b) the expression can be evaluated and the computed value is equal to [expectedValue]
     *
     * returns `null` otherwise (meaning a conflict was found)
     */
    fun checkConflict(expr: TACExpr, expectedValue: BigInteger): T?
}

/**
 * Concrete implementation of the memory model for TAC interpretation.
 *
 * Uses a Treap (tree + heap) data structure for efficient storage and lookup of memory cells.
 * The model tracks two types of pointers:
 * - Variable pointers (Var): Direct references to TAC variables
 * - Memory pointers (Memory): References to memory locations within map-type variables
 *
 * The values of the map is the expression how the value of the current memory cell can be computed.
 * This can either be a constant expression, when a value is known, but it can also be an expression
 * depending on other variables. This design allows backtracking, as we can compute the contents
 * of the cell once they are available.
 */
data class TACMemoryModel(
    private val pointerToExpr: TreapMap<Pointer, TACExpr> = treapMapOf(),
) : IMemory<TACMemoryModel> {

    override fun value(sym: TACSymbol.Var): BigInteger? {
        if (sym.isMapType()) {
            return null
        }
        return pointerToExpr[Var(sym)]?.asConstOrNull
    }

    override fun load(lhs: TACSymbol.Var, base: TACSymbol.Var, location: TACSymbol): TACMemoryModel {
        val loc = value(location) ?: return this

        val ptr = Memory(base, loc)
        val content = pointerToExpr[ptr] ?: return this
        return this.update(Var(lhs), content)
    }

    override fun memstore(
        storedExpr: TACExpr,
        base: TACSymbol.Var,
        location: TACSymbol,
        storeType: StoreType
    ): TACMemoryModel {
        val loc = value(location) ?: // Unknown location - invalidate all memory for this base
        return this.kill(base)

        when (storeType) {
            StoreType.FullWord -> {
                // Store complete 32-byte word
                return this.update(Memory(base, loc), storedExpr)
            }

            StoreType.SingleByte -> {
                // Store single byte - need to preserve other bytes in the word
                // Calculate which word this byte belongs to and the offset within that word
                val offset = loc % EVM_WORD_SIZE
                val wordStart = loc - offset
                val impactedPtr = Memory(base, wordStart)
                val oldExpr = this.pointerToExpr[impactedPtr]
                return if (oldExpr != null) {
                    // There was an expression stored at [impactedPtr],
                    // we can combine it with the newValue
                    val shiftFactor = (8 * (EVM_WORD_SIZE_INT - 1 - offset.toInt()))
                    val mask = lowOnes(8) shl shiftFactor
                    val oldValueWithHole =
                        TACExpr.BinOp.BWAnd(oldExpr, BWNot(mask.asTACExpr(Tag.Bit256), Tag.Bit256), Tag.Bit256)
                    val rhsShifted = TACExpr.BinOp.ShiftLeft(
                        BWAnd(storedExpr, lowOnes(8).asTACExpr(Tag.Bit256), Tag.Bit256),
                        shiftFactor.asTACExpr(Tag.Bit256),
                        Tag.Bit256
                    )
                    val expr = safeMathNarrow(IntAdd(oldValueWithHole, rhsShifted, Tag.Int), Tag.Bit256)
                    this.update(impactedPtr, expr)
                } else {
                    // One or both values unknown - result is unknown
                    this.copy(pointerToExpr = pointerToExpr.remove(impactedPtr))
                }

            }
        }
    }

    override fun byteCopy(
        srcBase: TACSymbol.Var,
        srcOffset: TACSymbol,
        length: TACSymbol,
        dstBase: TACSymbol.Var,
        dstOffset: TACSymbol
    ): TACMemoryModel = byteCopy(srcBase, value(srcOffset), value(length), dstBase, value(dstOffset))

    override fun storeExpression(lhs: TACSymbol.Var, rhs: TACExpr): TACMemoryModel {
        val rhsVar = rhs.asVarOrNull
        val maybeKilled = if (lhs.isMapType() && rhsVar != lhs) {
            this.kill(lhs)
        } else {
            this
        }
        if (rhsVar != null && rhsVar.isMapType()) {
            check(lhs.isMapType())
            val newKeys = pointerToExpr
                .mapNotNull { (k, v) ->
                    if (k is Memory && k.baseVar == rhsVar) {
                        k.copy(baseVar = lhs) to v
                    } else {
                        null
                    }
                }

            return maybeKilled.copy(
                pointerToExpr = pointerToExpr.putAll(newKeys.toTreapMap()),
            )
        } else {
            val ptr = Var(lhs)
            return maybeKilled.update(ptr, rhs)
        }
    }

    override fun checkConflict(expr: TACExpr, expectedValue: BigInteger): TACMemoryModel? {
        this.calculateExpression(expr)?.let { res ->
            if (res != expectedValue) {
                return null
            }
        }
        return this
    }

    override fun forceAssume(expr: TACExpr, expectedResult: BigInteger): TACMemoryModel? {
        checkConflict(expr, expectedResult) ?: return null

        // 1. Compute the value of the expression
        val interpretedRes = calculateExpression(expr)
        if (interpretedRes != null) {
            // The expression was fully computed, nothing to be done here.
            return this
        }

        fun updateAndBackTrack(ptr: Var, value: BigInteger): TACMemoryModel? {
            val existingExpr = this.pointerToExpr[ptr]
            return this.update(ptr, value.asTACExpr(ptr.tag)).calculateFixpoint()
                .letIf(existingExpr != null) {
                    // back tracking: the expression that was previously stored can now also be evaluated.
                    it.forceAssume(existingExpr!!, value)
                }
        }

        // 2. Reverse the operation given expectedResult
        return when (expr) {
            is TACExpr.Sym.Const -> `impossible!`

            is TACExpr.Sym.Var -> {
                val ptr = Var(expr.asVar)
                updateAndBackTrack(ptr, expectedResult)
            }

            else -> {
                val operandsAndVars = expr.getOperands().map {
                    when (it) {
                        is TACExpr.Sym.Const -> it.asConst to null
                        is TACExpr.Sym.Var -> value(it.s) to it
                        else -> calculateExpression(it) to null
                    }
                }

                val missingParams = operandsAndVars.filter { it.first == null }

                // Only proceed, if there is exactly one variable missing.
                val missingVariable = missingParams.singleOrNull()?.second ?: return this
                val missingValue = ReverseTACExpr.reverseTACExpr(
                    expr,
                    expectedResult,
                    operandsAndVars.map { it.first }
                ) ?: return this

                check(missingVariable.tag !is Tag.Bits || missingValue.inBounds(missingVariable.tag)) { "When reversing the" +
                    " expression $expr the computed missing ($missingValue) is not in its bounds (${missingVariable.tag})" }
                val ptr = Var(missingVariable.asVar)
                updateAndBackTrack(ptr, missingValue)
            }
        }
    }

    override fun kill(lhs: TACSymbol.Var): TACMemoryModel = this.copy(pointerToExpr = pointerToExpr.kill(lhs))

    override fun toTacAssignments(): Map<TACSymbol.Var, TACValue> {
        return this.pointerToExpr
            .mapNotNull { (k, v) ->
                if (k is Var && v is TACExpr.Sym.Const) {
                    if (k.variable.tag == Tag.Bool) {
                        k.variable to TACValue.valueOf(v.s.value != BigInteger.ZERO)
                    } else {
                        k.variable to TACValue.valueOf(v.s.value)
                    }
                } else {
                    null
                }
            }.toMap()
    }


    /**
     * Calculates the expression [expr] given all expressions provided in [pointerToExpr].
     * Will first compute all operands of [expr] and then compute the [expr] itself.
     */
    private fun calculateExpression(expr: TACExpr): BigInteger? {
        return when (expr) {
            is TACExpr.Sym.Const -> expr.asConst
            is TACExpr.Sym.Var -> this.value(expr.asVar)
            else -> ConstantPropagatorAndSimplifier.calculateOrNull(
                expr,
                expr.getOperands().map { calculateExpression(it) })?.also { value ->
                check(expr.tag !is Tag.Bits || value.inBounds(expr.tag as Tag.Bits)) { "Calculating the expression $expr" +
                    "yielded a value ($value) that is not in bounds of ${expr.tag}" }
            }
        }
    }

    /**
     * Iterates over all [TACExpr] in [pointerToExpr] and tries to evaluate them given existing over
     * expressions in the map. This is called upon addition of an expression into the map, as it can
     * result to some of the expression being evaluated. This process is recursive as the expression
     * typically depend on each other.
     */
    private fun calculateFixpoint(): TACMemoryModel {
        var changed = false
        return this.copy(pointerToExpr = pointerToExpr.updateValues { k, v ->
            v as? TACExpr.Sym.Const
                ?: calculateExpression(v)?.let {
                    changed = true
                    it.asTACExpr(k.tag)
                } ?: v
        }).letIf(changed) { it.calculateFixpoint() }
    }

    /**
     * Performs a deep copy of memory regions (models long memory copy in TAC).
     *
     * Copies data from srcBase[srcOffset, srcOffset+length] to
     * dstBase[dstOffset, dstOffset+length].
     *
     * In the case that one of [srcOffset], [dstOffset] or [length] isn't concrete, invalidates all pointers
     * to [dstBase] as we do not know which range in memory was overwritten.
     */
    private fun byteCopy(
        srcBase: TACSymbol.Var,
        srcOffset: BigInteger?,
        length: BigInteger?,
        dstBase: TACSymbol.Var,
        dstOffset: BigInteger?
    ): TACMemoryModel {
        return when {
            srcOffset != null && length != null && dstOffset != null && length >= BigInteger.ONE -> {
                val srcRange = srcOffset ..< srcOffset + length
                val dstRange = dstOffset ..< dstOffset + length
                this.copy(
                    pointerToExpr = pointerToExpr.removeAllKeys { k ->
                        k.isMemPtrInRange(dstBase, dstRange)
                    }
                        .putAll(
                            pointerToExpr.retainAll { (k, _) ->
                                k.isMemPtrInRange(srcBase, srcRange)
                            }.mapKeys { (k, _) ->
                                require(k is Memory)
                                Memory(dstBase, dstOffset + (k.offset - srcOffset))
                            })
                )
            }

            else -> {
                // Safe default, remove all expression that link to [dstBase]
                this.copy(
                    pointerToExpr = pointerToExpr.kill(dstBase)
                )
            }
        }
    }

    /**
     * Tries to calculate the [expr] and then stores either the computed value or the symbolic expression at [ptr].
     */
    private fun update(ptr: Pointer, expr: TACExpr): TACMemoryModel {
        return this.copy(pointerToExpr = pointerToExpr.put(ptr, this.calculateExpression(expr)?.asTACExpr(ptr.tag) ?: expr))
    }
}

fun TACSymbol.Var.isMapType() = tag.isMapType()

/**
 * Represents different types of pointers in the memory model.
 * Used as keys in the pointer-to-cell mapping.
 */
sealed interface Pointer {
    val tag: Tag
    /**
     * A pointer to a TAC variable (non-map types).
     */
    data class Var(val variable: TACSymbol.Var) : Pointer {
        init {
            check(!variable.isMapType()) { "Variable cannot be of map type." }
        }
        override val tag get() =  variable.tag
    }

    /**
     * A pointer to a memory location within a map-type variable.
     * Models EVM memory access at a specific offset.
     *
     * Points to a word of length [EVM_WORD_SIZE] into [baseVar] at [offset]
     */
    data class Memory(val baseVar: TACSymbol.Var, val offset: BigInteger) : Pointer {
        init {
            check(baseVar.isMapType()) { "Base variable is not a map type." }
            check(offset >= BigInteger.ZERO) { "Offset must be positive." }
        }

        override val tag get() = (baseVar.tag as Tag.Map).resultSort
    }

    fun isMemPtrInRange(
        base: TACSymbol.Var,
        range: OpenEndRange<BigInteger>,
    ): Boolean = when (this) {
        is Var -> false
        is Memory -> base == baseVar && offset in range
    }
}

/**
 * Helper extension function to remove all pointers associated with a variable.
 *
 * As [TACProgramInterpreter] assumes a program to be in DSA, we only kill variables of map type.
 * For these, this method removes all pointers of type [Memory] who base variable is [toKill].
 */
private fun TreapMap<Pointer, TACExpr>.kill(toKill: TACSymbol.Var): TreapMap<Pointer, TACExpr> {
    require(toKill.isMapType()) { "$toKill must be a map type." }
    return removeAllKeys { k ->
        when (k) {
            is Memory -> toKill == k.baseVar
            is Var -> false
        }
    }
}
