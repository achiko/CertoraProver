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

import datastructures.stdcollections.forEachEntry
import datastructures.stdcollections.mapOf
import datastructures.stdcollections.setOf
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import report.calltrace.interpreter.InterpreterResult
import report.calltrace.interpreter.TACProgramInterpreter
import report.calltrace.interpreter.TACProgramInterpreter.Companion.SMT_MODEL_VALUE_OPTIMIZED
import utils.*
import vc.data.*
import java.math.BigInteger

class TacProgramInterpreterTest : TACBuilderAuxiliaries() {

    private fun TACProgramBuilder.BuiltTACProgram.runInterpreter(
        visitedBlocks: Set<Int> = this.code.blockgraph.keys.mapToSet { it.origStartPc },
        optimizedEval: Map<String, Int> = mapOf()
    ): InterpreterResult? {
        val p = code.toPatchingProgram()
        optimizedEval.forEachEntry { (k, v) ->
            val cmdPtr = this.ptr(k)
            val existingCmd = code.analysisCache.graph.elab(cmdPtr)
            val new = existingCmd.cmd.plusMeta(SMT_MODEL_VALUE_OPTIMIZED, v.toBigInteger())
            p.update(cmdPtr, new)
        }
        val withMeta = p.toCode(code)
        val interpreter = TACProgramInterpreter(
            withMeta,
            visitedBlocks.mapToSet { this.block(it) },
            withMeta.entryBlockId,

        )
        return interpreter.interpretProgram()
    }

    private fun TACProgramBuilder.BuiltTACProgram.checkQuery(
        visitableBlocks: Set<Int> = this.code.blockgraph.keys.mapToSet { it.origStartPc },
        optimizedEval: Map<String, Int> = mapOf(),
        vararg pairs: Pair<TACSymbol.Var, Int?>
    ) {
        val model = this.runInterpreter(visitableBlocks, optimizedEval)!!.cex

        for ((variable, value) in pairs) {
            assertEquals(
                value?.let { BigInteger.valueOf(it.toLong()) },
                model.tacAssignments[variable]?.asBigIntOrNull(),
                "Expected $variable to equal $value"
            )
        }
    }

    private fun TACProgramBuilder.BuiltTACProgram.checkNoModelComputable(optimizedEval: Map<String, Int> = mapOf()) {
        assertNull(
            this.runInterpreter(
                visitedBlocks = this.code.blockgraph.keys.mapToSet { it.origStartPc },
                optimizedEval = optimizedEval
            )
        )
    }

    @Test
    fun `havoc values are correctly backtracked when assertion fails`() {
        val prog = TACProgramBuilder {
            a assign 10
            havoc(b)
            a assign b
            x assign Eq(bS, 1.asTACExpr)
            assumeExp(xS)
            assert(False)
        }
        prog.checkQuery(pairs = arrayOf(x to 1, b to 1, a to 1))
    }

    @Test
    fun `no conflict occurs when assumption matches assignment`() {
        val prog = TACProgramBuilder {
            a assign 1
            x assign Eq(aS, 1.asTACExpr)
            assumeExp(xS)
            assert(False)
        }
        prog.checkQuery(pairs = arrayOf(a to 1))
    }

    /**
     * Tests for re-assignment of variables.
     *
     * In this example, the variable b is re-assigned. The first assignment b_0 is 0
     * and the second assignment is b_1 is a havoc that will then be assumed to be 1 later.
     *
     * c is derived from b_0 + 1. Assuming b to be 1 should not make c to be computed to 2.
     */
    @Disabled("We assume that the TAC is in DSA form")
    @Test
    fun `overwriting values in DSA form preserves original variable versions`() {
        val prog = TACProgramBuilder {
            havoc(a)
            b assign 0 //b_0
            c assign Add(aS, bS) // c should be 1 as we load b_0 and not b_1
            havoc(b) //b_1
            assumeExp(Eq(bS, 1.asTACExpr)) // Re-assigning b = 1.
            assumeExp(Eq(aS, 1.asTACExpr)) // Re-assigning a = 1.
            assert(False)
        }
        prog.checkQuery(
            pairs = arrayOf(a to 1, b to 1, c to null /*In DSA form we should evaluate c to 1 here */)
        )
    }

    @Test
    fun `vacuous assumptions cause interpreter exception`() {
        val prog = TACProgramBuilder {
            a assign 2
            x assign Eq(aS, 1.asTACExpr)
            assumeExp(xS)
            assert(False)
        }
        prog.checkNoModelComputable()
    }

    @Test
    fun `interpreter fails when assumption contradicts assignment`() {
        val prog = TACProgramBuilder {
            a assign 10
            b assign a
            y assign Eq(bS, 1.asTACExpr)
            assumeExp(yS)
            assert(False)
        }
        prog.checkNoModelComputable()
    }

    @Test
    fun `assume statements do not force values when block is not on path`() {
        val prog = TACProgramBuilder {
            havoc(x)
            havoc(y)
            jumpCond(x)
            jump(1) {
                assume(y)
                jump(3) {
                    assert(False)
                }
            }
            jump(2) {
                jump(3)
            }
        }
        prog.checkQuery(pairs = arrayOf(y to null))
    }

    @Test
    fun `assume statements force values when block is definitely on path`() {
        val prog = TACProgramBuilder {
            havoc(x)
            havoc(y)
            jumpCond(x)
            jump(1) {
                assume(y)
                jump(3) {
                    assert(False)
                }
            }
            jump(2) {
                jump(1)
            }
        }
        // The block 1 is known to be on the path to the assert, thus the assume MUST hold and the y must be 1.
        prog.checkQuery(pairs = arrayOf(y to 1))
    }

    @Test
    fun `assume statements do not force values when block may not be on path`() {
        val prog = TACProgramBuilder {
            havoc(x)
            havoc(y)
            jumpCond(x)
            jump(1) {
                assume(y)
                jump(3) {
                    assert(False)
                }
            }
            jump(2) {
                jump(3)
            }
        }
        // The block 1 may only be on the path to the assert, thus the assume MUST not hold and y is null here.
        prog.checkQuery(pairs = arrayOf(y to null))
    }

    @Test
    fun `assume statements force values when directly on failing path`() {
        val prog = TACProgramBuilder {
            havoc(x)
            havoc(y)
            assume(y)
            assert(False)
        }
        prog.checkQuery(pairs = arrayOf(y to 1, x to null))
    }

    @Test
    fun `interpreter fails on false assumption before assertion`() {
        val prog = TACProgramBuilder {
            havoc(a)
            assumeExp(False)
            assert(False)
        }
        prog.checkNoModelComputable()
    }

    @Test
    fun `optimized evaluation correctly handles assert false via havoc`() {
        val prog = TACProgramBuilder {
            label("l1")
            havoc(x)
            assert(x)
        }
        prog.checkQuery(optimizedEval = mapOf("l1" to 0), pairs = arrayOf(x to 0))
    }

    @Test
    fun `optimized evaluation correctly handles direct assert false`() {
        val prog = TACProgramBuilder {
            havoc(x)
            label("l1")
            assert(x)
        }
        prog.checkQuery(optimizedEval = mapOf("l1" to 0), pairs = arrayOf(x to 0))
    }

    @Test
    fun `optimized evaluation backtracks through arithmetic when assert fails`() {
        val prog = TACProgramBuilder {
            havoc(a)
            b assign Add(aS, 2.asTACExpr)
            y assign Eq(bS, 5.asTACExpr)
            x assign LNot(yS)
            label("l1")
            assert(x)
        }
        prog.checkQuery(
            optimizedEval = mapOf("l1" to 0), pairs = arrayOf(x to 0, y to 1, b to 5, a to 3),
        )
    }

    @Test
    fun `optimized evaluation backtracks through expressions when assert fails`() {
        val prog = TACProgramBuilder {
            havoc(a)
            b assign Add(aS, 2.asTACExpr)
            c assign Add(1.asTACExpr, 4.asTACExpr)
            y assign Eq(bS, cS)
            x assign LNot(yS)
            label("l1")
            assert(x)
        }
        prog.checkQuery(
            optimizedEval = mapOf("l1" to 0), pairs = arrayOf(c to 5, b to 5, a to 3),
        )
    }

    @Test
    fun `optimized evaluation correctly propagates values for false assertion`() {
        val prog = TACProgramBuilder {
            label("l1")
            havoc(a)
            b assign Add(aS, 2.asTACExpr)
            x assign Eq(bS, 5.asTACExpr)
            assert(x)
        }
        prog.checkQuery(optimizedEval = mapOf("l1" to 2), pairs = arrayOf(x to 0, a to 2))
    }

    @Test
    fun `simple constant expressions are folded during interpretation`() {
        val prog = TACProgramBuilder {
            havoc(a)
            b assign Add(7.asTACExpr, 2.asTACExpr)
            y assign Eq(bS, aS)
            x assign LNot(yS)
            label("l1")
            assert(x)
        }
        prog.checkQuery(optimizedEval = mapOf("l1" to 0), pairs = arrayOf(b to 9, a to 9))
    }


    @Test
    fun `optimized evaluation throws exception when assertion would be true`() {
        val prog = TACProgramBuilder {
            label("l1")
            havoc(a)
            b assign Add(aS, 2.asTACExpr)
            x assign Eq(bS, 5.asTACExpr)
            assert(x)
        }
        prog.checkNoModelComputable(mapOf("l1" to 3))
    }

    @Test
    fun `optimized evaluation handles direct false assertion without backtracking`() {
        val prog = TACProgramBuilder {
            havoc(a)
            b assign Add(aS, 2.asTACExpr)
            x assign Eq(bS, 5.asTACExpr)
            label("l1")
            assert(x)
        }
        prog.checkQuery(optimizedEval = mapOf("l1" to 0), pairs = arrayOf(b to null))
    }

    @Test
    fun `conflict between interpreted and optimized evaluation`() {
        val prog = TACProgramBuilder {
            havoc(a)
            b assign Add(aS, 2.asTACExpr)
            x assign Eq(bS, 5.asTACExpr)
            jumpCond(x)
            jump(1) {
                // Here we know that x == true as we jumped into this branch,
                // but then the optimized/SMT model tells us x == false
                // As SMT takes precedence, we have a conflict here.
                label("l1")
                assert(x)
            }
            jump(2) {
                b assign 2
            }
        }
        prog.checkQuery(optimizedEval = mapOf("l1" to 0), pairs = arrayOf(x to 0, b to 5, a to 3))
    }

    @Test
    fun `optimized evaluation finds feasible path through conditional branches`() {
        val prog = TACProgramBuilder {
            havoc(a)
            b assign Add(aS, 2.asTACExpr)
            x assign Eq(bS, 5.asTACExpr)
            jumpCond(x)
            jump(1) {
                label("l1")
                y assign LNot(xS)
                assert(y)
            }
            jump(2) {
                b assign 2
            }
        }
        prog.checkQuery(
            optimizedEval = mapOf("l1" to 0), pairs = arrayOf(b to 5, a to 3)
            /** Fails: a to 3, We currently don't trace back through Add */
        )
    }

    @Test
    fun `bytemap write and read operations preserve values`() {
        val prog = TACProgramBuilder {
            a assign 10
            bMap1[32] assign a
            b assign bMap1[32]
            assert(False)
        }
        prog.checkQuery(pairs = arrayOf(a to 10, b to 10))
    }


    @Test
    fun `bytemap long store operation correctly copies data between maps`() {
        val prog = TACProgramBuilder {
            a assign 10
            b assign 15
            c assign 42
            bMap1[0] assign a
            bMap2[32] assign b
            bMap3[64] assign c //Will be overwritten
            addCmd(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    bMap3,
                    TACExpr.LongStore(
                        dstMap = bMap2.toTACExpr(),
                        dstOffset = 64.asTACExpr,
                        srcMap = bMap1.toTACExpr(),
                        srcOffset = 0.asTACExpr,
                        length = 32.asTACExpr
                    )
                )
            )
            d assign bMap3[32]
            e assign bMap3[64]
            assert(False)
        }
        prog.checkQuery(pairs = arrayOf(d to 15, e to 10))
    }

    @Disabled("We current do not branch state at ite expressions")
    @Test
    fun `ITE expression with unknown condition produces non-deterministic result`() {
        val prog = TACProgramBuilder {
            a assign 10
            havoc(x)
            addCmd(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    b,
                    Ite(
                        xS,
                        4.asTACExpr, 3.asTACExpr
                    ),
                )
            )
            assert(False)
        }
        val assignments = prog.runInterpreter()!!.cex.tacAssignments
        assert(assignments[b]?.asBigIntOrNull()?.toInt() in listOf(4, 3))
    }

    @Disabled("We current do not branch state at ite expressions")
    @Test
    fun `ITE expression result is imprecise when condition is later determined`() {
        val prog = TACProgramBuilder {
            a assign 10
            havoc(x)
            addCmd(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    b,
                    Ite(
                        xS,
                        4.asTACExpr, 3.asTACExpr
                    ),
                )
            )
            jumpCond(x)
            jump(1) {
                assert(False)
            }
            jump(2) {
                c assign 5
            }
        }
        prog.checkQuery(pairs = arrayOf(b to 4))
    }

    @Disabled("We current do not branch state at ite expressions")
    @Test
    fun `ITE expression with known condition evaluates to correct branch`() {
        val prog = TACProgramBuilder {
            a assign 10
            x assign Eq(aS, 10.asTACExpr)
            addCmd(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    b,
                    Ite(
                        xS,
                        4.asTACExpr, 3.asTACExpr
                    ),
                )
            )
            assert(False)
        }
        prog.checkQuery(pairs = arrayOf(b to 4))
    }

    @Disabled("Hashing is not modeled in the interpreter.")
    @Test
    fun `SHA3 hashing produces same value for identical inputs`() {
        val prog = TACProgramBuilder {
            a assign 10
            b assign 20
            c assign 30
            addCmd(
                TACCmd.Simple.AssigningCmd.AssignSimpleSha3Cmd(
                    d,
                    10.asTACSymbol(),
                    listOf(a, b, c)
                )
            )
            addCmd(
                TACCmd.Simple.AssigningCmd.AssignSimpleSha3Cmd(
                    e,
                    10.asTACSymbol(),
                    listOf(a, b, c)
                )
            )
            x assign LNot(Eq(dS, eS))
            assert(x)
        }

        prog.checkQuery(pairs = arrayOf(x to 0))
    }

    @Test
    fun `bytemap byte load at specific offsets returns unknown values`() {
        val prog = TACProgramBuilder {
            a assign 1
            bMap1[0] assign a
            bMap1[32] assign 0
            addCmd(
                TACCmd.Simple.AssigningCmd.ByteLoad(
                    b,
                    1.asTACSymbol(),
                    bMap1
                )
            )
            addCmd(
                TACCmd.Simple.AssigningCmd.ByteLoad(
                    c,
                    2.asTACSymbol(),
                    bMap1
                )
            )
            assert(False)
        }
        prog.checkQuery(pairs = arrayOf(a to 1, b to null, c to null))
    }

    @Test
    fun `bytemap byte load returns unknown when offset content is uncertain`() {
        val prog = TACProgramBuilder {
            a assign 1
            bMap1[0] assign a
            addCmd(
                TACCmd.Simple.AssigningCmd.ByteLoad(
                    b,
                    1.asTACSymbol(),
                    bMap1
                )
            )
            assert(False)
        }
        prog.checkQuery(pairs = arrayOf(a to 1, b to null))
    }

    @Test
    fun `bytemap aliasing preserves values through assignment`() {
        val prog = TACProgramBuilder {
            a assign 10
            bMap1[32] assign a
            bMap2 assign bMap1
            b assign bMap2[32]
            assert(False)
        }
        prog.checkQuery(pairs = arrayOf(a to 10, b to 10))
    }

    @Test
    fun `bytemap aliasing before write does not propagate future changes`() {
        val prog = TACProgramBuilder {
            a assign 10
            bMap2 assign bMap1
            bMap1[32] assign a
            b assign bMap2[32]
            assert(False)
        }
        prog.checkQuery(pairs = arrayOf(a to 10, b to null))
    }

    @Test
    fun `bytemap overlapping writes preserve last written values`() {
        val prog = TACProgramBuilder {
            a assign 10
            bMap1[32] assign a
            bMap1[33] assign a
            b assign bMap1[33]
            c assign bMap1[32]
            assert(False)
        }
        prog.checkQuery(pairs = arrayOf(a to 10, b to 10, c to 10))
    }

    @Test
    fun `bytemap long copy correctly handles partial overlapping ranges`() {
        val prog = TACProgramBuilder {
            // dst: will be overwritten in [80-180]
            bMap1[0] assign 11 // kept
            bMap1[70] assign 22 // cut
            bMap1[110] assign 33 // removed
            bMap1[200] assign 44 // kept

            // src: will be copied from 50-150
            bMap2[0] assign 111 // not taken
            bMap2[32] assign 222 // cut
            bMap2[100] assign 333 // taken
            bMap2[140] assign 444 // cut
            bMap2[200] assign 555 // not taken

            label("query")
            addCmd(
                TACCmd.Simple.ByteLongCopy(
                    srcBase = bMap2,
                    srcOffset = 50.asTACSymbol(),
                    length = 100.asTACSymbol(),
                    dstBase = bMap1,
                    dstOffset = 80.asTACSymbol(),
                )
            )
            a assign bMap2[0]
            b assign bMap2[100]
            c assign bMap2[200]

            d assign bMap1[0]
            e assign bMap1[200]
            f assign bMap1[130]
            assert(False)
        }
        prog.checkQuery(pairs = arrayOf(a to 111, b to 333, c to 555, d to 11, e to 44, f to 333))
    }

    @Test
    fun `bytemap non-overlapping writes preserve independent values`() {
        val prog = TACProgramBuilder {
            a assign 10
            bMap1[32] assign a
            bMap1[64] assign a
            b assign bMap1[32]
            assert(False)
        }
        prog.checkQuery(pairs = arrayOf(b to 10, a to 10))
    }

    @Test
    fun `conditional jumps with havoc correctly track values per path`() {
        val prog = TACProgramBuilder {
            havoc(x)
            jumpCond(x)
            jump(1) {
                a assign 10
                jump(3) {
                    b assign a
                    assert(False)
                }
            }
            jump(2) {
                a assign 20
                jump(3)
            }
        }
        prog.checkQuery(setOf(0, 1, 3), pairs = arrayOf(a to 10, b to 10))
        prog.checkQuery(setOf(0, 2, 3), pairs = arrayOf(a to 20, b to 20))
    }

    @Test
    fun `conditional jump tracks correct branch when other branch fails`() {
        val prog = TACProgramBuilder {
            havoc(x)
            jumpCond(x)
            jump(1) {
                a assign 10
                jump(3) {
                    b assign a
                }
            }
            jump(2) {
                a assign 20
                assert(False)
            }
        }
        prog.checkQuery(pairs = arrayOf(a to 20, b to null))
    }

    @Test
    fun `diamond pattern with assumption forces specific execution path`() {
        val prog = TACProgramBuilder {
            havoc(x)
            jumpCond(x)
            jump(1) {
                a assign 42
                b assign Add(aS, 13.asTACExpr)
                jump(3) {
                    // Force a specific path (1,3) and not (2,3) by assuming x=1
                    y assign Eq(xS, True)
                    assumeExp(yS)
                    assert(False)
                }
            }
            jump(2) {
                a assign 41
                b assign Sub(aS, 13.asTACExpr)
                jump(3)
            }
        }
        prog.checkQuery(pairs = arrayOf(a to 42, b to 55, x to 1))
    }

    @Test
    fun `backtracking through branches determines condition from taken path`() {
        val prog = TACProgramBuilder {
            havoc(x)
            havoc(a)
            x assign Eq(aS, 10.asTACExpr)
            jumpCond(x)
            jump(1) {
                assert(False)
            }
            jump(2) {
                a assign 42
            }
        }
        prog.checkQuery(pairs = arrayOf(x to 1, a to 10))
    }

    @Test
    fun `optimized evaluation takes precedence in conflict resolution`() {
        val prog = TACProgramBuilder {
            a assign 2
            label("l1")
            b assign BWAnd(aS, 2.asTACExpr)
            y assign Eq(bS, 1.asTACExpr)
            x assign LNot(yS)
            assert(x)
        }
        prog.checkQuery(
            optimizedEval = mapOf("l1" to 1), pairs = arrayOf(b to 1, y to 1, x to 0),
        )
    }

    @Test
    fun `simple back tracking`() {
        val prog = TACProgramBuilder {
            havoc(a)
            y assign Eq(aS, 2.asTACExpr)
            x assign LNot(yS)
            label("l1")
            assert(x)
        }
        prog.checkQuery(
            optimizedEval = mapOf("l1" to 0), pairs = arrayOf(x to 0, a to 2),
        )
    }

    @Test
    fun `map backpropagation`() {
        val prog = TACProgramBuilder {
            havoc(a)
            bMap1[1] assign a
            b assign bMap1[1]
            y assign Eq(aS, 2.asTACExpr)
            x assign LNot(yS)
            label("l1")
            assert(x)
        }
        prog.checkQuery(
            optimizedEval = mapOf("l1" to 0), pairs = arrayOf(x to 0, y to 1, a to 2, b to 2),
        )
    }


    @Test
    fun `map overwrite at havoced location should kill previous assignment`() {
        val prog = TACProgramBuilder {
            havoc(a)
            bMap1[1] assign 2
            bMap1[a] assign 3
            c assign bMap1[1]
            assert(False)
        }
        prog.checkQuery(
            pairs = arrayOf(c to null),
        )
    }

    @Test
    fun `map overwrite at havoced location should not kill previous assignment (imprecise)`() {
        val prog = TACProgramBuilder {
            havoc(a)
            bMap1[1] assign 2
            bMap1[a] assign 3
            c assign bMap1[1]
            x assign Eq(aS, 2.asTACExpr) // a is fixed to 2 here, but the analysis already processed the kill
            assume(x)
            assert(False)
        }
        prog.checkQuery(
            pairs = arrayOf(c to null), //A more precise analysis can infer c == 2
        )
    }

    @Test
    fun `simple map assignment backward propagation`() {
        val prog = TACProgramBuilder {
            havoc(a)
            bMap1[1] assign a
            c assign bMap1[1]
            x assign Eq(cS, 5.asTACExpr)
            assume(x)
            assert(False)
        }
        prog.checkQuery(
            pairs = arrayOf(x to 1, c to 5, a to 5),
        )
    }

    @Test
    fun `simple map assignment with a kill`() {
        val prog = TACProgramBuilder {
            havoc(a)
            bMap1[1] assign a
            bMap1[1] assign 2
            c assign bMap1[1]
            x assign Eq(cS, 5.asTACExpr)
            assume(x)
            assert(False)
        }
        prog.checkNoModelComputable()
    }

    @Test
    fun `single byte store correctly stores value`() {
        val prog = TACProgramBuilder {
            bMap1[0] assign 0
            // Store single bytes at different offsets
            addCmd(
                TACCmd.Simple.AssigningCmd.ByteStoreSingle(
                    loc = 31.asTACSymbol(),
                    value = 1.asTACSymbol(),
                    base = bMap1
                )
            )
            c assign bMap1[0]

            assert(False)
        }
        prog.checkQuery(pairs = arrayOf(c to 1))
    }


    @Test
    fun `single byte store with different offsets stores correct value`() {
        val prog = TACProgramBuilder {
            bMap1[0] assign 0
            // Store single bytes at different offsets
            addCmd(
                TACCmd.Simple.AssigningCmd.ByteStoreSingle(
                    loc = 31.asTACSymbol(),
                    value = 1.asTACSymbol(),
                    base = bMap1
                )
            )
            addCmd(
                TACCmd.Simple.AssigningCmd.ByteStoreSingle(
                    loc = 30.asTACSymbol(),
                    value = 1.asTACSymbol(),
                    base = bMap1
                )
            )
            c assign bMap1[0]

            assert(False)
        }
        prog.checkQuery(pairs = arrayOf(c to 257))
    }

    @Test
    fun `single byte store with initially havoc'ed value`() {
        val prog = TACProgramBuilder {
            havoc(a)
            bMap1[0] assign a
            addCmd(
                TACCmd.Simple.AssigningCmd.ByteStoreSingle(
                    loc = 31.asTACSymbol(),
                    value = 1.asTACSymbol(),
                    base = bMap1
                )
            )
            c assign bMap1[0]
            x assign Eq(aS, 0.asTACExpr)
            assume(x)
            assert(False)
        }
        prog.checkQuery(pairs = arrayOf(a to 0, c to 1))
    }

    @Test
    fun `single byte store with havoc'ed stored value backtracks`() {
        val prog = TACProgramBuilder {
            havoc(a)
            bMap1[0] assign 0
            addCmd(
                TACCmd.Simple.AssigningCmd.ByteStoreSingle(
                    loc = 31.asTACSymbol(),
                    value = a,
                    base = bMap1
                )
            )
            c assign bMap1[0]
            x assign Eq(aS, 1.asTACExpr) //The store value a can only be known here. But c can still be known.
            assume(x)
            assert(False)
        }
        prog.checkQuery(pairs = arrayOf(a to 1, c to 1))
    }

    @Test
    fun `single byte store with unknown location kills (imprecise)`() {
        val prog = TACProgramBuilder {
            havoc(a)
            bMap1[0] assign 0
            addCmd(
                TACCmd.Simple.AssigningCmd.ByteStoreSingle(
                    loc = a,
                    value = 1.asTACSymbol(),
                    base = bMap1
                )
            )
            c assign bMap1[0] // Due to the interleaved single byte store for which the location isn't known, c is unknown
            x assign Eq(aS, 1.asTACExpr)
            assume(x)
            assert(False)
        }
        prog.checkQuery(pairs = arrayOf(a to 1, c to null))
    }
}
