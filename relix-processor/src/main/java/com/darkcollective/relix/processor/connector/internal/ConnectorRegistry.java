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
package com.darkcollective.relix.processor.connector.internal;

import com.darkcollective.relix.processor.connector.RelixConnector;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * The open dispatch point for connectors — it replaces the hardcoded
 * {@code CompositeDataSourceConnector} switch.
 *
 * <p>The registry indexes every available {@link RelixConnector} by the type
 * tokens it {@link RelixConnector#handles() handles} and resolves a connector for
 * a token via {@link #forType(String)}.  Built-in connectors are discovered from
 * the module path with {@link ServiceLoader}; external connectors are loaded from
 * the connector directory by a {@link ConnectorPluginLoader}.
 *
 * <p>Token resolution is case-insensitive.  When two connectors claim the same
 * token, the first registered wins and a warning is logged — built-ins are
 * registered before external plugins, so a plugin cannot silently shadow a
 * built-in.
 */
public final class ConnectorRegistry implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ConnectorRegistry.class.getName());

    private final Map<String, RelixConnector> byType;
    private final List<RelixConnector> connectors;

    /**
     * The connectors this registry closes — the ones it discovered itself. A connector
     * handed in by a caller is the caller's, so it is indexed and dispatched to but never
     * closed here: this registry is built per query while such a connector outlives every
     * one of them, and the same rule already governs a bound {@code DataSource}.
     */
    private final List<RelixConnector> owned;

    /**
     * @param connectors the connectors to index, in priority order (earliest wins
     *                   on a token clash)
     */
    ConnectorRegistry(List<RelixConnector> connectors) {
        this(connectors, connectors);
    }

    /**
     * @param connectors the connectors to index, in priority order
     * @param owned      the subset whose lifecycle this registry owns
     */
    private ConnectorRegistry(List<RelixConnector> connectors, List<RelixConnector> owned) {
        this.connectors = List.copyOf(connectors);
        this.owned = List.copyOf(owned);
        this.byType = index(this.connectors);
    }

    /**
     * Builds a registry from the installed connector providers (module-path or
     * classpath {@link ServiceLoader}) plus external plugins from the default
     * connector directory ({@code $RELIX_CONNECTORS}, default
     * {@code ~/.relix/connectors/}).
     *
     * <p>The engine provides none of them itself: the standard CSV/JSON/HTTP/JDBC
     * set is a discovered provider on exactly the footing an out-of-tree plugin
     * has. A runtime with no provider installed yields an empty registry, and a
     * source declaration then reports an unknown connector type.
     *
     * @return a registry over all discovered connectors
     */
    public static ConnectorRegistry create() {
        return create(ConnectorPluginLoader.defaultDirectory());
    }

    /**
     * Builds a registry from the installed providers plus external plugins in the
     * given directory, rather than the default one.
     *
     * @param pluginDirectory the connector-plugin directory to scan
     * @return a registry over all discovered connectors
     */
    public static ConnectorRegistry create(Path pluginDirectory) {
        return createWith(List.of(), pluginDirectory);
    }

    /**
     * Builds a registry over connectors the caller supplies directly, ahead of the
     * discovered and plugin ones.
     *
     * <p>For a program that holds a connector instance rather than a service
     * declaration — an embedder wiring a backend of its own without a
     * {@code META-INF/services} entry, or a test standing one in. The SPI's argument is
     * that anything a shipped connector can do a third party can do; needing a service
     * declaration to exercise that inside one's own program would be a gap in the claim
     * rather than a feature of it.
     *
     * <p>Supplied connectors are indexed <strong>first</strong>, so one claiming a token
     * a discovered connector also claims wins it — the caller named this instance
     * explicitly, which is a stronger statement than a classpath scan.
     *
     * @param extra the caller's connectors, in priority order; must not be null
     * @return a registry over the supplied, discovered and plugin connectors
     */
    public static ConnectorRegistry createWith(List<RelixConnector> extra) {
        return createWith(extra, ConnectorPluginLoader.defaultDirectory());
    }

    /**
     * Builds a registry over caller-supplied connectors plus the installed providers and
     * the plugins in the given directory.
     *
     * @param extra           the caller's connectors, in priority order; must not be null
     * @param pluginDirectory the connector-plugin directory to scan
     * @return a registry over all three sources
     */
    public static ConnectorRegistry createWith(List<RelixConnector> extra, Path pluginDirectory) {
        Objects.requireNonNull(extra, "extra");
        // Supplied connectors first, then service providers (so they win token clashes
        // against plugin JARs). Dedupe by concrete class: a child URLClassLoader inherits
        // its parent's META-INF/services entries, so the plugin loader can re-surface a
        // built-in already discovered here — keep only the first instance of each class.
        Map<Class<?>, RelixConnector> unique = new LinkedHashMap<>();
        for (RelixConnector supplied : extra) {
            unique.putIfAbsent(Objects.requireNonNull(supplied, "connector").getClass(), supplied);
        }
        for (RelixConnector builtin : builtinConnectors()) {
            unique.putIfAbsent(builtin.getClass(), builtin);
        }
        for (RelixConnector plugin : new ConnectorPluginLoader(pluginDirectory).load()) {
            unique.putIfAbsent(plugin.getClass(), plugin);
        }
        List<RelixConnector> all = new ArrayList<>(unique.values());
        List<RelixConnector> discovered = all.stream()
                .filter(connector -> extra.stream().noneMatch(supplied -> supplied == connector))
                .toList();
        return new ConnectorRegistry(all, discovered);
    }

    /**
     * Resolves the connector for a declaration's type token (case-insensitive).
     *
     * @param token the type token (e.g. {@code "jdbc"}, {@code "csv"}, {@code "mongodb"})
     * @return the handling connector, or empty when no connector handles the token
     */
    public Optional<RelixConnector> forType(String token) {
        Objects.requireNonNull(token, "token");
        return Optional.ofNullable(byType.get(token.toLowerCase(Locale.ROOT)));
    }

    /**
     * @return the set of type tokens this registry can dispatch, lower-cased
     */
    public Set<String> types() {
        return Set.copyOf(byType.keySet());
    }

    private static Map<String, RelixConnector> index(List<RelixConnector> connectors) {
        Map<String, RelixConnector> map = new LinkedHashMap<>();
        for (RelixConnector connector : connectors) {
            for (String handle : connector.handles()) {
                String token = handle.toLowerCase(Locale.ROOT);
                RelixConnector existing = map.putIfAbsent(token, connector);
                if (existing != null) {
                    LOG.log(System.Logger.Level.WARNING,
                            "Two connectors handle type ''{0}''; keeping {1}, ignoring {2}",
                            token, existing.getClass().getName(), connector.getClass().getName());
                }
            }
        }
        return map;
    }

    /**
     * Closes every connector this registry discovered, suppressing individual failures.
     * A connector supplied by the caller is left open — see {@link #createWith(List)}.
     */
    @Override
    public void close() {
        for (RelixConnector connector : owned) {
            try {
                connector.close();
            } catch (Exception e) {
                LOG.log(System.Logger.Level.DEBUG, "closing connector failed", e);
            }
        }
    }

    /**
     * The built-in connectors, skipping any that cannot be instantiated.
     *
     * <p>The same guard {@link ConnectorPluginLoader} applies to out-of-tree plugin JARs.
     * Applying it only there was backwards: a connector with a broken classpath should
     * fail as itself and leave the others usable, and that is at least as true of a
     * connector shipped in the artifact as of one a user dropped into a directory.
     *
     * <p>Only {@code next()} is guarded, deliberately. {@link ServiceLoader}'s three
     * lookup iterators all park a provider's failure in a {@code nextError} field during
     * {@code hasNext()} and throw it from {@code next()}, so this catch sees every one of
     * them. Guarding {@code hasNext()} as well would be worse than redundant: {@code
     * next()} is what clears that field, so a loop that swallowed a {@code hasNext()}
     * failure and asked again would spin.
     */
    private static List<RelixConnector> builtinConnectors() {
        List<RelixConnector> found = new ArrayList<>();
        ServiceLoader<RelixConnector> services = ServiceLoader.load(RelixConnector.class);
        for (var it = services.iterator(); it.hasNext(); ) {
            try {
                found.add(it.next());
            } catch (ServiceConfigurationError unloadable) {
                LOG.log(System.Logger.Level.WARNING,
                        "skipping a built-in connector that could not be loaded; the "
                                + "connection types it handles will be unavailable",
                        unloadable);
            }
        }
        return found;
    }

}
