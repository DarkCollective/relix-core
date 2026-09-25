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
 * The connector provider interface, and connector plugin provisioning.
 *
 * <p>A {@link com.darkcollective.relix.processor.connector.RelixConnector} reads one kind
 * of external source, configured by a
 * {@link com.darkcollective.relix.processor.connector.ConnectorConfig}; connectors are
 * discovered with {@code ServiceLoader} or registered on a session.
 * {@link com.darkcollective.relix.processor.connector.ConnectorProvisioner} downloads a
 * plugin a host asks for, from the {@link com.darkcollective.relix.processor.connector.ConnectorCatalog},
 * through a {@link com.darkcollective.relix.processor.connector.Fetcher}.
 */
package com.darkcollective.relix.processor.connector;
