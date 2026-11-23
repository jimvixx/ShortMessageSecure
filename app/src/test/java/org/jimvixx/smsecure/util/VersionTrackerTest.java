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

package org.jimvixx.smsecure.util;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mockStatic;

import org.jimvixx.smsecure.logging.Log;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.io.IOException;

public class VersionTrackerTest {

  @Test
  public void retriesWhenTheFailedCommitDidNotPersistTheVersion() {
    TestVersionCodeStore store = new TestVersionCodeStore(false, true);

    try (MockedStatic<Log> ignored = mockStatic(Log.class)) {
      VersionTracker.updateLastSeenVersion(10401, store);
    }

    assertEquals(2, store.persistAttempts);
    assertEquals(10401, store.lastSeenVersion);
  }

  @Test
  public void doesNotRetryWhenTheVersionCanBeReadBackAfterAFailedCommit() {
    TestVersionCodeStore store = new TestVersionCodeStore(true, false);

    try (MockedStatic<Log> ignored = mockStatic(Log.class)) {
      VersionTracker.updateLastSeenVersion(10401, store);
    }

    assertEquals(1, store.persistAttempts);
    assertEquals(10401, store.lastSeenVersion);
  }

  @Test
  public void leavesTheVersionUnchangedWhenBothAttemptsFail() {
    TestVersionCodeStore store = new TestVersionCodeStore(false, false);

    try (MockedStatic<Log> ignored = mockStatic(Log.class)) {
      VersionTracker.updateLastSeenVersion(10401, store);
    }

    assertEquals(2, store.persistAttempts);
    assertEquals(0, store.lastSeenVersion);
  }

  private static final class TestVersionCodeStore implements VersionTracker.VersionCodeStore {
    private final boolean persistOnFirstFailure;
    private final boolean succeedsOnSecondAttempt;
    private int persistAttempts;
    private int lastSeenVersion;

    TestVersionCodeStore(boolean persistOnFirstFailure, boolean succeedsOnSecondAttempt) {
      this.persistOnFirstFailure = persistOnFirstFailure;
      this.succeedsOnSecondAttempt = succeedsOnSecondAttempt;
    }

    @Override
    public void persist(int versionCode) throws IOException {
      persistAttempts++;

      if (persistAttempts == 1 && persistOnFirstFailure) {
        lastSeenVersion = versionCode;
      }

      if (persistAttempts == 2 && succeedsOnSecondAttempt) {
        lastSeenVersion = versionCode;
        return;
      }

      throw new IOException("synthetic SharedPreferences commit failure");
    }

    @Override
    public int getLastSeenVersion() {
      return lastSeenVersion;
    }
  }
}
