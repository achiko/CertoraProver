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

package analysis.opt.intervals

import datastructures.*
import datastructures.stdcollections.*
import java.util.TreeSet
import log.*
import org.jetbrains.annotations.TestOnly
import utils.*
import utils.Color.Companion.blue
import utils.Color.Companion.green
import utils.Color.Companion.yellow

private val logger = Logger(LoggerTypes.INTERVALS_SIMPLIFIER)

/**
 * A fancy name for something pretty simple.
 *
 * The vertices (of type [V]) have values (of type [T]) attached to them. Each [Edge] holds a "transformation" on
 * the edge's vertices (can be more than 2 vertices, hence hyper-graph), that can change the values of these vertices.
 *
 * [State.fixedPoint] runs these edge transformations until reaching a fixed point.
 *
 * [defaultValue] gives the value of a vertex if it was never assigned before.
 * [normalize] is run before setting a new value, it takes the old and the new values of a vertex (after a
 * transformation), and returns the actual value to save.
 *
 * We can improve efficiency a bit by allowing edges to say if they surely didn't change a vertex.
 */
class HyperGraphFixedPointCalculator<V, T : Any>(
    private val defaultValue: (V) -> T,
    /**
     * Activated before saving a value related to a vertex. It takes the vertex, the old value, and the new calculated
     * value. It returns the actual value to save.
     * There are a few use cases for this, but one is to avoid saving values that are too complex, and so [normalize]
     * would simplify them before actually saving them.
     */
    private val normalize: (V, T, T) -> T,
    private val maxFactor: Int = 4
) {
    private inner class Edge(
        val vertices: List<V>,
        val func: (List<T>) -> List<T>,
        val name: String,
        /** For ordering the edges in the work queue; edges that are added to the graph earlier come first */
        private val ordinal: Int
    ) : Comparable<Edge> {
        override fun toString() = name
        override fun compareTo(other: Edge) = ordinal.compareTo(other.ordinal)
    }

    private val vertexToEdges = mutableMultiMapOf<V, Edge>()
    private val allEdges = mutableSetOf<Edge>()

    fun addEdge(vertices: List<V>, func: (List<T>) -> List<T>, name: String) {
        val e = Edge(vertices, func, name, ordinal = allEdges.size)
        allEdges += e
        for (v in vertices) {
            vertexToEdges.add(v, e)
        }
    }

    fun addEdge(v1: V, v2: V, func: (T, T) -> List<T>, name: String) {
        addEdge(listOf(v1, v2), { l -> func(l[0], l[1]) }, name)
    }

    /**
        To reduce the number of map lookups, we store the values in a mutable [Box] (so that we can get the current
        value and also update it with a single map lookup).
     */
    private class Box<T>(var t: T) {
        override fun toString() = "$t"
    }

    inner class State private constructor(private val vals: ArrayHashMap<V, Box<T>>) {
        constructor() : this(ArrayHashMap(loadFactor = 1f))

        fun get(v: V) = vals[v]?.t ?: defaultValue(v)
        fun getOrNull(v: V) = vals[v]?.t
        fun set(v: V, t: T) {
            val box = vals.computeIfAbsent(v) { Box(defaultValue(it)) }
            box.t = normalize(v, box.t, t)
        }

        val vertices: Set<V> = vals.keys

        fun duplicate() = State(
            vals.mapValuesTo(ArrayHashMap(vals.size, vals.loadFactor)) { (_, b) -> Box(b.t) }
        )

        /**
         * [startWith] is the set of vertices we consider "changed" when we start, i.e., any edge that contains them
         * should be run. If this is null, then all edges should be run.
         */
        fun fixedPoint(startWith: Collection<V>? = null) {
            if (allEdges.isEmpty()) {
                return
            }

            // We use a TreeSet as a queue to get the edges in the order they were added to the graph.
            val queue: TreeSet<Edge> = startWith
                ?.flatMapTo(TreeSet()) { vertexToEdges[it].orEmpty() }
                ?: TreeSet(allEdges)

            var count = 0
            val maxCount = allEdges.size * maxFactor
            var e = queue.firstOrNull()
            while (e != null) {
                if (++count > maxCount) {
                    logger.warn {
                        "Factor of $maxFactor exceeded. Stopping fixed point computation"
                    }
                    break
                }
                val vertices = e.vertices
                val boxes = vertices.map { vals[it] }
                val olds = boxes.mapIndexed { i, box -> box?.t ?: defaultValue(vertices[i]) }
                val news = e.func(olds)
                logger.trace {
                    "${e.green}\n" +
                        zip(vertices, olds, news).joinToString("\n") { (v, o, n) ->
                            "   ${v.yellow} : ${
                                "$o -> $n".letIf(o != n) {
                                    it.blue
                                }
                            }"
                        }
                }
                vertices.forEachIndexed { i, v ->
                    // In case the vertex is used twice by this edge, we need to get the most up-to-date boxed value
                    val box = boxes[i] ?: vals[v]
                    val old = box?.t ?: defaultValue(v)
                    val new = news[i]
                    val normalizedNew = normalize(v, old, new)
                    if (normalizedNew != old) {
                        if (box != null) {
                            box.t = normalizedNew
                        } else {
                            vals[v] = Box(normalizedNew)
                        }
                        queue += vertexToEdges[v].orEmpty()
                    }
                }
                queue -= e

                // We traverse the edges in queue in the order in which they were added to the graph, continuing on to
                // "later" edges even if "earlier" edges are added to the queue.  When we've exhausted the "later"
                // edges, we start over with the "early" ones. Experimentally, this seems to be a good heuristic for
                // convergence speed.
                e = queue.higher(e) ?: queue.firstOrNull()
            }
            logger.debug {
                "edgeCalculations = $count, edges = ${allEdges.size}, factor = ${count / allEdges.size}"
            }
        }

        @TestOnly
        fun edgesString() =
            allEdges.joinToString("\n") { e ->
                "$e : ${e.vertices}"
            }
    }

}
