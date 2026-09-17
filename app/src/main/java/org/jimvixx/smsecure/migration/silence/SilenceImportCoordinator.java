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

import java.io.File;
import java.io.IOException;

/** Serializes analysis so cleanup cannot remove another active snapshot. */
public final class SilenceImportCoordinator {
  public static synchronized SilencePreflightResult inspect(SilenceBackupSource source,
                                                            File privateCache) throws IOException {
    return inspect(source, privateCache, null);
  }

  static synchronized SilencePreflightResult inspect(SilenceBackupSource source, File privateCache,
                                                     char[] password) throws IOException {
    File workspace = new File(privateCache, "silence-preflight");
    if (!workspace.isDirectory() && !workspace.mkdir()) throw new IOException("Cannot create workspace");
    // Remove leftovers from an interrupted process before starting the next analysis.
    File[] leftovers = workspace.listFiles();
    if (leftovers == null) throw new IOException("Cannot enumerate workspace");
    for (File leftover : leftovers) {
      if (!leftover.getName().startsWith("silence-preflight-")) throw new IOException("Unexpected workspace entry");
      SilenceBackupStager.Snapshot.delete(leftover);
    }
    try (SilenceBackupStager.Snapshot snapshot = new SilenceBackupStager().stage(source, workspace)) {
      SilencePreflightResult result = new SilencePreflightAnalyzer().analyze(snapshot);
      try (SilenceDatabaseMigrator.PreparedDatabase prepared = new SilenceDatabaseMigrator().prepare(snapshot)) {
        return result.withDatabaseMigration(prepared.info()).withCryptoVerification(
            new SilenceCryptoVerifier().verify(snapshot, result.getInfo().isPassphraseDisabled(), password));
      }
    }
  }
}
