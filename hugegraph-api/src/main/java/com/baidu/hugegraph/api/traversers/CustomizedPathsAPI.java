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

package com.baidu.hugegraph.api.traversers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;

import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.slf4j.Logger;

import com.baidu.hugegraph.HugeGraph;
import com.baidu.hugegraph.api.API;
import com.baidu.hugegraph.api.filter.StatusFilter.Status;
import com.baidu.hugegraph.api.graph.VertexAPI;
import com.baidu.hugegraph.backend.id.Id;
import com.baidu.hugegraph.backend.query.Condition;
import com.baidu.hugegraph.backend.query.ConditionQuery;
import com.baidu.hugegraph.core.GraphManager;
import com.baidu.hugegraph.schema.EdgeLabel;
import com.baidu.hugegraph.schema.PropertyKey;
import com.baidu.hugegraph.server.RestServer;
import com.baidu.hugegraph.structure.HugeVertex;
import com.baidu.hugegraph.traversal.algorithm.CustomizePathTraverser;
import com.baidu.hugegraph.traversal.algorithm.HugeTraverser;
import com.baidu.hugegraph.type.HugeType;
import com.baidu.hugegraph.type.define.Directions;
import com.baidu.hugegraph.type.define.HugeKeys;
import com.baidu.hugegraph.util.E;
import com.baidu.hugegraph.util.Log;
import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.annotation.JsonProperty;

import static com.baidu.hugegraph.traversal.algorithm.HugeTraverser.*;

@Path("graphs/{graph}/traversers/customizedpaths")
@Singleton
public class CustomizedPathsAPI extends API {

    private static final Logger LOG = Log.logger(RestServer.class);

    @POST
    @Timed
    @Status(Status.CREATED)
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON_WITH_CHARSET)
    public String post(@Context GraphManager manager,
                       @PathParam("graph") String graph,
                       PathRequest request) {
        LOG.debug("Graph [{}] get customized paths from source vertex '{}', " +
                  "with steps '{}', sort by '{}', capacity '{}', limit '{}' " +
                  "and with_vertex '{}'", graph, request.sources, request.steps,
                  request.sortBy, request.capacity, request.limit,
                  request.withVertex);

        E.checkArgumentNotNull(request, "The path request body can't be null");
        E.checkArgumentNotNull(request.sources,
                               "The sources of path request can't be null");
        E.checkArgument(!request.steps.isEmpty(),
                        "The steps of path request can't be empty");
        E.checkArgumentNotNull(request.sortBy,
                               "The sortBy of path request can't be null");

        HugeGraph g = graph(manager, graph);
        List<HugeVertex> sources = sources(g, request.sources);
        List<CustomizePathTraverser.Step> steps = step(g, request);
        boolean sorted = request.sortBy != SortBy.NONE;

        CustomizePathTraverser traverser = new CustomizePathTraverser(g);
        List<HugeTraverser.Path> paths;
        paths = traverser.customizedPaths(sources, steps, sorted,
                                          request.capacity, request.limit);

        if (sorted) {
            boolean incr = request.sortBy == SortBy.INCR;
            paths = CustomizePathTraverser.topNPath(paths, incr, request.limit);
        }

        if (!request.withVertex) {
            return manager.serializer(g).writePaths("paths", paths, false);
        }

        Set<Id> ids = new HashSet<>();
        for (HugeTraverser.Path p : paths) {
            ids.addAll(p.vertices());
        }
        Iterator<Vertex> iter = Collections.emptyIterator();
        if (!ids.isEmpty()) {
            iter =g.vertices(ids.toArray());
        }
        return manager.serializer(g).writePaths("paths", paths, false, iter);
    }

    private static List<HugeVertex> sources(HugeGraph g,
                                            SourceVertices sources) {
        Map<String, Object> props = sources.properties;
        E.checkArgument(!((sources.ids == null || sources.ids.isEmpty()) &&
                        (props == null || props.isEmpty()) &&
                        sources.label == null), "No source vertices provided");
        int size = sources.ids.size();
        List<HugeVertex> vertices = new ArrayList<>(size);
        Iterator<Vertex> iter;
        if (sources.ids != null && !sources.ids.isEmpty()) {
            List<Id> sourceIds = new ArrayList<>(size);
            for (String id : sources.ids) {
                sourceIds.add(VertexAPI.checkAndParseVertexId(id));
            }
            iter = g.vertices(sourceIds.toArray());
            E.checkArgument(iter.hasNext(),
                            "Not exist source vertexes with ids %s",
                            sources.ids);
        } else {
            ConditionQuery query = new ConditionQuery(HugeType.VERTEX);
            if (sources.label != null) {
                Id label = g.vertexLabel(sources.label).id();
                query.eq(HugeKeys.LABEL, label);
            }
            if (props != null && !props.isEmpty()) {
                for (Map.Entry<String, Object> entry : props.entrySet()) {
                    Id pkeyId = g.propertyKey(entry.getKey()).id();
                    Object value = entry.getValue();
                    if (value instanceof List) {
                        query.query(Condition.in(pkeyId, (List<?>) value));
                    } else {
                        query.query(Condition.eq(pkeyId, value));
                    }
                }
            }
            iter = g.vertices(query);
            E.checkArgument(iter.hasNext(), "Not exist source vertex with " +
                            "label '%s' and properties '%s'",
                            sources.label, props);
        }
        while (iter.hasNext()) {
            vertices.add((HugeVertex) iter.next());
        }
        return vertices;
    }

    private static List<CustomizePathTraverser.Step> step(HugeGraph graph,
                                                          PathRequest request) {
        int stepSize = request.steps.size();
        List<CustomizePathTraverser.Step> steps = new ArrayList<>(stepSize);
        for (Step step : request.steps) {
            steps.add(step.jsonToStep(graph));
        }
        return steps;
    }

    private static class PathRequest {

        @JsonProperty("sources")
        public SourceVertices sources;
        @JsonProperty("steps")
        public List<Step> steps;
        @JsonProperty("sort_by")
        public SortBy sortBy;
        @JsonProperty("capacity")
        public long capacity = Long.valueOf(DEFAULT_CAPACITY);
        @JsonProperty("limit")
        public long limit = Long.valueOf(DEFAULT_PATHS_LIMIT);
        @JsonProperty("with_vertex")
        public boolean withVertex = false;

        @Override
        public String toString() {
            return String.format("pathRequest{sourceVertex=%s,steps=%s," +
                                 "sortBy=%s,capacity=%s,limit=%s," +
                                 "withVertex=%s}", this.sources, this.steps,
                                 this.sortBy, this.capacity, this.limit,
                                 this.withVertex);
        }
    }

    private static class SourceVertices {

        @JsonProperty("ids")
        public Set<String> ids;
        @JsonProperty("label")
        public String label;
        @JsonProperty("properties")
        public Map<String, Object> properties;

        @Override
        public String toString() {
            return String.format("sourceVertex{ids=%s,label=%s,properties=%s}",
                                 this.ids, this.label, this.properties);
        }
    }

    private static class Step {

        @JsonProperty("direction")
        public String direction;
        @JsonProperty("labels")
        public List<String> labels;
        @JsonProperty("properties")
        public Map<String, Object> properties;
        @JsonProperty("weight_by")
        public String weightBy;
        @JsonProperty("default_weight")
        public double defaultWeight = Double.valueOf(DEFAULT_WEIGHT);
        @JsonProperty("degree")
        public long degree = Long.valueOf(DEFAULT_DEGREE);
        @JsonProperty("sample")
        public long sample = Long.valueOf(DEFAULT_SAMPLE);

        @Override
        public String toString() {
            return String.format("step:{direction=%s,labels=%s,properties=%s," +
                                 "weightBy=%s,defaultWeight=%s,degree=%s," +
                                 "sample=%s}", this.direction, this.labels,
                                 this.properties, this.weightBy,
                                 this.defaultWeight, this.degree, this.sample);
        }

        private CustomizePathTraverser.Step jsonToStep(HugeGraph graph) {
            E.checkArgument(this.degree > 0 || this.degree == NO_LIMIT,
                            "The degree must be > 0 or == -1, but got: %s",
                            this.degree);
            E.checkArgument(this.sample >= 0,
                            "The sample must be >= 0, but got: %s",
                            this.sample);
            E.checkArgument(this.degree == NO_LIMIT || this.degree >= sample,
                            "Degree must be greater than or equal to sample," +
                            " but got degree %s and sample %s", degree, sample);
            Directions direction = Directions.valueOf(this.direction);
            Map<Id, String> labelIds = new HashMap<>(this.labels.size());
            for (String label : this.labels) {
                EdgeLabel el = graph.edgeLabel(label);
                labelIds.put(el.id(), label);
            }
            PropertyKey weightBy = null;
            if (this.weightBy != null) {
                weightBy = graph.propertyKey(this.weightBy);
            }
            return new CustomizePathTraverser.Step(direction, labelIds,
                                                   this.properties, weightBy,
                                                   this.defaultWeight,
                                                   this.degree, this.sample);
        }
    }

    private enum SortBy {
        INCR,
        DECR,
        NONE
    }
}
