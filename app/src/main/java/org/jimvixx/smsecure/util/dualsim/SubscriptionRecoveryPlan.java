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
import org.jimvixx.smsecure.util.SMSecurePreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable confirmation snapshot. Never log or serialize its SIM identifiers. */
public final class SubscriptionRecoveryPlan {
  final int appId;
  final int lastAppId;
  final long generation;
  final Map<Integer, Integer> savedMappings;
  private final String savedNumber;
  private final String savedIcc;
  private final List<SubscriptionInfoCompat> active;
  private final List<SubscriptionInfoCompat> candidates;

  SubscriptionRecoveryPlan(Context context, int appId, List<SubscriptionInfoCompat> active) {
    this.appId = appId;
    this.active = new ArrayList<>(active);
    this.lastAppId = SMSecurePreferences.getLastAppSubscriptionId(context);
    this.generation = SMSecurePreferences.getSubscriptionRecoveryGeneration(context);
    this.savedMappings = SMSecurePreferences.getDeviceSubscriptionMappings(context);
    this.savedNumber = SMSecurePreferences.getNumberForSubscriptionId(context, appId);
    this.savedIcc = SMSecurePreferences.getIccIdForSubscriptionId(context, appId);
    List<SubscriptionInfoCompat> choices = new ArrayList<>();
    for (SubscriptionInfoCompat info : active) {
      if (info.getSubscriptionId() == appId) choices.add(info);
    }
    candidates = Collections.unmodifiableList(choices);
  }

  public List<SubscriptionInfoCompat> getCandidates() {
    return candidates;
  }

  boolean matches(Context context, List<SubscriptionInfo> current) {
    if (generation != SMSecurePreferences.getSubscriptionRecoveryGeneration(context)
            || lastAppId != SMSecurePreferences.getLastAppSubscriptionId(context)
            || !savedMappings.equals(SMSecurePreferences.getDeviceSubscriptionMappings(context))
            || !SMSecurePreferences.hasSubscriptionMappingConflict(context, appId)
            || !Objects.equals(savedNumber, SMSecurePreferences.getNumberForSubscriptionId(context, appId))
            || !Objects.equals(savedIcc, SMSecurePreferences.getIccIdForSubscriptionId(context, appId))
            || active.size() != current.size()) return false;
    Map<Integer, SubscriptionInfo> byDevice = new HashMap<>();
    for (SubscriptionInfo info : current) {
      if (info.getSubscriptionId() < 0 || byDevice.put(info.getSubscriptionId(), info) != null) return false;
    }
    for (SubscriptionInfoCompat info : active) {
      SubscriptionInfo now = byDevice.get(info.getDeviceSubscriptionId());
      if (now == null || info.getIccSlot() != now.getSimSlotIndex() + 1
              || !Objects.equals(info.getIccId(), now.getIccId())
              || !info.getNumber().equals(SubscriptionManagerCompat.getSubscriptionNumber(context, now))) return false;
    }
    return true;
  }
}
