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

/** Access to the Silence-compatible MMS table, including explicit MMS-only read operations. */
public final class MmsDatabase {
  static final String TABLE_NAME = "mms";
  static final String THREAD_ID = "thread_id";
  static final String READ = "read";

  private final Context context;
  private final SQLiteOpenHelper databaseHelper;

  public MmsDatabase(@NonNull Context context, @NonNull SQLiteOpenHelper databaseHelper) {
    this.context = context;
    this.databaseHelper = databaseHelper;
  }

  @Nullable public MmsRecord getMessage(long id) {
    try (Cursor c = databaseHelper.getReadableDatabase().query(TABLE_NAME, MmsRecord.PROJECTION,
            "_id = ?", new String[]{String.valueOf(id)}, null, null, null)) {
      return c.moveToFirst() ? MmsRecord.from(c) : null;
    }
  }

  @NonNull public List<MmsRecord> getMessagesForThread(long threadId) {
    List<MmsRecord> result = new ArrayList<>();
    try (Cursor c = databaseHelper.getReadableDatabase().query(TABLE_NAME, MmsRecord.PROJECTION,
            THREAD_ID + " = ?", new String[]{String.valueOf(threadId)}, null, null,
            "date_received ASC, _id ASC")) {
      while (c.moveToNext()) result.add(MmsRecord.from(c));
    }
    return result;
  }

  public int getMessageCountForThread(long threadId) {
    try (Cursor c = databaseHelper.getReadableDatabase().rawQuery(
            "SELECT COUNT(*) FROM " + TABLE_NAME + " WHERE " + THREAD_ID + " = ?",
            new String[]{String.valueOf(threadId)})) {
      return c.moveToFirst() ? c.getInt(0) : 0;
    }
  }

  /** Deletes one MMS and records its attachment paths for later, separately authorized cleanup. */
  @NonNull public DeletionResult deleteMessage(long mmsId) {
    SQLiteDatabase database;
    try {
      database = databaseHelper.getWritableDatabase();
      database.beginTransaction();
    } catch (RuntimeException exception) {
      return DeletionResult.FAILED;
    }

    boolean transactionStarted = true;
    long threadId = -1;
    DeletionResult result = DeletionResult.FAILED;
    try {
      try (Cursor cursor = database.query(TABLE_NAME, new String[]{THREAD_ID}, "_id = ?",
              new String[]{String.valueOf(mmsId)}, null, null, null)) {
        if (!cursor.moveToFirst()) {
          result = DeletionResult.NOT_FOUND;
        } else {
          threadId = cursor.getLong(0);
        }
      }

      if (result != DeletionResult.NOT_FOUND) {
        AttachmentCleanupDatabase cleanupDatabase =
            new AttachmentCleanupDatabase(context, databaseHelper);
        long createdAt = System.currentTimeMillis();
        int partCount = 0;
        try (Cursor cursor = database.query("part",
                new String[]{"_id", "_data", "thumbnail", "encrypted"}, "mid = ?",
                new String[]{String.valueOf(mmsId)}, null, null, null)) {
          while (cursor.moveToNext()) {
            long partId = cursor.getLong(0);
            Integer encrypted = cursor.isNull(3) ? null : cursor.getInt(3);
            cleanupDatabase.enqueue(database, cursor.getString(1),
                    AttachmentCleanupDatabase.TYPE_DATA, mmsId, partId, encrypted, createdAt);
            cleanupDatabase.enqueue(database, cursor.getString(2),
                    AttachmentCleanupDatabase.TYPE_THUMBNAIL, mmsId, partId, encrypted, createdAt);
            partCount++;
          }
        }

        if (database.delete("part", "mid = ?", new String[]{String.valueOf(mmsId)}) != partCount) {
          throw new IllegalStateException("MMS attachment metadata changed during deletion");
        }
        database.delete("mms_addresses", "mms_id = ?", new String[]{String.valueOf(mmsId)});
        if (database.delete(TABLE_NAME, "_id = ?", new String[]{String.valueOf(mmsId)}) != 1) {
          throw new IllegalStateException("MMS row changed during deletion");
        }

        new ThreadDatabase(context, databaseHelper).updateAfterMmsDeletion(database, threadId);
        database.setTransactionSuccessful();
        result = DeletionResult.DELETED;
      }
    } catch (RuntimeException exception) {
      result = DeletionResult.FAILED;
    } finally {
      if (transactionStarted && database.inTransaction()) {
        try {
          database.endTransaction();
        } catch (RuntimeException exception) {
          result = DeletionResult.FAILED;
        }
      }
    }

    if (result == DeletionResult.DELETED) {
      DatabaseFactory.scheduleAttachmentCleanupIfOwned(context, databaseHelper);
      new ThreadDatabase(context, databaseHelper).notifyMmsDeletionCommitted(threadId);
    }
    return result;
  }

  public enum DeletionResult {
    DELETED,
    NOT_FOUND,
    FAILED
  }

  /**
   * Marks only MMS rows in a thread as read. Thread metadata is maintained by
   * {@link ThreadDatabase#setMmsRead(long)}.
   */
  public int setMessagesRead(long threadId) {
    SQLiteDatabase database = databaseHelper.getWritableDatabase();
    database.beginTransaction();
    try {
      int updated = setMessagesRead(database, threadId);
      database.setTransactionSuccessful();
      return updated;
    } finally {
      database.endTransaction();
    }
  }

  /** Marks every MMS row as read. Thread metadata is maintained by ThreadDatabase. */
  public int setAllMessagesRead() {
    SQLiteDatabase database = databaseHelper.getWritableDatabase();
    database.beginTransaction();
    try {
      int updated = setAllMessagesRead(database);
      database.setTransactionSuccessful();
      return updated;
    } finally {
      database.endTransaction();
    }
  }

  int setMessagesRead(SQLiteDatabase database, long threadId) {
    ContentValues values = new ContentValues(1);
    values.put(READ, 1);
    return database.update(TABLE_NAME, values,
            THREAD_ID + " = ? AND " + READ + " = 0",
            new String[]{String.valueOf(threadId)});
  }

  int setAllMessagesRead(SQLiteDatabase database) {
    ContentValues values = new ContentValues(1);
    values.put(READ, 1);
    return database.update(TABLE_NAME, values, READ + " = 0", null);
  }

  public static final class MmsRecord {
    private static final String[] PROJECTION = {"_id", "thread_id", "date", "date_received",
            "msg_box", "read", "m_id", "sub", "body", "part_count", "ct_t", "ct_l",
            "address", "address_device_id", "exp", "m_cls", "m_type", "v", "m_size", "pri",
            "rr", "rpt_a", "resp_st", "st", "tr_id", "retr_st", "retr_txt", "retr_txt_cs",
            "read_status", "ct_cls", "resp_txt", "d_tm", "date_delivery_received",
            "mismatched_identities", "network_failures", "d_rpt", "subscription_id", "notified"};
    public final long id, threadId, date, dateReceived, expiry, messageSize, deliveryTime, dateDeliveryReceived;
    public final int messageBox, read, partCount, addressDeviceId, messageType, version, priority,
            readReport, replyReport, responseStatus, status, retrieveStatus, retrieveTextCharset,
            readStatus, contentClass, deliveryReport, subscriptionId, notified;
    public final String messageId, subject, body, contentType, contentLocation, address, messageClass,
            transactionId, retrieveText, responseText, mismatchedIdentities, networkFailures;

    private MmsRecord(Cursor c) {
      id=c.getLong(0); threadId=c.getLong(1); date=c.getLong(2); dateReceived=c.getLong(3);
      messageBox=c.getInt(4); read=c.getInt(5); messageId=c.getString(6); subject=c.getString(7);
      body=c.getString(8); partCount=c.getInt(9); contentType=c.getString(10); contentLocation=c.getString(11);
      address=c.getString(12); addressDeviceId=c.getInt(13); expiry=c.getLong(14); messageClass=c.getString(15);
      messageType=c.getInt(16); version=c.getInt(17); messageSize=c.getLong(18); priority=c.getInt(19);
      readReport=c.getInt(20); replyReport=c.getInt(21); responseStatus=c.getInt(22); status=c.getInt(23);
      transactionId=c.getString(24); retrieveStatus=c.getInt(25); retrieveText=c.getString(26);
      retrieveTextCharset=c.getInt(27); readStatus=c.getInt(28); contentClass=c.getInt(29);
      responseText=c.getString(30); deliveryTime=c.getLong(31); dateDeliveryReceived=c.getLong(32);
      mismatchedIdentities=c.getString(33); networkFailures=c.getString(34); deliveryReport=c.getInt(35);
      subscriptionId=c.getInt(36); notified=c.getInt(37);
    }
    private static MmsRecord from(Cursor c) { return new MmsRecord(c); }
  }
}
