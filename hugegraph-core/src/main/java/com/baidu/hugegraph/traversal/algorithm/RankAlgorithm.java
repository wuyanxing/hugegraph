/*
 * Copyright 2017 HugeGraph Authors
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.baidu.hugegraph.traversal.algorithm;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.tinkerpop.gremlin.util.function.TriConsumer;

import com.baidu.hugegraph.HugeGraph;
import com.baidu.hugegraph.backend.id.Id;
import com.baidu.hugegraph.schema.EdgeLabel;
import com.baidu.hugegraph.schema.VertexLabel;
import com.baidu.hugegraph.structure.HugeVertex;
import com.baidu.hugegraph.traversal.optimize.HugeTraverser;
import com.baidu.hugegraph.type.define.Directions;
import com.baidu.hugegraph.util.E;
import com.google.common.collect.ImmutableMap;

public class RankAlgorithm extends HugeTraverser {

    private final double alpha;
    private final long maxDepth;

    public RankAlgorithm(HugeGraph graph, double alpha, long maxDepth) {
        super(graph);
        this.alpha = alpha;
        this.maxDepth = maxDepth;
    }

    public Map<Id, Double> personalRank(Id source, String label) {
        Id labelId = this.graph().edgeLabel(label).id();
        Directions dir = this.getStartDirection(source, label);
        long degree = this.degreeOfVertex(source, dir, labelId);
        if (degree < 1) {
            return ImmutableMap.of(source, 1.0);
        }

        long depth = 0;
        Set<Id> outSeeds = new HashSet<>();
        Set<Id> inSeeds = new HashSet<>();
        if (dir == Directions.OUT) {
            outSeeds.add(source);
        } else {
            inSeeds.add(source);
        }
        Map<Id, Double> ranks = new HashMap<>();
        ranks.put(source, 1.0);
        while (++depth <= this.maxDepth) {
            Map<Id, Double> incrRanks = this.getIncrRanks(outSeeds, inSeeds,
                                                          labelId, ranks);
            ranks = this.compensateSourceVertex(source, incrRanks);
        }
        return ranks;
    }

    private Map<Id, Double> compensateSourceVertex(Id source,
                                                   Map<Id, Double> incrRanks) {
        double sourceRank = incrRanks.getOrDefault(source, 0.0);
        sourceRank += (1 - this.alpha);
        incrRanks.put(source, sourceRank);
        return incrRanks;
    }

    public Map<Id, Double> neighborRank(Id source, String label) {
        Id labelId = this.graph().edgeLabel(label).id();
        Directions dir = this.getStartDirection(source, label);
        long degree = this.degreeOfVertex(source, dir, labelId);
        if (degree < 1) {
            return ImmutableMap.of(source, 1.0);
        }

        long depth = 0;
        Set<Id> seeds = new HashSet<>();
        seeds.add(source);
        Map<Id, Double> ranks = new HashMap<>();
        ranks.put(source, 1.0);
        while (++depth <= this.maxDepth) {
            Map<Id, Double> incrRanks = this.getIncrRanks(seeds, dir,
                                                          labelId, ranks);
            ranks = this.combineIncrement(seeds, ranks, incrRanks);
            seeds = incrRanks.keySet();
            dir = dir.opposite();
        }
        return ranks;
    }

    private Map<Id, Double> combineIncrement(Set<Id> seeds,
                                             Map<Id, Double> ranks,
                                             Map<Id, Double> incrRanks) {
        // Update the rank of the neighbor vertices
        for (Map.Entry<Id, Double> entry : incrRanks.entrySet()) {
            double oldRank = ranks.getOrDefault(entry.getKey(), 0.0);
            double incrRank = entry.getValue();
            double newRank = oldRank + incrRank;
            ranks.put(entry.getKey(), newRank);
        }
        // The rank of each seed vertex is attenuated to original * (1 - alpha)
        for (Id seed : seeds) {
            double oldRank = ranks.get(seed);
            double newRank = oldRank * (1 - alpha);
            ranks.put(seed, newRank);
        }
        return ranks;
    }

    private Map<Id, Double> getIncrRanks(Set<Id> outSeeds, Set<Id> inSeeds,
                                         Id label, Map<Id, Double> ranks) {
        Set<Id> tmpOutSeeds = new HashSet<>();
        Set<Id> tmpInSeeds = new HashSet<>();

        Map<Id, Double> incrRanks = new HashMap<>();

        TriConsumer<Set<Id>, Directions, Set<Id>> collectNeighborIncrRanks =
                (seeds, dir, tmpSeeds) -> {
            for (Id seed : seeds) {
                long degree = this.degreeOfVertex(seed, dir, label);
                assert degree > 0;
                // Must be exist
                double originRank = ranks.get(seed);
                double spreadRank = originRank * alpha / degree;

                Set<Id> neighbors = this.adjacentVertices(seed, dir, label);
                // Collect all neighbors increment
                for (Id neighbor : neighbors) {
                    tmpSeeds.add(neighbor);
                    // Assign an initial value when firstly update neighbor rank
                    double incrRank = incrRanks.getOrDefault(neighbor, 0.0);
                    incrRank += spreadRank;
                    incrRanks.put(neighbor, incrRank);
                }
            }
        };

        collectNeighborIncrRanks.accept(outSeeds, Directions.OUT, tmpInSeeds);
        collectNeighborIncrRanks.accept(inSeeds, Directions.IN, tmpOutSeeds);

        outSeeds.addAll(tmpOutSeeds);
        inSeeds.addAll(tmpInSeeds);
        return incrRanks;
    }

    private Map<Id, Double> getIncrRanks(Set<Id> seeds, Directions dir,
                                         Id label, Map<Id, Double> ranks) {
        Map<Id, Double> rankIncrs = new HashMap<>();
        for (Id seed : seeds) {
            long degree = this.degreeOfVertex(seed, dir, label);
            assert degree > 0;
            // Must be exist
            double originRank = ranks.get(seed);
            double spreadRank = originRank * alpha / degree;

            Set<Id> neighbors = this.adjacentVertices(seed, dir, label);
            // Collect all neighbors increment
            for (Id neighbor : neighbors) {
                // Assign an initial value when firstly update neighbor rank
                double incrRank = rankIncrs.getOrDefault(neighbor, 0.0);
                incrRank += spreadRank;
                rankIncrs.put(neighbor, incrRank);
            }
        }
        return rankIncrs;
    }

    private Directions getStartDirection(Id source, String label) {
        // NOTE: The outer layer needs to ensure that the vertex Id is valid
        HugeVertex vertex = (HugeVertex) graph().vertices(source).next();
        VertexLabel vertexLabel = vertex.schemaLabel();
        EdgeLabel edgeLabel = this.graph().edgeLabel(label);
        E.checkArgument(edgeLabel.linkWithLabel(vertexLabel.id()),
                        "The vertex '%s' doesn't link with edge label '%s'",
                        source, label);

        if (edgeLabel.sourceLabel().equals(vertexLabel.id())) {
            return Directions.OUT;
        } else {
            assert edgeLabel.targetLabel().equals(vertexLabel.id());
            return Directions.IN;
        }
    }
}
