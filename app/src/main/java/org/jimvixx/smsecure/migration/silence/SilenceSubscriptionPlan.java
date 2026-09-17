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
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Immutable source inventory and optional explicit assignments; no installed-SIM discovery or writes. */
public final class SilenceSubscriptionPlan {
  public enum Origin { IDENTITY, SESSION, SMS, RECIPIENT_DEFAULT, SOURCE_METADATA }
  private final Map<Integer, Set<Origin>> sources;
  private final Map<Integer, Integer> assignments;
  private final Set<Integer> deferredSources;
  private final long smsWithoutSubscription;
  SilenceSubscriptionPlan(Map<Integer, Set<Origin>> sources, long smsWithoutSubscription) {
    this(sources, smsWithoutSubscription, Collections.emptyMap(), Collections.emptySet());
  }
  private SilenceSubscriptionPlan(Map<Integer, Set<Origin>> sources, long smsWithoutSubscription,
                                 Map<Integer, Integer> assignments, Set<Integer> deferredSources) {
    Map<Integer, Set<Origin>> copied = new TreeMap<>();
    for (Map.Entry<Integer, Set<Origin>> entry : sources.entrySet()) {
      if (entry.getKey() < -1 || entry.getValue().isEmpty()) throw new IllegalArgumentException("Invalid source slot");
      copied.put(entry.getKey(), Collections.unmodifiableSet(EnumSet.copyOf(entry.getValue())));
    }
    if (smsWithoutSubscription < 0) throw new IllegalArgumentException("Invalid SMS count");
    this.sources = Collections.unmodifiableMap(copied);
    this.assignments = Collections.unmodifiableMap(new TreeMap<>(assignments));
    this.deferredSources = Collections.unmodifiableSet(new TreeSet<>(deferredSources));
    this.smsWithoutSubscription = smsWithoutSubscription;
  }
  /** Target SMSecure app-subscription IDs must be supplied by the caller; numeric equality never selects one. */
  public SilenceSubscriptionPlan withAssignments(Map<Integer, Integer> proposed, Set<Integer> availableTargets) {
    return withDecisions(proposed, Collections.emptySet(), availableTargets);
  }
  /** Replaces the draft decisions. Deferred sources remain unresolved and are never discarded. */
  public SilenceSubscriptionPlan withDecisions(Map<Integer, Integer> proposed, Set<Integer> deferred,
                                               Set<Integer> availableTargets) {
    if (deferred.stream().anyMatch(value -> value == null) || !sources.keySet().containsAll(deferred)) {
      throw new IllegalArgumentException("Unknown deferred source");
    }
    Set<Integer> used = new HashSet<>();
    for (Map.Entry<Integer, Integer> entry : proposed.entrySet()) {
      Integer target = entry.getValue();
      if (deferred.contains(entry.getKey()) || !sources.containsKey(entry.getKey()) || target == null || target < 0 || !availableTargets.contains(target)
          || !used.add(target)) throw new IllegalArgumentException("Invalid or conflicting SIM assignment");
    }
    return new SilenceSubscriptionPlan(sources, smsWithoutSubscription, proposed, deferred);
  }
  public Map<Integer, Set<Origin>> getSources() { return sources; }
  public Map<Integer, Integer> getAssignments() { return assignments; }
  public Set<Integer> getDeferredSources() { return deferredSources; }
  public long getSmsWithoutSubscription() { return smsWithoutSubscription; }
  public boolean hasCompleteAssignments() { return assignments.keySet().equals(sources.keySet()); }
}
