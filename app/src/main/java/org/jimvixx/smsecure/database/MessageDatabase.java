/*
 * Copyright (C) 2011 Whisper Systems
 * Copyright (C) 2025 Jimvixx
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
import android.database.sqlite.SQLiteQueryBuilder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.model.MessageRecord;
import org.whispersystems.libsignal.util.guava.Optional;

public class MessageDatabase extends Database {

  public static final String TRANSPORT = "transport_type";
  public static final String SMS_TRANSPORT = "sms";

  public MessageDatabase(Context context, SQLiteOpenHelper databaseHelper) {
    super(context, databaseHelper);
  }

  public Cursor getConversation(long threadId, long limit) {
    String order = MessageColumns.NORMALIZED_DATE_RECEIVED + " DESC";
    String selection = MessageColumns.THREAD_ID + " = " + threadId;

    Cursor cursor = queryMessages(selection, order, limit > 0 ? String.valueOf(limit) : null);
    setNotifyConverationListeners(cursor, threadId);

    return cursor;
  }

  public Cursor getConversation(long threadId) {
    return getConversation(threadId, 0);
  }

  public Cursor getConversationSnippet(long threadId) {
    String order = MessageColumns.NORMALIZED_DATE_RECEIVED + " DESC";
    String selection = MessageColumns.THREAD_ID + " = " + threadId;

    return queryMessages(selection, order, "1");
  }

  public Cursor getUnread() {
    String order = MessageColumns.NORMALIZED_DATE_RECEIVED + " ASC";
    String selection = MessageColumns.READ + " = 0 AND " + MessageColumns.NOTIFIED + " = 0";

    return queryMessages(selection, order, null);
  }

  public int getConversationCount(long threadId) {
    return DatabaseFactory.getSmsDatabase(context).getMessageCountForThread(threadId);
  }

  private Cursor queryMessages(String selection, String order, String limit) {
    SQLiteQueryBuilder queryBuilder = new SQLiteQueryBuilder();
    queryBuilder.setTables(SmsDatabase.TABLE_NAME);
    queryBuilder.setDistinct(true);

    String[] projection = {
            SmsDatabase.DATE_SENT + " AS " + MessageColumns.NORMALIZED_DATE_SENT,
            SmsDatabase.DATE_RECEIVED + " AS " + MessageColumns.NORMALIZED_DATE_RECEIVED,
            MessageColumns.ID,
            "'SMS::' || " + MessageColumns.ID + " || '::' || " + SmsDatabase.DATE_SENT
                    + " AS " + MessageColumns.UNIQUE_ROW_ID,
            SmsDatabase.BODY,
            MessageColumns.READ,
            MessageColumns.THREAD_ID,
            SmsDatabase.TYPE,
            SmsDatabase.ADDRESS,
            SmsDatabase.ADDRESS_DEVICE_ID,
            SmsDatabase.SUBJECT,
            SmsDatabase.STATUS,
            MessageColumns.DATE_DELIVERY_RECEIVED,
            MessageColumns.MISMATCHED_IDENTITIES,
            MessageColumns.SUBSCRIPTION_ID,
            MessageColumns.NOTIFIED,
            "'" + SMS_TRANSPORT + "' AS " + TRANSPORT
    };

    @SuppressWarnings("deprecation")
    String query = queryBuilder.buildQuery(
            projection,
            selection,
            null,
            null,
            null,
            order,
            limit
    );

    SQLiteDatabase db = databaseHelper.getReadableDatabase();
    return db.rawQuery(query, null);
  }

  public Reader readerFor(@NonNull Cursor cursor, @Nullable MasterSecret masterSecret) {
    return new Reader(cursor, masterSecret);
  }

  public Reader readerFor(@NonNull Cursor cursor) {
    return new Reader(cursor);
  }

  public class Reader {

    private final Cursor cursor;
    private final Optional<MasterSecret> masterSecret;
    private EncryptingSmsDatabase.Reader smsReader;

    public Reader(Cursor cursor, @Nullable MasterSecret masterSecret) {
      this.cursor = cursor;
      this.masterSecret = Optional.fromNullable(masterSecret);
    }

    public Reader(Cursor cursor) {
      this(cursor, null);
    }

    private EncryptingSmsDatabase.Reader getSmsReader() {
      if (smsReader == null) {
        if (masterSecret.isPresent()) {
          smsReader = DatabaseFactory.getEncryptingSmsDatabase(context).readerFor(masterSecret.get(), cursor);
        } else {
          smsReader = DatabaseFactory.getSmsDatabase(context).readerFor(cursor);
        }
      }

      return smsReader;
    }

    public MessageRecord getNext() {
      if (cursor == null || !cursor.moveToNext()) {
        return null;
      }

      return getCurrent();
    }

    public MessageRecord getCurrent() {
      String type = cursor.getString(cursor.getColumnIndexOrThrow(TRANSPORT));

      if (SMS_TRANSPORT.equals(type)) {
        return getSmsReader().getCurrent();
      }

      throw new AssertionError("Bad type: " + type);
    }

    public void close() {
      cursor.close();
    }
  }
}