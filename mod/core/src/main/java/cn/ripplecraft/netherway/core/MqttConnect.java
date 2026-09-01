package cn.ripplecraft.netherway.core;

/**
 * First-bytes detection of an MQTT CONNECT packet, used to recognise gonc-p2p
 * signaling connections on the Minecraft port.
 *
 * <p>With the embedded rendezvous the gonc-p2p backend runs its MQTT signaling
 * broker on the server's loopback, exactly like frp's embedded frps. A
 * player's signaling connection therefore arrives on the Minecraft port and
 * the sniffer must relay it to the broker. An MQTT session always opens with
 * a CONNECT packet: fixed header byte {@code 0x10} (packet type 1, flags 0),
 * the variable-length "remaining length" (1-4 bytes, continuation bit
 * {@code 0x80}), then the protocol name as a length-prefixed string:
 * {@code 00 04 'M' 'Q' 'T' 'T'} for MQTT 3.1.1/5 or
 * {@code 00 06 'M' 'Q' 'I' 's' 'd' 'p'} for MQTT 3.1.
 *
 * <p>This cannot collide with the other first bytes on that port:
 * <ul>
 *   <li>MC modern handshake: byte 1 is the packet id {@code 0x00}, while a
 *       CONNECT's remaining length is never 0</li>
 *   <li>MC legacy ping: first byte {@code 0xFE}</li>
 *   <li>PROXY protocol: first byte {@code 'P'} or {@code 0x0D}</li>
 *   <li>Pre-auth frame: starts with {@code NWAY} (see {@link PreauthProtocol})</li>
 *   <li>frp control channel: TLS record {@code 0x16 0x03} (see {@link TlsRecord})</li>
 * </ul>
 *
 * <p>Same tri-state convention as {@link TlsRecord#looksLikeHandshake} and
 * deliberately minimal parsing: the sniffer only needs to know which side
 * owns the connection, the bytes are then forwarded untouched and the broker
 * parses them for real. The Go-side broker accepts exactly these protocol
 * names; keep the two in sync.
 */
public final class MqttConnect {

    /** Fixed header of CONNECT: type 1 in the high nibble, flags must be 0. */
    private static final int FIXED_HEADER_CONNECT = 0x10;
    /** Maximum number of remaining-length bytes allowed by the spec. */
    private static final int MAX_LENGTH_BYTES = 4;

    private static final byte[] NAME_MQTT = {0x00, 0x04, 'M', 'Q', 'T', 'T'};
    private static final byte[] NAME_MQISDP = {0x00, 0x06, 'M', 'Q', 'I', 's', 'd', 'p'};

    /** Bytes needed to decide in the worst case: 1 + 4 length bytes + 8 name bytes. */
    public static final int PEEK_BYTES = 1 + MAX_LENGTH_BYTES + 8;

    private MqttConnect() {
    }

    /**
     * Whether the first {@code len} bytes look like an MQTT CONNECT packet.
     *
     * @return {@code TRUE} definitely yes, {@code FALSE} definitely not,
     *         {@code null} not enough bytes yet
     */
    public static Boolean looksLikeConnect(byte[] buf, int len) {
        if (len <= 0) {
            return null;
        }
        if ((buf[0] & 0xFF) != FIXED_HEADER_CONNECT) {
            return Boolean.FALSE;
        }
        // Remaining length: little-endian base-128 varint, continuation bit 0x80.
        int i = 1;
        int lengthBytes = 0;
        int remaining = 0;
        int multiplier = 1;
        while (true) {
            if (i >= len) {
                return null;
            }
            int b = buf[i] & 0xFF;
            remaining += (b & 0x7F) * multiplier;
            multiplier *= 128;
            i++;
            lengthBytes++;
            if ((b & 0x80) == 0) {
                break;
            }
            if (lengthBytes == MAX_LENGTH_BYTES) {
                // Four continuation bytes in a row: malformed by the spec.
                return Boolean.FALSE;
            }
        }
        if (remaining < NAME_MQTT.length) {
            // Cannot even hold the shortest protocol name; in particular a
            // Minecraft handshake (0x10 = packet length, 0x00 = packet id) lands here.
            return Boolean.FALSE;
        }
        Boolean mqtt = matchesPrefix(buf, i, len, NAME_MQTT);
        if (Boolean.TRUE.equals(mqtt)) {
            return Boolean.TRUE;
        }
        Boolean mqisdp = matchesPrefix(buf, i, len, NAME_MQISDP);
        if (Boolean.TRUE.equals(mqisdp)) {
            return Boolean.TRUE;
        }
        if (mqtt == null || mqisdp == null) {
            return null;
        }
        return Boolean.FALSE;
    }

    /** Tri-state prefix comparison of {@code buf[from, len)} against {@code expected}. */
    private static Boolean matchesPrefix(byte[] buf, int from, int len, byte[] expected) {
        for (int k = 0; k < expected.length; k++) {
            if (from + k >= len) {
                return null;
            }
            if (buf[from + k] != expected[k]) {
                return Boolean.FALSE;
            }
        }
        return Boolean.TRUE;
    }
}
