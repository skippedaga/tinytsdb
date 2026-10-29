package io.github.skippedaga.tinytsdb;

import java.util.List;

/** The parsed form of a query. */
sealed interface Expr {

    record NumberLiteral(double value) implements Expr {
    }

    /** An instant vector selector: every matching series with its total. */
    record Selector(List<Matcher> matchers) implements Expr {
    }

    /** {@code increase|added|retracted(selector[range])}: a rolling window of the series. */
    record RangeFunction(RangeOp op, Selector selector, long rangeMs) implements Expr {
    }

    enum RangeOp {
        /** Net growth: added − retracted. */
        INCREASE,
        /** Only the added part. */
        ADDED,
        /** Only the retracted part. */
        RETRACTED
    }

    /** {@code op by|without (labels) (expr)}. */
    record Aggregate(AggregateOp op, List<String> grouping, boolean without, Expr expr) implements Expr {
    }

    record Binary(BinaryOp op, Expr lhs, Expr rhs, boolean returnBool, VectorMatching matching) implements Expr {
    }

    record Negate(Expr expr) implements Expr {
    }

    /**
     * {@code on(labels)} or {@code ignoring(labels)}; none given is {@code ignoring()}.
     *
     * @param on true for {@code on}, false for {@code ignoring}
     */
    record VectorMatching(boolean on, List<String> labels) {

        static final VectorMatching DEFAULT = new VectorMatching(false, List.of());
    }

    enum AggregateOp {
        SUM, COUNT, MIN, MAX, AVG
    }

    enum BinaryOp {
        ADD, SUB, MUL, DIV,
        EQL, NEQ, GTR, LSS, GTE, LTE,
        AND, OR, UNLESS;

        boolean isComparison() {
            return this == EQL || this == NEQ || this == GTR || this == LSS || this == GTE || this == LTE;
        }

        boolean isSet() {
            return this == AND || this == OR || this == UNLESS;
        }
    }
}
