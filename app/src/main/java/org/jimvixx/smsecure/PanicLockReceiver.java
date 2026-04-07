/*
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import org.jimvixx.smsecure.logging.Log;

import androidx.annotation.NonNull;

import org.jimvixx.smsecure.util.SMSecurePreferences;

/**
 * Runtime receiver, registered from {@link ApplicationContext}.
 */
public final class PanicLockReceiver extends BroadcastReceiver {

  private static final String TAG = PanicLockReceiver.class.getSimpleName();

  @Override
  public void onReceive(Context context, Intent intent) {

    if (!SMSecurePreferences.isPanicOnDeviceLockEnabled(context)) {
      Log.w(TAG, "Panic on lock is disabled in settings -> ignoring");
      return;
    }

    if (context == null) return;
    final String action = (intent != null ? intent.getAction() : null);
    if (action == null) return;

    Log.w(TAG, "onReceive(), action=" + action + ", sdk=" + Build.VERSION.SDK_INT);

    if (Intent.ACTION_SCREEN_OFF.equals(action)) {
      Log.w(TAG, "ACTION_SCREEN_OFF -> triggering panic");
      PanicResponderActivity.triggerInternalPanic(
              context.getApplicationContext(),
              PanicResponderActivity.REASON_DEVICE_LOCK
      );
    }
  }

  @NonNull
  public static IntentFilterBuilder newIntentFilter() {
    return new IntentFilterBuilder()
            .add(Intent.ACTION_SCREEN_OFF);
  }

  /**
   * Helper to avoid forgetting actions.
   */
  public static final class IntentFilterBuilder {
    private final android.content.IntentFilter filter = new android.content.IntentFilter();

    public IntentFilterBuilder add(@NonNull String action) {
      filter.addAction(action);
      return this;
    }

    public android.content.IntentFilter build() {
      return filter;
    }
  }
}
