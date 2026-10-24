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

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import androidx.annotation.NonNull;

import org.jimvixx.smsecure.database.AttachmentBackupRestorer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Read-only inventory of MMS attachment references found in a selected SilenceExport. */
public final class SilenceAttachmentRepairPreview {
  private static final String DB_ENTRY = "databases/messages.db";
  private static final Pattern LEGACY_PART_PATH = Pattern.compile(
          "^/data/(?:data/org\\.smssecure\\.smssecure|user(?:_de)?/[0-9]+/org\\.smssecure\\.smssecure)/app_parts/(part[A-Za-z0-9]+\\.mms)$");
  private static final List<String> MMS_IDENTITY_COLUMNS = Arrays.asList("_id", "thread_id", "date",
          "date_received", "msg_box", "m_id", "sub", "sub_cs", "body", "part_count", "ct_t", "ct_l");
  private static final List<String> PART_IDENTITY_COLUMNS = Arrays.asList("_id", "mid", "seq", "ct",
          "name", "cd", "fn", "cid", "cl", "data_size", "file_name", "unique_id", "digest",
          "encrypted", "pending_push");

  private SilenceAttachmentRepairPreview() {}

  /** Builds a validated private archive from the selected directory and inspects it without restore. */
  @NonNull public static Report inspect(@NonNull Context context, @NonNull Uri silenceExport,
                                        @NonNull SQLiteOpenHelper currentDatabase) throws IOException {
    File archive = SilenceBackupAdapter.createArchive(context, silenceExport);
    try {
      return inspectArchive(context, archive, currentDatabase);
    } finally {
      SilenceBackupAdapter.deleteArchive(archive);
    }
  }

  private static Report inspectArchive(Context context, File archive,
                                       SQLiteOpenHelper currentDatabase) throws IOException {
    File sourceDb = File.createTempFile("mms-repair-db-", ".sqlite", context.getCacheDir());
    try (ZipFile zip = new ZipFile(archive)) {
      ZipEntry databaseEntry = zip.getEntry(DB_ENTRY);
      if (databaseEntry == null || databaseEntry.isDirectory()) {
        throw new IOException("SilenceExport is missing its MMS database");
      }
      try (InputStream input = zip.getInputStream(databaseEntry);
           FileOutputStream output = new FileOutputStream(sourceDb)) {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        output.flush();
        output.getFD().sync();
      }

      SQLiteDatabase source = SQLiteDatabase.openDatabase(sourceDb.getAbsolutePath(), null,
              SQLiteDatabase.OPEN_READONLY);
      SQLiteDatabase current = null;
      try {
        String databaseName = currentDatabase.getDatabaseName();
        if (databaseName == null) throw new IOException("Current MMS database has no file name");
        File currentDbFile = context.getDatabasePath(databaseName);
        if (currentDbFile == null || !currentDbFile.isFile()) {
          throw new IOException("Current MMS database file is unavailable");
        }
        current = SQLiteDatabase.openDatabase(currentDbFile.getAbsolutePath(), null,
                SQLiteDatabase.OPEN_READONLY);
        requireIdentitySchema(source);
        requireIdentitySchema(current);
        return buildReport(context, zip, source, current);
      } finally {
        if (current != null) current.close();
        source.close();
      }
    } finally {
      if (!sourceDb.delete() && sourceDb.exists()) {
        throw new IOException("Cannot remove temporary repair inspection database");
      }
    }
  }

  private static Report buildReport(Context context, ZipFile zip, SQLiteDatabase source,
                                    SQLiteDatabase current) throws IOException {
    int total = 0;
    int accessible = 0;
    int missing = 0;
    int ambiguous = 0;
    int missingSource = 0;
    int unsafe = 0;
    int encryptionUnverified = 0;

    Map<Long, String> sourceMms = readMmsIdentities(source);
    Map<Long, String> currentMms = readMmsIdentities(current);
    Map<Long, String> sourceParts = readRows(source, "part", PART_IDENTITY_COLUMNS, "_id");
    Map<Long, String> currentPartSignatures = readRows(current, "part", PART_IDENTITY_COLUMNS, "_id");
    Map<Long, PartReference> currentParts = readCurrentParts(current);
    Map<Long, String> sourcePartMms = readLongColumn(source, "part", "mid");
    Map<Long, String> currentPartMms = readLongColumn(current, "part", "mid");
    File ownedRoot = AttachmentBackupRestorer.getOwnedPartsRoot(context).getCanonicalFile();

    for (Map.Entry<Long, PartReference> entry : currentParts.entrySet()) {
      long partId = entry.getKey();
      PartReference part = entry.getValue();
      String sourceMmsSignature = sourceMms.get(part.mmsId);
      String currentMmsSignature = currentMms.get(part.mmsId);
      boolean parentMatches = sourceMmsSignature != null && sourceMmsSignature.equals(currentMmsSignature);
      boolean partMatches = parentMatches && sourceParts.containsKey(partId) &&
              sourceParts.get(partId).equals(currentPartSignatures.get(partId)) &&
              sourcePartMms.containsKey(partId) && currentPartMms.containsKey(partId) &&
              sourcePartMms.get(partId).equals(currentPartMms.get(partId));

      for (boolean thumbnail : new boolean[]{false, true}) {
        String currentPath = thumbnail ? part.thumbnail : part.path;
        if (currentPath == null || currentPath.isEmpty()) continue;
        total++;
        if (isAccessibleOwnedFile(currentPath, ownedRoot)) {
          accessible++;
          continue;
        }
        missing++;
        if (!isMissingOwnedPath(currentPath, ownedRoot) && legacyMember(currentPath) == null) {
          unsafe++;
          continue;
        }
        if (!partMatches) {
          ambiguous++;
          continue;
        }
        SourceReference reference = readSourceReference(source, partId, thumbnail);
        String member = legacyMember(reference.path);
        if (member == null) {
          unsafe++;
          continue;
        }
        ZipEntry sourceFile = zip.getEntry("app_parts/" + member);
        if (sourceFile == null || sourceFile.isDirectory()) {
          missingSource++;
          continue;
        }
        if (!verifySourceEntry(zip, sourceFile)) {
          unsafe++;
          continue;
        }
        // Legacy attachment reads always use DecryptingPartInputStream. The part.encrypted
        // column does not establish whether these bytes are plaintext or ciphertext.
        encryptionUnverified++;
      }
    }
    return new Report(total, accessible, missing, ambiguous, missingSource, unsafe,
            encryptionUnverified);
  }

  private static boolean verifySourceEntry(ZipFile zip, ZipEntry entry) {
    long total = 0;
    try (InputStream input = zip.getInputStream(entry)) {
      byte[] buffer = new byte[32 * 1024];
      int count;
      while ((count = input.read(buffer)) != -1) {
        total += count;
        if (total > 512L * 1024L * 1024L) return false;
      }
      return entry.getSize() < 0 || entry.getSize() == total;
    } catch (IOException exception) {
      return false;
    }
  }

  private static boolean isAccessibleOwnedFile(String path, File root) {
    File file = new File(path);
    try {
      return file.isAbsolute() && file.getParentFile() != null &&
              file.getParentFile().getCanonicalFile().equals(root) &&
              OsConstants.S_ISREG(Os.lstat(file.getAbsolutePath()).st_mode);
    } catch (IOException | ErrnoException exception) {
      return false;
    }
  }

  private static boolean isMissingOwnedPath(String path, File root) {
    File file = new File(path);
    try {
      return file.isAbsolute() && file.getParentFile() != null &&
              file.getParentFile().getCanonicalFile().equals(root) &&
              Os.lstat(file.getAbsolutePath()) == null;
    } catch (ErrnoException exception) {
      return exception.errno == OsConstants.ENOENT;
    } catch (IOException exception) {
      return false;
    }
  }

  private static String legacyMember(String path) {
    if (path == null || path.contains("\\") || path.indexOf('\0') >= 0) return null;
    Matcher matcher = LEGACY_PART_PATH.matcher(path);
    return matcher.matches() ? matcher.group(1) : null;
  }

  private static void requireIdentitySchema(SQLiteDatabase db) throws IOException {
    for (String column : MMS_IDENTITY_COLUMNS) if (!hasColumn(db, "mms", column)) {
      throw new IOException("Silence MMS database lacks required identity metadata");
    }
    for (String column : PART_IDENTITY_COLUMNS) if (!hasColumn(db, "part", column)) {
      throw new IOException("Silence attachment database lacks required identity metadata");
    }
    for (String column : new String[]{"mms_id", "type", "address", "address_charset"}) {
      if (!hasColumn(db, "mms_addresses", column)) {
        throw new IOException("Silence MMS database lacks required address identity metadata");
      }
    }
    for (String table : new String[]{"mms", "part", "mms_addresses"}) {
      try (Cursor cursor = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
              new String[]{table})) {
        if (!cursor.moveToFirst()) throw new IOException("Silence backup database is incomplete");
      }
    }
  }

  private static Map<Long, String> readMmsIdentities(SQLiteDatabase db) throws IOException {
    Map<Long, String> messages = readRows(db, "mms", MMS_IDENTITY_COLUMNS, "_id");
    Map<Long, List<String>> addresses = new HashMap<>();
    try (Cursor cursor = db.query("mms_addresses", new String[]{"mms_id", "type", "address", "address_charset"},
            null, null, null, null, "mms_id ASC, type ASC, address ASC")) {
      while (cursor.moveToNext()) {
        long messageId = cursor.getLong(0);
        String value = cursor.getString(1) + "\u0000" + cursor.getString(2) + "\u0000" +
                (cursor.isNull(3) ? "<null>" : cursor.getString(3));
        addresses.computeIfAbsent(messageId, ignored -> new ArrayList<>()).add(value);
      }
    } catch (RuntimeException exception) {
      throw new IOException("Cannot inspect backup address identity metadata", exception);
    }
    for (Map.Entry<Long, String> message : messages.entrySet()) {
      List<String> values = addresses.get(message.getKey());
      message.setValue(message.getValue() + "\u0001" + (values == null ? "" : values.toString()));
    }
    return messages;
  }

  private static boolean hasColumn(SQLiteDatabase db, String table, String column) {
    try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
      while (cursor.moveToNext()) if (column.equals(cursor.getString(1))) return true;
    }
    return false;
  }

  private static Map<Long, String> readRows(SQLiteDatabase db, String table, List<String> columns,
                                            String idColumn) throws IOException {
    Map<Long, String> rows = new HashMap<>();
    String[] projection = columns.toArray(new String[0]);
    try (Cursor cursor = db.query(table, projection, null, null, null, null, idColumn + " ASC")) {
      while (cursor.moveToNext()) rows.put(cursor.getLong(cursor.getColumnIndexOrThrow(idColumn)),
              signature(cursor));
    } catch (RuntimeException exception) {
      throw new IOException("Cannot inspect backup identity metadata", exception);
    }
    return rows;
  }

  private static Map<Long, String> readLongColumn(SQLiteDatabase db, String table, String column) {
    Map<Long, String> result = new HashMap<>();
    try (Cursor cursor = db.query(table, new String[]{"_id", column}, null, null, null, null, null)) {
      while (cursor.moveToNext()) result.put(cursor.getLong(0), cursor.isNull(1) ? "<null>" : cursor.getString(1));
    }
    return result;
  }

  private static Map<Long, PartReference> readCurrentParts(SQLiteDatabase db) {
    Map<Long, PartReference> result = new HashMap<>();
    try (Cursor cursor = db.query("part", new String[]{"_id", "mid", "_data", "thumbnail"},
            null, null, null, null, "_id ASC")) {
      while (cursor.moveToNext()) result.put(cursor.getLong(0), new PartReference(cursor.getLong(1),
              cursor.isNull(2) ? null : cursor.getString(2), cursor.isNull(3) ? null : cursor.getString(3)));
    }
    return result;
  }

  private static SourceReference readSourceReference(SQLiteDatabase db, long partId, boolean thumbnail) {
    try (Cursor cursor = db.query("part", new String[]{thumbnail ? "thumbnail" : "_data"}, "_id = ?",
            new String[]{String.valueOf(partId)}, null, null, null)) {
      return cursor.moveToFirst() ? new SourceReference(cursor.isNull(0) ? null : cursor.getString(0)) :
              new SourceReference(null);
    }
  }

  private static String signature(Cursor cursor) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (int i = 0; i < cursor.getColumnCount(); i++) {
        digest.update(cursor.getColumnName(i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0);
        if (cursor.isNull(i)) digest.update((byte) 0xff);
        else if (cursor.getType(i) == Cursor.FIELD_TYPE_BLOB) digest.update(cursor.getBlob(i));
        else digest.update(cursor.getString(i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0xfe);
      }
      return android.util.Base64.encodeToString(digest.digest(), android.util.Base64.NO_WRAP);
    } catch (NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }

  private static final class PartReference {
    final long mmsId;
    final String path;
    final String thumbnail;
    PartReference(long mmsId, String path, String thumbnail) {
      this.mmsId = mmsId;
      this.path = path;
      this.thumbnail = thumbnail;
    }
  }

  private static final class SourceReference {
    final String path;
    SourceReference(String path) { this.path = path; }
  }

  public static final class Report {
    public final int totalReferences;
    public final int alreadyAccessible;
    public final int missing;
    public final int ambiguous;
    public final int missingSourceFiles;
    public final int invalidOrUnsafe;
    public final int encryptionUnverified;
    Report(int totalReferences, int alreadyAccessible, int missing, int ambiguous,
           int missingSourceFiles, int invalidOrUnsafe, int encryptionUnverified) {
      this.totalReferences = totalReferences;
      this.alreadyAccessible = alreadyAccessible;
      this.missing = missing;
      this.ambiguous = ambiguous;
      this.missingSourceFiles = missingSourceFiles;
      this.invalidOrUnsafe = invalidOrUnsafe;
      this.encryptionUnverified = encryptionUnverified;
    }
  }
}
