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
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Read-only access to the Silence-compatible MMS table. */
public final class MmsDatabase {
  private final SQLiteOpenHelper databaseHelper;

  public MmsDatabase(@NonNull Context context, @NonNull SQLiteOpenHelper databaseHelper) {
    this.databaseHelper = databaseHelper;
  }

  @Nullable public MmsRecord getMessage(long id) {
    try (Cursor c = databaseHelper.getReadableDatabase().query("mms", MmsRecord.PROJECTION,
            "_id = ?", new String[]{String.valueOf(id)}, null, null, null)) {
      return c.moveToFirst() ? MmsRecord.from(c) : null;
    }
  }

  @NonNull public List<MmsRecord> getMessagesForThread(long threadId) {
    List<MmsRecord> result = new ArrayList<>();
    try (Cursor c = databaseHelper.getReadableDatabase().query("mms", MmsRecord.PROJECTION,
            "thread_id = ?", new String[]{String.valueOf(threadId)}, null, null,
            "date_received ASC, _id ASC")) {
      while (c.moveToNext()) result.add(MmsRecord.from(c));
    }
    return result;
  }

  public int getMessageCountForThread(long threadId) {
    try (Cursor c = databaseHelper.getReadableDatabase().rawQuery(
            "SELECT COUNT(*) FROM mms WHERE thread_id = ?", new String[]{String.valueOf(threadId)})) {
      return c.moveToFirst() ? c.getInt(0) : 0;
    }
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
