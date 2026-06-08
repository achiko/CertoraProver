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

import config.Config
import config.DestructiveOptimizationsModeEnum
import report.RuleAlertReport
import solver.CounterexampleModel
import utils.runIf
import java.util.Locale

@OptIn(Config.DestructiveOptimizationsOption::class)
private val RE_RUN_TWO_STAGE =
    "You may want to re-run with " +
        "--prover_args ${Config.DestructiveOptimizationsMode.name} ${
            DestructiveOptimizationsModeEnum.TWOSTAGE.toString().lowercase(Locale.getDefault())
        } " +
        "and then check for imprecision labels in the call trace."

private val INFEASIBLE_PATH_MESSAGE = "The call trace may contain an infeasible path due to branch conditions " +
    "that were optimized out and whose values were chosen non-deterministically during interpretation. " +
    "The call trace may present the `true` branch, while the `false` branch is correct or vice versa. " +
    RE_RUN_TWO_STAGE

private val SMT_VALUE_CONFLICT_MESSAGE = "A value computed by interpretation does not match the " +
    "value from the SMT model. The SMT value takes precedence. " +
    RE_RUN_TWO_STAGE

@OptIn(Config.DestructiveOptimizationsOption::class)
val INTERPRETATION_FAILED_MESSAGE = "Executing the counter example by interpretation failed. " +
    RE_RUN_TWO_STAGE

/**
 * The result of the interpretation. It contains
 * [cex] - the new counter example model (the model from the destructive run enriched
 * by all values computed via interpretation).
 * [maybeInfeasible] - indicates that interpretation chooses a path non-deterministically,
 * i.e. for at least one branch condition at a [vc.data.TACCmd.Simple.JumpiCmd], the condition
 * wasn't evaluated and the execution continued along both branch, and one of them reached the
 * failing assert.
 * [smtValueConflict] - indicates that during interpretation there exists at least one
 * statement along the path for which the optimized value doesn't match the value that the
 * interpreted computed.
 */
data class InterpreterResult(
    val cex: CounterexampleModel,
    val maybeInfeasible: Boolean,
    val smtValueConflict: Boolean
) {
    fun toRuleAlerts() = listOfNotNull(
        runIf(maybeInfeasible) {
            RuleAlertReport.Info(INFEASIBLE_PATH_MESSAGE)
        },
        runIf(smtValueConflict) {
            RuleAlertReport.Info(SMT_VALUE_CONFLICT_MESSAGE)
        }
    )
}