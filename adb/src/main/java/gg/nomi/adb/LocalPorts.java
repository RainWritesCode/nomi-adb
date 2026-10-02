package gg.nomi.adb;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;

public final class LocalPorts {
    private LocalPorts() {}

    public static List<Integer> open(String host, int from, int to, int batch, long waitMs) {
        List<Integer> found = new ArrayList<>();
        for (int start = from; start <= to; start += batch) {
            int end = Math.min(to, start + batch - 1);
            try (Selector selector = Selector.open()) {
                List<SocketChannel> channels = new ArrayList<>();
                for (int port = start; port <= end; port++) {
                    try {
                        SocketChannel channel = SocketChannel.open();
                        channel.configureBlocking(false);
                        channels.add(channel);
                        if (channel.connect(new InetSocketAddress(host, port))) {
                            found.add(port);
                        } else {
                            channel.register(selector, SelectionKey.OP_CONNECT, port);
                        }
                    } catch (IOException ignored) {
                    }
                }
                long deadline = System.currentTimeMillis() + waitMs;
                while (System.currentTimeMillis() < deadline && !selector.keys().isEmpty()) {
                    if (selector.select(Math.max(1, deadline - System.currentTimeMillis())) == 0) continue;
                    for (SelectionKey key : selector.selectedKeys()) {
                        SocketChannel channel = (SocketChannel) key.channel();
                        try {
                            if (channel.finishConnect()) found.add((Integer) key.attachment());
                        } catch (IOException ignored) {
                        }
                        key.cancel();
                    }
                    selector.selectedKeys().clear();
                }
                for (SocketChannel channel : channels) {
                    try {
                        channel.close();
                    } catch (IOException ignored) {
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return found;
    }
}
