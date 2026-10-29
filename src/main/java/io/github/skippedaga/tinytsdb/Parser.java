package io.github.skippedaga.tinytsdb;

import io.github.skippedaga.tinytsdb.Expr.AggregateOp;
import io.github.skippedaga.tinytsdb.Expr.BinaryOp;
import io.github.skippedaga.tinytsdb.Expr.VectorMatching;
import io.github.skippedaga.tinytsdb.Lexer.Kind;
import io.github.skippedaga.tinytsdb.Lexer.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Recursive-descent parser for the supported PromQL subset. Precedence, lowest first: {@code or};
 * {@code and unless}; comparisons; {@code + -}; {@code * /}; unary minus.
 */
final class Parser {

    private static final Map<String, AggregateOp> AGGREGATES = Map.of(
            "sum", AggregateOp.SUM, "count", AggregateOp.COUNT, "min", AggregateOp.MIN,
            "max", AggregateOp.MAX, "avg", AggregateOp.AVG);

    private static final Set<String> KEYWORDS = Set.of(
            "by", "without", "on", "ignoring", "bool", "or", "and", "unless", "offset",
            "group_left", "group_right");

    private final List<Token> tokens;
    private int i;

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    static Expr parse(String query) {
        if (query == null || query.isBlank()) {
            throw new QueryException("Empty query");
        }
        Parser p = new Parser(Lexer.tokens(query));
        Expr e = p.expr();
        if (p.peek().kind() != Kind.EOF) {
            throw p.unexpected();
        }
        return e;
    }

    private Expr expr() {
        Expr lhs = and();
        while (keyword("or")) {
            VectorMatching m = matching();
            lhs = binary(BinaryOp.OR, lhs, and(), false, m);
        }
        return lhs;
    }

    private Expr and() {
        Expr lhs = comparison();
        while (true) {
            BinaryOp op;
            if (keyword("and")) {
                op = BinaryOp.AND;
            } else if (keyword("unless")) {
                op = BinaryOp.UNLESS;
            } else {
                return lhs;
            }
            VectorMatching m = matching();
            lhs = binary(op, lhs, comparison(), false, m);
        }
    }

    private Expr comparison() {
        Expr lhs = additive();
        while (true) {
            BinaryOp op = switch (peek().kind()) {
                case EQL -> BinaryOp.EQL;
                case NEQ, NOT_EQUAL_MATCH -> BinaryOp.NEQ;
                case GTR -> BinaryOp.GTR;
                case LSS -> BinaryOp.LSS;
                case GTE -> BinaryOp.GTE;
                case LTE -> BinaryOp.LTE;
                default -> null;
            };
            if (op == null) {
                return lhs;
            }
            i++;
            boolean bool = keyword("bool");
            VectorMatching m = matching();
            lhs = binary(op, lhs, additive(), bool, m);
        }
    }

    private Expr additive() {
        Expr lhs = multiplicative();
        while (peek().kind() == Kind.ADD || peek().kind() == Kind.SUB) {
            BinaryOp op = next().kind() == Kind.ADD ? BinaryOp.ADD : BinaryOp.SUB;
            VectorMatching m = matching();
            lhs = binary(op, lhs, multiplicative(), false, m);
        }
        return lhs;
    }

    private Expr multiplicative() {
        Expr lhs = unary();
        while (peek().kind() == Kind.MUL || peek().kind() == Kind.DIV) {
            BinaryOp op = next().kind() == Kind.MUL ? BinaryOp.MUL : BinaryOp.DIV;
            VectorMatching m = matching();
            lhs = binary(op, lhs, unary(), false, m);
        }
        return lhs;
    }

    private Expr unary() {
        if (peek().kind() == Kind.SUB) {
            i++;
            Expr e = unary();
            return e instanceof Expr.NumberLiteral n ? new Expr.NumberLiteral(-n.value()) : new Expr.Negate(e);
        }
        if (peek().kind() == Kind.ADD) {
            i++;
            return unary();
        }
        return primary();
    }

    private Expr primary() {
        Token t = peek();
        switch (t.kind()) {
            case NUMBER:
                i++;
                try {
                    return new Expr.NumberLiteral(Double.parseDouble(t.text()));
                } catch (NumberFormatException e) {
                    throw new QueryException("Invalid number '" + t.text() + "' at position " + t.pos());
                }
            case LPAREN: {
                i++;
                Expr e = expr();
                expect(Kind.RPAREN);
                if (peek().kind() == Kind.LBRACKET) {
                    throw new QueryException("Subqueries are not supported (position " + peek().pos() + ")");
                }
                return e;
            }
            case LBRACE:
                return instantSelector(null);
            case IDENT: {
                String lower = t.text().toLowerCase(Locale.ROOT);
                Kind after = tokens.get(i + 1).kind();
                if (AGGREGATES.containsKey(lower)
                        && (after == Kind.LPAREN || isKeyword(tokens.get(i + 1), "by")
                        || isKeyword(tokens.get(i + 1), "without"))) {
                    i++;
                    return aggregate(AGGREGATES.get(lower));
                }
                if (after == Kind.LPAREN) {
                    Expr.RangeOp op = switch (lower) {
                        case "increase" -> Expr.RangeOp.INCREASE;
                        case "added" -> Expr.RangeOp.ADDED;
                        case "retracted" -> Expr.RangeOp.RETRACTED;
                        default -> throw new QueryException("Function " + t.text()
                                + "() is not supported; supported: increase(), added(), retracted()");
                    };
                    i++;
                    return rangeFunction(op);
                }
                if (KEYWORDS.contains(lower)) {
                    throw unexpected();
                }
                i++;
                return instantSelector(t.text());
            }
            default:
                throw unexpected();
        }
    }

    private Expr.Selector instantSelector(String metric) {
        Expr.Selector s = selector(metric);
        if (peek().kind() == Kind.LBRACKET) {
            throw new QueryException("A range vector is only supported inside increase(), added() or retracted() (position "
                    + peek().pos() + ")");
        }
        if (isKeyword(peek(), "offset")) {
            throw new QueryException("offset is not supported (position " + peek().pos() + ")");
        }
        return s;
    }

    private Expr.Selector selector(String metric) {
        List<Matcher> matchers = new ArrayList<>();
        if (metric != null) {
            matchers.add(Matcher.of(Matcher.Type.EQUAL, Labels.METRIC_NAME, metric));
        }
        if (peek().kind() == Kind.LBRACE) {
            i++;
            while (peek().kind() != Kind.RBRACE) {
                Token name = expect(Kind.IDENT);
                Matcher.Type type = switch (next().kind()) {
                    case ASSIGN -> Matcher.Type.EQUAL;
                    case NOT_EQUAL_MATCH -> Matcher.Type.NOT_EQUAL;
                    case REGEX_MATCH -> Matcher.Type.REGEX;
                    case NOT_REGEX_MATCH -> Matcher.Type.NOT_REGEX;
                    default -> throw new QueryException("Expected =, !=, =~ or !~ after label " + name.text()
                            + " at position " + tokens.get(i - 1).pos());
                };
                Token value = expect(Kind.STRING);
                matchers.add(Matcher.of(type, name.text(), value.text()));
                if (peek().kind() == Kind.COMMA) {
                    i++;
                } else if (peek().kind() != Kind.RBRACE) {
                    throw unexpected();
                }
            }
            i++;
        }
        if (matchers.stream().allMatch(m -> m.matches(""))) {
            throw new QueryException("A selector needs a metric name or a matcher that does not match the empty string");
        }
        return new Expr.Selector(matchers);
    }

    private Expr rangeFunction(Expr.RangeOp op) {
        expect(Kind.LPAREN);
        Token t = peek();
        Expr.Selector s;
        if (t.kind() == Kind.IDENT && !KEYWORDS.contains(t.text().toLowerCase(Locale.ROOT))) {
            i++;
            s = selector(t.text());
        } else if (t.kind() == Kind.LBRACE) {
            s = selector(null);
        } else {
            throw new QueryException(op.name().toLowerCase(Locale.ROOT)
                    + "() takes a range vector selector such as metric[5m] (position " + t.pos() + ")");
        }
        expect(Kind.LBRACKET);
        long range = Lexer.parseDuration(expect(Kind.DURATION).text());
        expect(Kind.RBRACKET);
        if (isKeyword(peek(), "offset")) {
            throw new QueryException("offset is not supported (position " + peek().pos() + ")");
        }
        expect(Kind.RPAREN);
        return new Expr.RangeFunction(op, s, range);
    }

    private Expr aggregate(AggregateOp op) {
        List<String> grouping = null;
        boolean without = false;
        if (keyword("by")) {
            grouping = labelList();
        } else if (keyword("without")) {
            grouping = labelList();
            without = true;
        }
        expect(Kind.LPAREN);
        Expr e = expr();
        if (peek().kind() == Kind.COMMA) {
            throw new QueryException("Aggregations with a parameter (topk, quantile…) are not supported");
        }
        expect(Kind.RPAREN);
        if (grouping == null) {
            if (keyword("by")) {
                grouping = labelList();
            } else if (keyword("without")) {
                grouping = labelList();
                without = true;
            }
        }
        if (isScalar(e)) {
            throw new QueryException(op.name().toLowerCase(Locale.ROOT) + "() takes an instant vector, not a scalar");
        }
        return new Expr.Aggregate(op, grouping == null ? List.of() : List.copyOf(grouping), without, e);
    }

    private VectorMatching matching() {
        VectorMatching m = VectorMatching.DEFAULT;
        if (keyword("on")) {
            m = new VectorMatching(true, labelList());
        } else if (keyword("ignoring")) {
            m = new VectorMatching(false, labelList());
        }
        if (isKeyword(peek(), "group_left") || isKeyword(peek(), "group_right")) {
            throw new QueryException("group_left / group_right are not supported (position " + peek().pos() + ")");
        }
        return m;
    }

    private List<String> labelList() {
        expect(Kind.LPAREN);
        List<String> labels = new ArrayList<>();
        while (peek().kind() != Kind.RPAREN) {
            labels.add(expect(Kind.IDENT).text());
            if (peek().kind() == Kind.COMMA) {
                i++;
            } else if (peek().kind() != Kind.RPAREN) {
                throw unexpected();
            }
        }
        i++;
        return labels;
    }

    private static Expr binary(BinaryOp op, Expr lhs, Expr rhs, boolean bool, VectorMatching m) {
        boolean scalars = isScalar(lhs) || isScalar(rhs);
        if (bool && !op.isComparison()) {
            throw new QueryException("bool is only allowed on comparisons");
        }
        if (op.isSet() && scalars) {
            throw new QueryException(op.name().toLowerCase(Locale.ROOT) + " is only allowed between instant vectors");
        }
        if (op.isComparison() && isScalar(lhs) && isScalar(rhs) && !bool) {
            throw new QueryException("A comparison between two scalars needs bool");
        }
        if (scalars && m != VectorMatching.DEFAULT) {
            throw new QueryException("on() / ignoring() are only allowed between instant vectors");
        }
        return new Expr.Binary(op, lhs, rhs, bool, m);
    }

    static boolean isScalar(Expr e) {
        if (e instanceof Expr.NumberLiteral) {
            return true;
        }
        if (e instanceof Expr.Negate n) {
            return isScalar(n.expr());
        }
        if (e instanceof Expr.Binary b) {
            return isScalar(b.lhs()) && isScalar(b.rhs());
        }
        return false;
    }

    private boolean keyword(String word) {
        if (isKeyword(peek(), word)) {
            i++;
            return true;
        }
        return false;
    }

    private static boolean isKeyword(Token t, String word) {
        return t.kind() == Kind.IDENT && t.text().equalsIgnoreCase(word);
    }

    private Token peek() {
        return tokens.get(i);
    }

    private Token next() {
        return tokens.get(i++);
    }

    private Token expect(Kind kind) {
        Token t = peek();
        if (t.kind() != kind) {
            throw new QueryException("Expected " + describe(kind) + " but found " + t + " at position " + t.pos());
        }
        i++;
        return t;
    }

    private QueryException unexpected() {
        Token t = peek();
        return new QueryException("Unexpected " + t + " at position " + t.pos());
    }

    private static String describe(Kind kind) {
        return switch (kind) {
            case IDENT -> "a name";
            case STRING -> "a quoted string";
            case DURATION -> "a duration";
            case LPAREN -> "'('";
            case RPAREN -> "')'";
            case LBRACKET -> "'['";
            case RBRACKET -> "']'";
            case RBRACE -> "'}'";
            default -> kind.name();
        };
    }
}
