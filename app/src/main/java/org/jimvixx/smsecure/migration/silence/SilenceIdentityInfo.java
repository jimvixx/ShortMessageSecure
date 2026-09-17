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

/** Contains only a count, never identity key material or device subscription identifiers. */
public final class SilenceIdentityInfo {
  public enum Status { VERIFIED, ABSENT, REJECTED }
  private final Status status;
  private final int count;
  private SilenceIdentityInfo(Status status, int count) { this.status = status; this.count = count; }
  static SilenceIdentityInfo verified(int count) {
    return new SilenceIdentityInfo(count == 0 ? Status.ABSENT : Status.VERIFIED, count);
  }
  static SilenceIdentityInfo rejected() { return new SilenceIdentityInfo(Status.REJECTED, 0); }
  public Status getStatus() { return status; }
  public int getCount() { return count; }
}
