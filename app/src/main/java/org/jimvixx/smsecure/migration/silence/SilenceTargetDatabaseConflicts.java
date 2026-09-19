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

/** Analyzes a caller-owned, quiescent target snapshot, never the live database helper. */
final class SilenceTargetDatabaseConflicts {
  private SilenceTargetDatabaseConflicts() {}

  static Set<Integer> occupied(File snapshot, Set<Integer> candidates) throws IOException {
    if (snapshot == null || !snapshot.isFile()) throw new IOException("Missing target snapshot");
    for (int candidate : candidates) if (candidate < 0) throw new IllegalArgumentException("Invalid target");
    // A plain file copy is not a consistent snapshot of a live WAL database.
    for (String suffix : new String[]{"-wal", "-shm", "-journal"})
      if (new File(snapshot.getPath() + suffix).exists()) throw new IOException("Unsettled target snapshot");
    try (SQLiteDatabase db = SQLiteDatabase.openDatabase(snapshot.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
      if (db.getVersion() != 35) throw new IOException("Unsupported target schema");
      Set<Integer> occupied = new HashSet<>();
      scan(db, "sms", "subscription_id", true, candidates, occupied);
      scan(db, "recipient_preferences", "default_subscription_id", false, candidates, occupied);
      return Collections.unmodifiableSet(occupied);
    } catch (android.database.SQLException e) {
      throw new IOException("Target snapshot could not be checked");
    }
  }

  private static void scan(SQLiteDatabase db, String table, String column, boolean messages,
                           Set<Integer> candidates, Set<Integer> occupied) throws IOException {
    try (Cursor definition = db.rawQuery("SELECT type FROM sqlite_master WHERE name = ?", new String[]{table})) {
      if (!definition.moveToFirst() || !"table".equals(definition.getString(0)))
        throw new IOException("Missing target table");
    }
    // LIMIT bounds the returned inventory; only subscription references are read.
    try (Cursor rows = db.rawQuery("SELECT DISTINCT " + column + " FROM " + table + " LIMIT 1025", null)) {
      int count = 0;
      while (rows.moveToNext()) {
        if (++count > 1024) throw new IOException("Target binding inventory too large");
        if (rows.isNull(0)) {
          if (messages) occupied.addAll(candidates);
        } else if (rows.getType(0) != Cursor.FIELD_TYPE_INTEGER) {
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
  }
}
