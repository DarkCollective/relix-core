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
package com.darkcollective.relix.connectors.std;

import com.darkcollective.relix.processor.connector.Fetcher;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The production {@link Fetcher}: an HTTPS-only download over
 * {@link HttpClient}.
 *
 * <p>It lives with the connectors rather than beside the interface because it is
 * the only thing in the download path that needs an HTTP client, and the engine
 * that declares the seam must not carry one.
 */
public final class HttpFetcher {

    private HttpFetcher() {
    }

    /**
     * An HTTPS-only fetcher backed by {@link HttpClient} (follows redirects,
     * 30-second timeouts).  Rejects non-HTTPS URIs so a manifest cannot smuggle a
     * plaintext download.
     *
     * @return a production fetcher
     */
    public static Fetcher https() {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        return uri -> {
            String scheme = uri.getScheme();
            if (scheme == null || !scheme.equalsIgnoreCase("https")) {
                throw new IOException("refusing non-HTTPS driver download: " + uri);
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            try {
                HttpResponse<byte[]> response =
                        client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    throw new IOException("download failed (HTTP " + response.statusCode() + "): " + uri);
                }
                return response.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("download interrupted: " + uri, e);
            }
        };
    }
}
