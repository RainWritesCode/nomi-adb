package gg.nomi.adb;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLSocket;

public final class AdbConnection implements Closeable {
    private static final int CNXN = 0x4e584e43;
    private static final int AUTH = 0x48545541;
    private static final int OPEN = 0x4e45504f;
    private static final int OKAY = 0x59414b4f;
    private static final int CLSE = 0x45534c43;
    private static final int WRTE = 0x45545257;
    private static final int STLS = 0x534c5453;
    private static final int VERSION = 0x01000001;
    private static final int STLS_VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 1024 * 1024;
    private static final String FEATURES = "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,abb,"
            + "fixed_push_symlink_timestamp,abb_exec,remount_shell,track_app,sendrecv_v2\u0000";

    private final Socket socket;
    private final String host;
    private final int port;
    private final DataInputStream in;
    private final OutputStream out;
    private final Map<Integer, Stream> streams = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final String banner;
    private final int maxPayload;
    private volatile boolean closed;

    private static final class Message {
        int command;
        int arg0;
        int arg1;
        byte[] payload;
    }

    private AdbConnection(Socket socket, String host, int port, DataInputStream in, OutputStream out, String banner, int maxPayload) {
        this.socket = socket;
        this.host = host;
        this.port = port;
        this.in = in;
        this.out = out;
        this.banner = banner;
        this.maxPayload = maxPayload;
        Thread reader = new Thread(this::readLoop, "adb-reader");
        reader.setDaemon(true);
        reader.start();
    }

    public static AdbConnection open(String host, int port, AdbKey key, int timeoutMs) throws Exception {
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), timeoutMs);
        raw.setTcpNoDelay(true);
        raw.setSoTimeout(timeoutMs);
        try {
            DataInputStream rawIn = new DataInputStream(raw.getInputStream());
            OutputStream rawOut = raw.getOutputStream();
            write(rawOut, CNXN, VERSION, MAX_PAYLOAD, FEATURES.getBytes(StandardCharsets.UTF_8));
            Message first = read(rawIn);
            Socket active = raw;
            DataInputStream activeIn = rawIn;
            OutputStream activeOut = rawOut;
            if (first.command == STLS) {
                write(rawOut, STLS, STLS_VERSION, 0, new byte[0]);
                SSLSocket tls = (SSLSocket) key.sslContext().getSocketFactory().createSocket(raw, host, port, true);
                tls.setUseClientMode(true);
                tls.setEnabledProtocols(new String[] {"TLSv1.3"});
                tls.startHandshake();
                active = tls;
                activeIn = new DataInputStream(tls.getInputStream());
                activeOut = tls.getOutputStream();
                first = read(activeIn);
            }
            if (first.command == AUTH) throw new IOException("device asked for legacy authentication");
            if (first.command != CNXN) throw new IOException("unexpected reply " + Integer.toHexString(first.command));
            active.setSoTimeout(0);
            String banner = new String(first.payload, StandardCharsets.UTF_8);
            return new AdbConnection(active, host, port, activeIn, activeOut, banner, Math.min(first.arg1, MAX_PAYLOAD));
        } catch (Exception e) {
            try {
                raw.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String banner() {
        return banner;
    }

    public boolean isOpen() {
        return !closed;
    }

    public Stream openStream(String service, long timeoutMs) throws IOException {
        if (closed) throw new IOException("connection closed");
        int local = nextId.getAndIncrement();
        Stream stream = new Stream(local);
        streams.put(local, stream);
        try {
            send(OPEN, local, 0, (service + "\u0000").getBytes(StandardCharsets.UTF_8));
            stream.awaitOpen(timeoutMs);
        } catch (IOException e) {
            streams.remove(local);
            stream.finish();
            throw e;
        }
        return stream;
    }

    public Stream openShell(String command, long timeoutMs) throws IOException {
        return openStream("shell:" + command, timeoutMs);
    }

    public Stream openExec(String command, long timeoutMs) throws IOException {
        return openStream("exec:" + command, timeoutMs);
    }

    public String shell(String command, long timeoutMs) throws IOException {
        return new String(openShell(command, timeoutMs).readAll(timeoutMs), StandardCharsets.UTF_8);
    }

    public byte[] exec(String command, long timeoutMs) throws IOException {
        return openExec(command, timeoutMs).readAll(timeoutMs);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        for (Stream stream : streams.values()) stream.finish();
        streams.clear();
    }

    private void readLoop() {
        try {
            while (!closed) {
                Message message = read(in);
                Stream stream = streams.get(message.arg1);
                switch (message.command) {
                    case OKAY:
                        if (stream != null) stream.acknowledged(message.arg0);
                        else send(CLSE, 0, message.arg0, new byte[0]);
                        break;
                    case WRTE:
                        if (stream != null) {
                            stream.received(message.payload);
                            send(OKAY, message.arg1, message.arg0, new byte[0]);
                        }
                        break;
                    case CLSE:
                        if (stream != null) {
                            streams.remove(message.arg1);
                            if (stream.remoteId != 0) send(CLSE, message.arg1, message.arg0, new byte[0]);
                            stream.finish();
                        }
                        break;
                    default:
                        break;
                }
            }
        } catch (IOException e) {
            close();
        }
    }

    private void send(int command, int arg0, int arg1, byte[] payload) throws IOException {
        synchronized (out) {
            write(out, command, arg0, arg1, payload);
        }
    }

    private static void write(OutputStream out, int command, int arg0, int arg1, byte[] payload) throws IOException {
        int checksum = 0;
        for (byte b : payload) checksum += b & 0xff;
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(payload.length).putInt(checksum).putInt(~command);
        byte[] frame = new byte[24 + payload.length];
        System.arraycopy(header.array(), 0, frame, 0, 24);
        System.arraycopy(payload, 0, frame, 24, payload.length);
        out.write(frame);
        out.flush();
    }

    private static Message read(DataInputStream in) throws IOException {
        byte[] header = new byte[24];
        in.readFully(header);
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        Message message = new Message();
        message.command = buffer.getInt();
        message.arg0 = buffer.getInt();
        message.arg1 = buffer.getInt();
        int length = buffer.getInt();
        buffer.getInt();
        int magic = buffer.getInt();
        if (magic != ~message.command) throw new IOException("bad adb frame");
        if (length < 0 || length > MAX_PAYLOAD * 2) throw new IOException("bad adb length");
        message.payload = new byte[length];
        in.readFully(message.payload);
        return message;
    }

    public final class Stream extends InputStream implements Closeable {
        private final int localId;
        private volatile int remoteId;
        private final LinkedBlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
        private final Semaphore writable = new Semaphore(0);
        private final Semaphore opened = new Semaphore(0);
        private volatile boolean finished;
        private byte[] current;
        private int position;
        private final byte[] end = new byte[0];

        private Stream(int localId) {
            this.localId = localId;
        }

        void acknowledged(int remote) {
            if (remoteId == 0) {
                remoteId = remote;
                opened.release();
            }
            writable.release();
        }

        void received(byte[] data) {
            chunks.offer(data);
        }

        void finish() {
            if (finished) return;
            finished = true;
            chunks.offer(end);
            opened.release();
            writable.release();
        }

        void awaitOpen(long timeoutMs) throws IOException {
            try {
                if (!opened.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) throw new IOException("adb stream timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            if (remoteId == 0) throw new IOException("adb refused the stream");
        }

        public byte[] next(long timeoutMs) throws IOException {
            try {
                byte[] chunk = timeoutMs < 0 ? chunks.take() : chunks.poll(timeoutMs, TimeUnit.MILLISECONDS);
                if (chunk == end) {
                    chunks.offer(end);
                    return null;
                }
                return chunk == null ? new byte[0] : chunk;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }

        public byte[] readAll(long timeoutMs) throws IOException {
            ByteArrayOutputStream collected = new ByteArrayOutputStream();
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    close();
                    throw new IOException("adb command timed out");
                }
                byte[] chunk = next(left);
                if (chunk == null) return collected.toByteArray();
                collected.write(chunk, 0, chunk.length);
            }
        }

        public void write(byte[] data) throws IOException {
            int offset = 0;
            while (offset < data.length) {
                if (finished) throw new IOException("stream closed");
                try {
                    writable.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
                if (finished) throw new IOException("stream closed");
                int size = Math.min(maxPayload, data.length - offset);
                byte[] part = new byte[size];
                System.arraycopy(data, offset, part, 0, size);
                send(WRTE, localId, remoteId, part);
                offset += size;
            }
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int got = read(one, 0, 1);
            return got < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            while (current == null || position >= current.length) {
                current = next(-1);
                position = 0;
                if (current == null) return -1;
            }
            int size = Math.min(length, current.length - position);
            System.arraycopy(current, position, target, offset, size);
            position += size;
            return size;
        }

        @Override
        public void close() {
            if (!finished) {
                streams.remove(localId);
                try {
                    if (remoteId != 0) send(CLSE, localId, remoteId, new byte[0]);
                } catch (IOException ignored) {
                }
                finish();
            }
        }
    }
}
