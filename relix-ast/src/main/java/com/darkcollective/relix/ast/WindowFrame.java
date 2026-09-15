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
package com.darkcollective.relix.ast;

/**
 * The scope of rows fed to a {@link WindowFunction} within a partition.
 *
 * <p>A frame is the window operator's analogue of SQL's
 * {@code ROWS BETWEEN … AND …} clause.  The sealed hierarchy has three permits,
 * each corresponding to one surface form (or the implicit full-partition frame
 * used by ranking and offset functions):
 *
 * <ul>
 *   <li>{@link BoundedFrame} — {@code OVER n ROWS}: the current row plus the
 *       preceding {@code n − 1} rows (a trailing {@code n}-row sliding window).</li>
 *   <li>{@link CumulativeFrame} — {@code OVER ALL ROWS}: the full prefix of the
 *       partition up to and including the current row (running total).</li>
 *   <li>{@link PartitionFrame} — the whole partition; never written in the
 *       surface syntax, set implicitly for ranking and offset functions.</li>
 * </ul>
 */
public sealed interface WindowFrame
        permits WindowFrame.BoundedFrame, WindowFrame.CumulativeFrame, WindowFrame.PartitionFrame {

    /**
     * A trailing {@code n}-row sliding window — {@code OVER n ROWS} — equivalent to
     * SQL's {@code ROWS BETWEEN n-1 PRECEDING AND CURRENT ROW}.  The per-partition
     * buffer never exceeds {@code n} rows, so a bounded frame is materialisation-safe
     * over an unbounded input.
     *
     * @param n the window width in rows; must be ≥ 1
     */
    record BoundedFrame(int n) implements WindowFrame {
        public BoundedFrame {
            if (n < 1) {
                throw new IllegalArgumentException("Window frame size must be at least 1, got " + n);
            }
        }
    }

    /**
     * The cumulative (running) frame — {@code OVER ALL ROWS} — equivalent to SQL's
     * {@code ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW}.  The buffer grows
     * with the partition, so this frame is blocking.
     */
    record CumulativeFrame() implements WindowFrame {}

    /**
     * The full-partition frame used implicitly by ranking and offset functions —
     * equivalent to SQL's {@code ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED
     * FOLLOWING}.  Never appears in the surface syntax.
     */
    record PartitionFrame() implements WindowFrame {}
}
