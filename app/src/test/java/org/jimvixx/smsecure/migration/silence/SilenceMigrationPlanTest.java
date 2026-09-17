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

import java.io.IOException;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SilenceMigrationPlanTest {
  @Test public void copiesOnlyExplicitAllowlistedBooleans() throws Exception {
    Map<String, String> source = new HashMap<>(); source.put("pref_show_sent_time", "boolean:true");
    source.put("pref_key_enable_notifications", "boolean:false"); source.put("pref_disable_passphrase", "boolean:true");
    source.put("pref_screen_security", "boolean:false"); source.put("pref_apn_mmsc_password", "string:synthetic-secret");
    SilencePreferencePlan plan = SilencePreferencePlan.from(source);
    assertEquals(2, plan.getCandidates().size()); assertTrue(plan.getCandidates().get("pref_show_sent_time"));
    assertFalse(plan.getCandidates().get("pref_key_enable_notifications")); assertEquals(3, plan.getDeferredCount());
  }
  @Test public void missingPreferencesDoNotInventDefaults() throws Exception {
    assertTrue(SilencePreferencePlan.from(Collections.emptyMap()).getCandidates().isEmpty());
  }
  @Test public void rejectsWrongCandidateTypes() {
    for (String value : new String[]{"string:true", "int:1", "boolean:yes", "boolean:"})
      assertThrows(IOException.class, () -> SilencePreferencePlan.from(Collections.singletonMap("pref_show_sent_time", value)));
  }
  @Test public void preferencePlanIsDefensiveAndImmutable() throws Exception {
    Map<String, String> source = new HashMap<>(); source.put("pref_system_emoji", "boolean:true");
    SilencePreferencePlan plan = SilencePreferencePlan.from(source); source.clear();
    assertEquals(1, plan.getCandidates().size());
    assertThrows(UnsupportedOperationException.class, () -> plan.getCandidates().clear());
  }
  @Test public void numericEqualityDoesNotAutoAssignASim() {
    SilenceSubscriptionPlan plan = source(3); assertTrue(plan.getAssignments().isEmpty()); assertFalse(plan.hasCompleteAssignments());
  }
  @Test public void permitsExplicitCompleteAndPartialPlans() {
    SilenceSubscriptionPlan plan = source(3, 4);
    SilenceSubscriptionPlan partial = plan.withAssignments(Collections.singletonMap(3, 7), targets(7, 8));
    assertFalse(partial.hasCompleteAssignments());
    Map<Integer, Integer> mapping = new HashMap<>(); mapping.put(3, 7); mapping.put(4, 8);
    assertTrue(plan.withAssignments(mapping, targets(7, 8)).hasCompleteAssignments());
    assertTrue(plan.getAssignments().isEmpty());
  }
  @Test public void requiresExplicitTargetForLegacyUnscopedIdentity() {
    assertTrue(source(-1).withAssignments(Collections.singletonMap(-1, 7), targets(7)).hasCompleteAssignments());
    assertThrows(IllegalArgumentException.class, () -> source(-1).withAssignments(Collections.singletonMap(-1, -1), targets(-1)));
  }
  @Test public void refusesToMergeDistinctSourceIdentities() {
    Map<Integer, Integer> mapping = new HashMap<>(); mapping.put(3, 7); mapping.put(4, 7);
    assertThrows(IllegalArgumentException.class, () -> source(3, 4).withAssignments(mapping, targets(7)));
  }
  @Test public void rejectsUnknownSourceAndUnavailableTarget() {
    assertThrows(IllegalArgumentException.class, () -> source(3).withAssignments(Collections.singletonMap(9, 7), targets(7)));
    assertThrows(IllegalArgumentException.class, () -> source(3).withAssignments(Collections.singletonMap(3, 8), targets(7)));
  }
  @Test public void deeplyCopiesOriginsAndAssignments() {
    Set<SilenceSubscriptionPlan.Origin> origins = EnumSet.of(SilenceSubscriptionPlan.Origin.SMS);
    Map<Integer, Set<SilenceSubscriptionPlan.Origin>> slots = new HashMap<>(); slots.put(3, origins);
    SilenceSubscriptionPlan plan = new SilenceSubscriptionPlan(slots, 5); origins.clear(); slots.clear();
    Map<Integer, Integer> mapping = new HashMap<>(); mapping.put(3, 7);
    SilenceSubscriptionPlan assigned = plan.withAssignments(mapping, targets(7)); mapping.clear();
    assertEquals(1, assigned.getAssignments().size()); assertEquals(5, assigned.getSmsWithoutSubscription());
    assertEquals(1, assigned.getSources().get(3).size());
    assertThrows(UnsupportedOperationException.class, () -> assigned.getSources().get(3).clear());
    assertThrows(UnsupportedOperationException.class, () -> assigned.getAssignments().clear());
  }
  @Test public void emptySourceInventoryNeedsNoInventedSim() { assertTrue(source().hasCompleteAssignments()); }
  private static Set<Integer> targets(Integer... values) { return new HashSet<>(Arrays.asList(values)); }
  private static SilenceSubscriptionPlan source(Integer... values) {
    Map<Integer, Set<SilenceSubscriptionPlan.Origin>> sources = new HashMap<>();
    for (int value : values) sources.put(value, EnumSet.of(SilenceSubscriptionPlan.Origin.IDENTITY));
    return new SilenceSubscriptionPlan(sources, 0);
  }
}
