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

/** Structural recognition only: the legacy format has no authenticated version manifest. */
public final class SilenceBackupDetector {
  static final String DEFAULT_PREFS = "shared_prefs/org.smssecure.smssecure_preferences.xml";
  static final String SECRET_PREFS = "shared_prefs/SecureSMS-Preferences.xml";

  public void validate(File root) throws IOException {
    for (String dir : new String[]{"files", "databases", "shared_prefs"})
      if (!new File(root, dir).isDirectory()) throw new IOException("Missing backup directory");
    for (String path : new String[]{"databases/messages.db", "databases/canonical_address.db",
        DEFAULT_PREFS, SECRET_PREFS}) {
      File file = new File(root, path);
      if (!file.isFile() || file.length() == 0) throw new IOException("Missing or empty required backup file");
    }
    if (new File(root, "shared_prefs/org.jimvixx.smsecure_preferences.xml").exists())
      throw new IOException("Mixed SMSecure and Silence backup");
    // Reject sidecars rather than silently analyze a potentially stale database snapshot.
    for (String name : new String[]{"messages.db", "canonical_address.db"})
      for (String suffix : new String[]{"-wal", "-journal"}) {
        File sidecar = new File(root, "databases/" + name + suffix);
        if (sidecar.exists() && sidecar.length() > 0) throw new IOException("Uncheckpointed database backup");
      }
  }
}
