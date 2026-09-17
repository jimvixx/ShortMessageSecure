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
import org.jimvixx.smsecure.database.DraftDatabase;
import org.jimvixx.smsecure.database.IdentityDatabase;
import org.jimvixx.smsecure.database.RecipientPreferenceDatabase;
import org.jimvixx.smsecure.database.SmsDatabase;
import org.jimvixx.smsecure.database.ThreadDatabase;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Uses current DDL as a read-only contract without constructing core database helpers. */
final class SilenceDatabaseContract {
  private static final String[] CURRENT_TABLES = {"sms", "thread", "identities", "drafts", "recipient_preferences"};

  static void validate(SQLiteDatabase actual, boolean legacy) throws IOException {
    try (SQLiteDatabase expected = SQLiteDatabase.create(null)) {
      expected.execSQL(SmsDatabase.CREATE_TABLE);
      expected.execSQL(ThreadDatabase.CREATE_TABLE);
      expected.execSQL(IdentityDatabase.CREATE_TABLE);
      expected.execSQL(DraftDatabase.CREATE_TABLE);
      expected.execSQL(RecipientPreferenceDatabase.CREATE_TABLE);
      for (String table : CURRENT_TABLES) {
        Map<String, String> columns = columns(actual, table);
        for (Map.Entry<String, String> entry : columns(expected, table).entrySet()) {
          String name = entry.getKey();
          if (legacy && ((table.equals("identities") && name.equals("verified"))
              || (table.equals("thread") && name.equals("pinned_order")))) continue;
          if (legacy && table.equals("identities") && name.equals("identity_key")) name = "key";
          if (!entry.getValue().equals(columns.get(name)))
            throw new IOException("Unsupported database column contract");
        }
        if (legacy && ((table.equals("identities") && (columns.containsKey("identity_key") || columns.containsKey("verified")))
            || (table.equals("thread") && columns.containsKey("pinned_order"))))
          throw new IOException("Mixed database schema versions");
        if (legacy && table.equals("identities")) {
          for (String name : columns.keySet())
            if (!java.util.Arrays.asList("_id", "recipient", "key", "name", "mac").contains(name))
              throw new IOException("Unrecognized identity data would be lost");
        }
      }
    }
  }

  static Map<String, String> columns(SQLiteDatabase db, String table) {
    Map<String, String> result = new LinkedHashMap<>();
    try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + quote(table) + ")", null)) {
      while (cursor.moveToNext()) {
        String name = cursor.getString(cursor.getColumnIndexOrThrow("name"));
        String type = cursor.getString(cursor.getColumnIndexOrThrow("type"));
        int primaryKey = cursor.getInt(cursor.getColumnIndexOrThrow("pk"));
        result.put(name, type.toUpperCase(Locale.ROOT) + ":" + primaryKey);
      }
    }
    return result;
  }

  static String quote(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }
}
