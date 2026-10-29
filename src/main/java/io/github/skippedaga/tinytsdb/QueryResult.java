package io.github.skippedaga.tinytsdb;

import java.util.List;

/** What a query returns: a scalar, or an instant vector (samples sorted by their labels). */
public sealed interface QueryResult {

    /** One series of an instant vector. */
    record Sample(Labels labels, double value) {
    }

    record Scalar(double value) implements QueryResult {
    }

    record Vector(List<Sample> samples) implements QueryResult {

        public Vector {
            samples = List.copyOf(samples);
        }
    }

    /** @throws QueryException when the result is a scalar */
    default List<Sample> vector() {
        if (this instanceof Vector v) {
            return v.samples();
        }
        throw new QueryException("The query returns a scalar, not a vector");
    }

    /** @throws QueryException when the result is a vector */
    default double scalar() {
        if (this instanceof Scalar s) {
            return s.value();
        }
        throw new QueryException("The query returns a vector, not a scalar");
    }
}
