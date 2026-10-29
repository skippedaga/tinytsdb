package io.github.skippedaga.tinytsdb;

/** A query that cannot be parsed, is outside the supported subset, or cannot be evaluated. */
public class QueryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public QueryException(String message) {
        super(message);
    }
}
