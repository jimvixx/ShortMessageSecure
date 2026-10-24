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
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/** Explicit transport-aware trimming for callers that can safely account for visible MMS. */
public final class MessageTrimmingDatabase {
  private static final String TRANSPORT_SMS = "sms";
  private static final String TRANSPORT_MMS = "mms";
  private static final long MILLIS_THRESHOLD = 100000000000L;

  private final Context context;
  private final SQLiteOpenHelper databaseHelper;

  public MessageTrimmingDatabase(@NonNull Context context,
                                 @NonNull SQLiteOpenHelper databaseHelper) {
    this.context = context;
    this.databaseHelper = databaseHelper;
  }

  /** Keeps the newest {@code retentionLimit} messages across both transports atomically. */
  @NonNull public TrimResult trimThread(long threadId, int retentionLimit) {
    if (retentionLimit <= 0) return new TrimResult(Status.NO_OP, 0, 0);

    SQLiteDatabase database;
    try {
      database = databaseHelper.getWritableDatabase();
      database.beginTransaction();
    } catch (RuntimeException exception) {
      return new TrimResult(Status.FAILED, 0, 0);
    }

    List<Long> smsIds = new ArrayList<>();
    List<Long> mmsIds = new ArrayList<>();
    int deletedSms = 0;
    int deletedMms = 0;
    Status status = Status.FAILED;
    try {
      collectObsoleteIds(database, threadId, retentionLimit, smsIds, mmsIds);

      for (long smsId : smsIds) {
        int deleted = database.delete(SmsDatabase.TABLE_NAME,
                "thread_id = ? AND _id = ?",
                new String[]{String.valueOf(threadId), String.valueOf(smsId)});
        if (deleted != 1) throw new IllegalStateException("SMS trim changed unexpectedly");
        deletedSms++;
      }

      MmsDatabase.DeletionBatch mmsBatch = new MmsDatabase(context, databaseHelper)
              .deleteMessages(database, mmsIds);
      deletedMms = mmsBatch.deletedCount;
      if (deletedMms != mmsIds.size()) throw new IllegalStateException("MMS trim changed unexpectedly");

      if (deletedSms > 0 || deletedMms > 0) {
        new ThreadDatabase(context, databaseHelper).updateAfterMmsDeletion(database, threadId);
        status = Status.TRIMMED;
      } else {
        status = Status.NO_OP;
      }
      database.setTransactionSuccessful();
    } catch (RuntimeException exception) {
      status = Status.FAILED;
      deletedSms = 0;
      deletedMms = 0;
    } finally {
      if (database.inTransaction()) {
        try {
          database.endTransaction();
        } catch (RuntimeException exception) {
          status = Status.FAILED;
          deletedSms = 0;
          deletedMms = 0;
        }
      }
    }

    if (status == Status.TRIMMED) {
      ThreadDatabase threads = new ThreadDatabase(context, databaseHelper);
      if (deletedMms > 0) DatabaseFactory.scheduleAttachmentCleanupIfOwned(context, databaseHelper);
      threads.notifyMmsDeletionCommitted(threadId);
      return new TrimResult(status, deletedSms, deletedMms);
    }
    return new TrimResult(status, 0, 0);
  }

  private void collectObsoleteIds(SQLiteDatabase database, long threadId, int retentionLimit,
                                  List<Long> smsIds, List<Long> mmsIds) {
    String normalizedSmsDate = "CASE WHEN date < " + MILLIS_THRESHOLD + " THEN date * 1000 ELSE date END";
    String normalizedMmsDate = "CASE WHEN date_received < " + MILLIS_THRESHOLD +
            " THEN date_received * 1000 ELSE date_received END";
    String query = "SELECT transport, _id FROM (" +
            "SELECT '" + TRANSPORT_SMS + "' AS transport, _id, " + normalizedSmsDate + " AS sort_date " +
            "FROM sms WHERE thread_id = ? UNION ALL " +
            "SELECT '" + TRANSPORT_MMS + "' AS transport, _id, " + normalizedMmsDate + " AS sort_date " +
            "FROM mms WHERE thread_id = ?) " +
            "ORDER BY sort_date DESC, transport ASC, _id ASC";

    try (Cursor cursor = database.rawQuery(query,
            new String[]{String.valueOf(threadId), String.valueOf(threadId)})) {
      int retained = 0;
      while (cursor.moveToNext()) {
        String transport = cursor.getString(0);
        long id = cursor.getLong(1);
        if (retained++ < retentionLimit) continue;
        if (TRANSPORT_SMS.equals(transport)) smsIds.add(id);
        else if (TRANSPORT_MMS.equals(transport)) mmsIds.add(id);
      }
    }
  }

  public enum Status { TRIMMED, NO_OP, FAILED }

  public static final class TrimResult {
    @NonNull public final Status status;
    public final int deletedSmsCount;
    public final int deletedMmsCount;

    private TrimResult(@NonNull Status status, int deletedSmsCount, int deletedMmsCount) {
      this.status = status;
      this.deletedSmsCount = deletedSmsCount;
      this.deletedMmsCount = deletedMmsCount;
    }
  }
}
