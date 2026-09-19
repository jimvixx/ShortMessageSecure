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

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class SilenceSubscriptionReviewTest {
  @Test public void requiresSourceAndCompletedInventory() {
    SilenceSubscriptionReview review = new SilenceSubscriptionReview();
    assertFalse(review.canReview()); review.replaceSource(source()); assertFalse(review.canReview());
    review.completeRefresh(targets(42, 7)); assertTrue(review.canReview());
    assertTrue(review.getPlan().getAssignments().isEmpty());
  }
  @Test public void unchangedRefreshPreservesDecisionsButRejectsOldDialogs() {
    SilenceSubscriptionReview review = ready();
    assertTrue(review.decide(review.getRevision(), 1, 7, false));
    long old = review.getRevision(); review.beginRefresh();
    assertFalse(review.canReview()); assertTrue(review.availableTargets().isEmpty());
    assertFalse(review.decide(old, 2, null, true));
    review.completeRefresh(targets(42, 7));
    assertEquals(Collections.singletonMap(1, 7), review.getPlan().getAssignments());
    assertFalse(review.decide(old, 2, null, true));
  }
  @Test public void deviceChangeInvalidatesEvenIfLogicalIdIsTheSame() {
    SilenceSubscriptionReview review = ready(); review.decide(review.getRevision(), 1, 7, false);
    review.completeRefresh(targets(43, 7));
    assertTrue(review.getPlan().getAssignments().isEmpty());
  }
  @Test public void unavailableInventoryClearsAssignmentsAndAllowsOnlyDeferral() {
    SilenceSubscriptionReview review = ready(); review.decide(review.getRevision(), 1, 7, false);
    review.completeRefresh(SilenceTargetSubscriptions.unavailable(SilenceTargetSubscriptions.Status.PERMISSION_REQUIRED));
    assertTrue(review.getPlan().getAssignments().isEmpty());
    assertFalse(review.decide(review.getRevision(), 1, 7, false));
    assertTrue(review.decide(review.getRevision(), 1, null, true));
    assertFalse(review.getPlan().hasCompleteAssignments());
  }
  @Test public void sourceReplacementClearsAllDecisionsAndRejectsOldDialog() {
    SilenceSubscriptionReview review = ready(); review.decide(review.getRevision(), 1, null, true);
    long old = review.getRevision(); review.replaceSource(source());
    assertTrue(review.getPlan().getDeferredSources().isEmpty());
    assertFalse(review.decide(old, 1, 7, false));
    review.replaceSource(null); assertNull(review.getPlan()); assertFalse(review.canReview());
  }
  @Test public void collisionOrUnavailableTargetNeverChangesExistingDraft() {
    SilenceSubscriptionReview review = ready(); assertTrue(review.decide(review.getRevision(), 1, 7, false));
    SilenceSubscriptionPlan before = review.getPlan();
    assertFalse(review.decide(review.getRevision(), 2, 7, false));
    assertFalse(review.decide(review.getRevision(), 2, 42, false));
    assertFalse(review.decide(review.getRevision(), 99, null, true));
    assertSame(before, review.getPlan());
  }
  @Test public void userCanDeferClearAndReassignWithoutMutatingPreviousSnapshot() {
    SilenceSubscriptionReview review = ready(); review.decide(review.getRevision(), 1, 7, false);
    SilenceSubscriptionPlan before = review.getPlan();
    assertTrue(review.decide(review.getRevision(), 1, null, true));
    assertTrue(review.getPlan().getAssignments().isEmpty());
    assertTrue(review.getPlan().getDeferredSources().contains(1));
    assertTrue(review.decide(review.getRevision(), 1, null, false));
    assertTrue(review.getPlan().getDeferredSources().isEmpty());
    assertTrue(review.decide(review.getRevision(), 2, 7, false));
    assertEquals(Collections.singletonMap(1, 7), before.getAssignments());
  }
  @Test public void newlyOccupiedIdentityClearsDraftAndCannotBeAssigned() {
    SilenceSubscriptionReview review = ready();
    assertTrue(review.decide(review.getRevision(), 1, 7, false));
    review.completeRefresh(targets(42, 7).excludingIdentitySlots(Collections.singleton(7)));
    assertTrue(review.getPlan().getAssignments().isEmpty());
    assertFalse(review.decide(review.getRevision(), 1, 7, false));
    assertTrue(review.decide(review.getRevision(), 1, null, true));
  }
  @Test public void occupiedInventoryDoesNotMutateOriginalOrCountMissingMappings() {
    SilenceTargetSubscriptions original = targets(42, 7);
    SilenceTargetSubscriptions filtered = original.excludingIdentitySlots(Collections.singleton(7));
    assertEquals(Collections.singletonMap(42, 7), original.getCandidates());
    assertEquals(0, original.getOccupiedCount()); assertEquals(1, filtered.getOccupiedCount());
    assertEquals(0, filtered.getUnresolvedCount());
    assertEquals(1, filtered.excludingIdentitySlots(Collections.singleton(7)).getOccupiedCount());
  }
  private static SilenceSubscriptionReview ready() {
    SilenceSubscriptionReview review = new SilenceSubscriptionReview();
    review.replaceSource(source()); review.completeRefresh(targets(42, 7)); return review;
  }
  private static SilenceSubscriptionPlan source() {
    Map<Integer, Set<SilenceSubscriptionPlan.Origin>> sources = new HashMap<>();
    sources.put(1, EnumSet.of(SilenceSubscriptionPlan.Origin.IDENTITY));
    sources.put(2, EnumSet.of(SilenceSubscriptionPlan.Origin.IDENTITY));
    return new SilenceSubscriptionPlan(sources, 3);
  }
  private static SilenceTargetSubscriptions targets(int device, int app) {
    return SilenceTargetSubscriptions.from(Collections.singleton(device),
        Collections.singletonMap("app_subscription_id_for_device_subscription_id_" + device, app));
  }
}
