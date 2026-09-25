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
package com.darkcollective.relix.embed;

import com.darkcollective.relix.ast.SourceLocation;
import com.darkcollective.relix.semantic.SemanticError;
import com.darkcollective.relix.semantic.Severity;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * One thing wrong with a query, as data rather than as an exception.
 *
 * <p>What {@code Relix.validate} returns. The rest of the API throws, which is right when
 * the author of the query is the author of the program; a caller building a query from
 * user input needs to render the problem instead, and cannot do that from a stack trace.
 *
 * <p>It carries a {@link Severity} because not everything the analyser reports is fatal.
 * A caller that treats every diagnostic as a refusal would stop on a warning, which is
 * the analyser saying "this is odd" rather than "this cannot run".
 *
 * @param message  what is wrong, in the analyser's own words
 * @param location where, when the analyser knew
 * @param severity whether this stops the query or merely remarks on it
 * @since 1.0
 */
public record Diagnostic(String message, Optional<SourceLocation> location, Severity severity) {

    /**
     * Validates the components.
     *
     * @since 1.0
     */
    public Diagnostic {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(severity, "severity");
    }

    /**
     * {@return whether this diagnostic stops the query from running}
     *
     * @since 1.0
     */
    public boolean isError() {
        return severity == Severity.ERROR;
    }

    /**
     * A diagnostic with no location — an error nothing could attribute to a position.
     * A syntax error is not normally one of those: see {@link #of(String, SourceLocation)}.
     *
     * @param message what is wrong
     * @return the diagnostic
     * @since 1.0
     */
    public static Diagnostic of(String message) {
        return new Diagnostic(message, Optional.empty(), Severity.ERROR);
    }

    /**
     * A diagnostic at a known position — a syntax error the frontend could place.
     *
     * @param message  what is wrong
     * @param location where; must not be null
     * @return the diagnostic
     * @since 1.0
     */
    public static Diagnostic of(String message, SourceLocation location) {
        return new Diagnostic(message, Optional.of(Objects.requireNonNull(location, "location")),
                Severity.ERROR);
    }

    /**
     * The diagnostic for a semantic error.
     *
     * @param error the analyser's error; must not be null
     * @return the diagnostic
     * @since 1.0
     */
    public static Diagnostic of(SemanticError error) {
        return new Diagnostic(error.message(),
                Optional.of(new SourceLocation(error.filePath(), error.line(), error.column())),
                error.severity());
    }

    /**
     * Renders a list of errors as one message, for the exception the throwing API raises.
     *
     * <p>An error placed in an imported file is prefixed with that file and position,
     * since the exception has no other way to say which file it is in; one in the
     * session's own text is not, a caller having written that text itself.
     *
     * @param errors the errors; must not be null
     * @return a single human-readable description
     */
    static String describe(List<SemanticError> errors) {
        if (errors.isEmpty()) {
            return "analysis produced no model";
        }
        return errors.stream().map(Diagnostic::describe)
                .collect(Collectors.joining("; "));
    }

    private static String describe(SemanticError error) {
        if (error.line() == 0 || error.filePath().equals(Relix.SESSION_PATH)) {
            return error.message();
        }
        return error.filePath() + ":" + error.line() + ":" + error.column() + ": "
                + error.message();
    }

    @Override
    public String toString() {
        String where = location.map(l -> l + ": ").orElse("");
        return where + severity.name().toLowerCase(java.util.Locale.ROOT) + ": " + message;
    }
}
