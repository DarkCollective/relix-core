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

import java.io.IOException;
import java.io.InputStream;
import java.time.DateTimeException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

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
    private Token current;

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
        parser.expect(TokenType.EOF, "Expected end of input");
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
        parser.expect(TokenType.EOF, "Expected end of input");
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
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(filePath, "filePath");
        if (startLine < 1) {
            throw new IllegalArgumentException("startLine must be >= 1");
        }
        if (startColumn < 1) {
            throw new IllegalArgumentException("startColumn must be >= 1");
        }
        RelAlgebraParser parser = new RelAlgebraParser(input, filePath, startLine, startColumn);
        RelNode result = parser.parseExpression(0);
        parser.expect(TokenType.EOF, "Expected end of input");
        return result;
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
     * Parses a scalar operand expression from the given input string, starting
     * from the specified source position, with a specific file path embedded in
     * every node's source location.
     *
     * @param input       the operand expression to parse
     * @param filePath    the file path to embed in source locations
     * @param startLine   1-based source line (used for error reporting)
     * @param startColumn 1-based source column (used for error reporting)
     * @return the parsed operand
     * @throws ParseException if the input contains syntax errors
     */
    public static Operand parseOperand(String input, String filePath, int startLine, int startColumn) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(filePath, "filePath");
        if (startLine < 1) {
            throw new IllegalArgumentException("startLine must be >= 1");
        }
        if (startColumn < 1) {
            throw new IllegalArgumentException("startColumn must be >= 1");
        }
        RelAlgebraParser parser = new RelAlgebraParser(input, filePath, startLine, startColumn);
        Operand result = parser.parseOperand();
        parser.expect(TokenType.EOF, "Expected end of operand expression");
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
        parser.expect(TokenType.EOF, "Expected end of input");
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
        parser.expect(TokenType.EOF, "Expected end of input");
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

    private RelNode parseExpression(int minPrecedence) {
        if (++depth > MAX_NESTING_DEPTH) {
            throw error(current, "expression nests more than " + MAX_NESTING_DEPTH
                    + " levels deep");
        }
        try {
            return parseExpressionAtDepth(minPrecedence);
        } finally {
            depth--;
        }
    }

    private RelNode parseExpressionAtDepth(int minPrecedence) {
        RelNode left = parsePrefix();
        left = parseKleenePostfix(left);

        while (isBinaryRelOperator(current.type()) && precedence(current.type()) >= minPrecedence) {
            Token operator = current;
            int precedence = precedence(operator.type());
            advance();

            Predicate condition = null;
            boolean asofInner = false;
            Optional<Operand> asofTolerance = Optional.empty();
            TieBreak asofTieBreak = TieBreak.LAST;
            AllenRelation ijoinRelation = null;
            String ijoinLeftStart = null, ijoinLeftEnd = null;
            String ijoinRightStart = null, ijoinRightEnd = null;

            if (operator.type() == TokenType.THETA_JOIN ||
                operator.type() == TokenType.LEFT_OUTER_JOIN ||
                operator.type() == TokenType.RIGHT_OUTER_JOIN ||
                operator.type() == TokenType.FULL_OUTER_JOIN ||
                operator.type() == TokenType.SEMI_JOIN ||
                operator.type() == TokenType.ANTI_JOIN ||
                operator.type() == TokenType.UNIVERSAL_SEMI_JOIN) {
                condition = parsePredicate();
            } else if (operator.type() == TokenType.ASOF_JOIN) {
                // Optional INNER keyword switches from left-outer to inner mode.
                if (current.type() == TokenType.IDENTIFIER && "inner".equalsIgnoreCase(current.lexeme())) {
                    asofInner = true;
                    advance();
                }
                condition = parsePredicate();
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

            RelNode right = parseExpression(precedence + 1);
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

        return left;
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
        expect(TokenType.COMMA,
                "Expected ',' between the two closure columns in OVER (from, to)");
        Token to = expectName("Expected the 'to' column name inside OVER (from, to)");
        expect(TokenType.RPAREN,
                "Expected ')' to close OVER (from, to)");
        return new ClosureNode(input, from.lexeme(), to.lexeme(), reflexive, loc(glyph));
    }

    private RelNode parsePrefix() {
        return switch (current.type()) {
            case IDENTIFIER -> {
                Token startTok = current;
                advance();
                yield parseRelationFromNameToken(startTok);
            }
            case LPAREN -> {
                advance();
                RelNode expression = parseExpression(0);
                expect(TokenType.RPAREN, "Expected ')' after relational expression");
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
            case FORALL   -> parseUniversal();
            case SAMPLE   -> parseSample();
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

        // An identifier immediately followed by '(' in relation position is a
        // table-valued (relation-returning) function call, e.g. recentOrders(2).
        if (current.type() == TokenType.LPAREN) {
            return parseRelationFunctionCall(sb.toString(), startTok);
        }

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
        ProjectionOperands attributes = parseProjectionOperandList();

        if (attributes.hadComma() && current.type() == TokenType.EOF) {
            throw error(new Token(
                    current.type(),
                    current.lexeme(),
                    current.literal(),
                    current.line(),
                    current.column() + 1
            ), "Expected '(' after projection attribute list");
        }

        RelNode input = parseParenthesizedRelation("Expected '(' after projection attribute list");
        return new ProjectionNode(attributes.operands(), input, loc(opTok));
    }

    private SelectionNode parseSelection() {
        Token opTok = current;
        expect(TokenType.SELECT, "Expected 'σ'");
        Predicate predicate = parsePredicate();
        RelNode input = parseParenthesizedRelation("Expected '(' after selection predicate");
        return new SelectionNode(predicate, input, loc(opTok));
    }

    private RenameNode parseRename() {
        Token opTok = current;
        expect(TokenType.RENAME, "Expected 'ρ'");

        // The new relation name is optional: `ρ (a → b) (R)` renames columns only.
        Optional<String> relationName = Optional.empty();
        if (isNameToken(current)) {
            relationName = Optional.of(expectName("Expected new relation name after 'ρ'").lexeme());
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

    private AggregationNode parseAggregation() {
        Token opTok = current;
        expect(TokenType.AGGREGATION, "Expected 'γ'");
        consumeOptionalBy();

        List<GroupingKey> groupingKeys = parseGroupingKeys();
        List<AggregateFunction> aggregates = parseAggregateFunctionList();
        RelNode input = parseParenthesizedRelation("Expected '(' after aggregation expression");

        return new AggregationNode(groupingKeys, aggregates, input, loc(opTok));
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
        List<SortSpecification> specs = new ArrayList<>();
        specs.add(parseSortSpecification());

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

        if (current.type() == TokenType.ASC) {
            advance();
            direction = SortDirection.ASC;
        } else if (current.type() == TokenType.DESC) {
            advance();
            direction = SortDirection.DESC;
        }

        return new SortSpecification(expression, direction);
    }

    private DistinctNode parseDistinct() {
        Token opTok = current;
        expect(TokenType.DISTINCT, "Expected 'δ'");
        RelNode input = parseParenthesizedRelation("Expected '(' after 'δ'");
        return new DistinctNode(input, loc(opTok));
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
     * Parses a transitive-closure operator:
     * {@code CLOSURE from, to (R)} (transitive) or {@code RCLOSURE from, to (R)}
     * (reflexive-transitive).  The current token is the CLOSURE/RCLOSURE keyword.
     */
    private ClosureNode parseClosure(boolean reflexive) {
        Token opTok = current;
        String keyword = reflexive ? "RCLOSURE" : "CLOSURE";
        advance();   // consume CLOSURE / RCLOSURE
        Token from = expectName("Expected the 'from' column after '" + keyword + "'");
        expect(TokenType.COMMA,
                "Expected ',' between the two closure columns: " + keyword + " from, to (R)");
        Token to = expectName("Expected the 'to' column after ',' in " + keyword);
        RelNode input = parseParenthesizedRelation(
                "Expected '(' after the closure columns");
        return new ClosureNode(input, from.lexeme(), to.lexeme(), reflexive, loc(opTok));
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
        expect(TokenType.COMMA,
                "Expected ',' between the two PATH edge columns: PATH from, to HOPS m TO n AS depth (R)");
        Token to = expectName("Expected the 'to' column after ',' in PATH");
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
        return new PathNode(input, from.lexeme(), to.lexeme(), min, max, depth.lexeme(), loc(opTok));
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
        expect(TokenType.COMMA,
                "Expected ',' between the two TRACE endpoint columns: "
                + "TRACE from, to VIA weight MINIMIZE|MAXIMIZE AS path (R)");
        Token to = expectName("Expected the 'to' column after ',' in TRACE");
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
        return new TraceNode(input, from.lexeme(), to.lexeme(),
                             weight.lexeme(), sense, path.lexeme(), loc(opTok));
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

        AggregateOperator operator = switch (current.type()) {
            case SUM -> AggregateOperator.SUM;
            case AVG -> AggregateOperator.AVG;
            case COUNT -> AggregateOperator.COUNT;
            case MIN -> AggregateOperator.MIN;
            case MAX -> AggregateOperator.MAX;
            default -> throw error(current,
                    "Expected a window aggregate (SUM, AVG, COUNT, MIN, MAX) after 'ROLLING'");
        };
        advance();
        expect(TokenType.LPAREN, "Expected '(' after the ROLLING aggregate name");
        Operand argument = parseOperand();
        expect(TokenType.RPAREN, "Expected ')' after the ROLLING aggregate argument");

        expect(TokenType.OVER, "Expected 'OVER' before the ROLLING frame specification");
        WindowFrame frame = parseWindowFrame();

        expect(TokenType.SORT, "Expected 'SORT' before the ROLLING ordering");
        List<SortSpecification> sortSpecs = parseSortSpecificationList();

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
            throw error(current,
                    "Expected a frame size (<n> ROWS) or 'ALL ROWS' after 'OVER'");
        }
        expect(TokenType.ROWS, "Expected 'ROWS' after the window frame size");
        return frame;
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
            case "ROW_NUMBER", "RANK", "DENSE_RANK", "PERCENT_RANK", "NTILE" ->
                    parseRankingWindow(RankingFunction.valueOf(name));
            case "LAG", "LEAD", "FIRST_VALUE", "LAST_VALUE" ->
                    parseOffsetWindow(OffsetFunction.valueOf(name));
            default -> throw error(fnTok, "Unknown window function '" + fnTok.lexeme()
                    + "'; expected ROW_NUMBER, RANK, DENSE_RANK, PERCENT_RANK, NTILE, "
                    + "LAG, LEAD, FIRST_VALUE, or LAST_VALUE");
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
        if (fn == OffsetFunction.LAG || fn == OffsetFunction.LEAD) {
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
     * Parses a goal-seek operator: {@code SOLVE left = right (R)}, e.g.
     * {@code SOLVE total = principal * rate (Loans)}.  Both sides are arithmetic
     * operands; the equation is validated (invertible vocabulary, single
     * occurrence per column) during semantic analysis.  The current token is the
     * SOLVE keyword.
     */
    private SolveNode parseSolve() {
        Token opTok = current;
        expect(TokenType.SOLVE, "Expected 'SOLVE'");
        Operand left = parseOperand();
        expect(TokenType.EQUAL, "Expected '=' between the two sides of the SOLVE equation");
        Operand right = parseOperand();
        RelNode input = parseParenthesizedRelation("Expected '(' after the SOLVE equation");
        return new SolveNode(left, right, input, loc(opTok));
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
        expect(TokenType.TOP, "Expected 'TOP'");

        Token firstNum = expect(TokenType.NUMBER, "Expected a count after 'TOP'");
        long firstValue = Long.parseLong(firstNum.lexeme());
        Optional<Long> offset = Optional.empty();
        long count;
        if (match(TokenType.COMMA)) {
            offset = Optional.of(firstValue);
            count = Long.parseLong(
                    expect(TokenType.NUMBER, "Expected count after offset").lexeme());
        } else {
            count = firstValue;
        }

        List<SortSpecification> sortSpecs = parseSortSpecificationList();

        // No PER is not a missing clause, it is the global group. The sort-spec list is
        // read greedily and ends at either PER or the opening parenthesis, so which was
        // written is unambiguous.
        List<String> keys = new ArrayList<>();
        if (match(TokenType.PER)) {
            keys.add(expect(TokenType.IDENTIFIER, "Expected a grouping key after 'PER'").lexeme());
            while (match(TokenType.COMMA)) {
                keys.add(expect(TokenType.IDENTIFIER, "Expected a grouping key after ','").lexeme());
            }
        }

        RelNode input = parseParenthesizedRelation(
                keys.isEmpty() ? "Expected 'PER' or '(' after the TOP sort keys"
                               : "Expected '(' after the TOP grouping keys");
        return new TopKNode(List.copyOf(keys), sortSpecs, offset, count, input, loc(opTok));
    }

    private LimitNode parseLimit() {
        Token opTok = current;
        expect(TokenType.LIMIT, "Expected 'λ'");

        Token firstNum = expect(TokenType.NUMBER, "Expected number for limit or offset");
        long firstValue = Long.parseLong(firstNum.lexeme());

        Optional<Long> offset = Optional.empty();
        Long count;

        if (match(TokenType.COMMA)) {
            // Two arguments: offset, count
            offset = Optional.of(firstValue);
            Token countNum = expect(TokenType.NUMBER, "Expected count after offset");
            count = Long.parseLong(countNum.lexeme());
        } else {
            // One argument: count
            count = firstValue;
        }

        RelNode input = parseParenthesizedRelation("Expected '(' after limit specification");
        return new LimitNode(offset, count, input, loc(opTok));
    }

    /**
     * Parses the grouping-key list of a γ: zero or more comma-separated
     * {@link GroupingKey}s (each a full {@link Operand} expression with an
     * optional {@code → alias}) preceding the aggregate-function list. Parsing
     * stops at the first aggregate-function keyword (SUM, COUNT, …) or the input
     * relation's {@code (}, so a bare column, a function call
     * ({@code YEAR(ts) → yr}), or an arithmetic expression may all be grouping
     * keys — mirroring what the projection/selection positions already accept.
     */
    private List<GroupingKey> parseGroupingKeys() {
        List<GroupingKey> keys = new ArrayList<>();

        while (!isAggregateFunctionKeyword(current) && current.type() != TokenType.LPAREN) {
            Operand expression = parseOperand();
            Optional<String> alias = Optional.empty();
            if (match(TokenType.ARROW)) {
                alias = Optional.of(expectName("Expected alias name after '→'").lexeme());
            }
            keys.add(new GroupingKey(expression, alias));

            if (current.type() == TokenType.COMMA) {
                advance();
            } else {
                break;
            }
        }

        return keys;
    }

    private boolean isAggregateFunctionKeyword(Token token) {
        return token.type() == TokenType.SUM ||
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
            aggregates.add(parseAggregateFunction());

            if (current.type() == TokenType.COMMA) {
                advance();
            } else {
                break;
            }
        } while (isAggregateFunctionKeyword(current));

        if (aggregates.isEmpty()) {
            throw error(current, "Expected at least one aggregate function");
        }

        return aggregates;
    }

    private AggregateFunction parseAggregateFunction() {
        AggregateOperator operator = switch (current.type()) {
            case SUM -> AggregateOperator.SUM;
            case AVG -> AggregateOperator.AVG;
            case COUNT -> AggregateOperator.COUNT;
            case MIN -> AggregateOperator.MIN;
            case MAX -> AggregateOperator.MAX;
            case COLLECT -> AggregateOperator.COLLECT;
            case ARGMAX -> AggregateOperator.ARGMAX;
            case ARGMIN -> AggregateOperator.ARGMIN;
            default -> throw error(current,
                "Expected aggregate function (SUM, AVG, COUNT, MIN, MAX, COLLECT, ARGMAX, or ARGMIN)");
        };
        advance();

        expect(TokenType.LPAREN, "Expected '(' after aggregate function name");

        // The aggregate argument is a full scalar expression — e.g. SUM(price * qty).
        // COUNT(*) is the one exception: SQL's row-count spelling, accepted as a
        // pure synonym for COUNT(1).  A literal argument is never NULL, so counting
        // it counts every row; parsing to the *identical* AST is how every other
        // alternative spelling in this language works (SELECT/σ, !=/≠), and it means
        // inference, validation, execution, and SQL pushdown need no COUNT(*) case.
        // Restricted to COUNT — SUM(*)/MIN(*) are meaningless, so `*` keeps its
        // ordinary MULTIPLY reading everywhere else.
        Operand argument;
        if (operator == AggregateOperator.COUNT
                && current.type() == TokenType.MULTIPLY
                && peek(1).type() == TokenType.RPAREN) {
            Token star = current;
            advance();
            argument = new NumberOperand("1", loc(star));
        } else {
            argument = parseOperand();
        }

        // ARGMAX/ARGMIN take a second expression: ARGMAX(rank, yield).
        boolean isArg = operator == AggregateOperator.ARGMAX || operator == AggregateOperator.ARGMIN;
        Optional<Operand> yieldExpr = Optional.empty();
        if (isArg) {
            expect(TokenType.COMMA, "Expected ',' — ARGMAX/ARGMIN take two arguments: (rank, yield)");
            yieldExpr = Optional.of(parseOperand());
        }

        expect(TokenType.RPAREN, "Expected ')' after aggregate function argument");

        Optional<String> alias = Optional.empty();
        if (current.type() == TokenType.ARROW) {
            advance();
            alias = Optional.of(expectName("Expected alias name after '→'").lexeme());
        }

        return new AggregateFunction(operator, argument, yieldExpr, alias);
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
            operands.add(parseProjectedAttribute());
        }

        return new ProjectionOperands(List.copyOf(operands), hadComma);
    }

    private ProjectedAttribute parseProjectedAttribute() {
        Operand expression = parseOperand();

        if (match(TokenType.ARROW)) {
            Token aliasToken = expectName("Expected alias after '→'");
            return ProjectedAttribute.aliased(expression, aliasToken.lexeme());
        }

        return ProjectedAttribute.simple(expression);
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
                || t == TokenType.LIKE) return true;
        return t == TokenType.NOT
                && (peek(1).type() == TokenType.ELEMENT_OF
                    || peek(1).type() == TokenType.LIKE);
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
            advance();
            Predicate predicate = parsePredicate();
            expect(TokenType.RPAREN, "Expected ')' after predicate");
            return predicate;
        }

        Operand left = seed != null ? seed : parseOperand();

        // Check for element-of predicates (∈, ∉, IN, or NOT IN)
        if (current.type() == TokenType.ELEMENT_OF || current.type() == TokenType.NOT_ELEMENT_OF) {
            Token opTok = current;
            boolean isNegated = opTok.type() == TokenType.NOT_ELEMENT_OF;
            advance();

            Operand setExpr = parseSetExpression();
            return new ElementOfPredicate(left, setExpr, isNegated, loc(opTok));
        }

        if (current.type() == TokenType.NOT && peek(1).type() == TokenType.ELEMENT_OF) {
            Token notTok = current;
            advance(); // consume NOT
            advance(); // consume IN
            Operand setExpr = parseSetExpression();
            return new ElementOfPredicate(left, setExpr, true, loc(notTok));
        }

        // Check for LIKE / NOT LIKE
        if (current.type() == TokenType.LIKE) {
            Token opTok = current;
            advance();
            Operand pattern = parseOperand();
            return new PatternPredicate(left, pattern, false, loc(opTok));
        }

        if (current.type() == TokenType.NOT && peek(1).type() == TokenType.LIKE) {
            Token notTok = current;
            advance(); // consume NOT
            advance(); // consume LIKE
            Operand pattern = parseOperand();
            return new PatternPredicate(left, pattern, true, loc(notTok));
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
            expect(TokenType.NULL, "Expected 'NULL' after 'IS'");
            return new NullPredicate(left, isNull, loc(opTok));
        }

        if (!isComparison(current.type())) {
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
            throw error(current, "Expected set literal {items}, (SELECT ...), or relation name");
        }
    }

    private boolean isParenthesizedPredicate() {
        int depth = 0;

        for (int offset = 0; ; offset++) {
            Token token = peek(offset);

            if (token.type() == TokenType.LPAREN) {
                depth++;
            } else if (token.type() == TokenType.RPAREN) {
                depth--;
                if (depth == 0) {
                    return false;
                }
            } else if (depth > 0 && (isComparison(token.type()) || isLogicalOperator(token.type()) || token.type() == TokenType.ELEMENT_OF || token.type() == TokenType.NOT_ELEMENT_OF || token.type() == TokenType.LIKE)) {
                return true;
            } else if (token.type() == TokenType.EOF) {
                return false;
            }
        }
    }

    // =========================================================================
    // Operand parsing
    // =========================================================================

    private Operand parseOperand() {
        return parseArithmeticExpression(0);
    }

    private Operand parseArithmeticExpression(int minPrecedence) {
        Operand left = parsePrimaryOperand();

        while (isArithmeticOperator(current.type()) && arithmeticPrecedence(current.type()) >= minPrecedence) {
            Token operator = current;
            int precedence = arithmeticPrecedence(operator.type());
            advance();

            Operand right = parseArithmeticExpression(precedence + 1);
            ArithmeticOperator arithOp = switch (operator.type()) {
                case PLUS     -> ArithmeticOperator.PLUS;
                case MINUS    -> ArithmeticOperator.MINUS;
                case MULTIPLY -> ArithmeticOperator.MULTIPLY;
                case DIVIDE   -> ArithmeticOperator.DIVIDE;
                default -> throw error(operator, "Unexpected arithmetic operator");
            };
            left = new BinaryArithmeticExpression(left, arithOp, right, loc(operator));
        }

        return left;
    }

    private Operand parsePrimaryOperand() {
        // Unary minus: capture the '-' token and recursively parse the operand
        if (current.type() == TokenType.MINUS) {
            Token minusTok = current;
            advance();
            return new UnaryOperand(parsePrimaryOperand(), loc(minusTok));
        }

        return switch (current.type()) {
            case IDENTIFIER -> {
                Token nameToken = current;
                advance();
                yield parseOperandFromNameToken(nameToken);
            }
            case STRING -> {
                Token strTok = current;
                String value = current.literal();
                advance();
                yield new StringOperand(value, loc(strTok));
            }
            case NUMBER -> {
                Token numTok = current;
                String value = current.lexeme();
                advance();
                yield new NumberOperand(value, loc(numTok));
            }
            case DATE, TIME, TIMESTAMP, DURATION -> parseTemporalLiteral();
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
            case LPAREN -> {
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
        Token payload = expect(TokenType.STRING,
                "Expected a quoted ISO-8601 string after '" + keyword.lexeme() + "'");
        String iso = payload.literal();
        try {
            return switch (keyword.type()) {
                case DATE      -> new DateOperand(TemporalLiterals.parseDate(iso), loc(keyword));
                case TIME      -> new TimeOperand(TemporalLiterals.parseTime(iso), loc(keyword));
                case TIMESTAMP -> new TimestampOperand(TemporalLiterals.parseTimestamp(iso), loc(keyword));
                case DURATION  -> new DurationOperand(TemporalLiterals.parseDuration(iso), loc(keyword));
                default        -> throw error(keyword, "Unexpected temporal literal keyword");
            };
        } catch (DateTimeException e) {
            throw error(payload, "Malformed " + keyword.lexeme() + " literal "
                    + payload.lexeme() + ": not a valid ISO-8601 "
                    + keyword.lexeme().toLowerCase(java.util.Locale.ROOT));
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
            Token next = expectName("Expected identifier after '.'");
            sb.append(next.lexeme());
        }

        if (current.type() == TokenType.LPAREN && current.column() == nameToken.column() + sb.length()) {
            advance(); // consume '('
            List<Operand> arguments = new ArrayList<>();
            if (current.type() != TokenType.RPAREN) {
                do {
                    arguments.add(parseArgument());
                } while (match(TokenType.COMMA));
            }
            expect(TokenType.RPAREN, "Expected ')' after function arguments");
            return new FunctionCall(sb.toString(), arguments, loc(nameToken));
        }
        return new AttributeOperand(sb.toString(), loc(nameToken));
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
            case PLUS, MINUS, MULTIPLY, DIVIDE -> true;
            default -> false;
        };
    }

    private static int arithmeticPrecedence(TokenType type) {
        return switch (type) {
            case MULTIPLY, DIVIDE -> 20;
            case PLUS, MINUS -> 10;
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
            lookahead.remove(0);
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
