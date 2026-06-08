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

package sbf.tac

import analysis.opt.PatternRewriter
import analysis.opt.PatternRewriter.Key.*
import config.Config
import datastructures.stdcollections.*
import evm.twoToThe
import tac.Tag
import utils.*
import utils.ModZm.Companion.lowOnes
import vc.data.TACExpr
import vc.data.asTACExpr
import java.math.BigInteger

fun PatternRewriter.solanaPatternsList() = listOf(

    /**
     * `x xor y == 0` ~~> `x == y`
     */
    PatternHandler(
        name = "xor-eq-0",
        pattern = {
            (lSym(A) xor lSym(B)) eq zero
        },
        handle = {
            sym(A) eq sym(B)
        },
        TACExpr.BinRel.Eq::class.java
    ),

    /**
     * `x | y == 0` ~~> `x == 0 && y == 0`
     */
    PatternHandler(
        name = "bworEq0",
        pattern = {
            (lSym(A) bwOr lSym(B)) eq zero
        },
        handle = {
            LAnd(
                Eq(sym(A), Zero),
                Eq(sym(B), Zero),
            )
        },
        TACExpr.BinRel.Eq::class.java
    ),
) + runIf(Config.extraSolanaPatterns.get()) {
    listOf(
        /**
         * `2^n * (a >> n)`   ~~~>   `a & 0xff...ff0000`
         * if `a` is bv256. The number of 0 bits in the mask is `n`.
         */
        PatternHandler(
            name = "mul-shr",
            pattern = {
                c(C1) anyMul maybeNarrow(lSym256(A) shr c(I1))
            },
            handle = {
                runIf(C1.n == twoToThe(I1.n)) {
                    BWAnd(sym(A), (Tag.Bit256.maxUnsigned - lowOnes(I1.n)).asTACExpr)
                }
            },
            TACExpr.Vec.IntMul.Binary::class.java, TACExpr.Vec.Mul::class.java
        ),


        /**
         * `a & mask1 + a & mask2` ~~~> `a`
         * if `a` is bv256, and `mask1` and `mask2` complement each other.
         */
        PatternHandler(
            name = "complement-masks",
            pattern = {
                maybeNarrow(c(C1) bwAnd lSym256(A)) anyAdd
                    maybeNarrow(c(C2) bwAnd lSym256(B))
            },
            handle = {
                runIf(
                    C1.n or C2.n == Tag.Bit256.maxUnsigned &&
                        C1.n and C2.n == BigInteger.ZERO &&
                        src(A) == src(B)
                ) {
                    sym(A)
                }
            },
            TACExpr.Vec.IntAdd.Binary::class.java, TACExpr.Vec.Add.Binary::class.java
        ),

        /**
         * `a * const1 / const2` ~~~>
         *    `a * (const1/const2)` if const2 is a divisor of const1
         *    `a / (const2/const1)` if const1 is a divisor of const2
         */
        PatternHandler(
            name = "div-mul-consts",
            pattern = {
                maybeNarrow(lSym(A) intMul c(C1)) intDiv c(C2)
            },
            handle = {
                runIf(C1.n != BigInteger.ZERO && C2.n != BigInteger.ZERO) {
                    when {
                        C1.n % C2.n == BigInteger.ZERO ->
                            IntMul(sym(A), (C1.n / C2.n).asTACExpr)

                        C2.n % C1.n == BigInteger.ZERO ->
                            IntDiv(sym(A), (C2.n / C1.n).asTACExpr)

                        else -> null
                    }
                }
            },
            TACExpr.BinOp.IntDiv::class.java
        ),


        /**
         * `safeMathNarrow(a *int const1) / const2` ~~~>
         *    `safeMathNarrow(a *int (const1/const2))` if const2 is a divisor of const1
         *    `safeMathNarrow(a /int (const2/const1))` if const1 is a divisor of const2
         *
         * Sound because: safeMathNarrow guarantees `a *int const1` fits in [0, 2^256),
         * so the bv256 division equals mathematical division (no wrapping, divisor != 0).
         */
        PatternHandler(
            name = "bv-div-mul-consts",
            pattern = {
                safeMathNarrow(lSym(A) intMul c(C1)) div c(C2)
            },
            handle = {
                runIf(C1.n != BigInteger.ZERO && C2.n != BigInteger.ZERO) {
                    when {
                        C1.n % C2.n == BigInteger.ZERO ->
                            safeMathNarrow(IntMul(sym(A), (C1.n / C2.n).asTACExpr), Tag.Bit256)

                        C2.n % C1.n == BigInteger.ZERO ->
                            safeMathNarrow(IntDiv(sym(A), (C2.n / C1.n).asTACExpr), Tag.Bit256)

                        else -> null
                    }
                }
            },
            TACExpr.BinOp.Div::class.java
        ),

        /**
         * `safeMathNarrow(a:bv256)` ~~~> a:bv256
         * Follows copy chains: if the operand is a Tag.Int copy of a Tag.Bit256 variable,
         * the pattern still matches (e.g., `safeMathNarrow(I1078)` where `I1078 = R1066:bv256`).
         */
        PatternHandler(
            name = "redundant-narrow",
            pattern = {
                safeMathNarrow(defChainLSym(A) { it.tag is Tag.Bit256 })
            },
            handle = {
                sym(A)
            },
            TACExpr.Apply.Unary::class.java
        ),

        /**
         * `safeMathNarrow(safeMathNarrow(x))` ~~~> x
         */
        PatternHandler(
            name = "redundant-narrow2",
            pattern = {
                safeMathNarrow(safeMathNarrow(lSym(A)))
            },
            handle = {
                safeMathNarrow(sym(A), Tag.Bit256)
            },
            TACExpr.Apply.Unary::class.java
        ),

        /**
         * This, together with the following pattern simplifies a solana 128-bit checked multiplication by a constant.
         * It's rather specific. Look at the `PatternRewriterTest` to see the specific pattern and the effect of
         * applying the rewriter (and intervalsRewriter) to it.
         *
         * We need two patterns because the original pattern also has an assume on the higher bits of the multiplication,
         * and so we first simplify it (using this first pattern), and then simplify the actually result.
         *
         * Generally speaking, the multiplication pattern of `x * c` is:
         * ```
         *    low = (x & low-64-bits) * c
         *    high = (x >> 64) * c
         *    resultLow = low & low-64-bits
         *    resultHigh = (low >> 64) + high
         *    result = resultLow + (resultHigh << 64)
         * ```
         * The pattern here replaces `resultHigh` with `(x * c) >> 64`. The next pattern assumes this replacement happens
         * and replaces `result` with `x * c`.
         */
        PatternHandler(
            name = "solana-mul-by-const-1",
            pattern = {
                val mask = c(lowOnes(64))
                val low = lSym(A) bwAnd mask
                val high = lSym(B) shr c(0x40)
                val highMul = safeMathNarrow(high intMul c(C1) { it >= BigInteger.ZERO && it <= lowOnes(64)})
                val lowMul = safeMathNarrow(low intMul c(C1))
                val lowHigh = lowMul shr c(0x40)

                lowHigh intAdd highMul
            },
            handle = {
                runIf(src(A) == src(B)) {
                    ShiftRightLogical(safeMathNarrow(IntMul(sym(A), C1.n.asTACExpr), Tag.Bit256), 0x40.asTACExpr)
                }
            },
            TACExpr.Vec.IntAdd.Binary::class.java
        ),

        PatternHandler(
            name = "solana-mul-by-const-2",
            pattern = {
                val mask = c(lowOnes(64))
                val low = lSym(A) bwAnd mask
                val lowMul = safeMathNarrow(low intMul c(C1) { it >= BigInteger.ZERO && it <= lowOnes(64)})
                val lowlow = lowMul bwAnd mask
                val newHigh = safeMathNarrow(lSym(B) intMul c(C1)) bwAnd c(BigInteger("ffffffffffffffffffffffffffffffffffffffffffffffff0000000000000000", 16))
                lowlow intAdd safeMathNarrow(newHigh)
            },
            handle = {
                runIf(src(A) == src(B)) {
                    safeMathNarrow(IntMul(sym(A), C1.n.asTACExpr), Tag.Bit256)
                }
            },
            TACExpr.Vec.IntAdd.Binary::class.java
        ),


        /**
         * Recognizes a fixed-point multiply split into high/low 64-bit parts and collapses it:
         *
         * ```
         * I_hi     = (X >> K) *int Y                          // K in 1..63
         * R_lo_raw = safeMathNarrow(2^(64-K) *int X) & (2^64-1)
         * I_prod   = Y *int R_lo_raw
         * result   = (safeMathNarrow(I_prod) >> 0x40) +int safeMathNarrow(I_hi)
         * ```
         * ~~~> `(X *int Y) /int 2^K`
         *
         * Sound because: `(X >> K) * Y + (X * 2^(64-K) mod 2^64) * Y / 2^64 = X * Y / 2^K`
         * in unbounded integer arithmetic (the split avoids 64-bit overflow).
         */
        PatternHandler(
            name = "fixed-point-multiply",
            pattern = {
                val mask = c(lowOnes(64))
                val hi = safeMathNarrow((lSym(A) shr c(I1)) intMul lSym(B))
                val lo = lSym(D) intMul (safeMathNarrow(c(C1) intMul lSym(C)) bwAnd mask)
                (safeMathNarrow(lo) shr c(0x40)) intAdd hi
            },
            handle = {
                val shift = I1.n
                runIf(
                    shift in 1..63 &&
                        src(A) == src(C) &&
                        src(B) == src(D) &&
                        C1.n == twoToThe(64 - shift)
                ) {
                    IntDiv(IntMul(sym(A), sym(B)), twoToThe(shift).asTACExpr)
                }
            },
            TACExpr.Vec.IntAdd.Binary::class.java
        ),

        /**
         * Recognizes a constant multiply split into high/low 64-bit parts and collapses it:
         *
         * ```
         * low    = safeMathNarrow(C *int X) & (2^64-1)        // C = 2^(64-K), K in 1..63
         * high   = 2^64 *int (X >> K)
         * result = safeMathNarrow(high) +int low
         * ```
         * ~~~> `X *int C`
         *
         * Sound because: `(X >> K) * 2^64 + (X * C) mod 2^64 = X * C`
         * when `C = 2^(64-K)`, i.e., `C * 2^K = 2^64`.
         */
        PatternHandler(
            name = "const-mul-split",
            pattern = {
                val mask = c(lowOnes(64))
                val low = safeMathNarrow(c(C1) intMul lSym(A)) bwAnd mask
                val high = c(twoToThe(64)) intMul (lSym(B) shr c(I1))
                safeMathNarrow(high) intAdd low
            },
            handle = {
                val shift = I1.n
                runIf(
                    shift in 1..63 &&
                        src(A) == src(B) &&
                        C1.n == twoToThe(64 - shift)
                ) {
                    IntMul(sym(A), C1.n.asTACExpr)
                }
            },
            TACExpr.Vec.IntAdd.Binary::class.java
        ),

        /**
         * Recognizes a fixed-point multiply via intermediate constant scaling:
         *
         * ```
         * safeMathNarrow(X *int safeMathNarrow(C *int Y)) >> K  // K in 1..64
         * ```
         * ~~~> `safeMathNarrow((X *int Y) /int (2^K / C))`
         *
         * where `C` is a power of 2 and `2^K` is divisible by `C`.
         * For example: C = 2^50, K = 64 → result = (X *int Y) /int 2^14.
         */
        PatternHandler(
            name = "fixed-point-multiply-2",
            pattern = {
                safeMathNarrow(lSym(A) intMul safeMathNarrow(c(C1) intMul lSym(B))) shr c(I1)
            },
            handle = {
                val shift = I1.n
                val twoPowK = twoToThe(shift)
                runIf(
                    shift in 1..64 &&
                        C1.n > BigInteger.ZERO &&
                        twoPowK % C1.n == BigInteger.ZERO
                ) {
                    val divisor = twoPowK / C1.n
                    safeMathNarrow(IntDiv(IntMul(sym(A), sym(B)), divisor.asTACExpr), Tag.Bit256)
                }
            },
            TACExpr.BinOp.ShiftRightLogical::class.java
        ),

    )
}.orEmpty()
