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

package org.jimvixx.smsecure;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.crypto.MasterSecretUtil;
import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.jimvixx.smsecure.util.VersionTracker;
import org.jimvixx.smsecure.util.dualsim.DualSimUtil;
import org.jimvixx.smsecure.util.dualsim.SubscriptionInfoCompat;
import org.jimvixx.smsecure.util.dualsim.SubscriptionManagerCompat;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Activity for initializing secure storage.
 */
public class PassphraseCreateActivity extends PassphraseActivity {

  private final Handler mainThread = new Handler(Looper.getMainLooper());
  private ExecutorService executor;

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setContentView(R.layout.passphrase_activity);

    Toolbar toolbar = findViewById(R.id.toolbar);
    if (toolbar != null) {
      setSupportActionBar(toolbar);
    }

    bindViews();

    executor = Executors.newSingleThreadExecutor();
    executor.execute(new SecretGenerator(this, mainThread));

    getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
      @Override
      public void handleOnBackPressed() {
        finishAndRemoveTask();
      }
    });
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();

    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
  }

  @Override
  public boolean onSupportNavigateUp() {
    finishAndRemoveTask();
    return true;
  }

  private void onSecretReady(@Nullable MasterSecret masterSecret) {
    if (masterSecret == null || isFinishing() || isDestroyed()) {
      return;
    }

    setMasterSecret(masterSecret);
  }

  @Override
  protected void cleanup() {
    System.gc();
  }

  private void bindViews() {
    ProgressBar progressBar = findViewById(R.id.progress);
    if (progressBar != null) {
      progressBar.setVisibility(View.VISIBLE);
    }

    TextView appTitle = findViewById(R.id.app_title);
    if (appTitle != null) {
      appTitle.setText(R.string.AndroidManifest__initializing_secure_storage);
    }
  }

  /**
   * Static background worker.
   */
  private static final class SecretGenerator implements Runnable {

    private final WeakReference<PassphraseCreateActivity> activityRef;
    private final Handler mainThread;

    SecretGenerator(@NonNull PassphraseCreateActivity activity,
                    @NonNull Handler mainThread) {
      this.activityRef = new WeakReference<>(activity);
      this.mainThread = mainThread;
    }

    @Override
    public void run() {
      PassphraseCreateActivity activity = activityRef.get();
      if (activity == null) {
        return;
      }

      String passphrase = MasterSecretUtil.UNENCRYPTED_PASSPHRASE;

      MasterSecret masterSecret = MasterSecretUtil.generateMasterSecret(
              activity.getApplicationContext(),
              passphrase
      );

      if (masterSecret == null) {
        return;
      }

      MasterSecretUtil.generateAsymmetricMasterSecret(
              activity.getApplicationContext(),
              masterSecret
      );

      SubscriptionManagerCompat subscriptionManager =
              SubscriptionManagerCompat.from(activity.getApplicationContext());

      List<SubscriptionInfoCompat> activeSubscriptions =
              subscriptionManager.getActiveSubscriptionInfoList();

      DualSimUtil.generateKeysIfDoNotExist(
              activity.getApplicationContext(),
              masterSecret,
              activeSubscriptions,
              false
      );

      VersionTracker.updateLastSeenVersion(activity.getApplicationContext());
      SMSecurePreferences.setPasswordDisabled(activity.getApplicationContext(), true);

      mainThread.post(() -> {
        PassphraseCreateActivity currentActivity = activityRef.get();
        if (currentActivity != null) {
          currentActivity.onSecretReady(masterSecret);
        }
      });
    }
  }
}