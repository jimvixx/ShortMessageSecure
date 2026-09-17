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

/** Describes authenticated, parseable records; recipient/SIM mapping and protocol validity are separate. */
public final class SilenceCryptoFileInfo {
  public enum Status { READABLE, REJECTED }
  private final Status status;
  private final int sessions;
  private final int preKeys;
  private final int signedPreKeys;

  private SilenceCryptoFileInfo(Status status, int sessions, int preKeys, int signedPreKeys) {
    this.status = status;
    this.sessions = sessions;
    this.preKeys = preKeys;
    this.signedPreKeys = signedPreKeys;
  }
  static SilenceCryptoFileInfo readable(int sessions, int preKeys, int signedPreKeys) {
    return new SilenceCryptoFileInfo(Status.READABLE, sessions, preKeys, signedPreKeys);
  }
  static SilenceCryptoFileInfo rejected() { return new SilenceCryptoFileInfo(Status.REJECTED, 0, 0, 0); }
  public Status getStatus() { return status; }
  public int getSessionCount() { return sessions; }
  public int getPreKeyCount() { return preKeys; }
  public int getSignedPreKeyCount() { return signedPreKeys; }
}
