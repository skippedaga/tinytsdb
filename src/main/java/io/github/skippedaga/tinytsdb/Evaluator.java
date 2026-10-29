package io.github.skippedaga.tinytsdb;

import io.github.skippedaga.tinytsdb.Expr.BinaryOp;
import io.github.skippedaga.tinytsdb.Expr.VectorMatching;
import io.github.skippedaga.tinytsdb.QueryResult.Sample;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Evaluates a parsed query at one point in time. Runs under the store's read lock. */
final class Evaluator {

    private static final Comparator<Sample> BY_LABELS = Comparator.comparing(s -> s.labels().toString());

    private final TinyTsdb db;
    private final long time;

    Evaluator(TinyTsdb db, long time) {
        this.db = db;
        this.time = time;
    }

    QueryResult evaluate(Expr expr) {
        Value v = eval(expr);
        if (v.isScalar()) {
            return new QueryResult.Scalar(v.scalar);
        }
        List<Sample> samples = new ArrayList<>(v.vector);
        samples.sort(BY_LABELS);
        return new QueryResult.Vector(samples);
    }

    /** Either a scalar or an instant vector. */
    private record Value(double scalar, List<Sample> vector) {

        static Value of(double scalar) {
            return new Value(scalar, null);
        }

        static Value of(List<Sample> vector) {
            return new Value(Double.NaN, vector);
        }

        boolean isScalar() {
            return vector == null;
        }
    }

    private Value eval(Expr e) {
        if (e instanceof Expr.NumberLiteral n) {
            return Value.of(n.value());
        }
        if (e instanceof Expr.Selector s) {
            List<Labels> series = db.select(s.matchers());
            Map<Labels, List<Reading>> read = db.readMany(series, List.of(Window.allTime()), time);
            List<Sample> out = new ArrayList<>(series.size());
            for (Labels l : series) {
                out.add(new Sample(l, read.get(l).get(0).net()));
            }
            return Value.of(out);
        }
        if (e instanceof Expr.RangeFunction f) {
            TinyTsdb.Tier tier = db.tierFor(f.rangeMs());
            int steps = Math.toIntExact(f.rangeMs() / tier.grain().stepMs());
            List<Labels> series = db.select(f.selector().matchers());
            Map<Labels, List<Reading>> read = db.readMany(series, List.of(Window.rolling(steps, tier.grain())), time);
            List<Sample> out = new ArrayList<>();
            for (Labels l : series) {
                Parts p = read.get(l).get(0).parts();
                // nothing in the window: no sample, as a series without samples in the range in Prometheus
                if (!p.isZero()) {
                    double v = switch (f.op()) {
                        case INCREASE -> p.net();
                        case ADDED -> p.added();
                        case RETRACTED -> p.retracted();
                    };
                    out.add(new Sample(l.withoutMetricName(), v));
                }
            }
            return Value.of(out);
        }
        if (e instanceof Expr.Negate n) {
            Value v = eval(n.expr());
            if (v.isScalar()) {
                return Value.of(-v.scalar);
            }
            List<Sample> out = new ArrayList<>(v.vector.size());
            for (Sample s : v.vector) {
                out.add(new Sample(s.labels().withoutMetricName(), -s.value()));
            }
            return Value.of(out);
        }
        if (e instanceof Expr.Aggregate a) {
            return aggregate(a, eval(a.expr()));
        }
        if (e instanceof Expr.Binary b) {
            return binary(b, eval(b.lhs()), eval(b.rhs()));
        }
        throw new IllegalStateException("Unknown expression " + e);
    }

    private static Value aggregate(Expr.Aggregate a, Value v) {
        if (v.isScalar()) {
            throw new QueryException("Aggregations take an instant vector");
        }
        List<String> drop = new ArrayList<>(a.grouping());
        drop.add(Labels.METRIC_NAME);
        Map<Labels, double[]> groups = new LinkedHashMap<>(); // sum, count, min, max
        for (Sample s : v.vector) {
            Labels key = a.without() ? s.labels().without(drop) : s.labels().keep(a.grouping());
            double[] acc = groups.computeIfAbsent(key,
                    k -> new double[]{0, 0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY});
            acc[0] += s.value();
            acc[1]++;
            acc[2] = Math.min(acc[2], s.value());
            acc[3] = Math.max(acc[3], s.value());
        }
        List<Sample> out = new ArrayList<>(groups.size());
        groups.forEach((key, acc) -> out.add(new Sample(key, switch (a.op()) {
            case SUM -> acc[0];
            case COUNT -> acc[1];
            case MIN -> acc[2];
            case MAX -> acc[3];
            case AVG -> acc[0] / acc[1];
        })));
        return Value.of(out);
    }

    private static Value binary(Expr.Binary b, Value l, Value r) {
        BinaryOp op = b.op();
        if (l.isScalar() && r.isScalar()) {
            return Value.of(op.isComparison() ? (compare(op, l.scalar, r.scalar) ? 1 : 0) : apply(op, l.scalar, r.scalar));
        }
        if (l.isScalar() || r.isScalar()) {
            boolean vectorLeft = r.isScalar();
            List<Sample> vector = vectorLeft ? l.vector : r.vector;
            double scalar = vectorLeft ? r.scalar : l.scalar;
            List<Sample> out = new ArrayList<>(vector.size());
            for (Sample s : vector) {
                double x = vectorLeft ? s.value() : scalar;
                double y = vectorLeft ? scalar : s.value();
                if (op.isComparison()) {
                    boolean ok = compare(op, x, y);
                    if (b.returnBool()) {
                        out.add(new Sample(s.labels().withoutMetricName(), ok ? 1 : 0));
                    } else if (ok) {
                        out.add(s);
                    }
                } else {
                    out.add(new Sample(s.labels().withoutMetricName(), apply(op, x, y)));
                }
            }
            return Value.of(out);
        }
        if (op.isSet()) {
            return set(op, b.matching(), l.vector, r.vector);
        }
        return oneToOne(b, l.vector, r.vector);
    }

    private static Value set(BinaryOp op, VectorMatching m, List<Sample> lhs, List<Sample> rhs) {
        Set<Labels> right = new HashSet<>();
        for (Sample s : rhs) {
            right.add(signature(s.labels(), m));
        }
        List<Sample> out = new ArrayList<>();
        switch (op) {
            case AND -> lhs.stream().filter(s -> right.contains(signature(s.labels(), m))).forEach(out::add);
            case UNLESS -> lhs.stream().filter(s -> !right.contains(signature(s.labels(), m))).forEach(out::add);
            case OR -> {
                Set<Labels> left = new HashSet<>();
                for (Sample s : lhs) {
                    left.add(signature(s.labels(), m));
                    out.add(s);
                }
                rhs.stream().filter(s -> !left.contains(signature(s.labels(), m))).forEach(out::add);
            }
            default -> throw new IllegalStateException(op.name());
        }
        return Value.of(out);
    }

    private static Value oneToOne(Expr.Binary b, List<Sample> lhs, List<Sample> rhs) {
        VectorMatching m = b.matching();
        Map<Labels, Sample> right = new HashMap<>();
        for (Sample s : rhs) {
            if (right.put(signature(s.labels(), m), s) != null) {
                throw new QueryException("Found duplicate series for the match group " + signature(s.labels(), m)
                        + " on the right-hand side; many-to-many matching is not supported");
            }
        }
        Set<Labels> matchedLeft = new HashSet<>();
        Set<Labels> results = new HashSet<>();
        List<Sample> out = new ArrayList<>();
        for (Sample s : lhs) {
            Labels sig = signature(s.labels(), m);
            Sample other = right.get(sig);
            if (other == null) {
                continue;
            }
            if (!matchedLeft.add(sig)) {
                throw new QueryException("Multiple matches for " + sig
                        + " on the left-hand side; many-to-one matching is not supported");
            }
            double value;
            if (b.op().isComparison()) {
                boolean ok = compare(b.op(), s.value(), other.value());
                if (!ok && !b.returnBool()) {
                    continue;
                }
                value = b.returnBool() ? (ok ? 1 : 0) : s.value();
            } else {
                value = apply(b.op(), s.value(), other.value());
            }
            Labels labels = s.labels();
            if (!b.op().isComparison() || b.returnBool()) {
                labels = labels.withoutMetricName();
            }
            labels = m.on() ? labels.keep(m.labels()) : labels.without(m.labels());
            if (!results.add(labels)) {
                throw new QueryException("The result has two series with the labels " + labels);
            }
            out.add(new Sample(labels, value));
        }
        return Value.of(out);
    }

    /** What two series are matched on: the {@code on} labels, or all but the {@code ignoring} ones and the name. */
    private static Labels signature(Labels labels, VectorMatching m) {
        if (m.on()) {
            return labels.keep(m.labels());
        }
        List<String> drop = new ArrayList<>(m.labels());
        drop.add(Labels.METRIC_NAME);
        return labels.without(drop);
    }

    private static double apply(BinaryOp op, double x, double y) {
        return switch (op) {
            case ADD -> x + y;
            case SUB -> x - y;
            case MUL -> x * y;
            case DIV -> x / y;
            default -> throw new IllegalStateException(op.name());
        };
    }

    private static boolean compare(BinaryOp op, double x, double y) {
        return switch (op) {
            case EQL -> x == y;
            case NEQ -> x != y;
            case GTR -> x > y;
            case LSS -> x < y;
            case GTE -> x >= y;
            case LTE -> x <= y;
            default -> throw new IllegalStateException(op.name());
        };
    }
}
