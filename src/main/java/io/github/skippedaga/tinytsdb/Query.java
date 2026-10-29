package io.github.skippedaga.tinytsdb;

/** A parsed query, bound to its store; run it as often as needed. Thread-safe. */
public final class Query {

    private final TinyTsdb db;
    private final Expr expr;
    private final String text;

    private Query(TinyTsdb db, Expr expr, String text) {
        this.db = db;
        this.expr = expr;
        this.text = text;
    }

    static Query parse(TinyTsdb db, String promql) {
        return new Query(db, Parser.parse(promql), promql);
    }

    /**
     * Evaluates the query at {@code timeMs}.
     *
     * @throws QueryException when evaluation fails, e.g. a selector matches too many series
     */
    public QueryResult execute(long timeMs) {
        return new Evaluator(db, timeMs).evaluate(expr);
    }

    @Override
    public String toString() {
        return text;
    }
}
