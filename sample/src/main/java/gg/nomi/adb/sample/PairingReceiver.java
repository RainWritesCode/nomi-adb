package gg.nomi.adb.sample;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Bundle;

import java.net.InetSocketAddress;
import java.util.List;

import gg.nomi.adb.AdbDiscovery;
import gg.nomi.adb.AdbPairing;

public final class PairingReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "pairing";
    private static final String CODE = "code";
    private static final int NOTIFICATION = 1;

    static void prompt(Context context, String title, String text) {
        NotificationManager manager = manager(context);
        PendingIntent reply = PendingIntent.getBroadcast(context, 0, new Intent(context, PairingReceiver.class),
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        RemoteInput input = new RemoteInput.Builder(CODE).setLabel(context.getString(R.string.pair_code_hint)).build();
        Notification.Action action = new Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_pair),
                context.getString(R.string.pair_enter), reply).addRemoteInput(input).setAllowGeneratedReplies(false).build();
        Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_pair)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentIntent(openApp(context))
                .addAction(action)
                .build();
        manager.notify(NOTIFICATION, notification);
    }

    private static void status(Context context, String text, boolean working) {
        Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_pair)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(working)
                .setAutoCancel(!working)
                .setContentIntent(openApp(context));
        if (working) builder.setProgress(0, 0, true);
        manager(context).notify(NOTIFICATION, builder.build());
    }

    private static NotificationManager manager(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, context.getString(R.string.channel_pairing),
                NotificationManager.IMPORTANCE_HIGH));
        return manager;
    }

    private static PendingIntent openApp(Context context) {
        Intent intent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        CharSequence typed = results == null ? null : results.getCharSequence(CODE);
        String code = typed == null ? "" : typed.toString().replaceAll("[^0-9]", "");
        if (code.length() != 6) {
            prompt(context, context.getString(R.string.pair_six_digits), context.getString(R.string.pair_prompt_body));
            return;
        }
        Context app = context.getApplicationContext();
        status(app, app.getString(R.string.pair_working), true);
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                pair(app, code);
            } finally {
                pending.finish();
            }
        }, "adb-pair").start();
    }

    private static void pair(Context context, String code) {
        try {
            List<InetSocketAddress> targets = AdbDiscovery.pairingTargets(context, 6000);
            if (targets.isEmpty()) {
                prompt(context, context.getString(R.string.pair_no_window_title), context.getString(R.string.pair_no_window));
                return;
            }
            String guid = AdbPairing.pairFirst(targets, code, LocalAdb.key(context));
            LocalAdb.setPaired(context, guid);
            status(context, context.getString(R.string.pair_done, guid), false);
        } catch (AdbPairing.WrongCodeException e) {
            prompt(context, context.getString(R.string.pair_wrong_code_title), context.getString(R.string.pair_wrong_code));
        } catch (Exception e) {
            prompt(context, context.getString(R.string.pair_failed_title), String.valueOf(e.getMessage()));
        }
    }
}
