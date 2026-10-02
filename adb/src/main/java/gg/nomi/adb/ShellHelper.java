package gg.nomi.adb;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.function.BooleanSupplier;

public final class ShellHelper {
    public static final String STAGING = "/data/local/tmp";
    private static final long TIMEOUT_MS = 20000;

    private ShellHelper() {}

    public static String nativePath(Context context, String fileName) {
        return new File(context.getApplicationInfo().nativeLibraryDir, fileName).getPath();
    }

    public static void stop(AdbConnection adb, String pattern) throws IOException {
        adb.shell("pkill -f " + Shell.quote(pattern) + " ; true", TIMEOUT_MS);
    }

    public static void start(AdbConnection adb, String executable, String... arguments) throws IOException {
        adb.shell(detached(Shell.quote(executable), arguments), TIMEOUT_MS);
    }

    public static String stage(AdbConnection adb, String source, String name) throws IOException {
        String target = STAGING + "/" + name;
        adb.shell("cp " + Shell.quote(source) + " " + Shell.quote(target) + " && chmod 755 " + Shell.quote(target), TIMEOUT_MS);
        return target;
    }

    public static boolean launch(AdbConnection adb, Context context, String fileName, String stagedName, BooleanSupplier alive,
            long settleMs, String... arguments) throws IOException {
        String source = nativePath(context, fileName);
        start(adb, source, arguments);
        if (waitFor(alive, settleMs)) return true;
        if (stagedName == null) return false;
        start(adb, stage(adb, source, stagedName), arguments);
        return waitFor(alive, settleMs);
    }

    private static String detached(String executable, String... arguments) {
        StringBuilder command = new StringBuilder("setsid ").append(executable);
        if (arguments.length > 0) command.append(' ').append(Shell.join(arguments));
        return command.append(" </dev/null >/dev/null 2>&1 &").toString();
    }

    private static boolean waitFor(BooleanSupplier alive, long settleMs) {
        long deadline = System.currentTimeMillis() + settleMs;
        while (true) {
            if (alive.getAsBoolean()) return true;
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) return false;
            try {
                Thread.sleep(Math.min(250, left));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
