package gg.nomi.adb.sample;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import gg.nomi.adb.AdbConnection;
import gg.nomi.adb.WirelessDebugging;

public final class MainActivity extends Activity {
    private static final int NOTIFICATIONS = 1;

    private interface Task {
        String call() throws Exception;
    }

    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView output;
    private EditText command;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        output = findViewById(R.id.output);
        command = findViewById(R.id.command);
        findViewById(R.id.pair).setOnClickListener(v -> pair());
        findViewById(R.id.connect).setOnClickListener(v -> run(this::connect));
        findViewById(R.id.run).setOnClickListener(v -> {
            String text = command.getText().toString();
            run(() -> shell(text));
        });
        findViewById(R.id.grant).setOnClickListener(v -> run(this::grant));
        findViewById(R.id.wireless).setOnClickListener(v -> run(this::toggleWireless));
        findViewById(R.id.helper_start).setOnClickListener(v -> run(this::startHelper));
        findViewById(R.id.helper_ping).setOnClickListener(v -> run(this::pingHelper));
        findViewById(R.id.helper_stop).setOnClickListener(v -> run(this::stopHelper));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        work.shutdownNow();
        super.onDestroy();
    }

    private void run(Task task) {
        output.setText(R.string.working);
        work.execute(() -> {
            String result;
            try {
                result = task.call();
            } catch (Exception e) {
                result = getString(R.string.failed, String.valueOf(e.getMessage()));
            }
            String shown = result;
            main.post(() -> {
                if (isDestroyed()) return;
                output.setText(shown);
                refresh();
            });
        });
    }

    private void refresh() {
        if (isDestroyed()) return;
        work.execute(() -> {
            String text = getString(R.string.status_lines,
                    answer(WirelessDebugging.developerOptionsOn(this)),
                    answer(WirelessDebugging.isOn(this)),
                    answer(WirelessDebugging.onWifi(this)),
                    answer(LocalAdb.paired(this)),
                    answer(WirelessDebugging.canSwitch(this)),
                    answer(EchoHelper.ping(this) != null));
            main.post(() -> {
                if (!isDestroyed()) status.setText(text);
            });
        });
    }

    private String answer(boolean value) {
        return getString(value ? R.string.yes : R.string.no);
    }

    private void pair() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
            return;
        }
        PairingReceiver.prompt(this, getString(R.string.pair_prompt_title), getString(R.string.pair_prompt_body));
        try {
            startActivity(WirelessDebugging.tileSettingsIntent());
            return;
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
        try {
            startActivity(WirelessDebugging.developerSettingsIntent());
        } catch (ActivityNotFoundException e) {
            output.setText(R.string.no_settings);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != NOTIFICATIONS) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) pair();
        else output.setText(R.string.notifications_needed);
    }

    private String connect() throws Exception {
        try (AdbConnection adb = LocalAdb.connect(this)) {
            if (adb == null) return getString(R.string.unreachable);
            return getString(R.string.connected, adb.host(), adb.port(), adb.banner().trim());
        }
    }

    private String shell(String text) throws Exception {
        try (AdbConnection adb = LocalAdb.connect(this)) {
            if (adb == null) return getString(R.string.unreachable);
            return adb.shell(text, 20000);
        }
    }

    private String grant() throws Exception {
        try (AdbConnection adb = LocalAdb.connect(this)) {
            if (adb == null) return getString(R.string.unreachable);
            return getString(WirelessDebugging.grantSwitch(adb, this) ? R.string.granted : R.string.not_granted);
        }
    }

    private String toggleWireless() {
        if (!WirelessDebugging.canSwitch(this)) return getString(R.string.not_granted);
        boolean on = !WirelessDebugging.isOn(this);
        WirelessDebugging.setOn(this, on);
        return getString(on ? R.string.wireless_on : R.string.wireless_off);
    }

    private String startHelper() throws Exception {
        try (AdbConnection adb = LocalAdb.connect(this)) {
            if (adb == null) return getString(R.string.unreachable);
            String hello = EchoHelper.start(adb, this);
            return hello == null ? getString(R.string.helper_failed) : getString(R.string.helper_running, hello);
        }
    }

    private String pingHelper() {
        String hello = EchoHelper.ping(this);
        return hello == null ? getString(R.string.helper_down) : getString(R.string.helper_running, hello);
    }

    private String stopHelper() throws Exception {
        try (AdbConnection adb = LocalAdb.connect(this)) {
            if (adb == null) return getString(R.string.unreachable);
            EchoHelper.stop(adb);
            return getString(R.string.helper_stopped);
        }
    }
}
