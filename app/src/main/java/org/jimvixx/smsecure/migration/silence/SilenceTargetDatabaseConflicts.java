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

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Reads target binding references through read-only SQLite, never the live database helper. */
final class SilenceTargetDatabaseConflicts {
  private SilenceTargetDatabaseConflicts() {}

  static Set<Integer> occupied(File snapshot, Set<Integer> candidates) throws IOException {
    if (snapshot == null || !snapshot.isFile()) throw new IOException("Missing target snapshot");
    for (int candidate : candidates) if (candidate < 0) throw new IllegalArgumentException("Invalid target");
    // A plain file copy is not a consistent snapshot of a live WAL database.
    for (String suffix : new String[]{"-wal", "-shm", "-journal"})
      if (new File(snapshot.getPath() + suffix).exists()) throw new IOException("Unsettled target snapshot");
    return occupiedCurrent(snapshot, candidates);
  }

  /** Reads a logical binding snapshot, including committed WAL rows, without checkpointing. */
  static Set<Integer> occupiedCurrent(File database, Set<Integer> candidates) throws IOException {
    if (database == null || !database.isFile()) throw new IOException("Missing target database");
    for (int candidate : candidates) if (candidate < 0) throw new IllegalArgumentException("Invalid target");
    try (SQLiteDatabase db = SQLiteDatabase.openDatabase(database.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
      if (db.getVersion() != 35) throw new IOException("Unsupported target schema");
      for (String table : new String[]{"sms", "recipient_preferences", "thread", "identities"}) {
        try (Cursor definition = db.rawQuery("SELECT type FROM sqlite_master WHERE name = ?", new String[]{table})) {
          if (!definition.moveToFirst() || !"table".equals(definition.getString(0)))
            throw new IOException("Missing target table");
        }
      }
      Set<Integer> occupied = new HashSet<>();
      // One bounded statement reads scoped and global conflicts from the same SQLite snapshot.
      // typeof prevents DISTINCT from hiding malformed real values equal to an integer.
      String query = "SELECT DISTINCT subscription_id, typeof(subscription_id), 1 FROM sms "
          + "UNION ALL SELECT DISTINCT default_subscription_id, typeof(default_subscription_id), 0 "
          + "FROM recipient_preferences "
          + "UNION ALL SELECT NULL, 'null', 2 WHERE EXISTS (SELECT 1 FROM thread) "
          + "OR EXISTS (SELECT 1 FROM identities) LIMIT 2050";
      try (Cursor rows = db.rawQuery(query, null)) {
        int[] counts = new int[2];
        while (rows.moveToNext()) {
          int origin = rows.getInt(2);
          if (origin == 2) {
            occupied.addAll(candidates);
            continue;
          }
          if (++counts[origin] > 1024) throw new IOException("Target binding inventory too large");
          boolean messages = origin == 1;
          if (rows.isNull(0)) {
            if (messages) occupied.addAll(candidates);
          } else if (!"integer".equals(rows.getString(1))) {
            throw new IOException("Invalid target binding type");
          } else {
            long slot = rows.getLong(0);
            if (slot < -1 || slot > Integer.MAX_VALUE) throw new IOException("Invalid target binding");
            if (slot == -1) {
              if (messages) occupied.addAll(candidates);
            } else if (candidates.contains((int) slot)) occupied.add((int) slot);
          }
        }
      }
      return Collections.unmodifiableSet(occupied);
    } catch (android.database.SQLException e) {
      throw new IOException("Target database could not be checked");
    }
  }
}
