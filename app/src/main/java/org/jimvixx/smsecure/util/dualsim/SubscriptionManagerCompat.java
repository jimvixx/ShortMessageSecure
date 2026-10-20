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
import android.os.Build;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import org.jimvixx.smsecure.util.TelephonyUtil;
import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.whispersystems.libsignal.util.guava.Optional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;

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

  public static @NonNull Optional<Integer> getDefaultMessagingSubscriptionId(@NonNull Context context) {
    int deviceSubscriptionId = SmsManager.getDefaultSmsSubscriptionId();

    if (deviceSubscriptionId < 0) {
      return Optional.absent();
    }

    Optional<SubscriptionInfoCompat> subscriptionInfo =
            from(context).getActiveSubscriptionInfoFromDeviceSubscriptionId(deviceSubscriptionId);

    return subscriptionInfo.isPresent()
            ? Optional.of(subscriptionInfo.get().getSubscriptionId())
            : Optional.absent();
  }

  public @NonNull Optional<SubscriptionInfoCompat> getActiveSubscriptionInfo(int subscriptionId) {
    List<SubscriptionInfoCompat> list = getActiveSubscriptionInfoList();

    SubscriptionInfoCompat match = null;
    for (SubscriptionInfoCompat subscriptionInfo : list) {
      if (subscriptionInfo.getSubscriptionId() == subscriptionId) {
        // A shared app ID cannot identify which physical SIM the caller intended.
        if (match != null || subscriptionInfo.hasMappingConflict()) return Optional.absent();
        match = subscriptionInfo;
      }
    }

    return Optional.fromNullable(match);
  }

  public @NonNull Optional<SubscriptionInfoCompat> getActiveSubscriptionInfoFromDeviceSubscriptionId(int deviceSubscriptionId) {
    List<SubscriptionInfoCompat> list = getActiveSubscriptionInfoList();

    for (SubscriptionInfoCompat subscriptionInfo : list) {
      if (subscriptionInfo.getDeviceSubscriptionId() == deviceSubscriptionId) {
        if (subscriptionInfo.hasMappingConflict()) return Optional.absent();
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

  /** Returns the last loaded snapshot without querying Android or updating saved SIM mappings. */
  public @NonNull List<SubscriptionInfoCompat> getLastKnownActiveSubscriptionInfoList() {
    return lastKnownCompatList;
  }

  public synchronized Optional<SubscriptionRecoveryPlan> prepareRecovery(int appId) {
    List<SubscriptionInfoCompat> active = updateActiveSubscriptionInfoList();
    for (SubscriptionInfoCompat info : active) {
      if (info.getSubscriptionId() == appId && info.hasMappingConflict()) {
        return Optional.of(new SubscriptionRecoveryPlan(context, appId, active));
      }
    }
    return Optional.absent();
  }

  /** Applies only an explicitly confirmed, still-current plan. No keys, sessions or messages are moved. */
  public synchronized boolean applyRecovery(SubscriptionRecoveryPlan plan, int selectedDeviceId) {
    if (plan == null || !plan.matches(context, TelephonyUtil.getActiveSubscriptionInfoListSafe(context))) return false;
    boolean selected = false;
    for (SubscriptionInfoCompat info : plan.getCandidates()) {
      selected |= info.getDeviceSubscriptionId() == selectedDeviceId;
    }
    if (!selected) return false;

    Set<Integer> removedDevices = new HashSet<>();
    for (Map.Entry<Integer, Integer> entry : plan.savedMappings.entrySet()) {
      if (entry.getValue() == plan.appId) removedDevices.add(entry.getKey());
    }
    Map<Integer, Integer> mappings = new HashMap<>();
    Map<Integer, String> numbers = new HashMap<>();
    Map<Integer, String> iccIds = new HashMap<>();
    int lastId = plan.lastAppId;
    for (SubscriptionInfoCompat info : plan.getCandidates()) {
      int id = info.getDeviceSubscriptionId() == selectedDeviceId ? plan.appId : Math.incrementExact(lastId);
      lastId = Math.max(lastId, id);
      mappings.put(info.getDeviceSubscriptionId(), id);
      numbers.put(id, info.getNumber());
      iccIds.put(id, info.getIccId());
    }
    // The conflicted app ID does not reveal which physical SIM a saved recipient choice meant.
    // Clear those choices before publishing the new binding so later sends require a fresh route.
    DatabaseFactory.getRecipientPreferenceDatabase(context).clearDefaultSubscriptionId(plan.appId);
    SMSecurePreferences.applySubscriptionRecovery(context, mappings, removedDevices, numbers,
            iccIds, plan.appId, lastId, Math.incrementExact(plan.generation));
    lastKnownCompatList = Collections.emptyList();
    return true;
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

    List<SubscriptionIdResolver.Mapping> mappings = SubscriptionIdResolver.resolve(context, subscriptionInfos);
    List<SubscriptionInfoCompat> compatList = new ArrayList<>(subscriptionInfos.size());

    for (int i = 0; i < subscriptionInfos.size(); i++) {
      SubscriptionInfo subscriptionInfo = subscriptionInfos.get(i);
      SubscriptionIdResolver.Mapping mapping = mappings.get(i);
      compatList.add(new SubscriptionInfoCompat(
              context,
              mapping.appId,
              mapping.conflict,
              subscriptionInfo.getSubscriptionId(),
              subscriptionInfo.getDisplayName(),
              getSubscriptionNumber(context, subscriptionInfo),
              subscriptionInfo.getIccId(),
              subscriptionInfo.getSimSlotIndex() + 1,
              getMcc(subscriptionInfo),
              getMnc(subscriptionInfo),
              isDuplicateDisplayName(displayNames, subscriptionInfo.getDisplayName())
      ));
    }

    lastKnownCompatList = Collections.unmodifiableList(compatList);
    return lastKnownCompatList;
  }

  static @NonNull String getSubscriptionNumber(Context context, SubscriptionInfo info) {
    // The modern replacement requires READ_PHONE_NUMBERS or carrier privileges, while this
    // compatibility path is also available to the default SMS app and holders of READ_SMS.
    // Keep the role-compatible accessor until the application intentionally requests the
    // broader phone-number permission.
    return getLegacySubscriptionNumber(info);
  }

  @SuppressWarnings("deprecation")
  private static @NonNull String getLegacySubscriptionNumber(SubscriptionInfo info) {
    String number = info.getNumber();
    return number != null ? number : "";
  }

  static int getMcc(SubscriptionInfo info) {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ? getModernMcc(info) : getLegacyMcc(info);
  }

  static int getMnc(SubscriptionInfo info) {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ? getModernMnc(info) : getLegacyMnc(info);
  }

  @RequiresApi(Build.VERSION_CODES.Q)
  static int getModernMcc(SubscriptionInfo info) {
    return parseNetworkCode(info.getMccString());
  }

  @RequiresApi(Build.VERSION_CODES.Q)
  static int getModernMnc(SubscriptionInfo info) {
    return parseNetworkCode(info.getMncString());
  }

  private static int parseNetworkCode(String value) {
    if (value == null || value.isEmpty()) return 0;
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException failure) {
      return 0;
    }
  }

  @SuppressWarnings("deprecation")
  private static int getLegacyMcc(SubscriptionInfo info) {
    return info.getMcc();
  }

  @SuppressWarnings("deprecation")
  private static int getLegacyMnc(SubscriptionInfo info) {
    return info.getMnc();
  }
}
