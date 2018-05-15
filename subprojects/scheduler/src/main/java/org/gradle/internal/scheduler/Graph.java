/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.scheduler;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import org.gradle.api.Action;
import org.gradle.api.CircularReferenceException;
import org.gradle.internal.Actions;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Graph {
    private final SetMultimap<Node, Edge> incomingEdges = LinkedHashMultimap.create();
    private final SetMultimap<Node, Edge> outgoingEdges = LinkedHashMultimap.create();
    private final Set<Node> rootNodes = Sets.newLinkedHashSet();

    public boolean hasNodes() {
        return !rootNodes.isEmpty() || !incomingEdges.isEmpty();
    }

    public ImmutableList<Node> getAllNodes() {
        return ImmutableList.copyOf(Iterables.concat(rootNodes, incomingEdges.keySet()));
    }

    public ImmutableList<Edge> getAllEdges() {
        return ImmutableList.copyOf(incomingEdges.values());
    }

    @VisibleForTesting
    Collection<Edge> getIncomingEdges(Node target) {
        return incomingEdges.get(target);
    }

    @VisibleForTesting
    Collection<Edge> getOutgoingEdges(Node source) {
        return outgoingEdges.get(source);
    }

    public Iterable<Node> getRootNodes() {
        return ImmutableList.copyOf(rootNodes);
    }

    public void addNode(Node node) {
        if (rootNodes.contains(node) || incomingEdges.containsKey(node)) {
            throw new IllegalArgumentException("Node is already present in graph: " + node);
        }
        rootNodes.add(node);
    }

    public void processOutgoingEdges(Node node, EdgeAction action) {
        Set<Edge> outgoingFromNode = outgoingEdges.get(node);
        if (!outgoingFromNode.isEmpty()) {
            for (Edge outgoing : Lists.newArrayList(outgoingFromNode)) {
                if (action.process(outgoing) == EdgeActionResult.REMOVE) {
                    removeEdge(outgoing);
                }
            }
        }
    }

    public void removeNodeWithOutgoingEdges(Node node, Action<? super Edge> removalAction) {
        if (rootNodes.contains(node)) {
            Set<Edge> outgoing = outgoingEdges.get(node);
            if (!outgoing.isEmpty()) {
                for (Edge edge : Lists.newArrayList(outgoing)) {
                    removeEdge(edge);
                    removalAction.execute(edge);
                }
            }
            rootNodes.remove(node);
        } else if (incomingEdges.containsKey(node)) {
            throw new IllegalStateException("Node to be removed has incoming edges: " + node);
        } else {
            throw new IllegalArgumentException("Node is not present in the graph: " + node);
        }
    }

    public Graph breakCycles(CycleReporter cycleReporter) throws CircularReferenceException {
        Map<Node, Node> parents = Maps.newHashMap();
        List<Edge> removableEdges = Lists.newArrayListWithCapacity(incomingEdges.size());

        // Start by copying root nodes over
        Graph dag = new Graph();
        for (Node rootNode : rootNodes) {
            dag.addNode(rootNode);
        }
        // Copy non-root nodes over, too
        for (Node node : incomingEdges.keySet()) {
            dag.addNode(node);
        }

        // First add non-breakable edges
        for (Edge edge : incomingEdges.values()) {
            Node source = edge.getSource();
            Node target = edge.getTarget();
            if (edge.isRemovableToBreakCycles()) {
                removableEdges.add(edge);
                continue;
            }
            if (hasAmongAncestors(parents, source, target)) {
                // Report cycle
                List<Node> path = Lists.newArrayList();
                addAncestorsToPath(parents, source, path);
                String message = cycleReporter.reportCycle(this, path);
                throw new CircularReferenceException(String.format("Circular dependency between the following tasks:%n%s", message));
            }
            dag.addEdge(edge);
            parents.put(target, source);
        }

        // Then add any breakable edges as long as they don't form a cycle
        for (Edge removableEdge : removableEdges) {
            Node source = removableEdge.getSource();
            Node target = removableEdge.getTarget();
            if (hasAmongAncestors(parents, source, target)) {
                // Skip edge if it would form a cycle
                continue;
            }
            dag.addEdge(removableEdge);
            // TODO Need to track all weak parents, not just one
            parents.put(target, source);
        }

        return dag;
    }

    private static void addAncestorsToPath(Map<Node, Node> parents, Node child, List<Node> path) {
        path.add(child);
        Node parent = parents.get(child);
        if (parent != null) {
            addAncestorsToPath(parents, parent, path);
        }
    }

    private static boolean hasAmongAncestors(Map<Node, Node> parents, Node parent, Node child) {
        Node grandParent = parents.get(parent);
        if (grandParent == null) {
            return false;
        }
        if (grandParent == child) {
            return true;
        }
        return hasAmongAncestors(parents, grandParent, child);
    }

    public void walkIncomingEdgesFrom(Node start, EdgeWalkerAction action) {
        Deque<Node> queue = new ArrayDeque<Node>();
        queue.add(start);
        while (true) {
            Node node = queue.poll();
            if (node == null) {
                break;
            }
            for (Edge incoming : Lists.newArrayList(incomingEdges.get(node))) {
                if (action.execute(incoming)) {
                    queue.add(incoming.getSource());
                }
            }
        }
    }

    public interface EdgeWalkerAction {
        boolean execute(Edge edge);
    }

    /**
     * Removes nodes with their incoming edges that are not accessible from the given entry nodes via live edges.
     */
    public void removeDeadNodes(Collection<? extends Node> entryNodes, LiveEdgeDetector detector) {
        Set<Node> liveNodes = Sets.newLinkedHashSet();
        Deque<Node> queue = new ArrayDeque<Node>(entryNodes);
        while (true) {
            Node node = queue.poll();
            if (node == null) {
                break;
            }
            if (!liveNodes.add(node)) {
                continue;
            }
            for (Edge incoming : incomingEdges.get(node)) {
                Node source = incoming.getSource();
                if (!liveNodes.contains(source) && detector.isIncomingEdgeLive(incoming)) {
                    queue.add(source);
                }
            }
            for (Edge outgoing : outgoingEdges.get(node)) {
                Node target = outgoing.getTarget();
                if (!liveNodes.contains(target) && detector.isOutgoingEdgeLive(outgoing)) {
                    queue.add(target);
                }
            }
        }
        for (Edge edge : Lists.newArrayList(incomingEdges.values())) {
            if (!liveNodes.contains(edge.getSource())
                || !liveNodes.contains(edge.getTarget())) {
                removeEdge(edge);
            }
        }
        for (Node node : getAllNodes()) {
            if (!liveNodes.contains(node)) {
                removeNodeWithOutgoingEdges(node, Actions.doNothing());
            }
        }
    }

    public interface LiveEdgeDetector {
        boolean isIncomingEdgeLive(Edge edge);
        boolean isOutgoingEdgeLive(Edge edge);
    }

    public void addEdge(Edge edge) {
        if (!addEdgeIfAbsent(edge)) {
            throw new IllegalArgumentException("Edge already present in graph: " + edge);
        }
    }

    public boolean addEdgeIfAbsent(Edge edge) {
        Node source = edge.getSource();
        Node target = edge.getTarget();
        boolean targetWasRootNode = rootNodes.contains(target);
        if (!targetWasRootNode && !incomingEdges.containsKey(target)) {
            throw new IllegalArgumentException("Target node for edge to be added is not present in graph: " + edge);
        }
        if (!rootNodes.contains(source) && !incomingEdges.containsKey(source)) {
            throw new IllegalArgumentException("Source node for edge to be added is not present in graph: " + edge);
        }
        if (!incomingEdges.put(target, edge)) {
            return false;
        }
        outgoingEdges.put(source, edge);
        if (targetWasRootNode) {
            rootNodes.remove(target);
        }
        return true;
    }

    private void removeEdge(Edge edge) {
        Node source = edge.getSource();
        Node target = edge.getTarget();
        if (!incomingEdges.remove(target, edge)) {
            throw new IllegalArgumentException("Edge not part of the graph: " + edge);
        }
        if (!outgoingEdges.remove(source, edge)) {
            throw new AssertionError("Edge was present in incoming edges but not in outgoing edges: " + edge);
        }
        if (!incomingEdges.containsKey(target)) {
            rootNodes.add(target);
        }
    }

    public interface EdgeAction {
        /**
         * Processes the given edge and returns whether or not to keep it.
         */
        EdgeActionResult process(Edge edge);
    }

    public enum EdgeActionResult {
        KEEP, REMOVE
    }
}