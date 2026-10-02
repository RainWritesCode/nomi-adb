package gg.nomi.adb;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.provider.Settings;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

public final class WirelessDebugging {
    public static final String SETTING = "adb_wifi_enabled";

    private WirelessDebugging() {}

    public static boolean developerOptionsOn(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
    }

    public static boolean isOn(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), SETTING, 0) == 1;
    }

    public static boolean canSwitch(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean setOn(Context context, boolean on) {
        if (!canSwitch(context)) return false;
        try {
            return Settings.Global.putInt(context.getContentResolver(), SETTING, on ? 1 : 0);
        } catch (SecurityException e) {
            return false;
        }
    }

    public static boolean grantSwitch(AdbConnection adb, Context context) throws IOException {
        adb.shell("pm grant " + Shell.quote(context.getPackageName()) + " " + Manifest.permission.WRITE_SECURE_SETTINGS, 20000);
        return canSwitch(context);
    }

    public static boolean onWifi(Context context) {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        Network network = connectivity.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    public static String wifiAddress() {
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.getName().startsWith("wlan")) continue;
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address) return address.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static Intent tileSettingsIntent() {
        return new Intent("android.service.quicksettings.action.QS_TILE_PREFERENCES")
                .setPackage("com.android.settings")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, new ComponentName("com.android.settings",
                        "com.android.settings.development.qstile.DevelopmentTiles$WirelessDebugging"));
    }

    public static Intent developerSettingsIntent() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        Bundle arguments = new Bundle();
        arguments.putString(":settings:fragment_args_key", "toggle_adb_wireless");
        intent.putExtra(":settings:fragment_args_key", "toggle_adb_wireless");
        intent.putExtra(":settings:show_fragment_args", arguments);
        return intent;
    }
}
