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

/** Aggregate source integrity/comparison results, never peer trust or key material. */
public final class SilenceRemoteIdentityInfo {
  public enum Status { AUTHENTICATED, REJECTED }
  private final Status status;
  private final int records, currentMatches, currentMissing, currentDifferent, archivedMatches, archivedUnmatched;
  SilenceRemoteIdentityInfo(Status status, int records, int currentMatches, int currentMissing,
                            int currentDifferent, int archivedMatches, int archivedUnmatched) {
    this.status = status; this.records = records; this.currentMatches = currentMatches;
    this.currentMissing = currentMissing; this.currentDifferent = currentDifferent;
    this.archivedMatches = archivedMatches; this.archivedUnmatched = archivedUnmatched;
  }
  public Status getStatus() { return status; }
  public int getRecordCount() { return records; }
  public int getCurrentMatches() { return currentMatches; }
  public int getCurrentMissing() { return currentMissing; }
  public int getCurrentDifferent() { return currentDifferent; }
  public int getArchivedMatches() { return archivedMatches; }
  public int getArchivedUnmatched() { return archivedUnmatched; }
}
