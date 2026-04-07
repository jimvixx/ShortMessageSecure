/*
 * Copyright (C) 2011 Whisper Systems
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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jimvixx.smsecure.ApplicationContext;
import org.jimvixx.smsecure.ConversationListActivity;
import org.jimvixx.smsecure.DatabaseUpgradeActivity;
import org.jimvixx.smsecure.crypto.InvalidPassphraseException;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.crypto.MasterSecretUtil;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.notifications.MessageNotifier;
import org.jimvixx.smsecure.util.DynamicLanguage;
import org.jimvixx.smsecure.util.ParcelUtil;
import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.whispersystems.jobqueue.EncryptionKeys;

import java.util.concurrent.TimeUnit;

/**
 * Small service that stays running to keep a key cached in memory.
 * NOTE: Intentionally NOT a Foreground Service (Play policy).
 */
public class KeyCachingService extends Service {

  public static final int SERVICE_RUNNING_ID = 4141; // unused now, left for compatibility

  public static final String KEY_PERMISSION = "org.jimvixx.smsecure.ACCESS_SECRETS";
  public static final String NEW_KEY_EVENT = "org.jimvixx.smsecure.service.action.NEW_KEY_EVENT";
  public static final String CLEAR_KEY_EVENT = "org.jimvixx.smsecure.service.action.CLEAR_KEY_EVENT";
  public static final String CLEAR_KEY_ACTION = "org.jimvixx.smsecure.service.action.CLEAR_KEY";
  public static final String DISABLE_ACTION = "org.jimvixx.smsecure.service.action.DISABLE";
  public static final String ACTIVITY_START_EVENT = "org.jimvixx.smsecure.service.action.ACTIVITY_START_EVENT";
  public static final String ACTIVITY_STOP_EVENT = "org.jimvixx.smsecure.service.action.ACTIVITY_STOP_EVENT";
  public static final String LOCALE_CHANGE_EVENT = "org.jimvixx.smsecure.service.action.LOCALE_CHANGE_EVENT";

  // Broadcast action used by the AlarmManager PendingIntent.
  public static final String PASSPHRASE_EXPIRED_EVENT = "org.jimvixx.smsecure.service.action.PASSPHRASE_EXPIRED_EVENT";

  public static final String EXTRA_CLEAR_REASON = "clear_reason";
  public static final int CLEAR_REASON_USER = 1;
  public static final int CLEAR_REASON_PANIC = 2;
  public static final int CLEAR_REASON_OTHER = 3;

  private static final String TAG = "KeyCachingService";

  // Small grace period to avoid treating activity-to-activity transitions
  // inside the app as a real background transition.
  private static final long STOP_GRACE_PERIOD_MS = 1000L;

  // Deadline fallback (persists across service/process death)
  private static final String PREFS_NAME = "kcs_timeout";
  private static final String KEY_DEADLINE_ELAPSED = "deadline_elapsed";
  private static final String KEY_DEADLINE_ACTIVE = "deadline_active";
  // Process-memory cache.
  private static @Nullable MasterSecret masterSecret;
  private final DynamicLanguage dynamicLanguage = new DynamicLanguage();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final IBinder binder = new KeySetBinder();
  private PendingIntent pendingTimeout;
  // Guard against negative counts due to mismatched START/STOP deliveries.
  private int activitiesRunning = 0;
  // Counts STOP events that are waiting out the grace period before being applied.
  private int pendingStops = 0;
  private final Runnable applyPendingStopRunnable = this::applyPendingStops;

  public KeyCachingService() {
  }

  /**
   * Public helper: call this from activities early (eg in onStart / onResume).
   */
  public static void enforceTimeoutIfExpired(@NonNull Context context) {
    Context app = context.getApplicationContext();
    try {
      SharedPreferences sp = app.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
      if (!sp.getBoolean(KEY_DEADLINE_ACTIVE, false)) return;

      long deadline = sp.getLong(KEY_DEADLINE_ELAPSED, 0L);
      long now = SystemClock.elapsedRealtime();

      if (deadline > 0 && now >= deadline) {
        Log.w(TAG, "enforceTimeoutIfExpired(): deadline passed -> clearing");
        clearMasterSecretDirect(app, CLEAR_REASON_OTHER);
        sp.edit().putBoolean(KEY_DEADLINE_ACTIVE, false).apply();
      }
    } catch (Throwable t) {
      Log.w(TAG, "enforceTimeoutIfExpired() failed", t);
    }
  }

  public static synchronized @Nullable MasterSecret getMasterSecret(Context context) {
    // Fallback enforcement (covers case where alarm didn't fire)
    enforceTimeoutIfExpired(context);

    if (masterSecret == null && SMSecurePreferences.isPasswordDisabled(context)) {
      try {
        MasterSecret ms = MasterSecretUtil.getMasterSecret(context, MasterSecretUtil.UNENCRYPTED_PASSPHRASE);
        masterSecret = ms;

        // Make sure service is alive so locale handling, broadcasts, and timeout scheduling are consistent.
        Intent intent = new Intent(context, KeyCachingService.class);
        context.startService(intent);

        return ms;
      } catch (InvalidPassphraseException e) {
        Log.w(TAG, e);
      }
    }

    return masterSecret;
  }

  /**
   * Clears masterSecret WITHOUT starting the Service.
   * This is important for background triggers (screen-off / lock), where startService()
   * may be restricted on Android 8+.
   */
  public static synchronized void clearMasterSecretDirect(@NonNull Context context, int reason) {
    Context app = context.getApplicationContext();

    Log.w(TAG, "clearMasterSecretDirect(), reason=" + reason,
            new Throwable("CLEAR_KEY direct caller stack"));

    masterSecret = null;

    // Cancel persisted deadline
    try {
      app.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
              .edit()
              .putBoolean(KEY_DEADLINE_ACTIVE, false)
              .apply();
    } catch (Throwable ignore) {
    }

    Intent cleared = new Intent(CLEAR_KEY_EVENT);
    cleared.setPackage(app.getPackageName());
    cleared.putExtra(EXTRA_CLEAR_REASON, reason);

    try {
      app.sendBroadcast(cleared, KEY_PERMISSION);
    } catch (Throwable t) {
      Log.w(TAG, "Failed to broadcast CLEAR_KEY_EVENT", t);
    }

    new Thread(() -> {
      try {
        MessageNotifier.updateNotification(app, null);
      } catch (Throwable t) {
        Log.w(TAG, "Failed to update notification after clear", t);
      }
    }).start();
  }

  public static void registerPassphraseActivityStarted(Context activity) {
    // Enforce on foreground entry even if alarm never fired
    enforceTimeoutIfExpired(activity);

    Intent intent = new Intent(activity, KeyCachingService.class);
    intent.setAction(KeyCachingService.ACTIVITY_START_EVENT);
    activity.startService(intent);
  }

  public static void registerPassphraseActivityStopped(Context activity) {
    Intent intent = new Intent(activity, KeyCachingService.class);
    intent.setAction(KeyCachingService.ACTIVITY_STOP_EVENT);
    activity.startService(intent);
  }

  public void setMasterSecret(final MasterSecret newMasterSecret) {
    synchronized (KeyCachingService.class) {
      masterSecret = newMasterSecret;

      broadcastNewSecret();
      startTimeoutIfAppropriate();

      new Thread(() -> {
        if (!DatabaseUpgradeActivity.isUpdate(KeyCachingService.this)) {
          ApplicationContext.getInstance(KeyCachingService.this)
                  .getJobManager()
                  .setEncryptionKeys(new EncryptionKeys(ParcelUtil.serialize(newMasterSecret)));
          MessageNotifier.updateNotification(KeyCachingService.this, newMasterSecret);
        }
      }).start();
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null) return START_NOT_STICKY;

    String action = intent.getAction();
    Log.w(TAG, "onStartCommand, " + action + " activitiesRunning=" + activitiesRunning +
            " pendingStops=" + pendingStops +
            " masterSecret=" + (masterSecret != null));

    if (action != null) {
      switch (action) {
        case CLEAR_KEY_ACTION: {
          int reason = intent.getIntExtra(EXTRA_CLEAR_REASON, CLEAR_REASON_OTHER);
          handleClearKey(reason);
          break;
        }
        case ACTIVITY_START_EVENT:
          handleActivityStarted();
          break;
        case ACTIVITY_STOP_EVENT:
          handleActivityStopped();
          break;
        case DISABLE_ACTION:
          handleDisableService();
          break;
        case LOCALE_CHANGE_EVENT:
          handleLocaleChanged();
          break;
      }
    }

    maybeStopServiceIfIdle();

    return START_NOT_STICKY;
  }

  @Override
  public void onCreate() {
    Log.w(TAG, "onCreate()");
    super.onCreate();

    // Alarm should deliver a BROADCAST (starting a Service from an alarm is unreliable on modern Android).
    Intent timeoutIntent = new Intent(this, PassphraseTimeoutReceiver.class);
    timeoutIntent.setAction(PASSPHRASE_EXPIRED_EVENT);
    timeoutIntent.setPackage(getPackageName());

    this.pendingTimeout = PendingIntent.getBroadcast(
            this,
            0,
            timeoutIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
    );

    if (SMSecurePreferences.isPasswordDisabled(this)) {
      try {
        MasterSecret ms = MasterSecretUtil.getMasterSecret(this, MasterSecretUtil.UNENCRYPTED_PASSPHRASE);
        setMasterSecret(ms);
      } catch (InvalidPassphraseException e) {
        Log.w(TAG, e);
      }
    }
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    mainHandler.removeCallbacks(applyPendingStopRunnable);
    Log.w(TAG, "KCS Is Being Destroyed!");
    // IMPORTANT: do not clear key here.
  }

  private void handleActivityStarted() {
    Log.w(TAG, "Incrementing activity count...");

    // Any new started activity means the app is in active use again.
    cancelPendingStopProcessing();

    // Enforce fallback deadline on foreground return
    enforceTimeoutIfExpired(this);

    AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
    if (alarmManager != null) alarmManager.cancel(pendingTimeout);

    // Cancel persisted deadline when app is in use
    try {
      getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
              .edit()
              .putBoolean(KEY_DEADLINE_ACTIVE, false)
              .apply();
    } catch (Throwable ignore) {
    }

    activitiesRunning++;
    Log.w(TAG, "activitiesRunning=" + activitiesRunning + " pendingStops=" + pendingStops);
  }

  private void handleActivityStopped() {
    Log.w(TAG, "Queueing activity stop...");

    pendingStops++;
    Log.w(TAG, "activitiesRunning=" + activitiesRunning + " pendingStops=" + pendingStops);

    mainHandler.removeCallbacks(applyPendingStopRunnable);
    mainHandler.postDelayed(applyPendingStopRunnable, STOP_GRACE_PERIOD_MS);
  }

  private void applyPendingStops() {
    if (pendingStops <= 0) {
      return;
    }

    Log.w(TAG, "Applying pending stops: " + pendingStops);

    while (pendingStops > 0) {
      pendingStops--;

      if (activitiesRunning > 0) {
        activitiesRunning--;
      } else {
        Log.w(TAG, "activitiesRunning already 0 -> ignoring extra STOP");
        activitiesRunning = 0;
      }
    }

    Log.w(TAG, "activitiesRunning=" + activitiesRunning + " pendingStops=" + pendingStops);

    startTimeoutIfAppropriate();
    maybeStopServiceIfIdle();
  }

  private void cancelPendingStopProcessing() {
    if (pendingStops > 0) {
      Log.w(TAG, "Cancelling pending stops: " + pendingStops);
      pendingStops = 0;
    }

    mainHandler.removeCallbacks(applyPendingStopRunnable);
  }

  private void handleClearKey(int reason) {
    Log.w(TAG, "handleClearKey(), reason=" + reason, new Throwable("CLEAR_KEY caller stack"));

    masterSecret = null;

    cancelPendingStopProcessing();

    // Cancel any pending timeout alarm when clearing.
    AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
    if (alarmManager != null) alarmManager.cancel(pendingTimeout);

    // Cancel persisted deadline
    try {
      getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
              .edit()
              .putBoolean(KEY_DEADLINE_ACTIVE, false)
              .apply();
    } catch (Throwable ignore) {
    }

    Intent cleared = new Intent(CLEAR_KEY_EVENT);
    cleared.setPackage(getApplicationContext().getPackageName());
    cleared.putExtra(EXTRA_CLEAR_REASON, reason);

    sendBroadcast(cleared, KEY_PERMISSION);

    new Thread(() -> MessageNotifier.updateNotification(this, null)).start();

    maybeStopServiceIfIdle();
  }

  private void handleDisableService() {
    if (SMSecurePreferences.isPasswordDisabled(this)) {
      maybeStopServiceIfIdle();
    }
  }

  private void handleLocaleChanged() {
    dynamicLanguage.updateServiceLocale(this);

    if (masterSecret != null) {
      new Thread(() -> MessageNotifier.updateNotification(this, masterSecret)).start();
    }
  }

  private void startTimeoutIfAppropriate() {
    boolean timeoutEnabled = SMSecurePreferences.isPassphraseTimeoutEnabled(this);

    if ((activitiesRunning <= 0) &&
            (pendingStops <= 0) &&
            (masterSecret != null) &&
            timeoutEnabled &&
            !SMSecurePreferences.isPasswordDisabled(this)) {

      long timeoutMinutes = SMSecurePreferences.getPassphraseTimeoutInterval(this);
      if (timeoutMinutes < 1) timeoutMinutes = 1;

      long timeoutMillis = TimeUnit.MINUTES.toMillis(timeoutMinutes);
      long triggerAt = SystemClock.elapsedRealtime() + timeoutMillis;

      Log.w(TAG, "Starting timeout: " + timeoutMillis + "ms (minutes=" + timeoutMinutes + ")");

      // Persist deadline fallback (covers OEM/Doze delaying or dropping alarms)
      try {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putLong(KEY_DEADLINE_ELAPSED, triggerAt)
                .putBoolean(KEY_DEADLINE_ACTIVE, true)
                .apply();
      } catch (Throwable t) {
        Log.w(TAG, "Failed to persist timeout deadline", t);
      }

      AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
      if (alarmManager != null) {
        alarmManager.cancel(pendingTimeout);

        // No exact alarms: exact requires SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM on modern Android.
        // For passphrase timeouts, "eventually even in idle" is sufficient, but may be delayed by OEM.
        try {
          alarmManager.setAndAllowWhileIdle(
                  AlarmManager.ELAPSED_REALTIME_WAKEUP,
                  triggerAt,
                  pendingTimeout
          );
        } catch (Throwable t) {
          Log.w(TAG, "setAndAllowWhileIdle failed, falling back to set()", t);
          alarmManager.set(
                  AlarmManager.ELAPSED_REALTIME_WAKEUP,
                  triggerAt,
                  pendingTimeout
          );
        }
      }
    }
  }

  private void maybeStopServiceIfIdle() {
    if (masterSecret == null && activitiesRunning <= 0 && pendingStops <= 0) {
      Log.w(TAG, "Service idle -> stopSelf()");
      stopSelf();
    }
  }

  private void broadcastNewSecret() {
    Log.w(TAG, "Broadcasting new secret...");

    Intent intent = new Intent(NEW_KEY_EVENT);
    intent.setPackage(getApplicationContext().getPackageName());
    sendBroadcast(intent, KEY_PERMISSION);
  }

  // (unused currently; can keep/remove)
  private PendingIntent buildLaunchIntent() {
    Intent intent = new Intent(this, ConversationListActivity.class);
    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    return PendingIntent.getActivity(getApplicationContext(), 0, intent, PendingIntent.FLAG_IMMUTABLE);
  }

  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  public class KeySetBinder extends Binder {
    public KeyCachingService getService() {
      return KeyCachingService.this;
    }
  }
}