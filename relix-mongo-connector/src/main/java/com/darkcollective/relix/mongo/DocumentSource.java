/*
 * Copyright 2026 Darkcollective, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.darkcollective.relix.mongo;

import com.darkcollective.relix.processor.connector.ConnectorConfig;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The seam between {@link MongoConnector}'s row-mapping logic and the live MongoDB
 * driver: given a connection config and a collection name, it yields the matching
 * documents as plain {@code Map<String,Object>}s.
 *
 * <p>Abstracting document retrieval keeps the connector's type-coercion logic
 * unit-testable with canned documents (no running MongoDB).  Production uses
 * {@link MongoDocumentSource}, which reads via {@code mongodb-driver-sync}; a
 * {@code org.bson.Document} is itself a {@code Map<String,Object>}.
 */
public interface DocumentSource {

    /**
     * Streams the documents of a collection.
     *
     * @param config     the connection configuration (e.g. {@code uri}, {@code database})
     * @param collection the collection name
     * @return a stream of documents; the caller must close it
     */
    Stream<Map<String, Object>> documents(ConnectorConfig config, String collection);

    /**
     * Runs an aggregation pipeline over a collection and streams the resulting
     * documents — the I/O leg of pushed σ/μ execution.  Each pipeline stage
     * is a plain map (e.g. {@code {"$match": {"age": {"$gt": 18}}}}); the default rejects
     * pushdown so a document source that only reads whole collections need not implement
     * it.
     *
     * @param config     the connection configuration (e.g. {@code uri}, {@code database})
     * @param collection the collection name
     * @param pipeline   the aggregation-pipeline stages, in order
     * @return a stream of result documents; the caller must close it
     */
    default Stream<Map<String, Object>> aggregate(
            ConnectorConfig config, String collection, List<Map<String, Object>> pipeline) {
        throw new UnsupportedOperationException("aggregation-pipeline pushdown is not supported");
    }

    /**
     * Releases anything the source holds between calls — a client and its connection pool.
     *
     * <p>Defaulted to nothing, because a source built over canned documents holds nothing;
     * the live one overrides it, and {@link MongoConnector#close()} is what calls it.
     */
    default void close() {
        // nothing held by default
    }
}
