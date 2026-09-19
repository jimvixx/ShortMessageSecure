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

public class SilenceTargetSubscriptionsTest {
  private static final String PREFIX = "app_subscription_id_for_device_subscription_id_";

  @Test public void usesSavedAppIdInsteadOfDeviceIdOrNumericEquality() {
    Map<String, Object> prefs = new HashMap<>();
    prefs.put(PREFIX + 42, 7); prefs.put(PREFIX + 99, 8);
    SilenceTargetSubscriptions result = SilenceTargetSubscriptions.from(ids(42), prefs);
    assertEquals(Collections.singletonMap(42, 7), result.getCandidates());
    assertEquals(0, result.getUnresolvedCount());
    assertEquals(SilenceTargetSubscriptions.Status.AVAILABLE, result.getStatus());
  }
  @Test public void missingAndMalformedMappingsStayUnresolved() {
    for (Object value : Arrays.asList("7", -1, 7L, true)) {
      SilenceTargetSubscriptions result = SilenceTargetSubscriptions.from(ids(42, 43),
          Collections.singletonMap(PREFIX + 42, value));
      assertTrue(result.getCandidates().isEmpty()); assertEquals(2, result.getUnresolvedCount());
    }
  }
  @Test public void removesEveryActiveMappingToACollidingAppId() {
    Map<String, Object> prefs = new HashMap<>();
    prefs.put(PREFIX + 42, 7); prefs.put(PREFIX + 43, 7); prefs.put(PREFIX + 44, 9);
    SilenceTargetSubscriptions result = SilenceTargetSubscriptions.from(ids(42, 43, 44), prefs);
    assertEquals(Collections.singletonMap(44, 9), result.getCandidates());
    assertEquals(2, result.getUnresolvedCount());
  }
  @Test public void ignoresInactiveAliasesAndMetadataWithoutInventingTargets() {
    Map<String, Object> prefs = new HashMap<>();
    prefs.put(PREFIX + 99, 7); prefs.put("last_app_subscription_id", 100);
    prefs.put("number_for_app_subscription_id_7", "synthetic");
    SilenceTargetSubscriptions result = SilenceTargetSubscriptions.from(ids(42), prefs);
    assertTrue(result.getCandidates().isEmpty()); assertEquals(1, result.getUnresolvedCount());
  }
  @Test public void snapshotsAreImmutableAndInputsUnchanged() {
    Set<Integer> active = ids(42);
    Map<String, Object> prefs = new HashMap<>(); prefs.put(PREFIX + 42, 0);
    SilenceTargetSubscriptions result = SilenceTargetSubscriptions.from(active, prefs);
    assertEquals(ids(42), active); assertEquals(Integer.valueOf(0), prefs.get(PREFIX + 42));
    active.clear(); prefs.clear();
    assertEquals(Collections.singletonMap(42, 0), result.getCandidates());
    assertThrows(UnsupportedOperationException.class, () -> result.getCandidates().clear());
  }
  @Test public void distinguishesEmptyInventoryFromUnavailableInventory() {
    assertEquals(SilenceTargetSubscriptions.Status.AVAILABLE,
        SilenceTargetSubscriptions.from(ids(), Collections.emptyMap()).getStatus());
    for (SilenceTargetSubscriptions.Status status : Arrays.asList(
        SilenceTargetSubscriptions.Status.PERMISSION_REQUIRED, SilenceTargetSubscriptions.Status.UNAVAILABLE)) {
      SilenceTargetSubscriptions result = SilenceTargetSubscriptions.unavailable(status);
      assertEquals(status, result.getStatus()); assertTrue(result.getCandidates().isEmpty());
    }
  }
  @Test public void rejectsInvalidActiveIds() {
    assertThrows(IllegalArgumentException.class, () -> SilenceTargetSubscriptions.from(ids(-1), Collections.emptyMap()));
    assertThrows(IllegalArgumentException.class, () -> SilenceTargetSubscriptions.from(ids((Integer) null), Collections.emptyMap()));
  }
  @Test public void preservesOverlappingReasonsWithoutDoubleCounting() {
    SilenceTargetSubscriptions original = SilenceTargetSubscriptions.from(ids(42), Collections.singletonMap(PREFIX + 42, 7));
    SilenceTargetSubscriptions result = original.excluding(ids(7), SilenceTargetSubscriptions.Conflict.IDENTITY_KEYS)
        .excluding(ids(7, 99), SilenceTargetSubscriptions.Conflict.DATABASE_REFERENCES);
    assertEquals(1, result.getOccupiedCount()); assertEquals(2, result.getConflicts().get(7).size());
    assertEquals(1, result.getConflictCount(SilenceTargetSubscriptions.Conflict.IDENTITY_KEYS));
    assertEquals(1, result.getConflictCount(SilenceTargetSubscriptions.Conflict.DATABASE_REFERENCES));
    assertEquals(0, result.getConflictCount(SilenceTargetSubscriptions.Conflict.CRYPTO_FILES));
    assertTrue(original.getConflicts().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> result.getConflicts().clear());
    assertThrows(UnsupportedOperationException.class, () -> result.getConflicts().get(7).clear());
  }
  @Test public void conflictInputIsCopiedAndRepeatedReasonIsIdempotent() {
    Set<Integer> occupied = ids(7);
    SilenceTargetSubscriptions result = SilenceTargetSubscriptions.from(ids(42), Collections.singletonMap(PREFIX + 42, 7))
        .excluding(occupied, SilenceTargetSubscriptions.Conflict.CRYPTO_FILES);
    occupied.clear();
    assertEquals(result.getConflicts(), result.excluding(ids(7), SilenceTargetSubscriptions.Conflict.CRYPTO_FILES).getConflicts());
    assertEquals(1, result.getOccupiedCount());
  }
  private static Set<Integer> ids(Integer... values) { return new HashSet<>(Arrays.asList(values)); }
}
