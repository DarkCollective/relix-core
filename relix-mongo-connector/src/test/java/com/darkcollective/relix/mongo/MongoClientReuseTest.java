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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code MongoClient} is not a connection — it <em>is</em> a connection pool, and the
 * driver's guidance is to keep one per cluster for the life of the application.
 *
 * <p>It used to be built per {@code open()} call and closed with the stream, so a join
 * across two collections built two pools and a lateral join over a Mongo TVF built one per
 * outer row. Nothing could see that: the rows were right either way, and an
 * open-counting test counts cursors rather than clients.
 */
@DisplayName("MongoDocumentSource — one client per URI, not one per read")
final class MongoClientReuseTest {

    /**
     * Counts clients built, so the reuse is observable without a server.
     *
     * <p>The client is a JDK dynamic proxy rather than a Mockito mock: Mockito 5 cannot
     * mock {@code MongoClient} on this toolchain (it fails to instrument
     * {@code MongoCluster}), and the proxy is enough — the subject is how many clients are
     * built and whether each is closed, not what any of them does.
     */
    private static final class CountingFactory implements java.util.function.Function<String, MongoClient> {
        final AtomicInteger built = new AtomicInteger();
        final List<AtomicInteger> closes = new ArrayList<>();

        @Override
        public MongoClient apply(String uri) {
            built.incrementAndGet();
            AtomicInteger closed = new AtomicInteger();
            closes.add(closed);
            return (MongoClient) Proxy.newProxyInstance(
                    MongoClient.class.getClassLoader(),
                    new Class<?>[] {MongoClient.class},
                    (proxy, method, args) -> {
                        if ("close".equals(method.getName())) {
                            closed.incrementAndGet();
                            return null;
                        }
                        // Anything else ends the read; the client was already obtained,
                        // which is all these tests are about.
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static ConnectorConfig config(String uri) {
        return new ConnectorConfig(Map.of("uri", uri, "database", "d"));
    }

    @Test
    @DisplayName("two reads against one URI share a client")
    void readsShareAClient() {
        CountingFactory factory = new CountingFactory();
        MongoDocumentSource source = new MongoDocumentSource(factory);

        // A read that fails inside the driver still had to obtain a client, which is all
        // this is measuring — the mock has no collections to hand back.
        attempt(() -> source.documents(config("mongodb://host/"), "a"));
        attempt(() -> source.documents(config("mongodb://host/"), "b"));

        assertThat(factory.built)
                .as("the second read must reuse the first read's pool")
                .hasValue(1);
    }

    @Test
    @DisplayName("a second URI gets its own client")
    void distinctUrisGetDistinctClients() {
        CountingFactory factory = new CountingFactory();
        MongoDocumentSource source = new MongoDocumentSource(factory);

        attempt(() -> source.documents(config("mongodb://one/"), "a"));
        attempt(() -> source.documents(config("mongodb://two/"), "a"));

        assertThat(factory.built).hasValue(2);
    }

    @Test
    @DisplayName("close releases every client it built")
    void closeReleasesTheClients() {
        CountingFactory factory = new CountingFactory();
        MongoDocumentSource source = new MongoDocumentSource(factory);
        attempt(() -> source.documents(config("mongodb://one/"), "a"));
        attempt(() -> source.documents(config("mongodb://two/"), "a"));

        source.close();

        assertThat(factory.closes).hasSize(2);
        assertThat(factory.closes).allSatisfy(closed ->
                assertThat(closed).as("every cached client must be released").hasValue(1));
    }

    @Test
    @DisplayName("the connector's own close reaches the document source")
    void connectorCloseDelegates() {
        boolean[] closed = {false};
        DocumentSource source = new DocumentSource() {
            @Override
            public java.util.stream.Stream<Map<String, Object>> documents(
                    ConnectorConfig config, String collection) {
                return java.util.stream.Stream.of();
            }

            @Override
            public void close() {
                closed[0] = true;
            }
        };

        new MongoConnector(source).close();

        assertThat(closed[0])
                .as("ConnectorRegistry.close() closes each connector; that has to reach "
                    + "the pool, or a session leaks one")
                .isTrue();
    }

    /** Runs {@code read}, ignoring whatever the mocked driver does after the client is built. */
    private static void attempt(Runnable read) {
        try {
            read.run();
        } catch (RuntimeException ignored) {
            // The client was obtained; what the mock does next is not the subject.
        }
    }
}
