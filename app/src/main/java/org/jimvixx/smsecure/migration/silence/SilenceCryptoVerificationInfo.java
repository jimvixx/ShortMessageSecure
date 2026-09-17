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

/** Reports only master-secret and symmetric SMS-body checks, never full import readiness. */
public final class SilenceCryptoVerificationInfo {
  public enum Status { PASSWORD_REQUIRED, VERIFIED, REJECTED }
  private final Status status;
  private final long verifiedSmsCount;
  private final long uncheckedSmsCount;

  private SilenceCryptoVerificationInfo(Status status, long verifiedSmsCount, long uncheckedSmsCount) {
    this.status = status;
    this.verifiedSmsCount = verifiedSmsCount;
    this.uncheckedSmsCount = uncheckedSmsCount;
  }
  static SilenceCryptoVerificationInfo passwordRequired() {
    return new SilenceCryptoVerificationInfo(Status.PASSWORD_REQUIRED, 0, 0);
  }
  static SilenceCryptoVerificationInfo rejected() {
    return new SilenceCryptoVerificationInfo(Status.REJECTED, 0, 0);
  }
  static SilenceCryptoVerificationInfo verified(long checked, long unchecked) {
    return new SilenceCryptoVerificationInfo(Status.VERIFIED, checked, unchecked);
  }
  public Status getStatus() { return status; }
  public long getVerifiedSmsCount() { return verifiedSmsCount; }
  public long getUncheckedSmsCount() { return uncheckedSmsCount; }
}
