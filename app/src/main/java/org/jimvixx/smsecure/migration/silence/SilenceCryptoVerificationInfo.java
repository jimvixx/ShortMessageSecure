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

/** Reports scoped SMS, file, and identity checks, never full import readiness. */
public final class SilenceCryptoVerificationInfo {
  public enum Status { PASSWORD_REQUIRED, VERIFIED, REJECTED }
  private final Status status;
  private final SilenceCryptoFileInfo files;
  private final SilenceIdentityInfo identities;
  private final long verifiedSmsCount;
  private final long uncheckedSmsCount;

  private SilenceCryptoVerificationInfo(Status status, long verifiedSmsCount, long uncheckedSmsCount) {
    this(status, verifiedSmsCount, uncheckedSmsCount, null, null);
  }
  private SilenceCryptoVerificationInfo(Status status, long verifiedSmsCount, long uncheckedSmsCount, SilenceCryptoFileInfo files, SilenceIdentityInfo identities) {
    this.identities = identities;
    this.files = files;
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
  SilenceCryptoVerificationInfo withFiles(SilenceCryptoFileInfo files) {
    return new SilenceCryptoVerificationInfo(status, verifiedSmsCount, uncheckedSmsCount, files, identities);
  }
  SilenceCryptoVerificationInfo withIdentities(SilenceIdentityInfo identities) {
    return new SilenceCryptoVerificationInfo(status, verifiedSmsCount, uncheckedSmsCount, files, identities);
  }
  public SilenceIdentityInfo getIdentities() { return identities; }
  public SilenceCryptoFileInfo getFiles() { return files; }
  public Status getStatus() { return status; }
  public long getVerifiedSmsCount() { return verifiedSmsCount; }
  public long getUncheckedSmsCount() { return uncheckedSmsCount; }
}
