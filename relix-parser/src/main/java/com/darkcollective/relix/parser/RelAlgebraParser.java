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
package com.darkcollective.relix.parser;

import com.darkcollective.relix.ast.*;
import com.darkcollective.relix.ast.internal.*;

import java.io.IOException;
import java.io.InputStream;
import java.time.DateTimeException;
import java.util.ArrayDeque;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Recursive-descent parser for relational algebra expressions.
 *
 * <p>Each public {@code parse} method accepts an optional file path that is
 * embedded into every {@link SourceLocation} attached to parsed AST nodes.
 * When no file path is supplied, {@link SourceLocation#UNKNOWN} is used.
 */
public final class RelAlgebraParser {
    private final Lexer lexer;
    private final String filePath;
    private final List<Token> lookahead = new ArrayList<>();
    /** Every token parsed so far, for an error that quotes the expression back. */
    private final List<Token> consumed = new ArrayList<>();
    /** Whether the sort key {@link #parseSortSpecification} read last wrote ASC or DESC. */
    private boolean sortDirectionWritten;
    private Token current;
    /**
     * The parenthesis that opens the join condition being read, and where its join's
     * left input starts, so a relation name left inside it is reported as the right
     * input; null outside a parenthesised join condition.
     */
    private JoinConditionParen joinConditionParen;

    private record JoinConditionParen(Token open, int leftStart) {}

    /**
     * Stack of {@code FIX}-bound recursive relation names currently in lexical
     * scope (innermost on top). While parsing a {@code FIX} step, a bare relation
     * reference whose name is on this stack is resolved to a {@link RecursiveRefNode}
     * rather than a {@link RelationNode}; nested {@code FIX} shadowing falls out of
     * the push/pop discipline (see {@link #parseFixpoint()}).
     */
    private final Deque<String> recursiveBinders = new ArrayDeque<>();

    private record ProjectionOperands(List<ProjectedAttribute> operands, boolean hadComma) {
    }

    private RelAlgebraParser(String input) {
        this(new Lexer(input), "<unknown>");
    }

    private RelAlgebraParser(String input, String filePath, int startLine, int startColumn) {
        this(new Lexer(input, startLine, startColumn), filePath);
    }

    private RelAlgebraParser(String input, int startLine, int startColumn) {
        this(new Lexer(input, startLine, startColumn), "<unknown>");
    }

    private RelAlgebraParser(InputStream input) throws IOException {
        this(new Lexer(input), "<unknown>");
    }

    private RelAlgebraParser(InputStream input, int startLine, int startColumn) throws IOException {
        this(new Lexer(input, startLine, startColumn), "<unknown>");
    }

    private RelAlgebraParser(Lexer lexer, String filePath) {
        this.lexer = lexer;
        this.filePath = Objects.requireNonNull(filePath, "filePath");
        this.current = peek(0);
    }

    // =========================================================================
    // Public parse methods — no filePath (use "<unknown>")
    // =========================================================================

    /**
     * Parses a relational algebra expression from the given input string.
     *
     * @param input the relational algebra expression to parse
     * @return the parsed AST root node
     * @throws ParseException if the input contains syntax errors
     */
    public static RelNode parse(String input) {
        Objects.requireNonNull(input, "input");
        RelAlgebraParser parser = new RelAlgebraParser(input);
        RelNode result = parser.parseExpression(0);
        parser.expectEnd();
        return result;
    }

    /**
     * Parses a relational algebra expression from the given input string,
     * starting from the specified line and column position.
     * Useful when the parser is used by another parser that provides only
     * a portion of an input stream.
     *
     * @param input the relational algebra expression to parse
     * @param startLine the line number to start counting from (1-based)
     * @param startColumn the column number to start counting from (1-based)
     * @return the parsed AST root node
     * @throws ParseException if the input contains syntax errors
     * @throws IllegalArgumentException if startLine or startColumn is less than 1
     */
    public static RelNode parse(String input, int startLine, int startColumn) {
        Objects.requireNonNull(input, "input");
        if (startLine < 1) {
            throw new IllegalArgumentException("startLine must be >= 1");
        }
        if (startColumn < 1) {
            throw new IllegalArgumentException("startColumn must be >= 1");
        }
        RelAlgebraParser parser = new RelAlgebraParser(input, startLine, startColumn);
        RelNode result = parser.parseExpression(0);
        parser.expectEnd();
        return result;
    }

    /**
     * Parses a relational algebra expression from the given input string,
     * with a specific file path embedded in every node's source location.
     *
     * @param input       the relational algebra expression to parse
     * @param filePath    the file path to embed in source locations
     * @param startLine   the line number to start counting from (1-based)
     * @param startColumn the column number to start counting from (1-based)
     * @return the parsed AST root node
     * @throws ParseException           if the input contains syntax errors
     * @throws IllegalArgumentException if startLine or startColumn is less than 1
     */
    public static RelNode parse(String input, String filePath, int startLine, int startColumn) {
        return parse(input, filePath, startLine, startColumn, new ArrayList<Comment>()::add);
    }

    /**
     * Parses a relational algebra expression as {@link #parse(String, String, int, int)}
     * does, handing each comment it reads past to {@code comments}, in order — for a
     * caller that prints the expression back and wants its comments with it.
     *
     * @param input       the relational algebra expression to parse
     * @param filePath    the file path to embed in source locations, the comments' too
     * @param startLine   the line number to start counting from (1-based)
     * @param startColumn the column number to start counting from (1-based)
     * @param comments    receives each comment, once the whole expression has parsed
     * @return the parsed AST root node
     * @throws ParseException           if the input contains syntax errors
     * @throws IllegalArgumentException if startLine or startColumn is less than 1
     */
    public static RelNode parse(String input, String filePath, int startLine, int startColumn,
                                Consumer<Comment> comments) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(filePath, "filePath");
        Objects.requireNonNull(comments, "comments");
        if (startLine < 1) {
            throw new IllegalArgumentException("startLine must be >= 1");
        }
        if (startColumn < 1) {
            throw new IllegalArgumentException("startColumn must be >= 1");
        }
        RelAlgebraParser parser = new RelAlgebraParser(input, filePath, startLine, startColumn);
        RelNode result = parser.parseExpression(0);
        parser.expectEnd();
        parser.comments(comments);
        return result;
    }

    /** Hands {@code sink} each comment the lexer read past, with this parser's file path. */
    private void comments(Consumer<Comment> sink) {
        for (Comment comment : lexer.comments()) {
            SourceLocation at = comment.location();
            sink.accept(new Comment(comment.text(),
                    new SourceLocation(filePath, at.line(), at.column())));
        }
    }

    /**
     * Parses a scalar operand expression from the given input string, starting
     * from the specified source position.
     *
     * <p>Used by the scripting-language parser to parse the bodies of
     * {@code def} function declarations, which are operand expressions rather
     * than full relational-algebra expressions.
     *
     * @param input       the operand expression to parse
     * @param startLine   1-based source line (used for error reporting)
     * @param startColumn 1-based source column (used for error reporting)
     * @return the parsed operand
     * @throws ParseException if the input contains syntax errors
     */
    public static Operand parseOperand(String input, int startLine, int startColumn) {
        Objects.requireNonNull(input, "input");
        if (startLine < 1) {
            throw new IllegalArgumentException("startLine must be >= 1");
        }
        if (startColumn < 1) {
            throw new IllegalArgumentException("startColumn must be >= 1");
        }
        RelAlgebraParser parser = new RelAlgebraParser(input, startLine, startColumn);
        Operand result = parser.parseOperand();
        parser.expect(TokenType.EOF, "Expected end of operand expression");
        return result;
    }

    /**
     * Parses a scalar operand expression as {@link #parseOperand(String, int, int)}
     * does, embedding {@code filePath} in every node's source location and handing each
     * comment it reads past to {@code comments}, in order.
     *
     * @param input       the operand expression to parse
     * @param filePath    the file path to embed in source locations, the comments' too
     * @param startLine   1-based source line (used for error reporting)
     * @param startColumn 1-based source column (used for error reporting)
     * @param comments    receives each comment, once the whole operand has parsed
     * @return the parsed operand
     * @throws ParseException if the input contains syntax errors
     */
    public static Operand parseOperand(String input, String filePath, int startLine, int startColumn,
                                       Consumer<Comment> comments) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(filePath, "filePath");
        Objects.requireNonNull(comments, "comments");
        if (startLine < 1) {
            throw new IllegalArgumentException("startLine must be >= 1");
        }
        if (startColumn < 1) {
            throw new IllegalArgumentException("startColumn must be >= 1");
        }
        RelAlgebraParser parser = new RelAlgebraParser(input, filePath, startLine, startColumn);
        Operand result = parser.parseOperand();
        parser.expect(TokenType.EOF, "Expected end of operand expression");
        parser.comments(comments);
        return result;
    }

    /**
     * Parses a relational algebra expression from the given input stream.
     *
     * @param input the input stream containing the relational algebra expression
     * @return the parsed AST root node
     * @throws IOException if an error occurs reading from the input stream
     * @throws ParseException if the input contains syntax errors
     */
    public static RelNode parse(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        RelAlgebraParser parser = new RelAlgebraParser(input);
        RelNode result = parser.parseExpression(0);
        parser.expectEnd();
        return result;
    }

    /**
     * Parses a relational algebra expression from the given input stream,
     * starting from the specified line and column position.
     * Useful when the parser is used by another parser that provides only
     * a portion of an input stream.
     *
     * @param input the input stream containing the relational algebra expression
     * @param startLine the line number to start counting from (1-based)
     * @param startColumn the column number to start counting from (1-based)
     * @return the parsed AST root node
     * @throws IOException if an error occurs reading from the input stream
     * @throws ParseException if the input contains syntax errors
     * @throws IllegalArgumentException if startLine or startColumn is less than 1
     */
    public static RelNode parse(InputStream input, int startLine, int startColumn) throws IOException {
        Objects.requireNonNull(input, "input");
        if (startLine < 1) {
            throw new IllegalArgumentException("startLine must be >= 1");
        }
        if (startColumn < 1) {
            throw new IllegalArgumentException("startColumn must be >= 1");
        }
        RelAlgebraParser parser = new RelAlgebraParser(input, startLine, startColumn);
        RelNode result = parser.parseExpression(0);
        parser.expectEnd();
        return result;
    }

    // =========================================================================
    // Source location helper
    // =========================================================================

    /**
     * Creates a {@link SourceLocation} from the given token's position and this
     * parser's file path.
     *
     * @param tok the token whose position to use
     * @return the source location; never null
     */
    private SourceLocation loc(Token tok) {
        return new SourceLocation(filePath, tok.line(), tok.column());
    }

    // =========================================================================
    // Expression parsing
    // =========================================================================

    /**
     * How deeply one expression may nest before the parser refuses it.
     *
     * <p>This is recursive descent, so nesting depth is stack depth. A
     * {@link StackOverflowError} is not a {@link ParseException} — it is an {@code Error},
     * which {@code Relix.validate} does not catch, so it escaped the one method whose whole
     * purpose is to report a bad query rather than raise on one.
     *
     * <p>The limit therefore has to fire <em>before</em> the stack runs out, and how deep
     * that is depends on the stack the host gives the thread. Measured, on nested
     * parentheses — the cheapest construct, so the most levels per frame:
     *
     * <table border="1">
     * <caption>Depth at which parsing overflows, by thread stack size</caption>
     * <tr><th>Stack</th><th>Outcome</th></tr>
     * <tr><td>512 KB</td><td>overflows at 700</td></tr>
     * <tr><td>1 MB</td><td>overflows at 900</td></tr>
     * <tr><td>2 MB</td><td>reaches this limit; no overflow</td></tr>
     * <tr><td>8 MB</td><td>reaches this limit; no overflow</td></tr>
     * </table>
     *
     * <p>1 MB is the JVM's default thread stack on Linux and Windows, so a limit above
     * about 800 is not a limit at all on the most ordinary host there is — which is what a
     * thousand was, and it took a hosted CI runner to say so, every machine that had run
     * the suite until then giving the thread 2 MB.
     *
     * <p>250 clears the 512 KB measurement by better than a factor of two, on the
     * construct that nests most cheaply. It costs nothing worth having: 250 nested
     * operators is not a query anyone wrote, and an expression assembled by a program
     * does not come through this parser.
     */
    private static final int MAX_NESTING_DEPTH = 250;

    /** Current nesting depth; see {@link #MAX_NESTING_DEPTH}. */
    private int depth;

    /**
     * The {@code SELECT}/{@code σ} whose condition is being parsed, or {@code null}
     * outside one. Parsing a relation clears it, so a join's condition, or one in a
     * relation nested anywhere below, is never read as the selection's.
     */
    private Token selection;

    /** Where the condition of {@link #selection} starts in {@code consumed}. */
    private int selectionAt;

    /**
     * Each aggregate call read in operand position, where only GROUP takes one: its
     * first and past-last index in {@code consumed}, and its {@code *} argument's
     * index, or -1. An operator that read one says where it goes.
     */
    private final List<int[]> aggregateCalls = new ArrayList<>();

    /**
     * Whether {@code COUNT(*)} in operand position is read for now and reported once
     * its operator's input is, so the report can rewrite the operator: true while a
     * PROJECT's items or a SELECT's condition are read. Parsing a relation clears it.
     */
    private boolean deferAggregateStar;

    /** Where the GROUP whose aggregates are being read starts in {@code consumed}; -1 outside one. */
    private int groupAt = -1;

    /** The {@code *} an aggregate other than COUNT was given in that GROUP, reported after its input; or null. */
    private Token groupStar;

    private RelNode parseExpression(int minPrecedence) {
        if (++depth > MAX_NESTING_DEPTH) {
            throw error(current, "expression nests more than " + MAX_NESTING_DEPTH
                    + " levels deep");
        }
        Token enclosing = selection;
        boolean enclosingDefer = deferAggregateStar;
        int enclosingGroup = groupAt;
        selection = null;
        deferAggregateStar = false;
        groupAt = -1;
        try {
            return parseExpressionAtDepth(minPrecedence);
        } finally {
            depth--;
            selection = enclosing;
            deferAggregateStar = enclosingDefer;
            groupAt = enclosingGroup;
        }
    }

    private RelNode parseExpressionAtDepth(int minPrecedence) {
        int leftStart = consumed.size();
        RelNode left = parsePrefix();
        left = parseKleenePostfix(left);

        while (isBinaryRelOperator(current.type()) && precedence(current.type()) >= minPrecedence) {
            Token operator = current;
            int precedence = precedence(operator.type());
            int operatorAt = consumed.size();
            advance();
            if (isWord(operator, "CROSS") && isWord(current, "JOIN")) {
                rejectSqlJoin(operator, "×", leftStart, operatorAt);
            }

            Predicate condition = null;
            boolean asofInner = false;
            Optional<Operand> asofTolerance = Optional.empty();
            TieBreak asofTieBreak = TieBreak.LAST;
            AllenRelation ijoinRelation = null;
            String ijoinLeftStart = null, ijoinLeftEnd = null;
            String ijoinRightStart = null, ijoinRightEnd = null;

            // JOIN … USING (columns): written where a condition goes, A ⟕ USING (id) B,
            // or after the right input as SQL writes it, A LJOIN B USING (id).
            Optional<UsingJoinNode.Kind> usingKind = usingKind(operator.type());
            if (usingKind.isPresent() && (startsUsingList() || usingFollowsRightInput())) {
                List<String> columns = startsUsingList() ? parseUsingList() : null;
                RelNode right = parseExpression(precedence + 1);
                if (columns == null) {
                    if (!startsUsingList()) {
                        throw error(current, "Expected 'USING (' after the right input of '"
                                + operator.lexeme() + "'");
                    }
                    columns = parseUsingList();
                }
                left = new UsingJoinNode(usingKind.get(), left, right, columns, loc(operator));
                continue;
            }

            if (takesJoinCondition(operator.type()) && rightInputThenOn()) {
                int rightStart = consumed.size();
                parseExpression(precedence + 1);
                rejectOnClause(operator, leftStart, operatorAt, rightStart);
            }

            if (operator.type() == TokenType.THETA_JOIN ||
                operator.type() == TokenType.LEFT_OUTER_JOIN ||
                operator.type() == TokenType.RIGHT_OUTER_JOIN ||
                operator.type() == TokenType.FULL_OUTER_JOIN ||
                operator.type() == TokenType.SEMI_JOIN ||
                operator.type() == TokenType.ANTI_JOIN ||
                operator.type() == TokenType.UNIVERSAL_SEMI_JOIN) {
                requireJoinCondition(operator, left);
                rejectRightInputBeforeCondition(operator, leftStart, operatorAt);
                condition = parseJoinCondition(leftStart);
            } else if (operator.type() == TokenType.ASOF_JOIN) {
                // Optional INNER keyword switches from left-outer to inner mode.
                if (current.type() == TokenType.IDENTIFIER && "inner".equalsIgnoreCase(current.lexeme())) {
                    asofInner = true;
                    advance();
                }
                requireJoinCondition(operator, left);
                rejectRightInputBeforeCondition(operator, leftStart, operatorAt);
                condition = parseJoinCondition(leftStart);
                // Optional WITHIN <durExpr> tolerance clause.
                if (current.type() == TokenType.WITHIN) {
                    advance();
                    asofTolerance = Optional.of(parseOperand());
                }
                // Optional TIES(FIRST) tie-break clause.
                if (current.type() == TokenType.TIES) {
                    advance();
                    expect(TokenType.LPAREN, "Expected '(' after 'TIES'");
                    Token tieToken = expectName("Expected 'FIRST' or 'LAST' inside TIES(…)");
                    asofTieBreak = switch (tieToken.lexeme().toUpperCase(java.util.Locale.ROOT)) {
                        case "FIRST" -> TieBreak.FIRST;
                        case "LAST"  -> TieBreak.LAST;
                        default -> throw error(tieToken,
                                "Expected 'FIRST' or 'LAST' inside TIES(…), got '"
                                        + tieToken.lexeme() + "'");
                    };
                    expect(TokenType.RPAREN, "Expected ')' after tie-break rule in TIES(…)");
                }
            } else if (operator.type() == TokenType.IJOIN) {
                // Syntax: IJOIN <relation> (lStart, lEnd ; rStart, rEnd)
                // Parse the Allen relation name.
                Token relTok = expectName("Expected an Allen relation name after 'IJOIN' (e.g. INTERSECTS, OVERLAPS, DURING, CONTAINS, MEETS, PRECEDES)");
                try {
                    ijoinRelation = AllenRelation.valueOf(relTok.lexeme().toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw error(relTok, "Unknown Allen relation '" + relTok.lexeme()
                            + "'; expected one of INTERSECTS, OVERLAPS, OVERLAPPED_BY, DURING, CONTAINS, "
                            + "STARTS, STARTED_BY, FINISHES, FINISHED_BY, EQUALS, MEETS, MET_BY, PRECEDES, PRECEDED_BY");
                }
                // Parse the four endpoint column names as a comma-separated list:
                //   (lStart, lEnd, rStart, rEnd)
                // The first two belong to the left relation, the second two to the right.
                expect(TokenType.LPAREN, "Expected '(' after the Allen relation name in IJOIN");
                ijoinLeftStart  = expectQualifiedName("Expected left-start column name");
                expect(TokenType.COMMA, "Expected ',' after left-start column in IJOIN");
                ijoinLeftEnd    = expectQualifiedName("Expected left-end column name");
                expect(TokenType.COMMA, "Expected ',' after left-end column in IJOIN");
                ijoinRightStart = expectQualifiedName("Expected right-start column name");
                expect(TokenType.COMMA, "Expected ',' after right-start column in IJOIN");
                ijoinRightEnd   = expectQualifiedName("Expected right-end column name");
                expect(TokenType.RPAREN, "Expected ')' after the IJOIN column list");
            }

            // LATERAL is special: the right side must be a TVF call name(args…),
            // not a general relational expression, so we parse it directly.
            if (operator.type() == TokenType.LATERAL) {
                Token fnTok = current;
                String fnName = expectName("Expected table-valued function name after 'LATERAL'").lexeme();
                expect(TokenType.LPAREN, "Expected '(' after function name in LATERAL " + fnName);
                List<Operand> lateralArgs = new ArrayList<>();
                if (current.type() != TokenType.RPAREN) {
                    lateralArgs.add(parseOperand());
                    while (match(TokenType.COMMA)) {
                        lateralArgs.add(parseOperand());
                    }
                }
                expect(TokenType.RPAREN, "Expected ')' after LATERAL function arguments");
                left = new LateralJoinNode(left, fnName, lateralArgs, loc(operator));
                continue;
            }

            if (operator.type() == TokenType.NATURAL_JOIN) {
                rejectConditionAfterRightInput(operator, left);
            }
            if (condition != null) {
                rejectMissingRightInput(operator, leftStart, operatorAt);
            }
            int rightStart = consumed.size();
            RelNode right = parseExpression(precedence + 1);
            if ((operator.type() == TokenType.NATURAL_JOIN || operator.type() == TokenType.PRODUCT)
                    && isWord(current, "ON")) {
                rejectOnClause(operator, leftStart, operatorAt, rightStart);
            }
            // A ⋈ B USING (id): the natural join reads its right input first, then USING.
            if (operator.type() == TokenType.NATURAL_JOIN && startsUsingList()) {
                left = new UsingJoinNode(UsingJoinNode.Kind.INNER, left, right, parseUsingList(),
                        loc(operator));
                continue;
            }
            if (operator.type() == TokenType.NATURAL_JOIN && isComparison(current.type())) {
                // `A ⋈ A.x = B.y B`: the condition was read as the right input, and the
                // comparison after it is where parsing stops. Say what went wrong, at
                // the operator, rather than that the input should have ended there.
                throw ParseException.at("'" + operator.lexeme() + "' is the natural join: it joins"
                        + " on the columns both inputs share and takes no condition; for an"
                        + " explicit condition use ⨝ (><)", lexer.input(), operator);
            }
            left = switch (operator.type()) {
                case NATURAL_JOIN    -> new NaturalJoinNode(left, right, loc(operator));
                case THETA_JOIN      -> new ThetaJoinNode(left, right, condition, loc(operator));
                case LEFT_OUTER_JOIN -> new LeftOuterJoinNode(left, right, condition, loc(operator));
                case RIGHT_OUTER_JOIN -> new RightOuterJoinNode(left, right, condition, loc(operator));
                case FULL_OUTER_JOIN -> new FullOuterJoinNode(left, right, condition, loc(operator));
                case SEMI_JOIN            -> new SemiJoinNode(left, right, condition, loc(operator));
                case ANTI_JOIN            -> new AntiJoinNode(left, right, condition, loc(operator));
                case UNIVERSAL_SEMI_JOIN  -> new PairwiseUniversalNode(left, right, condition, loc(operator));
                case ASOF_JOIN            -> new AsOfJoinNode(left, right, condition,
                                                    asofTolerance, asofInner, asofTieBreak,
                                                    loc(operator));
                case IJOIN                -> new IntervalJoinNode(left, right, ijoinRelation,
                                                    ijoinLeftStart, ijoinLeftEnd,
                                                    ijoinRightStart, ijoinRightEnd,
                                                    loc(operator));
                case PRODUCT         -> new ProductNode(left, right, loc(operator));
                case UNION           -> new UnionNode(left, right, loc(operator));
                case UNION_ALL       -> new UnionAllNode(left, right, loc(operator));
                case OUTER_UNION     -> new OuterUnionNode(left, right, loc(operator));
                case DIFFERENCE      -> new DifferenceNode(left, right, loc(operator));
                case INTERSECTION    -> new IntersectionNode(left, right, loc(operator));
                case DIVISION        -> new DivisionNode(left, right, loc(operator));
                case SYMMETRIC_DIFFERENCE -> new SymmetricDifferenceNode(left, right, loc(operator));
                case COMPOSITION     -> new CompositionNode(left, right, loc(operator));
                default -> throw error(operator, "Unexpected relational operator");
            };
        }

        rejectSqlJoinKeywords(leftStart);
        return left;
    }

    /** The joins that take a condition between their inputs, as SQL's {@code ON} gives one. */
    private static boolean takesJoinCondition(TokenType type) {
        return switch (type) {
            case THETA_JOIN, LEFT_OUTER_JOIN, RIGHT_OUTER_JOIN, FULL_OUTER_JOIN, SEMI_JOIN,
                 ANTI_JOIN, UNIVERSAL_SEMI_JOIN, ASOF_JOIN -> true;
            default -> false;
        };
    }

    /**
     * Whether the tokens after a conditioned join are its right input followed by
     * {@code ON}, SQL's order: {@code A LJOIN B ON A.id = B.a_id}. The input is one name
     * or one parenthesised expression. Left alone, the name is read as the start of the
     * condition and parsing stops at {@code ON}, "expected comparison operator"; no
     * condition can be a single name or group followed by a word.
     */
    private boolean rightInputThenOn() {
        int next;
        if (current.type() == TokenType.LPAREN) {
            int depth = 0;
            next = 0;
            do {
                TokenType type = peek(next).type();
                if (type == TokenType.EOF) {
                    return false;
                }
                if (type == TokenType.LPAREN) {
                    depth++;
                } else if (type == TokenType.RPAREN) {
                    depth--;
                }
                next++;
            } while (depth > 0);
        } else if (current.type() == TokenType.IDENTIFIER) {
            next = 1;
        } else {
            return false;
        }
        return isWord(peek(next), "ON");
    }

    /**
     * Refuses SQL's {@code ON} after a join's right input, {@code A JOIN B ON cond}, with
     * the current token at {@code ON} and the join's inputs read from
     * {@code consumed[leftStart]}, the operator at {@code consumed[operatorAt]} and the
     * right input from {@code consumed[rightStart]} on. Relix writes the condition between
     * the inputs with no keyword, and the error rewrites the reader's own join that way,
     * with ⨝ (><) for a natural join or a product, which take no condition.
     */
    private void rejectOnClause(Token operator, int leftStart, int operatorAt, int rightStart) {
        Token on = current;
        String left = relationText(leftStart, operatorAt);
        String right = relationText(rightStart, consumed.size());
        String condition = conditionAfterOn();
        boolean conditioned = takesJoinCondition(operator.type());
        String join = conditioned ? operator.lexeme() : operator.lexeme().equals("⋈") ? "⨝" : "><";
        String problem = conditioned
                ? "'" + operator.lexeme() + "' takes its condition between the two relations,"
                        + " not after them with ON"
                : "'" + operator.lexeme() + "' takes no condition; to join on one, use " + join
                        + " with the condition between the two relations, not after them with ON";
        throw ParseException.at(problem + ": write " + left + " " + join + " " + condition
                + " " + right, lexer.input(), on);
    }

    /**
     * Refuses a conditioned join's right input written before its condition, SQL's
     * {@code ON} clause with the {@code ON} dropped: {@code A LJOIN B A.id = B.a_id}, at
     * {@code B}, with the condition moved before it. Left alone, {@code B} is read as the
     * start of the condition and parsing stops at the next name, "expected comparison
     * operator", describing a comparison that never began. No condition starts with two
     * names in a row unless the second is a word that follows an operand ({@code AT},
     * {@code BETWEEN}, …), so a name followed by a name that is itself compared, or
     * qualified, can only be the right input. A copy of the relation written again after
     * the condition is left out of the rewrite, which puts the relation there once.
     *
     * <p>SQL's {@code AND} or {@code WHERE} before the condition, {@code A >< B AND A.id =
     * B.a_id}, is the same mistake, and the rewrite drops the word. A name followed by
     * {@code AND} cannot begin a condition, whose first term must be compared.
     */
    private void rejectRightInputBeforeCondition(Token operator, int leftStart, int operatorAt) {
        boolean joined = current.type() == TokenType.IDENTIFIER
                && (peek(1).type() == TokenType.AND || isWord(peek(1), "WHERE"));
        if (!joined && (current.type() != TokenType.IDENTIFIER || peek(1).type() != TokenType.IDENTIFIER
                || List.of("AT", "BETWEEN", "MATCHES", "ESCAPE").stream().anyMatch(w -> isWord(peek(1), w)))) {
            return;
        }
        if (!joined && peek(2).type() != TokenType.DOT && !isComparison(peek(2).type())) {
            return;
        }
        Token right = current;
        String left = relationText(leftStart, operatorAt);
        advance();
        if (joined) {
            advance();
        }
        int start = consumed.size();
        String condition;
        try {
            parsePredicate();
            condition = writtenBetween(start, consumed.size());
        } catch (ParseException e) {
            condition = "condition";
        }
        throw ParseException.at("the right relation goes after the condition: write " + left + " "
                + operator.lexeme() + " " + condition + " " + right.lexeme(), lexer.input(), right);
    }

    /**
     * Refuses a conditioned join that ends after its condition, {@code A >< A.id = B.a_id}
     * followed by {@code )}, {@code }} or the end of the expression, at that
     * token: the right input was never written. Left alone, the parser asks for a
     * relation name where the join ended, without saying which join lacks one. When the
     * condition qualifies exactly one relation that the left input does not name, the
     * join is written out with it after the condition; otherwise with {@code B}.
     */
    private void rejectMissingRightInput(Token operator, int leftStart, int operatorAt) {
        if (current.type() != TokenType.RPAREN && current.type() != TokenType.RBRACE
                && current.type() != TokenType.EOF) {
            return;
        }
        Set<String> leftNames = new HashSet<>();
        for (Token token : consumed.subList(leftStart, operatorAt)) {
            if (isNameToken(token)) {
                leftNames.add(token.lexeme());
            }
        }
        Set<String> named = new LinkedHashSet<>();
        for (int i = operatorAt + 1; i < consumed.size(); i++) {
            // consumed[i] up to consumed[last] is a dotted name; all but its last part qualify it.
            int last = i;
            while (last + 3 <= consumed.size() && isColumnName(i, last + 3)) {
                last += 2;
            }
            if (last > i && !leftNames.contains(consumed.get(last - 2).lexeme())) {
                named.add(writtenBetween(i, last - 1));
            }
            i = last;
        }
        String join = writtenBetween(operatorAt, consumed.size());
        String right = named.size() == 1 ? named.iterator().next() : "B";
        String left = named.size() == 1 ? relationText(leftStart, operatorAt) : "A";
        throw ParseException.at("a join takes its right relation after the condition: write "
                + left + " " + (named.size() == 1 ? join : operator.lexeme() + " cond") + " " + right,
                lexer.input(), current);
    }

    /**
     * Reads the condition after the current {@code ON} and returns it as written, or
     * {@code condition} when none parses there.
     */
    private String conditionAfterOn() {
        advance();
        int start = consumed.size();
        try {
            parsePredicate();
        } catch (ParseException e) {
            return "condition";
        }
        return writtenBetween(start, consumed.size());
    }

    /** An input of a join as the reader wrote it, or {@code …} when it is long. */
    private String relationText(int start, int end) {
        String text = writtenBetween(start, end);
        return text.length() > 80 ? "…" : text;
    }

    /**
     * Refuses SQL's two-word joins after a relation: {@code A LEFT JOIN B ON cond},
     * {@code LEFT OUTER JOIN}, {@code RIGHT}, {@code FULL}, {@code INNER} and
     * {@code NATURAL}. The first word is read as nothing at all, and parsing stops there
     * with "expected end of input"; no relation is followed by a word and then
     * {@code JOIN}, so the words are SQL's whatever they are. ({@code CROSS} is Relix's
     * own word for ×, and its {@code CROSS JOIN} is caught where the operator is read.)
     */
    private void rejectSqlJoinKeywords(int leftStart) {
        String kind = current.lexeme().toUpperCase(Locale.ROOT);
        if (!isNameToken(current) || !List.of("LEFT", "RIGHT", "FULL", "INNER", "NATURAL").contains(kind)) {
            return;
        }
        int join = isWord(peek(1), "OUTER") ? 2 : 1;
        if (!isWord(peek(join), "JOIN")) {
            return;
        }
        Token first = current;
        int leftEnd = consumed.size();
        for (int i = 0; i < join; i++) {
            advance();
        }
        String relix = switch (kind) {
            case "LEFT" -> "LJOIN";
            case "RIGHT" -> "RJOIN";
            case "FULL" -> "FJOIN";
            default -> "JOIN";
        };
        rejectSqlJoin(first, relix, leftStart, leftEnd);
    }

    /**
     * Refuses a SQL join written in words, from {@code consumed[leftEnd]} through the
     * current {@code JOIN}, after a left input read from {@code consumed[leftStart]}: names
     * the Relix operator, {@code relix}, and rewrites the reader's own join with it,
     * reading the right input and the {@code ON} condition or {@code USING} list after it.
     * An inner or natural join with a condition is ⨝ (><); without one it is {@code JOIN}.
     */
    private void rejectSqlJoin(Token first, String relix, int leftStart, int leftEnd) {
        advance();
        String sql = writtenBetween(leftEnd, consumed.size());
        String left = relationText(leftStart, leftEnd);
        boolean conditioned = !relix.equals("JOIN") && !relix.equals("×");
        int rightStart = consumed.size();
        String right;
        try {
            parseExpression(precedence(TokenType.NATURAL_JOIN) + 1);
            right = relationText(rightStart, consumed.size());
        } catch (ParseException e) {
            right = null;
        }
        String spelling = "'" + sql + "' is SQL's spelling of ";
        String rewrite;
        if (right != null && isWord(current, "ON")) {
            String join = conditioned ? relix : "><";
            throw ParseException.at(spelling + join + ", which takes its condition between the"
                    + " two relations, not after them with ON: write " + left + " " + join + " "
                    + conditionAfterOn() + " " + right, lexer.input(), first);
        } else if (right != null && !relix.equals("×") && startsUsingList()) {
            int using = consumed.size();
            try {
                parseUsingList();
                rewrite = left + " " + relix + " " + right + " " + writtenBetween(using, consumed.size());
            } catch (ParseException e) {
                rewrite = left + " " + relix + " " + right + " USING (columns)";
            }
        } else if (right == null) {
            rewrite = left + " " + relix + (conditioned ? " condition" : "") + " B";
        } else if (conditioned) {
            rewrite = left + " " + relix + " condition " + right;
        } else if (relix.equals("JOIN")) {
            rewrite = left + " JOIN " + right + ", or " + left + " >< condition " + right;
        } else {
            rewrite = left + " × " + right;
        }
        throw ParseException.at(spelling + relix + ": write " + rewrite, lexer.input(), first);
    }

    /** The USING join a join operator writes, for the four that take {@code USING}. */
    private static Optional<UsingJoinNode.Kind> usingKind(TokenType operator) {
        return switch (operator) {
            case NATURAL_JOIN     -> Optional.of(UsingJoinNode.Kind.INNER);
            case LEFT_OUTER_JOIN  -> Optional.of(UsingJoinNode.Kind.LEFT);
            case RIGHT_OUTER_JOIN -> Optional.of(UsingJoinNode.Kind.RIGHT);
            case FULL_OUTER_JOIN  -> Optional.of(UsingJoinNode.Kind.FULL);
            default -> Optional.empty();
        };
    }

    /** Whether the current tokens open a USING column list, {@code USING (}. */
    private boolean startsUsingList() {
        return current.type() == TokenType.USING && peek(1).type() == TokenType.LPAREN;
    }

    /**
     * Whether a USING column list follows the right input of the join just read, which
     * is SQL's order: {@code A LJOIN B USING (id)}. The tokens are scanned at the
     * input's own nesting level up to the first that settles it — {@code USING (} says
     * yes, and a comparison or connective says the operator is followed by a condition
     * instead, as is the end of the expression.
     */
    private boolean usingFollowsRightInput() {
        // A right input that opens with a unary operator, σ x = 1 (B), holds comparisons
        // of its own, and is no condition: a condition never starts with an operator.
        boolean operatorInput = isPrefixOperator(current.type());
        int depth = 0;
        for (int i = 0; ; i++) {
            TokenType type = peek(i).type();
            if (type == TokenType.LPAREN) {
                depth++;
            } else if (type == TokenType.RPAREN) {
                depth--;
            } else if (depth == 0 && type == TokenType.USING && peek(i + 1).type() == TokenType.LPAREN) {
                return true;
            }
            if (type == TokenType.EOF || depth < 0
                    || depth == 0 && (isBinaryRelOperator(type) || !operatorInput && opensCondition(type))) {
                return false;
            }
        }
    }

    /** Whether a token at a join input's own level says it is a condition rather than a relation. */
    private static boolean opensCondition(TokenType type) {
        return isComparison(type) || isLogicalOperator(type) || switch (type) {
            case IS, LIKE, ELEMENT_OF, NOT_ELEMENT_OF -> true;
            default -> false;
        };
    }

    /** Parses {@code USING (a, b)}: one or more column names, none twice. */
    private List<String> parseUsingList() {
        expect(TokenType.USING, "Expected 'USING'");
        expect(TokenType.LPAREN, "Expected '(' after 'USING'");
        List<String> columns = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        do {
            Token name = expectName("Expected a column name in USING (…)");
            if (!seen.add(name.lexeme().toLowerCase(java.util.Locale.ROOT))) {
                throw error(name, "USING names column '" + name.lexeme() + "' twice");
            }
            columns.add(name.lexeme());
        } while (match(TokenType.COMMA));
        expect(TokenType.RPAREN, "Expected ')' after the USING columns");
        return columns;
    }

    /**
     * Handles the optional postfix Kleene-closure glyphs that may follow a
     * relation atom:
     * <ul>
     *   <li>{@code R⁺ OVER (from, to)} — transitive closure ({@code CLOSURE})</li>
     *   <li>{@code R* OVER (from, to)} — reflexive-transitive closure ({@code RCLOSURE})</li>
     * </ul>
     * If the current token is neither {@code ⁺} ({@link TokenType#KLEENE_PLUS}) nor
     * {@code *} ({@link TokenType#MULTIPLY}), the method returns {@code input} unchanged.
     * {@code *} is only valid as a Kleene star here — in relation position there is no
     * arithmetic multiplication, so no ambiguity arises.
     */
    private RelNode parseKleenePostfix(RelNode input) {
        if (current.type() != TokenType.KLEENE_PLUS && current.type() != TokenType.MULTIPLY) {
            return input;
        }
        boolean reflexive = current.type() == TokenType.MULTIPLY;
        Token glyph = current;
        advance();   // consume ⁺ or *
        expect(TokenType.OVER,
                "Expected 'OVER' after '" + glyph.lexeme() + "' in postfix closure");
        expect(TokenType.LPAREN,
                "Expected '(' after 'OVER' in postfix closure");
        Token from = expectName("Expected the 'from' column name inside OVER (from, to)");
        boolean undirected = parseEdgeSeparator(
                "Expected ',' or '↔' between the two closure columns in OVER (from, to)");
        Token to = expectName("Expected the 'to' column name inside OVER (from, to)");
        expect(TokenType.RPAREN,
                "Expected ')' to close OVER (from, to)");
        return new ClosureNode(input, from.lexeme(), to.lexeme(), undirected, reflexive,
                Optional.empty(), Optional.empty(), loc(glyph));
    }

    private RelNode parsePrefix() {
        return switch (current.type()) {
            case IDENTIFIER -> {
                Token startTok = current;
                advance();
                yield parseRelationFromNameToken(startTok);
            }
            case LPAREN -> {
                int start = consumed.size();
                advance();
                RelNode expression = parseExpression(0);
                expect(TokenType.RPAREN, "Expected ')' after relational expression");
                rejectTableAlias(relationText(start + 1, consumed.size() - 1), 0);
                yield expression;
            }
            case UNIT -> {
                Token tok = current;
                advance();
                yield TruthRelationNode.unit(loc(tok));
            }
            case EMPTY -> {
                Token tok = current;
                advance();
                yield TruthRelationNode.empty(loc(tok));
            }
            case PROJECT  -> parseProjection();
            case SELECT   -> parseSelection();
            case RENAME   -> parseRename();
            case AGGREGATION -> parseAggregation();
            case SORT, ORDER -> parseSort();
            case LIMIT    -> parseLimit();
            case DISTINCT -> parseDistinct();
            case UNNEST   -> parseUnnest();
            case CLOSURE  -> parseClosure(false);
            case RCLOSURE -> parseClosure(true);
            case CLUSTER  -> parseCluster();
            case PATH     -> parsePath();
            case TRACE    -> parseTrace();
            case FIX      -> parseFixpoint();
            case ITERATE  -> parseIterate();
            case FORALL   -> parseUniversal();
            case SAMPLE   -> parseSample();
            case SHUFFLE  -> parseShuffle();
            case ROLL     -> parseRoll();
            case SOLVE    -> parseSolve();
            case OPTIMIZE -> parseOptimize();
            case TOP      -> parseTopK();
            case COVER       -> parseCover();
            case DOWNSAMPLE  -> parseDownsample();
            case ROLLING     -> parseRolling();
            case WINDOW      -> parseWindow();
            case SESSIONIZE  -> parseSessionize();
            case UNPIVOT     -> parseUnpivot();
            case PIVOT       -> parsePivot();
            case TREE        -> parseTree();
            case WHY         -> parseWhy();
            default -> {
                rejectInfixOperatorFirst();
                // Allow any word-shaped keyword token as a relation name when there is no
                // dedicated operator case for it (e.g. a relation named 'count' or 'to').
                if (!isNameToken(current)) {
                    throw error(current, "Expected relation name, unary operator, or '('");
                }
                Token startTok = current;
                advance();
                yield parseRelationFromNameToken(startTok);
            }
        };
    }

    /** Consumes SQL's {@code WITH TIME ZONE} after {@code TIMESTAMP}, if it is there. */
    private boolean consumeWithTimeZone() {
        if (current.type() == TokenType.WITH && peek(1).type() == TokenType.TIME && isWord(peek(2), "ZONE")) {
            advance();
            advance();
            advance();
            return true;
        }
        return false;
    }

    /**
     * Whether a temporal keyword here starts a literal: its quoted string follows, or
     * {@code WITH}, for SQL's {@code TIMESTAMP WITH TIME ZONE '…'}.
     */
    private boolean startsTemporalLiteral() {
        return peek(1).type() == TokenType.STRING
                || current.type() == TokenType.TIMESTAMP && peek(1).type() == TokenType.WITH;
    }

    /**
     * Whether SQL's {@code INTERVAL '…'} or {@code INTERVAL n unit} starts here. A signed
     * one needs its unit, so that {@code interval - 1} still subtracts from a column.
     */
    private boolean startsIntervalLiteral() {
        return isWord(current, "INTERVAL") && (peek(1).type() == TokenType.STRING
                || peek(1).type() == TokenType.NUMBER
                || peek(1).type() == TokenType.MINUS && peek(2).type() == TokenType.NUMBER && isNameToken(peek(3)));
    }

    /**
     * Parses SQL's interval literal into a {@link DurationOperand}: PostgreSQL's
     * {@code INTERVAL '1 month'} or {@code INTERVAL 'P1M'}, the standard's
     * {@code INTERVAL '3' DAY}, and MySQL's {@code INTERVAL 1 MONTH}. The text is read
     * by {@link TemporalLiterals#parseInterval}. {@code INTERVAL} is not reserved.
     */
    private Operand parseIntervalLiteral() {
        Token keyword = current;
        advance(); // INTERVAL
        boolean negative = match(TokenType.MINUS);
        Token payload = current;
        advance();
        String text = payload.type() == TokenType.STRING ? payload.literal() : payload.lexeme();
        if (isNameToken(current) && (payload.type() == TokenType.NUMBER || isNumeral(text))) {
            text = text.strip() + " " + current.lexeme();
            advance();
        } else if (payload.type() == TokenType.NUMBER) {
            throw error(current, "Expected a unit after INTERVAL " + payload.lexeme()
                    + ", as in INTERVAL " + payload.lexeme() + " MONTH");
        }
        try {
            TemporalLiterals.Span span = TemporalLiterals.parseInterval(negative ? "-" + text : text);
            return new DurationOperand(span.months(), span.exact(), loc(keyword));
        } catch (DateTimeException e) {
            throw error(payload, "Malformed INTERVAL " + payload.lexeme() + ": write a number and a unit,"
                    + " as in INTERVAL '1 month' or INTERVAL '2 hours 30 minutes', or ISO-8601, 'P1M'");
        }
    }

    /** Whether {@code text} is a plain number, as the standard's {@code INTERVAL '3' DAY} quotes one. */
    private static boolean isNumeral(String text) {
        return text.strip().matches("[+-]?\\d+(\\.\\d+)?");
    }

    /** The words SQL writes after a relation, which end a table alias before them. */
    private static final List<String> SQL_AFTER_RELATION = List.of("ON", "LEFT", "RIGHT", "FULL",
            "INNER", "NATURAL", "OUTER", "CROSS", "WHERE");

    /** Words after an operand that go on with a condition, which a join's condition may hold. */
    private static final List<String> CONDITION_WORDS = List.of("AT", "BETWEEN", "MATCHES", "ESCAPE");

    /**
     * Refuses SQL's table alias after a relation, {@code orders o} or {@code orders AS o},
     * starting {@code offset} tokens ahead, at its first token, naming the RENAME that
     * gives the relation that name. Relix has no aliases, and left alone the name is
     * reported as wherever the parser expected the expression to go on, an end, a
     * {@code )} or a comparison, which never says what is wrong. An alias is one name,
     * not a word SQL writes after a relation, followed by what ends a relation: the end
     * of the expression, a {@code )}, a {@code ,}, an operator, or SQL's {@code ON},
     * {@code WHERE} or join words. Without {@code AS}, a name no shorter than the
     * relation's, {@code Users Orders}, is taken for an alias only where the expression
     * qualifies a column with it, as {@code o.id}: two relations side by side are a
     * missing operator or comma as likely as an alias.
     */
    private void rejectTableAlias(String relation, int offset) {
        Token first = peek(offset);
        int at;
        if (first.type() == TokenType.AS && isNameToken(peek(offset + 1))) {
            at = offset + 1;
        } else if (first.type() == TokenType.IDENTIFIER
                && Stream.concat(SQL_AFTER_RELATION.stream(), CONDITION_WORDS.stream()).noneMatch(w -> isWord(first, w))) {
            at = offset;
        } else {
            return;
        }
        Token after = peek(at + 1);
        boolean ends = switch (after.type()) {
            case EOF, RPAREN, RBRACE, COMMA -> true;
            default -> isBinaryRelOperator(after.type())
                    || SQL_AFTER_RELATION.stream().anyMatch(w -> isWord(after, w));
        };
        String alias = peek(at).lexeme();
        if (!ends || at == offset && alias.length() >= relation.length() && !qualifies(alias)) {
            return;
        }
        throw ParseException.at("Relix has no table aliases: to refer to " + relation + " as " + alias
                + ", write RENAME " + alias + " (" + relation + "). A self-join is"
                + " RENAME x (T) >< x.a = y.b RENAME y (T)", lexer.input(), first);
    }

    /** Whether {@code name} qualifies a column anywhere in the expression, as {@code name.column}. */
    private boolean qualifies(String name) {
        for (int i = 0; i + 1 < consumed.size(); i++) {
            if (consumed.get(i).lexeme().equals(name) && consumed.get(i + 1).type() == TokenType.DOT) {
                return true;
            }
        }
        try {
            for (int i = 0; peek(i).type() != TokenType.EOF; i++) {
                if (peek(i).lexeme().equals(name) && peek(i + 1).type() == TokenType.DOT) {
                    return true;
                }
            }
        } catch (ParseException e) {
            // A character that does not lex ends the search; its error comes in its turn.
        }
        return false;
    }

    /**
     * Shared logic for parsing a bare name token (IDENTIFIER or word-shaped keyword)
     * in relation position: handles dotted names, TVF calls, FIX recursive references,
     * and plain relation names.
     */
    private RelNode parseRelationFromNameToken(Token startTok) {
        String firstName = startTok.lexeme();
        StringBuilder sb = new StringBuilder(firstName);

        boolean dotted = false;
        while (match(TokenType.DOT)) {
            dotted = true;
            sb.append('.');
            Token next = expectName("Expected identifier after '.'");
            sb.append(next.lexeme());
        }

        if (!dotted && current.type() == TokenType.LPAREN && opensCall(startTok, current)
                && isAggregateName(firstName)) {
            rejectAggregateAsRelation(startTok);
        }

        // An identifier immediately followed by '(' in relation position is a
        // table-valued (relation-returning) function call, e.g. recentOrders(2).
        if (current.type() == TokenType.LPAREN) {
            return parseRelationFunctionCall(sb.toString(), startTok);
        }

        rejectTableAlias(sb.toString(), 0);

        // A bare identifier matching an in-scope FIX binder is a reference to
        // the recursive relation, resolved here at parse time (Decision 2).
        if (!dotted && recursiveBinders.contains(firstName)) {
            return new RecursiveRefNode(firstName, loc(startTok));
        }

        return new RelationNode(sb.toString(), loc(startTok));
    }

    /**
     * Parses the argument list of a table-valued function call {@code name(arg, …)},
     * with {@code current} positioned at the opening {@code (}.  Arguments are scalar
     * {@link Operand} expressions; an empty argument list is permitted.
     */
    private RelationFunctionCall parseRelationFunctionCall(String name, Token startTok) {
        expect(TokenType.LPAREN, "Expected '(' for function call arguments");
        List<Operand> args = new ArrayList<>();
        if (current.type() != TokenType.RPAREN) {
            args.add(parseOperand());
            while (match(TokenType.COMMA)) {
                args.add(parseOperand());
            }
        }
        expect(TokenType.RPAREN, "Expected ')' after function call arguments");
        return new RelationFunctionCall(name, args, loc(startTok));
    }

    private ProjectionNode parseProjection() {
        Token opTok = current;
        expect(TokenType.PROJECT, "Expected 'π'");
        if (current.type() == TokenType.DISTINCT && startsSqlDistinctColumns(1)) {
            rejectSqlDistinct(opTok.lexeme() + " takes no DISTINCT");
        }
        rejectEveryColumn(opTok);
        int itemsStart = consumed.size();
        boolean enclosingDefer = deferAggregateStar;
        deferAggregateStar = true;
        ProjectionOperands attributes;
        try {
            attributes = parseProjectionOperandList();
        } finally {
            deferAggregateStar = enclosingDefer;
        }
        int itemsEnd = consumed.size();

        if (attributes.hadComma() && current.type() == TokenType.EOF) {
            throw error(new Token(
                    current.type(),
                    current.lexeme(),
                    current.literal(),
                    current.line(),
                    current.column() + 1
            ), "Expected '(' after projection attribute list");
        }

        int inputStart = consumed.size();
        RelNode input = parseParenthesizedRelation("Expected '(' after projection attribute list");
        rejectCountStar(itemsStart, itemsEnd, inputStart, false, isGlyph(opTok));
        return new ProjectionNode(attributes.operands(), input, loc(opTok));
    }

    private SelectionNode parseSelection() {
        Token opTok = current;
        expect(TokenType.SELECT, "Expected 'σ'");
        if (current.type() == TokenType.DISTINCT && startsSqlDistinctColumns(1)) {
            rejectSqlDistinct(opTok.lexeme() + " filters rows");
        }
        rejectEveryColumn(opTok);
        Token enclosing = selection;
        int enclosingAt = selectionAt;
        boolean enclosingDefer = deferAggregateStar;
        selection = opTok;
        selectionAt = consumed.size();
        deferAggregateStar = true;
        int conditionStart = consumed.size();
        Predicate predicate;
        try {
            predicate = parsePredicate();
        } finally {
            selection = enclosing;
            selectionAt = enclosingAt;
            deferAggregateStar = enclosingDefer;
        }
        int conditionEnd = consumed.size();
        int inputStart = consumed.size();
        RelNode input = parseParenthesizedRelation("Expected '(' after selection predicate");
        rejectCountStar(conditionStart, conditionEnd, inputStart, true, isGlyph(opTok));
        return new SelectionNode(predicate, input, loc(opTok));
    }

    private RenameNode parseRename() {
        Token opTok = current;
        int opAt = consumed.size();
        expect(TokenType.RENAME, "Expected 'ρ'");

        // The new relation name is optional: `ρ (a → b) (R)` renames columns only.
        Optional<String> relationName = Optional.empty();
        if (isNameToken(current)) {
            relationName = Optional.of(expectName("Expected new relation name after 'ρ'").lexeme());
            if (current.type() == TokenType.ARROW) {
                rejectUnparenthesizedRename(opAt);
            }
        }

        List<String> attributes = List.of();
        List<RenameNode.RenamePair> pairs = List.of();

        if (startsRenameAttributeList()) {
            expect(TokenType.LPAREN, "Expected '(' before renamed attribute list");
            RenameList list = parseRenameList();
            attributes = list.attributes();
            pairs = list.pairs();
            expect(TokenType.RPAREN, "Expected ')' after renamed attribute list");
        } else if (relationName.isEmpty()) {
            throw error(current, "Expected a new relation name or a rename list after 'ρ'");
        }

        RelNode input = parseParenthesizedRelation("Expected '(' after rename target");
        return new RenameNode(relationName, attributes, pairs, input, loc(opTok));
    }

    /**
     * Refuses {@code RENAME a -> b (R)} at its arrow, with the pairs put in parentheses:
     * {@code RENAME (a -> b) (R)}. Left alone, {@code a} is read as the relation's new
     * name and the arrow is reported as where the input should start, which names
     * neither the parentheses nor the pairs.
     */
    private void rejectUnparenthesizedRename(int opAt) {
        Token arrow = current;
        String problem = "the columns to rename go in parentheses";
        List<String> attributes = new ArrayList<>();
        try {
            advance();
            expectName("Expected new column name after '→'");
            while (match(TokenType.COMMA)) {
                parseRenameListElement(attributes, new ArrayList<>());
            }
        } catch (ParseException e) {
            throw ParseException.at(problem, lexer.input(), arrow);
        }
        if (!attributes.isEmpty()) {
            // A bare name among the pairs: the list would not parse in parentheses either.
            throw ParseException.at(problem, lexer.input(), arrow);
        }
        String pairs = writtenFrom(opAt + 1);
        String input;
        try {
            int start = consumed.size();
            parseParenthesizedRelation("Expected '(' after rename target");
            input = writtenFrom(start);
        } catch (ParseException e) {
            input = "(…)";
        }
        throw ParseException.at(problem + ": write " + writtenBetween(opAt, opAt + 1) + " (" + pairs + ") "
                + input, lexer.input(), arrow);
    }

    /** The parsed column-list of a rename — exactly one of the two lists is populated. */
    private record RenameList(List<String> attributes, List<RenameNode.RenamePair> pairs) {}

    /**
     * Parses a rename column list: either bare positional names ({@code a, b, c}) or
     * {@code old → new} pairs ({@code a → x, b → y}). Mixing the two forms in one
     * list is a parse error.
     */
    private RenameList parseRenameList() {
        List<String> attributes = new ArrayList<>();
        List<RenameNode.RenamePair> pairs = new ArrayList<>();
        parseRenameListElement(attributes, pairs);
        while (match(TokenType.COMMA)) {
            parseRenameListElement(attributes, pairs);
        }
        if (!attributes.isEmpty() && !pairs.isEmpty()) {
            throw error(current,
                    "Rename ρ: cannot mix bare column names and 'old → new' pairs in one list");
        }
        return new RenameList(List.copyOf(attributes), List.copyOf(pairs));
    }

    private void parseRenameListElement(List<String> attributes, List<RenameNode.RenamePair> pairs) {
        Token name = expectName("Expected renamed attribute name");
        if (match(TokenType.ARROW)) {
            Token to = expectName("Expected new column name after '→'");
            pairs.add(new RenameNode.RenamePair(name.lexeme(), to.lexeme()));
        } else {
            attributes.add(name.lexeme());
        }
    }

    private static final String EXPECTED_AGGREGATE =
            "Expected aggregate function (SUM, AVG, COUNT, MIN, MAX, COLLECT, ARGMAX, or ARGMIN)";

    private AggregationNode parseAggregation() {
        Token opTok = current;
        int opAt = consumed.size();
        expect(TokenType.AGGREGATION, "Expected 'γ'");
        consumeOptionalBy();

        int keysStart = consumed.size();
        Grouping grouping = parseGrouping();
        if (current.type() == TokenType.LPAREN) {
            rejectParenthesizedAggregates(opAt);
            if (!grouping.keys().isEmpty() && grouping.sets().isEmpty()) {
                rejectKeysOnlyGroup(opTok, keysStart);
            }
        }
        int enclosingGroup = groupAt;
        Token enclosingStar = groupStar;
        groupAt = opAt;
        groupStar = null;
        List<AggregateFunction> aggregates;
        Token star;
        try {
            aggregates = parseAggregateFunctionList();
        } finally {
            star = groupStar;
            groupAt = enclosingGroup;
            groupStar = enclosingStar;
        }
        RelNode input = parseParenthesizedRelation("Expected '(' after aggregation expression");
        if (star != null) {
            rejectStarArgument(star, opAt);
        }

        return new AggregationNode(grouping.keys(), grouping.sets(), aggregates, input, loc(opTok));
    }

    /**
     * Refuses {@code GROUP k (COUNT(*) -> n) (R)} at its {@code (}, with the aggregates
     * moved into the key list: {@code GROUP k, COUNT(*) -> n (R)}. A {@code (} after the
     * keys never parsed, since the aggregate list cannot start with one, so this only
     * changes the message: before it named the aggregate functions, which the reader had
     * written, and not where they go. Anything other than an aggregate after the
     * {@code (} keeps that message.
     */
    private void rejectParenthesizedAggregates(int opAt) {
        Token open = current;
        int keysEnd = consumed.size();
        if (!startsAggregateAt(1)) {
            return;
        }
        String problem = "aggregates follow the grouping keys in the same list, without parentheses";
        String aggregates;
        String input;
        advance();
        try {
            int start = consumed.size();
            parseAggregateFunctionList();
            aggregates = writtenFrom(start);
            expect(TokenType.RPAREN, "Expected ')' after the aggregates");
        } catch (ParseException e) {
            throw ParseException.at(problem, lexer.input(), open);
        }
        try {
            int start = consumed.size();
            parseParenthesizedRelation("Expected '(' after aggregation expression");
            input = writtenFrom(start);
        } catch (ParseException e) {
            input = "(…)";
        }
        String keys = writtenBetween(opAt, keysEnd);
        // A comma before the aggregates unless there are no keys, or the reader wrote one.
        boolean listed = keysEnd - opAt > 1 && consumed.get(keysEnd - 1).type() != TokenType.COMMA;
        throw ParseException.at(problem + ": write " + keys + (listed ? ", " : " ") + aggregates + " " + input,
                lexer.input(), open);
    }

    /**
     * Refuses {@code GROUP a, b (R)}, keys with no aggregate, at its {@code (}, naming
     * the distinct rows of those keys: {@code DISTINCT (PROJECT a, b (R))}. SQL's
     * {@code GROUP BY} without aggregates means that, and before this the message asked
     * for an aggregate function and said nothing of DISTINCT. When what follows is not
     * one parenthesized input, that message stays.
     */
    private void rejectKeysOnlyGroup(Token opTok, int keysStart) {
        Token open = current;
        int keysEnd = consumed.size();
        if (consumed.get(keysEnd - 1).type() == TokenType.COMMA) {
            keysEnd--;
        }
        String keys = writtenBetween(keysStart, keysEnd);
        boolean glyph = isGlyph(opTok);
        String input;
        int start = consumed.size();
        try {
            parseParenthesizedRelation("Expected '(' after aggregation expression");
            input = writtenFrom(start);
        } catch (ParseException e) {
            input = null;
        }
        if (input == null || current.type() == TokenType.LPAREN) {
            // Not keys and an input, as in GROUP k (UPPER(name)) (R): the plain message.
            throw error(open, EXPECTED_AGGREGATE);
        }
        String grouped = groupedProject(opTok, keys, start + 1, consumed.size() - 1);
        if (grouped != null) {
            throw ParseException.at(grouped, lexer.input(), open);
        }
        throw ParseException.at(opTok.lexeme() + " needs an aggregate after its keys; for distinct rows write "
                + (glyph ? "δ (π " : "DISTINCT (PROJECT ") + keys + " " + input + ")", lexer.input(), open);
    }

    /**
     * The rewrite of SQL's {@code SELECT k, MAX(x) … GROUP BY k}, written as a GROUP over
     * a PROJECT that holds the aggregates, {@code consumed[from]} to {@code consumed[to]}:
     * the aggregates go in GROUP itself, after its keys. Null unless that range is one
     * PROJECT of items and a parenthesized input, with an aggregate among the items.
     *
     * <p>Without this, a trailing GROUP was rewritten into
     * {@code GROUP k (PROJECT k, MAX(x) -> m (R))}, which needs an aggregate after its
     * keys, and that message's rewrite still kept {@code MAX} in the PROJECT.
     */
    private String groupedProject(Token opTok, String keys, int from, int to) {
        if (to - from < 4 || consumed.get(from).type() != TokenType.PROJECT
                || consumed.get(to - 1).type() != TokenType.RPAREN) {
            return null;
        }
        int open = to - 1;
        for (int depth = 0; ; open--) {
            TokenType type = consumed.get(open).type();
            depth += type == TokenType.RPAREN ? 1 : type == TokenType.LPAREN ? -1 : 0;
            if (depth == 0) {
                break;
            }
        }
        List<String> aggregates = new ArrayList<>();
        int depth = 0;
        int itemStart = from + 1;
        boolean aggregate = false;
        for (int i = from + 1; i <= open; i++) {
            Token token = consumed.get(i);
            TokenType type = token.type();
            if (depth == 0 && (i == open || type == TokenType.COMMA)) {
                if (aggregate) {
                    aggregates.add(writtenBetween(itemStart, i));
                }
                itemStart = i + 1;
                aggregate = false;
                continue;
            }
            if (depth == 0 && isBinaryRelOperator(type)) {
                // More than one PROJECT's items: R ⋈ PROJECT … (S), say.
                return null;
            }
            depth += type == TokenType.LPAREN ? 1 : type == TokenType.RPAREN ? -1 : 0;
            Token next = consumed.get(i + 1);
            aggregate |= next.type() == TokenType.LPAREN && switch (type) {
                case SUM, AVG, COUNT, MIN, MAX, COLLECT, ARGMAX, ARGMIN -> true;
                default -> opensCall(token, next)
                        && NAMED_AGGREGATES.containsKey(token.lexeme().toUpperCase(java.util.Locale.ROOT));
            };
        }
        if (aggregates.isEmpty()) {
            return null;
        }
        String input = writtenBetween(open, to);
        String group = isGlyph(opTok) ? "γ" : "GROUP";
        return "In Relix the aggregates go in " + group + ", after its keys: write " + group + " " + keys
                + ", " + String.join(", ", aggregates) + " " + input;
    }

    /** Whether an aggregate call starts at {@code peek(n)}: a keyword aggregate, or a named one before its {@code (}. */
    private boolean startsAggregateAt(int n) {
        Token token = peek(n);
        return switch (token.type()) {
            case SUM, AVG, COUNT, MIN, MAX, COLLECT, ARGMAX, ARGMIN -> true;
            default -> peek(n + 1).type() == TokenType.LPAREN && opensCall(token, peek(n + 1))
                    && NAMED_AGGREGATES.containsKey(token.lexeme().toUpperCase(java.util.Locale.ROOT));
        };
    }

    private SortNode parseSort() {
        Token opTok = current;
        // SORT (τ) or its SQL-prior alias ORDER both start a sort.
        if (current.type() == TokenType.ORDER) {
            advance();
        } else {
            expect(TokenType.SORT, "Expected 'τ'");
        }
        consumeOptionalBy();
        List<SortSpecification> specs = parseSortSpecificationList();
        RelNode input = parseParenthesizedRelation("Expected '(' after sort specifications");
        return new SortNode(specs, input, loc(opTok));
    }

    /**
     * Consumes an optional {@code BY} noise word for the SQL-prior {@code ORDER BY} /
     * {@code GROUP BY} idioms. {@code BY} is only swallowed when it does
     * not immediately precede {@code (}, so a column literally named {@code by} in key
     * position (e.g. {@code ORDER by (R)}) still reads as the sort key.
     */
    private void consumeOptionalBy() {
        if (current.type() == TokenType.BY && peek(1).type() != TokenType.LPAREN) {
            advance();
        }
    }

    private List<SortSpecification> parseSortSpecificationList() {
        return parseSortSpecificationList(parseSortSpecification());
    }

    /** The sort-key list whose first key, {@code first}, has already been read. */
    private List<SortSpecification> parseSortSpecificationList(SortSpecification first) {
        List<SortSpecification> specs = new ArrayList<>();
        specs.add(first);

        while (match(TokenType.COMMA)) {
            specs.add(parseSortSpecification());
        }

        return List.copyOf(specs);
    }

    private SortSpecification parseSortSpecification() {
        // A sort key is a full scalar expression — a bare column (the common case),
        // or a computed value such as `to_timestamp(logged)`. A bare column
        // followed by the input relation's `(` is disambiguated from a function
        // call by the adjacency rule in parseOperandFromNameToken (a call requires
        // the `(` to abut the name), so `τ name (Users)` still reads `name`.
        Operand expression = parseOperand();
        SortDirection direction = SortDirection.ASC;

        sortDirectionWritten = true;
        if (current.type() == TokenType.ASC) {
            advance();
            direction = SortDirection.ASC;
        } else if (current.type() == TokenType.DESC) {
            advance();
            direction = SortDirection.DESC;
        } else {
            sortDirectionWritten = false;
        }

        // SQL's NULLS FIRST / NULLS LAST, matched by text so that none of the three
        // words becomes reserved. Without it a key puts its NULLs last.
        NullPlacement nulls = NullPlacement.NULLS_LAST;
        if (isWord(current, "NULLS") && (isWord(peek(1), "FIRST") || isWord(peek(1), "LAST"))) {
            advance();
            nulls = matchWord("FIRST") ? NullPlacement.NULLS_FIRST : NullPlacement.NULLS_LAST;
            if (nulls == NullPlacement.NULLS_LAST) {
                advance();
            }
        }

        return new SortSpecification(expression, direction, nulls);
    }

    private DistinctNode parseDistinct() {
        Token opTok = current;
        expect(TokenType.DISTINCT, "Expected 'δ'");
        String noParen = "Expected '(' after '" + opTok.lexeme() + "'";
        if (startsSqlDistinctColumns(0)) {
            // DISTINCT a, b (R): SQL's columns, where Relix takes a relation.
            Token first = current;
            String rewrite = distinctOf(opTok);
            throw ParseException.at(rewrite == null ? noParen
                    : opTok.lexeme() + " takes a relation, not columns: write " + rewrite, lexer.input(), first);
        }
        RelNode input = parseParenthesizedRelation(noParen);
        return new DistinctNode(input, loc(opTok));
    }

    /**
     * Whether SQL's columns of a distinct start at {@code peek(n)}: a name followed by
     * another, or a keyword-spelled name before a comma, or {@code *} before the input's
     * {@code (}. A keyword followed by anything else may be a column named
     * {@code distinct} in a condition or list, as in {@code SELECT distinct = 1 (R)}.
     */
    private boolean startsSqlDistinctColumns(int n) {
        Token token = peek(n);
        return token.type() == TokenType.IDENTIFIER
                || isNameToken(token) && peek(n + 1).type() == TokenType.COMMA
                || token.type() == TokenType.MULTIPLY && peek(n + 1).type() == TokenType.LPAREN;
    }

    /**
     * Refuses SQL's {@code SELECT DISTINCT a, b (R)} or {@code PROJECT DISTINCT a, b (R)}
     * at the {@code DISTINCT}, rewritten as Relix's {@code DISTINCT (PROJECT a, b (R))},
     * or {@code DISTINCT (R)} for {@code *}. Left alone, {@code SELECT} reads the columns
     * as a condition and {@code PROJECT} reads {@code DISTINCT} as a column.
     */
    private void rejectSqlDistinct(String problem) {
        Token distinct = current;
        advance();
        boolean everyColumn = current.type() == TokenType.MULTIPLY;
        String rewrite = distinctOf(distinct);
        throw ParseException.at(problem + "; for distinct rows "
                + (rewrite == null ? "write " + distinct.lexeme() + " (PROJECT … (…))"
                        : (everyColumn ? "write " : "of some columns write ") + rewrite),
                lexer.input(), distinct);
    }

    /**
     * Reads SQL's columns and input of a distinct, {@code a, b (R)} or {@code * (R)},
     * and returns them in Relix's form, {@code DISTINCT (PROJECT a, b (R))} or
     * {@code DISTINCT (R)}, with {@code distinct} spelled as the reader wrote it; null
     * when they do not parse.
     */
    private String distinctOf(Token distinct) {
        try {
            if (match(TokenType.MULTIPLY)) {
                int start = consumed.size();
                parseParenthesizedRelation("Expected '(' after '*'");
                return distinct.lexeme() + " " + writtenFrom(start);
            }
            int start = consumed.size();
            parseProjectionOperandList();
            String columns = writtenFrom(start);
            int from = consumed.size();
            parseParenthesizedRelation("Expected '(' after the columns");
            return distinct.lexeme() + " (" + (isGlyph(distinct) ? "π " : "PROJECT ") + columns + " "
                    + writtenFrom(from) + ")";
        } catch (ParseException e) {
            return null;
        }
    }

    /** Whether {@code token} is an operator's glyph, such as {@code δ}, rather than its keyword. */
    private static boolean isGlyph(Token token) {
        return token.lexeme().codePointAt(0) > 127;
    }

    /**
     * Parses a lineage-reification operator: {@code WHY (R)} (ASCII) or
     * {@code ω (R)} (glyph), per ADR-0018. The current token is the WHY keyword.
     */
    private WhyNode parseWhy() {
        Token opTok = current;
        expect(TokenType.WHY, "Expected 'ω'");
        RelNode input = parseParenthesizedRelation("Expected '(' after 'ω'");
        return new WhyNode(input, loc(opTok));
    }

    private UnnestNode parseUnnest() {
        Token opTok = current;
        expect(TokenType.UNNEST, "Expected 'μ'");
        Token attribute = expectName("Expected attribute name after 'μ'");
        // Optional `WITH ORDINALITY name` — appends a 1-based element-index column.
        Optional<String> ordinality = Optional.empty();
        if (match(TokenType.WITH)) {
            expect(TokenType.ORDINALITY, "Expected 'ORDINALITY' after 'WITH'");
            ordinality = Optional.of(expectName(
                    "Expected ordinality column name after 'WITH ORDINALITY'").lexeme());
        }
        RelNode input = parseParenthesizedRelation("Expected '(' after unnest attribute");
        return new UnnestNode(attribute.lexeme(), false, ordinality, input, loc(opTok));
    }


    /**
     * Consumes what stands between a graph operator's two endpoint columns, and says
     * whether the edge relation is to be read both ways.
     *
     * <p>A comma is a directed edge and {@code ↔} (ASCII {@code <->}) an undirected one.
     * One helper serves every graph operator, and the postfix Kleene form too, so the
     * four cannot drift about which separators they accept.
     *
     * @param message the diagnostic if neither separator is present
     * @return {@code true} for {@code ↔}, {@code false} for {@code ,}
     */
    private boolean parseEdgeSeparator(String message) {
        if (match(TokenType.UNDIRECTED_EDGE)) {
            return true;
        }
        expect(TokenType.COMMA, message);
        return false;
    }

    /**
     * Parses a transitive-closure operator:
     * {@code CLOSURE from, to (R)} (transitive) or {@code RCLOSURE from, to (R)}
     * (reflexive-transitive).  The current token is the CLOSURE/RCLOSURE keyword.
     */
    private ClosureNode parseClosure(boolean reflexive) {
        Token opTok = current;
        String keyword = reflexive ? "RCLOSURE" : "CLOSURE";
        advance();   // consume CLOSURE / RCLOSURE
        Token from = expectName("Expected the 'from' column after '" + keyword + "'");
        boolean undirected = parseEdgeSeparator(
                "Expected ',' or '↔' between the two closure columns: "
                + keyword + " from, to (R)");
        Token to = expectName("Expected the 'to' column after the separator in " + keyword);
        RelNode input = parseParenthesizedRelation(
                "Expected '(' after the closure columns");
        return new ClosureNode(input, from.lexeme(), to.lexeme(), undirected, reflexive,
                Optional.empty(), Optional.empty(), loc(opTok));
    }

    /**
     * Parses a connected-components operator:
     * {@code CLUSTER from, to AS label (R)}, e.g.
     * {@code CLUSTER src, dst AS island_id (NetworkTraffic)}.  The two edge-endpoint
     * columns are read as undirected edges; {@code label} names the appended
     * component-id column.  The current token is the CLUSTER keyword.
     */
    private ClusterNode parseCluster() {
        Token opTok = current;
        expect(TokenType.CLUSTER, "Expected 'CLUSTER'");
        Token from = expectName("Expected the 'from' column after 'CLUSTER'");
        expect(TokenType.COMMA,
                "Expected ',' between the two CLUSTER edge columns: CLUSTER from, to AS label (R)");
        Token to = expectName("Expected the 'to' column after ',' in CLUSTER");
        expect(TokenType.AS,
                "Expected 'AS' before the CLUSTER label column: CLUSTER from, to AS label (R)");
        Token label = expectName("Expected the component-label column name after 'AS' in CLUSTER");
        RelNode input = parseParenthesizedRelation(
                "Expected '(' after the CLUSTER label column");
        return new ClusterNode(input, from.lexeme(), to.lexeme(), label.lexeme(), loc(opTok));
    }

    /**
     * Parses a bounded variable-length path operator:
     * {@code PATH from, to HOPS m TO n AS depth (R)}, e.g.
     * {@code PATH src_account, dst_account HOPS 1 TO 3 AS depth (Transfers)}.  The two
     * edge-endpoint columns are followed by an inclusive hop window and the name of the
     * appended hop-distance column.  The single-bound form {@code HOPS n} is shorthand
     * for {@code HOPS 1 TO n}.  The current token is the PATH keyword.
     */
    private PathNode parsePath() {
        Token opTok = current;
        expect(TokenType.PATH, "Expected 'PATH'");
        Token from = expectName("Expected the 'from' column after 'PATH'");
        boolean undirected = parseEdgeSeparator(
                "Expected ',' or '↔' between the two PATH edge columns: "
                + "PATH from, to HOPS m TO n AS depth (R)");
        Token to = expectName("Expected the 'to' column after the separator in PATH");
        expect(TokenType.HOPS,
                "Expected 'HOPS' before the PATH hop window: PATH from, to HOPS m TO n AS depth (R)");
        Token firstTok = expect(TokenType.NUMBER,
                "Expected an integer hop count after 'HOPS'");
        int first = parseHopCount(firstTok);
        int min;
        int max;
        if (match(TokenType.TO)) {
            Token secondTok = expect(TokenType.NUMBER,
                    "Expected an integer upper hop bound after 'TO'");
            min = first;
            max = parseHopCount(secondTok);
        } else {
            min = 1;
            max = first;
        }
        if (min < 1) {
            throw error(firstTok, "'PATH' minimum hop count must be ≥ 1, got " + min);
        }
        if (max < min) {
            throw error(firstTok,
                    "'PATH' maximum hop count (" + max + ") must be ≥ minimum (" + min + ")");
        }
        expect(TokenType.AS,
                "Expected 'AS' before the PATH depth column: PATH from, to HOPS m TO n AS depth (R)");
        Token depth = expectName("Expected the hop-distance column name after 'AS' in PATH");
        RelNode input = parseParenthesizedRelation("Expected '(' after the PATH depth column");
        return new PathNode(input, from.lexeme(), to.lexeme(), undirected,
                min, max, depth.lexeme(), loc(opTok));
    }

    private int parseHopCount(Token tok) {
        try {
            return Integer.parseInt(tok.lexeme());
        } catch (NumberFormatException e) {
            throw error(tok, "'PATH' hop count must be an integer literal, got '"
                    + tok.lexeme() + "'");
        }
    }

    /**
     * Parses a gap-and-island / sessionization operator:
     * {@code SESSIONIZE <orderColumn> GAP <threshold> [PER <keys>] AS <sessionColumn> (R)},
     * e.g. {@code SESSIONIZE ts GAP DURATION 'PT30M' PER user_id AS session (Events)}.
     * Rows are ordered ascending by {@code orderColumn} within each partition and a
     * new session begins when the gap to the prior row exceeds {@code threshold}; the
     * appended {@code sessionColumn} carries a dense 1-based session id.  The current
     * token is the SESSIONIZE keyword.
     */
    private SessionizeNode parseSessionize() {
        Token opTok = current;
        expect(TokenType.SESSIONIZE, "Expected 'SESSIONIZE'");
        Token order = expectName("Expected the order column after 'SESSIONIZE'");
        expect(TokenType.GAP,
                "Expected 'GAP' after the SESSIONIZE order column: "
                + "SESSIONIZE col GAP threshold [PER keys] AS session (R)");
        Operand threshold = parseOperand();
        List<String> partitionKeys = parseOptionalPartition();
        expect(TokenType.AS,
                "Expected 'AS' before the SESSIONIZE session column: "
                + "SESSIONIZE col GAP threshold [PER keys] AS session (R)");
        Token session = expectName("Expected the session column name after 'AS' in SESSIONIZE");
        RelNode input = parseParenthesizedRelation(
                "Expected '(' after the SESSIONIZE session column");
        return new SessionizeNode(input, order.lexeme(), threshold,
                partitionKeys, session.lexeme(), loc(opTok));
    }

    /**
     * Parses an adjacency-to-forest nesting operator (ADR-0019):
     * {@code TREE <key> BY <parentKey> [ORDER <col> [ASC|DESC], ...] AS <childrenCol> (R)},
     * e.g. {@code TREE node_id BY parent_id ORDER ordinal AS children (relix.plan)}.
     * Folds the self-referential adjacency relation into a forest of nested
     * documents — one row per root, each carrying its whole subtree in the appended
     * {@code childrenCol} array.  The current token is the TREE keyword.
     */
    private TreeNode parseTree() {
        Token opTok = current;
        expect(TokenType.TREE, "Expected 'TREE'");
        Token key = expectName("Expected the key column after 'TREE'");
        expect(TokenType.BY,
                "Expected 'BY' after the TREE key column: "
                + "TREE key BY parentKey [ORDER cols] AS children (R)");
        Token parent = expectName("Expected the parent-key column after 'BY' in TREE");
        List<SortSpecification> orderSpecs = List.of();
        if (current.type() == TokenType.ORDER) {
            advance();
            orderSpecs = parseSortSpecificationList();
        }
        expect(TokenType.AS,
                "Expected 'AS' before the TREE children column: "
                + "TREE key BY parentKey [ORDER cols] AS children (R)");
        Token children = expectName("Expected the children column name after 'AS' in TREE");
        RelNode input = parseParenthesizedRelation(
                "Expected '(' after the TREE children column");
        return new TreeNode(input, key.lexeme(), parent.lexeme(),
                orderSpecs, children.lexeme(), loc(opTok));
    }

    /**
     * Parses a column-folding operator:
     * {@code UNPIVOT (col1, col2, ...) AS (nameCol, valueCol) (R)}, e.g.
     * {@code UNPIVOT (jan, feb, mar) AS (month, revenue) (MonthlySales)}.
     * The current token is the UNPIVOT keyword.
     */
    private UnpivotNode parseUnpivot() {
        Token opTok = current;
        expect(TokenType.UNPIVOT, "Expected 'UNPIVOT'");
        expect(TokenType.LPAREN,
                "Expected '(' after 'UNPIVOT': UNPIVOT (col1, col2, ...) AS (nameCol, valueCol) (R)");
        List<String> columns = new ArrayList<>();
        columns.add(expectName("Expected a column name in the UNPIVOT column list").lexeme());
        while (current.type() == TokenType.COMMA) {
            advance();
            columns.add(expectName("Expected a column name after ',' in UNPIVOT column list").lexeme());
        }
        expect(TokenType.RPAREN,
                "Expected ')' after the UNPIVOT column list");
        expect(TokenType.AS,
                "Expected 'AS' after the UNPIVOT column list: UNPIVOT (...) AS (nameCol, valueCol) (R)");
        expect(TokenType.LPAREN,
                "Expected '(' after 'AS' in UNPIVOT: UNPIVOT (...) AS (nameCol, valueCol) (R)");
        Token nameCol = expectName("Expected the name column after '(' in UNPIVOT AS clause");
        expect(TokenType.COMMA,
                "Expected ',' between nameCol and valueCol in UNPIVOT AS clause");
        Token valueCol = expectName("Expected the value column after ',' in UNPIVOT AS clause");
        expect(TokenType.RPAREN,
                "Expected ')' after the UNPIVOT AS clause");
        RelNode input = parseParenthesizedRelation(
                "Expected '(' after the UNPIVOT AS clause");
        return new UnpivotNode(columns, nameCol.lexeme(), valueCol.lexeme(), input, loc(opTok));
    }

    /**
     * Parses a row-pivoting operator:
     * {@code PIVOT valueCol BY keyCol [PER groupKey1, groupKey2, ...] (R)}, e.g.
     * {@code PIVOT revenue BY month PER region (MonthlySales)}.
     * The current token is the PIVOT keyword.
     */
    private PivotNode parsePivot() {
        Token opTok = current;
        expect(TokenType.PIVOT, "Expected 'PIVOT'");
        Token valueCol = expectName(
                "Expected the value column after 'PIVOT': PIVOT valueCol BY keyCol [PER keys] (R)");
        expect(TokenType.BY,
                "Expected 'BY' after the PIVOT value column: PIVOT valueCol BY keyCol [PER keys] (R)");
        Token keyCol = expectName(
                "Expected the key column after 'BY' in PIVOT: PIVOT valueCol BY keyCol [PER keys] (R)");
        List<String> groupKeys = parseOptionalPartition();
        RelNode input = parseParenthesizedRelation(
                "Expected '(' after the PIVOT key column");
        return new PivotNode(valueCol.lexeme(), keyCol.lexeme(), groupKeys, input, loc(opTok));
    }

    /**
     * Parses an optimal-path extraction operator:
     * {@code TRACE from, to VIA weight MINIMIZE|MAXIMIZE AS path (R)}, e.g.
     * {@code TRACE origin, dest VIA cost MINIMIZE AS route (Flights)}.
     * The current token is the TRACE keyword.
     */
    private TraceNode parseTrace() {
        Token opTok = current;
        expect(TokenType.TRACE, "Expected 'TRACE'");
        Token from = expectName("Expected the 'from' column after 'TRACE'");
        boolean undirected = parseEdgeSeparator(
                "Expected ',' or '↔' between the two TRACE endpoint columns: "
                + "TRACE from, to VIA weight MINIMIZE|MAXIMIZE AS path (R)");
        Token to = expectName("Expected the 'to' column after the separator in TRACE");
        expect(TokenType.VIA,
                "Expected 'VIA' after the 'to' column: "
                + "TRACE from, to VIA weight MINIMIZE|MAXIMIZE AS path (R)");
        Token weight = expectName("Expected the weight column after 'VIA' in TRACE");
        ObjectiveSense sense;
        if (current.type() == TokenType.MINIMIZE) {
            sense = ObjectiveSense.MINIMIZE;
            advance();
        } else if (current.type() == TokenType.MAXIMIZE) {
            sense = ObjectiveSense.MAXIMIZE;
            advance();
        } else {
            throw error(current,
                    "Expected 'MINIMIZE' or 'MAXIMIZE' after the weight column in TRACE");
        }
        expect(TokenType.AS,
                "Expected 'AS' before the TRACE path column: "
                + "TRACE from, to VIA weight MINIMIZE|MAXIMIZE AS path (R)");
        Token path = expectName("Expected the path column name after 'AS' in TRACE");
        RelNode input = parseParenthesizedRelation(
                "Expected '(' after the TRACE path column");
        return new TraceNode(input, from.lexeme(), to.lexeme(), undirected,
                             weight.lexeme(), sense, path.lexeme(),
                             Optional.empty(), Optional.empty(), loc(opTok));
    }

    /**
     * Parses the general-recursion fixpoint binder:
     * {@code FIX name ( base , step )}.  The current token is the {@code FIX}
     * keyword.  The recursive {@code name} is bound only within {@code step}: a
     * bare reference to it there parses to a {@link RecursiveRefNode} (it is
     * pushed onto {@link #recursiveBinders} for the duration of the step, so a
     * nested {@code FIX} of the same name shadows it and the binding pops on
     * exit), while in {@code base} the same identifier is an ordinary relation.
     */
    private FixpointNode parseFixpoint() {
        Token opTok = current;
        expect(TokenType.FIX, "Expected 'FIX'");
        Token nameTok = expect(TokenType.IDENTIFIER,
                "Expected the recursive relation name after 'FIX'");
        String name = nameTok.lexeme();

        expect(TokenType.LPAREN, "Expected '(' after the FIX relation name");

        // base: the recursive name is NOT in scope (lexical scope is the step only).
        RelNode base = parseExpression(0);

        expect(TokenType.COMMA,
                "Expected ',' between the base and step of FIX " + name + " (base, step)");

        // step: the recursive name IS in scope.
        recursiveBinders.push(name);
        RelNode step;
        try {
            step = parseExpression(0);
        } finally {
            recursiveBinders.pop();
        }

        expect(TokenType.RPAREN, "Expected ')' after the FIX step expression");
        return new FixpointNode(name, base, step, loc(opTok));
    }

    /**
     * Parses the replace-each-round iteration binder:
     * {@code ITERATE name ( base , step ) <stop>}, where {@code <stop>} is one of
     * {@code ROUNDS n}, {@code UNTIL STABLE MAX n ROUNDS} or
     * {@code UNTIL c, … WITHIN ε PER k, … MAX n ROUNDS}. The current token is the
     * {@code ITERATE} keyword. {@code name} is scoped exactly as for {@code FIX}: bound
     * in {@code step}, an ordinary relation name in {@code base}.
     *
     * <p>{@code ROUNDS}, {@code UNTIL} and {@code STABLE} are contextual words matched
     * by their text, as {@code ALL} is in {@code OVER ALL ROWS}, so none of them is
     * taken from the names a script may give its columns and relations.
     */
    private IterateNode parseIterate() {
        Token opTok = current;
        expect(TokenType.ITERATE, "Expected 'ITERATE'");
        Token nameTok = expect(TokenType.IDENTIFIER,
                "Expected the iterated relation name after 'ITERATE'");
        String name = nameTok.lexeme();

        expect(TokenType.LPAREN, "Expected '(' after the ITERATE relation name");
        RelNode base = parseExpression(0);
        expect(TokenType.COMMA,
                "Expected ',' between the base and step of ITERATE " + name + " (base, step)");
        recursiveBinders.push(name);
        RelNode step;
        try {
            step = parseExpression(0);
        } finally {
            recursiveBinders.pop();
        }
        expect(TokenType.RPAREN, "Expected ')' after the ITERATE step expression");
        return new IterateNode(name, base, step, parseIterateStop(), loc(opTok));
    }

    private IterateStop parseIterateStop() {
        if (matchWord("ROUNDS")) {
            return new IterateStop.Rounds(parseRoundCount(0, "ITERATE"));
        }
        if (!matchWord("UNTIL")) {
            throw error(current, "Expected 'ROUNDS n' or 'UNTIL …' after ITERATE (base, step)");
        }
        // Before WITHIN or a comma, STABLE is a column that happens to be called stable;
        // anywhere else it is the stable form, which then demands its cap.
        List<String> columns = new ArrayList<>();
        Token first = current;
        if (matchWord("STABLE")) {
            if (current.type() != TokenType.WITHIN && current.type() != TokenType.COMMA) {
                return new IterateStop.Stable(parseRoundCap());
            }
            columns.add(first.lexeme());
            while (match(TokenType.COMMA)) {
                columns.add(expectName("Expected a column name after ','").lexeme());
            }
        } else {
            columns = parseIterateNames("Expected a column name after 'UNTIL'");
        }
        expect(TokenType.WITHIN, "Expected 'WITHIN' after the UNTIL column(s)");
        Token tolTok = expect(TokenType.NUMBER, "Expected a tolerance after 'WITHIN'");
        java.math.BigDecimal tolerance = new java.math.BigDecimal(tolTok.lexeme());
        expect(TokenType.PER, "Expected 'PER' and the key column(s) after the WITHIN tolerance");
        List<String> keys = parseIterateNames("Expected a key column name after 'PER'");
        return new IterateStop.Converged(columns, tolerance, keys, parseRoundCap());
    }

    /** {@code MAX n ROUNDS}, mandatory after both {@code UNTIL} forms. */
    private int parseRoundCap() {
        expect(TokenType.MAX,
                "Expected 'MAX n ROUNDS' — an ITERATE … UNTIL needs a round cap");
        int n = parseRoundCount(1, "ITERATE");
        if (!matchWord("ROUNDS")) {
            throw error(current, "Expected 'ROUNDS' after 'MAX " + n + "'");
        }
        return n;
    }

    private int parseRoundCount(int least, String operator) {
        Token tok = expect(TokenType.NUMBER, "Expected a round count");
        String subject = ("AEIOU".indexOf(operator.charAt(0)) >= 0 ? "An " : "A ") + operator;
        int n;
        try {
            n = Integer.parseInt(tok.lexeme());
        } catch (NumberFormatException e) {
            throw error(tok, subject + " round count must be a whole number, got '"
                    + tok.lexeme() + "'");
        }
        if (n < least) {
            throw error(tok, subject + " round count must be at least " + least + ", got " + n);
        }
        return n;
    }

    private List<String> parseIterateNames(String message) {
        List<String> names = new ArrayList<>();
        names.add(expectName(message).lexeme());
        while (match(TokenType.COMMA)) {
            names.add(expectName(message).lexeme());
        }
        return names;
    }

    /** Consumes the current token if it is the contextual word {@code word}, any case. */
    private boolean matchWord(String word) {
        if (isNameToken(current) && current.lexeme().equalsIgnoreCase(word)) {
            advance();
            return true;
        }
        return false;
    }

    /**
     * Parses a group-wise universal-quantification operator:
     * {@code ∀ key1, key2 : predicate (R)} (ASCII: {@code FORALL …}).  The current
     * token is the FORALL keyword.  At least one grouping key is required; the
     * empty-key form is accepted syntactically and rejected during semantic
     * analysis (it would need an empty-schema result).
     */
    private UniversalNode parseUniversal() {
        Token opTok = current;
        expect(TokenType.FORALL, "Expected '∀'");

        List<String> keys = new ArrayList<>();
        if (isNameToken(current)) {
            keys.add(current.lexeme());
            advance();
            while (match(TokenType.COMMA)) {
                keys.add(expectName("Expected grouping key after ',' in ∀").lexeme());
            }
        }
        expect(TokenType.COLON,
                "Expected ':' between the ∀ grouping keys and the predicate");
        Predicate predicate = parsePredicate();
        RelNode input = parseParenthesizedRelation("Expected '(' after the ∀ predicate");
        return new UniversalNode(List.copyOf(keys), predicate, input, loc(opTok));
    }

    /**
     * Parses a sampling operator. Two forms share the {@code SAMPLE} keyword and
     * are distinguished by the {@code ROWS} keyword after the numeric literal:
     * <ul>
     *   <li>{@code SAMPLE 0.1 (R)} — Bernoulli sampling: keep each row with the
     *       given probability (validated to {@code [0, 1]} during semantic
     *       analysis) → {@link SampleNode}.</li>
     *   <li>{@code SAMPLE 100 ROWS (R)} — reservoir sampling: keep exactly that
     *       many rows chosen uniformly at random → {@link ReservoirSampleNode}.
     *       The count must be a non-negative integer literal.</li>
     * </ul>
     * The current token is the SAMPLE keyword.
     */
    private RelNode parseSample() {
        Token opTok = current;
        expect(TokenType.SAMPLE, "Expected 'SAMPLE'");
        Token number = expect(TokenType.NUMBER,
                "Expected a sampling probability or row count after 'SAMPLE'");
        if (match(TokenType.ROWS)) {
            long count;
            try {
                count = Long.parseLong(number.lexeme());
            } catch (NumberFormatException e) {
                throw error(number, "'SAMPLE … ROWS' requires an integer row count");
            }
            java.util.Optional<Long> seed = parseSeedClause();
            RelNode input = parseParenthesizedRelation("Expected '(' after 'ROWS'" +
                    (seed.isPresent() ? " (or after 'SEED <n>')" : ""));
            return new ReservoirSampleNode(count, seed, input, loc(opTok));
        }
        double probability = Double.parseDouble(number.lexeme());
        java.util.Optional<Long> seed = parseSeedClause();
        RelNode input = parseParenthesizedRelation("Expected '(' after the sampling probability" +
                (seed.isPresent() ? " (or after 'SEED <n>')" : ""));
        return new SampleNode(probability, seed, input, loc(opTok));
    }

    /**
     * Parses a random-permutation operator: {@code SHUFFLE [SEED <integer>] (R)}.
     * Keyword-only (no glyph), sharing the {@code SEED} clause with the sampling
     * operators. The current token is the {@code SHUFFLE} keyword.
     */
    private RelNode parseShuffle() {
        Token opTok = current;
        expect(TokenType.SHUFFLE, "Expected 'SHUFFLE'");
        java.util.Optional<Long> seed = parseSeedClause();
        RelNode input = parseParenthesizedRelation("Expected '(' after 'SHUFFLE'" +
                (seed.isPresent() ? " (or after 'SEED <n>')" : ""));
        return new ShuffleNode(seed, input, loc(opTok));
    }

    /**
     * Parses an endless-draw operator:
     * {@code ROLL [BY <weightExpr>] [SEED <integer>] (R)} — a die roll over the faces
     * {@code R}, uniform by default or weighted by the {@code BY} expression. Keyword-only
     * (no glyph), sharing the {@code SEED} clause with the sampling operators. The current
     * token is the {@code ROLL} keyword.
     *
     * <p>The weight is a full scalar expression over the face columns. A bare column
     * weight followed by the face relation's {@code (} is disambiguated from a function
     * call by the adjacency rule — a call requires the {@code (} to abut the name — so
     * {@code ROLL BY weight (Loot)} reads {@code weight} as the key, exactly as
     * {@code τ name (Users)} does.
     */
    private RelNode parseRoll() {
        Token opTok = current;
        expect(TokenType.ROLL, "Expected 'ROLL'");
        java.util.Optional<Operand> weight = java.util.Optional.empty();
        if (match(TokenType.BY)) {
            weight = java.util.Optional.of(parseOperand());
        }
        java.util.Optional<Long> seed = parseSeedClause();
        RelNode input = parseParenthesizedRelation("Expected '(' after 'ROLL'" +
                (weight.isPresent() || seed.isPresent()
                        ? " (or after its 'BY'/'SEED' clause)" : ""));
        return new RollNode(seed, weight, input, loc(opTok));
    }

    /**
     * Parses an optional {@code SEED <integer>} clause, consuming both tokens when
     * present.  Returns the seed value, or {@link java.util.Optional#empty()} when
     * the next token is not {@code SEED}.
     */
    private java.util.Optional<Long> parseSeedClause() {
        if (!match(TokenType.SEED)) {
            return java.util.Optional.empty();
        }
        Token seedTok = expect(TokenType.NUMBER, "Expected an integer seed value after 'SEED'");
        long seedValue;
        try {
            seedValue = Long.parseLong(seedTok.lexeme());
        } catch (NumberFormatException e) {
            throw error(seedTok, "'SEED' requires an integer value");
        }
        return java.util.Optional.of(seedValue);
    }

    /**
     * Parses a covering-reduction operator: {@code COVER [EXACT] t (R)}, where {@code t} is
     * a positive integer covering strength (t ≥ 1). The optional {@code EXACT} keyword
     * selects the provably-minimal MIP set-cover mode instead of the default greedy
     * near-minimal algorithm. A non-integer strength is a parse error (mirroring
     * {@code SAMPLE n ROWS}'s count). The input relation is validated during semantic
     * analysis (strength ≤ column count). The current token is the COVER keyword.
     */
    private CoverNode parseCover() {
        Token opTok = current;
        expect(TokenType.COVER, "Expected 'COVER'");
        boolean exact = match(TokenType.EXACT);
        String afterKeyword = exact ? "'COVER EXACT'" : "'COVER'";
        Token strengthTok = expect(TokenType.NUMBER,
                "Expected an integer covering strength (t ≥ 1) after " + afterKeyword);
        int strength;
        try {
            strength = Integer.parseInt(strengthTok.lexeme());
        } catch (NumberFormatException e) {
            throw error(strengthTok, "'COVER' strength must be an integer literal, got '"
                    + strengthTok.lexeme() + "'");
        }
        if (strength < 1) {
            throw error(strengthTok, "'COVER' strength must be ≥ 1, got " + strength);
        }
        RelNode input = parseParenthesizedRelation("Expected '(' after 'COVER' strength");
        return new CoverNode(strength, exact, input, loc(opTok));
    }

    /**
     * Parses a time-series downsampling operator:
     * {@code DOWNSAMPLE ts BY '<interval>' USING <fn> [PER key, …] [FOR n ROWS] (R)}.
     *
     * <p>The interval is a quoted string — ISO-8601 ({@code PT5M}, {@code PT1H},
     * {@code P1D}) or shorthand ({@code 5m}, {@code 1h}, {@code 1d}).  The
     * consolidation function must be one of AVG, MIN, MAX, SUM, COUNT.  The
     * current token is the DOWNSAMPLE keyword.
     */
    private DownsampleNode parseDownsample() {
        Token opTok = current;
        expect(TokenType.DOWNSAMPLE, "Expected 'DOWNSAMPLE'");
        Token tsCol = expectName("Expected timestamp column name after 'DOWNSAMPLE'");
        expect(TokenType.BY, "Expected 'BY' after timestamp column name");
        Token intervalTok = expect(TokenType.STRING,
                "Expected quoted interval string after 'BY' (e.g. '5m', 'PT1H')");
        String interval = intervalTok.lexeme().substring(1, intervalTok.lexeme().length() - 1);
        if (interval.isBlank()) {
            throw error(intervalTok, "'DOWNSAMPLE' interval must not be blank (e.g. '5m', 'PT1H')");
        }
        expect(TokenType.USING, "Expected 'USING' after interval");
        Token fnTok = expectName(
                "Expected consolidation function (AVG, MIN, MAX, SUM, COUNT) after 'USING'");
        ConsolidationFunction function;
        try {
            function = ConsolidationFunction.valueOf(fnTok.lexeme().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw error(fnTok, "Unknown consolidation function '" + fnTok.lexeme()
                    + "'; expected one of AVG, MIN, MAX, SUM, COUNT");
        }
        List<String> groupingKeys = new ArrayList<>();
        if (match(TokenType.PER)) {
            groupingKeys.add(expectName("Expected grouping key after 'PER'").lexeme());
            while (match(TokenType.COMMA)) {
                groupingKeys.add(expectName("Expected grouping key after ','").lexeme());
            }
        }
        OptionalLong maxRows = OptionalLong.empty();
        if (match(TokenType.FOR)) {
            Token countTok = expect(TokenType.NUMBER, "Expected row count after 'FOR'");
            long count;
            try {
                count = Long.parseLong(countTok.lexeme());
            } catch (NumberFormatException e) {
                throw error(countTok, "'DOWNSAMPLE … FOR n ROWS' requires an integer row count");
            }
            if (count < 1) {
                throw error(countTok, "'DOWNSAMPLE … FOR n ROWS' requires a row count of at least 1, got "
                        + count);
            }
            expect(TokenType.ROWS, "Expected 'ROWS' after row count in 'FOR n ROWS'");
            maxRows = OptionalLong.of(count);
        }
        RelNode input = parseParenthesizedRelation(
                "Expected '(' after DOWNSAMPLE specification");
        return new DownsampleNode(tsCol.lexeme(), interval, function,
                                  List.copyOf(groupingKeys), maxRows, input, loc(opTok));
    }

    /**
     * Parses a sliding / cumulative aggregate window (ADR-0015, slice 2):
     * {@code ROLLING <agg>(<expr>) OVER <n>|ALL ROWS SORT <specs> [PER <keys>] AS <col> (R)},
     * e.g. {@code ROLLING AVG(price) OVER 3 ROWS SORT trade_time ASC PER ticker AS avg3 (Ticks)}.
     * The current token is the {@code ROLLING} keyword.
     */
    private WindowNode parseRolling() {
        Token opTok = current;
        expect(TokenType.ROLLING, "Expected 'ROLLING'");

        // The variance family rolls as the five reducers do; a percentile is an
        // ordered-set aggregate, which SQL does not run over a window frame either.
        Optional<AggregateOperator> named = namedAggregate().filter(ROLLING_STATISTICS::contains);
        AggregateOperator operator = named.orElseGet(() -> switch (current.type()) {
            case SUM -> AggregateOperator.SUM;
            case AVG -> AggregateOperator.AVG;
            case COUNT -> AggregateOperator.COUNT;
            case MIN -> AggregateOperator.MIN;
            case MAX -> AggregateOperator.MAX;
            default -> throw error(current, "Expected a window aggregate (SUM, AVG, COUNT, MIN, MAX,"
                    + " STDDEV, STDDEV_SAMP, STDDEV_POP, VARIANCE, VAR_SAMP or VAR_POP) after 'ROLLING'");
        });
        advance();
        expect(TokenType.LPAREN, "Expected '(' after the ROLLING aggregate name");
        Operand argument = parseOperand();
        expect(TokenType.RPAREN, "Expected ')' after the ROLLING aggregate argument");

        expect(TokenType.OVER, "Expected 'OVER' before the ROLLING frame specification");
        Token frameTok = current;
        WindowFrame frame = parseWindowFrame();

        // A whole-partition total reads no order, so its SORT may be left out.
        List<SortSpecification> sortSpecs = List.of();
        if (!(frame instanceof WindowFrame.PartitionFrame) || current.type() == TokenType.SORT) {
            expect(TokenType.SORT, "Expected 'SORT' before the ROLLING ordering");
            sortSpecs = parseSortSpecificationList();
        }
        if (frame instanceof WindowFrame.Between between && between.measuresKey() && sortSpecs.size() != 1) {
            throw error(frameTok, "A RANGE frame with an offset measures one sort key; got "
                    + sortSpecs.size());
        }

        List<String> partitionKeys = parseOptionalPartition();

        expect(TokenType.AS, "Expected 'AS' before the ROLLING output column name");
        Token outputCol = expectName("Expected the output column name after 'AS' in ROLLING");

        RelNode input = parseParenthesizedRelation(
                "Expected '(' after the ROLLING output column");
        return new WindowNode(new WindowFunction.AggregateWindow(operator, argument),
                partitionKeys, sortSpecs, frame, outputCol.lexeme(), input, loc(opTok));
    }

    /**
     * Parses a window frame: {@code <n> ROWS} → {@link WindowFrame.BoundedFrame} or
     * {@code ALL ROWS} → {@link WindowFrame.CumulativeFrame}.  {@code ALL} is a
     * contextual identifier (not a reserved keyword), so it remains usable as a
     * column name elsewhere.  The current token is the first token after {@code OVER}.
     */
    private WindowFrame parseWindowFrame() {
        if (matchWord("PARTITION")) {
            return new WindowFrame.PartitionFrame();
        }
        if (current.type() == TokenType.ROWS || isWord(current, "RANGE")) {
            return parseFrameClause();
        }
        WindowFrame frame;
        if (current.type() == TokenType.NUMBER) {
            Token nTok = current;
            advance();
            int n;
            try {
                n = Integer.parseInt(nTok.lexeme());
            } catch (NumberFormatException e) {
                throw error(nTok, "Window frame size must be an integer, got '" + nTok.lexeme() + "'");
            }
            if (n < 1) {
                throw error(nTok, "Window frame size must be at least 1, got " + n);
            }
            frame = new WindowFrame.BoundedFrame(n);
        } else if (isNameToken(current) && current.lexeme().equalsIgnoreCase("ALL")) {
            advance();
            frame = new WindowFrame.CumulativeFrame();
        } else {
            throw error(current, "Expected a frame after 'OVER': <n> ROWS, ALL ROWS,"
                    + " ROWS or RANGE BETWEEN … AND …, or PARTITION");
        }
        expect(TokenType.ROWS, "Expected 'ROWS' after the window frame size");
        return frame;
    }

    /**
     * Parses SQL's frame clause, {@code ROWS | RANGE BETWEEN <bound> AND <bound>}, or its
     * short form {@code ROWS | RANGE <bound>}, which ends at the current row. A bound is
     * {@code UNBOUNDED PRECEDING | FOLLOWING}, {@code CURRENT ROW}, or an offset and
     * {@code PRECEDING | FOLLOWING}: a whole number of rows, or for RANGE a number or a
     * {@code DURATION} along the sort key. None of the words is reserved.
     */
    private WindowFrame parseFrameClause() {
        WindowFrame.Unit unit = current.type() == TokenType.ROWS ? WindowFrame.Unit.ROWS : WindowFrame.Unit.RANGE;
        advance();
        WindowFrame.Bound start;
        WindowFrame.Bound end;
        if (matchWord("BETWEEN")) {
            start = parseFrameBound(unit);
            expect(TokenType.AND, "Expected 'AND' between the frame's bounds");
            end = parseFrameBound(unit);
        } else {
            start = parseFrameBound(unit);
            end = WindowFrame.Bound.currentRow();
        }
        Token at = current;
        try {
            return new WindowFrame.Between(unit, start, end);
        } catch (IllegalArgumentException e) {
            throw error(at, e.getMessage());
        }
    }

    private WindowFrame.Bound parseFrameBound(WindowFrame.Unit unit) {
        if (matchWord("UNBOUNDED")) {
            if (matchWord("PRECEDING")) {
                return WindowFrame.Bound.unboundedPreceding();
            }
            if (matchWord("FOLLOWING")) {
                return WindowFrame.Bound.unboundedFollowing();
            }
            throw error(current, "Expected 'PRECEDING' or 'FOLLOWING' after 'UNBOUNDED'");
        }
        if (matchWord("CURRENT")) {
            if (!matchWord("ROW")) {
                throw error(current, "Expected 'ROW' after 'CURRENT'");
            }
            return WindowFrame.Bound.currentRow();
        }
        Token offsetTok = current;
        Operand offset;
        if (current.type() == TokenType.NUMBER) {
            advance();
            offset = new NumberOperand(offsetTok.lexeme(), loc(offsetTok));
            boolean whole = offsetTok.lexeme().chars().allMatch(Character::isDigit);
            if (unit == WindowFrame.Unit.ROWS && !whole) {
                throw error(offsetTok, "A ROWS offset is a whole number of rows, got '" + offsetTok.lexeme() + "'");
            }
        } else if (current.type() == TokenType.DURATION && unit == WindowFrame.Unit.RANGE) {
            offset = parseTemporalLiteral();
            if (((DurationOperand) offset).value().isNegative() || ((DurationOperand) offset).months() < 0) {
                throw error(offsetTok, "A frame offset cannot be negative");
            }
            if (!((DurationOperand) offset).isExact()) {
                throw error(offsetTok, "A RANGE offset is an exact DURATION; a month has no fixed"
                        + " length, so write it in days, as DURATION 'P30D'");
            }
        } else {
            throw error(current, unit == WindowFrame.Unit.ROWS
                    ? "Expected a frame bound: UNBOUNDED PRECEDING, <n> PRECEDING, CURRENT ROW,"
                            + " <n> FOLLOWING or UNBOUNDED FOLLOWING"
                    : "Expected a frame bound: UNBOUNDED PRECEDING, a number or DURATION and"
                            + " PRECEDING or FOLLOWING, CURRENT ROW, or UNBOUNDED FOLLOWING");
        }
        if (matchWord("PRECEDING")) {
            return WindowFrame.Bound.preceding(offset);
        }
        if (matchWord("FOLLOWING")) {
            return WindowFrame.Bound.following(offset);
        }
        throw error(current, "Expected 'PRECEDING' or 'FOLLOWING' after the frame offset");
    }

    /**
     * Parses an optional {@code PER <key> [, …]} partition clause.  Returns an empty
     * list when absent (one partition over the whole relation); requires at least
     * one key when present.
     */
    private List<String> parseOptionalPartition() {
        List<String> keys = new ArrayList<>();
        if (match(TokenType.PER)) {
            keys.add(expectName("Expected a partition key after 'PER'").lexeme());
            while (match(TokenType.COMMA)) {
                keys.add(expectName("Expected a partition key after ','").lexeme());
            }
        }
        return List.copyOf(keys);
    }

    /**
     * Parses the ranking / offset window form (ADR-0015, slices 3–4):
     * {@code WINDOW <name>(…) SORT <specs> [PER <keys>] AS <col> (R)}, e.g.
     * {@code WINDOW RANK() SORT amount DESC PER customer_id AS rnk (Orders)}.  The
     * function name is a contextual identifier (not a reserved keyword).  Ranking
     * functions (slice 3) are implemented; offset functions (slice 4) report a clear,
     * positioned error reserving the syntax.
     */
    private WindowNode parseWindow() {
        Token opTok = current;
        expect(TokenType.WINDOW, "Expected 'WINDOW'");
        Token fnTok = expectName("Expected a window function name after 'WINDOW'");
        WindowFunction function = parseWindowFunction(fnTok);

        expect(TokenType.SORT, "Expected 'SORT' before the WINDOW ordering");
        List<SortSpecification> sortSpecs = parseSortSpecificationList();

        List<String> partitionKeys = parseOptionalPartition();

        expect(TokenType.AS, "Expected 'AS' before the WINDOW output column name");
        Token outputCol = expectName("Expected the output column name after 'AS' in WINDOW");

        RelNode input = parseParenthesizedRelation(
                "Expected '(' after the WINDOW output column");
        return new WindowNode(function, partitionKeys, sortSpecs,
                new WindowFrame.PartitionFrame(), outputCol.lexeme(), input, loc(opTok));
    }

    /**
     * Dispatches a contextual {@code WINDOW} function name to its
     * {@link WindowFunction}.  Ranking functions (slice 3) and offset functions
     * (slice 4) are implemented.
     */
    private WindowFunction parseWindowFunction(Token fnTok) {
        String name = fnTok.lexeme().toUpperCase(java.util.Locale.ROOT);
        return switch (name) {
            case "ROW_NUMBER", "RANK", "DENSE_RANK", "PERCENT_RANK", "NTILE", "CUME_DIST" ->
                    parseRankingWindow(RankingFunction.valueOf(name));
            case "LAG", "LEAD", "FIRST_VALUE", "LAST_VALUE", "NTH_VALUE" ->
                    parseOffsetWindow(OffsetFunction.valueOf(name));
            default -> throw error(fnTok, "Unknown window function '" + fnTok.lexeme()
                    + "'; expected ROW_NUMBER, RANK, DENSE_RANK, PERCENT_RANK, NTILE, CUME_DIST, "
                    + "LAG, LEAD, FIRST_VALUE, LAST_VALUE, or NTH_VALUE");
        };
    }

    /**
     * Parses a ranking window function body (ADR-0015, slice 3).  {@code ROW_NUMBER()},
     * {@code RANK()}, {@code DENSE_RANK()}, and {@code PERCENT_RANK()} take empty
     * parentheses; {@code NTILE(n)} takes a single bucket-count argument.
     */
    private WindowFunction.RankingWindow parseRankingWindow(RankingFunction fn) {
        expect(TokenType.LPAREN, "Expected '(' after window function '" + fn.name() + "'");
        Optional<Operand> ntileCount = Optional.empty();
        if (fn == RankingFunction.NTILE) {
            ntileCount = Optional.of(parseOperand());
        }
        expect(TokenType.RPAREN, "Expected ')' after the " + fn.name() + " argument list");
        return new WindowFunction.RankingWindow(fn, ntileCount);
    }

    /**
     * Parses an offset window function body (ADR-0015, slice 4).
     * {@code LAG(expr [, offset [, default]])} and
     * {@code LEAD(expr [, offset [, default]])} take a value expression, an
     * optional row offset (defaults to 1), and an optional out-of-bounds default
     * (defaults to {@code NULL}).  {@code FIRST_VALUE(expr)} and
     * {@code LAST_VALUE(expr)} take only the value expression.
     */
    private WindowFunction.OffsetWindow parseOffsetWindow(OffsetFunction fn) {
        expect(TokenType.LPAREN, "Expected '(' after window function '" + fn.name() + "'");
        Operand expression = parseOperand();
        Optional<Operand> offset = Optional.empty();
        Optional<Operand> defaultValue = Optional.empty();
        if (fn == OffsetFunction.NTH_VALUE) {
            expect(TokenType.COMMA, "Expected ',' and a row number: NTH_VALUE(x, n)");
            offset = Optional.of(parseOperand());
        } else if (fn == OffsetFunction.LAG || fn == OffsetFunction.LEAD) {
            if (match(TokenType.COMMA)) {
                offset = Optional.of(parseOperand());
                if (match(TokenType.COMMA)) {
                    defaultValue = Optional.of(parseOperand());
                }
            }
        }
        expect(TokenType.RPAREN, "Expected ')' after the " + fn.name() + " argument list");
        return new WindowFunction.OffsetWindow(fn, expression, offset, defaultValue);
    }

    /**
     * Parses a goal-seek operator:
     * {@code SOLVE left = right [PER k, …] [WITHIN ε] [MAX n ROUNDS] [START u = e, …] (R)}, e.g.
     * {@code SOLVE total = principal * rate (Loans)}, or a system of equations in
     * braces, {@code SOLVE &#123; a = b, c = d &#125; (R)}, separated by commas as every
     * list inside an expression is — {@code ;} ends a statement. The sides are
     * arithmetic operands; the equations are validated during semantic analysis. A
     * {@code &#123;} cannot open a struct literal here, a struct never being a number.
     * {@code START} is a contextual word, as {@code ROUNDS} is: only a {@code (} may
     * follow the clauses, so a column named {@code start} is never in doubt.
     * The current token is the SOLVE keyword.
     */
    private SolveNode parseSolve() {
        Token opTok = current;
        expect(TokenType.SOLVE, "Expected 'SOLVE'");
        List<SolveEquation> equations = new ArrayList<>();
        if (match(TokenType.LBRACE)) {
            do {
                equations.add(parseSolveEquation());
            } while (match(TokenType.COMMA));
            expect(TokenType.RBRACE, "Expected ',' or '}' after a SOLVE equation");
        } else {
            equations.add(parseSolveEquation());
        }
        List<String> keys = match(TokenType.PER)
                ? parseIterateNames("Expected a key column name after 'PER'")
                : List.of();
        Optional<java.math.BigDecimal> tolerance = Optional.empty();
        if (match(TokenType.WITHIN)) {
            Token tolTok = expect(TokenType.NUMBER, "Expected a tolerance after 'WITHIN'");
            java.math.BigDecimal tol = new java.math.BigDecimal(tolTok.lexeme());
            if (tol.signum() <= 0) {
                throw error(tolTok, "A SOLVE tolerance must be greater than 0, got " + tolTok.lexeme());
            }
            tolerance = Optional.of(tol);
        }
        Optional<Integer> maxRounds = Optional.empty();
        if (match(TokenType.MAX)) {
            int n = parseRoundCount(1, "SOLVE");
            if (!matchWord("ROUNDS")) {
                throw error(current, "Expected 'ROUNDS' after 'MAX " + n + "'");
            }
            maxRounds = Optional.of(n);
        }
        List<SolveStart> starts = new ArrayList<>();
        if (matchWord("START")) {
            Set<String> named = new HashSet<>();
            do {
                Token column = expectName("Expected an unknown column name after 'START'");
                String name = column.lexeme();
                if (!named.add(name.toLowerCase(Locale.ROOT))) {
                    throw error(column, "SOLVE START names '" + name + "' more than once");
                }
                expect(TokenType.EQUAL, "Expected '=' after the START column '" + name + "'");
                starts.add(new SolveStart(name, parseOperand()));
            } while (match(TokenType.COMMA));
        }
        RelNode input = parseParenthesizedRelation("Expected '(' after the SOLVE equation");
        return new SolveNode(equations, keys, tolerance, maxRounds, starts, input, loc(opTok));
    }

    private SolveEquation parseSolveEquation() {
        Operand left = parseOperand();
        expect(TokenType.EQUAL, "Expected '=' between the two sides of the SOLVE equation");
        return new SolveEquation(left, parseOperand());
    }

    /**
     * Parses a declarative-optimisation operator.  Two modes:
     *
     * <p><b>MIP (binary subset selection, default):</b>
     * {@code OPTIMIZE MAXIMIZE|MINIMIZE SUM(expr) SUBJECT TO SUM(expr) op bound
     * [AND …] [PER keys] (R)}.
     *
     * <p><b>LP (continuous allocation):</b>
     * {@code OPTIMIZE ALLOCATE (lo, hi) MAXIMIZE|MINIMIZE SUM(expr) SUBJECT TO
     * SUM(expr) op bound [AND …] -> col [PER keys] (R)}.
     *
     * <p>At least one constraint is required in both modes.  The current token is
     * OPTIMIZE.
     */
    private OptimizeNode parseOptimize() {
        Token opTok = current;
        expect(TokenType.OPTIMIZE, "Expected 'OPTIMIZE'");

        // Optional LP mode: ALLOCATE (lo, hi) — store bounds; column name comes after constraints.
        double allocLo = 0, allocHi = 0;
        boolean lpMode = match(TokenType.ALLOCATE);
        if (lpMode) {
            expect(TokenType.LPAREN, "Expected '(' after ALLOCATE");
            allocLo = parseSignedNumber();
            expect(TokenType.COMMA, "Expected ',' between allocation bounds");
            allocHi = parseSignedNumber();
            expect(TokenType.RPAREN, "Expected ')' after allocation bounds");
        }

        ObjectiveSense sense;
        if (match(TokenType.MAXIMIZE)) {
            sense = ObjectiveSense.MAXIMIZE;
        } else if (match(TokenType.MINIMIZE)) {
            sense = ObjectiveSense.MINIMIZE;
        } else {
            throw error(current, "Expected MAXIMIZE or MINIMIZE after OPTIMIZE"
                    + (lpMode ? " ALLOCATE (lo, hi)" : ""));
        }

        Operand objective = parseSumExpression();

        expect(TokenType.SUBJECT, "Expected 'SUBJECT TO' before the OPTIMIZE constraints");
        expect(TokenType.TO, "Expected 'TO' after 'SUBJECT'");

        List<OptimizeConstraint> constraints = new ArrayList<>();
        constraints.add(parseOptimizeConstraint());
        while (match(TokenType.AND)) {
            constraints.add(parseOptimizeConstraint());
        }
        if (current.type() == TokenType.COMMA && peek(1).type() == TokenType.SUM) {
            // SQL's habit of listing: SUBJECT TO SUM(w) <= 80, SUM(v) <= 60.
            throw ParseException.at("Join OPTIMIZE constraints with AND, not ',':"
                    + " SUBJECT TO SUM(a) <= 80 AND SUM(b) <= 60", lexer.input(), current);
        }

        // LP mode: -> col names the allocation output column.
        Optional<AllocationSpec> allocation = Optional.empty();
        if (lpMode) {
            expect(TokenType.ARROW, "Expected '->' after OPTIMIZE ALLOCATE constraints");
            Token colTok = expect(TokenType.IDENTIFIER,
                    "Expected the allocation column name after '->'");
            try {
                allocation = Optional.of(new AllocationSpec(allocLo, allocHi, colTok.lexeme()));
            } catch (IllegalArgumentException e) {
                throw error(opTok, e.getMessage());
            }
        }

        List<String> keys = new ArrayList<>();
        if (match(TokenType.PER)) {
            keys.add(expect(TokenType.IDENTIFIER, "Expected a grouping key after 'PER'").lexeme());
            while (match(TokenType.COMMA)) {
                keys.add(expect(TokenType.IDENTIFIER, "Expected a grouping key after ','").lexeme());
            }
        }

        RelNode input = parseParenthesizedRelation("Expected '(' after the OPTIMIZE specification");
        return new OptimizeNode(sense, objective, List.copyOf(constraints), List.copyOf(keys),
                allocation, input, loc(opTok));
    }

    /** Parses {@code SUM ( operand )} and returns the inner per-row expression. */
    private Operand parseSumExpression() {
        expect(TokenType.SUM, "Expected 'SUM(' in the OPTIMIZE objective or constraint");
        expect(TokenType.LPAREN, "Expected '(' after 'SUM'");
        Operand expr = parseOperand();
        expect(TokenType.RPAREN, "Expected ')' after the SUM expression");
        return expr;
    }

    /** Parses a single {@code SUM(expr) op bound} constraint. */
    private OptimizeConstraint parseOptimizeConstraint() {
        Operand expr = parseSumExpression();
        ComparisonOperator op = switch (current.type()) {
            case LESS_EQUAL    -> ComparisonOperator.LESS_EQUAL;
            case GREATER_EQUAL -> ComparisonOperator.GREATER_EQUAL;
            case EQUAL         -> ComparisonOperator.EQUAL;
            default -> throw error(current,
                    "Expected a constraint operator (<=, >=, or =)");
        };
        advance();
        double bound = parseSignedNumber();
        return new OptimizeConstraint(expr, op, bound);
    }

    /** Parses an optionally-negated numeric literal into a {@code double}. */
    private double parseSignedNumber() {
        boolean negative = match(TokenType.MINUS);
        Token num = expect(TokenType.NUMBER, "Expected a numeric constraint bound");
        double value = Double.parseDouble(num.lexeme());
        return negative ? -value : value;
    }

    /**
     * Parses a top-k-per-group operator:
     * {@code TOP count [, offset+count] sortspecs [PER keys] (R)}, e.g.
     * {@code TOP 3 amount DESC PER customer_id (Orders)}.  At least one sort
     * specification is required.
     *
     * <p><b>{@code PER} is optional, and its absence means one global group</b> — the
     * top N overall rather than the top N of each group. That is the form {@code LIM-003}
     * produces when it fuses {@code λ} over {@code τ}, and the form {@code PrettyPrinter}
     * emits for it, so a grammar that could not read it back made the optimizer's own
     * output unparseable: {@code --optimize} printed a rewritten query that could not be
     * run or pasted anywhere. Both node corpora carry a {@code TOP} with grouping keys, so
     * the round-trip guards had never been shown the shape the optimizer actually makes.
     */
    private TopKNode parseTopK() {
        Token opTok = current;
        int opAt = consumed.size();
        expect(TokenType.TOP, "Expected 'TOP'");

        Token firstNum = expect(TokenType.NUMBER, "Expected a count after 'TOP'");
        long firstValue = parseRowCount(firstNum, "'TOP'");
        Optional<Long> offset = Optional.empty();
        long count;
        if (match(TokenType.COMMA)) {
            offset = Optional.of(firstValue);
            count = parseRowCount(expect(TokenType.NUMBER, "Expected count after offset"), "'TOP'");
        } else {
            count = firstValue;
        }
        int countEnd = consumed.size();
        boolean withTies = parseWithTies();

        int sortStart = consumed.size();
        SortSpecification leading = parseSortSpecification();
        // Whether the first key was left ascending by default: "top" reads as the
        // highest, so validation warns about it (#210).
        boolean leadingDirectionImplied = !sortDirectionWritten;
        List<SortSpecification> sortSpecs = parseSortSpecificationList(leading);
        int sortEnd = consumed.size();
        Token misplacedTies = withTies ? null : skipMisplacedTies();

        // No PER is not a missing clause, it is the global group. The sort-spec list is
        // read greedily and ends at either PER or the opening parenthesis, so which was
        // written is unambiguous.
        List<String> keys = new ArrayList<>();
        int perStart = consumed.size();
        if (match(TokenType.PER)) {
            keys.add(expect(TokenType.IDENTIFIER, "Expected a grouping key after 'PER'").lexeme());
            while (match(TokenType.COMMA)) {
                keys.add(expect(TokenType.IDENTIFIER, "Expected a grouping key after ','").lexeme());
            }
        }
        int perEnd = consumed.size();
        if (misplacedTies == null && !withTies) {
            misplacedTies = skipMisplacedTies();
        }

        if (misplacedTies != null) {
            rejectMisplacedTies(misplacedTies, writtenBetween(opAt, countEnd),
                    writtenBetween(sortStart, sortEnd), writtenBetween(perStart, perEnd));
        }
        RelNode input = parseParenthesizedRelation(
                keys.isEmpty() ? "Expected 'PER' or '(' after the TOP sort keys"
                               : "Expected '(' after the TOP grouping keys");
        return new TopKNode(List.copyOf(keys), sortSpecs, offset, count, withTies, leadingDirectionImplied,
                input, loc(opTok));
    }

    /** Consumes a {@code WITH TIES} written after TOP's sort or PER keys, returning its WITH; null if none. */
    private Token skipMisplacedTies() {
        Token with = current;
        return parseWithTies() ? with : null;
    }

    /**
     * Refuses {@code TOP 2 x ASC WITH TIES PER k (R)}, or the same after the PER keys, at
     * WITH, rewritten with WITH TIES after the count, where TOP reads it. SQL writes
     * {@code FETCH FIRST n ROWS WITH TIES} after its ORDER BY, so a SQL reader puts it
     * after the sort keys.
     */
    private void rejectMisplacedTies(Token with, String count, String sort, String per) {
        String input;
        try {
            int start = consumed.size();
            parseParenthesizedRelation("Expected '(' after the TOP grouping keys");
            input = writtenFrom(start);
        } catch (ParseException e) {
            input = "(…)";
        }
        throw ParseException.at("WITH TIES goes right after the count: write " + count + " WITH TIES "
                + sort + (per.isEmpty() ? "" : " " + per) + " " + input, lexer.input(), with);
    }

    /** Consumes SQL's {@code WITH TIES} after a row count, and says whether it was written. */
    private boolean parseWithTies() {
        if (current.type() == TokenType.WITH && peek(1).type() == TokenType.TIES) {
            advance();
            advance();
            return true;
        }
        return false;
    }

    /**
     * Reads a row count or offset — a whole number that fits in a {@code long} — from a
     * {@code NUMBER} token, reporting anything else at the token. The lexer has already
     * refused a sign, so the result is never negative.
     */
    private long parseRowCount(Token tok, String operator) {
        try {
            return Long.parseLong(tok.lexeme());
        } catch (NumberFormatException e) {
            throw error(tok, operator + " takes a whole-number row count, got '" + tok.lexeme() + "'");
        }
    }

    /**
     * Parses a λ. With SQL's {@code WITH TIES} it keeps the rows tied with the last one
     * too, which needs the order that decides a tie, so its input must be a τ; the pair
     * is then the global {@code TOP} that means the same thing, with the ties kept.
     */
    private RelNode parseLimit() {
        Token opTok = current;
        expect(TokenType.LIMIT, "Expected 'λ'");

        Token firstNum = expect(TokenType.NUMBER, "Expected number for limit or offset");
        long firstValue = parseRowCount(firstNum, "'λ'");

        Optional<Long> offset = Optional.empty();
        Long count;

        if (match(TokenType.COMMA)) {
            // Two arguments: offset, count
            offset = Optional.of(firstValue);
            Token countNum = expect(TokenType.NUMBER, "Expected count after offset");
            count = parseRowCount(countNum, "'λ'");
        } else {
            // One argument: count
            count = firstValue;
        }
        boolean withTies = parseWithTies();

        RelNode input = parseParenthesizedRelation("Expected '(' after limit specification");
        if (withTies) {
            if (!(input instanceof SortNode sort)) {
                throw error(opTok, "WITH TIES needs a sorted input, to know what ties: "
                        + "λ n WITH TIES (τ key (R))");
            }
            return new TopKNode(List.of(), sort.sortSpecs(), offset, count, true, sort.input(), loc(opTok));
        }
        return new LimitNode(offset, count, input, loc(opTok));
    }

    /** The keys of a γ, and the grouping sets over them: empty for the ordinary grouping. */
    private record Grouping(List<GroupingKey> keys, List<List<Integer>> sets) { }

    /** A CUBE of more keys than this is refused: it would be 2^n grouping sets. */
    private static final int MAX_CUBE_KEYS = 12;

    /**
     * Parses the grouping-key list of a γ: zero or more comma-separated elements
     * preceding the aggregate-function list. An element is a {@link GroupingKey} (a full
     * {@link Operand} expression with an optional {@code → alias}), or SQL's
     * {@code ROLLUP (k, …)}, {@code CUBE (k, …)} or {@code GROUPING SETS ((k, …), …, ())}.
     * Parsing stops at the first aggregate-function keyword (SUM, COUNT, …) or the input
     * relation's {@code (}, so a bare column, a function call ({@code YEAR(ts) → yr}), or
     * an arithmetic expression may all be grouping keys — mirroring what the
     * projection/selection positions already accept.
     *
     * <p>The grouping sets of several elements are SQL's: every combination of one set
     * from each, so {@code region, ROLLUP (product)} is {@code (region, product)} and
     * {@code (region)}. A key written twice is one key. The keys are numbered in the
     * order the sets name them first, which is the order they are written in unless a
     * plain key follows a {@code GROUPING SETS} whose sets do not all name the same keys.
     */
    private Grouping parseGrouping() {
        List<GroupingKey> keys = new ArrayList<>();
        List<List<List<Integer>>> elements = new ArrayList<>();
        boolean sets = false;

        while (!startsAggregateFunction() && current.type() != TokenType.LPAREN) {
            if (startsGroupingSetsElement()) {
                sets = true;
                elements.add(parseGroupingSetsElement(keys));
            } else {
                elements.add(List.of(List.of(addGroupingKey(keys, parseGroupingKey()))));
            }

            if (current.type() == TokenType.COMMA) {
                advance();
            } else {
                break;
            }
        }

        if (!sets) {
            return new Grouping(keys, List.of());
        }
        List<List<Integer>> combined = List.of(List.of());
        for (List<List<Integer>> element : elements) {
            List<List<Integer>> next = new ArrayList<>(combined.size() * element.size());
            for (List<Integer> prefix : combined) {
                for (List<Integer> set : element) {
                    java.util.TreeSet<Integer> union = new java.util.TreeSet<>(prefix);
                    union.addAll(set);
                    next.add(List.copyOf(union));
                }
            }
            combined = next;
        }
        // Number the keys in the order the sets name them first.
        int[] renumbered = new int[keys.size()];
        java.util.Arrays.fill(renumbered, -1);
        List<GroupingKey> ordered = new ArrayList<>(keys.size());
        for (List<Integer> set : combined) {
            for (int index : set) {
                if (renumbered[index] < 0) {
                    renumbered[index] = ordered.size();
                    ordered.add(keys.get(index));
                }
            }
        }
        List<List<Integer>> renumberedSets = combined.stream()
                .map(set -> set.stream().map(index -> renumbered[index]).sorted().toList())
                .toList();
        return new Grouping(ordered, renumberedSets);
    }

    /** Whether {@code ROLLUP (}, {@code CUBE (} or {@code GROUPING SETS} starts here. */
    private boolean startsGroupingSetsElement() {
        return (isWord(current, "ROLLUP") || isWord(current, "CUBE")) && peek(1).type() == TokenType.LPAREN
                || isWord(current, "GROUPING") && isWord(peek(1), "SETS");
    }

    /** One grouping key: an expression and an optional {@code → alias}. */
    private GroupingKey parseGroupingKey() {
        int start = consumed.size();
        Operand expression = parseOperand();
        rejectForeignAlias(start, this::parseAggregateOrOperand);
        Optional<String> alias = Optional.empty();
        Token arrow = current;
        if (match(TokenType.ARROW)) {
            // GROUP k -> COUNT(*) -> n: the arrow would make COUNT the key's name, and the
            // parenthesis after it the error, so say what was meant at the arrow instead.
            if (startsAggregateFunction() && peek(1).type() == TokenType.LPAREN) {
                throw error(arrow, "a grouping key's alias must be a plain name; to add an aggregate, "
                        + "separate it with a comma: GROUP " + expression.prettyPrint() + ", "
                        + current.lexeme() + "(…) -> name (…)");
            }
            alias = Optional.of(expectName("Expected alias name after '→'").lexeme());
        }
        return new GroupingKey(expression, alias);
    }

    /**
     * Adds {@code key} to {@code keys}, or finds the key already there with the same
     * expression, and returns its index. An alias written on either is the key's.
     */
    private int addGroupingKey(List<GroupingKey> keys, GroupingKey key) {
        for (int i = 0; i < keys.size(); i++) {
            GroupingKey existing = keys.get(i);
            if (com.darkcollective.relix.ast.internal.AstEquivalence.equivalent(
                    existing.expression(), key.expression())) {
                if (key.alias().isPresent() && existing.alias().isPresent()
                        && !key.alias().get().equals(existing.alias().get())) {
                    throw ParseException.at("Grouping key " + key.expression().prettyPrint()
                            + " is named both '" + existing.alias().get() + "' and '"
                            + key.alias().get() + "': a key has one column", lexer.input(), current);
                }
                if (existing.alias().isEmpty() && key.alias().isPresent()) {
                    keys.set(i, key);
                }
                return i;
            }
        }
        keys.add(key);
        return keys.size() - 1;
    }

    /**
     * Parses {@code ROLLUP (k, …)}, {@code CUBE (k, …)} or
     * {@code GROUPING SETS (set, …)} into its grouping sets, adding its keys to
     * {@code keys}. A set of {@code GROUPING SETS} is {@code (k, …)}, {@code ()}, or one
     * key without parentheses. {@code ROLLUP} is each prefix of its keys, longest first;
     * {@code CUBE} every subset, in the order PostgreSQL lists them.
     */
    private List<List<Integer>> parseGroupingSetsElement(List<GroupingKey> keys) {
        Token start = current;
        if (isWord(current, "GROUPING")) {
            advance(); // GROUPING
            advance(); // SETS
            expect(TokenType.LPAREN, "Expected '(' after GROUPING SETS");
            List<List<Integer>> sets = new ArrayList<>();
            do {
                if (match(TokenType.LPAREN)) {
                    List<Integer> set = new ArrayList<>();
                    if (current.type() != TokenType.RPAREN) {
                        do {
                            set.add(addGroupingKey(keys, parseGroupingKey()));
                        } while (match(TokenType.COMMA));
                    }
                    expect(TokenType.RPAREN, "Expected ')' after a grouping set");
                    sets.add(set);
                } else {
                    sets.add(List.of(addGroupingKey(keys, parseGroupingKey())));
                }
            } while (match(TokenType.COMMA));
            expect(TokenType.RPAREN, "Expected ')' after the grouping sets");
            return sets;
        }
        boolean cube = isWord(current, "CUBE");
        String name = cube ? "CUBE" : "ROLLUP";
        advance();
        expect(TokenType.LPAREN, "Expected '(' after " + name);
        List<Integer> listed = new ArrayList<>();
        do {
            listed.add(addGroupingKey(keys, parseGroupingKey()));
        } while (match(TokenType.COMMA));
        expect(TokenType.RPAREN, "Expected ')' after the " + name + " keys");
        List<List<Integer>> sets = new ArrayList<>();
        if (cube) {
            if (listed.size() > MAX_CUBE_KEYS) {
                throw error(start, "CUBE of " + listed.size() + " keys would be " + (1L << listed.size())
                        + " grouping sets; it takes at most " + MAX_CUBE_KEYS + " keys");
            }
            int n = listed.size();
            for (int mask = (1 << n) - 1; mask >= 0; mask--) {
                List<Integer> set = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    if ((mask & (1 << (n - 1 - i))) != 0) {
                        set.add(listed.get(i));
                    }
                }
                sets.add(set);
            }
        } else {
            for (int size = listed.size(); size >= 0; size--) {
                sets.add(List.copyOf(listed.subList(0, size)));
            }
        }
        return sets;
    }

    /**
     * The aggregates named by an ordinary word rather than a keyword, so that none of the
     * names is reserved: a column may still be called {@code median}. Each is an
     * aggregate only where it is called, written directly before its parenthesis.
     */
    private static final java.util.Map<String, AggregateOperator> NAMED_AGGREGATES = java.util.Map.ofEntries(
            java.util.Map.entry("STDDEV", AggregateOperator.STDDEV),
            java.util.Map.entry("STDDEV_SAMP", AggregateOperator.STDDEV_SAMP),
            java.util.Map.entry("STDDEV_POP", AggregateOperator.STDDEV_POP),
            java.util.Map.entry("VARIANCE", AggregateOperator.VARIANCE),
            java.util.Map.entry("VAR_SAMP", AggregateOperator.VAR_SAMP),
            java.util.Map.entry("VAR_POP", AggregateOperator.VAR_POP),
            java.util.Map.entry("PERCENTILE_CONT", AggregateOperator.PERCENTILE_CONT),
            java.util.Map.entry("PERCENTILE_DISC", AggregateOperator.PERCENTILE_DISC),
            java.util.Map.entry("MEDIAN", AggregateOperator.MEDIAN),
            java.util.Map.entry("STRING_AGG", AggregateOperator.STRING_AGG),
            java.util.Map.entry("GROUP_CONCAT", AggregateOperator.STRING_AGG),
            java.util.Map.entry("LISTAGG", AggregateOperator.STRING_AGG),
            java.util.Map.entry("ARRAY_AGG", AggregateOperator.COLLECT),
            java.util.Map.entry("GROUPING", AggregateOperator.GROUPING),
            java.util.Map.entry("GROUPING_ID", AggregateOperator.GROUPING));

    /** The named aggregates that roll over a window frame: the variance family. */
    private static final java.util.Set<AggregateOperator> ROLLING_STATISTICS = java.util.EnumSet.of(
            AggregateOperator.STDDEV, AggregateOperator.STDDEV_SAMP, AggregateOperator.STDDEV_POP,
            AggregateOperator.VARIANCE, AggregateOperator.VAR_SAMP, AggregateOperator.VAR_POP);

    /** The aggregate the current word names where it is called, as in {@code MEDIAN(x)}; empty otherwise. */
    private Optional<AggregateOperator> namedAggregate() {
        if (peek(1).type() != TokenType.LPAREN || !opensCall(current, peek(1))) {
            return Optional.empty();
        }
        return Optional.ofNullable(NAMED_AGGREGATES.get(current.lexeme().toUpperCase(java.util.Locale.ROOT)));
    }

    private boolean startsAggregateFunction() {
        Token token = current;
        return namedAggregate().isPresent() ||
               token.type() == TokenType.SUM ||
               token.type() == TokenType.AVG ||
               token.type() == TokenType.COUNT ||
               token.type() == TokenType.MIN ||
               token.type() == TokenType.MAX ||
               token.type() == TokenType.COLLECT ||
               token.type() == TokenType.ARGMAX ||
               token.type() == TokenType.ARGMIN;
    }

    private List<AggregateFunction> parseAggregateFunctionList() {
        List<AggregateFunction> aggregates = new ArrayList<>();

        do {
            int start = consumed.size();
            aggregates.add(parseAggregateFunction());
            rejectForeignAlias(start, this::parseAggregateOrOperand);

            if (current.type() == TokenType.COMMA) {
                advance();
            } else {
                break;
            }
            // GROUP k, COUNT(*) -> n, total = SUM(x): a name before '=' or ':' ends the
            // list, so the rename is refused here rather than at the name.
            if (isNameToken(current) && !startsAggregateFunction()
                    && (peek(1).type() == TokenType.EQUAL || peek(1).type() == TokenType.COLON)) {
                int name = consumed.size();
                advance();
                rejectForeignAlias(name, this::parseAggregateOrOperand);
            }
        } while (startsAggregateFunction());

        if (aggregates.isEmpty()) {
            throw error(current, "Expected at least one aggregate function");
        }

        return aggregates;
    }

    /**
     * The tokens consumed from {@code consumed[start]} on, spaced as the reader wrote
     * them: a space wherever the source had any, between tokens or across a line.
     */
    private String writtenFrom(int start) {
        return writtenBetween(start, consumed.size());
    }

    /** Whether the source had space, or a line break, between two tokens. */
    private static boolean spaced(Token previous, Token token) {
        return token.line() != previous.line() || token.column() > previous.column() + previous.lexeme().length();
    }

    /** The tokens {@code consumed[start]} up to {@code consumed[end]}, as {@link #writtenFrom}. */
    private String writtenBetween(int start, int end) {
        StringBuilder text = new StringBuilder();
        Token previous = null;
        for (Token token : consumed.subList(start, end)) {
            if (previous != null && spaced(previous, token)) {
                text.append(' ');
            }
            text.append(token.lexeme());
            previous = token;
        }
        return text.toString();
    }

    /** An aggregate where one starts, else a scalar expression: what a renamed item in GROUP is. */
    private void parseAggregateOrOperand() {
        if (startsAggregateFunction()) {
            parseAggregateFunction();
        } else {
            parseOperand();
        }
    }

    private AggregateFunction parseAggregateFunction() {
        Token nameTok = current;
        AggregateOperator operator = namedAggregate().orElseGet(() -> switch (current.type()) {
            case SUM -> AggregateOperator.SUM;
            case AVG -> AggregateOperator.AVG;
            case COUNT -> AggregateOperator.COUNT;
            case MIN -> AggregateOperator.MIN;
            case MAX -> AggregateOperator.MAX;
            case COLLECT -> AggregateOperator.COLLECT;
            case ARGMAX -> AggregateOperator.ARGMAX;
            case ARGMIN -> AggregateOperator.ARGMIN;
            default -> throw error(current, EXPECTED_AGGREGATE);
        });
        advance();

        if (operator == AggregateOperator.PERCENTILE_CONT || operator == AggregateOperator.PERCENTILE_DISC) {
            return parsePercentile(operator, nameTok);
        }
        if (operator == AggregateOperator.GROUPING) {
            return parseGroupingIndicator(nameTok);
        }

        expect(TokenType.LPAREN, "Expected '(' after aggregate function name");

        // The aggregate argument is a full scalar expression — e.g. SUM(price * qty).
        // COUNT(*) is the one exception: SQL's row-count spelling, accepted as a
        // pure synonym for COUNT(1).  A literal argument is never NULL, so counting
        // it counts every row; parsing to the *identical* AST is how every other
        // alternative spelling in this language works (SELECT/σ, !=/≠), and it means
        // inference, validation, execution, and SQL pushdown need no COUNT(*) case.
        // Restricted to COUNT — SUM(*)/MIN(*) are meaningless, so `*` keeps its
        // ordinary MULTIPLY reading everywhere else.
        // SQL's COUNT(DISTINCT x): each distinct value of the argument, once. A ranking
        // aggregate has no such reading, and COUNT(DISTINCT *) has none in SQL either.
        boolean distinct = false;
        if (current.type() == TokenType.DISTINCT) {
            Token distinctTok = current;
            if (operator == AggregateOperator.ARGMAX || operator == AggregateOperator.ARGMIN) {
                throw ParseException.at(operator + " ranks rows and takes no DISTINCT",
                        lexer.input(), distinctTok);
            }
            advance();
            if (current.type() == TokenType.MULTIPLY && peek(1).type() == TokenType.RPAREN) {
                throw ParseException.at("COUNT(DISTINCT *) has no meaning: name what to count"
                        + " distinct values of, as in COUNT(DISTINCT session)",
                        lexer.input(), current);
            }
            distinct = true;
        }

        Operand argument;
        if (!distinct && operator == AggregateOperator.COUNT
                && current.type() == TokenType.MULTIPLY
                && peek(1).type() == TokenType.RPAREN) {
            Token star = current;
            advance();
            argument = new NumberOperand("1", loc(star));
        } else if (!distinct && current.type() == TokenType.MULTIPLY && peek(1).type() == TokenType.RPAREN) {
            // SUM(*): reported once the GROUP's input is read, so the GROUP can be echoed.
            Token star = current;
            if (groupAt < 0) {
                rejectStarArgument(star, -1);
            }
            if (groupStar == null) {
                groupStar = star;
            }
            advance();
            argument = new NumberOperand("1", loc(star));
        } else {
            argument = parseOperand();
        }

        if (operator == AggregateOperator.STRING_AGG) {
            return parseStringAgg(nameTok, argument, distinct);
        }

        // ARGMAX/ARGMIN take a second expression: ARGMAX(rank, yield).
        boolean isArg = operator == AggregateOperator.ARGMAX || operator == AggregateOperator.ARGMIN;
        Optional<Operand> yieldExpr = Optional.empty();
        if (isArg) {
            expect(TokenType.COMMA, "Expected ',' — ARGMAX/ARGMIN take two arguments: (rank, yield)");
            yieldExpr = Optional.of(parseOperand());
        }
        // COLLECT(x ORDER BY y), SQL's array_agg ordering.
        List<SortSpecification> orderBy = operator == AggregateOperator.COLLECT ? parseAggregateOrder() : List.of();

        expect(TokenType.RPAREN, "Expected ')' after aggregate function argument");
        return finishAggregate(operator, argument, yieldExpr, distinct, orderBy);
    }

    /** An {@code ORDER BY} inside an aggregate's parentheses, or none. */
    private List<SortSpecification> parseAggregateOrder() {
        if (current.type() != TokenType.ORDER) {
            return List.of();
        }
        advance();
        expect(TokenType.BY, "Expected 'BY' after 'ORDER'");
        return parseSortSpecificationList();
    }

    /**
     * Parses the rest of a string aggregate, in each engine's spelling, after its value:
     * PostgreSQL's and SQL Server's {@code STRING_AGG(x, sep [ORDER BY …])}, MySQL's
     * {@code GROUP_CONCAT(x [ORDER BY …] [SEPARATOR sep])} with a comma by default, and
     * Oracle's {@code LISTAGG(x [, sep]) [WITHIN GROUP (ORDER BY …)]} with nothing by
     * default. All three are {@link AggregateOperator#STRING_AGG}.
     */
    private AggregateFunction parseStringAgg(Token nameTok, Operand argument, boolean distinct) {
        String name = nameTok.lexeme().toUpperCase(java.util.Locale.ROOT);
        SourceLocation at = loc(nameTok);
        Operand separator;
        List<SortSpecification> orderBy;
        switch (name) {
            case "GROUP_CONCAT" -> {
                orderBy = parseAggregateOrder();
                separator = matchWord("SEPARATOR") ? parseOperand() : new StringOperand(",", at);
                expect(TokenType.RPAREN, "Expected ')' after the GROUP_CONCAT arguments");
            }
            case "LISTAGG" -> {
                separator = match(TokenType.COMMA) ? parseOperand() : new StringOperand("", at);
                expect(TokenType.RPAREN, "Expected ')' after the LISTAGG arguments");
                orderBy = List.of();
                if (current.type() == TokenType.WITHIN) {
                    advance();
                    expect(TokenType.AGGREGATION, "Expected 'GROUP' after 'WITHIN'");
                    expect(TokenType.LPAREN, "Expected '(' after 'WITHIN GROUP'");
                    expect(TokenType.ORDER, "Expected 'ORDER BY' in WITHIN GROUP (…)");
                    expect(TokenType.BY, "Expected 'BY' after 'ORDER'");
                    orderBy = parseSortSpecificationList();
                    expect(TokenType.RPAREN, "Expected ')' after the WITHIN GROUP ordering");
                }
            }
            default -> {
                expect(TokenType.COMMA, "Expected ',' and a separator: STRING_AGG(x, ', ')");
                separator = parseOperand();
                orderBy = parseAggregateOrder();
                expect(TokenType.RPAREN, "Expected ')' after the STRING_AGG arguments");
            }
        }
        return finishAggregate(AggregateOperator.STRING_AGG, argument, Optional.of(separator), distinct, orderBy);
    }

    /**
     * The rest of an aggregate after its parenthesis: an optional {@code FILTER} and an
     * optional {@code → alias}.
     */
    private AggregateFunction finishAggregate(AggregateOperator operator, Operand argument,
                                              Optional<Operand> yieldExpr, boolean distinct,
                                              List<SortSpecification> orderBy) {
        // SQL's AGG(x) FILTER (WHERE c): only the rows where c holds are read, written as
        // the portable AGG(IIf(c, x, NULL)) — an aggregate skips a NULL, so COUNT is 0
        // and the rest NULL over a group in which no row passes. ARGMAX/ARGMIN filter
        // their ranking value, which they skip a NULL of too.
        if (isWord(current, "FILTER") && peek(1).type() == TokenType.LPAREN && isWord(peek(2), "WHERE")) {
            Token filterTok = current;
            if (operator == AggregateOperator.COLLECT) {
                throw ParseException.at("COLLECT keeps NULLs, so it takes no FILTER: filter the"
                        + " rows first, as in γ k, COLLECT(x) (σ c (R))", lexer.input(), filterTok);
            }
            advance(); // FILTER
            advance(); // (
            advance(); // WHERE
            Predicate condition = parsePredicate();
            expect(TokenType.RPAREN, "Expected ')' after the FILTER condition");
            SourceLocation at = loc(filterTok);
            argument = new FunctionCall("IIf", List.of(new ConditionOperand(condition, at), argument,
                    new NullOperand(at)), at);
        }

        Optional<String> alias = Optional.empty();
        if (current.type() == TokenType.ARROW) {
            advance();
            alias = Optional.of(expectName("Expected alias name after '→'").lexeme());
        }

        return new AggregateFunction(operator, argument, yieldExpr, alias, distinct, orderBy);
    }

    /**
     * Parses SQL's {@code GROUPING(k, …)}, or SQL Server's and Oracle's
     * {@code GROUPING_ID(k, …)}, which is the same: the keys it names, held as an
     * {@link ArrayConstruction}. Whether each is a grouping key is checked when the
     * query is analysed.
     */
    private AggregateFunction parseGroupingIndicator(Token nameTok) {
        expect(TokenType.LPAREN, "Expected '(' after " + nameTok.lexeme());
        List<Operand> keys = new ArrayList<>();
        do {
            keys.add(parseOperand());
        } while (match(TokenType.COMMA));
        expect(TokenType.RPAREN, "Expected ')' after the " + nameTok.lexeme() + " keys");
        if (isWord(current, "FILTER")) {
            throw error(current, "GROUPING reads no rows, so it takes no FILTER");
        }
        return finishAggregate(AggregateOperator.GROUPING, new ArrayConstruction(keys, loc(nameTok)),
                Optional.empty(), false, List.of());
    }

    /**
     * Parses a percentile: SQL's {@code PERCENTILE_CONT(p) WITHIN GROUP (ORDER BY x)}, or
     * the shorter {@code PERCENTILE_CONT(p, x)}. The tree holds {@code x} as the argument
     * and the fraction {@code p} as the second one. A descending order reads the
     * fraction from the other end, which for the continuous percentile is the fraction
     * {@code 1 - p}; the discrete one has no such reading, and is refused.
     */
    private AggregateFunction parsePercentile(AggregateOperator operator, Token nameTok) {
        String name = nameTok.lexeme();
        expect(TokenType.LPAREN, "Expected '(' after " + name);
        Operand fraction = parseOperand();
        Operand argument;
        if (match(TokenType.COMMA)) {
            argument = parseOperand();
            expect(TokenType.RPAREN, "Expected ')' after the " + name + " argument");
        } else {
            expect(TokenType.RPAREN, "Expected ')' after the " + name + " fraction");
            expect(TokenType.WITHIN, "Expected 'WITHIN GROUP (ORDER BY …)' after " + name + "(…),"
                    + " or write the value second: " + name + "(p, x)");
            expect(TokenType.AGGREGATION, "Expected 'GROUP' after 'WITHIN'");
            expect(TokenType.LPAREN, "Expected '(' after 'WITHIN GROUP'");
            expect(TokenType.ORDER, "Expected 'ORDER BY' in WITHIN GROUP (…)");
            expect(TokenType.BY, "Expected 'BY' after 'ORDER'");
            SortSpecification key = parseSortSpecification();
            expect(TokenType.RPAREN, "Expected ')' after the WITHIN GROUP ordering");
            argument = key.expression();
            if (key.direction() == SortDirection.DESC) {
                if (operator == AggregateOperator.PERCENTILE_DISC) {
                    throw error(nameTok, "PERCENTILE_DISC takes an ascending order;"
                            + " ask for the fraction from the other end instead");
                }
                fraction = new BinaryArithmeticExpression(new NumberOperand("1", loc(nameTok)),
                        ArithmeticOperator.MINUS, fraction, loc(nameTok));
            }
        }
        return finishAggregate(operator, argument, Optional.of(fraction), false, List.of());
    }

    private boolean startsRenameAttributeList() {
        if (current.type() != TokenType.LPAREN) {
            return false;
        }

        int offset = 1;

        int consumed = scanRenameListElement(offset);
        if (consumed < 0) {
            return false;
        }
        offset = consumed;

        while (peek(offset).type() == TokenType.COMMA) {
            offset++;
            consumed = scanRenameListElement(offset);
            if (consumed < 0) {
                return false;
            }
            offset = consumed;
        }

        if (peek(offset).type() != TokenType.RPAREN) {
            return false;
        }
        offset++;

        return peek(offset).type() == TokenType.LPAREN;
    }

    /**
     * Lookahead for a single rename-list element at {@code offset} — a bare name, or
     * an {@code old → new} pair. Returns the offset just past the element, or
     * {@code -1} if no valid element starts there.
     */
    private int scanRenameListElement(int offset) {
        if (!isNameToken(peek(offset))) {
            return -1;
        }
        offset++;
        if (peek(offset).type() == TokenType.ARROW) {
            offset++;
            if (!isNameToken(peek(offset))) {
                return -1;
            }
            offset++;
        }
        return offset;
    }

    private RelNode parseParenthesizedRelation(String message) {
        expect(TokenType.LPAREN, message);
        RelNode input = parseExpression(0);
        expect(TokenType.RPAREN, "Expected ')' after relational expression");
        return input;
    }

    private ProjectionOperands parseProjectionOperandList() {
        List<ProjectedAttribute> operands = new ArrayList<>();
        operands.add(parseProjectedAttribute());
        boolean hadComma = false;

        while (match(TokenType.COMMA)) {
            hadComma = true;
            rejectStarItem();
            operands.add(parseProjectedAttribute());
        }

        return new ProjectionOperands(List.copyOf(operands), hadComma);
    }

    private ProjectedAttribute parseProjectedAttribute() {
        int start = consumed.size();
        Operand expression = parseOperand();
        rejectForeignAlias(start, this::parseOperand);

        if (match(TokenType.ARROW)) {
            Token aliasToken = expectName("Expected alias after '→'");
            return ProjectedAttribute.aliased(expression, aliasToken.lexeme());
        }

        return ProjectedAttribute.simple(expression);
    }

    /**
     * Refuses a column named in another language's spelling, after the item read from
     * {@code consumed[start]} on: SQL's {@code expr AS name}, and {@code name = expr} or
     * {@code name: expr} where the item is one name. None of them parses, and the
     * message the next token would get describes the list it ends, not the rename, so
     * the error names the {@code expr -> name} to write, in the reader's own text.
     * {@code value} reads what follows {@code =} or {@code :}.
     */
    private void rejectForeignAlias(int start, Runnable value) {
        Token sign = current;
        if (sign.type() == TokenType.AS && isNameToken(peek(1))) {
            String item = writtenFrom(start);
            throw ParseException.at("a column is named with expr -> name, and AS is SQL's"
                    + " spelling of '->': write " + item + " -> " + peek(1).lexeme(),
                    lexer.input(), sign);
        }
        if ((sign.type() == TokenType.EQUAL || sign.type() == TokenType.COLON)
                && consumed.size() - start == 1 && isNameToken(consumed.get(start))) {
            String name = consumed.get(start).lexeme();
            advance();
            int from = consumed.size();
            value.run();
            String item = writtenFrom(from);
            String written = sign.type() == TokenType.EQUAL ? "name = expr" : "name: expr";
            throw ParseException.at("a column is named with expr -> name, not " + written
                    + ": write " + item + " -> " + name, lexer.input(), sign);
        }
    }

    // =========================================================================
    // Predicate parsing
    // =========================================================================

    /**
     * Parses a single function-call argument, which may be either a plain
     * (arithmetic) operand or a boolean <em>condition</em> — a comparison /
     * IN / LIKE / null test, optionally combined with AND/OR/NOT. A condition is
     * wrapped in a {@link ConditionOperand} so it can occupy operand position;
     * this is what makes {@code IIf(price > 100, "a", "b")} — and every predicate
     * form the reference documents for a conditional's test — parse.
     *
     * <p>A leading {@code NOT} or a parenthesized predicate can only be a
     * condition, so it is parsed as one directly. Otherwise an operand is parsed
     * first and escalated to a condition only when a comparison-style operator
     * follows (a bare operand argument stays an operand).
     */
    private Operand parseArgument() {
        Token start = current;
        if (current.type() == TokenType.NOT
                || (current.type() == TokenType.LPAREN && isParenthesizedPredicate())) {
            return new ConditionOperand(parsePredicate(), loc(start));
        }
        Operand op = parseOperand();
        if (startsConditionTail()) {
            return new ConditionOperand(parseOrPredicate(op), op.location());
        }
        return op;
    }

    /**
     * True when the token after a parsed operand begins a boolean condition tail —
     * a comparison operator, set membership (∈/∉/IN), LIKE, or their negated
     * two-token forms. (A trailing {@code = ⊥} null test is covered by the
     * comparison operators.)
     */
    private boolean startsConditionTail() {
        TokenType t = current.type();
        if (isComparison(t)) return true;
        if (t == TokenType.ELEMENT_OF || t == TokenType.NOT_ELEMENT_OF
                || t == TokenType.LIKE || t == TokenType.IS || isWord(current, "BETWEEN")
                || startsRegexMatch()) return true;
        return t == TokenType.NOT
                && (peek(1).type() == TokenType.ELEMENT_OF
                    || peek(1).type() == TokenType.LIKE || isWord(peek(1), "BETWEEN"));
    }

    /** True when {@code token} is the unreserved word {@code word}, in any case. */
    private boolean isWord(Token token, String word) {
        return isNameToken(token) && token.lexeme().equalsIgnoreCase(word);
    }

    private Predicate parsePredicate() {
        return parseOrPredicate(null);
    }

    /**
     * Parses a boolean expression, optionally seeded with an already-parsed
     * left operand. The {@code seed} threads a pre-parsed operand into the
     * innermost primary so a function argument can be parsed as an operand first
     * and then escalated to a condition when a comparison follows (see
     * {@link #parseArgument()}); {@code null} means "parse fresh" (every existing
     * caller). Only the first primary is seeded — subsequent AND/OR operands are
     * parsed normally.
     */
    private Predicate parseOrPredicate(Operand seed) {
        Predicate left = parseAndPredicate(seed);

        while (true) {
            if (current.type() != TokenType.OR) break;
            Token opTok = current;
            advance();
            Predicate right = parseAndPredicate(null);
            left = new OrPredicate(left, right, loc(opTok));
        }

        return left;
    }

    private Predicate parseAndPredicate(Operand seed) {
        Predicate left = parseNotPredicate(seed);

        while (true) {
            if (current.type() != TokenType.AND) break;
            Token opTok = current;
            advance();
            Predicate right = parseNotPredicate(null);
            left = new AndPredicate(left, right, loc(opTok));
        }

        return left;
    }

    private Predicate parseNotPredicate(Operand seed) {
        if (seed == null && current.type() == TokenType.NOT) {
            Token notTok = current;
            advance();
            return new NotPredicate(parseNotPredicate(null), loc(notTok));
        }

        return parsePrimaryPredicate(seed);
    }

    private Predicate parsePrimaryPredicate(Operand seed) {
        if (seed == null && current.type() == TokenType.LPAREN && isParenthesizedPredicate()) {
            Token open = current;
            advance();
            Predicate predicate = parsePredicate();
            rejectRightInputInCondition(open);
            expect(TokenType.RPAREN, "Expected ')' after predicate");
            // A parenthesised predicate followed by a condition tail is not the
            // predicate itself but its *truth value* in operand position:
            // `(a > b) = ⊥` asks whether the comparison came out UNKNOWN, which is
            // a different question from `a > b`. This is the same escalation
            // parseArgument already performs for a function argument, and without it
            // the grammar cannot read back a tree AstBuilders can build.
            if (startsConditionTail()) {
                return parsePrimaryPredicate(new ConditionOperand(predicate, loc(open)));
            }
            return predicate;
        }

        int leftStart = seed != null ? -1 : consumed.size();
        Operand left = seed != null ? seed : parseOperand();
        int leftEnd = consumed.size();

        // Check for element-of predicates (∈, ∉, IN, or NOT IN)
        if (current.type() == TokenType.ELEMENT_OF || current.type() == TokenType.NOT_ELEMENT_OF) {
            Token opTok = current;
            boolean isNegated = opTok.type() == TokenType.NOT_ELEMENT_OF;
            advance();
            rejectSubquery(leftStart, leftEnd, isNegated);

            Operand setExpr = parseSetExpression();
            return new ElementOfPredicate(left, setExpr, isNegated, loc(opTok));
        }

        if (current.type() == TokenType.NOT && peek(1).type() == TokenType.ELEMENT_OF) {
            Token notTok = current;
            advance(); // consume NOT
            advance(); // consume IN
            rejectSubquery(leftStart, leftEnd, true);
            Operand setExpr = parseSetExpression();
            return new ElementOfPredicate(left, setExpr, true, loc(notTok));
        }

        // SQL's x [NOT] BETWEEN lo AND hi, read as the two comparisons it means
        if (isWord(current, "BETWEEN")
                || current.type() == TokenType.NOT && isWord(peek(1), "BETWEEN")) {
            return parseBetween(left);
        }

        if (startsRegexMatch()) {
            return parseRegexMatch(left);
        }

        // Check for LIKE / NOT LIKE
        if (current.type() == TokenType.LIKE) {
            Token opTok = current;
            advance();
            Operand pattern = parseOperand();
            return new PatternPredicate(left, pattern, false, parseLikeEscape(), loc(opTok));
        }

        if (current.type() == TokenType.NOT && peek(1).type() == TokenType.LIKE) {
            Token notTok = current;
            advance(); // consume NOT
            advance(); // consume LIKE
            Operand pattern = parseOperand();
            return new PatternPredicate(left, pattern, true, parseLikeEscape(), loc(notTok));
        }

        // Check for null predicate (attribute = ⊥ or attribute ≠ ⊥)
        if ((current.type() == TokenType.EQUAL || current.type() == TokenType.NOT_EQUAL) && peek(1).type() == TokenType.NULL) {
            Token opTok = current;
            advance(); // consume operator
            advance(); // consume ⊥
            boolean isNull = opTok.type() == TokenType.EQUAL;
            return new NullPredicate(left, isNull, loc(opTok));
        }

        // SQL-prior null predicate: `x IS NULL` / `x IS NOT NULL`,
        // parsing to the same NullPredicate as `x = ⊥` / `x ≠ ⊥`.
        if (current.type() == TokenType.IS) {
            Token opTok = current;
            advance(); // consume IS
            boolean isNull = true;
            if (current.type() == TokenType.NOT) {
                advance(); // consume NOT
                isNull = false;
            }
            if (current.type() == TokenType.DISTINCT && isWord(peek(1), "FROM")) {
                advance(); // consume DISTINCT
                advance(); // consume FROM
                Operand right = parseOperand();
                return isNull ? distinctFrom(left, right, loc(opTok))
                        : notDistinctFrom(left, right, loc(opTok));
            }
            expect(TokenType.NULL, "Expected 'NULL' or 'DISTINCT FROM' after 'IS'");
            return new NullPredicate(left, isNull, loc(opTok));
        }

        if (!isComparison(current.type())) {
            rejectColumnListAfterSelect(leftStart);
            throw error(current, "Expected comparison operator");
        }

        Token opToken = current;
        ComparisonOperator operator = switch (current.type()) {
            case EQUAL         -> ComparisonOperator.EQUAL;
            case NOT_EQUAL     -> ComparisonOperator.NOT_EQUAL;
            case LESS          -> ComparisonOperator.LESS;
            case LESS_EQUAL    -> ComparisonOperator.LESS_EQUAL;
            case GREATER       -> ComparisonOperator.GREATER;
            case GREATER_EQUAL -> ComparisonOperator.GREATER_EQUAL;
            default -> throw error(current, "Expected comparison operator");
        };

        advance();
        Operand right = parseOperand();
        return new ComparisonPredicate(left, operator, right, loc(opToken));
    }

    /** Whether a regular-expression match follows: {@code ~}, {@code !~} (each with {@code *}), or {@code [NOT] MATCHES}. */
    private boolean startsRegexMatch() {
        return current.type() == TokenType.REGEX_MATCH || current.type() == TokenType.REGEX_NOT_MATCH
                || isWord(current, "MATCHES")
                || current.type() == TokenType.NOT && isWord(peek(1), "MATCHES");
    }

    /**
     * Parses a regular-expression match, {@code s ~ p}, {@code s ~* p} (ignoring case),
     * {@code s !~ p}, {@code s !~* p}, {@code s MATCHES p} or {@code s NOT MATCHES p},
     * into the test it is: {@code REGEXP_LIKE(s, p) = TRUE}, or {@code = FALSE} for a
     * negation, so a NULL string or pattern is UNKNOWN either way, as in SQL.
     * {@code MATCHES} is matched by its text, so it is not reserved.
     */
    private Predicate parseRegexMatch(Operand subject) {
        Token op = current;
        boolean negated = op.type() == TokenType.REGEX_NOT_MATCH || op.type() == TokenType.NOT;
        boolean ignoreCase = op.lexeme().endsWith("*");
        advance();
        if (op.type() == TokenType.NOT) {
            advance(); // MATCHES
        }
        Operand pattern = parseOperand();
        SourceLocation at = loc(op);
        List<Operand> arguments = ignoreCase
                ? List.of(subject, pattern, new StringOperand("i", at))
                : List.of(subject, pattern);
        return new ComparisonPredicate(new FunctionCall("REGEXP_LIKE", arguments, at),
                ComparisonOperator.EQUAL, new BooleanOperand(!negated, at), at);
    }

    /**
     * Refuses SQL's {@code SELECT} column list: {@code SELECT 'TOTAL' -> Item, SUM(v) -> v (R)}.
     *
     * <p>{@code SELECT} is σ, a filter, so its condition was being read and the
     * comparison it needs is missing. Where a {@code ->} or a {@code ,} stands in the
     * comparison's place, the condition was never a condition: it is a column list, and
     * saying "expected comparison operator" sends the writer to the literal or the arrow.
     * The error names the operator that chooses columns instead.
     *
     * <p>Where the whole condition is one operand, from {@code consumed[leftStart]}, and a
     * relation in parentheses follows it, {@code SELECT x (R)} is SQL's {@code SELECT x
     * FROM R}: the error echoes it as {@code PROJECT x (R)}, or as the GROUP an aggregate
     * needs.
     */
    private void rejectColumnListAfterSelect(int leftStart) {
        if (selection == null) {
            return;
        }
        boolean whole = leftStart == selectionAt;
        boolean input = current.type() == TokenType.LPAREN && whole;
        boolean sql = whole && (current.type() == TokenType.AS && isNameToken(peek(1))
                || isWord(current, "FROM"));
        if (!input && !sql && current.type() != TokenType.ARROW && current.type() != TokenType.COMMA) {
            return;
        }
        Token sign = current;
        Token select = selection;
        if (input && callsBetween(selectionAt, consumed.size()).isEmpty()) {
            rejectProjectionAfterSelect(select, sign);
            return;
        }
        if (callsBetween(selectionAt, consumed.size()).isEmpty()) {
            String written = select.lexeme().equals("σ") ? "'σ'" : "'" + select.lexeme() + "' (σ)";
            String rewrite = whole ? selectListAsProjection(select, sign) : null;
            throw ParseException.at(written + " filters rows by a"
                    + " condition, and found '" + sign.lexeme() + "' where its comparison"
                    + " belongs. To choose or compute columns, use PROJECT (π): "
                    + (rewrite != null ? "write " + rewrite : "PROJECT a, b * 2 -> c (R)"),
                    lexer.input(), sign);
        }
        // An aggregate before the '->' or ',': GROUP computes it, which PROJECT cannot.
        int itemsStart = selectionAt;
        String fix = null;
        Token at = sign;
        try {
            if (match(TokenType.ARROW)) {
                expectName("Expected alias after '→'");
            }
            if (match(TokenType.COMMA)) {
                parseProjectionOperandList();
            }
            int itemsEnd = consumed.size();
            int inputStart = consumed.size();
            parseParenthesizedRelation("Expected '(' after the columns");
            fix = groupForm(itemsStart, itemsEnd, writtenFrom(inputStart), false, isGlyph(select));
            at = firstStar(itemsStart, sign);
        } catch (ParseException e) {
            // The rest does not read as columns and an input: say only where aggregates go.
        }
        throw ParseException.at("'" + select.lexeme() + "' filters rows; an aggregate belongs in "
                + (isGlyph(select) ? "γ" : "GROUP") + ": write "
                + (fix != null ? fix : (isGlyph(select) ? "γ" : "GROUP") + " AVG(x) -> avg (R)"),
                lexer.input(), at);
    }

    /**
     * The PROJECT that SQL's select list means, read on from the condition's first
     * operand, at {@code sign}: its items, each {@code AS} written {@code ->}, then its
     * input, in parentheses or after SQL's {@code FROM}, with a {@code WHERE} written as
     * the SELECT it is. {@code SELECT a AS x, b FROM T WHERE c} is {@code PROJECT a -> x,
     * b (SELECT c (T))}. An input after {@code FROM} that does not parse is echoed as
     * written, so its own error comes next. Null when the items do not read as columns.
     * An aggregate among them is refused here, naming GROUP.
     */
    private String selectListAsProjection(Token select, Token sign) {
        boolean glyph = isGlyph(select);
        String arrow = glyph ? " → " : " -> ";
        int itemsStart = selectionAt;
        StringBuilder items = new StringBuilder(writtenFrom(itemsStart));
        boolean sqlAlias = false;
        int itemsEnd;
        String input;
        selection = null;
        try {
            while (true) {
                if (current.type() == TokenType.AS || current.type() == TokenType.ARROW) {
                    sqlAlias |= current.type() == TokenType.AS;
                    advance();
                    items.append(arrow).append(expectName("Expected a column name").lexeme());
                }
                if (!match(TokenType.COMMA)) {
                    break;
                }
                int item = consumed.size();
                parseOperand();
                items.append(", ").append(writtenFrom(item));
            }
            itemsEnd = consumed.size();
            if (isWord(current, "FROM")) {
                advance();
                input = "(" + sqlFromClause(glyph) + ")";
            } else {
                int start = consumed.size();
                parseParenthesizedRelation("Expected '(' after the columns");
                input = writtenFrom(start);
            }
        } catch (ParseException e) {
            return null;
        }
        if (!callsBetween(itemsStart, itemsEnd).isEmpty()) {
            String group = glyph ? "γ" : "GROUP";
            String fix = sqlAlias ? null : groupForm(itemsStart, itemsEnd, input, false, glyph);
            throw ParseException.at("'" + select.lexeme() + "' filters rows; an aggregate belongs in "
                    + group + ": write " + (fix != null ? fix : group + " AVG(x)" + arrow + "avg " + input),
                    lexer.input(), firstStar(itemsStart, sign));
        }
        return (glyph ? "π " : "PROJECT ") + items + " " + input;
    }

    /**
     * SQL's {@code FROM} clause, read after its {@code FROM}: the relation, or
     * {@code SELECT c (R)} for {@code R WHERE c}. When it does not parse, the rest of the
     * expression up to the parenthesis that closes it, as written.
     */
    private String sqlFromClause(boolean glyph) {
        int start = consumed.size();
        try {
            parseExpression(0);
            String from = writtenFrom(start);
            if (!isWord(current, "WHERE")) {
                return from;
            }
            advance();
            int condition = consumed.size();
            parsePredicate();
            return (glyph ? "σ " : "SELECT ") + writtenFrom(condition) + " (" + from + ")";
        } catch (ParseException e) {
            int depth = 0;
            for (Token token : consumed.subList(start, consumed.size())) {
                depth += token.type() == TokenType.LPAREN ? 1 : token.type() == TokenType.RPAREN ? -1 : 0;
            }
            while (current.type() != TokenType.EOF && (current.type() != TokenType.RPAREN || depth > 0)) {
                depth += current.type() == TokenType.LPAREN ? 1 : current.type() == TokenType.RPAREN ? -1 : 0;
                advance();
            }
            return writtenFrom(start);
        }
    }

    /**
     * Refuses {@code SELECT x (R)}, a column or expression and then an input where the
     * comparison belongs, at the {@code (}, naming {@code PROJECT x (R)} as written, or
     * {@code PROJECT x + 1 -> c (R)} for an expression, which needs a name. Where the
     * input does not read as a relation, the plain error stands.
     */
    private void rejectProjectionAfterSelect(Token select, Token paren) {
        boolean glyph = isGlyph(select);
        String column = writtenFrom(selectionAt)
                + (isColumnName(selectionAt, consumed.size()) ? "" : (glyph ? " → c" : " -> c"));
        int inputStart = consumed.size();
        try {
            parseParenthesizedRelation("Expected '(' after the column");
        } catch (ParseException e) {
            throw error(paren, "Expected comparison operator");
        }
        String written = glyph ? "'σ'" : "'" + select.lexeme() + "' (σ)";
        throw ParseException.at(written + " filters rows by a condition, and found '('"
                + " where its comparison belongs. To choose columns, use PROJECT (π): write "
                + (glyph ? "π " : "PROJECT ") + column + " " + writtenFrom(inputStart),
                lexer.input(), paren);
    }

    /**
     * Refuses {@code COUNT(*)} read outside GROUP in a PROJECT's items or a SELECT's
     * condition, between {@code start} and {@code end}, at its {@code *}, naming the GROUP
     * that computes it with the operator's own items and input: {@code GROUP COUNT(*) -> n
     * (R)}, or {@code PROJECT s / n -> avg (GROUP SUM(x) -> s, COUNT(*) -> n (R))}.
     */
    private void rejectCountStar(int start, int end, int inputStart, boolean condition, boolean glyph) {
        Token star = firstStar(start, null);
        if (star == null) {
            return;
        }
        String group = glyph ? "γ" : "GROUP";
        String fix = groupForm(start, end, writtenFrom(inputStart), condition, glyph);
        throw ParseException.at("COUNT(*) is an aggregate and belongs in " + group + ": write "
                + (fix != null ? fix : group + " COUNT(*) -> n " + writtenFrom(inputStart)),
                lexer.input(), star);
    }

    /**
     * Refuses an aggregate called in relation position, {@code COUNT(*) (R)}, naming the
     * GROUP that computes it: {@code GROUP COUNT(*) -> n (R)}. Left alone, it is read as
     * a table-valued function call, whose {@code *} or input does not parse.
     */
    private void rejectAggregateAsRelation(Token name) {
        int start = consumed.size() - 1;
        boolean enclosingDefer = deferAggregateStar;
        deferAggregateStar = true;
        try {
            parseOperandFromNameToken(name);
        } finally {
            deferAggregateStar = enclosingDefer;
        }
        int end = consumed.size();
        String call = writtenBetween(start, end);
        String fix = null;
        if (current.type() == TokenType.LPAREN) {
            int inputStart = consumed.size();
            try {
                parseParenthesizedRelation("Expected '(' after the aggregate");
                fix = groupForm(start, end, writtenFrom(inputStart), false, false);
            } catch (ParseException e) {
                // No input to echo: the plain form below.
            }
        }
        throw ParseException.at(call + " is an aggregate and belongs in GROUP: write "
                + (fix != null ? fix : "GROUP " + call + " -> " + aggregateName(start, Set.of()) + " (R)"),
                lexer.input(), firstStar(start, name));
    }

    /**
     * Refuses {@code *} given to an aggregate that is not COUNT: {@code SUM(*)} has no
     * column to add up. Inside a GROUP that starts at {@code consumed[groupStart]}, read
     * to the end of its input, the GROUP is echoed with a column in its place; else
     * {@code -1}.
     */
    private void rejectStarArgument(Token star, int groupStart) {
        int starAt = consumed.indexOf(star);
        Token name = consumed.get(starAt < 0 ? consumed.size() - 2 : starAt - 2);
        String fn = name.lexeme();
        if (fn.equalsIgnoreCase("COUNT")) {
            throw ParseException.at("COUNT(*) is an aggregate and belongs in GROUP: write"
                    + " GROUP COUNT(*) -> n (R)", lexer.input(), star);
        }
        String problem = fn + " needs a column, and only COUNT takes *";
        if (groupStart < 0) {
            throw ParseException.at(problem + ": write " + fn + "(column), or COUNT(*) to count rows",
                    lexer.input(), star);
        }
        throw ParseException.at(problem + ": write " + writtenBetween(groupStart, starAt) + "column"
                + writtenFrom(starAt + 1) + ", naming the column, or COUNT(*) to count rows",
                lexer.input(), star);
    }

    /**
     * Refuses SQL's {@code SELECT *} or {@code PROJECT *} at the {@code *}: a relation
     * already has every column, so the relation alone is meant. After SQL's {@code FROM},
     * {@code SELECT * FROM T WHERE c} is {@code SELECT c (T)}, and without a
     * {@code WHERE} it is {@code T}.
     */
    private void rejectEveryColumn(Token opTok) {
        if (current.type() != TokenType.MULTIPLY
                || peek(1).type() != TokenType.LPAREN && !isWord(peek(1), "FROM")) {
            return;
        }
        Token star = current;
        advance();
        if (isWord(current, "FROM")) {
            advance();
            String from = sqlFromClause(isGlyph(opTok));
            throw ParseException.at("Relix has no '*' column list, and a relation already has all"
                    + " its columns; SQL's FROM is the input: write " + from, lexer.input(), star);
        }
        String relation = "R";
        int start = consumed.size();
        try {
            parseParenthesizedRelation("Expected '(' after '*'");
            relation = writtenBetween(start + 1, consumed.size() - 1);
        } catch (ParseException e) {
            // Nothing to echo: R stands for it.
        }
        boolean filter = opTok.type() == TokenType.SELECT;
        throw ParseException.at("Relix has no '*' column list, and a relation already has all its"
                + " columns: write " + relation
                + (filter ? ", or " + opTok.lexeme() + " a condition (" + relation + ")" : ""),
                lexer.input(), star);
    }

    /**
     * Refuses {@code *} for "every column" after a qualifier, {@code PROJECT i.* (R)}, at
     * the {@code *}, which {@code qualifier} was read up to: no operator takes a column
     * list of {@code *}, and PROJECT names the columns it keeps.
     */
    private void rejectQualifiedStar(CharSequence qualifier) {
        if (current.type() != TokenType.MULTIPLY) {
            return;
        }
        String of = qualifier.toString().substring(0, qualifier.length() - 1);
        throw ParseException.at("Relix has no '*' column list: PROJECT names the columns it keeps."
                + " To keep all of " + of + "'s columns, leave PROJECT out, or list them",
                lexer.input(), current);
    }

    /**
     * Refuses {@code *} as a later item of a column list, {@code PROJECT a, * (R)}, at
     * the {@code *}.
     */
    private void rejectStarItem() {
        if (current.type() != TokenType.MULTIPLY || peek(1).type() != TokenType.COMMA
                && peek(1).type() != TokenType.LPAREN && !isWord(peek(1), "FROM")) {
            return;
        }
        throw ParseException.at("Relix has no '*' column list: PROJECT names the columns it keeps."
                + " List them, or leave PROJECT out to keep them all", lexer.input(), current);
    }

    /**
     * The aggregate calls read between two indices of {@code consumed}, outermost only, in
     * order. A call is recorded when it closes, so after any call inside it.
     */
    private List<int[]> callsBetween(int start, int end) {
        List<int[]> calls = new ArrayList<>();
        for (int[] call : aggregateCalls) {
            if (call[0] >= start && call[1] <= end) {
                calls.removeIf(inner -> call[0] <= inner[0]);
                calls.add(call);
            }
        }
        return calls;
    }

    /**
     * The first {@code *} given to an aggregate from {@code consumed[start]} on; else
     * {@code otherwise}. One in a relation read since is reported by that relation's own
     * operator, so none is past the range asked about.
     */
    private Token firstStar(int start, Token otherwise) {
        for (int[] call : aggregateCalls) {
            if (call[2] >= start) {
                return consumed.get(call[2]);
            }
        }
        return otherwise;
    }

    /**
     * Relix's form of items that use aggregates outside GROUP, read from
     * {@code consumed[start]} to {@code consumed[end]}, over {@code input} as written.
     * Plain columns become GROUP's keys and aggregates its aggregates, named by their
     * item's alias or else {@code n} for COUNT and an initial otherwise; an item that
     * computes with aggregates, or a condition, is then written over the GROUP:
     * {@code PROJECT s / n -> avg (GROUP SUM(x) -> s, COUNT(*) -> n (R))}. Null when an
     * item computes with a column, which would need to be a key.
     */
    private String groupForm(int start, int end, String input, boolean condition, boolean glyph) {
        List<int[]> calls = callsBetween(start, end);
        String arrow = glyph ? " → " : " -> ";
        List<int[]> items = condition ? List.<int[]>of(new int[] {start, end}) : itemsBetween(start, end);
        Set<String> used = new HashSet<>();
        for (int[] item : items) {
            if (item[1] - item[0] >= 3 && consumed.get(item[1] - 2).type() == TokenType.ARROW) {
                used.add(consumed.get(item[1] - 1).lexeme());
            }
        }
        List<String> keys = new ArrayList<>();
        List<String> aggregates = new ArrayList<>();
        List<String> outer = new ArrayList<>();
        boolean computed = condition;
        for (int[] item : items) {
            boolean aliased = item[1] - item[0] >= 3 && consumed.get(item[1] - 2).type() == TokenType.ARROW;
            int exprEnd = aliased ? item[1] - 2 : item[1];
            String alias = aliased ? consumed.get(item[1] - 1).lexeme() : null;
            List<int[]> own = calls.stream().filter(c -> c[0] >= item[0] && c[1] <= exprEnd).toList();
            if (own.size() == 1 && own.getFirst()[0] == item[0] && own.getFirst()[1] == exprEnd) {
                String name = alias != null ? alias : aggregateName(item[0], used);
                used.add(name);
                aggregates.add(writtenBetween(item[0], exprEnd) + arrow + name);
                outer.add(name);
            } else if (own.isEmpty()) {
                if (!isColumnName(item[0], exprEnd)) {
                    return null;
                }
                keys.add(writtenBetween(item[0], item[1]));
                outer.add(alias != null ? alias : writtenBetween(item[0], exprEnd));
            } else {
                computed = true;
                StringBuilder text = new StringBuilder();
                Token previous = null;
                for (int i = item[0]; i < exprEnd; i++) {
                    int index = i;
                    int[] call = own.stream().filter(c -> c[0] == index).findFirst().orElse(null);
                    Token token = consumed.get(i);
                    if (previous != null && spaced(previous, token)) {
                        text.append(' ');
                    }
                    if (call != null) {
                        String name = aggregateName(call[0], used);
                        used.add(name);
                        aggregates.add(writtenBetween(call[0], call[1]) + arrow + name);
                        text.append(name);
                        previous = consumed.get(call[1] - 1);
                        i = call[1] - 1;
                    } else if (token.type() == TokenType.IDENTIFIER
                            && consumed.get(i + 1).type() != TokenType.LPAREN) {
                        return null; // A column outside an aggregate, which would need to be a key.
                    } else {
                        text.append(token.lexeme());
                        previous = token;
                    }
                }
                outer.add(alias != null ? text + arrow + alias : text.toString());
            }
        }
        if (aggregates.isEmpty()) {
            return null;
        }
        List<String> grouped = new ArrayList<>(keys);
        grouped.addAll(aggregates);
        String group = (glyph ? "γ " : "GROUP ") + String.join(", ", grouped) + " " + input;
        if (!computed) {
            return group;
        }
        String op = condition ? (glyph ? "σ " : "SELECT ") : (glyph ? "π " : "PROJECT ");
        return op + String.join(", ", outer) + " (" + group + ")";
    }

    /** The items of a list between two indices of {@code consumed}, split at its own commas. */
    private List<int[]> itemsBetween(int start, int end) {
        List<int[]> items = new ArrayList<>();
        int depth = 0;
        int from = start;
        for (int i = start; i < end; i++) {
            switch (consumed.get(i).type()) {
                case LPAREN, LBRACE, LBRACKET -> depth++;
                case RPAREN, RBRACE, RBRACKET -> depth--;
                case COMMA -> {
                    if (depth == 0) {
                        items.add(new int[] {from, i});
                        from = i + 1;
                    }
                }
                default -> { }
            }
        }
        items.add(new int[] {from, end});
        return items;
    }

    /** Whether the tokens between two indices of {@code consumed} are one column's name, dotted or not. */
    private boolean isColumnName(int start, int end) {
        for (int i = start; i < end; i++) {
            boolean name = (i - start) % 2 == 0;
            if (name ? !isNameToken(consumed.get(i)) : consumed.get(i).type() != TokenType.DOT) {
                return false;
            }
        }
        return (end - start) % 2 == 1;
    }

    /** A name for the aggregate called at {@code consumed[call]}: n for COUNT, else its initial; not one of {@code used}. */
    private String aggregateName(int call, Set<String> used) {
        String fn = consumed.get(call).lexeme();
        String base = fn.equalsIgnoreCase("COUNT") ? "n" : fn.substring(0, 1).toLowerCase(java.util.Locale.ROOT);
        String name = base;
        for (int i = 2; used.contains(name); i++) {
            name = base + i;
        }
        return name;
    }

    /** Whether {@code name} is an aggregate's, in any case: a keyword aggregate or a named one. */
    private static boolean isAggregateName(String name) {
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        return Set.of("SUM", "AVG", "COUNT", "MIN", "MAX", "COLLECT", "ARGMAX", "ARGMIN").contains(upper)
                || NAMED_AGGREGATES.containsKey(upper);
    }

    /**
     * Parses {@code [NOT] BETWEEN lo AND hi} after its subject, as the comparisons it
     * stands for: {@code x >= lo ∧ x <= hi}, and for {@code NOT BETWEEN}
     * {@code x < lo ∨ x > hi}. Both bounds are inclusive and the subject is read
     * twice, which is SQL's definition.
     */
    private Predicate parseBetween(Operand subject) {
        Token opTok = current;
        boolean negated = match(TokenType.NOT);
        advance(); // consume BETWEEN
        Operand low = parseOperand();
        expect(TokenType.AND, "Expected 'AND' between the two bounds of BETWEEN");
        Operand high = parseOperand();
        SourceLocation at = loc(opTok);
        if (negated) {
            return new OrPredicate(
                    new ComparisonPredicate(subject, ComparisonOperator.LESS, low, at),
                    new ComparisonPredicate(subject, ComparisonOperator.GREATER, high, at), at);
        }
        return new AndPredicate(
                new ComparisonPredicate(subject, ComparisonOperator.GREATER_EQUAL, low, at),
                new ComparisonPredicate(subject, ComparisonOperator.LESS_EQUAL, high, at), at);
    }

    /**
     * Parses the optional {@code ESCAPE 'c'} after a LIKE pattern: one character, written
     * as a string literal.
     */
    private Optional<String> parseLikeEscape() {
        if (!isWord(current, "ESCAPE")) {
            return Optional.empty();
        }
        advance(); // consume ESCAPE
        Token escape = current;
        if (escape.type() != TokenType.STRING || escape.literal().length() != 1) {
            throw ParseException.at("ESCAPE takes one character, written as a string:"
                    + " name LIKE '50!%' ESCAPE '!'", lexer.input(), escape);
        }
        advance();
        return Optional.of(escape.literal());
    }

    /**
     * {@code a IS DISTINCT FROM b}: true when exactly one is NULL, or neither is and
     * they differ. Unlike {@code a ≠ b} it is never UNKNOWN, so it is written out in
     * null tests, which are never UNKNOWN either:
     * {@code (a ≠ b ∧ a ≠ ⊥ ∧ b ≠ ⊥) ∨ (a = ⊥ ∧ b ≠ ⊥) ∨ (a ≠ ⊥ ∧ b = ⊥)}.
     */
    private static Predicate distinctFrom(Operand a, Operand b, SourceLocation at) {
        Predicate differ = new AndPredicate(new AndPredicate(
                new ComparisonPredicate(a, ComparisonOperator.NOT_EQUAL, b, at),
                new NullPredicate(a, false, at), at), new NullPredicate(b, false, at), at);
        Predicate onlyA = new AndPredicate(new NullPredicate(a, true, at), new NullPredicate(b, false, at), at);
        Predicate onlyB = new AndPredicate(new NullPredicate(a, false, at), new NullPredicate(b, true, at), at);
        return new OrPredicate(new OrPredicate(differ, onlyA, at), onlyB, at);
    }

    /**
     * {@code a IS NOT DISTINCT FROM b}, null-safe equality: true when both are NULL, or
     * neither is and they are equal, and never UNKNOWN:
     * {@code (a = b ∧ a ≠ ⊥ ∧ b ≠ ⊥) ∨ (a = ⊥ ∧ b = ⊥)}.
     */
    private static Predicate notDistinctFrom(Operand a, Operand b, SourceLocation at) {
        Predicate equal = new AndPredicate(new AndPredicate(
                new ComparisonPredicate(a, ComparisonOperator.EQUAL, b, at),
                new NullPredicate(a, false, at), at), new NullPredicate(b, false, at), at);
        Predicate bothNull = new AndPredicate(new NullPredicate(a, true, at), new NullPredicate(b, true, at), at);
        return new OrPredicate(equal, bothNull, at);
    }

    private Operand parseSetExpression() {
        if (current.type() == TokenType.LBRACE) {
            Token lbraceTok = current;
            advance(); // consume {
            List<Operand> elements = new ArrayList<>();

            if (current.type() != TokenType.RBRACE) {
                do {
                    elements.add(parseOperand());
                } while (match(TokenType.COMMA));
            }

            expect(TokenType.RBRACE, "Expected '}' after set elements");
            return new SetLiteralOperand(elements, loc(lbraceTok));
        } else if (current.type() == TokenType.LPAREN) {
            return parseOperand();
        } else if (current.type() == TokenType.IDENTIFIER) {
            return parseOperand();
        } else {
            throw error(current, "Expected set literal {items}, '(', or a name");
        }
    }

    /**
     * Refuses SQL's subquery after {@code IN}, {@code x IN (SELECT y FROM S)} or
     * {@code x IN (SELECT y (S))}, at its {@code SELECT}, and the same subquery in a set's
     * braces, {@code x IN { SELECT y (S) }}: IN takes a set, and membership
     * in another relation is a semi-join, or an anti-join for {@code NOT IN}. When the
     * test is a selection's whole condition over a named relation, the join is written
     * out: {@code R SEMI R.x = S.y S}. The subject is {@code consumed[subjectStart]} up to
     * {@code consumed[subjectEnd]}; {@code subjectStart} is -1 when it was read elsewhere.
     */
    private void rejectSubquery(int subjectStart, int subjectEnd, boolean negated) {
        boolean braced = current.type() == TokenType.LBRACE;
        if (!braced && current.type() != TokenType.LPAREN || peek(1).type() != TokenType.SELECT) {
            return;
        }
        Token select = peek(1);
        String join = negated ? "ANTI" : "SEMI";
        String problem = "IN takes a set such as {1, 2}; to keep rows with " + (negated ? "no" : "a")
                + " match in another relation write ";
        String fix = null;
        try {
            Token enclosing = selection;
            int conditionStart = selectionAt;
            advance(); // ( or {
            advance(); // SELECT
            int columnStart = consumed.size();
            parseOperand();
            int columnEnd = consumed.size();
            String column = writtenFrom(columnStart);
            String inner;
            boolean named = true;
            if (matchWord("FROM")) {
                inner = expectName("Expected a relation after FROM").lexeme();
            } else {
                int innerStart = consumed.size();
                parseParenthesizedRelation("Expected '(' after the column");
                inner = writtenBetween(innerStart + 1, consumed.size() - 1);
                named = isColumnName(innerStart + 1, consumed.size() - 1);
            }
            if (braced) {
                expect(TokenType.RBRACE, "Expected '}' after the subquery");
            } else {
                expect(TokenType.RPAREN, "Expected ')' after the subquery");
            }
            if (enclosing != null && subjectStart == conditionStart && current.type() == TokenType.LPAREN) {
                int outerStart = consumed.size();
                parseParenthesizedRelation("Expected '(' after selection predicate");
                String outer = writtenBetween(outerStart + 1, consumed.size() - 1);
                String subject = writtenBetween(subjectStart, subjectEnd);
                if (named && isColumnName(outerStart + 1, consumed.size() - 1)
                        && isColumnName(subjectStart, subjectEnd) && isColumnName(columnStart, columnEnd)) {
                    fix = outer + " " + join + " " + (subject.contains(".") ? subject : outer + "." + subject)
                            + " = " + (column.contains(".") ? column : inner + "." + column) + " " + inner;
                }
            }
        } catch (ParseException e) {
            // Not the subquery shapes this reads: the general form below.
        }
        throw ParseException.at(problem + (fix != null ? fix : "R " + join + " R.x = S.y S"),
                lexer.input(), select);
    }

    /**
     * True when the parenthesis at {@code current} opens a predicate rather than an
     * arithmetic group: a comparison, logical, membership, LIKE or IS operator appears
     * inside it before it closes.
     *
     * <p>An operator inside a function call's own argument list does not count. The
     * call parses its arguments itself, and an argument may be a condition, so in
     * {@code 4 * (a + Iif(b = 1, 1, 0))} the {@code =} belongs to {@code Iif} and the
     * group around it is arithmetic. A call's parenthesis is the one written directly
     * against a name, which is the rule {@link #parseOperandFromNameToken} applies.
     * An operator inside a plain nested group still counts, so {@code ((a > b))} is
     * a predicate.
     */
    private boolean isParenthesizedPredicate() {
        int depth = 0;
        int callDepth = 0;   // how many of the open parentheses belong to a call
        Deque<Boolean> opened = new ArrayDeque<>();

        for (int offset = 0; ; offset++) {
            Token token = peek(offset);

            if (token.type() == TokenType.LPAREN) {
                boolean call = offset > 0 && opensCall(peek(offset - 1), token);
                opened.push(call);
                if (call) callDepth++;
                depth++;
            } else if (token.type() == TokenType.RPAREN) {
                if (opened.pop()) callDepth--;
                depth--;
                if (depth == 0) {
                    return false;
                }
            } else if (depth > 0 && callDepth == 0 && (isComparison(token.type()) || isLogicalOperator(token.type()) || token.type() == TokenType.ELEMENT_OF || token.type() == TokenType.NOT_ELEMENT_OF || token.type() == TokenType.LIKE
                    || token.type() == TokenType.IS || token.type() == TokenType.REGEX_MATCH
                    || token.type() == TokenType.REGEX_NOT_MATCH)) {
                return true;
            } else if (token.type() == TokenType.EOF) {
                return false;
            }
        }
    }

    /**
     * True when {@code paren} is written directly against {@code before}, a name: a
     * call. An operator keyword is a name too, but one inside the group has already
     * decided the lookahead before its parenthesis is reached.
     */
    private static boolean opensCall(Token before, Token paren) {
        return isNameToken(before)
                && before.column() + before.lexeme().length() == paren.column();
    }

    // =========================================================================
    // Operand parsing
    // =========================================================================

    private Operand parseOperand() {
        return parseArithmeticExpression(0);
    }

    private Operand parseArithmeticExpression(int minPrecedence) {
        Operand left = parseAtTimeZone(parsePrimaryOperand());

        while (isArithmeticOperator(current.type()) && arithmeticPrecedence(current.type()) >= minPrecedence) {
            Token operator = current;
            int precedence = arithmeticPrecedence(operator.type());
            advance();

            Operand right = parseArithmeticExpression(precedence + 1);
            // SQL's remainder operator is the Mod function, so it folds and pushes down as Mod.
            if (operator.type() == TokenType.MODULO) {
                left = new FunctionCall("Mod", List.of(left, right), loc(operator));
                continue;
            }
            ArithmeticOperator arithOp = switch (operator.type()) {
                // SQL's concatenation is the + that concatenates two strings.
                case PLUS, CONCATENATE -> ArithmeticOperator.PLUS;
                case MINUS    -> ArithmeticOperator.MINUS;
                case MULTIPLY -> ArithmeticOperator.MULTIPLY;
                case DIVIDE   -> ArithmeticOperator.DIVIDE;
                default -> throw error(operator, "Unexpected arithmetic operator");
            };
            left = new BinaryArithmeticExpression(left, arithOp, right, loc(operator));
        }

        return left;
    }

    /**
     * SQL's {@code ts AT TIME ZONE zone}, which binds tighter than any arithmetic, as in
     * PostgreSQL: it is {@code TIMEZONE(zone, ts)}, the wall-clock time in the zone. The
     * three words are matched by their text, so none is reserved.
     */
    private Operand parseAtTimeZone(Operand operand) {
        while (isWord(current, "AT") && peek(1).type() == TokenType.TIME && isWord(peek(2), "ZONE")) {
            Token at = current;
            advance();
            advance();
            advance();
            Operand zone = parsePrimaryOperand();
            operand = new FunctionCall("TIMEZONE", List.of(zone, operand), loc(at));
        }
        return operand;
    }

    private Operand parsePrimaryOperand() {
        // Unary minus: capture the '-' token and recursively parse the operand
        if (current.type() == TokenType.MINUS) {
            Token minusTok = current;
            advance();
            return new UnaryOperand(parsePrimaryOperand(), loc(minusTok));
        }

        if (startsIntervalLiteral()) {
            return parseIntervalLiteral();
        }
        if (isWord(current, "TIMESTAMPTZ") && peek(1).type() == TokenType.STRING) {
            return parseTemporalLiteral();
        }

        return switch (current.type()) {
            case IDENTIFIER -> {
                Token nameToken = current;
                advance();
                yield parseOperandFromNameToken(nameToken);
            }
            case PARAMETER -> {
                Token paramTok = current;
                advance();
                yield new ParameterOperand(paramTok.literal(), loc(paramTok));
            }
            case STRING -> {
                Token strTok = current;
                String value = current.literal();
                advance();
                yield new StringOperand(value, loc(strTok));
            }
            case NUMBER -> {
                Token numTok = current;
                // A number written with an exponent carries itself written out as its literal.
                String value = current.literal() != null ? current.literal() : current.lexeme();
                advance();
                yield new NumberOperand(value, loc(numTok));
            }
            // A literal always has its string next (TIME '12:00'); without one, the word
            // is a column named date, time, timestamp or duration.
            case DATE, TIME, TIMESTAMP, DURATION -> {
                if (startsTemporalLiteral()) {
                    yield parseTemporalLiteral();
                }
                Token nameToken = current;
                advance();
                yield parseOperandFromNameToken(nameToken);
            }
            case TRUE -> {
                Token trueTok = current;
                advance();
                yield new BooleanOperand(true, loc(trueTok));
            }
            case FALSE -> {
                Token falseTok = current;
                advance();
                yield new BooleanOperand(false, loc(falseTok));
            }
            // NULL (or ⊥) as a value: IIf(late, NULL, fee). In a null test, x = NULL or
            // x IS NULL, the word never reaches here; those are read as NullPredicates.
            case NULL -> {
                Token nullTok = current;
                advance();
                yield new NullOperand(loc(nullTok));
            }
            case LPAREN -> {
                // `(a > b)` in operand position is a condition, not an arithmetic
                // parenthesis — so a boolean can be projected (`π (a > b) → flag`)
                // and handed to an operator that takes an operand. The lookahead is
                // the one parseArgument uses, so the two positions agree about what
                // a parenthesis opens.
                if (isParenthesizedPredicate()) {
                    Token open = current;
                    advance();
                    Predicate predicate = parsePredicate();
                    expect(TokenType.RPAREN, "Expected ')' after predicate");
                    yield new ConditionOperand(predicate, loc(open));
                }
                advance();
                Operand expression = parseArithmeticExpression(0);
                expect(TokenType.RPAREN, "Expected ')' after arithmetic expression");
                yield expression;
            }
            case LBRACE -> parseStructConstruction();
            case LBRACKET -> parseArrayConstruction();
            default -> {
                // Allow any word-shaped keyword token as an attribute name or function call
                // in operand position (e.g. a column named 'count' or 'sum').
                if (!isNameToken(current)) {
                    throw error(current, "Expected attribute, string, number, boolean literal, function call, or '('");
                }
                Token nameToken = current;
                advance();
                yield parseOperandFromNameToken(nameToken);
            }
        };
    }

    /**
     * Parses a typed temporal literal — {@code DATE '…'}, {@code TIME '…'},
     * {@code TIMESTAMP '…'}, or {@code DURATION '…'} (ADR-0013).  The current token
     * is the temporal keyword.  The required quoted payload is parsed against
     * ISO-8601 via {@link TemporalLiterals}; a malformed payload is a parse error
     * positioned at the payload string (mirroring the {@code SAMPLE n ROWS}
     * integer check).
     */
    private Operand parseTemporalLiteral() {
        Token keyword = current;
        advance();
        // TIMESTAMPTZ '…', or the standard's TIMESTAMP WITH TIME ZONE '…'.
        boolean zoned = keyword.type() != TokenType.TIMESTAMP ? keyword.type() == TokenType.IDENTIFIER
                : consumeWithTimeZone();
        String name = zoned ? "TIMESTAMPTZ" : keyword.lexeme();
        Token payload = expect(TokenType.STRING,
                "Expected a quoted ISO-8601 string after '" + name + "'");
        String iso = payload.literal();
        try {
            if (zoned) {
                return TimestampOperand.zoned(TemporalLiterals.parseZonedTimestamp(iso), loc(keyword));
            }
            return switch (keyword.type()) {
                case DATE      -> new DateOperand(TemporalLiterals.parseDate(iso), loc(keyword));
                case TIME      -> new TimeOperand(TemporalLiterals.parseTime(iso), loc(keyword));
                case TIMESTAMP -> new TimestampOperand(TemporalLiterals.parseTimestamp(iso), loc(keyword));
                case DURATION  -> {
                    TemporalLiterals.Span span = TemporalLiterals.parseSpan(iso);
                    yield new DurationOperand(span.months(), span.exact(), loc(keyword));
                }
                default        -> throw error(keyword, "Unexpected temporal literal keyword");
            };
        } catch (DateTimeException e) {
            throw error(payload, "Malformed " + name + " literal "
                    + payload.lexeme() + ": not a valid ISO-8601 "
                    + name.toLowerCase(java.util.Locale.ROOT));
        }
    }

    /**
     * Shared logic for parsing a bare name token (IDENTIFIER or word-shaped keyword)
     * in operand position: handles dotted attribute names and function calls.
     */
    private Operand parseOperandFromNameToken(Token nameToken) {
        StringBuilder sb = new StringBuilder(nameToken.lexeme());

        while (match(TokenType.DOT)) {
            sb.append('.');
            rejectQualifiedStar(sb);
            Token next = expectName("Expected identifier after '.'");
            sb.append(next.lexeme());
        }

        if (current.type() == TokenType.LPAREN && current.column() == nameToken.column() + sb.length()) {
            // SQL's CAST(x AS type) and EXTRACT(field FROM x), which are not argument lists.
            if (sb.toString().equalsIgnoreCase("CAST")) {
                return parseCast(nameToken);
            }
            if (sb.toString().equalsIgnoreCase("EXTRACT") && isNameToken(peek(1)) && isWord(peek(2), "FROM")) {
                return parseExtract(nameToken);
            }
            // SQL Server's DATEDIFF(month, a, b) and DATEADD(month, n, d), and MySQL's
            // TIMESTAMPADD(MONTH, n, d), name their unit as a bare word.
            if ((sb.toString().equalsIgnoreCase("DATEDIFF") || sb.toString().equalsIgnoreCase("DATEADD")
                    || sb.toString().equalsIgnoreCase("TIMESTAMPADD")) && isNameToken(peek(1))
                    && peek(2).type() == TokenType.COMMA) {
                advance(); // (
                Token unit = current;
                advance();
                List<Operand> arguments = new ArrayList<>(List.of(new StringOperand(
                        unit.lexeme().toLowerCase(java.util.Locale.ROOT), loc(unit))));
                while (match(TokenType.COMMA)) {
                    arguments.add(parseArgument());
                }
                expect(TokenType.RPAREN, "Expected ')' after function arguments");
                return new FunctionCall(sb.toString(), arguments, loc(nameToken));
            }
            if (sb.toString().equalsIgnoreCase("SUBSTRING") || sb.toString().equalsIgnoreCase("POSITION")) {
                return parseSqlStringCall(sb.toString(), nameToken);
            }
            int callStart = consumed.lastIndexOf(nameToken);
            boolean aggregate = isAggregateName(sb.toString());
            advance(); // consume '('
            if (aggregate && current.type() == TokenType.MULTIPLY) {
                // COUNT(*) outside GROUP: read for now when its operator will say where it goes.
                Token star = current;
                if (!deferAggregateStar || !sb.toString().equalsIgnoreCase("COUNT")) {
                    rejectStarArgument(star, -1);
                }
                int starAt = consumed.size();
                advance();
                expect(TokenType.RPAREN, "Expected ')' after COUNT(*");
                aggregateCalls.add(new int[] {callStart, consumed.size(), starAt});
                return new FunctionCall(sb.toString(), List.of(), loc(nameToken));
            }
            List<Operand> arguments = new ArrayList<>();
            if (current.type() != TokenType.RPAREN) {
                do {
                    arguments.add(parseArgument());
                } while (match(TokenType.COMMA));
            }
            expect(TokenType.RPAREN, "Expected ')' after function arguments");
            if (aggregate) {
                aggregateCalls.add(new int[] {callStart, consumed.size(), -1});
            }
            return new FunctionCall(sb.toString(), arguments, loc(nameToken));
        }
        return new AttributeOperand(sb.toString(), loc(nameToken));
    }

    /**
     * Parses a call to {@code SUBSTRING} or {@code POSITION}, which SQL also writes with
     * words between the arguments: {@code SUBSTRING(s FROM start FOR length)}, either
     * clause alone, is {@code SUBSTRING(s, start, length)} (a start of 1 when only
     * {@code FOR} is given), and {@code POSITION(sub IN s)} is {@code STRPOS(s, sub)}.
     * Written with commas, either is an ordinary call. The current token is the {@code (}.
     */
    private Operand parseSqlStringCall(String name, Token nameToken) {
        expect(TokenType.LPAREN, "Expected '(' after " + name);
        Operand first = parseOperand();
        SourceLocation at = loc(nameToken);
        if (name.equalsIgnoreCase("POSITION") && match(TokenType.ELEMENT_OF)) {
            Operand in = parseOperand();
            expect(TokenType.RPAREN, "Expected ')' after POSITION(… IN …)");
            return new FunctionCall("STRPOS", List.of(in, first), at);
        }
        if (name.equalsIgnoreCase("SUBSTRING") && (isWord(current, "FROM") || isWord(current, "FOR"))) {
            Operand start = new NumberOperand("1", at);
            if (matchWord("FROM")) {
                start = parseOperand();
            }
            List<Operand> arguments = new ArrayList<>(List.of(first, start));
            if (matchWord("FOR")) {
                arguments.add(parseOperand());
            }
            expect(TokenType.RPAREN, "Expected ')' after SUBSTRING(… FROM … FOR …)");
            return new FunctionCall(name, arguments, at);
        }
        List<Operand> arguments = new ArrayList<>(List.of(first));
        while (match(TokenType.COMMA)) {
            arguments.add(parseArgument());
        }
        expect(TokenType.RPAREN, "Expected ')' after function arguments");
        return new FunctionCall(name, arguments, at);
    }

    /**
     * The fields {@code EXTRACT} reads, each the function that computes it: SQL's
     * {@code EXTRACT(DOW FROM d)} is {@code DOW(d)}.
     */
    private static final java.util.Set<String> EXTRACT_FIELDS = java.util.Set.of(
            "YEAR", "MONTH", "DAY", "HOUR", "MINUTE", "SECOND",
            "DOW", "ISODOW", "DOY", "WEEK", "ISOYEAR", "QUARTER", "EPOCH");

    /**
     * Parses SQL's {@code EXTRACT(field FROM operand)}, which is the field's own function
     * applied to the operand. The current token is the {@code (}.
     */
    private Operand parseExtract(Token nameToken) {
        expect(TokenType.LPAREN, "Expected '(' after EXTRACT");
        Token field = expectName("Expected a field after 'EXTRACT('");
        String name = field.lexeme().toUpperCase(java.util.Locale.ROOT);
        if (!EXTRACT_FIELDS.contains(name)) {
            throw error(field, "EXTRACT has no field '" + field.lexeme() + "'; expected one of "
                    + "YEAR, MONTH, DAY, HOUR, MINUTE, SECOND, DOW, ISODOW, DOY, WEEK, ISOYEAR, QUARTER, EPOCH");
        }
        advance(); // FROM, which the caller has seen
        Operand operand = parseOperand();
        expect(TokenType.RPAREN, "Expected ')' after the EXTRACT operand");
        return new FunctionCall(name, List.of(operand), loc(nameToken));
    }

    /**
     * Parses SQL's {@code CAST(operand AS type)} into the conversion it stands for. To a
     * date, time or timestamp it is {@code to_date}, {@code to_time} or
     * {@code to_timestamp}; to a number, a string or a boolean it is {@code CDbl},
     * {@code CStr} or {@code CBool} — which refuse a NULL, so the cast is written
     * {@code IIf(x IS NULL, NULL, CDbl(x))}, and a NULL casts to NULL as it does in SQL.
     * The current token is the {@code (}.
     */
    private Operand parseCast(Token nameToken) {
        expect(TokenType.LPAREN, "Expected '(' after CAST");
        Operand operand = parseOperand();
        expect(TokenType.AS, "Expected 'AS' and a type in CAST(… AS type)");
        Token type = current;
        // Every type is word-shaped, DATE, TIME and TIMESTAMP keywords among them.
        if (!isNameToken(type)) {
            throw error(type, "Expected a type after 'AS'");
        }
        advance();
        String typeName = type.lexeme().toUpperCase(java.util.Locale.ROOT);
        if (typeName.equals("TIMESTAMP") && consumeWithTimeZone()) {
            typeName = "TIMESTAMPTZ";
        }
        expect(TokenType.RPAREN, "Expected ')' after the CAST type");
        SourceLocation at = loc(nameToken);
        String conversion = switch (typeName) {
            case "DATE" -> "to_date";
            case "TIME" -> "to_time";
            case "TIMESTAMP" -> "to_timestamp";
            case "TIMESTAMPTZ" -> "to_timestamptz";
            case "NUMBER" -> "CDbl";
            case "STRING" -> "CStr";
            case "BOOLEAN" -> "CBool";
            default -> throw error(type, "CAST has no type '" + type.lexeme()
                    + "'; expected NUMBER, STRING, BOOLEAN, DATE, TIME, TIMESTAMP or TIMESTAMPTZ");
        };
        FunctionCall converted = new FunctionCall(conversion, List.of(operand), at);
        if (conversion.startsWith("to_")) {
            return converted;
        }
        return new FunctionCall("IIf", List.of(
                new ConditionOperand(new NullPredicate(operand, true, at), at),
                new NullOperand(at), converted), at);
    }

    /**
     * Parses a struct construction {@code { name: expr, … }}.  The shorthand
     * {@code { id, name }} (a bare identifier with no {@code :}) is desugared to
     * {@code { id: id, name: name }}.  The empty struct {@code {}} is permitted.
     */
    private Operand parseStructConstruction() {
        Token braceTok = current;
        expect(TokenType.LBRACE, "Expected '{'");
        List<StructConstruction.Field> fields = new ArrayList<>();
        if (current.type() != TokenType.RBRACE) {
            do {
                Token nameTok = expect(TokenType.IDENTIFIER, "Expected field name in struct construction");
                String name = nameTok.lexeme();
                Operand value;
                if (current.type() == TokenType.COLON) {
                    advance();
                    value = parseOperand();
                } else {
                    // shorthand: { name } ≡ { name: name }
                    value = new AttributeOperand(name, loc(nameTok));
                }
                fields.add(new StructConstruction.Field(name, value));
            } while (match(TokenType.COMMA));
        }
        expect(TokenType.RBRACE, "Expected '}' after struct fields");
        return new StructConstruction(fields, loc(braceTok));
    }

    /** Parses an array construction {@code [ expr, … ]}.  The empty array {@code []} is permitted. */
    private Operand parseArrayConstruction() {
        Token bracketTok = current;
        expect(TokenType.LBRACKET, "Expected '['");
        List<Operand> elements = new ArrayList<>();
        if (current.type() != TokenType.RBRACKET) {
            do {
                elements.add(parseOperand());
            } while (match(TokenType.COMMA));
        }
        expect(TokenType.RBRACKET, "Expected ']' after array elements");
        return new ArrayConstruction(elements, loc(bracketTok));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Parses a join's condition, noting the parenthesis it opens with, if any, so that
     * {@code T >< (a = M.a M)} is refused at {@code M} by
     * {@link #rejectRightInputInCondition}.
     */
    private Predicate parseJoinCondition(int leftStart) {
        JoinConditionParen outer = joinConditionParen;
        joinConditionParen = current.type() == TokenType.LPAREN
                ? new JoinConditionParen(current, leftStart) : null;
        try {
            return parsePredicate();
        } finally {
            joinConditionParen = outer;
        }
    }

    /**
     * Refuses a join's right input written inside its condition's parentheses,
     * {@code T >< (age_group = M.age_group M)}, at the name, with the name moved after
     * the parenthesis. Left alone, the name is reported as where the {@code )} should
     * be, and nothing says the name belongs outside it: a reader who had written
     * {@code OR} or {@code AND} in the condition blames that instead.
     */
    private void rejectRightInputInCondition(Token open) {
        JoinConditionParen paren = joinConditionParen;
        if (paren == null || paren.open() != open || current.type() != TokenType.IDENTIFIER
                || peek(1).type() != TokenType.RPAREN) {
            return;
        }
        throw ParseException.at("the right relation goes after the condition, outside its"
                + " parentheses: write " + writtenFrom(paren.leftStart()) + ") " + current.lexeme(),
                lexer.input(), current);
    }

    /**
     * Refuses a conditioned join written with no condition, such as
     * {@code Customers ANTI Orders}, naming the operator and what it lacks.
     *
     * <p>Left alone, the right input is read as the start of the condition and parsing
     * stops at whatever follows it — "expected comparison operator; found end of input",
     * placed at the end of the expression. A name followed directly by the end of the
     * expression, a closing parenthesis or another operator cannot begin a condition,
     * which always compares something, so it can only be the right input.
     */
    private void requireJoinCondition(Token operator, RelNode left) {
        if (current.type() != TokenType.IDENTIFIER) {
            return;
        }
        rejectConditionAfterRightInput(operator, left);
        StringBuilder right = new StringBuilder(current.lexeme());
        int next = 1;
        while (peek(next).type() == TokenType.DOT && peek(next + 1).type() == TokenType.IDENTIFIER) {
            right.append('.').append(peek(next + 1).lexeme());
            next += 2;
        }
        rejectTableAlias(right.toString(), next);
        TokenType after = peek(next).type();
        if (after == TokenType.EOF || after == TokenType.RPAREN || isBinaryRelOperator(after)) {
            String leftName = left instanceof RelationNode named ? named.name() : "…";
            throw ParseException.at("'" + operator.lexeme() + "' needs a join condition"
                    + " between its two inputs, written after the operator: " + leftName + " "
                    + operator.lexeme() + " <condition> " + right, lexer.input(), operator);
        }
    }

    /**
     * Refuses a join condition written in parentheses after the right input, SQL's
     * {@code ON} clause without the {@code ON}: {@code Round3 JOIN Teams (Teams.team_id = home_id)}.
     *
     * <p>Left alone, {@code Teams (} is read as a table-valued function call and parsing
     * stops at the comparison among its "arguments" (after a natural join), or the
     * condition is read from {@code Teams} and stops at the parenthesis (after a
     * conditioned one) — both describing the parser's reading rather than the mistake.
     * Neither reading can succeed: a table-valued function's arguments are operands,
     * which hold no comparison, connective, membership or pattern test outside a
     * parenthesis of their own, and a condition cannot end in a call with nothing
     * compared to it. So a parenthesised group holding one of those at its own level —
     * and, after a conditioned join, ending the expression — is a misplaced condition,
     * and the error rewrites it into the place it belongs.
     */
    private void rejectConditionAfterRightInput(Token operator, RelNode left) {
        if (current.type() != TokenType.IDENTIFIER) {
            return;
        }
        StringBuilder right = new StringBuilder(current.lexeme());
        int open = 1;
        while (peek(open).type() == TokenType.DOT && peek(open + 1).type() == TokenType.IDENTIFIER) {
            right.append('.').append(peek(open + 1).lexeme());
            open += 2;
        }
        if (peek(open).type() != TokenType.LPAREN) {
            return;
        }
        boolean condition = false;
        int depth = 0;
        int close = open;
        do {
            TokenType type = peek(close).type();
            if (type == TokenType.EOF) {
                return;
            }
            if (type == TokenType.LPAREN) {
                depth++;
            } else if (type == TokenType.RPAREN) {
                depth--;
            } else if (depth == 1 && (isComparison(type) || isLogicalOperator(type)
                    || type == TokenType.ELEMENT_OF || type == TokenType.NOT_ELEMENT_OF
                    || type == TokenType.LIKE || type == TokenType.IS)) {
                condition = true;
            }
            close++;
        } while (depth > 0);
        TokenType after = peek(close).type();
        if (!condition || operator.type() != TokenType.NATURAL_JOIN
                && after != TokenType.EOF && after != TokenType.RPAREN && !isBinaryRelOperator(after)) {
            // After a conditioned join the group may be a call the condition starts with,
            // `A >< IIf(x > 1, 1, 0) = y B`, whose arguments may compare; a misplaced
            // condition is the last thing in the expression, with nothing left to be
            // the right input.
            return;
        }
        String leftName = left instanceof RelationNode named ? named.name() : "…";
        String written = operator.type() == TokenType.NATURAL_JOIN
                ? (operator.lexeme().equals("⋈") ? "⨝" : "><")
                : operator.lexeme();
        String rewrite = leftName + " " + written + " " + sourceText(open + 1, close - 1)
                + " " + right;
        String problem = operator.type() == TokenType.NATURAL_JOIN
                ? "'" + operator.lexeme() + "' is the natural join: it joins on the columns both"
                        + " inputs share and takes no condition; to join on a condition, use"
                        + " ⨝ (><) and write the condition between the inputs: "
                : "'" + operator.lexeme() + "' takes its join condition between its two inputs,"
                        + " not after them: ";
        throw ParseException.at(problem + rewrite, lexer.input(), operator);
    }

    /**
     * The lookahead tokens from {@code from} up to but excluding {@code to}, spaced as
     * they would be written: nothing around a dot, after an opening parenthesis or before
     * a closing one or a comma.
     */
    private String sourceText(int from, int to) {
        List<Token> tokens = new ArrayList<>();
        for (int i = from; i < to; i++) {
            tokens.add(peek(i));
        }
        return joined(tokens);
    }

    /** {@code tokens} as source text, spaced as {@link #sourceText} describes. */
    private static String joined(List<Token> tokens) {
        StringBuilder text = new StringBuilder();
        TokenType previous = null;
        for (Token token : tokens) {
            TokenType type = token.type();
            if (previous != null && previous != TokenType.DOT && previous != TokenType.LPAREN
                    && type != TokenType.DOT && type != TokenType.RPAREN
                    && type != TokenType.COMMA) {
                text.append(' ');
            }
            text.append(token.lexeme());
            previous = type;
        }
        return text.toString();
    }

    private static boolean isBinaryRelOperator(TokenType type) {
        return switch (type) {
            case NATURAL_JOIN, THETA_JOIN, LEFT_OUTER_JOIN, RIGHT_OUTER_JOIN, FULL_OUTER_JOIN, SEMI_JOIN, ANTI_JOIN, UNIVERSAL_SEMI_JOIN, ASOF_JOIN, IJOIN, PRODUCT, UNION, UNION_ALL, OUTER_UNION, DIFFERENCE, INTERSECTION, DIVISION, SYMMETRIC_DIFFERENCE, COMPOSITION, LATERAL -> true;
            default -> false;
        };
    }

    private static int precedence(TokenType type) {
        return switch (type) {
            case NATURAL_JOIN, THETA_JOIN, LEFT_OUTER_JOIN, RIGHT_OUTER_JOIN, FULL_OUTER_JOIN, SEMI_JOIN, ANTI_JOIN, UNIVERSAL_SEMI_JOIN, ASOF_JOIN, IJOIN, PRODUCT, DIVISION, COMPOSITION, LATERAL -> 30;
            case INTERSECTION -> 20;
            case UNION, UNION_ALL, OUTER_UNION, DIFFERENCE, SYMMETRIC_DIFFERENCE -> 10;
            default -> -1;
        };
    }

    private static boolean isComparison(TokenType type) {
        return switch (type) {
            case EQUAL, NOT_EQUAL, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL -> true;
            default -> false;
        };
    }

    private static boolean isLogicalOperator(TokenType type) {
        return switch (type) {
            case AND, OR, NOT -> true;
            default -> false;
        };
    }

    private static boolean isArithmeticOperator(TokenType type) {
        return switch (type) {
            case PLUS, MINUS, MULTIPLY, DIVIDE, MODULO, CONCATENATE -> true;
            default -> false;
        };
    }

    private static int arithmeticPrecedence(TokenType type) {
        return switch (type) {
            case MULTIPLY, DIVIDE, MODULO -> 20;
            case PLUS, MINUS -> 10;
            // Below + and -, as in PostgreSQL: 'n = ' || n + 1 adds before it concatenates.
            case CONCATENATE -> 5;
            default -> -1;
        };
    }

    private boolean match(TokenType type) {
        if (current.type() == type) {
            advance();
            return true;
        }
        return false;
    }

    /**
     * Expects the end of the input, after a whole expression has parsed.
     *
     * <p>An operator keyword there is SQL's order, the clause written after what it
     * applies to: {@code PROJECT a (R) SORT a ASC}. Relix writes the operator first and
     * its input in parentheses, and the error rewrites the writer's own expression into
     * that order, rather than reporting the end of input it expected.
     */
    private void expectEnd() {
        if (isPrefixOperator(current.type())) {
            int end = 0;
            while (peek(end).type() != TokenType.EOF) {
                end++;
            }
            if (current.type() == TokenType.AGGREGATION) {
                // SQL's SELECT k, MAX(x) … GROUP BY k: the aggregates move into GROUP.
                int keys = peek(1).type() == TokenType.BY ? 2 : 1;
                String grouped = end > keys
                        ? groupedProject(current, sourceText(keys, end), 0, consumed.size()) : null;
                if (grouped != null) {
                    throw ParseException.at(grouped, lexer.input(), current);
                }
            }
            String operator = sourceText(0, end);
            String input = joined(consumed);
            if (input.length() > 80) {
                input = "…";
            }
            throw ParseException.at("'" + current.lexeme() + "' is written before its input,"
                    + " which it takes in parentheses: " + operator + " (" + input + ")",
                    lexer.input(), current);
        }
        rejectColumnListWithoutProject();
        expect(TokenType.EOF, "Expected end of input");
    }

    /**
     * Refuses a column list with no operator before it, {@code a, b (R)}: SQL's select
     * list with its {@code SELECT} left out, or a PROJECT dropped. The first column reads
     * as a relation's name and parsing stops at the comma, so "expected end of input"
     * never says what is missing. The error names PROJECT and the writer's own expression
     * after it, when that parses.
     */
    private void rejectColumnListWithoutProject() {
        if (current.type() != TokenType.COMMA || !isColumnName(0, consumed.size())) {
            return;
        }
        int end = 0;
        while (peek(end).type() != TokenType.EOF) {
            end++;
        }
        String rewrite = "PROJECT " + joined(consumed) + sourceText(0, end);
        try {
            parse(rewrite);
        } catch (ParseException e) {
            rewrite = "PROJECT a, b (R)";
        }
        throw ParseException.at("A column list needs PROJECT (π) before it: write " + rewrite,
                lexer.input(), current);
    }

    /**
     * Refuses a binary operator written before its inputs, the way a unary one is:
     * {@code JOIN A >< A.id = B.a_id B}, or {@code UNION A B}.
     *
     * <p>A glyph there is reported as no relation at all, and a word such as
     * {@code JOIN} is read as a relation's name, so parsing stops at the next relation
     * with "expected end of input" and never mentions the operator. A word is taken for
     * the operator only when a relation starts after it, which cannot follow a relation's
     * name; a relation really named {@code diff} or {@code union} still reads as one.
     * Straight after another binary operator, {@code A ⊎ ⊎ B}, the operator is doubled
     * rather than misplaced, and keeps the plain message.
     */
    private void rejectInfixOperatorFirst() {
        TokenType type = current.type();
        if (!isBinaryRelOperator(type)
                || !consumed.isEmpty() && isBinaryRelOperator(consumed.getLast().type())) {
            return;
        }
        Token next = peek(1);
        // A unary operator's Greek glyph is word-shaped, as its keyword is; ∀ alone is not.
        boolean relationFollows = isNameToken(next) && !isBinaryRelOperator(next.type())
                || next.type() == TokenType.FORALL;
        if (isNameToken(current) && !relationFollows) {
            return;
        }
        String op = current.lexeme();
        String written = switch (type) {
            case NATURAL_JOIN -> "goes between the two relations it joins: A " + op
                    + " B, or A >< condition B";
            case THETA_JOIN, LEFT_OUTER_JOIN, RIGHT_OUTER_JOIN, FULL_OUTER_JOIN, SEMI_JOIN,
                 ANTI_JOIN, UNIVERSAL_SEMI_JOIN -> "goes between the two relations it joins,"
                    + " with its condition after it: A " + op + " A.id = B.a_id B";
            case ASOF_JOIN, IJOIN -> "goes between the two relations it joins: A " + op + " … B";
            case UNION, UNION_ALL, OUTER_UNION, DIFFERENCE, INTERSECTION, SYMMETRIC_DIFFERENCE ->
                    "goes between the two relations it combines: A " + op + " B";
            default -> "goes between its two inputs: A " + op + " B";
        };
        throw ParseException.at("'" + op + "' " + written, lexer.input(), current);
    }

    /** True for the keywords that open a unary operator, written before its input. */
    private static boolean isPrefixOperator(TokenType type) {
        return switch (type) {
            case PROJECT, SELECT, RENAME, AGGREGATION, SORT, ORDER, LIMIT, DISTINCT, TOP,
                 SAMPLE, SHUFFLE -> true;
            default -> false;
        };
    }

    private Token expect(TokenType type, String message) {
        if (current.type() != type) {
            throw error(current, message);
        }

        Token token = current;
        advance();
        return token;
    }

    /**
     * Returns {@code true} if the token can serve as an identifier name — either
     * a true {@code IDENTIFIER} token or a keyword whose lexeme is word-shaped
     * (starts with a letter/underscore, followed by letters/digits/underscores).
     * All keyword tokens in the Lexer's map have word-shaped lexemes, so this is
     * equivalent to "any word token" and allows keywords to be used as column
     * names, aliases, and other name positions without ambiguity.
     */
    private static boolean isNameToken(Token t) {
        if (t.type() == TokenType.IDENTIFIER) return true;
        String s = t.lexeme();
        if (s.isEmpty()) return false;
        char first = s.charAt(0);
        if (!Character.isLetter(first) && first != '_') return false;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') return false;
        }
        return true;
    }

    /**
     * Reads a possibly-dotted qualified column name (e.g. {@code table.column} or
     * {@code schema.table.column}) and returns the full dotted string.  Used for
     * column references inside the IJOIN argument list where qualified names like
     * {@code Stays.checkin} are valid and expected.
     */
    private String expectQualifiedName(String message) {
        Token first = expectName(message);
        StringBuilder sb = new StringBuilder(first.lexeme());
        while (current.type() == TokenType.DOT) {
            advance();  // consume '.'
            Token next = expectName("Expected identifier after '.' in column name");
            sb.append('.').append(next.lexeme());
        }
        return sb.toString();
    }

    /**
     * Like {@link #expect(TokenType, String)} but accepts any word-shaped token
     * (IDENTIFIER or keyword) as a name.  Use in positions where a column name,
     * alias, or other identifier is expected and keywords should be permitted.
     */
    private Token expectName(String message) {
        if (!isNameToken(current)) {
            throw error(current, message);
        }
        Token token = current;
        advance();
        return token;
    }

    private void advance() {
        if (!lookahead.isEmpty()) {
            consumed.add(lookahead.remove(0));
        }
        current = peek(0);
    }

    private Token peek(int offset) {
        while (lookahead.size() <= offset) {
            lookahead.add(lexer.next());
        }
        return lookahead.get(offset);
    }

    private ParseException error(Token token, String message) {
        return new ParseException(message, lexer.input(), token);
    }
}
