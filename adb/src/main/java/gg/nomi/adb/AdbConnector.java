package gg.nomi.adb;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AdbConnector {
    private final Context context;
    private final AdbKey key;
    private final Set<Integer> skipped = new HashSet<>();
    private int preferred;
    private int timeoutMs = 2500;
    private long discoveryMs = 4000;
    private boolean scan = true;
    private int scanFrom = 30000;
    private int scanTo = 65535;

    public AdbConnector(Context context, AdbKey key) {
        this.context = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        this.key = key;
    }

    public AdbConnector preferPort(int port) {
        preferred = port;
        return this;
    }

    public AdbConnector skipPort(int port) {
        skipped.add(port);
        return this;
    }

    public AdbConnector timeout(int ms) {
        timeoutMs = ms;
        return this;
    }

    public AdbConnector discovery(long ms) {
        discoveryMs = ms;
        return this;
    }

    public AdbConnector scan(boolean enabled) {
        scan = enabled;
        return this;
    }

    public AdbConnector scanRange(int from, int to) {
        scanFrom = from;
        scanTo = to;
        return this;
    }

    public AdbConnection connect() {
        Set<Integer> tried = new HashSet<>(skipped);
        if (preferred > 0 && tried.add(preferred)) {
            AdbConnection quick = tryPort(preferred);
            if (quick != null) return quick;
        }
        for (int port : AdbDiscovery.findOwnPorts(context, AdbDiscovery.CONNECT, discoveryMs)) {
            if (!tried.add(port)) continue;
            AdbConnection found = tryPort(port);
            if (found != null) return found;
        }
        if (!scan) return null;
        for (int port : scanPorts(tried)) {
            if (!tried.add(port)) continue;
            AdbConnection found = tryPort(port);
            if (found != null) return found;
        }
        return null;
    }

    public AdbConnection tryPort(int port) {
        for (String host : hosts()) {
            try {
                return AdbConnection.open(host, port, key, timeoutMs);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private List<Integer> scanPorts(Set<Integer> excluded) {
        List<Integer> ports = new ArrayList<>(LocalPorts.open(AdbDiscovery.LOOPBACK, scanFrom, scanTo, 1024, 150));
        ports.removeAll(excluded);
        if (ports.isEmpty()) {
            String wifi = WirelessDebugging.wifiAddress();
            if (wifi != null) ports.addAll(LocalPorts.open(wifi, scanFrom, scanTo, 1024, 150));
            ports.removeAll(excluded);
        }
        return ports;
    }

    private List<String> hosts() {
        List<String> out = new ArrayList<>();
        out.add(AdbDiscovery.LOOPBACK);
        String wifi = WirelessDebugging.wifiAddress();
        if (wifi != null) out.add(wifi);
        return out;
    }
}
