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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Main-thread draft state; never persists decisions or applies them to live data. */
final class SilenceSubscriptionReview {
  private SilenceSubscriptionPlan plan;
  private SilenceTargetSubscriptions targets;
  private boolean refreshing;
  private long revision;

  void replaceSource(SilenceSubscriptionPlan source) {
    plan = source == null ? null : source.withAssignments(Collections.emptyMap(), Collections.emptySet());
    revision++;
  }
  void beginRefresh() { refreshing = true; revision++; }
  void completeRefresh(SilenceTargetSubscriptions next) {
    boolean changed = targets == null || targets.getStatus() != next.getStatus()
        || !targets.getCandidates().equals(next.getCandidates())
        || targets.getUnresolvedCount() != next.getUnresolvedCount();
    targets = next;
    refreshing = false;
    if (changed && plan != null) plan = plan.withAssignments(Collections.emptyMap(), Collections.emptySet());
    revision++;
  }
  SilenceSubscriptionPlan getPlan() { return plan; }
  long getRevision() { return revision; }
  boolean canReview() { return plan != null && !refreshing && targets != null; }
  Set<Integer> availableTargets() {
    return targets == null || refreshing || targets.getStatus() != SilenceTargetSubscriptions.Status.AVAILABLE
        ? Collections.emptySet() : new java.util.TreeSet<>(targets.getCandidates().values());
  }
  /** A null target clears the source decision or explicitly defers it. Rejects stale dialogs. */
  boolean decide(long expectedRevision, int source, Integer target, boolean defer) {
    if (!canReview() || revision != expectedRevision || !plan.getSources().containsKey(source)
        || (defer && target != null)) return false;
    Map<Integer, Integer> assignments = new HashMap<>(plan.getAssignments());
    Set<Integer> deferred = new HashSet<>(plan.getDeferredSources());
    assignments.remove(source); deferred.remove(source);
    if (target != null) assignments.put(source, target);
    if (defer) deferred.add(source);
    try {
      plan = plan.withDecisions(assignments, deferred, availableTargets());
      revision++;
      return true;
    } catch (IllegalArgumentException e) { return false; }
  }
}
