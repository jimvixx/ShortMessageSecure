/*
 * Copyright (C) 2026 Jimvixx
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
import android.telephony.SubscriptionInfo;
import android.text.TextUtils;

import org.jimvixx.smsecure.util.SMSecurePreferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves the whole active list before publishing any preference changes. */
final class SubscriptionIdResolver {
  private SubscriptionIdResolver() {}

  static final class Mapping {
    final int appId;
    final boolean conflict;

    Mapping(int appId, boolean conflict) {
      this.appId = appId;
      this.conflict = conflict;
    }
  }

  static synchronized List<Mapping> resolve(Context context, List<SubscriptionInfo> subscriptions) {
    int savedLastId = SMSecurePreferences.getLastAppSubscriptionId(context);
    int lastId = savedLastId;
    List<Entry> entries = new ArrayList<>(subscriptions.size());
    Map<Integer, Integer> owners = new HashMap<>();
    Map<String, Integer> activeIccCounts = new HashMap<>();
    Set<Integer> reserved = new HashSet<>();
    Set<Integer> conflicts = new HashSet<>();

    // Reserve every existing binding, including ones appearing later in the Android list.
    for (SubscriptionInfo subscription : subscriptions) {
      Entry entry = new Entry(context, subscription);
      entries.add(entry);
      if (!TextUtils.isEmpty(entry.iccId)) {
        activeIccCounts.put(entry.iccId, getCount(activeIccCounts, entry.iccId) + 1);
      }
      if (entry.appId >= 0) {
        reserved.add(entry.appId);
        owners.put(entry.appId, getCount(owners, entry.appId) + 1);
        lastId = Math.max(lastId, entry.appId);
        String savedIcc = SMSecurePreferences.getIccIdForSubscriptionId(context, entry.appId);
        if (SMSecurePreferences.hasSubscriptionMappingConflict(context, entry.appId)
                || (!TextUtils.isEmpty(savedIcc) && !TextUtils.isEmpty(entry.iccId)
                    && !savedIcc.equals(entry.iccId))) {
          conflicts.add(entry.appId);
        }
      }
    }
    for (Map.Entry<Integer, Integer> owner : owners.entrySet()) {
      if (owner.getValue() > 1) conflicts.add(owner.getKey());
    }

    // Number and slot are deliberately not identity evidence. Duplicate historical ICCIDs
    // are also insufficient evidence, even when only one of those IDs is currently available.
    Map<String, Integer> historicalIccIds = new HashMap<>();
    Set<String> duplicateHistoricalIccIds = new HashSet<>();
    for (Map.Entry<Integer, String> saved : SMSecurePreferences.getIccIdMappings(context).entrySet()) {
      int appId = saved.getKey();
      String iccId = saved.getValue();
      if (appId < 0 || appId > savedLastId || TextUtils.isEmpty(iccId)) continue;
      if (historicalIccIds.put(iccId, appId) != null) duplicateHistoricalIccIds.add(iccId);
    }

    for (Entry entry : entries) {
      if (entry.appId >= 0) continue;
      Integer historicalId = historicalIccIds.get(entry.iccId);
      if (historicalId != null && getCount(activeIccCounts, entry.iccId) == 1
              && !duplicateHistoricalIccIds.contains(entry.iccId) && !reserved.contains(historicalId)
              && !SMSecurePreferences.hasSubscriptionMappingConflict(context, historicalId)) {
        entry.appId = historicalId;
      } else {
        entry.appId = Math.incrementExact(lastId);
        lastId = entry.appId;
      }
      reserved.add(entry.appId);
    }

    Map<Integer, Integer> deviceUpdates = new HashMap<>();
    Map<Integer, String> numberUpdates = new HashMap<>();
    Map<Integer, String> iccUpdates = new HashMap<>();
    Set<Integer> conflictUpdates = new HashSet<>();
    List<Mapping> result = new ArrayList<>(entries.size());
    for (Entry entry : entries) {
      boolean conflict = conflicts.contains(entry.appId);
      result.add(new Mapping(entry.appId, conflict));
      if (entry.appId != SMSecurePreferences.getAppSubscriptionId(context, entry.deviceId)) {
        deviceUpdates.put(entry.deviceId, entry.appId);
      }
      if (conflict) {
        if (!SMSecurePreferences.hasSubscriptionMappingConflict(context, entry.appId)) {
          conflictUpdates.add(entry.appId);
        }
        // Preserve the last saved evidence for explicit recovery instead of overwriting it.
        continue;
      }
      if (!TextUtils.isEmpty(entry.number)
              && !entry.number.equals(SMSecurePreferences.getNumberForSubscriptionId(context, entry.appId))) {
        numberUpdates.put(entry.appId, entry.number);
      }
      if (!TextUtils.isEmpty(entry.iccId)
              && !entry.iccId.equals(SMSecurePreferences.getIccIdForSubscriptionId(context, entry.appId))) {
        iccUpdates.put(entry.appId, entry.iccId);
      }
    }
    if (lastId != savedLastId || !deviceUpdates.isEmpty() || !numberUpdates.isEmpty()
            || !iccUpdates.isEmpty() || !conflictUpdates.isEmpty()) {
      SMSecurePreferences.applySubscriptionMappings(context, deviceUpdates, numberUpdates,
              iccUpdates, conflictUpdates, lastId);
    }
    return result;
  }

  private static <K> int getCount(Map<K, Integer> counts, K key) {
    Integer count = counts.get(key);
    return count != null ? count : 0;
  }

  private static final class Entry {
    final int deviceId;
    final String number;
    final String iccId;
    int appId;

    Entry(Context context, SubscriptionInfo subscription) {
      deviceId = subscription.getSubscriptionId();
      number = SubscriptionManagerCompat.getSubscriptionNumber(context, subscription);
      iccId = subscription.getIccId();
      appId = SMSecurePreferences.getAppSubscriptionId(context, deviceId);
    }
  }
}
