package cn.ripplecraft.netherway.core;

import java.io.IOException;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Short codes: a memorable, operator-chosen string that stands for a whole
 * credential. The operator sets {@code server.shortCode}; a player types
 * {@code nw-<code>} as the "server address" and the mod does the rest.
 *
 * <p>Unlike an {@link InviteCode} the short code carries no data, so both
 * sides must derive the same credential from the text alone: the session key
 * is {@code PBKDF2(normalize(code))}, signaling goes through the default
 * public brokers, the room is fixed. The code is therefore a password — whoever
 * knows it can reach the Minecraft port through the tunnel (admission stays
 * with the server's whitelist/online-mode), and two servers sharing one code
 * share one rendezvous. The slow hash only makes offline guessing of a weak
 * code from an observed topic more expensive; it cannot make a weak code
 * strong ({@link #strengthWarning}).
 *
 * <p>Client-side identity mirrors invite codes: the credential's origin is the
 * synthetic {@code short-<12 hex of SHA-256(normalized code)>} so routing,
 * caching rules and the "which server am I on" bridges need no new concepts.
 * {@link InviteCode} dispatches to this class for {@value #PREFIX} entries.
 *
 * <p>The derivation constants below are a wire contract between client and
 * server builds; changing any of them silently splits the two.
 */
public final class ShortCode {

    /** Leading marker; also how a list entry is recognised as a short code. */
    public static final String PREFIX = "nw-";

    /** Room name shared by every short-code credential. */
    public static final String ROOM = "minecraft";

    /** Origin host prefix of a credential that came from a short code. */
    static final String ORIGIN_HOST_PREFIX = "short-";

    /** Normalized codes shorter than this earn a weakness warning. */
    static final int MIN_RECOMMENDED_LENGTH = 8;

    private static final String SALT = "netherway-shortcode-v1";
    private static final int ITERATIONS = 100000;
    private static final int KEY_BITS = 256;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /**
     * Derived keys by normalized code. The client re-reads the server list
     * every second on the game thread; PBKDF2 must run once per distinct code,
     * not once per scan.
     */
    private static final Map<String, String> KEY_CACHE = new ConcurrentHashMap<String, String>();

    private ShortCode() {
    }

    /** True if the text (trimmed) is {@value #PREFIX} plus at least one character. */
    public static boolean isShortCode(String text) {
        if (text == null) {
            return false;
        }
        String s = text.trim();
        return s.length() > PREFIX.length()
                && s.regionMatches(true, 0, PREFIX, 0, PREFIX.length());
    }

    /**
     * Canonical form of a code, with or without the prefix: trimmed,
     * prefix-stripped, NFKC-folded (full-width letters and compatibility forms
     * collapse), lower-cased. Players mistype case and IMEs emit full-width
     * characters; none of that may change the derived key.
     */
    public static String normalize(String text) {
        String s = text == null ? "" : text.trim();
        if (s.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            s = s.substring(PREFIX.length());
        }
        return Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();
    }

    /** Session key for a code (deterministic; cached per normalized code). */
    public static String deriveSessionKey(String text) {
        String code = normalize(text);
        String key = KEY_CACHE.get(code);
        if (key == null) {
            key = pbkdf2(code);
            KEY_CACHE.put(code, key);
        }
        return key;
    }

    private static String pbkdf2(String code) {
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            PBEKeySpec spec = new PBEKeySpec(code.toCharArray(), SALT.getBytes(UTF8),
                    ITERATIONS, KEY_BITS);
            byte[] raw = f.generateSecret(spec).getEncoded();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e); // PBKDF2WithHmacSHA256 ships since Java 8
        }
    }

    /**
     * The parameters both ends hand to the gonc-p2p backend for a code. The
     * server's second serve process takes these verbatim; {@link #decode}
     * wraps them in a credential.
     */
    public static Map<String, String> params(String text) {
        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("sessionKey", deriveSessionKey(text));
        p.put(Credentials.PARAM_ROOM, ROOM);
        return p;
    }

    /**
     * Credential for a list entry, carrying the synthetic origin
     * ({@link #originOf}).
     *
     * @throws IOException when the text is not a usable short code; the
     *         message is localized
     */
    public static Credentials decode(String text) throws IOException {
        if (!isShortCode(text)) {
            throw new IOException(L10n.tr("shortcode.badPrefix", PREFIX));
        }
        Credentials cred;
        try {
            cred = new Credentials(Credentials.BACKEND_GONC_P2P, params(text), 0);
        } catch (IllegalArgumentException e) {
            throw new IOException(L10n.tr("shortcode.badCredentials", e.getMessage()));
        }
        ServerCandidates.Address origin = originOf(text);
        return cred.withOrigin(origin.host, origin.port);
    }

    /**
     * The synthetic Minecraft entry a short code stands for on this client:
     * {@code short-<12 hex of SHA-256(normalized code)>} on the default port.
     * Stable, derivable from the list entry alone, and does not reveal the key.
     */
    public static ServerCandidates.Address originOf(String text) {
        return ServerCandidates.Address.of(ORIGIN_HOST_PREFIX + fingerprint(normalize(text)),
                ServerCandidates.DEFAULT_PORT);
    }

    /** True if the credential's origin was synthesised from a short code. */
    public static boolean isShortOrigin(Credentials cred) {
        return cred != null && cred.hasOrigin() && cred.originHost().startsWith(ORIGIN_HOST_PREFIX);
    }

    /**
     * Localization key of the warning a code deserves, or null if it looks
     * acceptable: too short, or built from too few distinct characters (a
     * password anyone can guess is an open door to the tunnel).
     */
    public static String strengthWarning(String text) {
        String code = normalize(text);
        if (code.codePointCount(0, code.length()) < MIN_RECOMMENDED_LENGTH) {
            return "shortcode.weakShort";
        }
        if (distinctCodePoints(code) < 4) {
            return "shortcode.weakRepetitive";
        }
        return null;
    }

    private static int distinctCodePoints(String s) {
        java.util.Set<Integer> seen = new java.util.HashSet<Integer>();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            seen.add(cp);
            i += Character.charCount(cp);
        }
        return seen.size();
    }

    private static String fingerprint(String code) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(code.getBytes(UTF8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e); // every Java ships SHA-256
        }
    }
}
