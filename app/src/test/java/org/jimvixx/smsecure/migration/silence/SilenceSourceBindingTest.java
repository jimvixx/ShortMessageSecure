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
import org.junit.Test;
import static org.junit.Assert.*;

public class SilenceSourceBindingTest {
  @Test public void readsLegacyUnscopedSession() throws Exception {
    SilenceSourceBinding binding = SilenceSourceBinding.session("123");
    assertEquals(123, binding.recipientId); assertEquals(-1, binding.subscriptionId);
  }
  @Test public void readsScopedSessionWithoutChoosingTargetSim() throws Exception {
    SilenceSourceBinding binding = SilenceSourceBinding.session("123.45");
    assertEquals(123, binding.recipientId); assertEquals(45, binding.subscriptionId);
  }
  @Test public void rejectsMalformedOrOverflowingSessionNames() {
    for (String value : new String[]{"", "0", "01", "-1", "1.", "1.-1", "1.01", "1.2.3", "9223372036854775808", "1.2147483648"})
      assertThrows(IOException.class, () -> SilenceSourceBinding.session(value));
  }
  @Test public void resolvesConcatenationUsingEmbeddedRecordId() throws Exception {
    assertEquals(3, SilenceSourceBinding.preKeySubscription("123", 12));
    assertEquals(23, SilenceSourceBinding.preKeySubscription("123", 1));
    assertEquals(-1, SilenceSourceBinding.preKeySubscription("123", 123));
  }
  @Test public void rejectsRecordNameDisagreement() {
    assertThrows(IOException.class, () -> SilenceSourceBinding.preKeySubscription("123", 45));
    assertThrows(IOException.class, () -> SilenceSourceBinding.preKeySubscription("12", 123));
    assertThrows(IOException.class, () -> SilenceSourceBinding.preKeySubscription("123", -1));
  }
  @Test public void rejectsNonCanonicalPreKeySuffixes() {
    for (String value : new String[]{"1203", "12.3", "12-1", "122147483648", "0123"})
      assertThrows(IOException.class, () -> SilenceSourceBinding.preKeySubscription(value, 12));
  }
  @Test public void acceptsZeroAndMaximumSourceSubscription() throws Exception {
    assertEquals(0, SilenceSourceBinding.preKeySubscription("120", 12));
    assertEquals(Integer.MAX_VALUE, SilenceSourceBinding.preKeySubscription("122147483647", 12));
  }
}
