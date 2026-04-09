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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.Nullable;

import org.jimvixx.smsecure.WelcomeActivity;
import org.jimvixx.smsecure.logging.Log;

public class BootReceiver extends BroadcastReceiver {
  private static final String TAG = BootReceiver.class.getSimpleName();

  @Override
  public void onReceive(Context context, @Nullable Intent intent) {
    if (intent == null) return;

    final String action = intent.getAction();
    if (action == null) return;

    switch (action) {
      case Intent.ACTION_BOOT_COMPLETED:
      case Intent.ACTION_MY_PACKAGE_REPLACED:
        Log.w(TAG, "onReceive(): " + action);
        WelcomeActivity.checkForPermissions(context, intent);
        break;

      default:
        Log.w(TAG, "Ignoring unexpected action: " + action);
        break;
    }
  }
}
