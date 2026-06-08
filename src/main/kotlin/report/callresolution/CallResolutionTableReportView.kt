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

package report.callresolution

import kotlinx.serialization.json.*
import report.*

/**
 * A view of a [CallResolutionTableRow], which is tree-view reportable and should be used to produce
 * the corresponding Json element of the row.
 */
sealed class CallResolutionTableRowReportView<T : CallResolutionTableRow> : TreeViewReportable {

    abstract val row: T

    override val treeViewRepBuilder = TreeViewRepJsonObjectBuilder {
        putJsonObject(CallResolutionTableReportView.Attribute.CALLER()) {
            put(CallResolutionTableReportView.Attribute.NAME(), row.caller)

            put(
                CallResolutionTableReportView.Attribute.JUMP_TO_DEFINITION(),
                TreeViewLocation.Empty  // TODO - pass real location
            )
        }

        put(
            CallResolutionTableReportView.Attribute.CALL_SITE(),
            row.callSite?.toTreeViewRep() ?: buildJsonObject { })

        put(CallResolutionTableReportView.Attribute.SUMMARY(), row.summary)

        putJsonArray(CallResolutionTableReportView.Attribute.COMMENTS()) {
            for (comment in row.resolutionComments) {
                addJsonObject { put(comment.key, comment.value) }
            }
        }
    }

}

/**
 * A view of a [CallResolutionTable], which is tree-view reportable and should be used to produce
 * the corresponding Json element of the table.
 */
abstract class CallResolutionTableReportView<T : CallResolutionTableRowReportView<*>> : TreeViewReportable {

    enum class Attribute(private val repString: String) {
        CALLER("caller"),
        CALL_SITE("callSite"),
        CALLEE("callee"),
        SUMMARY("summary"),
        COMMENTS("comments"),
        IS_WARNING("isWarning"),
        GLOBAL_CALL_RESOLUTION_ROWS("globalCallResolutionRows"),
        CALL_RESOLUTION("callResolution"),
        CALL_RESOLUTION_WARNINGS("callResolutionWarnings"),
        IS_IN_COUNTER_EXAMPLE("isInCounterExample"),
        NAME("name"),
        JUMP_TO_DEFINITION("jumpToDefinition"),
        STORAGE_PATH("storagePath"),
        SELECTOR("selector")
        ;

        operator fun invoke(): String = repString
    }

    /**
     * Set of views of entries in the table (view).
     */
    abstract val rowViews: Set<T>

    override val treeViewRepBuilder = TreeViewRepJsonArrayBuilder {
        addAll(rowViews)
    }

}

