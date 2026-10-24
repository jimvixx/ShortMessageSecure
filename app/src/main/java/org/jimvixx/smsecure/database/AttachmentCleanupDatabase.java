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
package org.jimvixx.smsecure.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Durable records describing attachment paths whose owning MMS rows were deleted. */
public final class AttachmentCleanupDatabase {
  static final String TABLE_NAME = "attachment_cleanup";
  private static final String ID = "_id";
  private static final String FILE_PATH = "file_path";
  private static final String FILE_TYPE = "file_type";
  private static final String SOURCE_MMS_ID = "source_mms_id";
  private static final String SOURCE_PART_ID = "source_part_id";
  private static final String ENCRYPTED = "encrypted";
  private static final String CREATED_AT = "created_at";
  private static final String STATE = "state";
  private static final String ATTEMPTS = "attempt_count";
  private static final String NEXT_ATTEMPT = "next_attempt_at";
  private static final String LAST_ERROR = "last_error";

  public static final int TYPE_DATA = 1;
  public static final int TYPE_THUMBNAIL = 2;
  public static final int STATE_PENDING = 0;
  public static final int STATE_RETRY = 1;
  public static final int STATE_BLOCKED = 2;
  public static final int ERROR_NONE = 0;
  public static final int ERROR_REFERENCED = 1;
  public static final int ERROR_UNSAFE_PATH = 2;
  public static final int ERROR_UNSUPPORTED_FILESYSTEM = 3;
  public static final int ERROR_IO = 4;
  public static final int ERROR_UNKNOWN_ENCRYPTION = 5;
  public static final int MAX_ATTEMPTS = 8;
  public static final int BATCH_LIMIT = 32;

  private final SQLiteOpenHelper databaseHelper;

  public AttachmentCleanupDatabase(@NonNull Context context,
                                   @NonNull SQLiteOpenHelper databaseHelper) {
    this.databaseHelper = databaseHelper;
  }

  @NonNull public List<CleanupEntry> getPendingEntries(int limit) {
    if (limit <= 0) return new ArrayList<>();
    List<CleanupEntry> entries = new ArrayList<>();
    try (Cursor cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME,
            new String[]{ID, FILE_PATH, FILE_TYPE, SOURCE_MMS_ID, SOURCE_PART_ID, ENCRYPTED,
                    CREATED_AT, STATE, ATTEMPTS, NEXT_ATTEMPT, LAST_ERROR}, null, null, null,
            null, CREATED_AT + " ASC, " + ID + " ASC", String.valueOf(limit))) {
      while (cursor.moveToNext()) entries.add(readEntry(cursor));
    }
    return entries;
  }

  @NonNull public List<CleanupEntry> getDueEntries(long now, int limit) {
    if (limit <= 0) return new ArrayList<>();
    List<CleanupEntry> entries = new ArrayList<>();
    try (Cursor cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME,
            new String[]{ID, FILE_PATH, FILE_TYPE, SOURCE_MMS_ID, SOURCE_PART_ID, ENCRYPTED,
                    CREATED_AT, STATE, ATTEMPTS, NEXT_ATTEMPT, LAST_ERROR},
            "(" + STATE + " = ? OR " + STATE + " = ?) AND " + NEXT_ATTEMPT + " <= ?",
            new String[]{String.valueOf(STATE_PENDING), String.valueOf(STATE_RETRY),
                    String.valueOf(now)}, null, null, CREATED_AT + " ASC, " + ID + " ASC",
            String.valueOf(limit))) {
      while (cursor.moveToNext()) entries.add(readEntry(cursor));
    }
    return entries;
  }

  @Nullable public CleanupEntry getNextDueEntry(long now) {
    List<CleanupEntry> due = getDueEntries(now, 1);
    return due.isEmpty() ? null : due.get(0);
  }

  public boolean hasDueEntries(long now) {
    return !getDueEntries(now, 1).isEmpty();
  }

  private CleanupEntry readEntry(Cursor cursor) {
    return new CleanupEntry(cursor.getLong(0), cursor.getString(1), cursor.getInt(2),
            cursor.getLong(3), cursor.getLong(4), cursor.isNull(5) ? null : cursor.getInt(5),
            cursor.getLong(6), cursor.getInt(7), cursor.getInt(8), cursor.getLong(9),
            cursor.getInt(10));
  }

  int enqueue(@NonNull SQLiteDatabase database, @Nullable String path, int type,
              long mmsId, long partId, @Nullable Integer encrypted, long createdAt) {
    if (path == null || path.isEmpty()) return 0;
    ContentValues values = new ContentValues(6);
    values.put(FILE_PATH, path);
    values.put(FILE_TYPE, type);
    values.put(SOURCE_MMS_ID, mmsId);
    values.put(SOURCE_PART_ID, partId);
    values.put(ENCRYPTED, encrypted);
    values.put(CREATED_AT, createdAt);
    database.insertOrThrow(TABLE_NAME, null, values);
    return 1;
  }

  public boolean isReferenced(SQLiteDatabase database, @NonNull String path) {
    try (Cursor cursor = database.rawQuery("SELECT 1 FROM part WHERE _data = ? OR thumbnail = ? " +
            "LIMIT 1", new String[]{path, path})) {
      return cursor.moveToFirst();
    }
  }

  public int markComplete(SQLiteDatabase database, long id) {
    return database.delete(TABLE_NAME, ID + " = ?", new String[]{String.valueOf(id)});
  }

  public void markBlocked(SQLiteDatabase database, long id, int error) {
    ContentValues values = new ContentValues(2);
    values.put(STATE, STATE_BLOCKED);
    values.put(LAST_ERROR, error);
    database.update(TABLE_NAME, values, ID + " = ?", new String[]{String.valueOf(id)});
  }

  public void markRetry(SQLiteDatabase database, CleanupEntry entry, int error, long now) {
    int attempts = entry.attemptCount + 1;
    ContentValues values = new ContentValues(4);
    values.put(ATTEMPTS, attempts);
    values.put(LAST_ERROR, error);
    if (attempts >= MAX_ATTEMPTS) {
      values.put(STATE, STATE_BLOCKED);
      values.put(NEXT_ATTEMPT, Long.MAX_VALUE);
    } else {
      values.put(STATE, STATE_RETRY);
      values.put(NEXT_ATTEMPT, now + Math.min(86400000L, 1000L << Math.min(attempts, 16)));
    }
    database.update(TABLE_NAME, values, ID + " = ?", new String[]{String.valueOf(entry.id)});
  }

  public int reactivateSharedEntries() {
    ContentValues values = new ContentValues(3);
    values.put(STATE, STATE_PENDING);
    values.put(NEXT_ATTEMPT, 0);
    values.put(LAST_ERROR, ERROR_NONE);
    return databaseHelper.getWritableDatabase().update(TABLE_NAME, values,
            STATE + " = ? AND " + LAST_ERROR + " = ?",
            new String[]{String.valueOf(STATE_BLOCKED), String.valueOf(ERROR_REFERENCED)});
  }

  @Nullable public CleanupEntry getEntry(long id) {
    try (Cursor cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME,
            new String[]{ID, FILE_PATH, FILE_TYPE, SOURCE_MMS_ID, SOURCE_PART_ID, ENCRYPTED,
                    CREATED_AT, STATE, ATTEMPTS, NEXT_ATTEMPT, LAST_ERROR},
            ID + " = ?", new String[]{String.valueOf(id)}, null, null, null)) {
      return cursor.moveToFirst() ? readEntry(cursor) : null;
    }
  }

  public int deleteEntry(long id) {
    return databaseHelper.getWritableDatabase().delete(TABLE_NAME, ID + " = ?",
            new String[]{String.valueOf(id)});
  }

  public static final class CleanupEntry {
    public final long id;
    public final String path;
    public final int type;
    public final long sourceMmsId;
    public final long sourcePartId;
    @Nullable public final Integer encrypted;
    public final long createdAt;
    public final int state;
    public final int attemptCount;
    public final long nextAttemptAt;
    public final int lastError;

    private CleanupEntry(long id, String path, int type, long sourceMmsId, long sourcePartId,
                         @Nullable Integer encrypted, long createdAt, int state, int attemptCount,
                         long nextAttemptAt, int lastError) {
      this.id = id;
      this.path = path;
      this.type = type;
      this.sourceMmsId = sourceMmsId;
      this.sourcePartId = sourcePartId;
      this.encrypted = encrypted;
      this.createdAt = createdAt;
      this.state = state;
      this.attemptCount = attemptCount;
      this.nextAttemptAt = nextAttemptAt;
      this.lastError = lastError;
    }
  }
}
