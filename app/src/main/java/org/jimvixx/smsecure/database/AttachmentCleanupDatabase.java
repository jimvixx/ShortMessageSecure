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

  public static final int TYPE_DATA = 1;
  public static final int TYPE_THUMBNAIL = 2;

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
                    CREATED_AT}, null, null, null, null, CREATED_AT + " ASC, " + ID + " ASC",
            String.valueOf(limit))) {
      while (cursor.moveToNext()) {
        entries.add(new CleanupEntry(cursor.getLong(0), cursor.getString(1), cursor.getInt(2),
                cursor.getLong(3), cursor.getLong(4), cursor.isNull(5) ? null : cursor.getInt(5),
                cursor.getLong(6)));
      }
    }
    return entries;
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

    private CleanupEntry(long id, String path, int type, long sourceMmsId, long sourcePartId,
                         @Nullable Integer encrypted, long createdAt) {
      this.id = id;
      this.path = path;
      this.type = type;
      this.sourceMmsId = sourceMmsId;
      this.sourcePartId = sourcePartId;
      this.encrypted = encrypted;
      this.createdAt = createdAt;
    }
  }
}
