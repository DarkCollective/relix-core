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
/**
 * A MongoDB {@link com.darkcollective.relix.processor.connector.RelixConnector} plugin.
 *
 * <p>{@link com.darkcollective.relix.mongo.MongoConnector} handles the {@code "mongodb"}
 * connection type token, mapping a collection's documents to relix rows against a
 * declared schema.  Document retrieval is abstracted behind
 * {@link com.darkcollective.relix.mongo.DocumentSource} (live implementation
 * {@link com.darkcollective.relix.mongo.MongoDocumentSource}) so the type-coercion logic
 * is testable without a running MongoDB.
 *
 * <p>The plugin is distributed as a self-contained JAR dropped into
 * {@code ~/.relix/connectors/}; it is discovered by relix's {@code ConnectorPluginLoader}
 * via {@link java.util.ServiceLoader}.
 */
package com.darkcollective.relix.mongo;
