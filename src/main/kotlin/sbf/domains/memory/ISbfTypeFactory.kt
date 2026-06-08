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

interface ISbfTypeFactory<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> {
    fun mkTop(): SbfType<TNum, TOffset>
    fun mkBottom(): SbfType<TNum, TOffset>

    fun numToOffset(num: TNum): TOffset
    fun offsetToNum(offset: TOffset): TNum

    fun toNum(value: ULong): SbfType.NumType<TNum, TOffset>
    fun toNum(value: Long): SbfType.NumType<TNum, TOffset>

    fun toStackPtr(offset: Long): SbfType.PointerType.Stack<TNum, TOffset>
    fun toHeapPtr(offset: Long): SbfType.PointerType.Heap<TNum, TOffset>
    fun toInputPtr(offset: Long): SbfType.PointerType.Input<TNum, TOffset>
    fun toGlobalPtr(offset: Long, gv: SbfGlobalVariable?): SbfType.PointerType.Global<TNum, TOffset>
    // offsets cannot be empty
    fun toGlobalPtr(offsets: List<Long>, gv: SbfGlobalVariable?): SbfType.PointerType.Global<TNum, TOffset>

    fun anyNum(): SbfType.NumType<TNum, TOffset>
    fun anyStackPtr(): SbfType.PointerType.Stack<TNum, TOffset>
    fun anyHeapPtr(): SbfType.PointerType.Heap<TNum, TOffset>
    fun anyInputPtr():  SbfType.PointerType.Input<TNum, TOffset>
    fun anyGlobalPtr(gv: SbfGlobalVariable?): SbfType.PointerType.Global<TNum, TOffset>
}
