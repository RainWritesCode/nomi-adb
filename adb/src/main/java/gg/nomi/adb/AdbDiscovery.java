package gg.nomi.adb;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class AdbDiscovery {
    public static final String PAIRING = "_adb-tls-pairing._tcp";
    public static final String CONNECT = "_adb-tls-connect._tcp";
    public static final String LOOPBACK = "127.0.0.1";

    private AdbDiscovery() {}

    public static List<InetSocketAddress> pairingTargets(Context context, long timeoutMs) {
        return targets(findOwnPorts(context, PAIRING, timeoutMs));
    }

    public static List<InetSocketAddress> connectTargets(Context context, long timeoutMs) {
        return targets(findOwnPorts(context, CONNECT, timeoutMs));
    }

    public static List<InetSocketAddress> targets(List<Integer> ports) {
        String wifi = WirelessDebugging.wifiAddress();
        List<InetSocketAddress> out = new ArrayList<>();
        for (int port : ports) {
            if (wifi != null) out.add(InetSocketAddress.createUnresolved(wifi, port));
            out.add(InetSocketAddress.createUnresolved(LOOPBACK, port));
        }
        return out;
    }

    @SuppressWarnings("deprecation")
    public static List<Integer> findOwnPorts(Context context, String type, long timeoutMs) {
        NsdManager nsd = context.getSystemService(NsdManager.class);
        LinkedBlockingQueue<NsdServiceInfo> found = new LinkedBlockingQueue<>();
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {}

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {}

            @Override
            public void onDiscoveryStarted(String serviceType) {}

            @Override
            public void onDiscoveryStopped(String serviceType) {}

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                found.offer(info);
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {}
        };
        List<Integer> ports = new ArrayList<>();
        Set<String> own = ownAddresses();
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener);
            long deadline = System.currentTimeMillis() + timeoutMs;
            Set<String> seen = new HashSet<>();
            while (System.currentTimeMillis() < deadline) {
                NsdServiceInfo info = found.poll(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                if (info == null) break;
                if (!seen.add(info.getServiceName())) continue;
                NsdServiceInfo resolved = resolve(nsd, info, 4000);
                if (resolved == null || resolved.getPort() <= 0) continue;
                InetAddress host = resolved.getHost();
                boolean mine = host == null || own.contains(host.getHostAddress()) || host.isLoopbackAddress();
                if (mine && !ports.contains(resolved.getPort())) ports.add(0, resolved.getPort());
                else if (!ports.contains(resolved.getPort())) ports.add(resolved.getPort());
                if (mine) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignored) {
        } finally {
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (RuntimeException ignored) {
            }
        }
        return ports;
    }

    @SuppressWarnings("deprecation")
    private static NsdServiceInfo resolve(NsdManager nsd, NsdServiceInfo info, long timeoutMs) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        NsdServiceInfo[] result = new NsdServiceInfo[1];
        nsd.resolveService(info, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                done.countDown();
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                result[0] = serviceInfo;
                done.countDown();
            }
        });
        done.await(timeoutMs, TimeUnit.MILLISECONDS);
        return result[0];
    }

    private static Set<String> ownAddresses() {
        Set<String> out = new HashSet<>();
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress address : Collections.list(network.getInetAddresses())) out.add(address.getHostAddress());
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
