package io.github.skippedaga.tinytsdb;

import java.util.ArrayList;
import java.util.List;

/** Splits a query into tokens. Right after {@code [} it reads a duration ({@code 5m}, {@code 1h30m}). */
final class Lexer {

    enum Kind {
        IDENT, NUMBER, STRING, DURATION,
        LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET, COMMA,
        ASSIGN, NOT_EQUAL_MATCH, REGEX_MATCH, NOT_REGEX_MATCH,
        ADD, SUB, MUL, DIV,
        EQL, NEQ, GTR, LSS, GTE, LTE,
        EOF
    }

    record Token(Kind kind, String text, int pos) {

        @Override
        public String toString() {
            return kind == Kind.EOF ? "end of query" : "'" + text + "'";
        }
    }

    private final String in;
    private int pos;

    private Lexer(String in) {
        this.in = in;
    }

    static List<Token> tokens(String query) {
        Lexer lexer = new Lexer(query);
        List<Token> out = new ArrayList<>();
        Token previous = null;
        while (true) {
            Token t = previous != null && previous.kind() == Kind.LBRACKET ? lexer.duration() : lexer.next();
            out.add(t);
            if (t.kind() == Kind.EOF) {
                return out;
            }
            previous = t;
        }
    }

    private Token next() {
        skipSpace();
        if (pos >= in.length()) {
            return new Token(Kind.EOF, "", pos);
        }
        int start = pos;
        char c = in.charAt(pos);
        if (Character.isLetter(c) || c == '_' || c == ':') {
            while (pos < in.length() && (Character.isLetterOrDigit(in.charAt(pos)) || in.charAt(pos) == '_'
                    || in.charAt(pos) == ':')) {
                pos++;
            }
            return new Token(Kind.IDENT, in.substring(start, pos), start);
        }
        if (Character.isDigit(c) || (c == '.' && pos + 1 < in.length() && Character.isDigit(in.charAt(pos + 1)))) {
            return number(start);
        }
        if (c == '"' || c == '\'') {
            return string(start, c);
        }
        pos++;
        switch (c) {
            case '(':
                return new Token(Kind.LPAREN, "(", start);
            case ')':
                return new Token(Kind.RPAREN, ")", start);
            case '{':
                return new Token(Kind.LBRACE, "{", start);
            case '}':
                return new Token(Kind.RBRACE, "}", start);
            case '[':
                return new Token(Kind.LBRACKET, "[", start);
            case ']':
                return new Token(Kind.RBRACKET, "]", start);
            case ',':
                return new Token(Kind.COMMA, ",", start);
            case '+':
                return new Token(Kind.ADD, "+", start);
            case '-':
                return new Token(Kind.SUB, "-", start);
            case '*':
                return new Token(Kind.MUL, "*", start);
            case '/':
                return new Token(Kind.DIV, "/", start);
            case '=':
                if (peek('=')) {
                    return new Token(Kind.EQL, "==", start);
                }
                if (peek('~')) {
                    return new Token(Kind.REGEX_MATCH, "=~", start);
                }
                return new Token(Kind.ASSIGN, "=", start);
            case '!':
                if (peek('=')) {
                    return new Token(Kind.NOT_EQUAL_MATCH, "!=", start);
                }
                if (peek('~')) {
                    return new Token(Kind.NOT_REGEX_MATCH, "!~", start);
                }
                break;
            case '>':
                return peek('=') ? new Token(Kind.GTE, ">=", start) : new Token(Kind.GTR, ">", start);
            case '<':
                return peek('=') ? new Token(Kind.LTE, "<=", start) : new Token(Kind.LSS, "<", start);
            default:
                break;
        }
        throw new QueryException("Unexpected character '" + c + "' at position " + start);
    }

    private boolean peek(char c) {
        if (pos < in.length() && in.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }

    private Token number(int start) {
        while (pos < in.length() && (Character.isDigit(in.charAt(pos)) || in.charAt(pos) == '.')) {
            pos++;
        }
        if (pos < in.length() && (in.charAt(pos) == 'e' || in.charAt(pos) == 'E')) {
            int mark = pos;
            pos++;
            if (pos < in.length() && (in.charAt(pos) == '+' || in.charAt(pos) == '-')) {
                pos++;
            }
            if (pos < in.length() && Character.isDigit(in.charAt(pos))) {
                while (pos < in.length() && Character.isDigit(in.charAt(pos))) {
                    pos++;
                }
            } else {
                pos = mark;
            }
        }
        return new Token(Kind.NUMBER, in.substring(start, pos), start);
    }

    private Token string(int start, char quote) {
        StringBuilder out = new StringBuilder();
        pos++;
        while (pos < in.length()) {
            char c = in.charAt(pos++);
            if (c == quote) {
                return new Token(Kind.STRING, out.toString(), start);
            }
            if (c == '\\' && pos < in.length()) {
                char e = in.charAt(pos++);
                switch (e) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case '\\', '"', '\'' -> out.append(e);
                    // anything else stays as written, so regular expressions such as "\\d" keep their backslash
                    default -> out.append('\\').append(e);
                }
            } else {
                out.append(c);
            }
        }
        throw new QueryException("Unterminated string starting at position " + start);
    }

    private Token duration() {
        skipSpace();
        int start = pos;
        while (pos < in.length() && Character.isLetterOrDigit(in.charAt(pos))) {
            pos++;
        }
        if (start == pos) {
            throw new QueryException("Expected a duration at position " + start);
        }
        return new Token(Kind.DURATION, in.substring(start, pos), start);
    }

    private void skipSpace() {
        while (pos < in.length() && Character.isWhitespace(in.charAt(pos))) {
            pos++;
        }
    }

    /** {@code 5m}, {@code 1h30m}, {@code 500ms}; units ms, s, m, h, d, w, y (365d), largest first. */
    static long parseDuration(String text) {
        long total = 0;
        int i = 0;
        int lastRank = Integer.MAX_VALUE;
        while (i < text.length()) {
            int digits = i;
            while (i < text.length() && Character.isDigit(text.charAt(i))) {
                i++;
            }
            if (digits == i) {
                throw new QueryException("Invalid duration '" + text + "'");
            }
            long amount = Long.parseLong(text.substring(digits, i));
            int unit = i;
            while (i < text.length() && Character.isLetter(text.charAt(i))) {
                i++;
            }
            String u = text.substring(unit, i);
            long ms;
            int rank;
            switch (u) {
                case "y" -> { ms = 365L * 24 * 3600 * 1000; rank = 7; }
                case "w" -> { ms = 7L * 24 * 3600 * 1000; rank = 6; }
                case "d" -> { ms = 24L * 3600 * 1000; rank = 5; }
                case "h" -> { ms = 3600L * 1000; rank = 4; }
                case "m" -> { ms = 60L * 1000; rank = 3; }
                case "s" -> { ms = 1000L; rank = 2; }
                case "ms" -> { ms = 1L; rank = 1; }
                default -> throw new QueryException("Invalid duration '" + text + "': unknown unit '" + u + "'");
            }
            if (rank >= lastRank) {
                throw new QueryException("Invalid duration '" + text + "': units must go from largest to smallest");
            }
            lastRank = rank;
            total = Math.addExact(total, Math.multiplyExact(amount, ms));
        }
        if (total <= 0) {
            throw new QueryException("Duration must be positive: '" + text + "'");
        }
        return total;
    }
}
