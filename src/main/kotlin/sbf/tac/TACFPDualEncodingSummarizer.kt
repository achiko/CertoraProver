/*
 *     The Certora Prover
 *     Copyright (C) 2026  Certora Ltd.
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

package sbf.tac

import analysis.opt.DiamondSimplifier.registerMergeableAnnot
import config.Config
import config.DestructiveOptimizationsModeEnum
import sbf.domains.INumValue
import sbf.domains.IOffset
import sbf.domains.IPTANodeFlags
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACSymbol
import datastructures.stdcollections.*
import vc.data.tacexprutil.asSym
import vc.data.tacexprutil.asVarOrNull
import java.math.BigInteger

@OptIn(Config.DestructiveOptimizationsOption::class)
/** Helper for internal TAC debugging **/
private fun debugSymbols(msg: String, symbols: List<TACSymbol.Var>): List<TACCmd.Simple> {
    return if (Config.DestructiveOptimizationsMode.get() == DestructiveOptimizationsModeEnum.DISABLE) {
        listOf(
            TACCmd.Simple.AnnotationCmd(
                TACCmd.Simple.AnnotationCmd.Annotation(
                    tac.MetaKey<DebugSnippet>("debug.symbols").registerMergeableAnnot(),
                    DebugSnippet(msg, symbols)
                )
            )
        )
    } else {
        listOf()
    }
}

/**
 * Utilities for packing/unpacking two u64 values and one byte (stored as Bit256 TAC variables)
 * into/from a single Bit256 value.
 *
 * Layout: bits [0..63] = low (u64), bits [64..127] = high (u64), bits [128..135] = flag (u8)
 */
object DualEncoding {

    /** The three components of a packed [DualEncoding] value. */
    data class PackedF64(val fp: TACSymbol.Var, val int: TACSymbol.Var, val isIntActive: TACSymbol)

    /**
     * Pack [tuple] into a single Bit256:
     * `result = (byte & 0xFF) << 128 + (hi & MASK64) << 64 + (lo & MASK64)`
     *
     * Layout: bits [0..63] = lo, bits [64..127] = hi, bits [128..135] = byte
     *
     * Creates a fresh variable for the result and appends the assignment to [cmds].
     */
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags : IPTANodeFlags<TFlags>>
        pack(tuple: PackedF64, cmds: MutableList<TACCmd.Simple>): TACSymbol.Var {
        cmds += debugSymbols("Start dualEncoding.pack [fp::int]", listOf(tuple.fp, tuple.int))
        val packed128 = mergeU128(tuple.fp.asSym(), tuple.int.asSym(), cmds, mayMaskLowBits = true)
        val shiftedByte =
            TACExpr.BinOp.ShiftLeft(TACExpr.BinOp.BWAnd(tuple.isIntActive.asSym(), sbfTacB.mask8), sbfTacB.c128)
        val res = vFac.mkFreshIntVar()
        cmds += assign(res, TACExpr.Vec.Add(shiftedByte, packed128.asSym()))
        cmds += debugSymbols("End dualEncoding.pack [res]", listOf(res))
        return res
    }

    /**
     * Unpack a Bit256 [packed] that encodes two u64 values and an extra byte.
     *
     * Creates fresh variables and appends assignments to [cmds].
     * Returns a [PackedF64] where normally:
     * - `fp          = packed & MASK64`
     * - `int         = (packed >> 64) & MASK64`
     * - `isIntActive = (packed >> 128) & 0xFF`
     *
     * **Special constants:** when [packed] is a known compiler-generated f64 literal, the integer shadow is
     * set to the corresponding u64 integer value and `isIntActive` is forced to `1`.
     * This handles the case where the compiler passes a f64 constant directly (without going through
     * `__floatundidf`), so the shadow would otherwise be inactive.
     */
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    internal fun <TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags : IPTANodeFlags<TFlags>>
        unpack(packed: TACExpr.Sym, cmds: MutableList<TACCmd.Simple>): PackedF64 {
        cmds += debugSymbols("Start dualEncoding.unpack [arg]", listOf(packed.asVarOrNull).filterNotNull())
        val rawFp = vFac.mkFreshIntVar()
        val rawInt = vFac.mkFreshIntVar()
        val rawIsIntActive = vFac.mkFreshIntVar()
        cmds += splitU128(packed, rawFp, rawInt)
        cmds += assign(
            rawIsIntActive,
            TACExpr.BinOp.BWAnd(TACExpr.BinOp.ShiftRightLogical(packed, sbfTacB.c128), sbfTacB.mask8)
        )

        // Here special constants that must be recognized
        // -- +0.0
        val zeroF64    = sbfTacB.mkConst(0x0000000000000000)
        // -- 1.0
        val oneF64     = sbfTacB.mkConst(0x3FF0000000000000)
        // -- 2.0
        val twoF64     = sbfTacB.mkConst(0x4000000000000000)
        // -- 2^64 - 2^11: the largest f64 whose integer part fits exactly in an u64
        val maxU64FP64 = sbfTacB.mkConst(0x43EFFFFFFFFFFFFF)

        val isF64Constant = sbfTacB {
                (packed eq zeroF64) or
                (packed eq oneF64) or
                (packed eq twoF64) or
                (packed eq maxU64FP64)
        }

        val fp = vFac.mkFreshIntVar()
        val int = vFac.mkFreshIntVar()
        val isIntActive = vFac.mkFreshIntVar()

        // fp part
        cmds += assign(fp, sbfTacB {
            switch(isF64Constant to packed, default = rawFp.asSym())
        })
        // int part
        cmds += assign(int, sbfTacB {
            switch(
                // Detect here constants generated directly by compiler: we need to convert each bit pattern
                // to its integer value. The default case handles correctly the case that packed is zero
                packed eq oneF64 to mkConst(1L).asSym(),
                packed eq twoF64 to mkConst(2L).asSym(),
                packed eq maxU64FP64 to mkConst(BigInteger.TWO.pow(64) - BigInteger.valueOf(2048)).asSym(),
                default = rawInt.asSym()
            )
        })
        // isActive flag
        cmds += assign(isIntActive, sbfTacB {
            switch(isF64Constant to ONE, default = rawIsIntActive.asSym())
        })
        cmds += debugSymbols("End DualEncoding.unpack [fp::int::isIntActive]", listOf(fp, int, isIntActive))

        return PackedF64(fp, int, isIntActive)
    }
}

/**
 * f64 compiler-rt summaries based on a **dual encoding** ([DualEncoding.PackedF64]) that carries both the
 * IEEE-754 bit pattern and an integer shadow alongside each f64 value. The goal of this encoding is to be precise
 * with floating point numbers that have zero fractional mantissa.
 *
 * The integer shadow records the exact u64 value whenever the f64 was produced by `__floatundidf`
 * (u64 → f64) or is a known constant. Arithmetic operations propagate the shadow precisely when both
 * operands have an active shadow; otherwise the shadow is left unconstrained. Comparisons can then
 * operate on the integer shadow directly, improving precision over [SummarizeFPCompilerRt] for the
 * common pattern of casting an u64 to f64 and comparing or converting back.
 */
class SummarizeFPDualEncoding<TNum : INumValue<TNum>, TOffset : IOffset<TOffset>, TFlags : IPTANodeFlags<TFlags>>
    : SummarizeFPCompilerRt<TNum, TOffset, TFlags>() {

    /**
     * Helper for binary f64 operations.
     *
     * It does the unpack/pack of the arguments [arg1] and [arg2] and calls [opFp] and [opInt] on the fp and integer shadow.
     * The result is assigned to [res].
     */
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    private fun binaryFPWithPackedResult(
        res: TACSymbol.Var,
        arg1: TACSymbol,
        arg2: TACSymbol,
        opInt: (intRes: TACSymbol.Var,
                isIntActiveRes: TACSymbol.Var,
                intArg1: TACSymbol.Var, intArg2: TACSymbol.Var,
                isIntActive1: TACSymbol, isIntActive2: TACSymbol) -> List<TACCmd.Simple>,
        opFp: (fpRes: TACSymbol.Var, fpArg1: TACSymbol.Var, fpArg2: TACSymbol.Var) -> List<TACCmd.Simple>
    ): List<TACCmd.Simple> {
        val cmds = mutableListOf<TACCmd.Simple>()

        // Unpack
        val (fpArg1, intArg1, isIntActive1) = DualEncoding.unpack(arg1.asSym(), cmds)
        val (fpArg2, intArg2, isIntActive2) = DualEncoding.unpack(arg2.asSym(), cmds)

        // Operation over integer and fp parts
        val fpRes = vFac.mkFreshIntVar()
        val intRes = vFac.mkFreshIntVar()
        val isIntActiveRes = vFac.mkFreshIntVar()
        cmds += opInt(intRes, isIntActiveRes,intArg1, intArg2, isIntActive1, isIntActive2)
        cmds += opFp(fpRes, fpArg1, fpArg2)

        // Pack
        cmds += assign(res, DualEncoding.pack(DualEncoding.PackedF64(fpRes, intRes, isIntActiveRes), cmds).asSym())
        return cmds
    }

    /**
     * Helper for unary f64 operations.
     *
     * It does the unpack/pack of the [arg] and calls [opFp] and [opInt] on the fp and integer shadow.
     * The result is assigned to [res].
     */
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    private fun unaryFPWithPackedResult(
        res: TACSymbol.Var,
        arg: TACSymbol,
        opInt: (intRes: TACSymbol.Var, intArg: TACSymbol.Var, isIntActiveArg: TACSymbol) -> List<TACCmd.Simple>,
        opFp: (fpRes: TACSymbol.Var, fpArg: TACSymbol.Var) -> List<TACCmd.Simple>
    ): List<TACCmd.Simple> {
        val cmds = mutableListOf<TACCmd.Simple>()

        // Unpack
        val (fp, int, isIntActive) = DualEncoding.unpack(arg.asSym(), cmds)

        // Operation over integer and fp parts
        val fpRes = vFac.mkFreshIntVar()
        val intRes = vFac.mkFreshIntVar()
        cmds += opInt(intRes, int, isIntActive)
        cmds += opFp(fpRes, fp)

        // Pack
        cmds += assign(res, DualEncoding.pack(DualEncoding.PackedF64(fpRes, intRes, isIntActive), cmds).asSym())
        return cmds
    }

    /**
     * Helper for binary f64 operations that return a scalar result (no packing).
     */
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    private fun binaryFPWithScalarResult(
        res: TACSymbol.Var,
        arg1: TACSymbol,
        arg2: TACSymbol,
        opFp: (fpRes: TACSymbol.Var, fpArg1: TACSymbol.Var, fpArg2: TACSymbol.Var) -> List<TACCmd.Simple>,
        opInt: (intRes: TACSymbol.Var, intArg1: TACSymbol.Var, intArg2: TACSymbol.Var) -> List<TACCmd.Simple>
    ): List<TACCmd.Simple> {
        val cmds = mutableListOf<TACCmd.Simple>()
        val (fpArg1, intArg1, isActiveArg1) = DualEncoding.unpack(arg1.asSym(), cmds)
        val (fpArg2, intArg2, isActiveArg2) = DualEncoding.unpack(arg2.asSym(), cmds)
        val fpRes = vFac.mkFreshIntVar()
        val intRes = vFac.mkFreshIntVar()
        cmds += opInt(intRes, intArg1, intArg2)
        cmds += opFp(fpRes, fpArg1, fpArg2)
        // Reduced product: res is always constrained by fpRes (fp encoding), and additionally
        // constrained by intRes when both shadows are active (integer encoding).
        cmds += assign(res, fpRes.asSym())
        cmds += assume(
            sbfTacB { not((isActiveArg1 eq ONE) and (isActiveArg2 eq ONE)) or (res eq intRes) },
            "if shadow integers are active, then constrain further the result"
        )
        return cmds
    }

    // f64 -> u64
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    override fun summarizeFixunsdfdi(
        res: TACSymbol.Var,
        arg: TACSymbol // f64
    ): List<TACCmd.Simple> {
        val cmds = mutableListOf<TACCmd.Simple>()
        val nondetV = vFac.mkFreshIntVar()
        cmds += havoc(nondetV)
        val (_, intArg, isActiveInt) = DualEncoding.unpack(arg.asSym(), cmds)
        cmds += assign(res, sbfTacB { switch((isActiveInt eq ONE) to intArg.asSym(), default = nondetV.asSym()) })
        return cmds
    }

    // u64 -> f64
    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    override fun summarizeFloatundidf(
        res: TACSymbol.Var,
        arg: TACSymbol // u64
    ): List<TACCmd.Simple> {
        val cmds = mutableListOf<TACCmd.Simple>()
        val fpRes = vFac.mkFreshIntVar()
        val intRes = vFac.mkFreshIntVar()
        cmds += Debug.startFunction("floatundidf -- fp part")
        cmds += super.summarizeFloatundidf(fpRes, arg)
        cmds += Debug.endFunction("floatundidf -- fp part")
        cmds += Debug.startFunction("floatundidf -- int part")
        cmds += assign(intRes, arg.asSym())
        cmds += assign(res, DualEncoding.pack(DualEncoding.PackedF64(fpRes, intRes, sbfTacB.ONE.asSym), cmds).asSym())
        cmds += Debug.endFunction("floatundidf -- int part")
        return cmds
    }


    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    override fun summarizeUnorddf2(
        res: TACSymbol.Var,
        arg1: TACSymbol,
        arg2: TACSymbol
    ): List<TACCmd.Simple> {
        val cmds = mutableListOf<TACCmd.Simple>()
        val (fpArg1, _, _) = DualEncoding.unpack(arg1.asSym(), cmds)
        val (fpArg2, _, _) = DualEncoding.unpack(arg2.asSym(), cmds)
        return super.summarizeUnorddf2(res, fpArg1, fpArg2)
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    override fun summarizeAdddf3(
        res: TACSymbol.Var,
        arg1: TACSymbol,
        arg2: TACSymbol
    ): List<TACCmd.Simple> {
        val nondetV = vFac.mkFreshIntVar()
        return binaryFPWithPackedResult(
            res, arg1, arg2,
            opInt = { intRes, isIntActiveRes, intArg1, intArg2, isIntActiveArg1, isIntActiveArg2 ->
                val intAdd = vFac.mkFreshIntVar()
                val notOverflow = sbfTacB { intAdd le mkConst(BigInteger.TWO.pow(53)) }
                listOf(
                    havoc(nondetV),
                    assign(intAdd, sbfTacB { intArg1 add intArg2 }),
                    assign(intRes, sbfTacB {
                        switch(
                            (isIntActiveArg1 eq ONE) and (isIntActiveArg2 eq ONE) and notOverflow to intAdd.asSym(),
                            default = nondetV.asSym()
                        )
                    }),
                    assign(isIntActiveRes, sbfTacB {
                        switch(
                            (isIntActiveArg1 eq ONE) and (isIntActiveArg2 eq ONE) and notOverflow to ONE,
                            default = ZERO
                        )
                    })
                )
            },
            opFp = { fpRes, fpArg1, fpArg2 ->
                super.summarizeAdddf3(fpRes, fpArg1, fpArg2)
            }
        )
    }


    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    override fun summarizeSubdf3(
        res: TACSymbol.Var,
        arg1: TACSymbol,
        arg2: TACSymbol
    ): List<TACCmd.Simple> {
        val nondetV = vFac.mkFreshIntVar()
        return binaryFPWithPackedResult(
            res, arg1, arg2,
            opInt = { intRes, isIntActiveRes, intArg1, intArg2, isIntActiveArg1, isIntActiveArg2 ->
                val notOverflow = sbfTacB { (intArg1 ge intArg2) }
                listOf(
                    havoc(nondetV),
                    assign(intRes, sbfTacB {
                        switch(
                            (isIntActiveArg1 eq ONE) and (isIntActiveArg2 eq ONE) and notOverflow to (intArg1 sub intArg2),
                            default = nondetV.asSym()
                        )
                    }),
                    assign(isIntActiveRes, sbfTacB {
                        switch(
                            (isIntActiveArg1 eq ONE) and (isIntActiveArg2 eq ONE) and notOverflow to ONE,
                            default = ZERO
                        )
                    })
                )
            },
            opFp = { fpRes, fpArg1, fpArg2 ->
                super.summarizeSubdf3(fpRes, fpArg1, fpArg2)
            }
        )
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    override fun summarizeMuldf3(
        res: TACSymbol.Var,
        arg1: TACSymbol,
        arg2: TACSymbol
    ): List<TACCmd.Simple> {
        val nondetV = vFac.mkFreshIntVar()
        return binaryFPWithPackedResult(
            res, arg1, arg2,
            opInt = { intRes, isIntActiveRes, intArg1, intArg2, isIntActiveArg1, isIntActiveArg2 ->
                val intMul = vFac.mkFreshIntVar()
                val notOverflow = sbfTacB { intMul le mkConst(BigInteger.TWO.pow(53)) }
                listOf(
                    havoc(nondetV),
                    assign(intMul, sbfTacB { intArg1 mul intArg2 }),
                    assign(intRes, sbfTacB {
                        switch(
                            (isIntActiveArg1 eq ONE) and (isIntActiveArg2 eq ONE) and notOverflow to intMul.asSym(),
                            default = nondetV.asSym()
                        )
                    }),
                    assign(isIntActiveRes, sbfTacB {
                        switch(
                            (isIntActiveArg1 eq ONE) and (isIntActiveArg2 eq ONE) and notOverflow to ONE,
                            default = ZERO
                        )
                    })
                )
            },
            opFp = { fpRes, fpArg1, fpArg2 ->
                super.summarizeMuldf3(fpRes, fpArg1, fpArg2)
            }
        )
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    override fun summarizeDivdf3(
        res: TACSymbol.Var,
        arg1: TACSymbol,
        arg2: TACSymbol
    ): List<TACCmd.Simple> {
        return binaryFPWithPackedResult(
            res, arg1, arg2,
            opInt = { intRes, isIntActiveRes, _, _, _, _ ->
                // For division, we can only propagate isIntActiveRes to 1 if intArg2 ≠ 0  AND intArg1 % intArg2 == 0  AND  intArg1 / intArg2 ≤ 2^53.
                // Since we don't like modulo operations, we just havoc for now until we have a use case.
                listOf(havoc(intRes), assign(isIntActiveRes, sbfTacB.ZERO))
            },
            opFp = { fpRes, fpArg1, fpArg2 ->
                super.summarizeDivdf3(fpRes, fpArg1, fpArg2)
            }
        )
    }

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    override fun summarizeNegdf3(
        res: TACSymbol.Var,
        arg: TACSymbol
    ): List<TACCmd.Simple> =
        unaryFPWithPackedResult(
            res, arg,
            opInt = { intRes, _, _ -> listOf(havoc(intRes)) },
            opFp = { fpRes, fpArg -> super.summarizeNegdf3(fpRes, fpArg) }
        )

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    override fun summarizeEqdf2(
        res: TACSymbol.Var,
        arg1: TACSymbol,
        arg2: TACSymbol
    ): List<TACCmd.Simple> =
        binaryFPWithScalarResult(
            res, arg1, arg2,
            opFp = { r, fpArg1, fpArg2 -> super.summarizeEqdf2(r, fpArg1, fpArg2) },
            opInt = { r, intArg1, intArg2 ->
                val cmds = mutableListOf<TACCmd.Simple>()
                val (trueS, falseS) = eqReturnValues(cmds)
                cmds += assign(r, sbfTacB { switch((intArg1 eq intArg2) to trueS, default = falseS) })
                cmds
            })

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    override fun summarizeNedf2(
        res: TACSymbol.Var,
        arg1: TACSymbol,
        arg2: TACSymbol
    ): List<TACCmd.Simple> =
        binaryFPWithScalarResult(
            res, arg1, arg2,
            opFp = { r, fpArg1, fpArg2 -> super.summarizeNedf2(r, fpArg1, fpArg2) },
            opInt = { r, intArg1, intArg2 ->
                val cmds = mutableListOf<TACCmd.Simple>()
                val (trueV, falseS) = neReturnValues(cmds)
                cmds += assign(r, sbfTacB { switch((intArg1 neq intArg2) to trueV, default = falseS) })
                cmds
            })

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    override fun summarizeLtdf2(
        res: TACSymbol.Var,
        arg1: TACSymbol,
        arg2: TACSymbol
    ): List<TACCmd.Simple> =
        binaryFPWithScalarResult(
            res, arg1, arg2,
            opFp = { r, fpArg1, fpArg2 -> super.summarizeLtdf2(r, fpArg1, fpArg2) },
            opInt = { r, intArg1, intArg2 ->
                val cmds = mutableListOf<TACCmd.Simple>()
                val (trueV, falseV) = ltReturnValues(cmds)
                // Shadow integers are always coming from __floatundidf (u64 → f64).
                // Since we check for under/overflows in add/sub/mul then using unsigned comparison is correct.
                cmds += assign(r, sbfTacB { switch((intArg1 lt intArg2) to trueV, default = falseV) })
                cmds
            })

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    override fun summarizeLedf2(
        res: TACSymbol.Var,
        arg1: TACSymbol,
        arg2: TACSymbol
    ): List<TACCmd.Simple> =
        binaryFPWithScalarResult(
            res, arg1, arg2,
            opFp = { r, fpArg1, fpArg2 -> super.summarizeLedf2(r, fpArg1, fpArg2) },
            opInt = { r, intArg1, intArg2 ->
                val cmds = mutableListOf<TACCmd.Simple>()
                val (trueV, falseV) = leReturnValues(cmds)
                // Shadow integers are always coming from __floatundidf (u64 → f64).
                // Since we check for under/overflows in add/sub/mul then using unsigned comparison is correct.
                cmds += assign(r, sbfTacB { switch((intArg1 le intArg2) to trueV, default = falseV) })
                cmds
            })

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    override fun summarizeGedf2(
        res: TACSymbol.Var,
        arg1: TACSymbol,
        arg2: TACSymbol
    ): List<TACCmd.Simple> =
        binaryFPWithScalarResult(
            res, arg1, arg2,
            opFp = { r, fpArg1, fpArg2 -> super.summarizeGedf2(r, fpArg1, fpArg2) },
            opInt = { r, intArg1, intArg2 ->
                val cmds = mutableListOf<TACCmd.Simple>()
                val (trueV, falseV) = geReturnValues(cmds)
                // Shadow integers are always coming from __floatundidf (u64 → f64).
                // Since we check for under/overflows in add/sub/mul then using unsigned comparison is correct.
                cmds += assign(r, sbfTacB { switch((intArg1 ge intArg2) to trueV, default = falseV) })
                cmds
            })

    context(SbfCFGToTAC<TNum, TOffset, TFlags>)
    override fun summarizeGtdf2(
        res: TACSymbol.Var,
        arg1: TACSymbol,
        arg2: TACSymbol
    ): List<TACCmd.Simple> =
        binaryFPWithScalarResult(
            res, arg1, arg2,
            opFp = { r, fpArg1, fpArg2 -> super.summarizeGtdf2(r, fpArg1, fpArg2) },
            opInt = { r, intArg1, intArg2 ->
                val cmds = mutableListOf<TACCmd.Simple>()
                val (trueV, falseV) = gtReturnValues(cmds)
                // Shadow integers are always coming from __floatundidf (u64 → f64).
                // Since we check for under/overflows in add/sub/mul then using unsigned comparison is correct.
                cmds += assign(r, sbfTacB { switch((intArg1 gt intArg2) to trueV, default = falseV) })
                cmds
            })
}
