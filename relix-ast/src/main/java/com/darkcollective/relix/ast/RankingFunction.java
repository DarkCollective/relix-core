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
 * The ranking window functions carried by a
 * {@link WindowFunction.RankingWindow}.
 *
 * <p>All ranking functions produce a {@code NUMBER} per row and operate over the
 * full sort order of the partition (a {@link WindowFrame.PartitionFrame}).  The
 * permit set is established in slice 2 so the exhaustive-switch enforcement guides
 * slice 3; only {@link WindowFunction.AggregateWindow} is executable in slice 2.
 */
public enum RankingFunction {
    /** Sequential 1-based position in the sort order (no ties). */
    ROW_NUMBER,
    /** Rank with gaps after ties (e.g. {@code 1, 1, 3}). */
    RANK,
    /** Rank without gaps after ties (e.g. {@code 1, 1, 2}). */
    DENSE_RANK,
    /** Relative rank {@code (rank − 1) / (rows − 1)} in {@code [0.0, 1.0]}. */
    PERCENT_RANK,
    /** Distributes rows into {@code n} buckets as evenly as possible. */
    NTILE
}
