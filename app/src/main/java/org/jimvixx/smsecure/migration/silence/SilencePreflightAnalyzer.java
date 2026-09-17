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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Inspects only the disposable snapshot; never opens an SMSecure database helper. */
public final class SilencePreflightAnalyzer {
  public SilencePreflightResult analyze(SilenceBackupStager.Snapshot snapshot) throws IOException {
    File root = snapshot.root();
    new SilenceBackupDetector().validate(root);
    boolean disabled = SilencePreferencesReader.validate(root);
    try (SQLiteDatabase messages = open(new File(root, "databases/messages.db"));
         SQLiteDatabase addresses = open(new File(root, "databases/canonical_address.db"))) {
      if (messages.getVersion() != 30 || addresses.getVersion() != 1)
        throw new IOException("Unsupported database version");
      integrity(messages);
      integrity(addresses);
      columns(messages, "sms", "_id", "thread_id", "address", "body", "type", "date", "date_sent");
      columns(messages, "mms", "_id", "thread_id");
      columns(messages, "thread", "_id", "recipient_ids");
      columns(messages, "identities", "_id", "recipient", "key", "mac");
      columns(messages, "part", "_id", "mid");
      columns(messages, "mms_addresses", "_id", "mms_id");
      columns(messages, "drafts", "_id", "thread_id");
      columns(messages, "recipient_preferences", "_id", "recipient_ids");
      columns(addresses, "canonical_addresses", "_id", "address");
      if (scalar(messages, "SELECT count(*) FROM sms WHERE thread_id IS NULL OR thread_id NOT IN (SELECT _id FROM thread)") != 0)
        throw new IOException("Orphaned SMS threads");
      int cryptoFiles = 0;
      for (String dir : new String[]{"sessions-v2", "prekeys", "signed_prekeys"})
        cryptoFiles += countFiles(new File(root, "files/" + dir));
      return SilencePreflightResult.valid(new SilenceBackupInfo(messages.getVersion(),
          scalar(messages, "SELECT count(*) FROM sms"), scalar(messages, "SELECT count(*) FROM mms"),
          disabled, cryptoFiles));
    } catch (RuntimeException e) { throw new IOException("Invalid backup database", e); }
  }

  private static SQLiteDatabase open(File file) {
    // A corrupt backup must not invoke Android's default destructive corruption handler.
    return SQLiteDatabase.openDatabase(file.getAbsolutePath(), null,
        SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS, db -> { });
  }

  private static void integrity(SQLiteDatabase db) throws IOException {
    try (Cursor cursor = db.rawQuery("PRAGMA integrity_check(1)", null)) {
      if (!cursor.moveToFirst() || !"ok".equals(cursor.getString(0)))
        throw new IOException("Database integrity check failed");
    }
  }

  private static void columns(SQLiteDatabase db, String table, String... required) throws IOException {
    try (Cursor cursor = db.rawQuery("SELECT type, sql FROM sqlite_master WHERE name = ?", new String[]{table})) {
      if (!cursor.moveToFirst() || !"table".equals(cursor.getString(0)) || cursor.getString(1) == null
          || cursor.getString(1).toUpperCase(java.util.Locale.ROOT).contains("VIRTUAL TABLE"))
        throw new IOException("Missing ordinary backup table");
    }
    Set<String> actual = new HashSet<>();
    try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
      while (cursor.moveToNext()) actual.add(cursor.getString(cursor.getColumnIndexOrThrow("name")));
    }
    if (!actual.containsAll(Arrays.asList(required))) throw new IOException("Missing backup columns");
  }

  private static long scalar(SQLiteDatabase db, String sql) throws IOException {
    try (Cursor cursor = db.rawQuery(sql, null)) {
      if (!cursor.moveToFirst()) throw new IOException("Missing database result");
      return cursor.getLong(0);
    }
  }

  private static int countFiles(File directory) throws IOException {
    if (!directory.exists()) return 0;
    if (!directory.isDirectory()) throw new IOException("Invalid crypto directory");
    File[] files = directory.listFiles();
    if (files == null) throw new IOException("Unreadable crypto directory");
    for (File file : files)
      if (!file.isFile() || file.length() == 0) throw new IOException("Invalid crypto record");
    return files.length;
  }
}
