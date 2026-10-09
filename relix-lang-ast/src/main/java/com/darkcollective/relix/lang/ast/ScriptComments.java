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
package com.darkcollective.relix.lang.ast;

import com.darkcollective.relix.ast.Comment;

import java.util.List;
import java.util.Objects;

/**
 * The comments a parsed script carried, each placed by the code it was written next to,
 * so that {@link ScriptPrinter} can put it back there.
 *
 * <p>A comment is not part of what a script means, and no phase above the parser reads
 * one. These are kept only for printing: a formatter that dropped them could not be run
 * over someone's files. A script built rather than parsed has {@link #NONE}.
 *
 * @param namespace  the comments placed by the {@code namespace} declaration, as a
 *                   statement's are; {@link StatementComments#NONE} when it declares none
 * @param statements one entry per statement, in the order of
 *                   {@link Script#statements()}; empty when nothing is known about any
 * @param footer     the comments after the last statement, on lines of their own
 * @param footerSpaced whether a blank line separated the footer from the statements
 * @since 1.0
 */
public record ScriptComments(StatementComments namespace, List<StatementComments> statements,
                             List<Comment> footer, boolean footerSpaced) {

    /** No comments at all: what a script built from statements carries. */
    public static final ScriptComments NONE =
            new ScriptComments(StatementComments.NONE, List.of(), List.of(), false);

    /**
     * Checks and copies every component.
     *
     * @since 1.0
     */
    public ScriptComments {
        Objects.requireNonNull(namespace, "namespace");
        statements = List.copyOf(statements);
        footer = List.copyOf(footer);
    }

    /**
     * The comments placed by one statement.
     *
     * @param index the statement's position in {@link Script#statements()}
     * @return its comments, or {@link StatementComments#NONE} when none are recorded
     * @since 1.0
     */
    public StatementComments statement(int index) {
        return index < statements.size() ? statements.get(index) : StatementComments.NONE;
    }

    /**
     * The comments placed by one statement, and whether a blank line came before it.
     *
     * @param blankLineBefore whether a blank line separated this statement, with the
     *                        comments before it, from what came before
     * @param before          comments on the lines before the statement, after the one
     *                        before it
     * @param inside          comments between the statement's first token and its
     *                        {@code ;}, such as a comment inside its algebra
     * @param after           comments after its {@code ;}, on the same line
     * @param blankLineAfter  one entry per comment in {@code before}: whether a blank line
     *                        followed it, before the next comment or the statement; empty
     *                        when nothing is known, as for a script that was built
     * @since 1.0
     */
    public record StatementComments(boolean blankLineBefore, List<Comment> before,
                                    List<Comment> inside, List<Comment> after,
                                    List<Boolean> blankLineAfter) {

        /** No comments, and no blank line. */
        public static final StatementComments NONE =
                new StatementComments(false, List.of(), List.of(), List.of());

        /**
         * Checks and copies every component.
         *
         * @since 1.0
         */
        public StatementComments {
            before = List.copyOf(Objects.requireNonNull(before, "before"));
            inside = List.copyOf(Objects.requireNonNull(inside, "inside"));
            after = List.copyOf(Objects.requireNonNull(after, "after"));
            blankLineAfter = List.copyOf(Objects.requireNonNull(blankLineAfter, "blankLineAfter"));
            if (!blankLineAfter.isEmpty() && blankLineAfter.size() != before.size()) {
                throw new IllegalArgumentException(
                        "blankLineAfter must have one entry per comment before, or none");
            }
        }

        /**
         * The comments placed by one statement, with nothing known of the blank lines
         * among the comments before it.
         *
         * @param blankLineBefore whether a blank line came before the statement and its comments
         * @param before          comments on the lines before the statement
         * @param inside          comments between its first token and its {@code ;}
         * @param after           comments after its {@code ;}, on the same line
         * @since 1.0
         */
        public StatementComments(boolean blankLineBefore, List<Comment> before,
                                 List<Comment> inside, List<Comment> after) {
            this(blankLineBefore, before, inside, after, List.of());
        }

        /**
         * Whether a blank line followed one of the comments before the statement.
         *
         * @param index the comment's position in {@link #before()}
         * @return {@code true} when one did; {@code false} when not, or when nothing is known
         * @since 1.0
         */
        public boolean blankLineAfter(int index) {
            return index < blankLineAfter.size() && blankLineAfter.get(index);
        }
    }
}
