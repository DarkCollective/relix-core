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
package com.darkcollective.relix.processor.connector;

import com.darkcollective.relix.processor.connector.internal.DriverDownloader;
import java.io.IOException;
import java.net.URI;

/**
 * Fetches the bytes at a URI — the network seam for {@link DriverDownloader}.
 *
 * <p>Abstracting the fetch lets tests supply canned bytes without touching the
 * network. It also keeps the engine off the network entirely: this interface is
 * the whole of what the engine knows about downloading, and every implementation
 * of it — the production HTTPS one included — ships outside the engine, with the
 * connectors. A caller that wants to download supplies the fetcher; a caller that
 * does not, cannot.
 *
 * @see com.darkcollective.relix.processor.connector.ConnectorProvisioner
 */
@FunctionalInterface
public interface Fetcher {

    /**
     * @param uri the resource to fetch
     * @return the resource bytes
     * @throws IOException if the fetch fails or returns a non-200 status
     */
    byte[] fetch(URI uri) throws IOException;
}
