package gg.nomi.adb.sample;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Locale;

import gg.nomi.adb.AdbConnection;
import gg.nomi.adb.ShellHelper;

final class EchoHelper {
    private static final String BINARY = "libechod.so";
    private static final String STAGED = "echod";
    private static final String PATTERN = "[e]chod(\\.so)? --port";

    private EchoHelper() {}

    static synchronized int port(Context context) {
        SharedPreferences preferences = LocalAdb.preferences(context);
        int port = preferences.getInt("helperPort", 0);
        if (port == 0) {
            port = 41000 + new SecureRandom().nextInt(8000);
            preferences.edit().putInt("helperPort", port).apply();
        }
        return port;
    }

    static synchronized String token(Context context) {
        SharedPreferences preferences = LocalAdb.preferences(context);
        String token = preferences.getString("helperToken", null);
        if (token == null) {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            StringBuilder text = new StringBuilder();
            for (byte b : bytes) text.append(String.format(Locale.ROOT, "%02x", b));
            token = text.toString();
            preferences.edit().putString("helperToken", token).apply();
        }
        return token;
    }

    static String start(AdbConnection adb, Context context) throws IOException {
        File directory = context.getExternalFilesDir(null);
        if (directory == null) throw new IOException("no external files directory");
        File tokenFile = new File(directory, "helper.key");
        Files.write(tokenFile.toPath(), token(context).getBytes(StandardCharsets.US_ASCII));
        ShellHelper.stop(adb, PATTERN);
        boolean alive = ShellHelper.launch(adb, context, BINARY, STAGED, () -> ping(context) != null, 1500,
                "--port", String.valueOf(port(context)), "--token-file", tokenFile.getPath());
        return alive ? ping(context) : null;
    }

    static void stop(AdbConnection adb) throws IOException {
        ShellHelper.stop(adb, PATTERN);
    }

    static String ping(Context context) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port(context)), 800);
            socket.setSoTimeout(1500);
            OutputStream out = socket.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            out.write((token(context) + "\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String hello = in.readLine();
            if (hello == null || !hello.startsWith("hello ")) return null;
            out.write("ping\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return "ping".equals(in.readLine()) ? hello.substring(6) : null;
        } catch (IOException e) {
            return null;
        }
    }
}
