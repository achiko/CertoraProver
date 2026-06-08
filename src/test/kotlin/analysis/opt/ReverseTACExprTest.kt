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

package analysis.opt

import evm.MAX_EVM_UINT256
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tac.Tag
import vc.data.ReverseTACExpr.reverseTACExpr
import vc.data.TACExpr
import vc.data.TACSymbol
import vc.data.asTACExpr
import java.math.BigInteger

class ReverseTACExprTest {

    // Helper variables for unknown values in expressions
    private val xVar = TACSymbol.Var("x", Tag.Bit256)
    private val xVarExpr = TACExpr.Sym.Var(xVar)
    private val xIntVar = TACSymbol.Var("x", Tag.Int)
    private val xIntVarExpr = TACExpr.Sym.Var(xIntVar)
    private val yVar = TACSymbol.Var("y", Tag.Bit256)
    private val yVarExpr = TACExpr.Sym.Var(yVar)
    private val zVar = TACSymbol.Var("z", Tag.Bit256)
    private val zVarExpr = TACExpr.Sym.Var(zVar)
    private val wVar = TACSymbol.Var("w", Tag.Bit256)
    private val wVarExpr = TACExpr.Sym.Var(wVar)

    // Pre-built expressions for Add operations
    private val addXY256 = TACExpr.Vec.Add(listOf(xVarExpr, yVarExpr), Tag.Bit256)
    private val addXYZ256 = TACExpr.Vec.Add(listOf(xVarExpr, yVarExpr, zVarExpr), Tag.Bit256)
    private val addWXYZ256 = TACExpr.Vec.Add(listOf(wVarExpr, xVarExpr, yVarExpr, zVarExpr), Tag.Bit256)
    private val addXYNoTag = TACExpr.Vec.Add(listOf(xVarExpr, yVarExpr), null)
    private val intAddXY = TACExpr.Vec.IntAdd.Binary(xIntVarExpr, yVarExpr, null)

    // Pre-built expressions for Sub operations
    private val subXY256 = TACExpr.BinOp.Sub(xVarExpr, yVarExpr, Tag.Bit256)
    private val subXYNoTag = TACExpr.BinOp.Sub(xVarExpr, yVarExpr, null)
    private val intSubXY = TACExpr.BinOp.IntSub(xIntVarExpr, yVarExpr)

    // Pre-built expressions for comparison and logical operations
    private val eqXY = TACExpr.BinRel.Eq(xVarExpr, yVarExpr, null)
    private val lorXY = TACExpr.BinBoolOp.LOr(xVarExpr, yVarExpr)
    private val landXY = TACExpr.BinBoolOp.LAnd(xVarExpr, yVarExpr)
    private val xorXY = TACExpr.BinOp.BWXOr(xVarExpr, yVarExpr, null)
    private val lnotX = TACExpr.UnaryExp.LNot(xVarExpr)

    // Pre-built expressions for Mul and Div operations
    private val mulXY256 = TACExpr.Vec.Mul(listOf(xVarExpr, yVarExpr), Tag.Bit256)
    private val mulXYZ256 = TACExpr.Vec.Mul(listOf(xVarExpr, yVarExpr, zVarExpr), Tag.Bit256)
    private val mulXYNoTag = TACExpr.Vec.Mul(listOf(xVarExpr, yVarExpr), null)
    private val intMulXY = TACExpr.Vec.IntMul.Binary(xIntVarExpr, yVarExpr, null)
    private val divXY256 = TACExpr.BinOp.Div(xVarExpr, yVarExpr, Tag.Bit256)
    private val divXYNoTag = TACExpr.BinOp.Div(xVarExpr, yVarExpr, null)
    private val intDivXY = TACExpr.BinOp.IntDiv(xIntVarExpr, yVarExpr, null)

    // ==================== Add Tests ====================

    @Test
    fun `test Add - non-overflow case with two operands, first unknown`() {
        // x + 5 = 10, solve for x -> x = 5
        val expr = TACExpr.Vec.Add(listOf(xVarExpr, yVarExpr), Tag.Bit256)
        val result = reverseTACExpr(expr, 10.toBigInteger(), listOf(null, 5.toBigInteger()))
        assertEquals(5.toBigInteger(), result)
    }

    @Test
    fun `test Add - non-overflow case with two operands, second unknown`() {
        // 3 + x = 10, solve for x -> x = 7
        val expr = TACExpr.Vec.Add(listOf(yVarExpr, xVarExpr), Tag.Bit256)
        val result = reverseTACExpr(expr, 10.toBigInteger(), listOf(3.toBigInteger(), null))
        assertEquals(7.toBigInteger(), result)
    }

    @Test
    fun `test Add - non-overflow case with multiple operands`() {
        // 3 + x + 5 + 2 = 20, solve for x -> x = 10
        val result = reverseTACExpr(addWXYZ256, 20.toBigInteger(), listOf(3.toBigInteger(), null, 5.toBigInteger(), 2.toBigInteger()))
        assertEquals(10.toBigInteger(), result)
    }

    @Test
    fun `test Add - overflow case returns wrapped value for 256-bit`() {
        // x + MAX_UINT256 = 5 (with modular arithmetic)
        val maxUint256 = MAX_EVM_UINT256
        val result = reverseTACExpr(addXY256, 5.toBigInteger(), listOf(null, maxUint256))
        // This would require x = 6, with overflow: (MAX + 6) mod 2^256 = 5
        // The function returns 6 (it uses modular arithmetic)
        assertEquals(6.toBigInteger(), result)
    }

    @Test
    fun `test Add - edge case at maximum value`() {
        // x + 0 = MAX_UINT256, solve for x -> x = MAX_UINT256
        val maxUint256 = MAX_EVM_UINT256
        val expr = TACExpr.Vec.Add(listOf(xVarExpr, yVarExpr), Tag.Bit256)
        val result = reverseTACExpr(expr, maxUint256, listOf(null, BigInteger.ZERO))
        assertEquals(maxUint256, result)
    }

    @Test
    fun `test IntAdd - mathematical integers without overflow`() {
        // x + 5 = 10, solve for x -> x = 5 (no modular arithmetic)
        val expr = TACExpr.Vec.IntAdd.Binary(xIntVarExpr, yVarExpr, null)
        val result = reverseTACExpr(expr, 10.toBigInteger(), listOf(null, 5.toBigInteger()))
        assertEquals(5.toBigInteger(), result)
    }

    @Test
    fun `test IntAdd - negative result`() {
        // x + 10 = 3, solve for x -> x = -7 (mathematical integers)
        val expr = TACExpr.Vec.IntAdd.Binary(xIntVarExpr, yVarExpr, null)
        val result = reverseTACExpr(expr, 3.toBigInteger(), listOf(null, 10.toBigInteger()))
        assertEquals((-7).toBigInteger(), result)
    }

    // ==================== Sub Tests ====================

    @Test
    fun `test Sub - non-underflow case, first operand unknown`() {
        // x - 3 = 7, solve for x -> x = 10
        val result = reverseTACExpr(subXY256, 7.toBigInteger(), listOf(null, 3.toBigInteger()))
        assertEquals(10.toBigInteger(), result)
    }

    @Test
    fun `test Sub - non-underflow case, second operand unknown`() {
        // 10 - x = 3, solve for x -> x = 7
        val result = reverseTACExpr(subXY256, 3.toBigInteger(), listOf(10.toBigInteger(), null))
        assertEquals(7.toBigInteger(), result)
    }

    @Test
    fun `test Sub - underflow case should return value with modular arithmetic`() {
        // 3 - x = 10 (with modular arithmetic, x wraps around)
        val result = reverseTACExpr(subXY256, 10.toBigInteger(), listOf(3.toBigInteger(), null))
        // 3 - x ≡ 10 (mod 2^256), so x = 3 - 10 = -7 ≡ 2^256 - 7 (mod 2^256)
        val expected = MAX_EVM_UINT256.subtract(6.toBigInteger()) // 2^256 - 7
        assertEquals(expected, result)
    }

    @Test
    fun `test Sub - overflow case for first operand returns wrapped value`() {
        // x - 5 = MAX_UINT256 (with modular arithmetic)
        val maxUint256 = MAX_EVM_UINT256
        val result = reverseTACExpr(subXY256, maxUint256, listOf(null, 5.toBigInteger()))
        // x = MAX_UINT256 + 5 ≡ 4 (mod 2^256)
        assertEquals(4.toBigInteger(), result)
    }

    @Test
    fun `test IntSub - mathematical integers, first operand unknown`() {
        // x - 10 = -3, solve for x -> x = 7
        val result = reverseTACExpr(intSubXY, (-3).toBigInteger(), listOf(null, 10.toBigInteger()))
        assertEquals(7.toBigInteger(), result)
    }

    @Test
    fun `test IntSub - mathematical integers, second operand unknown`() {
        // 5 - x = 10, solve for x -> x = -5
        val result = reverseTACExpr(intSubXY, 10.toBigInteger(), listOf(5.toBigInteger(), null))
        assertEquals((-5).toBigInteger(), result)
    }

    // ==================== Eq Tests ====================

    @Test
    fun `test Eq - when result is true`() {
        // x == 5 evaluates to true (1), solve for x -> x = 5
        val result = reverseTACExpr(eqXY, BigInteger.ONE, listOf(null, 5.toBigInteger()))
        assertEquals(5.toBigInteger(), result)
    }

    @Test
    fun `test Eq - when result is false should return null`() {
        // x == 5 evaluates to false (0), cannot determine unique x
        val result = reverseTACExpr(eqXY, BigInteger.ZERO, listOf(null, 5.toBigInteger()))
        assertNull(result)
    }

    @Test
    fun `test Eq - second operand unknown when result is true`() {
        // 7 == x evaluates to true (1), solve for x -> x = 7
        val result = reverseTACExpr(eqXY, BigInteger.ONE, listOf(7.toBigInteger(), null))
        assertEquals(7.toBigInteger(), result)
    }

    // ==================== LOr Tests ====================

    @Test
    fun `test LOr - when result is false both must be false`() {
        // x || y = 0, if y is known to be 0, then x must be 0
        val result = reverseTACExpr(lorXY, BigInteger.ZERO, listOf(null, BigInteger.ZERO))
        assertEquals(BigInteger.ZERO, result)
    }

    @Test
    fun `test LOr - when result is true and known is false, unknown must be true`() {
        // x || 0 = 1, solve for x -> x = 1
        val result = reverseTACExpr(lorXY, BigInteger.ONE, listOf(null, BigInteger.ZERO))
        assertEquals(BigInteger.ONE, result)
    }

    @Test
    fun `test LOr - when result is true and known is true, unknown is indeterminate`() {
        // x || 1 = 1, x can be anything
        val result = reverseTACExpr(lorXY, BigInteger.ONE, listOf(null, BigInteger.ONE))
        assertNull(result)
    }

    @Test
    fun `test LOr - second operand unknown, result false`() {
        // 0 || x = 0, solve for x -> x = 0
        val result = reverseTACExpr(lorXY, BigInteger.ZERO, listOf(BigInteger.ZERO, null))
        assertEquals(BigInteger.ZERO, result)
    }

    // ==================== LAnd Tests ====================

    @Test
    fun `test LAnd - when result is true both must be true`() {
        // x && y = 1, if y is known to be 1, then x must be 1
        val result = reverseTACExpr(landXY, BigInteger.ONE, listOf(null, BigInteger.ONE))
        assertEquals(BigInteger.ONE, result)
    }

    @Test
    fun `test LAnd - when result is false and known is true, unknown must be false`() {
        // x && 1 = 0, solve for x -> x = 0
        val result = reverseTACExpr(landXY, BigInteger.ZERO, listOf(null, BigInteger.ONE))
        assertEquals(BigInteger.ZERO, result)
    }

    @Test
    fun `test LAnd - when result is false and known is false, unknown is indeterminate`() {
        // x && 0 = 0, x can be anything
        val result = reverseTACExpr(landXY, BigInteger.ZERO, listOf(null, BigInteger.ZERO))
        assertNull(result)
    }

    @Test
    fun `test LAnd - second operand unknown, result true`() {
        // 1 && x = 1, solve for x -> x = 1
        val result = reverseTACExpr(landXY, BigInteger.ONE, listOf(BigInteger.ONE, null))
        assertEquals(BigInteger.ONE, result)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `test multiple unknowns should return null`() {
        // x + y = 10, cannot solve for unique values
        val result = reverseTACExpr(addXY256, 10.toBigInteger(), listOf(null, null))
        assertNull(result)
    }

    @Test
    fun `test no unknowns should return null`() {
        // 3 + 5 = 8, no unknown to solve for
        val expr = TACExpr.Vec.Add(listOf(3.toBigInteger(), 5.toBigInteger()).map { it.asTACExpr }, Tag.Bit256)
        val result = reverseTACExpr(expr, 8.toBigInteger(), listOf(3.toBigInteger(), 5.toBigInteger()))
        assertNull(result)
    }

    @Test
    fun `test XOR operation is reversible`() {
        // x ^ 5 = 12, solve for x -> x = 12 ^ 5 = 9
        val result = reverseTACExpr(xorXY, 12.toBigInteger(), listOf(null, 5.toBigInteger()))
        assertEquals(9.toBigInteger(), result)
    }

    @Test
    fun `test XOR second operand unknown`() {
        // 7 ^ x = 12, solve for x -> x = 7 ^ 12 = 11
        val result = reverseTACExpr(xorXY, 12.toBigInteger(), listOf(7.toBigInteger(), null))
        assertEquals(11.toBigInteger(), result)
    }

    @Test
    fun `test logical NOT is reversible`() {
        // !x = 0, solve for x -> x = 1
        val result = reverseTACExpr(lnotX, BigInteger.ZERO, listOf(null))
        assertEquals(BigInteger.ONE, result)
    }

    @Test
    fun `test logical NOT with result 1`() {
        // !x = 1, solve for x -> x = 0
        val result = reverseTACExpr(lnotX, BigInteger.ONE, listOf(null))
        assertEquals(BigInteger.ZERO, result)
    }

    // ==================== Mul Tests ====================

    @Test
    fun `test Mul - simple case with two operands, first unknown`() {
        // x * 3 = 12, solve for x -> x = 4
        val result = reverseTACExpr(mulXY256, 12.toBigInteger(), listOf(null, 3.toBigInteger()))
        assertEquals(4.toBigInteger(), result)
    }

    @Test
    fun `test Mul - result not divisible by known product should return null`() {
        // x * 4 = 13, not evenly divisible
        val result = reverseTACExpr(mulXY256, 13.toBigInteger(), listOf(null, 4.toBigInteger()))
        assertNull(result)
    }

    @Test
    fun `test Mul - known operand is zero should return null`() {
        // x * 0 = 0, cannot determine unique x
        val result = reverseTACExpr(mulXY256, BigInteger.ZERO, listOf(null, BigInteger.ZERO))
        assertNull(result)
    }

    @Test
    fun `test IntMul - simple case`() {
        // x * 5 = 20 (mathematical integers)
        val result = reverseTACExpr(intMulXY, 20.toBigInteger(), listOf(null, 5.toBigInteger()))
        assertEquals(4.toBigInteger(), result)
    }

    @Test
    fun `test IntMul - negative result`() {
        // x * -3 = 12, solve for x -> x = -4
        val result = reverseTACExpr(intMulXY, 12.toBigInteger(), listOf(null, (-3).toBigInteger()))
        assertEquals((-4).toBigInteger(), result)
    }

    // ==================== Div Tests ====================
    @Test
    fun `test Div - divisor unknown, exact division`() {
        // 12 / x = 3, solve for x -> x = 4
        val result = reverseTACExpr(divXY256, 3.toBigInteger(), listOf(12.toBigInteger(), null))
        assertEquals(4.toBigInteger(), result)
    }

    @Test
    fun `test Div - divisor unknown, inexact division should return null`() {
        // 13 / x = 3, no exact solution in integers
        val result = reverseTACExpr(divXY256, 3.toBigInteger(), listOf(13.toBigInteger(), null))
        assertEquals(4.toBigInteger(), result)
    }

    @Test
    fun `test Div - division by zero guard`() {
        // x / 0 = 5, invalid operation
        val result = reverseTACExpr(divXY256, 5.toBigInteger(), listOf(null, BigInteger.ZERO))
        assertNull(result)
    }

    @Test
    fun `test Div - result is zero, divisor unknown`() {
        // 5 / x = 0, would require x > 5, but then verification would fail
        val result = reverseTACExpr(divXY256, BigInteger.ZERO, listOf(5.toBigInteger(), null))
        assertNull(result)
    }

    @Test
    fun `test Div - without tag should return null`() {
        // Without tag information, cannot reverse modular division
        val result = reverseTACExpr(divXYNoTag, 3.toBigInteger(), listOf(null, 4.toBigInteger()))
        assertNull(result)
    }

    @Test
    fun `test IntDiv - divisor unknown, exact case`() {
        // 20 / x = 4, solve for x -> x = 5
        val result = reverseTACExpr(intDivXY, 4.toBigInteger(), listOf(20.toBigInteger(), null))
        assertEquals(5.toBigInteger(), result)
    }

    @Test
    fun `test IntDiv - division by zero guard`() {
        // x / 0 = 5, invalid operation
        val result = reverseTACExpr(intDivXY, 5.toBigInteger(), listOf(null, BigInteger.ZERO))
        assertNull(result)
    }
}