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
import java.util.HashSet;
import java.util.Set;

/** Presence-only guard. Never opens records or invokes stores that create directories. */
final class SilenceTargetCryptoFiles {
  private SilenceTargetCryptoFiles() {}

  static Set<Integer> occupied(File filesRoot, Set<Integer> candidates) throws IOException {
    if (filesRoot == null || !filesRoot.isDirectory()) throw new IOException("Target storage unavailable");
    filesRoot = filesRoot.getCanonicalFile();
    Set<Integer> result = new HashSet<>();
    int count = 0;
    for (String name : new String[]{"sessions-v2", "prekeys", "signed_prekeys"}) {
      File directory = new File(filesRoot, name);
      if (!directory.getCanonicalFile().equals(directory.getAbsoluteFile())) throw new IOException("Unexpected target path");
      if (!directory.exists()) continue;
      File[] entries = directory.listFiles();
      if (entries == null || entries.length > 20000) throw new IOException("Target inventory unavailable");
      for (File entry : entries) {
        if (++count > 20000) throw new IOException("Target inventory too large");
        if (!entry.isFile() || !entry.getCanonicalFile().equals(entry.getAbsoluteFile()))
          throw new IOException("Unexpected target entry");
        String filename = entry.getName();
        if (filename.endsWith(".tmp")) filename = filename.substring(0, filename.length() - 4);
        String[] parts = filename.split("\\.", -1);
        // Legacy unscoped records, metadata and unknown names cannot establish an owner.
        if (parts.length != 2 || !parts[0].matches("0|[1-9][0-9]{0,18}")) {
          result.addAll(candidates);
          continue;
        }
        try {
          Long.parseLong(parts[0]);
          int slot = SilenceSourceBinding.subscription(parts[1]);
          if (candidates.contains(slot)) result.add(slot);
        } catch (IOException | NumberFormatException e) { result.addAll(candidates); }
      }
    }
    return result;
  }
}
