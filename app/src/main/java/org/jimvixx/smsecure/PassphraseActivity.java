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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.IntentSanitizer;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.service.KeyCachingService;

/**
 * Base Activity for changing/prompting local encryption passphrase.
 */
public abstract class PassphraseActivity extends BaseActionBarActivity {

  public static final String EXTRA_NEXT_INTENT = "next_intent";
  private static final String TAG = PassphraseActivity.class.getSimpleName();
  private KeyCachingService keyCachingService;
  private MasterSecret masterSecret;

  private boolean completionHandled;
  private boolean masterSecretSettingInProgress;
  private boolean passphraseActivityRegistered;

  @Override
  protected void onStart() {
    super.onStart();

    if (!passphraseActivityRegistered && !isFinishing() && !isDestroyed()) {
      KeyCachingService.registerPassphraseActivityStarted(this);
      passphraseActivityRegistered = true;
      Log.w(TAG, "Registered PassphraseActivity as started");
    }
  }

  @Override
  protected void onStop() {
    super.onStop();

    if (passphraseActivityRegistered) {
      KeyCachingService.registerPassphraseActivityStopped(this);
      passphraseActivityRegistered = false;
      Log.w(TAG, "Registered PassphraseActivity as stopped");
    }
  }

  protected void setMasterSecret(@NonNull MasterSecret masterSecret) {
    if (completionHandled) {
      Log.w(TAG, "setMasterSecret(): completion already handled, ignoring");
      return;
    }

    if (masterSecretSettingInProgress) {
      Log.w(TAG, "setMasterSecret(): already in progress, ignoring");
      return;
    }

    masterSecretSettingInProgress = true;
    this.masterSecret = masterSecret;

    Intent bindIntent = new Intent(this, KeyCachingService.class);
    startService(bindIntent);
    bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE);
  }

  protected abstract void cleanup();

  private void safeUnbind() {
    try {
      unbindService(serviceConnection);
    } catch (IllegalArgumentException e) {
      Log.w(TAG, "Service was already unbound", e);
    }
  }

  @Nullable
  private Intent sanitizeNextIntent(@NonNull Intent unsafe) {
    if (unsafe.getComponent() == null) return null;

    IntentSanitizer sanitizer = new IntentSanitizer.Builder()
            .allowComponent(cn -> cn != null && getPackageName().equals(cn.getPackageName()))
            .allowFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .allowExtra(ConversationActivity.RECIPIENTS_EXTRA, long[].class)
            .allowExtra(ConversationActivity.THREAD_ID_EXTRA, Long.class)
            .allowExtra(ConversationActivity.IS_ARCHIVED_EXTRA, Boolean.class)
            .allowExtra(ConversationActivity.TEXT_EXTRA, String.class)
            .allowExtra(ConversationActivity.DISTRIBUTION_TYPE_EXTRA, Integer.class)
            .allowExtra(ConversationActivity.TIMING_EXTRA, Long.class)
            .allowExtra(ConversationActivity.LAST_SEEN_EXTRA, Long.class)
            .build();

    Intent sanitized = sanitizer.sanitizeByFiltering(unsafe);

    if (sanitized.getComponent() == null) return null;

    return sanitized;
  }  private final ServiceConnection serviceConnection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName className, IBinder service) {
      if (completionHandled) {
        Log.w(TAG, "onServiceConnected(): completion already handled, ignoring");
        safeUnbind();
        return;
      }

      completionHandled = true;

      keyCachingService = ((KeyCachingService.KeySetBinder) service).getService();
      keyCachingService.setMasterSecret(masterSecret);

      safeUnbind();

      masterSecret = null;
      masterSecretSettingInProgress = false;
      cleanup();

      Intent nextIntent = getIntent().getParcelableExtra(EXTRA_NEXT_INTENT);
      if (nextIntent != null) {
        Intent sanitized = sanitizeNextIntent(nextIntent);
        if (sanitized != null) {
          sanitized.removeExtra(EXTRA_NEXT_INTENT);
          startActivity(sanitized);
        }
      }

      finish();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
      keyCachingService = null;
    }
  };




}