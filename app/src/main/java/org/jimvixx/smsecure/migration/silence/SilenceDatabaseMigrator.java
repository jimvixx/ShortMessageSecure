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
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts only a second, disposable copy inside a staging snapshot. */
public final class SilenceDatabaseMigrator {
  static final int SOURCE_VERSION = 30;
  static final int TARGET_VERSION = 35;
  private static final String[] TABLES = {
      "sms", "mms", "part", "mms_addresses", "drafts", "recipient_preferences", "thread", "identities"
  };

  PreparedDatabase prepare(SilenceBackupStager.Snapshot snapshot) throws IOException {
    File directory = new File(snapshot.root(), "database-preview");
    if (!directory.mkdir()) throw new IOException("Cannot create database preview");
    File candidate = new File(directory, "messages.db");
    try {
      copy(new File(snapshot.root(), "databases/messages.db"), candidate);
      SilenceDatabaseMigrationInfo info;
      try (SQLiteDatabase db = SQLiteDatabase.openDatabase(candidate.getAbsolutePath(), null,
          SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS, corrupt -> { })) {
        if (db.getVersion() != SOURCE_VERSION) throw new IOException("Unsupported source database version");
        rejectExecutableSchema(db);
        SilenceDatabaseContract.validate(db, true);
        integrity(db);
        Map<String, List<String>> columns = new LinkedHashMap<>();
        Map<String, byte[]> before = new LinkedHashMap<>();
        for (String table : TABLES) {
          List<String> names = new ArrayList<>(SilenceDatabaseContract.columns(db, table).keySet());
          if (!names.contains("_id")) throw new IOException("Missing backup table identity");
          columns.put(table, names);
          before.put(table, fingerprint(db, table, names, false));
        }
        long smsCount = count(db, "sms");
        long identityCount = count(db, "identities");
        checkCancelled();
        db.beginTransaction();
        try {
          // Preserve legacy key material and MACs byte-for-byte; verification is a later phase.
          boolean hasName = columns.get("identities").contains("name");
          db.execSQL("ALTER TABLE identities RENAME TO silence_legacy_identities");
          db.execSQL("CREATE TABLE identities (_id INTEGER PRIMARY KEY, recipient INTEGER UNIQUE, "
              + "identity_key TEXT, name TEXT, mac TEXT, verified INTEGER DEFAULT 0)");
          db.execSQL("INSERT INTO identities (_id, recipient, identity_key, name, mac, verified) "
              + "SELECT _id, recipient, \"key\", " + (hasName ? "name" : "NULL")
              + ", mac, 0 FROM silence_legacy_identities");
          db.execSQL("DROP TABLE silence_legacy_identities");
          db.execSQL("ALTER TABLE thread ADD COLUMN pinned_order INTEGER DEFAULT 0");
          SilenceDatabaseContract.validate(db, false);
          for (String table : TABLES)
            if (!Arrays.equals(before.get(table), fingerprint(db, table, columns.get(table), true)))
              throw new IOException("Database conversion changed original data");
          checkCancelled();
          db.setVersion(TARGET_VERSION);
          integrity(db);
          db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        if (db.getVersion() != TARGET_VERSION) throw new IOException("Database conversion did not finish");
        info = new SilenceDatabaseMigrationInfo(SOURCE_VERSION, TARGET_VERSION, smsCount, identityCount);
      }
      return new PreparedDatabase(directory, candidate, info);
    } catch (IOException | RuntimeException e) {
      try { SilenceBackupStager.Snapshot.delete(directory); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
      if (e instanceof IOException) throw (IOException) e;
      throw new IOException("Database preview failed", e);
    }
  }

  private static void rejectExecutableSchema(SQLiteDatabase db) throws IOException {
    try (Cursor cursor = db.rawQuery("SELECT type, sql FROM sqlite_master", null)) {
      while (cursor.moveToNext()) {
        String type = cursor.getString(0);
        String sql = cursor.getString(1);
        if ("trigger".equals(type) || "view".equals(type)
            || (sql != null && sql.toUpperCase(java.util.Locale.ROOT).contains("VIRTUAL TABLE")))
          throw new IOException("Unsupported executable database schema");
      }
    }
  }

  private static void copy(File source, File destination) throws IOException {
    try (FileInputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(destination)) {
      byte[] buffer = new byte[32768];
      int length;
      while ((length = input.read(buffer)) != -1) {
        checkCancelled();
        output.write(buffer, 0, length);
      }
    }
  }

  private static byte[] fingerprint(SQLiteDatabase db, String table, List<String> columns,
                                    boolean migrated) throws IOException {
    List<String> projection = new ArrayList<>();
    for (String column : columns) {
      String name = migrated && table.equals("identities") && column.equals("key") ? "identity_key" : column;
      projection.add(SilenceDatabaseContract.quote(name));
    }
    MessageDigest digest;
    try { digest = MessageDigest.getInstance("SHA-256"); }
    catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    OutputStream sink = new OutputStream() {
      @Override public void write(int value) { }
      @Override public void write(byte[] value, int offset, int length) { }
    };
    try (DataOutputStream output = new DataOutputStream(new DigestOutputStream(sink, digest));
         Cursor cursor = db.rawQuery("SELECT " + android.text.TextUtils.join(",", projection)
             + " FROM " + SilenceDatabaseContract.quote(table) + " ORDER BY _id", null)) {
      while (cursor.moveToNext()) {
        checkCancelled();
        output.writeByte(1);
        for (int i = 0; i < columns.size(); i++) {
          int type = cursor.getType(i);
          output.writeByte(type);
          switch (type) {
            case Cursor.FIELD_TYPE_INTEGER: output.writeLong(cursor.getLong(i)); break;
            case Cursor.FIELD_TYPE_FLOAT: output.writeLong(Double.doubleToLongBits(cursor.getDouble(i))); break;
            case Cursor.FIELD_TYPE_STRING:
            case Cursor.FIELD_TYPE_BLOB:
              byte[] value = type == Cursor.FIELD_TYPE_BLOB ? cursor.getBlob(i)
                  : cursor.getString(i).getBytes(StandardCharsets.UTF_8);
              output.writeInt(value.length);
              output.write(value);
              break;
            default: break;
          }
        }
      }
      output.writeByte(0);
    }
    return digest.digest();
  }

  private static long count(SQLiteDatabase db, String table) {
    try (Cursor cursor = db.rawQuery("SELECT count(*) FROM " + SilenceDatabaseContract.quote(table), null)) {
      cursor.moveToFirst();
      return cursor.getLong(0);
    }
  }

  private static void integrity(SQLiteDatabase db) throws IOException {
    try (Cursor cursor = db.rawQuery("PRAGMA integrity_check(1)", null)) {
      if (!cursor.moveToFirst() || !"ok".equals(cursor.getString(0)))
        throw new IOException("Database integrity check failed");
    }
  }

  private static void checkCancelled() throws IOException {
    if (Thread.currentThread().isInterrupted()) throw new IOException("Database preview cancelled");
  }

  static final class PreparedDatabase implements AutoCloseable {
    private final File directory;
    private final File database;
    private final SilenceDatabaseMigrationInfo info;
    private PreparedDatabase(File directory, File database, SilenceDatabaseMigrationInfo info) {
      this.directory = directory;
      this.database = database;
      this.info = info;
    }
    File file() { return database; }
    SilenceDatabaseMigrationInfo info() { return info; }
    @Override public void close() throws IOException { SilenceBackupStager.Snapshot.delete(directory); }
  }
}
