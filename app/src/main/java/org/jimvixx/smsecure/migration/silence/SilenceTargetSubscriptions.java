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

package org.jimvixx.smsecure.migration.silence;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Read-only candidates from active device IDs and saved SMSecure mappings, not identity proof. */
public final class SilenceTargetSubscriptions {
  public enum Conflict { IDENTITY_KEYS, CRYPTO_FILES, DATABASE_REFERENCES }
  public enum Status { AVAILABLE, PERMISSION_REQUIRED, UNAVAILABLE }
  private static final String MAPPING = "app_subscription_id_for_device_subscription_id_";
  private final Status status;
  private final Map<Integer, Integer> candidates;
  private final int unresolvedCount;
  private final Map<Integer, Set<Conflict>> conflicts;

  private SilenceTargetSubscriptions(Status status, Map<Integer, Integer> candidates, int unresolvedCount) {
    this(status, candidates, unresolvedCount, Collections.emptyMap());
  }

  private SilenceTargetSubscriptions(Status status, Map<Integer, Integer> candidates, int unresolvedCount, Map<Integer, Set<Conflict>> conflicts) {
    Map<Integer, Set<Conflict>> copied = new TreeMap<>();
    for (Map.Entry<Integer, Set<Conflict>> entry : conflicts.entrySet())
      copied.put(entry.getKey(), Collections.unmodifiableSet(java.util.EnumSet.copyOf(entry.getValue())));
    this.conflicts = Collections.unmodifiableMap(copied);
    this.status = status;
    this.candidates = Collections.unmodifiableMap(new TreeMap<>(candidates));
    this.unresolvedCount = unresolvedCount;
  }

  static SilenceTargetSubscriptions unavailable(Status status) {
    if (status == Status.AVAILABLE) throw new IllegalArgumentException("Expected unavailable status");
    return new SilenceTargetSubscriptions(status, Collections.emptyMap(), 0);
  }

  static SilenceTargetSubscriptions from(Set<Integer> activeDeviceIds, Map<String, ?> preferences) {
    Map<Integer, Integer> selected = new TreeMap<>();
    Set<Integer> seen = new HashSet<>();
    Set<Integer> conflicting = new HashSet<>();
    for (Integer deviceId : activeDeviceIds) {
      if (deviceId == null || deviceId < 0) throw new IllegalArgumentException("Invalid active subscription");
      Object saved = preferences.get(MAPPING + deviceId);
      if (!(saved instanceof Integer) || (Integer) saved < 0) continue;
      int appId = (Integer) saved;
      selected.put(deviceId, appId);
      if (!seen.add(appId)) conflicting.add(appId);
    }
    selected.values().removeAll(conflicting);
    return new SilenceTargetSubscriptions(Status.AVAILABLE, selected, activeDeviceIds.size() - selected.size());
  }

  SilenceTargetSubscriptions excludingIdentitySlots(Set<Integer> occupied) {
    return excluding(occupied, Conflict.IDENTITY_KEYS);
  }

  /** Preserves overlapping reasons while counting each blocked logical binding only once. */
  SilenceTargetSubscriptions excluding(Set<Integer> occupied, Conflict reason) {
    java.util.Objects.requireNonNull(reason);
    Map<Integer, Integer> remaining = new TreeMap<>(candidates);
    Map<Integer, Set<Conflict>> reasons = new TreeMap<>();
    for (Map.Entry<Integer, Set<Conflict>> entry : conflicts.entrySet())
      reasons.put(entry.getKey(), java.util.EnumSet.copyOf(entry.getValue()));
    for (int slot : occupied) {
      if (!candidates.containsValue(slot) && !conflicts.containsKey(slot)) continue;
      reasons.computeIfAbsent(slot, ignored -> java.util.EnumSet.noneOf(Conflict.class)).add(reason);
    }
    remaining.values().removeAll(occupied);
    return new SilenceTargetSubscriptions(status, remaining, unresolvedCount, reasons);
  }

  public Map<Integer, Set<Conflict>> getConflicts() { return conflicts; }
  public int getConflictCount(Conflict reason) {
    int count = 0;
    for (Set<Conflict> reasons : conflicts.values()) if (reasons.contains(reason)) count++;
    return count;
  }
  public int getOccupiedCount() { return conflicts.size(); }
  public Status getStatus() { return status; }
  /** Keys are Android device IDs; values are SMSecure logical IDs. Never auto-select a source. */
  public Map<Integer, Integer> getCandidates() { return candidates; }
  public int getUnresolvedCount() { return unresolvedCount; }
}
