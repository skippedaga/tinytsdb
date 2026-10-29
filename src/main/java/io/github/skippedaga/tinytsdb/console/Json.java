package io.github.skippedaga.tinytsdb.console;

import java.util.Collection;
import java.util.Map;

/**
 * Writes JSON from maps, collections, strings, numbers, booleans and null. Just enough for the console's answers,
 * so the module needs no JSON library.
 */
final class Json {

    private Json() {
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder(256);
        write(value, out);
        return out.toString();
    }

    private static void write(Object v, StringBuilder out) {
        if (v == null) {
            out.append("null");
        } else if (v instanceof String s) {
            string(s, out);
        } else if (v instanceof Boolean b) {
            out.append(b);
        } else if (v instanceof Double d) {
            out.append(Double.isFinite(d) ? (d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString(d.longValue())
                    : d.toString()) : "null");
        } else if (v instanceof Float f) {
            write(f.doubleValue(), out);
        } else if (v instanceof Number n) {
            out.append(n);
        } else if (v instanceof Map<?, ?> m) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                string(String.valueOf(e.getKey()), out);
                out.append(':');
                write(e.getValue(), out);
            }
            out.append('}');
        } else if (v instanceof Collection<?> c) {
            out.append('[');
            boolean first = true;
            for (Object o : c) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(o, out);
            }
            out.append(']');
        } else if (v instanceof Enum<?> e) {
            string(e.name(), out);
        } else {
            string(v.toString(), out);
        }
    }

    private static void string(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                // < > & escaped so a value can never close a script or start markup if a page embeds the answer
                case '<' -> out.append("\\u003c");
                case '>' -> out.append("\\u003e");
                case '&' -> out.append("\\u0026");
                case ' ' -> out.append("\\u2028");
                case ' ' -> out.append("\\u2029");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
