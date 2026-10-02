package gg.nomi.adb.sample;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import gg.nomi.adb.AdbConnection;
import gg.nomi.adb.AdbConnector;
import gg.nomi.adb.AdbKey;

final class LocalAdb {
    private static AdbKey key;

    private LocalAdb() {}

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("adb", Context.MODE_PRIVATE);
    }

    static synchronized AdbKey key(Context context) throws Exception {
        if (key == null) key = AdbKey.loadOrCreate(context.getFilesDir(), "sample@" + Build.MODEL.replace(' ', '_'));
        return key;
    }

    static boolean paired(Context context) {
        return preferences(context).getBoolean("paired", false);
    }

    static void setPaired(Context context, String guid) {
        preferences(context).edit().putBoolean("paired", true).putString("guid", guid).apply();
    }

    static AdbConnection connect(Context context) throws Exception {
        SharedPreferences preferences = preferences(context);
        AdbConnection connection = new AdbConnector(context, key(context))
                .preferPort(preferences.getInt("connectPort", 0))
                .skipPort(EchoHelper.port(context))
                .connect();
        if (connection != null) preferences.edit().putInt("connectPort", connection.port()).apply();
        return connection;
    }
}
