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

/** A verified schema conversion of a disposable copy, not approval to import. */
public final class SilenceDatabaseMigrationInfo {
  private final int sourceVersion;
  private final int targetVersion;
  private final long smsCount;
  private final long identityCount;

  SilenceDatabaseMigrationInfo(int sourceVersion, int targetVersion, long smsCount, long identityCount) {
    this.sourceVersion = sourceVersion;
    this.targetVersion = targetVersion;
    this.smsCount = smsCount;
    this.identityCount = identityCount;
  }

  public int getSourceVersion() { return sourceVersion; }
  public int getTargetVersion() { return targetVersion; }
  public long getSmsCount() { return smsCount; }
  public long getIdentityCount() { return identityCount; }
}
