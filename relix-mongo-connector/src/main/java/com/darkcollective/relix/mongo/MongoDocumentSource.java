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
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCursor;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * The live {@link DocumentSource}: streams a collection's documents via
 * {@code mongodb-driver-sync}.  A {@link MongoClient} is opened per call and closed
 * when the returned stream is closed (the executor closes every source stream); the
 * BSON {@link Document} cursor is read lazily.
 *
 * <p>This is the thin I/O boundary — all type coercion lives in {@link MongoConnector},
 * which is unit-tested against a fake {@link DocumentSource}.
 *
 * <p><strong>The maps handed back are BSON {@link Document}s, not copies.</strong>
 * That is deliberate — {@code MongoConnector} coerces each one to a {@code Row}
 * immediately, so copying every document on a streaming path would buy nothing — but
 * it has one sharp edge worth knowing: {@code Document.equals} begins
 * {@code getClass() != o.getClass()}, so a Document is never equal to any other
 * {@code Map}, however identical its entries. A consumer that compares one to a plain
 * map gets {@code false} from two values that print the same. Compare entries, or
 * copy into a {@code LinkedHashMap} first.
 */
final class MongoDocumentSource implements DocumentSource {

    /** One client per URI, shared by every read against it. */
    private final ConcurrentMap<String, MongoClient> clients = new ConcurrentHashMap<>();

    /** How a client is built; injectable so a test can count what production cannot see. */
    private final Function<String, MongoClient> clientFactory;

    MongoDocumentSource() {
        this(MongoClients::create);
    }

    MongoDocumentSource(Function<String, MongoClient> clientFactory) {
        this.clientFactory = clientFactory;
    }

    @Override
    public Stream<Map<String, Object>> documents(ConnectorConfig config, String collection) {
        return run(config, client -> client.getDatabase(config.require("database"))
                .getCollection(collection)
                .find()
                .iterator());
    }

    @Override
    public Stream<Map<String, Object>> aggregate(
            ConnectorConfig config, String collection, List<Map<String, Object>> pipeline) {
        List<Bson> stages = pipeline.stream().<Bson>map(Document::new).toList();
        return run(config, client -> client.getDatabase(config.require("database"))
                .getCollection(collection)
                .aggregate(stages)
                .iterator());
    }

    /**
     * Obtains a cursor via {@code cursors} against the URI's shared client, and returns a
     * lazy stream that closes the <em>cursor</em> on {@link Stream#close()}.
     *
     * <p>The client is not closed here: it is shared with every other read against the same
     * URI, and closing it would break a concurrently-open cursor. {@link #close()} owns it.
     */
    private Stream<Map<String, Object>> run(
            ConnectorConfig config, Function<MongoClient, MongoCursor<Document>> cursors) {
        MongoClient client = clients.computeIfAbsent(config.require("uri"), clientFactory);
        MongoCursor<Document> cursor = cursors.apply(client);
        Stream<Document> documents = StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(cursor, Spliterator.ORDERED), false);
        return documents
                .<Map<String, Object>>map(d -> d)   // org.bson.Document is a Map<String,Object>
                .onClose(cursor::close);
    }

    /** Closes every cached client, suppressing individual failures. */
    @Override
    public void close() {
        clients.values().forEach(client -> {
            try {
                client.close();
            } catch (RuntimeException ignored) {
                // A client that cannot be closed is already unusable; the others still must be.
            }
        });
        clients.clear();
    }
}
