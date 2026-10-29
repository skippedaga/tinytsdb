package io.github.skippedaga.tinytsdb;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One label matcher of a selector: {@code name="v"}, {@code !=}, {@code =~} or {@code !~}. A missing label matches
 * as {@code ""}. Regular expressions are Java's, anchored at both ends as in PromQL.
 */
public final class Matcher {

    public enum Type {
        EQUAL("="), NOT_EQUAL("!="), REGEX("=~"), NOT_REGEX("!~");

        private final String symbol;

        Type(String symbol) {
            this.symbol = symbol;
        }
    }

    private final Type type;
    private final String name;
    private final String value;
    private final Pattern pattern;

    private Matcher(Type type, String name, String value) {
        this.type = type;
        this.name = name;
        this.value = value;
        Pattern p = null;
        if (type == Type.REGEX || type == Type.NOT_REGEX) {
            try {
                p = Pattern.compile(value);
            } catch (PatternSyntaxException e) {
                throw new QueryException("Invalid regular expression for " + name + ": " + e.getDescription());
            }
        }
        this.pattern = p;
    }

    public static Matcher of(Type type, String name, String value) {
        return new Matcher(type, name, value);
    }

    public Type type() {
        return type;
    }

    public String name() {
        return name;
    }

    public String value() {
        return value;
    }

    public boolean matches(String labelValue) {
        return switch (type) {
            case EQUAL -> value.equals(labelValue);
            case NOT_EQUAL -> !value.equals(labelValue);
            case REGEX -> pattern.matcher(labelValue).matches();
            case NOT_REGEX -> !pattern.matcher(labelValue).matches();
        };
    }

    @Override
    public String toString() {
        return name + type.symbol + '"' + value + '"';
    }
}
