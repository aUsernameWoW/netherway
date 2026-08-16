package cn.ripplecraft.netherway.core;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 预下发的客户端半边：向 Minecraft 服务器端口要一份直连凭证。
 *
 * <p>单步请求-响应，全在那一个 MC 端口上完成（{@link PreauthProtocol}）。
 * 不做任何身份验证——客户端自报用户名/UUID，服务端直接回凭证。
 * 用一条 TCP 连接，用完即关。
 */
public final class PreauthClient {

    public PreauthClient() {
    }

    /**
     * 向一个候选地址换取凭证。
     *
     * @param connectTimeoutMs TCP 连接与读取的超时
     * @return 换到的凭证；这个地址不提供预下发、或服务端拒绝时抛异常
     */
    public Credentials fetch(ServerCandidates.Address addr, SessionIdentity session,
                             int connectTimeoutMs) throws IOException {
        Socket sock = new Socket();
        try {
            sock.connect(new InetSocketAddress(addr.host, addr.port), connectTimeoutMs);
            sock.setSoTimeout(connectTimeoutMs);
            OutputStream out = sock.getOutputStream();
            DataInputStream in = new DataInputStream(sock.getInputStream());

            out.write(PreauthProtocol.encodeRequest(PreauthProtocol.OP_REQUEST,
                    PreauthProtocol.encodeIdentity(session.username(), session.uuid())));
            out.flush();

            byte[] payload;
            try {
                payload = readReply(in);
            } catch (EOFException e) {
                throw new IOException(L10n.tr("preauth.closedEarly"), e);
            }
            return Credentials.decode(payload);
        } finally {
            closeQuietly(sock);
        }
    }

    /**
     * 读一个响应帧的 payload；OK 返回 payload（凭证裸字节），
     * ERR 抛异常带出原因。
     */
    private static byte[] readReply(DataInputStream in) throws IOException {
        int version = in.readUnsignedByte();
        int status = in.readUnsignedByte();
        int len = in.readUnsignedShort();
        if (len > PreauthProtocol.MAX_PAYLOAD) {
            throw new IOException(L10n.tr("preauth.payloadTooLarge", len));
        }
        byte[] payload = new byte[len];
        in.readFully(payload);
        if (version != PreauthProtocol.VERSION) {
            throw new IOException(L10n.tr("preauth.versionMismatch",
                    version, PreauthProtocol.VERSION));
        }
        if (status != PreauthProtocol.STATUS_OK) {
            String reason = L10n.tr("preauth.noReason");
            try {
                reason = new DataInputStream(new ByteArrayInputStream(payload)).readUTF();
            } catch (IOException ignored) {
                // 拒绝原因读不出来也不影响「被拒绝」这个结论
            }
            throw new IOException(L10n.tr("preauth.rejected", reason));
        }
        return payload;
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // 关不掉也没什么可做的
        }
    }
}
