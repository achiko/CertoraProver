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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package cvl

import infra.CVLFlow
import org.junit.jupiter.api.Test
import kotlin.io.path.Path

class TestEventHooks : AbstractCVLTest() {
    @Test
    fun testCollision() {
        testFlowWithPredicatesCVLError(
            CVLFlow().getProverQuery(Path("src/test/resources/cvl/EventHooks/collide.conf")), listOf(
                GeneralType("BadCollide.spec", 4, 6, "duplicates the hook pattern `Event _.SignatureCollision(Target.MyType a)`"),
                GeneralType("BadCollide.spec", 12, 14, "duplicates the hook pattern `Event _.BasicEvent(uint256 a)` at")
            )
        )

    }
}
