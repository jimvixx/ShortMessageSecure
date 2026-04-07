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

package org.jimvixx.smsecure.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.jimvixx.smsecure.logging.Log;

public class PassphraseTimeoutReceiver extends BroadcastReceiver {

  private static final String TAG = "PassphraseTimeoutReceiver";

  @Override
  public void onReceive(Context context, Intent intent) {
    Log.w(TAG, "onReceive() passphrase timeout -> clearing master secret");
    KeyCachingService.clearMasterSecretDirect(context, KeyCachingService.CLEAR_REASON_OTHER);
  }
}
