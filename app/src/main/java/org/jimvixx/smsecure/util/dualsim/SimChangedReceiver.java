/*
 * Copyright (C) 2014 Open Whisper Systems
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

package org.jimvixx.smsecure.util.dualsim;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import org.jimvixx.smsecure.ApplicationContext;
import org.jimvixx.smsecure.jobs.GenerateKeysJob;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.jimvixx.smsecure.util.VersionTracker;

import java.util.Arrays;
import java.util.List;

public class SimChangedReceiver extends BroadcastReceiver {

  private static final String TAG = SimChangedReceiver.class.getSimpleName();

  private static final String SIM_STATE_CHANGED_EVENT = "android.intent.action.SIM_STATE_CHANGED";

  public static void checkSimState(@NonNull Context context) {
    Log.w(TAG, "checkSimState()");

    String previousDeviceSubscriptions = normalizeSubscriptions(
            SMSecurePreferences.getDeviceSubscriptions(context)
    );

    List<SubscriptionInfoCompat> activeSubscriptions =
            SubscriptionManagerCompat.from(context).updateActiveSubscriptionInfoList();

    String currentDeviceSubscriptions = buildDeviceSubscriptionsString(activeSubscriptions);

    Log.w(TAG, "previousDeviceSubscriptions: " + previousDeviceSubscriptions);
    Log.w(TAG, "currentDeviceSubscriptions:  " + currentDeviceSubscriptions);

    if (!currentDeviceSubscriptions.equals(previousDeviceSubscriptions) &&
            VersionTracker.isDbUpdated(context)) {
      Log.w(TAG, "Active SIM subscriptions changed, scheduling key generation job");

      ApplicationContext.getInstance(context)
              .getJobManager()
              .add(new GenerateKeysJob(context));
    }

    SMSecurePreferences.setDeviceSubscriptions(context, currentDeviceSubscriptions);
  }

  private static @NonNull String buildDeviceSubscriptionsString(@NonNull List<SubscriptionInfoCompat> activeSubscriptions) {
    if (activeSubscriptions.isEmpty()) {
      return "";
    }

    String[] subscriptions = new String[activeSubscriptions.size()];

    for (int i = 0; i < activeSubscriptions.size(); i++) {
      subscriptions[i] = Integer.toString(activeSubscriptions.get(i).getDeviceSubscriptionId());
    }

    Arrays.sort(subscriptions);

    return joinStrings(subscriptions);
  }

  private static @NonNull String normalizeSubscriptions(String value) {
    if (value == null) {
      return "";
    }

    value = value.trim();

    if (value.isEmpty()) {
      return "";
    }

    String[] parts = value.split(",");
    Arrays.sort(parts);

    return joinStrings(parts);
  }

  private static @NonNull String joinStrings(String[] values) {
    if (values == null || values.length == 0) {
      return "";
    }

    StringBuilder result = new StringBuilder();

    for (int i = 0; i < values.length; i++) {
      String value = values[i];

      if (value == null) {
        value = "";
      }

      result.append(value);

      if (i != values.length - 1) {
        result.append(",");
      }
    }

    return result.toString();
  }

  @Override
  public void onReceive(final Context context, final Intent intent) {
    Log.w(TAG, "onReceive()");

    if (context == null || intent == null) {
      return;
    }

    final String action = intent.getAction();

    if (SIM_STATE_CHANGED_EVENT.equals(action)) {
      checkSimState(context);
    }
  }
}