package io.github.skippedaga.tinytsdb;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Locale;

/**
 * Rewrites a label value before anything is stored or looked up, so a raw identifier never reaches the store. Applied
 * to writes, to reads by labels, and to {@code =} / {@code !=} matchers in queries (regular expressions see the
 * stored values).
 *
 * <p>{@link #apply} returns null to drop the label from the series.</p>
 */
@FunctionalInterface
public interface LabelTransform {

    /** @return the value to store, or null to leave the label out */
    String apply(String value);

    static LabelTransform none() {
        return v -> v;
    }

    /** Upper case, root locale. */
    static LabelTransform upper() {
        return v -> v.toUpperCase(Locale.ROOT);
    }

    /** The first {@code n} characters, or the whole value when shorter. */
    static LabelTransform prefix(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("prefix length must be positive");
        }
        return v -> v.length() <= n ? v : v.substring(0, n);
    }

    /** A number floored to a multiple of {@code n} (negatives floor down); a value that is not a number drops the label. */
    static LabelTransform bucket(long n) {
        if (n <= 0) {
            throw new IllegalArgumentException("bucket size must be positive");
        }
        return v -> {
            try {
                return Long.toString(Math.floorDiv(Long.parseLong(v.trim()), n) * n);
            } catch (NumberFormatException e) {
                return null;
            }
        };
    }

    /**
     * HMAC-SHA256 of the value with the caller's key: {@code prefix} + the full 32 bytes in base64url without padding.
     * The prefix marks a hashed value so it is never taken for a raw one.
     */
    static LabelTransform hmac(byte[] key, String prefix) {
        return hmac(key, prefix, false);
    }

    /**
     * Like {@link #hmac(byte[], String)}; with {@code keepHashed}, a value that already has the shape of one of its
     * outputs ({@code prefix} + 43 base64url characters) passes through unchanged, so hashing is idempotent. Use it when
     * values may arrive already hashed. Anyone who knows a hashed value can then address that series by it; that is the
     * point, and why it is off by default.
     */
    static LabelTransform hmac(byte[] key, String prefix, boolean keepHashed) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("HMAC key must not be empty");
        }
        if (keepHashed && (prefix == null || prefix.isEmpty())) {
            throw new IllegalArgumentException("keepHashed needs a prefix to recognise hashed values");
        }
        SecretKeySpec spec = new SecretKeySpec(key.clone(), "HmacSHA256");
        try {
            Mac.getInstance("HmacSHA256").init(spec);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        return v -> {
            if (keepHashed && isHashed(v, prefix)) {
                return v;
            }
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(spec);
                return prefix + base64.encodeToString(mac.doFinal(v.getBytes(StandardCharsets.UTF_8)));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("HmacSHA256 failed", e);
            }
        };
    }

    /** Whether {@code v} is {@code prefix} followed by exactly 43 base64url characters, an HMAC-SHA256 output. */
    private static boolean isHashed(String v, String prefix) {
        if (v.length() != prefix.length() + 43 || !v.startsWith(prefix)) {
            return false;
        }
        for (int i = prefix.length(); i < v.length(); i++) {
            char c = v.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_')) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code none}, {@code upper}, {@code prefix:N}, {@code bucket:N}, {@code hmac} or {@code hmac:keep} (both need
     * {@code hmacKey}; values get the prefix {@code hmacPrefix}; {@code hmac:keep} passes already hashed values through).
     *
     * @throws IllegalArgumentException on an unknown spec
     */
    static LabelTransform parse(String spec, byte[] hmacKey, String hmacPrefix) {
        String s = spec.trim();
        if (s.equals("none")) {
            return none();
        }
        if (s.equals("upper")) {
            return upper();
        }
        if (s.equals("hmac")) {
            return hmac(hmacKey, hmacPrefix);
        }
        if (s.equals("hmac:keep")) {
            return hmac(hmacKey, hmacPrefix, true);
        }
        int colon = s.indexOf(':');
        if (colon > 0) {
            String kind = s.substring(0, colon);
            long n;
            try {
                n = Long.parseLong(s.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Bad transform " + spec);
            }
            if (kind.equals("prefix")) {
                return prefix(Math.toIntExact(n));
            }
            if (kind.equals("bucket")) {
                return bucket(n);
            }
        }
        throw new IllegalArgumentException("Unknown transform " + spec + "; none, upper, hmac, hmac:keep, prefix:N, bucket:N");
    }
}
