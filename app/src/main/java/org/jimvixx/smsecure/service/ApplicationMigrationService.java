/*
 * Copyright (C) 2015 Open Whisper Systems
 * Copyright (C) 2025 Jimvixx
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.jimvixx.smsecure.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.BitmapFactory;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.jimvixx.smsecure.ConversationListActivity;
import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.SmsMigrator;
import org.jimvixx.smsecure.database.SmsMigrator.ProgressDescription;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.notifications.NotificationChannels;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class ApplicationMigrationService extends Service
        implements SmsMigrator.SmsMigrationProgressListener {
  public static final String MIGRATE_DATABASE = "org.jimvixx.smsecure.ApplicationMigration.MIGRATE_DATABSE";
  public static final String COMPLETED_ACTION = "org.jimvixx.smsecure.ApplicationMigrationService.COMPLETED";
  private static final String TAG = ApplicationMigrationService.class.getSimpleName();
  private static final String PREFERENCES_NAME = "SecureSMS";
  private static final String DATABASE_MIGRATED = "migrated";
  private static final int MIGRATION_NOTIFICATION_ID = 4242;

  private final BroadcastReceiver completedReceiver = new CompletedReceiver();
  private final Binder binder = new ApplicationMigrationBinder();
  private final Executor executor = Executors.newSingleThreadExecutor();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  private WeakReference<Handler> handler = null;
  private NotificationCompat.Builder notification = null;
  private ImportState state = new ImportState(ImportState.STATE_IDLE, null);

  public static boolean isDatabaseNotImported(Context context) {
    return !context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(DATABASE_MIGRATED, false);
  }

  public static void setDatabaseImported(Context context) {
    context.getSharedPreferences(PREFERENCES_NAME, 0)
            .edit()
            .putBoolean(DATABASE_MIGRATED, true)
            .apply();
  }

  @Override
  public void onCreate() {
    super.onCreate();
    registerCompletedReceiver();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null) return START_NOT_STICKY;

    if (intent.getAction() != null && intent.getAction().equals(MIGRATE_DATABASE)) {
      executor.execute(new ImportRunnable(intent));
    }

    return START_NOT_STICKY;
  }

  @Override
  public void onDestroy() {
    unregisterCompletedReceiver();
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  public void setImportStateHandler(Handler handler) {
    this.handler = new WeakReference<>(handler);
  }

  private void registerCompletedReceiver() {
    IntentFilter filter = new IntentFilter();
    filter.addAction(COMPLETED_ACTION);

    ContextCompat.registerReceiver(
            this,
            completedReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
    );
  }

  private void unregisterCompletedReceiver() {
    try {
      unregisterReceiver(completedReceiver);
    } catch (IllegalArgumentException ignored) {
    }
  }

  private void notifyImportComplete() {
    Intent intent = new Intent();
    intent.setAction(COMPLETED_ACTION);

    sendOrderedBroadcast(intent, null);
  }

  @Override
  public void progressUpdate(ProgressDescription progress) {
    setState(new ImportState(ImportState.STATE_MIGRATING_IN_PROGRESS, progress));
  }

  public ImportState getState() {
    return state;
  }

  private void setState(ImportState state) {
    this.state = state;

    if (this.handler != null) {
      Handler handler = this.handler.get();
      if (handler != null) {
        handler.obtainMessage(state.state, state.progress).sendToTarget();
      }
    }

    if (state.progress != null && state.progress.secondaryComplete == 0) {
      updateBackgroundNotification(state.progress.primaryTotal, state.progress.primaryComplete);
    }
  }

  private void updateBackgroundNotification(int total, int complete) {
    if (notification == null) return;

    notification.setProgress(total, complete, false);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // 33+
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
              != PackageManager.PERMISSION_GRANTED) {
        return;
      }
    }

    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    if (nm != null) {
      nm.notify(MIGRATION_NOTIFICATION_ID, notification.build());
    }
  }

  private NotificationCompat.Builder initializeBackgroundNotification() {
    NotificationCompat.Builder builder =
            new NotificationCompat.Builder(this, NotificationChannels.OTHER)
                    .setSmallIcon(R.drawable.ic_smsecure)
                    .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.drawable.ic_smsecure))
                    .setContentTitle(getString(R.string.ApplicationMigrationService_importing_text_messages))
                    .setContentText(getString(R.string.ApplicationMigrationService_import_in_progress))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setProgress(100, 0, false)
                    .setContentIntent(
                            PendingIntent.getActivity(
                                    this,
                                    0,
                                    new Intent(this, ConversationListActivity.class)
                                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                            )
                    );

    Notification n = builder.build();

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // 29+
      startForeground(MIGRATION_NOTIFICATION_ID, n,
              ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    } else {
      startForeground(MIGRATION_NOTIFICATION_ID, n);
    }

    return builder;
  }

  private NotificationCompat.Builder initializeBackgroundNotificationOnMainThread() {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      return initializeBackgroundNotification();
    }

    final NotificationCompat.Builder[] out = new NotificationCompat.Builder[1];
    final CountDownLatch latch = new CountDownLatch(1);

    mainHandler.post(() -> {
      out[0] = initializeBackgroundNotification();
      latch.countDown();
    });

    try {
      latch.await();
    } catch (InterruptedException ignored) {
      // If interrupted, proceed best-effort.
    }

    return out[0];
  }

  private static class CompletedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // 33+
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
          return;
        }
      }

      NotificationCompat.Builder builder =
              new NotificationCompat.Builder(context, NotificationChannels.OTHER)
                      .setSmallIcon(R.drawable.ic_smsecure)
                      .setContentTitle(context.getString(R.string.ApplicationMigrationService_import_complete))
                      .setContentText(context.getString(R.string.ApplicationMigrationService_system_database_import_is_complete))
                      .setContentIntent(
                              PendingIntent.getActivity(
                                      context,
                                      0,
                                      new Intent(context, ConversationListActivity.class)
                                              .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                                      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                              )
                      )
                      .setWhen(System.currentTimeMillis())
                      .setDefaults(Notification.DEFAULT_VIBRATE)
                      .setAutoCancel(true);

      Notification notification = builder.build();
      NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
      if (nm != null) {
        nm.notify(31337, notification);
      }
    }
  }

  public static class ImportState {
    public static final int STATE_IDLE = 0;
    public static final int STATE_MIGRATING_BEGIN = 1;
    public static final int STATE_MIGRATING_IN_PROGRESS = 2;
    public static final int STATE_MIGRATING_COMPLETE = 3;

    public int state;
    public ProgressDescription progress;

    public ImportState(int state, ProgressDescription progress) {
      this.state = state;
      this.progress = progress;
    }
  }

  private class ImportRunnable implements Runnable {
    private final MasterSecret masterSecret;

    public ImportRunnable(Intent intent) {
      this.masterSecret = intent.getParcelableExtra("master_secret");
      Log.w(TAG, "Service got mastersecret: " + masterSecret);
    }

    @Override
    public void run() {
      notification = initializeBackgroundNotificationOnMainThread();
      PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);

      if (powerManager == null) {
        Log.w(TAG, "PowerManager was null; cannot acquire wake lock");
        doMigration();
        return;
      }

      WakeLock wakeLock = powerManager.newWakeLock(
              PowerManager.PARTIAL_WAKE_LOCK,
              "org.jimvixx.smsecure:Migration"
      );

      try {
        wakeLock.acquire(10 * 60 * 1000L /*10 minutes*/);
        doMigration();
      } finally {
        try {
          if (wakeLock.isHeld()) wakeLock.release();
        } catch (RuntimeException ignored) {
          // Defensive: wakelock edge cases
        }
      }
    }

    private void doMigration() {
      try {
        setState(new ImportState(ImportState.STATE_MIGRATING_BEGIN, null));

        SmsMigrator.migrateDatabase(ApplicationMigrationService.this,
                masterSecret,
                ApplicationMigrationService.this);

        setState(new ImportState(ImportState.STATE_MIGRATING_COMPLETE, null));

        setDatabaseImported(ApplicationMigrationService.this);

      } finally {
        // Always stop foreground & finish the service
        try {
          stopForeground(true);
        } catch (Throwable ignored) {
        }

        notifyImportComplete();
        stopSelf();
      }
    }
  }

  public class ApplicationMigrationBinder extends Binder {
    public ApplicationMigrationService getService() {
      return ApplicationMigrationService.this;
    }
  }
}
