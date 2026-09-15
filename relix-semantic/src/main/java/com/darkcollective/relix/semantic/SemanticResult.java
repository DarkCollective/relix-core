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
package com.darkcollective.relix.semantic;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of a semantic analysis run.
 *
 * <p>A result always carries both a (possibly absent) model and a (possibly
 * empty) list of errors.  This allows callers to receive both a partially-valid
 * model and the errors that were discovered — useful for tooling that wants to
 * show diagnostics while still providing completion or navigation over the parts
 * of the script that analysed cleanly.
 *
 * <p>Three factory methods cover the common cases:
 * <pre>
 *   SemanticResult.success(model)              // no errors, full model
 *   SemanticResult.failure(errors)             // errors only, no model
 *   SemanticResult.partial(model, errors)      // errors + best-effort model
 * </pre>
 *
 * @param model  the semantic model, if analysis produced one (possibly partial)
 * @param errors all diagnostics collected; empty on full success
 */
public record SemanticResult(
        Optional<SemanticModel> model,
        List<SemanticError> errors
) {
    public SemanticResult {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(errors, "errors");
        errors = List.copyOf(errors);
    }

    /**
     * Returns {@code true} if any {@link Severity#ERROR}-level diagnostics were collected.
     */
    public boolean hasErrors() {
        return errors.stream().anyMatch(e -> e.severity() == Severity.ERROR);
    }

    /**
     * Returns {@code true} if any diagnostics were collected (errors or warnings).
     */
    public boolean hasDiagnostics() {
        return !errors.isEmpty();
    }

    /**
     * Returns {@code true} if a model is present.
     */
    public boolean hasModel() {
        return model.isPresent();
    }

    /**
     * Returns {@code true} if analysis completed with no diagnostics and a full model.
     */
    public boolean isFullyValid() {
        return !hasErrors() && hasModel();
    }

    /**
     * Creates a fully-successful result: model present, no diagnostics.
     *
     * @param model the completed semantic model; must not be null
     * @return a successful result
     */
    public static SemanticResult success(SemanticModel model) {
        Objects.requireNonNull(model, "model");
        return new SemanticResult(Optional.of(model), List.of());
    }

    /**
     * Creates a failure result: no model, one or more errors.
     *
     * @param errors the collected diagnostics; must not be null or empty
     * @return a failure result
     */
    public static SemanticResult failure(List<SemanticError> errors) {
        Objects.requireNonNull(errors, "errors");
        return new SemanticResult(Optional.empty(), errors);
    }

    /**
     * Creates a partial result: a best-effort model together with the errors
     * found during analysis.
     *
     * @param model  the partial semantic model; must not be null
     * @param errors the collected diagnostics; must not be null
     * @return a partial result
     */
    public static SemanticResult partial(SemanticModel model, List<SemanticError> errors) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(errors, "errors");
        return new SemanticResult(Optional.of(model), errors);
    }
}
