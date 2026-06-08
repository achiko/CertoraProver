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

package move

import analysis.CommandWithRequiredDecls.Companion.mergeMany
import datastructures.stdcollections.*
import java.util.concurrent.ConcurrentHashMap
import report.*
import tac.*
import tac.generation.*
import vc.data.*

/** Tracks the warnings issued already, to avoid duplicate alerts */
private val reported = ConcurrentHashMap.newKeySet<String>()

/**
    For legacy reasons, we allow new "nondet" references to be created in some cases.  These just point to a fresh
    location initialized to a nondet value.  This is unsound (because perhaps there would be a valid counterexample
    where the reference points to a different location?), but we allowed it in the beginning for convenience.  Now we
    issue a warning whenever this happens, and encourage spec writers to find a different way.
 */
fun MoveType.Reference.assignUnsoundHavoc(
    dest: TACSymbol.Var,
    warning: String,
    jumpToDefinition: TreeViewLocation?,
    meta: MetaMap = MetaMap()
): MoveCmdsWithDecls {
    if (reported.add(warning)) {
        CVTAlertReporter.reportAlert(
            type = CVTAlertType.CVL,
            severity = CVTAlertSeverity.WARNING,
            jumpToDefinition = jumpToDefinition,
            message = warning
        )
    }
    val havocLoc = TACKeyword.TMP(refType.toTag(), "havocLoc")
    return mergeMany(
        tac.generation.assignHavoc(havocLoc, meta),
        TACCmd.Move.BorrowLocCmd(
            ref = dest,
            loc = havocLoc,
            meta = meta
        ).withDecls(dest)
    )
}


sealed class FailedCallReason {
    object UnsummarizedNativeFunction : FailedCallReason()
    data class ShadowedTypeAccess(val typeName: MoveDatatypeName) : FailedCallReason()
}

/**
    If we cannot generate TAC for a call, we issue a warning and generate a summary that asserts false.
 */
context(SummarizationContext)
fun failCall(call: MoveCall, reason: FailedCallReason): MoveBlocks {
    val func = call.callee

    val message = "$func should be summarized" + when (reason) {
        FailedCallReason.UnsummarizedNativeFunction ->
            " because it is a native function."
        is FailedCallReason.ShadowedTypeAccess ->
            " because it accesses shadowed type ${reason.typeName}."
    }

    if (reported.add(message)) {
        // We issue a warning, rather than an error, because it's possible that the call won't be reachable, and so
        // rules may still be able to work.  Any rules that do reach this call will fail due to the assert-false below.
        CVTAlertReporter.reportAlert(
            type = CVTAlertType.CVL,
            severity = CVTAlertSeverity.WARNING,
            jumpToDefinition = func.range,
            message = message + " (called from ${call.callStack.format()})"
        )
    }

    check(call.returns.size == func.returns.size) {
        "Return count mismatch: ${call.returns.size} != ${func.returns.size}"
    }
    return singleBlockSummary(call) {
        mergeMany(
            // We assert false to ensure that any rules that reach this call will fail.
            assert(message) { false.asTACExpr },
            // We still need to generate the outputs of the function to keep later TAC transforms happy, but we don't
            // need to do this soundly.
            mergeMany(
                func.returns.zip(call.returns).mapIndexed { i, (type, ret) ->
                    when (type) {
                        is MoveType.Value -> type.assignHavoc(ret)
                        is MoveType.Reference -> {
                            val fakeLoc = TACSymbol.Var("${func.toVarName()}_ret_${i}_loc", type.refType.toTag())
                            mergeMany(
                                assignHavoc(fakeLoc),
                                TACCmd.Move.BorrowLocCmd(ref = ret, loc = fakeLoc).withDecls(ret)
                            )
                        }
                    }
                }
            )
        )
    }
}
