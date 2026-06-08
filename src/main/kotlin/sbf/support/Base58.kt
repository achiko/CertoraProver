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

package sbf.support

fun base58Encode(input: ByteArray): String {
    val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    var num = java.math.BigInteger(1, input)
    val sb = StringBuilder()

    val base = java.math.BigInteger.valueOf(58)
    while (num > java.math.BigInteger.ZERO) {
        val (quotient, remainder) = num.divideAndRemainder(base)
        sb.append(alphabet[remainder.toInt()])
        num = quotient
    }

    // Add '1' for each leading zero byte
    for (byte in input) {
        if (byte == 0.toByte()) {
            sb.append('1')
        } else {
            break
        }
    }

    return sb.reverse().toString()
}
