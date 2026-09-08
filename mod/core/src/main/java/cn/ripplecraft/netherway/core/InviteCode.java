package cn.ripplecraft.netherway.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Invite codes: a credential folded into one string a player can paste into
 * the vanilla server list as the "server address".
 *
 * <p>Motivation: a server with no reachable entry at all (no port forward,
 * no rented tunnel) cannot hand out credentials the usual way (login or
 * preauth both need a Minecraft port to talk to). With public signaling
 * brokers the credential is self-sufficient — sessionKey + brokers is all
 * the agent needs — so the operator can hand it to players out of band.
 * The vanilla "Add Server" dialog is the one text field every player
 * already knows, and the mod already routes clicks on list entries to warm
 * tunnels, so the invite entry needs no new UI: warm-up picks the code up
 * from the list, the runtime router resolves the click to the tunnel.
 *
 * <p>Format: {@value #PREFIX} followed by URL-safe base64 (no padding) of a
 * compact binary payload. The payload is deliberately not
 * {@link Credentials#encode()}: that layout is optimised for forward
 * compatibility, not size, and the server-address field of every Minecraft
 * version caps at {@value #MAX_LENGTH} characters. Layout (version 1):
 *
 * <pre>
 * u8  format version (1)
 * u8  backend: 1 = gonc-p2p, 0 = explicit (u8 length + UTF-8)
 * u16 suggested punch timeout in seconds (0 = client default)
 * u8  parameter count
 * per parameter:
 *     u8  key: well-known code, or 0 = explicit (u8 length + UTF-8)
 *     u8  value header: bit 7 = value is packed lowercase hex,
 *         bit 6 = value is ASCII with {@link #DICTIONARY} tokens
 *         (bytes 0x80+ stand for a token), bits 0-5 = byte length
 *     bytes
 * </pre>
 *
 * The well-known key table and the token dictionary only shorten the
 * encoding (URL text is what fills an invite code: two broker URLs plus a
 * STUN server take ~170 base64 characters verbatim); an unknown key or a
 * value that gains nothing is carried verbatim, so nothing here interprets
 * backend parameters. Both tables are append-only wire contracts.
 *
 * <p>Client-side identity: an invite entry has no Minecraft host:port, so the
 * credential's origin is {@code invite-<12 hex of SHA-256(code)>} on the
 * default port. Hashing keeps the session key out of route logs and cache
 * names; the router derives the same origin from the list entry text
 * ({@link ServerCandidates#parseEntry}). Invite credentials are never
 * written to the credential cache: the list entry itself is the source of
 * truth, remove it and the tunnel goes away.
 *
 * <p>Security: the code is the session key. Whoever holds it can reach the
 * Minecraft port through the tunnel — exactly what a credential means, and
 * admission stays with the server's whitelist/online-mode (CLAUDE.md,
 * 安全边界). Invite codes therefore need a fixed {@code sessionKey} or the
 * operator re-shares after every restart under {@code sessionKey=auto}.
 */
public final class InviteCode {

    /** Leading marker; also how a list entry is recognised as an invite. */
    public static final String PREFIX = "nw1-";

    /**
     * Longest code we will emit. The vanilla server-address text field has
     * accepted at most 128 characters in every version from 1.7.10 to 1.20.1.
     */
    public static final int MAX_LENGTH = 128;

    /** Origin host prefix of a credential that came from an invite code. */
    static final String ORIGIN_HOST_PREFIX = "invite-";

    private static final byte FORMAT_VERSION = 1;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_VALUE_BYTES = 63;
    private static final int PACKED_HEX = 0x80;
    private static final int DICT_CODED = 0x40;
    private static final int LENGTH_MASK = 0x3F;

    /**
     * Substrings common in broker/STUN URLs, referenced from dictionary-coded
     * values as bytes {@code 0x80 + index}. Append only; never reorder.
     */
    static final String[] DICTIONARY = {
        "tcp://", "udp://", "mqtt://", "mqtts://", "ws://", "wss://", "://",
        "stun:", ":1883", ":8883", ":3478", ":19302",
        ".com", ".net", ".org", ".io", ".cn",
        "broker", "stun", "mqtt", "public", "emqx", "hivemq", "mosquitto", "google",
    };

    private static final int BACKEND_EXPLICIT = 0;
    private static final int BACKEND_GONC_P2P = 1;

    /** Key codes; order is the wire contract, append only. */
    private static final String[] KNOWN_KEYS = {
        null, "sessionKey", Credentials.PARAM_ROOM, Credentials.PARAM_BROKERS,
        "stunServers", "network",
    };

    private InviteCode() {
    }

    /** True if the text (trimmed) starts with the invite marker. */
    public static boolean isInviteCode(String text) {
        return text != null && text.trim().startsWith(PREFIX);
    }

    /**
     * Encodes a credential as an invite code.
     *
     * @return the code, or null when the credential cannot stand alone:
     *         its broker list still carries the {@link Credentials#BROKER_ORIGIN}
     *         placeholder (the embedded rendezvous is on, so signaling needs
     *         the Minecraft entry the invite is meant to replace)
     * @throws IllegalArgumentException when the credential is invite-able but
     *         does not fit: a parameter value over {@value #MAX_VALUE_BYTES}
     *         bytes, or a result over {@value #MAX_LENGTH} characters. The
     *         message is localized and names the offending part.
     */
    public static String encode(Credentials cred) {
        if (cred == null || cred.needsRendezvousAddress()) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(96);
        out.write(FORMAT_VERSION);
        if (Credentials.BACKEND_GONC_P2P.equals(cred.backendId())) {
            out.write(BACKEND_GONC_P2P);
        } else {
            out.write(BACKEND_EXPLICIT);
            writeShortString(out, "backend", cred.backendId());
        }
        int seconds = (int) Math.min(0xFFFF, Math.max(0L, (cred.punchTimeoutMs() + 999L) / 1000L));
        out.write(seconds >>> 8);
        out.write(seconds & 0xFF);
        Map<String, String> params = cred.params();
        if (params.size() > 0xFF) {
            throw new IllegalArgumentException(L10n.tr("invite.tooManyParams", params.size()));
        }
        out.write(params.size());
        for (Map.Entry<String, String> e : params.entrySet()) {
            int code = keyCode(e.getKey());
            out.write(code);
            if (code == 0) {
                writeShortString(out, e.getKey(), e.getKey());
            }
            writeValue(out, e.getKey(), e.getValue());
        }
        String code = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
        if (code.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(L10n.tr("invite.tooLong", code.length(), MAX_LENGTH));
        }
        return code;
    }

    /**
     * Decodes an invite code into a credential carrying its synthetic origin
     * ({@link #originOf}). Surrounding whitespace is ignored.
     *
     * @throws IOException on anything that is not a well-formed version-1
     *         code; the message is localized
     */
    public static Credentials decode(String text) throws IOException {
        String code = text == null ? "" : text.trim();
        if (!code.startsWith(PREFIX)) {
            throw new IOException(L10n.tr("invite.badPrefix", PREFIX));
        }
        byte[] payload;
        try {
            payload = Base64.getUrlDecoder().decode(code.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IOException(L10n.tr("invite.badBase64"));
        }
        Reader in = new Reader(payload);
        int version = in.u8();
        if (version != FORMAT_VERSION) {
            throw new IOException(L10n.tr("invite.badVersion", version, FORMAT_VERSION));
        }
        int backendCode = in.u8();
        String backendId;
        if (backendCode == BACKEND_GONC_P2P) {
            backendId = Credentials.BACKEND_GONC_P2P;
        } else if (backendCode == BACKEND_EXPLICIT) {
            backendId = in.shortString();
        } else {
            throw new IOException(L10n.tr("invite.badBackendCode", backendCode));
        }
        int seconds = (in.u8() << 8) | in.u8();
        int count = in.u8();
        Map<String, String> params = new LinkedHashMap<String, String>();
        for (int i = 0; i < count; i++) {
            int keyCode = in.u8();
            String key;
            if (keyCode == 0) {
                key = in.shortString();
            } else if (keyCode < KNOWN_KEYS.length) {
                key = KNOWN_KEYS[keyCode];
            } else {
                throw new IOException(L10n.tr("invite.badKeyCode", keyCode));
            }
            params.put(key, in.value());
        }
        if (in.remaining() != 0) {
            throw new IOException(L10n.tr("invite.trailingBytes", in.remaining()));
        }
        Credentials cred;
        try {
            cred = new Credentials(backendId, params, seconds * 1000);
        } catch (IllegalArgumentException e) {
            throw new IOException(L10n.tr("invite.badCredentials", e.getMessage()));
        }
        if (cred.needsRendezvousAddress()) {
            // Encode refuses these; a hand-made code with a bare placeholder
            // would make the agent dial a host named "origin".
            throw new IOException(L10n.tr("invite.placeholder", Credentials.BROKER_ORIGIN));
        }
        ServerCandidates.Address origin = originOf(code);
        return cred.withOrigin(origin.host, origin.port);
    }

    /**
     * The synthetic Minecraft entry an invite code stands for on this client:
     * {@code invite-<12 hex of SHA-256(code)>} on the default port. Stable
     * across launches for the same code text, never reveals the key, and
     * derivable from the list entry alone so the runtime router and the
     * credential agree without a lookup table.
     */
    public static ServerCandidates.Address originOf(String text) {
        String code = text == null ? "" : text.trim();
        return ServerCandidates.Address.of(ORIGIN_HOST_PREFIX + fingerprint(code),
                ServerCandidates.DEFAULT_PORT);
    }

    /** True if the credential's origin was synthesised from an invite code. */
    public static boolean isInviteOrigin(Credentials cred) {
        return cred != null && cred.hasOrigin() && cred.originHost().startsWith(ORIGIN_HOST_PREFIX);
    }

    /**
     * Decodes every invite code found among server-list entries. Undecodable
     * ones are reported through {@code bridge.warn} (once per call — the
     * caller decides how often to scan) and skipped; entries that are not
     * invite codes are ignored silently.
     */
    public static List<Credentials> collect(List<String> serverListAddresses, ClientBridge bridge) {
        List<Credentials> out = new ArrayList<Credentials>();
        if (serverListAddresses == null) {
            return out;
        }
        for (String entry : serverListAddresses) {
            if (!isInviteCode(entry)) {
                continue;
            }
            try {
                out.add(decode(entry));
            } catch (IOException e) {
                if (bridge != null) {
                    bridge.warn(L10n.tr("invite.invalid", e.getMessage()), null);
                }
            }
        }
        return out;
    }

    // ---------- encoding helpers ----------

    private static int keyCode(String key) {
        for (int i = 1; i < KNOWN_KEYS.length; i++) {
            if (KNOWN_KEYS[i].equals(key)) {
                return i;
            }
        }
        return 0;
    }

    private static void writeShortString(ByteArrayOutputStream out, String what, String s) {
        byte[] b = s.getBytes(UTF8);
        if (b.length == 0 || b.length > 0xFF) {
            throw new IllegalArgumentException(L10n.tr("invite.valueTooLong", what, 0xFF));
        }
        out.write(b.length);
        out.write(b, 0, b.length);
    }

    private static void writeValue(ByteArrayOutputStream out, String key, String value) {
        int flags = 0;
        byte[] b = packHex(value);
        if (b != null) {
            flags = PACKED_HEX;
        } else {
            b = value.getBytes(UTF8);
            byte[] dict = packDictionary(value);
            if (dict != null && dict.length < b.length) {
                b = dict;
                flags = DICT_CODED;
            }
        }
        if (b.length > MAX_VALUE_BYTES) {
            throw new IllegalArgumentException(L10n.tr("invite.valueTooLong", key, MAX_VALUE_BYTES));
        }
        out.write(flags | b.length);
        out.write(b, 0, b.length);
    }

    /**
     * Greedy longest-token substitution over an ASCII value; null when the
     * value has non-ASCII characters (those bytes would collide with token
     * codes). Exact round trip: tokens are literal substrings.
     */
    private static byte[] packDictionary(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        int i = 0;
        while (i < s.length()) {
            int best = -1;
            int bestLen = 0;
            for (int t = 0; t < DICTIONARY.length; t++) {
                String token = DICTIONARY[t];
                if (token.length() > bestLen && s.startsWith(token, i)) {
                    best = t;
                    bestLen = token.length();
                }
            }
            if (best >= 0) {
                out.write(0x80 | best);
                i += bestLen;
                continue;
            }
            char c = s.charAt(i++);
            if (c >= 0x80) {
                return null;
            }
            out.write(c);
        }
        return out.toByteArray();
    }

    private static String unpackDictionary(byte[] b, int len) throws IOException {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            int v = b[i] & 0xFF;
            if (v < 0x80) {
                sb.append((char) v);
                continue;
            }
            int t = v & 0x7F;
            if (t >= DICTIONARY.length) {
                throw new IOException(L10n.tr("invite.badToken", v));
            }
            sb.append(DICTIONARY[t]);
        }
        return sb.toString();
    }

    /**
     * Even-length lowercase hex packs to half the bytes ({@code sessionKey=auto}
     * yields 32 hex characters). Anything else — uppercase, odd length,
     * empty — travels verbatim so the round trip is exact.
     */
    private static byte[] packHex(String s) {
        int n = s.length();
        if (n == 0 || (n & 1) != 0) {
            return null;
        }
        byte[] out = new byte[n / 2];
        for (int i = 0; i < n; i += 2) {
            int hi = hexNibble(s.charAt(i));
            int lo = hexNibble(s.charAt(i + 1));
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static int hexNibble(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        return -1;
    }

    private static String unpackHex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            int v = b[off + i] & 0xFF;
            sb.append(Character.forDigit(v >>> 4, 16)).append(Character.forDigit(v & 0xF, 16));
        }
        return sb.toString();
    }

    private static String fingerprint(String code) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(code.getBytes(UTF8));
            return unpackHex(hash, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every Java ships SHA-256
        }
    }

    /** Bounds-checked cursor; every short read is a malformed code. */
    private static final class Reader {
        private final byte[] data;
        private int pos;

        Reader(byte[] data) {
            this.data = data;
        }

        int remaining() {
            return data.length - pos;
        }

        int u8() throws IOException {
            if (pos >= data.length) {
                throw new IOException(L10n.tr("invite.truncated"));
            }
            return data[pos++] & 0xFF;
        }

        String shortString() throws IOException {
            int len = u8();
            if (len == 0) {
                throw new IOException(L10n.tr("invite.truncated"));
            }
            return new String(take(len), UTF8);
        }

        String value() throws IOException {
            int header = u8();
            int len = header & LENGTH_MASK;
            byte[] b = take(len);
            boolean hex = (header & PACKED_HEX) != 0;
            boolean dict = (header & DICT_CODED) != 0;
            if (hex && dict) {
                throw new IOException(L10n.tr("invite.badToken", header));
            }
            if (hex) {
                return unpackHex(b, 0, len);
            }
            return dict ? unpackDictionary(b, len) : new String(b, UTF8);
        }

        private byte[] take(int len) throws IOException {
            if (len > remaining()) {
                throw new IOException(L10n.tr("invite.truncated"));
            }
            byte[] b = new byte[len];
            System.arraycopy(data, pos, b, 0, len);
            pos += len;
            return b;
        }
    }
}
