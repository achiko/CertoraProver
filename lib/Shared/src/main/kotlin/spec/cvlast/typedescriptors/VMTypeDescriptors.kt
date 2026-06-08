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

package spec.cvlast.typedescriptors

import com.certora.collect.*
import kotlinx.serialization.Polymorphic
import spec.cvlast.CVLType
import tac.ITACSymbolVar
import tac.Tag
import utils.*
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.bind
import utils.CollectingResult.Companion.map
import java.math.BigInteger

interface IInternalSummaryReturn
interface IConverterOutput
interface ISimpleOutput : IConverterOutput
interface IExternalEncodeOutput : IConverterOutput
interface IProgramOutput : IConverterOutput
interface IMappingKeyOutput : IConverterOutput
interface IExternalBuffer
interface IExternalInputBuffer : IExternalBuffer
interface IExternalOutputBuffer : IExternalBuffer
interface IStorageMapping
interface IHookParameter
interface IInternalSummaryInput
interface ICVLDataInput
interface ICVLDataOutput

sealed class ToVMContext {
    abstract fun getVisitor(): (Visitor) -> CollectingResult<ITypeDescriptorConverter<*,*,*>, String>
    fun supportsConversion(from: CVLType.PureCVLType, to: VMTypeDescriptor) : VoidResult<String> {
        return to.converterFrom(from, this.getVisitor()).map {  }
    }
    object InternalSummaryReturn: ToVMContext() {
        override fun getVisitor() = Visitor::internalSummaryReturnContext
    }
    object ExternalSummaryReturn: ToVMContext() {
        override fun getVisitor() = Visitor::externalSummaryReturnContext
    }
    object ArgumentPassing: ToVMContext() {
        override fun getVisitor() = Visitor::argumentPassingContext
    }

    object DirectPassing : ToVMContext() {
        override fun getVisitor() = Visitor::directPassingContext
    }

    object MappingKey : ToVMContext() {
        override fun getVisitor() = Visitor::storageMappingKeyContext
    }

    /**
     * An interface to provide conversion logic for each context. Defines the possible conversion functions that can be
     * "visited" when converting from a [CVLType.PureCVLType] to a [VMTypeDescriptor]
     */
    interface Visitor {
        fun internalSummaryReturnContext(): CollectingResult<ITypeDescriptorConverter<IProgramOutput, ICVLDataInput, IInternalSummaryReturn>, String>
        fun externalSummaryReturnContext(): CollectingResult<ITypeDescriptorConverter<IExternalEncodeOutput, ICVLDataInput, IExternalInputBuffer>, String> {
            return this.argumentPassingContext()
        }

        fun argumentPassingContext(): CollectingResult<ITypeDescriptorConverter<IExternalEncodeOutput, ICVLDataInput, IExternalInputBuffer>, String>

        fun directPassingContext(): CollectingResult<ITypeDescriptorConverter<ISimpleOutput, ITACSymbolVar, ITACSymbolVar>, String> {
            return "Direct passing not supported".asError()
        }

        fun storageMappingKeyContext(): CollectingResult<ITypeDescriptorConverter<IMappingKeyOutput, ITACSymbolVar, IStorageMapping>, String>
    }
}

/**
 * [AmbiSerializable] because of [CVLType.VM.context].
 */
@KSerializable
@Treapable
sealed class FromVMContext: AmbiSerializable {
    abstract fun getVisitor(): (Visitor) -> CollectingResult<ITypeDescriptorConverter<*,*,*>, String>

    fun supportsConversion(from: VMTypeDescriptor, to: CVLType.PureCVLType) : VoidResult<String> {
        return from.converterTo(to, this.getVisitor()).map {}
    }
    @KSerializable
    object ReturnValue: FromVMContext() {
        override fun getVisitor() = Visitor::returnValueContext
        private fun readResolve(): Any = ReturnValue
        override fun hashCode(): Int = hashObject(this)
    }
    @KSerializable
    object HookValue: FromVMContext() {
        override fun getVisitor() = Visitor::hookValueContext
        private fun readResolve(): Any = HookValue
        override fun hashCode(): Int = hashObject(this)
    }
    @KSerializable
    object StateValue: FromVMContext(){
        override fun getVisitor() = Visitor::stateValueContext
        private fun readResolve(): Any = StateValue
        override fun hashCode(): Int = hashObject(this)
    }
    @KSerializable
    object ExternalSummaryArgBinding: FromVMContext() {
        override fun getVisitor() = Visitor::externalSummaryArgBindingContext
        private fun readResolve(): Any = ExternalSummaryArgBinding
        override fun hashCode(): Int = hashObject(this)
    }
    @KSerializable
    object InternalSummaryArgBinding: FromVMContext() {
        override fun getVisitor() = Visitor::internalSummaryArgBindingContext
        private fun readResolve(): Any = InternalSummaryArgBinding
        override fun hashCode(): Int = hashObject(this)
    }


    @KSerializable
    object EventHookBinding : FromVMContext() {
        override fun getVisitor() = Visitor::eventHookBindingContext
        private fun readResolve(): Any = EventHookBinding
        override fun hashCode(): Int = hashObject(this)
    }

    /**
     * An interface to provide conversion logic for each context. Defines the possible conversion functions that can be
     * "visited" when converting from a [VMTypeDescriptor] to a [CVLType.PureCVLType]
     */
    interface Visitor {
        fun returnValueContext(): CollectingResult<ITypeDescriptorConverter<IProgramOutput, IExternalOutputBuffer, CallReturnTarget>, String>
        fun hookValueContext(): CollectingResult<ITypeDescriptorConverter<ISimpleOutput, IHookParameter, ITACSymbolVar>, String>
        fun stateValueContext(): CollectingResult<ITypeDescriptorConverter<ISimpleOutput, ITACSymbolVar, ITACSymbolVar>, String>
        fun externalSummaryArgBindingContext(): CollectingResult<ITypeDescriptorConverter<IProgramOutput, IExternalOutputBuffer, ICVLDataOutput>, String> {
            return this.returnValueContext()
        }
        fun internalSummaryArgBindingContext(): CollectingResult<ITypeDescriptorConverter<IProgramOutput, IInternalSummaryInput, ICVLDataOutput>, String>

        fun eventHookBindingContext(): CollectingResult<ITypeDescriptorConverter<IProgramOutput, IExternalOutputBuffer, ICVLDataOutput>, String> {
            return this.returnValueContext()
        }
    }
}

typealias CallReturnTarget = ICVLDataOutput

typealias ToCVLContext = FromVMContext.Visitor.() -> CollectingResult<ITypeDescriptorConverter<*, *, *>, String>
typealias FromCVLContext = ToVMContext.Visitor.() -> CollectingResult<ITypeDescriptorConverter<*, *, *>, String>

interface FromVMContextDispatcher<O, SRC, DST> {
    /**
     * Visit the appropriate context in the provided [visitor] given `this` context. An instantiated [FromVMContextDispatcher]
     * has basically selected which method inside a [FromVMContext.Visitor] the method [accept] will call.
     */
    fun accept(visitor: FromVMContext.Visitor) : CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String>
}

interface ToVMContextDispatcher<O, SRC, DST> {
    /**
     * Visit the appropriate context in the provided [visitor] given `this` context.
     */
    fun accept(visitor: ToVMContext.Visitor) : CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String>
}

interface PrintingContext {
    val isLibrary: Boolean

    companion object {
        operator fun invoke(isLibrary: Boolean) = object : PrintingContext {
            override val isLibrary: Boolean
                get() = isLibrary
        }
    }
}
@Polymorphic
@Treapable
interface VMTypeDescriptor: AmbiSerializable {

    /**
     * Answers the question "Can I convert from [fromType] to `this` in context [i]?" If the answer is "no" the result
     * is a [CollectingResult.Error] . If the answer is "yes" a [ITypeDescriptorConverter] is returned inside a
     * [CollectingResult.Result] as proof that the conversion is possible.
     *
     * In this way, this function also answers the question "How do I convert from [fromType] to `this` in context [i]?"
     *
     * Note the visitor pattern provided by the [FromVMContextDispatcher] [i] allows the conversion semantics to be changed. This
     * is crucial since dummy semantics are provided on type checking, and changed to real semantics at code generation
     * time. This is due to the fact that code generation types are unavailable to type checking code.
     *
     * Additionally, the visitor semantics allow us to do GADT (generalized algebraic data type) style programming.
     */
    fun <O, SRC, DST> converterFrom(fromType: CVLType.PureCVLType, i: ToVMContextDispatcher<O, SRC, DST>): CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String>

    /**
     * Answers the question "Can I convert to [toType] from `this` in context [i]?" If the answer is "no" the result
     * is a [CollectingResult.Error]. If the answer is "yes" a [ITypeDescriptorConverter] is returned inside a
     * [CollectingResult.Result] as proof that the conversion is possible.
     *
     * In this way, this function also answers the question "How do I convert to [toType] from `this` in context [i]?"
     *
     * Note the visitor pattern provided by the [FromVMContextDispatcher] [i] allows the conversion semantics to be changed. This
     * is crucial since dummy semantics are provided on type checking, and changed to real semantics at code generation
     * time. This is due to the fact that code generation types are unavailable to type checking code.
     *
     * Additionally, the visitor semantics allow us to do GADT (generalized algebraic data type) style programming.
     *
     * should be resilient with respect to PureCVLType subtyping,
     * that is, if there is a CVL type t, such that this VM Type is convertibleTo
     * t, then the value returned by conversion must be valid for all
     * t' where t <: t' (in the CVL type system)
     */
    fun <O, SRC, DST> converterTo(toType: CVLType.PureCVLType, i: FromVMContextDispatcher<O, SRC, DST>): CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String>

    fun <O, SRC, DST> converterFrom(
        fromType: CVLType.PureCVLType,
        context: ToVMContext.Visitor.() -> CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String>,
    ) : CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String> {
        return this.converterFrom(fromType, object : ToVMContextDispatcher<O, SRC, DST> {
            override fun accept(visitor: ToVMContext.Visitor): CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String> {
                return visitor.context()
            }
        })
    }

    fun <O, SRC, DST> converterTo(
        toType: CVLType.PureCVLType,
        context: FromVMContext.Visitor.() -> CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String>
    ) : CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String> {
        return this.converterTo(toType, object : FromVMContextDispatcher<O, SRC, DST> {
            override fun accept(visitor: FromVMContext.Visitor): CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String> {
                return visitor.context()
            }
        })
    }

    fun getPureTypeToConvertFrom(context: ToVMContext): CollectingResult<CVLType.PureCVLType, String> = (this as? VMTypeWithCorrespondence)?.getPureCVLTypeToConvertFrom(context) ?: "VM type $this has no corresponding CVL type to convert from".asError()

    fun hasPureTypeToConvertFrom(context: ToVMContext): Boolean = getPureTypeToConvertFrom(context).isResult()

    fun getPureTypeToConvertTo(context: FromVMContext): CollectingResult<CVLType.PureCVLType, String> = (this as? VMTypeWithCorrespondence)?.getPureCVLTypeToConvertTo(context) ?: "VM type $this has no corresponding CLV type to convert to".asError()

    fun hasPureTypeToConvertTo(context: FromVMContext): Boolean = getPureTypeToConvertTo(context).isResult()

    /**
     * It should very much be that prettyPrint is overridden by implementers, but it is not necessary.
     *
     * Also note that [prettyPrint] should not be used to glean any semantic information about [this] (with the notable
     * exception that [prettyPrint] is used in generating out.json for regression tests
     */
    fun prettyPrint() : String = toString()
    fun canonicalString(ctxt: PrintingContext) : String = prettyPrint()
    fun mergeWith(other: VMTypeDescriptor) : CollectingResult<VMTypeDescriptor, String>
}

/**
 * Describes a [VMTypeDescriptor] which may have some corresponding [CVLType.PureCVLType]
 *
 * @property correspondenceType provides the corresponding [CVLType.PureCVLType] gated behind a conversion check. A null
 * [correspondenceType] means there is no known corresponding [CVLType.PureCVLType]
 *
 * @property getPureCVLTypeToConvertToOrNull given a context, returns a [CVLType.PureCVLType] t, if there is a t such that this
 * descriptor is convertible to t in the given context. A null result may indicate either 1) this descriptor had no
 * correspondence type or 2) this descriptor was not convertible to its correspondence type in the given context
 */
interface VMTypeWithCorrespondence: VMTypeDescriptor {

    /**
     * Gates access to a [VMTypeWithCorrespondence]'s [correspondenceType] behind a conversion check to ensure it is
     * only used when a convertibility check has been successfully performed.
     */
    open class NeedsConversionCheck(private val pureType: CVLType.PureCVLType) {
        open fun convertibleTo(t: VMTypeWithCorrespondence, context: FromVMContext) =
            context.supportsConversion(from = t, to = pureType).map { pureType }

        open fun convertibleFrom(t: VMTypeWithCorrespondence, context: ToVMContext) =
            context.supportsConversion(from = pureType, to = t).map { pureType }

        /**
         * Used to build a nested type from nested correspondence types
         */
        open fun build(cb: (CVLType.PureCVLType) -> CollectingResult<CVLType.PureCVLType, String>): CollectingResult<NeedsConversionCheck, String> = cb(pureType).bind { it.needsConversionCheck() }

        companion object {
            fun List<NeedsConversionCheck>.build(cb: (List<CVLType.PureCVLType>) -> CVLType.PureCVLType) =
                cb(this.map { correspondence -> correspondence.pureType }).needsConversionCheck()

        }
    }

    // Note that even though target/sourceCorrespondenceType has enough information to perform a convertibility check
    // on it's own, as a defensive measure, we do not rely on implementors to do the check
    fun correspondenceTypeToConvertTo(fromVMContext: FromVMContext): CollectingResult<NeedsConversionCheck, String>
    fun correspondenceTypeToConvertFrom(toVMContext: ToVMContext): CollectingResult<NeedsConversionCheck, String>
    fun getPureCVLTypeToConvertTo(context: FromVMContext): CollectingResult<CVLType.PureCVLType, String> = correspondenceTypeToConvertTo(context).bind { it.convertibleTo(this, context) }
    fun getPureCVLTypeToConvertFrom(context: ToVMContext): CollectingResult<CVLType.PureCVLType, String> = correspondenceTypeToConvertFrom(context).bind { it.convertibleFrom(this, context) }
}

interface ContextAgnosticVMTypeWithCorrespondence: VMTypeWithCorrespondence {
    val correspondenceType: CollectingResult<VMTypeWithCorrespondence.NeedsConversionCheck, String>
    override fun correspondenceTypeToConvertTo(fromVMContext: FromVMContext): CollectingResult<VMTypeWithCorrespondence.NeedsConversionCheck, String> = correspondenceType
    override fun correspondenceTypeToConvertFrom(toVMContext: ToVMContext): CollectingResult<VMTypeWithCorrespondence.NeedsConversionCheck, String> = correspondenceType
}

interface EmbeddableVMTypeDescriptor: ContextAgnosticVMTypeWithCorrespondence {
    val isomorphicType: CollectingResult<CVLType.PureCVLType, String>
    class ConversionCheckMustSucceed(pureType: CVLType.PureCVLType): VMTypeWithCorrespondence.NeedsConversionCheck(pureType)

    override val correspondenceType: CollectingResult<VMTypeWithCorrespondence.NeedsConversionCheck, String>
        get() = isomorphicType.map(::ConversionCheckMustSucceed)
}

/**
 * A type descriptor with a 1-to-1 corresponding [CVLType.PureCVLType]
 */
interface ISOVMTypeDescriptor : EmbeddableVMTypeDescriptor {
    override val isomorphicType: CollectingResult.Result<CVLType.PureCVLType>
}


/**
 * @property bitwidth the number of bits that a value of this type occupies (for example, a uint160 occupies 160 bits
 *                      and thus has a bitwidth of 160)
 *
 * @property representationSize the number of bits used to hold a value of this type in the VM (for example, a uint160
 *                              is represented using 256 bits)
 */
interface VMValueTypeDescriptor : VMTypeDescriptor {
    fun toTag(): Tag
    val bitwidth: Int
}

interface VMNumericValueTypeDescriptor : VMValueTypeDescriptor {
    val minValue: BigInteger
    val maxValue: BigInteger
    val isBigEndian: Boolean // true in EVM
}

interface VMSignedNumericValueTypeDescriptor : VMNumericValueTypeDescriptor

interface VMUnsignedNumericValueTypeDescriptor : VMNumericValueTypeDescriptor

interface VMReferenceTypeDescriptor: VMTypeDescriptor {
    val location: LocationSpecifier?

    fun isDynamicSize(): Boolean
}

interface VMArrayTypeDescriptor : VMReferenceTypeDescriptor {
    val elementSize: BigInteger
    val elementType: VMTypeDescriptor
    // in EVM:
    // byte[] = elementSize 32, elementType byte
    // bytes = elementSize 1, elementType byte
    // uint[][] = elementSize 32, elementType descriptor uint[]
    // bytes[] = elementSize 32, elementType bytes
}

interface VMMappingDescriptor : VMReferenceTypeDescriptor {
    val keyType: VMTypeDescriptor
    val valueType: VMTypeDescriptor
}

interface VMStructDescriptor : VMTypeDescriptor {
    val fieldTypes: Map<String, VMTypeDescriptor>
}

interface VMDynamicArrayTypeDescriptor : VMArrayTypeDescriptor {
    val lengthType: VMValueTypeDescriptor
}

interface VMStaticArrayType : VMArrayTypeDescriptor {
    val numElements: BigInteger
}

@KSerializable
data class VMFunctionReturn(val returns: List<VMTypeDescriptor>) : VMTypeDescriptor {
    init {
        check(returns.size > 1) {
            "VMFunctionReturn should only be used to encapsulate multiple return values"
        }
    }

    override fun <O, SRC, DST> converterTo(
        toType: CVLType.PureCVLType,
        i: FromVMContextDispatcher<O, SRC, DST>,
    ): CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String> {
        return "A function which returns multiple values must be assigned to multiple lhs variables.".asError()
    }

    override fun mergeWith(other: VMTypeDescriptor) = "function returns are not mergeable".asError()

    override fun <O, SRC, DST> converterFrom(
        fromType: CVLType.PureCVLType,
        i: ToVMContextDispatcher<O, SRC, DST>,
    ): CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String> {
        throw CertoraInternalException(CertoraInternalErrorType.CVL_TYPE_CHECKER, "Trying to convert to VMFunctionReturn $this does not make sense.")
    }

    override fun toString(): String = returns.joinToString(prefix = "(", postfix = ")")
}
