/*
 * Copyright (C) 2013 Open Whisper Systems
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

package org.jimvixx.smsecure;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.notifications.MessageNotifier;
import org.jimvixx.smsecure.util.ParcelUtil;
import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.jimvixx.smsecure.util.Util;
import org.jimvixx.smsecure.util.VersionTracker;
import org.jimvixx.smsecure.util.dualsim.DualSimUtil;
import org.jimvixx.smsecure.util.dualsim.SubscriptionInfoCompat;
import org.jimvixx.smsecure.util.dualsim.SubscriptionManagerCompat;
import org.whispersystems.jobqueue.EncryptionKeys;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class DatabaseUpgradeActivity extends BaseActivity {

  public static final int ASK_FOR_SIM_CARD_VERSION = 143;
  public static final int MULTI_SIM_MULTI_KEYS_VERSION = 200;
  private static final String TAG = DatabaseUpgradeActivity.class.getSimpleName();
  private static final SortedSet<Integer> UPGRADE_VERSIONS;

  static {
    TreeSet<Integer> set = new TreeSet<>();
    set.add(ASK_FOR_SIM_CARD_VERSION);
    set.add(MULTI_SIM_MULTI_KEYS_VERSION);
    UPGRADE_VERSIONS = Collections.unmodifiableSortedSet(set);
  }

  final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final AtomicBoolean finished = new AtomicBoolean(false);

  private MasterSecret masterSecret;

  private ProgressBar indeterminateProgress;
  private ProgressBar determinateProgress;

  public static boolean isUpdate(@NonNull Context context) {
    try {
      int currentVersionCode =
              context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
      int previousVersionCode = VersionTracker.getLastSeenVersion(context);

      return previousVersionCode < currentVersionCode;
    } catch (PackageManager.NameNotFoundException e) {
      throw new AssertionError(e);
    }
  }

  private static double clamp01(double v) {
    if (v < 0.0) return 0.0;
    return Math.min(v, 1.0);
  }

  @Override
  public void onCreate(@Nullable Bundle bundle) {
    super.onCreate(bundle);

    masterSecret = getIntent().getParcelableExtra("master_secret");
    if (masterSecret == null) {
      Log.e(TAG, "Missing master_secret extra, finishing.");
      finish();
      return;
    }

    if (!needsUpgradeTask()) {
      finishWithoutUpgrade();
      return;
    }

    Log.w(TAG, "Upgrading...");
    setContentView(R.layout.database_upgrade_activity);

    indeterminateProgress = findViewById(R.id.indeterminate_progress);
    determinateProgress = findViewById(R.id.determinate_progress);

    int lastSeen = VersionTracker.getLastSeenVersion(this);
    executor.execute(new DatabaseUpgradeRunner(this, masterSecret, lastSeen));
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    executor.shutdownNow();
  }

  private void finishWithoutUpgrade() {
    VersionTracker.updateLastSeenVersion(this);

    ApplicationContext.getInstance(this)
            .getJobManager()
            .setEncryptionKeys(new EncryptionKeys(ParcelUtil.serialize(masterSecret)));

    updateNotificationsAsync(this, masterSecret);

    Intent next = getIntent().getParcelableExtra(PassphraseActivity.EXTRA_NEXT_INTENT);
    if (next != null) startActivity(next);
    finish();
  }

  private boolean needsUpgradeTask() {
    int currentVersionCode = Util.getCurrentApkReleaseVersion(this);
    int lastSeenVersion = VersionTracker.getLastSeenVersion(this);

    Log.w(TAG, "LastSeenVersion: " + lastSeenVersion);

    if (lastSeenVersion >= currentVersionCode) return false;

    for (int version : UPGRADE_VERSIONS) {
      Log.w(TAG, "Comparing: " + version);
      if (lastSeenVersion < version) return true;
    }

    return false;
  }

  private void updateNotificationsAsync(@NonNull Context context, @NonNull MasterSecret masterSecret) {
    Context appContext = context.getApplicationContext();
    executor.execute(() -> MessageNotifier.updateNotification(appContext, masterSecret));
  }

  private void onUpgradeProgress01(double scaler01) {
    if (indeterminateProgress == null || determinateProgress == null) return;

    indeterminateProgress.setVisibility(View.GONE);
    determinateProgress.setVisibility(View.VISIBLE);

    int max = determinateProgress.getMax();
    int value = (int) Math.floor(max * clamp01(scaler01));
    determinateProgress.setProgress(value);
  }

  private void onUpgradeFinished() {
    if (!finished.compareAndSet(false, true)) return;

    VersionTracker.updateLastSeenVersion(this);

    ApplicationContext.getInstance(this)
            .getJobManager()
            .setEncryptionKeys(new EncryptionKeys(ParcelUtil.serialize(masterSecret)));

    updateNotificationsAsync(this, masterSecret);

    Intent next = getIntent().getParcelableExtra(PassphraseActivity.EXTRA_NEXT_INTENT);
    if (next != null) startActivity(next);
    finish();
  }

  private void onUpgradeFailed(@NonNull Throwable t) {
    Log.e(TAG, "Database upgrade failed", t);
    onUpgradeFinished();
  }

  // MUST match DatabaseFactory signature:
  // onApplicationLevelUpgrade(..., DatabaseUpgradeActivity.DatabaseUpgradeListener)
  public interface DatabaseUpgradeListener {
    void setProgress(int progress, int total);
  }

  private static final class DatabaseUpgradeRunner implements Runnable, DatabaseUpgradeListener {

    private final WeakReference<DatabaseUpgradeActivity> activityRef;
    private final MasterSecret masterSecret;
    private final int lastSeenVersion;

    DatabaseUpgradeRunner(@NonNull DatabaseUpgradeActivity activity,
                          @NonNull MasterSecret masterSecret,
                          int lastSeenVersion) {
      this.activityRef = new WeakReference<>(activity);
      this.masterSecret = masterSecret;
      this.lastSeenVersion = lastSeenVersion;
    }

    @Override
    public void run() {
      DatabaseUpgradeActivity activity = activityRef.get();
      if (activity == null) return;

      Context appContext = activity.getApplicationContext();

      try {
        Log.w(TAG, "Running background upgrade..");

        DatabaseFactory.getInstance(activity)
                .onApplicationLevelUpgrade(appContext, masterSecret, lastSeenVersion, this);

        if (lastSeenVersion < ASK_FOR_SIM_CARD_VERSION) {
          if (!SMSecurePreferences.isFirstRun(appContext) &&
                  SubscriptionManagerCompat.from(appContext).getActiveSubscriptionInfoList().size() > 1) {
            SMSecurePreferences.setSimCardAsked(appContext, false);
          }
        }

        if (lastSeenVersion < MULTI_SIM_MULTI_KEYS_VERSION) {
          List<SubscriptionInfoCompat> subscriptionInfoList =
                  SubscriptionManagerCompat.from(appContext).getActiveSubscriptionInfoList();

          int smallerSlot = -1;
          int eligibleDeviceSubscriptionId = -1;

          for (SubscriptionInfoCompat info : subscriptionInfoList) {
            if (smallerSlot == -1 || info.getIccSlot() < smallerSlot) {
              smallerSlot = info.getIccSlot();
              eligibleDeviceSubscriptionId = info.getDeviceSubscriptionId();
            }
          }

          DualSimUtil.moveIdentityKeysAndSessionsToSubscriptionId(appContext, -1, eligibleDeviceSubscriptionId);
          DualSimUtil.generateKeysIfDoNotExist(appContext, masterSecret, subscriptionInfoList);
          SubscriptionManagerCompat.from(appContext).updateActiveSubscriptionInfoList();
        }

        postFinish(true, null);
      } catch (Throwable t) {
        postFinish(false, t);
      }
    }

    @Override
    public void setProgress(int progress, int total) {
      DatabaseUpgradeActivity activity = activityRef.get();
      if (activity == null) return;

      double scaler01 = (total <= 0) ? 0.0 : (progress / (double) total);

      activity.mainHandler.post(() -> {
        DatabaseUpgradeActivity a = activityRef.get();
        if (a == null || a.isFinishing()) return;
        a.onUpgradeProgress01(scaler01);
      });
    }

    private void postFinish(boolean ok, @Nullable Throwable t) {
      DatabaseUpgradeActivity activity = activityRef.get();
      if (activity == null) return;

      activity.mainHandler.post(() -> {
        DatabaseUpgradeActivity a = activityRef.get();
        if (a == null || a.isFinishing()) return;

        if (ok) a.onUpgradeFinished();
        else a.onUpgradeFailed(t != null ? t : new RuntimeException("Upgrade failed"));
      });
    }
  }
}
