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
package org.jimvixx.smsecure.jobs;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class SmsDeliveryStatusTest {
  @Test public void staleAttemptsReusedIdsAndInvalidPartsAreRejected() {
    SmsDeliveryTracker.State state = new SmsDeliveryTracker.State("new", 123, 2);
    org.junit.Assert.assertTrue(state.matches("new", 123, 0, 2));
    org.junit.Assert.assertFalse(state.matches("old", 123, 0, 2));
    org.junit.Assert.assertFalse(state.matches("new", 456, 0, 2));
    org.junit.Assert.assertFalse(state.matches("new", 123, 2, 2));
    org.junit.Assert.assertFalse(state.matches("new", 123, -1, 2));
    org.junit.Assert.assertFalse(state.matches("new", 123, 0, 1));
  }
  @Test public void multipartWaitsForEveryPartAndSurvivesRestart() {
    SmsDeliveryTracker.State state = new SmsDeliveryTracker.State("attempt", 123, 2);
    assertEquals(0x20, state.record(1, 0));
    state = SmsDeliveryTracker.State.decode(state.encode());
    assertEquals(0x20, state.record(1, 0));
    assertEquals(0, state.record(0, 0));
    assertEquals(0, state.record(0, 0x20));
  }

  @Test public void failedPartCannotBeOverwrittenBySuccessOrPending() {
    SmsDeliveryTracker.State state = new SmsDeliveryTracker.State("attempt", 123, 2);
    assertEquals(0x45, state.record(0, 0x45));
    assertEquals(0x45, state.record(1, 0));
    assertEquals(0x45, state.record(0, 0));
    assertEquals(0x45, state.record(0, 0x20));
  }

  @Test public void corruptStoredResultsAreRejected() {
    org.junit.Assert.assertNull(SmsDeliveryTracker.State.decode("attempt|123|-1"));
    org.junit.Assert.assertNull(SmsDeliveryTracker.State.decode("attempt|invalid|0"));
  }
  @Test public void networkInterworkingFailureIsNotDelivery() {
    assertEquals(0x45, SmsSentJob.classifyGsmDeliveryStatus(69));
  }

  @Test public void onlyConfirmedReceptionIsComplete() {
    assertEquals(0, SmsSentJob.classifyGsmDeliveryStatus(0));
    // Forwarded without confirmation and replaced are not confirmed reception.
    assertEquals(0x20, SmsSentJob.classifyGsmDeliveryStatus(1));
    assertEquals(0x20, SmsSentJob.classifyGsmDeliveryStatus(2));
    assertEquals(0x20, SmsSentJob.classifyGsmDeliveryStatus(0x20));
    assertEquals(0x20, SmsSentJob.classifyGsmDeliveryStatus(0x3f));
  }

  @Test public void terminalFailureRangesArePreserved() {
    assertEquals(0x40, SmsSentJob.classifyGsmDeliveryStatus(0x40));
    assertEquals(0x60, SmsSentJob.classifyGsmDeliveryStatus(0x60));
    assertEquals(0x7f, SmsSentJob.classifyGsmDeliveryStatus(0x7f));
  }

  @Test public void legacyOrInvalidStatusCannotConfirmDelivery() {
    assertEquals(0x20, SmsSentJob.classifyGsmDeliveryStatus(-1));
    assertEquals(0x20, SmsSentJob.classifyGsmDeliveryStatus(128));
  }
}
