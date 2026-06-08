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

package sbf.domains

import sbf.disassembler.SbfGlobalVariable
import datastructures.stdcollections.*

/** Create SbfType instances where TNum=[Constant] and TOffset=[Constant] **/
class ConstantSbfTypeFactory: ISbfTypeFactory<Constant, Constant> {
    override fun mkTop(): SbfType<Constant, Constant> = SbfType.top()
    override fun mkBottom(): SbfType<Constant, Constant> = SbfType.bottom()

    override fun numToOffset(num: Constant) = num
    override fun offsetToNum(offset: Constant) = offset

    override fun toNum(value: ULong): SbfType.NumType<Constant, Constant> {
        // REVISIT: immediate values are represented as ULong
        // The analysis uses signed integer semantics, so we need to convert the value to Long.
        // Therefore, overflow will happen if value represents a negative number.
        return SbfType.NumType(Constant(value.toLong()))
    }
    override fun toNum(value: Long): SbfType.NumType<Constant, Constant> = SbfType.NumType(Constant(value))
    override fun toStackPtr(offset: Long): SbfType.PointerType.Stack<Constant, Constant> = SbfType.PointerType.Stack(
        Constant(offset)
    )
    override fun toHeapPtr(offset: Long): SbfType.PointerType.Heap<Constant, Constant> = SbfType.PointerType.Heap(
        Constant(offset)
    )
    override fun toInputPtr(offset: Long): SbfType.PointerType.Input<Constant, Constant> = SbfType.PointerType.Input(
        Constant(offset)
    )
    override fun toGlobalPtr(offset: Long, gv: SbfGlobalVariable?): SbfType.PointerType.Global<Constant, Constant> = SbfType.PointerType.Global(
        Constant(offset), gv)

    override fun toGlobalPtr(offsets: List<Long>, gv: SbfGlobalVariable?): SbfType.PointerType.Global<Constant, Constant> {
        check(offsets.isNotEmpty())
        val firstOffset = offsets.first()
        return if (offsets.all { it == firstOffset }) {
            SbfType.PointerType.Global(Constant(firstOffset), gv)
        } else {
            anyGlobalPtr(gv)
        }
    }

    override fun anyNum(): SbfType.NumType<Constant, Constant> = SbfType.NumType(Constant.makeTop())
    override fun anyStackPtr(): SbfType.PointerType.Stack<Constant, Constant> = SbfType.PointerType.Stack(Constant.makeTop())
    override fun anyHeapPtr(): SbfType.PointerType.Heap<Constant, Constant> = SbfType.PointerType.Heap(Constant.makeTop())
    override fun anyInputPtr(): SbfType.PointerType.Input<Constant, Constant> = SbfType.PointerType.Input(Constant.makeTop())
    override fun anyGlobalPtr(gv: SbfGlobalVariable?): SbfType.PointerType.Global<Constant, Constant> = SbfType.PointerType.Global(
        Constant.makeTop(), gv)
}


/** Create SbfType instances where TNum=[ConstantSet] and TOffset=[ConstantSet] **/
class ConstantSetSbfTypeFactory(private val maxNumDisjuncts: ULong): ISbfTypeFactory<ConstantSet, ConstantSet> {
    override fun mkTop(): SbfType<ConstantSet, ConstantSet> = SbfType.top()
    override fun mkBottom(): SbfType<ConstantSet, ConstantSet> = SbfType.bottom()

    override fun numToOffset(num: ConstantSet) = num
    override fun offsetToNum(offset: ConstantSet) = offset

    override fun toNum(value: ULong) = SbfType.NumType<ConstantSet, ConstantSet>(ConstantSet(value.toLong(), maxNumDisjuncts))
    override fun toNum(value: Long) = SbfType.NumType<ConstantSet, ConstantSet>(ConstantSet(value, maxNumDisjuncts))
    override fun toStackPtr(offset: Long) = SbfType.PointerType.Stack<ConstantSet, ConstantSet>(ConstantSet(offset, maxNumDisjuncts))
    override fun toHeapPtr(offset: Long) = SbfType.PointerType.Heap<ConstantSet, ConstantSet>(ConstantSet(offset, maxNumDisjuncts))
    override fun toInputPtr(offset: Long) = SbfType.PointerType.Input<ConstantSet, ConstantSet>(ConstantSet(offset, maxNumDisjuncts))
    override fun toGlobalPtr(offset: Long, gv: SbfGlobalVariable?) = SbfType.PointerType.Global<ConstantSet, ConstantSet>(ConstantSet(offset, maxNumDisjuncts), gv)
    override fun toGlobalPtr(offsets: List<Long>, gv: SbfGlobalVariable?): SbfType.PointerType.Global<ConstantSet, ConstantSet> {
        check(offsets.isNotEmpty())
        return SbfType.PointerType.Global(ConstantSet(offsets.map{ Constant(it) }.toSet(), maxNumDisjuncts), gv)
    }

    override fun anyNum() = SbfType.NumType<ConstantSet, ConstantSet>(ConstantSet.mkTop(maxNumDisjuncts))
    override fun anyStackPtr() = SbfType.PointerType.Stack<ConstantSet, ConstantSet>(ConstantSet.mkTop(maxNumDisjuncts))
    override fun anyHeapPtr() = SbfType.PointerType.Heap<ConstantSet, ConstantSet>(ConstantSet.mkTop(maxNumDisjuncts))
    override fun anyInputPtr() = SbfType.PointerType.Input<ConstantSet, ConstantSet>(ConstantSet.mkTop(maxNumDisjuncts))
    override fun anyGlobalPtr(gv: SbfGlobalVariable?) = SbfType.PointerType.Global<ConstantSet, ConstantSet>(ConstantSet.mkTop(maxNumDisjuncts), gv)
}
