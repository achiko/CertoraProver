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

package report

import datastructures.stdcollections.minus
import datastructures.stdcollections.mutableMapOf
import datastructures.stdcollections.toList
import log.Logger
import log.LoggerTypes
import log.regression
import report.calltrace.CallTrace
import rules.RuleCheckResult
import rules.RuleCheckResult.Single.RuleCheckInfo.WithExamplesData.CounterExample
import vc.data.TACMeta
import vc.data.TACSymbol

private val logger = Logger(LoggerTypes.REPORT_UTILS)

/** Used for [HTMLReporter] (the old index.html page, not treeView) and [ConsoleReporter] (CLI summary table).  */
fun formatLocalAssignmentsForReport(counterExample: CounterExample?): String {
    val localAssignments = counterExample?.localAssignments ?: return "no local variables"

    return localAssignments
        .flattenedTerminals
        .sortedBy { it.name }
        .map {
            val value = it.formattedValue(localAssignments.formatter)
            "${it.name}=$value"
        }
        .onEach { Logger.regression { it } }
        .joinToString(separator = "<br>")
}

/** like `check`, but only warns, rather than throwing .. (might make this a function of our loggers?) */
fun checkWarn(cond: Boolean, msg: () -> String) {
    if (!cond) {
        logger.warn(msg)
    }
}

/**
 * For each CVL string, attempt to collect a length and a  content symbol, using the meta annotations.
 */
internal fun collectCVLStringsSymbols(allSymbols: Set<TACSymbol.Var>)
        : Pair<Map<String, TACSymbol.Var>, Map<String, TACSymbol.Var>> {
    val displayNameToLength = mutableMapOf<String, TACSymbol.Var>()
    val displayNameToContents = mutableMapOf<String, TACSymbol.Var>()
    val displayNameToHavocMe = mutableMapOf<String, TACSymbol.Var>()
    allSymbols.forEach { sym ->
        when {
            TACMeta.CVL_LENGTH_OF in sym.meta -> {
                val displayName = sym.meta[TACMeta.CVL_LENGTH_OF]!! // just checked that it's there, so !! should be ok
                displayNameToLength[displayName] = sym
            }

            TACMeta.CVL_DATA_OF in sym.meta -> {
                val displayName = sym.meta[TACMeta.CVL_DATA_OF]!! // just checked that it's there, so !! should be ok
                displayNameToContents[displayName] = sym
            }

            TACMeta.TACSIMPLESIMPLE_HAVOCME in sym.meta && TACMeta.CVL_DISPLAY_NAME in sym.meta -> {
                val displayName =
                    sym.meta[TACMeta.CVL_DISPLAY_NAME]!! // just checked that it's there, so !! should be ok
                displayNameToHavocMe[displayName] = sym
            }
        }
    }
    // (making a copy to avoid concurrentModification)
    (displayNameToLength.keys - displayNameToContents.keys).toList().forEach { displayName ->
        // found a _length but missing _data -- must have been havocced only -- take the havocme-map for the contents
        val havocMe = displayNameToHavocMe[displayName]
        if (havocMe != null) {
            displayNameToContents[displayName] = havocMe
        }
    }
    return displayNameToLength to displayNameToContents
}

/**
 * Indicates whether the receiver list contains a [RuleCheckResult] that should
 * make the Prover end with a [FinalResult.DIAGNOSTIC_ERROR] notification and the corresponding exitcode.
 */
fun List<RuleCheckResult>.anyDiagnosticErrors(): Boolean =
    this.any {
        when (it) {
            is RuleCheckResult.Multi -> {
                it.results.anyDiagnosticErrors()
            }

            is RuleCheckResult.Single.Basic -> {
                it.ruleCheckInfo.details.isFailure
            }

            is RuleCheckResult.Single.WithCounterExamples -> {
                it.ruleCheckInfo.anyDiagnosticErrors()
            }

            is RuleCheckResult.Error, is RuleCheckResult.Skipped -> {
                false
            }
        }
    }

private fun RuleCheckResult.Single.RuleCheckInfo.WithExamplesData.anyDiagnosticErrors(): Boolean = examples.any {
        it.callResolutionExampleMeta.isFailure ||
        it.details.isFailure ||
        it.callTrace is CallTrace.Failure
}
