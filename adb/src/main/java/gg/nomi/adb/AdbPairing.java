package gg.nomi.adb;

import android.net.ssl.SSLSockets;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSocket;

public final class AdbPairing {
    private static final String EXPORT_LABEL = "adb-label\u0000";
    private static final int PEER_INFO_SIZE = 8192;
    private static final byte TYPE_SPAKE = 0;
    private static final byte TYPE_PEER_INFO = 1;

    public static final class WrongCodeException extends IOException {
        private static final long serialVersionUID = 1L;

        WrongCodeException() {
            super("pairing code did not match");
        }
    }

    private AdbPairing() {}

    public static String pair(InetSocketAddress target, String code, AdbKey key) throws Exception {
        return pair(target.getHostString(), target.getPort(), code, key);
    }

    public static String pairFirst(List<InetSocketAddress> targets, String code, AdbKey key) throws Exception {
        Exception last = null;
        for (InetSocketAddress target : targets) {
            try {
                return pair(target, code, key);
            } catch (WrongCodeException e) {
                throw e;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("no pairing service found");
    }

    public static String pair(String host, int port, String code, AdbKey key) throws Exception {
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), 5000);
        raw.setTcpNoDelay(true);
        raw.setSoTimeout(15000);
        try (SSLSocket tls = (SSLSocket) key.sslContext().getSocketFactory().createSocket(raw, host, port, true)) {
            tls.setUseClientMode(true);
            tls.setEnabledProtocols(new String[] {"TLSv1.3"});
            tls.startHandshake();
            byte[] exported = SSLSockets.exportKeyingMaterial(tls, EXPORT_LABEL, null, 64);
            byte[] codeBytes = code.getBytes(StandardCharsets.US_ASCII);
            byte[] password = new byte[codeBytes.length + exported.length];
            System.arraycopy(codeBytes, 0, password, 0, codeBytes.length);
            System.arraycopy(exported, 0, password, codeBytes.length, exported.length);

            byte[] random = new byte[64];
            new SecureRandom().nextBytes(random);
            long spake = Spake2.start(password, random);
            if (spake == 0) throw new IOException("pairing setup failed");
            DataInputStream in = new DataInputStream(tls.getInputStream());
            OutputStream out = tls.getOutputStream();
            byte[] theirs;
            try {
                writePacket(out, TYPE_SPAKE, Spake2.message(spake));
                theirs = readPacket(in, TYPE_SPAKE);
            } catch (IOException | RuntimeException e) {
                Spake2.finish(spake, new byte[32]);
                throw e;
            }
            byte[] keyMaterial = Spake2.finish(spake, theirs);
            if (keyMaterial == null) throw new WrongCodeException();
            SecretKeySpec aes = new SecretKeySpec(hkdf(keyMaterial, "adb pairing_auth aes-128-gcm key", 16), "AES");

            byte[] info = new byte[PEER_INFO_SIZE];
            info[0] = 0;
            byte[] publicKey = key.adbPublicKey();
            System.arraycopy(publicKey, 0, info, 1, Math.min(publicKey.length, PEER_INFO_SIZE - 1));
            Cipher seal = Cipher.getInstance("AES/GCM/NoPadding");
            seal.init(Cipher.ENCRYPT_MODE, aes, new GCMParameterSpec(128, nonce(0)));
            writePacket(out, TYPE_PEER_INFO, seal.doFinal(info));

            byte[] sealed;
            try {
                sealed = readPacket(in, TYPE_PEER_INFO);
            } catch (IOException e) {
                throw new WrongCodeException();
            }
            Cipher open = Cipher.getInstance("AES/GCM/NoPadding");
            open.init(Cipher.DECRYPT_MODE, aes, new GCMParameterSpec(128, nonce(0)));
            byte[] peer;
            try {
                peer = open.doFinal(sealed);
            } catch (AEADBadTagException e) {
                throw new WrongCodeException();
            }
            int end = 1;
            while (end < peer.length && peer[end] != 0) end++;
            return new String(peer, 1, end - 1, StandardCharsets.US_ASCII);
        } finally {
            try {
                raw.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static byte[] nonce(long counter) {
        byte[] nonce = new byte[12];
        for (int i = 0; i < 8; i++) nonce[i] = (byte) (counter >>> (8 * i));
        return nonce;
    }

    private static byte[] hkdf(byte[] input, String info, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        byte[] prk = mac.doFinal(input);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info.getBytes(StandardCharsets.US_ASCII));
        mac.update((byte) 1);
        byte[] block = mac.doFinal();
        byte[] out = new byte[length];
        System.arraycopy(block, 0, out, 0, length);
        return out;
    }

    private static void writePacket(OutputStream out, byte type, byte[] payload) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(6);
        header.put((byte) 1).put(type).putInt(payload.length);
        out.write(header.array());
        out.write(payload);
        out.flush();
    }

    private static byte[] readPacket(DataInputStream in, byte type) throws IOException {
        byte[] header = new byte[6];
        in.readFully(header);
        ByteBuffer buffer = ByteBuffer.wrap(header);
        byte version = buffer.get();
        byte got = buffer.get();
        int length = buffer.getInt();
        if (version != 1 || got != type || length < 0 || length > PEER_INFO_SIZE * 2) throw new IOException("bad pairing packet");
        byte[] payload = new byte[length];
        in.readFully(payload);
        return payload;
    }
}
