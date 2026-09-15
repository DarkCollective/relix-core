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
 * The pluggable connector SPI — the open replacement for
 * the closed, three-way CSV/JSON/JDBC connector dispatch.
 *
 * <p>A {@link com.darkcollective.relix.processor.connector.RelixConnector} bridges
 * one or more <em>type tokens</em> (the word after {@code from} in a
 * {@code connection} declaration) to the executor's
 * {@link com.darkcollective.relix.processor.Row} model, reading its backend-specific
 * settings from a type-agnostic
 * {@link com.darkcollective.relix.processor.connector.ConnectorConfig}.  Capabilities
 * (schema introspection, statistics, query pushdown) are opt-in via the optional
 * methods a connector overrides.
 *
 * <p>The {@link com.darkcollective.relix.processor.connector.ConnectorRegistry}
 * indexes connectors by token and dispatches to them: built-ins are discovered from
 * the module path via {@link java.util.ServiceLoader}, while external plugins are
 * loaded from {@code ~/.relix/connectors/} by a
 * {@link com.darkcollective.relix.processor.connector.ConnectorPluginLoader} — so a
 * new backend (e.g. MongoDB) ships as a JAR with no rebuild of relix.
 */
package com.darkcollective.relix.processor.connector;
