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

package org.jimvixx.smsecure.security;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.jimvixx.smsecure.logging.Log;

import androidx.annotation.NonNull;

import org.jimvixx.smsecure.PanicResponderActivity;
import org.jimvixx.smsecure.util.SMSecurePreferences;

public class DeviceLockPanicReceiver extends BroadcastReceiver {

  private static final String TAG = DeviceLockPanicReceiver.class.getSimpleName();

  @Override
  public void onReceive(@NonNull Context context, Intent intent) {

    if (!SMSecurePreferences.isPanicOnDeviceLockEnabled(context)) {
      Log.w(TAG, "Panic on device lock is disabled in settings -> ignoring");
      return;
    }

    if (intent == null) return;

    final String action = intent.getAction();
    if (action == null) return;

    final boolean lockEvent =
            Intent.ACTION_SCREEN_OFF.equals(action) ||
                    "android.intent.action.DEVICE_LOCKED".equals(action);

    if (!lockEvent) return;

    KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
    boolean locked = km != null && (km.isKeyguardLocked() || km.isDeviceLocked());

    Log.w(TAG, "event=" + action + " locked=" + locked);

    if (!locked) return;

    PanicResponderActivity.triggerInternalPanic(
            context,
            PanicResponderActivity.REASON_DEVICE_LOCK);
  }
}
