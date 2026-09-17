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

/** Metadata only. Presence of crypto files does not establish decryptability. */
public final class SilenceBackupInfo {
  private final int databaseVersion;
  private final long smsCount;
  private final long mmsCount;
  private final boolean passphraseDisabled;
  private final int cryptoFileCount;
  public SilenceBackupInfo(int databaseVersion, long smsCount, long mmsCount,
                           boolean passphraseDisabled, int cryptoFileCount) {
    if (databaseVersion < 0 || smsCount < 0 || mmsCount < 0 || cryptoFileCount < 0)
      throw new IllegalArgumentException("Negative metadata");
    this.databaseVersion = databaseVersion;
    this.smsCount = smsCount;
    this.mmsCount = mmsCount;
    this.passphraseDisabled = passphraseDisabled;
    this.cryptoFileCount = cryptoFileCount;
  }
  public int getDatabaseVersion() { return databaseVersion; }
  public long getSmsCount() { return smsCount; }
  public long getMmsCount() { return mmsCount; }
  public boolean isPassphraseDisabled() { return passphraseDisabled; }
  public int getCryptoFileCount() { return cryptoFileCount; }
}
