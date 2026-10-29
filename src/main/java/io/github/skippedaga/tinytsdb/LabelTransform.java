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
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("HMAC key must not be empty");
        }
        SecretKeySpec spec = new SecretKeySpec(key.clone(), "HmacSHA256");
        try {
            Mac.getInstance("HmacSHA256").init(spec);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        return v -> {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(spec);
                return prefix + base64.encodeToString(mac.doFinal(v.getBytes(StandardCharsets.UTF_8)));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("HmacSHA256 failed", e);
            }
        };
    }

    /**
     * {@code none}, {@code upper}, {@code prefix:N}, {@code bucket:N} or {@code hmac} (which needs {@code hmacKey};
     * values get the prefix {@code hmacPrefix}).
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
        throw new IllegalArgumentException("Unknown transform " + spec + "; none, upper, hmac, prefix:N, bucket:N");
    }
}
