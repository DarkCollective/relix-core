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
package com.darkcollective.relix.processor.internal;

import com.darkcollective.relix.processor.EvaluationException;
import com.darkcollective.relix.semantic.SchemaAnnotations;
import com.darkcollective.relix.symbol.table.internal.InMemorySymbolTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("QueryCancellation — one execution's cancel signal")
final class QueryCancellationTest {

    @Test
    @DisplayName("check passes until cancel, then raises")
    void check() {
        QueryCancellation cancellation = QueryCancellation.create();
        assertThatCode(cancellation::check).doesNotThrowAnyException();
        assertThat(cancellation.isCancelled()).isFalse();
        cancellation.cancel();
        assertThat(cancellation.isCancelled()).isTrue();
        assertThatThrownBy(cancellation::check)
                .isInstanceOf(EvaluationException.class)
                .hasMessage("query cancelled");
    }

    @Test
    @DisplayName("each cancel runs every action still registered")
    void runsActionsEachTime() {
        QueryCancellation cancellation = QueryCancellation.create();
        AtomicInteger ran = new AtomicInteger();
        QueryCancellation.Registration first = cancellation.onCancel(ran::incrementAndGet);
        cancellation.onCancel(ran::incrementAndGet);
        cancellation.cancel();
        assertThat(ran).hasValue(2);
        // A second request reaches work the first may have arrived too early for; work
        // that has finished, and closed its registration, is left alone.
        first.close();
        cancellation.cancel();
        assertThat(ran).hasValue(3);
    }

    @Test
    @DisplayName("an action whose registration was closed does not run")
    void closedRegistration() {
        QueryCancellation cancellation = QueryCancellation.create();
        AtomicInteger ran = new AtomicInteger();
        cancellation.onCancel(ran::incrementAndGet).close();
        cancellation.cancel();
        assertThat(ran).hasValue(0);
    }

    @Test
    @DisplayName("an action registered after cancel runs at once")
    void lateRegistration() {
        QueryCancellation cancellation = QueryCancellation.create();
        cancellation.cancel();
        AtomicInteger ran = new AtomicInteger();
        QueryCancellation.Registration registration = cancellation.onCancel(ran::incrementAndGet);
        assertThat(ran).as("ran at once").hasValue(1);
        cancellation.cancel();
        assertThat(ran).as("and stays registered for the next request").hasValue(2);
        registration.close();
        cancellation.cancel();
        assertThat(ran).hasValue(2);
    }

    @Test
    @DisplayName("an action that throws does not stop the others")
    void failingAction() {
        QueryCancellation cancellation = QueryCancellation.create();
        AtomicInteger ran = new AtomicInteger();
        cancellation.onCancel(() -> {
            throw new IllegalStateException("cannot cancel");
        });
        cancellation.onCancel(ran::incrementAndGet);
        assertThatCode(cancellation::cancel).doesNotThrowAnyException();
        assertThat(ran).hasValue(1);
    }

    @Test
    @DisplayName("NONE is never cancelled and runs nothing")
    void none() {
        AtomicInteger ran = new AtomicInteger();
        QueryCancellation.NONE.onCancel(ran::incrementAndGet).close();
        QueryCancellation.NONE.onCancel(ran::incrementAndGet);
        QueryCancellation.NONE.cancel();
        assertThat(QueryCancellation.NONE.isCancelled()).isFalse();
        assertThatCode(QueryCancellation.NONE::check).doesNotThrowAnyException();
        assertThat(ran).hasValue(0);
    }

    @Test
    @DisplayName("an execution context carries it, and none by default")
    void onTheContext() {
        ExecutionContext ctx = new ExecutionContext(
                new InMemorySymbolTable(), SchemaAnnotations.empty(), (n, s) -> null);
        assertThat(ctx.cancellation()).isSameAs(QueryCancellation.NONE);
        QueryCancellation cancellation = QueryCancellation.create();
        assertThat(ctx.withCancellation(cancellation).cancellation()).isSameAs(cancellation);
        assertThatThrownBy(() -> ctx.withCancellation(null)).isInstanceOf(NullPointerException.class);
    }
}
