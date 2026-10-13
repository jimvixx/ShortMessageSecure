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

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteOpenHelper;
import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.List;

/** Read-only attachment metadata; it never opens or rewrites attachment files. */
public final class AttachmentDatabase {
  private final SQLiteOpenHelper databaseHelper;
  public AttachmentDatabase(@NonNull Context context, @NonNull SQLiteOpenHelper databaseHelper) {
    this.databaseHelper = databaseHelper;
  }
  @NonNull public List<AttachmentRecord> getAttachmentsForMessage(long mmsId) {
    List<AttachmentRecord> result = new ArrayList<>();
    try (Cursor c = databaseHelper.getReadableDatabase().query("part",
            new String[]{"_id", "mid", "seq", "ct", "name", "cd", "cl", "_data", "data_size",
                    "thumbnail", "aspect_ratio", "unique_id", "digest", "encrypted", "pending_push"},
            "mid = ?", new String[]{String.valueOf(mmsId)}, null, null, "seq ASC, _id ASC")) {
      while (c.moveToNext()) result.add(new AttachmentRecord(c.getLong(0), c.getLong(1), c.getInt(2),
              c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7),
              c.getLong(8), c.getString(9), c.getFloat(10), c.getLong(11), c.getBlob(12),
              c.getInt(13), c.getInt(14)));
    }
    return result;
  }
  public static final class AttachmentRecord {
    public final long id, mmsId, dataSize, uniqueId; public final int sequence, encrypted, transferState;
    public final String contentType, name, disposition, location, path, thumbnailPath; public final float aspectRatio;
    public final byte[] digest;
    private AttachmentRecord(long id, long mmsId, int sequence, String contentType, String name,
                             String disposition, String location, String path, long dataSize,
                             String thumbnailPath, float aspectRatio, long uniqueId, byte[] digest,
                             int encrypted, int transferState) {
      this.id=id; this.mmsId=mmsId; this.sequence=sequence; this.contentType=contentType; this.name=name;
      this.disposition=disposition; this.location=location; this.path=path; this.dataSize=dataSize;
      this.thumbnailPath=thumbnailPath; this.aspectRatio=aspectRatio; this.uniqueId=uniqueId;
      this.digest=digest == null ? null : digest.clone(); this.encrypted=encrypted; this.transferState=transferState;
    }
  }
}
