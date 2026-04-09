/**
 * Copyright (C) 2011 Whisper Systems
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jimvixx.smsecure.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * WARNING: Manifest compatibility stub.
 * <p>
 * This receiver exists ONLY to satisfy Android's default SMS app
 * eligibility requirements. Removing it will cause the system to
 * stop recognizing SMSecure as a valid default SMS application.
 * <p>
 * SMSecure intentionally does not support MMS, but Android still
 * requires the WAP_PUSH_DELIVER declaration for MMS messages.
 * <p>
 * Do not remove unless the default SMS role support is intentionally dropped.
 */
public class MmsListener extends BroadcastReceiver {

  private static final String ACTION_WAP_PUSH_DELIVER =
          "android.provider.Telephony.WAP_PUSH_DELIVER";

  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null) {
      return;
    }

    String action = intent.getAction();
    if (!ACTION_WAP_PUSH_DELIVER.equals(action)) {
      return;
    }

    // Optional extra hardening:
    String type = intent.getType();
    if (!"application/vnd.wap.mms-message".equals(type)) {
    }

    // no-op stub
  }
}