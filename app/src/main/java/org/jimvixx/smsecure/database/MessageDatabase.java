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
import org.jimvixx.smsecure.database.model.DisplayRecord;
import org.jimvixx.smsecure.database.model.MmsMessageRecord;
import org.jimvixx.smsecure.database.model.MessageRecord;
import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.recipients.RecipientFactory;
import org.jimvixx.smsecure.recipients.Recipients;
import org.whispersystems.libsignal.util.guava.Optional;

public class MessageDatabase extends Database {

  public static final String TRANSPORT = "transport_type";
  public static final String SMS_TRANSPORT = "sms";
  public static final String MMS_TRANSPORT = "mms";
  private static final String MMS_MESSAGE_TYPE = "mms_message_type";
  private static final String MMS_PART_COUNT = "mms_part_count";
  private static final String MMS_CONTENT_LOCATION = "mms_content_location";
  private static final String MMS_MESSAGE_SIZE = "mms_message_size";
  private static final String MMS_EXPIRY = "mms_expiry";
  private static final String MMS_TRANSACTION_ID = "mms_transaction_id";

  public MessageDatabase(Context context, SQLiteOpenHelper databaseHelper) {
    super(context, databaseHelper);
  }

  public Cursor getConversation(long threadId, long limit) {
    String order = MessageColumns.NORMALIZED_DATE_RECEIVED + " DESC";
    String selection = MessageColumns.THREAD_ID + " = " + threadId;

    Cursor cursor = queryMessages(selection, order, limit > 0 ? String.valueOf(limit) : null, false);
    setNotifyConverationListeners(cursor, threadId);

    return cursor;
  }

  public Cursor getConversation(long threadId) {
    return getConversation(threadId, 0);
  }

  public Cursor getConversationSnippet(long threadId) {
    String order = MessageColumns.NORMALIZED_DATE_RECEIVED + " DESC";
    String selection = MessageColumns.THREAD_ID + " = " + threadId;

    return queryMessages(selection, order, "1", false);
  }

  public Cursor getUnread() {
    String order = MessageColumns.NORMALIZED_DATE_RECEIVED + " ASC";
    String selection = MessageColumns.READ + " = 0 AND " + MessageColumns.NOTIFIED + " = 0";

    return queryMessages(selection, order, null, false);
  }

  public int getConversationCount(long threadId) {
    return DatabaseFactory.getSmsDatabase(context).getMessageCountForThread(threadId);
  }

  public Cursor getUnifiedConversation(long threadId, long limit) {
    String order = MessageColumns.NORMALIZED_DATE_RECEIVED + " DESC";
    String selection = MessageColumns.THREAD_ID + " = " + threadId;
    return queryMessages(selection, order, limit > 0 ? String.valueOf(limit) : null, true);
  }

  public Cursor getUnifiedConversation(long threadId) {
    return getUnifiedConversation(threadId, 0);
  }

  public Cursor getUnifiedConversationSnippet(long threadId) {
    String order = MessageColumns.NORMALIZED_DATE_RECEIVED + " DESC";
    String selection = MessageColumns.THREAD_ID + " = " + threadId;
    return queryMessages(selection, order, "1", true);
  }

  public Cursor getUnifiedUnread() {
    String order = MessageColumns.NORMALIZED_DATE_RECEIVED + " ASC";
    String selection = MessageColumns.READ + " = 0 AND " + MessageColumns.NOTIFIED + " = 0";
    return queryMessages(selection, order, null, true);
  }

  public int getUnifiedConversationCount(long threadId) {
    try (Cursor cursor = databaseHelper.getReadableDatabase().rawQuery(
        "SELECT (SELECT COUNT(*) FROM sms WHERE thread_id = ?) + " +
            "(SELECT COUNT(*) FROM mms WHERE thread_id = ?)",
        new String[]{String.valueOf(threadId), String.valueOf(threadId)})) {
      return cursor.moveToFirst() ? cursor.getInt(0) : 0;
    }
  }

  private Cursor queryMessages(String selection, String order, String limit, boolean unified) {
    if (!unified) {
      SQLiteQueryBuilder queryBuilder = new SQLiteQueryBuilder();
      queryBuilder.setTables(SmsDatabase.TABLE_NAME);
      queryBuilder.setDistinct(true);
      String[] projection = {
          SmsDatabase.DATE_SENT + " AS " + MessageColumns.NORMALIZED_DATE_SENT,
          SmsDatabase.DATE_RECEIVED + " AS " + MessageColumns.NORMALIZED_DATE_RECEIVED,
          MessageColumns.ID,
          "'SMS::' || " + MessageColumns.ID + " || '::' || " + SmsDatabase.DATE_SENT +
              " AS " + MessageColumns.UNIQUE_ROW_ID,
          SmsDatabase.BODY, MessageColumns.READ, MessageColumns.THREAD_ID, SmsDatabase.TYPE,
          SmsDatabase.ADDRESS, SmsDatabase.ADDRESS_DEVICE_ID, SmsDatabase.SUBJECT,
          SmsDatabase.STATUS, MessageColumns.DATE_DELIVERY_RECEIVED,
          MessageColumns.MISMATCHED_IDENTITIES, MessageColumns.SUBSCRIPTION_ID,
          MessageColumns.NOTIFIED, "'" + SMS_TRANSPORT + "' AS " + TRANSPORT
      };
      @SuppressWarnings("deprecation")
      String query = queryBuilder.buildQuery(projection, selection, null, null, null, order, limit);
      return databaseHelper.getReadableDatabase().rawQuery(query, null);
    }

    String smsSelection = selection.replace("thread_id", "thread_id");
    String mmsSelection = selection.replace("thread_id", "thread_id");
    String sms = "SELECT date_sent AS date_sent, date AS date_received, _id, " +
        "'SMS::' || _id || '::' || date_sent AS unique_row_id, body, read, thread_id, " +
        "type, address, address_device_id, subject, status, date_delivery_received, " +
        "mismatched_identities, subscription_id, notified, '" + SMS_TRANSPORT + "' AS " + TRANSPORT +
        ", NULL AS " + MMS_MESSAGE_TYPE + ", NULL AS " + MMS_PART_COUNT +
        ", NULL AS " + MMS_CONTENT_LOCATION + ", NULL AS " + MMS_MESSAGE_SIZE +
        ", NULL AS " + MMS_EXPIRY + ", NULL AS " + MMS_TRANSACTION_ID +
        " FROM sms WHERE " + smsSelection;
    String mms = "SELECT date AS date_sent, date_received AS date_received, _id, " +
        "'MMS::' || _id || '::' || date AS unique_row_id, body, read, thread_id, " +
        "msg_box AS type, address, address_device_id, sub AS subject, st AS status, " +
        "date_delivery_received, mismatched_identities, subscription_id, notified, '" + MMS_TRANSPORT + "' AS " + TRANSPORT +
        ", m_type AS " + MMS_MESSAGE_TYPE + ", part_count AS " + MMS_PART_COUNT +
        ", ct_l AS " + MMS_CONTENT_LOCATION + ", m_size AS " + MMS_MESSAGE_SIZE +
        ", exp AS " + MMS_EXPIRY + ", tr_id AS " + MMS_TRANSACTION_ID +
        " FROM mms WHERE " + mmsSelection;
    String query = "SELECT * FROM (" + sms + " UNION ALL " + mms + ") ORDER BY " +
        order + ", " + TRANSPORT + " ASC, _id ASC" +
        (limit == null ? "" : " LIMIT " + limit);

    return databaseHelper.getReadableDatabase().rawQuery(query, null);
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

      if (MMS_TRANSPORT.equals(type)) {
        return getMmsRecord();
      }

      throw new AssertionError("Bad type: " + type);
    }

    private MmsMessageRecord getMmsRecord() {
      long id = cursor.getLong(cursor.getColumnIndexOrThrow(MessageColumns.ID));
      String address = cursor.getString(cursor.getColumnIndexOrThrow(MessageColumns.ADDRESS));
      Recipients recipients;
      if (address == null || address.length() == 0) {
        recipients = RecipientFactory.getRecipientsFor(context, Recipient.getUnknownRecipient(), true);
      } else {
        recipients = RecipientFactory.getRecipientsFromString(context, address, true);
        if (recipients.isEmpty()) {
          recipients = RecipientFactory.getRecipientsFor(context, Recipient.getUnknownRecipient(), true);
        }
      }

      long type = cursor.getLong(cursor.getColumnIndexOrThrow(SmsDatabase.TYPE));
      String body = cursor.getString(cursor.getColumnIndexOrThrow(MessageColumns.BODY));
      boolean plaintext = !MessageColumns.Types.isDecryptInProgressType(type) &&
          !MessageColumns.Types.isFailedDecryptType(type);
      boolean notification = cursor.getInt(cursor.getColumnIndexOrThrow(MMS_MESSAGE_TYPE)) == 130;

      return new MmsMessageRecord(context, id,
          new DisplayRecord.Body(body, plaintext), recipients, recipients.getPrimaryRecipient(),
          cursor.getInt(cursor.getColumnIndexOrThrow(MessageColumns.ADDRESS_DEVICE_ID)),
          cursor.getLong(cursor.getColumnIndexOrThrow(MessageColumns.NORMALIZED_DATE_SENT)),
          cursor.getLong(cursor.getColumnIndexOrThrow(MessageColumns.NORMALIZED_DATE_RECEIVED)),
          cursor.getLong(cursor.getColumnIndexOrThrow(MessageColumns.DATE_DELIVERY_RECEIVED)),
          cursor.getLong(cursor.getColumnIndexOrThrow(MessageColumns.THREAD_ID)), type,
          cursor.getInt(cursor.getColumnIndexOrThrow(SmsDatabase.STATUS)),
          cursor.getInt(cursor.getColumnIndexOrThrow(MMS_PART_COUNT)),
          cursor.getString(cursor.getColumnIndexOrThrow(MMS_CONTENT_LOCATION)),
          cursor.getLong(cursor.getColumnIndexOrThrow(MMS_MESSAGE_SIZE)),
          cursor.getLong(cursor.getColumnIndexOrThrow(MMS_EXPIRY)),
          cursor.getString(cursor.getColumnIndexOrThrow(MMS_TRANSACTION_ID)), notification,
          cursor.getInt(cursor.getColumnIndexOrThrow(MessageColumns.SUBSCRIPTION_ID)));
    }

    public void close() {
      cursor.close();
    }
  }
}
