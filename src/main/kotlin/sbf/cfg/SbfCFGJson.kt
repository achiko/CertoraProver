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

package sbf.cfg

import datastructures.stdcollections.*
import kotlinx.serialization.json.*

private val prettyJson = Json { prettyPrint = true }

/** Dump a [SbfCFG] to a pretty-printed JSON string.
 *
 * Format:
 * ```json
 * {
 *   "name": "<function name>",
 *   "entry": "<entry label>",
 *   "exit": "<exit label>",
 *   "blocks": [
 *     {
 *       "label": "<block label>",
 *       "predecessors": ["<label>", ...],
 *       "successors": ["<label>", ...],
 *       "instructions": [
 *         { "inst": "<SbfInstruction.toString()>", "meta": <metaId> },
 *         { "inst": "<SbfInstruction.toString()>" },
 *         ...
 *       ]
 *     },
 *     ...
 *   ],
 *   "metas": {
 *     "<metaId>": { "<key>": <value>, ... },
 *     ...
 *   }
 * }
 * ```
 *
 * Each instruction is a JSON object with an `"inst"` field holding [SbfInstruction.toString]
 * and, when there is associated [MetaData] or register-type information, a numeric `"meta"`
 * field whose value is the key into the top-level `"metas"` object.
 *
 * Metadata values are encoded as:
 * - Flag (Unit) keys  -> `true`
 * - Boolean keys      -> `true` / `false`
 * - Numeric keys      -> JSON number (unquoted)
 * - Everything else   -> JSON string via [toString]
 *
 * The metadata also includes register-type info for each typed slot in the instruction.
 * Keys are register names (`"r0"`, `"r1"`, ...). When the register has only a pre-execution
 * type the value is the type string itself (e.g. `"r1": "num(top)"`). When the register has
 * a post-execution type (the destination of [SbfInstruction.Bin], [SbfInstruction.Un], or a
 * load [SbfInstruction.Mem]), the value is a nested object with `"pre"` and `"post"` keys
 * (e.g. `"r0": {"pre": "num(top)", "post": "num(0)"}`); the `"pre"` key is omitted when its
 * type is unknown. Slots whose type is unknown emit no entry.
 */
fun SbfCFG.toJson(): String {
    var nextMetaId = 0
    val metaJsonToId = mutableMapOf<JsonObject, Int>()  // dedup key: JSON repr of metadata
    val metas = mutableMapOf<Int, JsonObject>()

    fun metaIdFor(inst: SbfInstruction): Int? {
        val regTypes = registerTypeEntries(inst)
        if (inst.metaData.entries.none() && regTypes.isEmpty()) {
            return null
        }
        val jsonObj = metaDataToJsonObject(inst.metaData, regTypes)
        return metaJsonToId.getOrPut(jsonObj) {
            val id = nextMetaId++
            metas[id] = jsonObj
            id
        }
    }

    val blocksArray = buildJsonArray {
        for (block in getBlocks().values) {
            add(buildJsonObject {
                put("label", block.getLabel().toString())
                put("predecessors", buildJsonArray {
                    block.getPreds().forEach { add(it.getLabel().toString()) }
                })
                put("successors", buildJsonArray {
                    block.getSuccs().forEach { add(it.getLabel().toString()) }
                })
                put("instructions", buildJsonArray {
                    for (inst in block.getInstructions()) {
                        val metaId = metaIdFor(inst)
                        add(buildJsonObject {
                            put("inst", inst.toString())
                            if (metaId != null) {
                                put("meta", metaId)
                            }
                        })
                    }
                })
            })
        }
    }

    val metasObject = buildJsonObject {
        metas.forEachEntry { (id, jsonObj) -> put(id.toString(), jsonObj) }
    }

    val root = buildJsonObject {
        put("name", getName())
        put("entry", getEntry().getLabel().toString())
        put("exit", getExit().getLabel().toString())
        put("blocks", blocksArray)
        put("metas", metasObject)
    }

    return prettyJson.encodeToString(JsonElement.serializer(), root)
}

private fun metaDataToJsonObject(metaData: MetaData, regTypes: Map<String, RegTypeInfo>): JsonObject {
    return buildJsonObject {
        for ((key, value) in metaData.entries) {
            val jsonValue: JsonElement = when (value) {
                is Unit    -> JsonPrimitive(true)
                is Boolean -> JsonPrimitive(value)
                is Int     -> JsonPrimitive(value)
                is Long    -> JsonPrimitive(value)
                is ULong   -> JsonPrimitive(value.toLong())
                is String  -> JsonPrimitive(value)
                else       -> JsonPrimitive(value.toString())
            }
            put(key.name, jsonValue)
        }
        for ((reg, info) in regTypes) {
            val v: JsonElement = if (info.post == null) {
                JsonPrimitive(info.pre!!)
            } else {
                buildJsonObject {
                    info.pre?.let { put("pre", JsonPrimitive(it)) }
                    put("post", JsonPrimitive(info.post))
                }
            }
            put(reg, v)
        }
    }
}

private data class RegTypeInfo(val pre: String?, val post: String?)

/**
 * Per-instruction register-type entries to embed alongside [MetaData] in JSON output.
 *
 * Returns a map keyed by register name. Each entry carries a pre- and/or post-execution type
 * (whichever are known). Imm operands and slots without a type contribute no entry.
 */
private fun registerTypeEntries(inst: SbfInstruction): Map<String, RegTypeInfo> {
    val pre = mutableMapOf<String, String>()
    val post = mutableMapOf<String, String>()

    fun addPre(reg: Value.Reg, type: SbfRegisterType?) {
        if (type != null) {
            pre["$reg"] = type.toString()
        }
    }
    fun addPost(reg: Value.Reg, type: SbfRegisterType?) {
        if (type != null) {
            post["$reg"] = type.toString()
        }
    }
    fun addTypedValue(tv: TypedValue) {
        (tv.v as? Value.Reg)?.let { addPre(it, tv.type) }
    }
    fun addTypedReg(tr: TypedReg) {
        addPre(tr.reg, tr.type)
    }
    fun addCond(cond: Condition) {
        addTypedReg(cond.typedLeft)
        addTypedValue(cond.typedRight)
    }

    when (inst) {
        is SbfInstruction.Bin -> {
            addTypedValue(inst.typedRhs)
            addPre(inst.dst, inst.preDstType)
            addPost(inst.dst, inst.postDstType)
        }
        is SbfInstruction.Un -> {
            addPre(inst.dst, inst.preDstType)
            addPost(inst.dst, inst.postDstType)
        }
        is SbfInstruction.Havoc -> addTypedReg(inst.typedDst)
        is SbfInstruction.Mem -> {
            if (inst.isLoad) {
                // For a load, the value reg is the destination: typedValue.type is its post-type.
                // When value reg == base reg (e.g. `r1 = *(r1 + 8)`), the same register also has
                // a pre-type from access.typedBase.type, and the two are merged below.
                (inst.typedValue.v as? Value.Reg)?.let { addPost(it, inst.typedValue.type) }
                addTypedReg(inst.access.typedBase)
            } else {
                addTypedValue(inst.typedValue)
                addTypedReg(inst.access.typedBase)
            }
        }
        is SbfInstruction.Select -> addCond(inst.cond)
        is SbfInstruction.Assume -> addCond(inst.cond)
        is SbfInstruction.Assert -> addCond(inst.cond)
        is SbfInstruction.Jump.ConditionalJump -> addCond(inst.cond)
        is SbfInstruction.Jump.UnconditionalJump,
        is SbfInstruction.Call,
        is SbfInstruction.CallReg,
        is SbfInstruction.Exit,
        is SbfInstruction.Debug -> {}
    }

    return (pre.keys + post.keys).associateWith { RegTypeInfo(pre[it], post[it]) }
}
