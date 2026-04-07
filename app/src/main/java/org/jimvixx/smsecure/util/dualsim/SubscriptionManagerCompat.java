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

package org.jimvixx.smsecure.util.dualsim;

import android.content.Context;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;

import androidx.annotation.NonNull;

import org.jimvixx.smsecure.util.TelephonyUtil;
import org.whispersystems.libsignal.util.guava.Optional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SubscriptionManagerCompat {

  private static volatile SubscriptionManagerCompat instance;

  private final Context context;

  /**
   * Snapshot of the last successfully loaded compat list.
   * This is kept only as a best-effort cache for callers that explicitly invoke
   * updateActiveSubscriptionInfoList() and then want to reuse the last result.
   *
   * Important:
   * getActiveSubscriptionInfoList() does NOT trust this cache blindly and always
   * refreshes from the system to avoid stale or empty data being stuck after app install,
   * permission grant, or delayed SIM initialization.
   */
  private volatile @NonNull List<SubscriptionInfoCompat> lastKnownCompatList = Collections.emptyList();

  private SubscriptionManagerCompat(@NonNull Context context) {
    this.context = context.getApplicationContext();
  }

  public static @NonNull SubscriptionManagerCompat from(@NonNull Context context) {
    if (instance == null) {
      synchronized (SubscriptionManagerCompat.class) {
        if (instance == null) {
          instance = new SubscriptionManagerCompat(context.getApplicationContext());
        }
      }
    }

    return instance;
  }

  private static boolean isDuplicateDisplayName(@NonNull List<String> displayNames, CharSequence displayName) {
    if (displayName == null) {
      return false;
    }

    String target = displayName.toString();
    int count = 0;

    for (String candidate : displayNames) {
      if (candidate != null && candidate.equals(target)) {
        count++;
        if (count > 1) {
          return true;
        }
      }
    }

    return false;
  }

  public static @NonNull Optional<Integer> getDefaultMessagingSubscriptionId() {
    int id = SmsManager.getDefaultSmsSubscriptionId();

    if (id < 0) {
      return Optional.absent();
    }

    return Optional.of(id);
  }

  public @NonNull Optional<SubscriptionInfoCompat> getActiveSubscriptionInfo(int subscriptionId) {
    List<SubscriptionInfoCompat> list = getActiveSubscriptionInfoList();

    for (SubscriptionInfoCompat subscriptionInfo : list) {
      if (subscriptionInfo.getSubscriptionId() == subscriptionId) {
        return Optional.of(subscriptionInfo);
      }
    }

    return Optional.absent();
  }

  public @NonNull Optional<SubscriptionInfoCompat> getActiveSubscriptionInfoFromDeviceSubscriptionId(int deviceSubscriptionId) {
    List<SubscriptionInfoCompat> list = getActiveSubscriptionInfoList();

    for (SubscriptionInfoCompat subscriptionInfo : list) {
      if (subscriptionInfo.getDeviceSubscriptionId() == deviceSubscriptionId) {
        return Optional.of(subscriptionInfo);
      }
    }

    return Optional.absent();
  }

  /**
   * Returns the current active subscription list.
   *
   * This method always refreshes from the framework instead of returning a stale cached list.
   * That prevents the common failure mode where the first query happens too early after install
   * or before telephony becomes ready, resulting in an empty list that remains cached forever.
   */
  public @NonNull List<SubscriptionInfoCompat> getActiveSubscriptionInfoList() {
    return updateActiveSubscriptionInfoList();
  }

  /**
   * Rebuilds and stores the current active subscription list snapshot.
   */
  public synchronized @NonNull List<SubscriptionInfoCompat> updateActiveSubscriptionInfoList() {
    List<SubscriptionInfo> subscriptionInfos = TelephonyUtil.getActiveSubscriptionInfoListSafe(context);

    if (subscriptionInfos.isEmpty()) {
      lastKnownCompatList = Collections.emptyList();
      return lastKnownCompatList;
    }

    List<String> displayNames = new ArrayList<>(subscriptionInfos.size());

    for (SubscriptionInfo subscriptionInfo : subscriptionInfos) {
      CharSequence displayName = subscriptionInfo.getDisplayName();
      displayNames.add(displayName != null ? displayName.toString() : null);
    }

    List<SubscriptionInfoCompat> compatList = new ArrayList<>(subscriptionInfos.size());

    for (SubscriptionInfo subscriptionInfo : subscriptionInfos) {
      compatList.add(new SubscriptionInfoCompat(
              context,
              subscriptionInfo.getSubscriptionId(),
              subscriptionInfo.getDisplayName(),
              subscriptionInfo.getNumber(),
              subscriptionInfo.getIccId(),
              subscriptionInfo.getSimSlotIndex() + 1,
              subscriptionInfo.getMcc(),
              subscriptionInfo.getMnc(),
              isDuplicateDisplayName(displayNames, subscriptionInfo.getDisplayName())
      ));
    }

    lastKnownCompatList = Collections.unmodifiableList(compatList);
    return lastKnownCompatList;
  }
}