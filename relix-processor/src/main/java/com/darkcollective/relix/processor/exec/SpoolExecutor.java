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
package com.darkcollective.relix.processor.exec;

import com.darkcollective.relix.plan.PhysicalNode;
import com.darkcollective.relix.processor.Row;

import java.util.stream.Stream;

/**
 * Executes {@link PhysicalNode.Spool} — the node that makes a physical plan a
 * directed acyclic graph, by having every consumer of one sub-plan read a single
 * evaluation of it.
 *
 * <p>Each consumer gets its own reader over the execution's {@link SpoolBuffer} for
 * that spool; the buffer is where the sharing, the row budget, and the stream
 * lifecycle live.
 */
final class SpoolExecutor {

    private final ChildDispatch dispatch;

    SpoolExecutor(ChildDispatch dispatch) {
        this.dispatch = dispatch;
    }

    Stream<Row> executeSpool(PhysicalNode.Spool node, EvalCtx ctx) {
        return ctx.spools().buffer(node.id(), node.input(), dispatch).view(ctx);
    }
}
