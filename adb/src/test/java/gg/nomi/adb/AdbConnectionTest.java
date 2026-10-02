package gg.nomi.adb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

public class AdbConnectionTest {
    private static final int CNXN = 0x4e584e43;
    private static final int OPEN = 0x4e45504f;
    private static final int OKAY = 0x59414b4f;
    private static final int CLSE = 0x45534c43;
    private static final int WRTE = 0x45545257;
    private static final int STLS = 0x534c5453;
    private static final byte[] NONE = new byte[0];
    private static final String BANNER = "device::ro.product.model=fake;features=shell_v2\u0000";

    private static final class Frame {
        int command;
        int arg0;
        int arg1;
        byte[] payload;

        String text() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    private interface Script {
        void run(DataInputStream in, OutputStream out) throws Exception;
    }

    private static final class FakeDevice implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<X509Certificate> client = new AtomicReference<>();

        FakeDevice(SSLContext tls, Script script) throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            thread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    OutputStream out = socket.getOutputStream();
                    Frame hello = read(in);
                    assertEquals(CNXN, hello.command);
                    assertTrue(hello.text().startsWith("host::features="));
                    if (tls != null) {
                        write(out, STLS, 0x01000000, 0, NONE);
                        assertEquals(STLS, read(in).command);
                        SSLSocket secure = (SSLSocket) tls.getSocketFactory().createSocket(socket, null, socket.getPort(), true);
                        secure.setUseClientMode(false);
                        secure.setNeedClientAuth(true);
                        secure.startHandshake();
                        client.set((X509Certificate) secure.getSession().getPeerCertificates()[0]);
                        in = new DataInputStream(secure.getInputStream());
                        out = secure.getOutputStream();
                    }
                    write(out, CNXN, 0x01000001, 4096, BANNER.getBytes(StandardCharsets.UTF_8));
                    script.run(in, out);
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "fake-adbd");
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        X509Certificate clientCertificate() {
            return client.get();
        }

        void await() throws Exception {
            thread.join(10000);
            if (thread.isAlive()) throw new AssertionError("fake device still running");
            if (failure.get() != null) throw new AssertionError(failure.get());
        }

        @Override
        public void close() throws Exception {
            server.close();
            await();
        }
    }

    private static AdbConnection connect(FakeDevice device, AdbKey key) throws Exception {
        return AdbConnection.open("127.0.0.1", device.port(), key, 3000);
    }

    @Test
    public void shellReturnsOutputAndBanner() throws Exception {
        try (FakeDevice device = new FakeDevice(null, (in, out) -> {
            Frame open = read(in);
            assertEquals(OPEN, open.command);
            assertEquals("shell:echo hi\u0000", open.text());
            write(out, OKAY, 7, open.arg0, NONE);
            write(out, WRTE, 7, open.arg0, "hi\n".getBytes(StandardCharsets.UTF_8));
            Frame ack = read(in);
            assertEquals(OKAY, ack.command);
            assertEquals(open.arg0, ack.arg0);
            assertEquals(7, ack.arg1);
            write(out, CLSE, 7, open.arg0, NONE);
            assertEquals(CLSE, read(in).command);
            assertEquals(-1, in.read());
        }); AdbConnection adb = connect(device, null)) {
            assertEquals(BANNER, adb.banner());
            assertEquals("hi\n", adb.shell("echo hi", 3000));
        }
    }

    @Test
    public void refusedStreamThrows() throws Exception {
        try (FakeDevice device = new FakeDevice(null, (in, out) -> {
            Frame open = read(in);
            write(out, CLSE, 0, open.arg0, NONE);
            assertEquals(-1, in.read());
        }); AdbConnection adb = connect(device, null)) {
            IOException error = assertThrows(IOException.class, () -> adb.shell("missing", 3000));
            assertEquals("adb refused the stream", error.getMessage());
        }
    }

    @Test
    public void lateOpenAcknowledgementIsClosed() throws Exception {
        try (FakeDevice device = new FakeDevice(null, (in, out) -> {
            Frame open = read(in);
            Thread.sleep(600);
            write(out, OKAY, 9, open.arg0, NONE);
            Frame reply = read(in);
            assertEquals(CLSE, reply.command);
            assertEquals(0, reply.arg0);
            assertEquals(9, reply.arg1);
        })) {
            try (AdbConnection adb = connect(device, null)) {
                assertThrows(IOException.class, () -> adb.openShell("sleep 5", 200));
                device.await();
            }
        }
    }

    @Test
    public void writeFailsWhenStreamClosesMidWrite() throws Exception {
        try (FakeDevice device = new FakeDevice(null, (in, out) -> {
            Frame open = read(in);
            write(out, OKAY, 5, open.arg0, NONE);
            Frame first = read(in);
            assertEquals(WRTE, first.command);
            assertEquals(4096, first.payload.length);
            Thread.sleep(300);
            write(out, CLSE, 5, open.arg0, NONE);
            assertEquals(CLSE, read(in).command);
            assertEquals(-1, in.read());
        }); AdbConnection adb = connect(device, null)) {
            AdbConnection.Stream stream = adb.openStream("shell:cat", 3000);
            assertThrows(IOException.class, () -> stream.write(new byte[8192]));
        }
    }

    @Test
    public void tlsUpgradePresentsTheAdbKey() throws Exception {
        KeyPair mine = rsa();
        AdbKey key = AdbKey.fromPkcs8(mine.getPrivate().getEncoded(), "test@host");
        try (FakeDevice device = new FakeDevice(deviceContext(), (in, out) -> {
            Frame open = read(in);
            assertEquals("shell:id\u0000", open.text());
            write(out, OKAY, 3, open.arg0, NONE);
            write(out, WRTE, 3, open.arg0, "uid=2000(shell)\n".getBytes(StandardCharsets.UTF_8));
            assertEquals(OKAY, read(in).command);
            write(out, CLSE, 3, open.arg0, NONE);
            assertEquals(CLSE, read(in).command);
        }); AdbConnection adb = connect(device, key)) {
            assertEquals("uid=2000(shell)\n", adb.shell("id", 3000));
            assertArrayEquals(mine.getPublic().getEncoded(), device.clientCertificate().getPublicKey().getEncoded());
        }
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static SSLContext deviceContext() throws Exception {
        KeyPair pair = rsa();
        X509Certificate certificate = Der.selfSigned(pair.getPublic(), pair.getPrivate(), "device");
        char[] password = "device".toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("device", pair.getPrivate(), password, new Certificate[] {certificate});
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password);
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(keys.getKeyManagers(), new TrustManager[] {new AnyClient()}, null);
        return context;
    }

    private static final class AnyClient extends X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {}

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    private static Frame read(InputStream stream) throws IOException {
        DataInputStream in = stream instanceof DataInputStream ? (DataInputStream) stream : new DataInputStream(stream);
        byte[] header = new byte[24];
        in.readFully(header);
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        Frame frame = new Frame();
        frame.command = buffer.getInt();
        frame.arg0 = buffer.getInt();
        frame.arg1 = buffer.getInt();
        int length = buffer.getInt();
        buffer.getInt();
        assertEquals(~frame.command, buffer.getInt());
        frame.payload = new byte[length];
        in.readFully(frame.payload);
        return frame;
    }

    private static void write(OutputStream out, int command, int arg0, int arg1, byte[] payload) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(payload.length).putInt(0).putInt(~command);
        out.write(header.array());
        out.write(payload);
        out.flush();
    }
}
