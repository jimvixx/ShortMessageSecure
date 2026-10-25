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

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.jimvixx.smsecure.database.model.MessageRecord;
import org.jimvixx.smsecure.database.model.MmsMessageRecord;

import java.io.File;
import java.lang.reflect.Constructor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class MmsThreadAggregationTest {
  private Context context;
  private File databaseFile;
  private SQLiteOpenHelper helper;

  @Before public void setUp() throws Exception {
    context = ApplicationProvider.getApplicationContext();
    databaseFile = new File(context.getCacheDir(), "mms-thread-aggregation-" + System.nanoTime() + ".db");
    helper = helper();
  }

  @After public void tearDown() {
    if (helper != null) helper.close();
    if (databaseFile != null) databaseFile.delete();
  }

  @Test public void unifiedMetadataReportsCountNewestStatusAndTransportAwareIdentity() throws Exception {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertSms(db, 7, 42, 3000, "sms-body", 3);
    insertMms(db, 7, 42, 5000, 6000, "mms-body", 8);

    MessageDatabase.UnifiedConversationMetadata metadata =
        new MessageDatabase(context, helper).getUnifiedConversationMetadata(42);

    assertEquals(2, metadata.getMessageCount());
    assertEquals(MessageDatabase.MMS_TRANSPORT, metadata.getTransport());
    assertEquals("MMS::7::5000", metadata.getUniqueMessageId());
    assertEquals("mms-body", metadata.getSnippet());
    assertEquals(6000, metadata.getDate());
    assertEquals(SmsDatabase.Status.STATUS_NONE, metadata.getStatus());
    assertEquals(7, metadata.getLatestMessage().getId());
    assertTrue(metadata.getLatestMessage().isMms());
    assertEquals(8, ((MmsMessageRecord) metadata.getLatestMessage()).getMmsDownloadStatus());
    assertFalse(metadata.getLatestMessage().isFailed());
    assertFalse(metadata.getLatestMessage().isDelivered());
  }

  @Test public void encryptedMmsStatesNeverExposeCiphertextAsPlaintext() throws Exception {
    SQLiteDatabase db = helper.getWritableDatabase();
    long[] flaggedTypes = {
        0x80000001L,
        0x10000001L,
        0x08000001L,
        0x40000001L,
        0x01000001L,
        0x20000001L,
        0x04000001L,
        0x02000001L
    };
    for (int i = 0; i < flaggedTypes.length; i++) {
      insertMmsWithType(db, 20 + i, 42, 1000 + i, 1000 + i,
          "ciphertext-" + i, 0, flaggedTypes[i]);
    }

    MessageDatabase messageDatabase = new MessageDatabase(context, helper);
    try (Cursor cursor = messageDatabase.getUnifiedConversation(42)) {
      MessageDatabase.Reader reader = messageDatabase.readerFor(cursor);
      MessageRecord record;
      int records = 0;
      while (cursor.moveToNext()) {
        record = reader.getCurrent();
        assertFalse(record.getBody().isPlaintext());
        assertNotEquals(record.getBody().getBody(), record.getDisplayBody().toString());
        assertFalse(record.getDisplayBody().toString().contains("ciphertext-"));
        records++;
      }
      assertEquals(flaggedTypes.length, records);
    }
  }

  @Test public void sameNumericIdsRemainDistinctAcrossTransports() throws Exception {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertSms(db, 7, 42, 5000, "sms-body", 3);
    insertMms(db, 7, 42, 5000, 5000, "mms-body", 8);

    try (Cursor cursor = new MessageDatabase(context, helper).getUnifiedConversation(42)) {
      assertTrue(cursor.moveToNext());
      String first = cursor.getString(cursor.getColumnIndexOrThrow(MessageColumns.UNIQUE_ROW_ID));
      assertTrue(cursor.moveToNext());
      String second = cursor.getString(cursor.getColumnIndexOrThrow(MessageColumns.UNIQUE_ROW_ID));
      assertFalse(first.equals(second));
    }
  }

  @Test public void updateKeepsMixedThreadAndPreservesArchivedPinnedAndAttachmentData() throws Exception {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 1, 9, "old sms", 20, 4, 12);
    insertSms(db, 7, 42, 3000, "old sms", 3);
    insertMms(db, 7, 42, 5000, 6000, "mms-body", 8);
    db.execSQL("INSERT INTO part (_id, mid, seq, ct, _data, data_size, digest, encrypted) " +
        "VALUES (71, 7, 0, 'image/jpeg', '/preserve/image.jpg', 12, X'0102', 1)");
    db.delete("sms", "_id = ?", new String[]{"7"});

    boolean deleted = new ThreadDatabase(context, helper).update(42, false);

    assertFalse(deleted);
    try (Cursor cursor = db.query("thread", null, "_id = ?", new String[]{"42"}, null, null, null)) {
      assertTrue(cursor.moveToFirst());
      assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.MESSAGE_COUNT)));
      assertEquals(6000, cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.DATE)));
      assertEquals(SmsDatabase.Status.STATUS_NONE,
          cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.STATUS)));
      assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.ARCHIVED)));
      assertEquals(9, cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.PINNED_ORDER)));
      assertEquals("mms-body", cursor.getString(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET)));
    }
    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM mms WHERE _id = 7"));
    assertEquals("/preserve/image.jpg", scalarString(db, "SELECT _data FROM part WHERE mid = 7"));
  }

  @Test public void mmsOnlyUpdateKeepsConversationListRowAndTextSnippet() throws Exception {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 0, 0, null, 0, 0, 0);
    insertMms(db, 7, 42, 5000, 6000, "mms-body", 8);

    assertFalse(new ThreadDatabase(context, helper).update(42, false));
    try (Cursor cursor = db.query("thread", new String[]{ThreadDatabase.MESSAGE_COUNT,
        ThreadDatabase.SNIPPET}, "_id = ?", new String[]{"42"}, null, null, null)) {
      assertTrue(cursor.moveToFirst());
      assertEquals(1, cursor.getInt(0));
      assertEquals("mms-body", cursor.getString(1));
    }
    try (Cursor cursor = new ThreadDatabase(context, helper).getConversationList()) {
      assertTrue(cursor.moveToFirst());
      assertEquals(42, cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.ID)));
      assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.MESSAGE_COUNT)));
    }
  }

  @Test public void updateUsesSmsSnippetWhenSmsIsNewerThanMms() throws Exception {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 0, 0, null, 0, 0, 0);
    insertSms(db, 9, 42, 7000, "new sms", 3);
    insertMms(db, 7, 42, 5000, 6000, "older mms", 8);

    assertFalse(new ThreadDatabase(context, helper).update(42, false));

    try (Cursor cursor = db.query("thread", null, "_id = ?", new String[]{"42"}, null, null, null)) {
      assertTrue(cursor.moveToFirst());
      assertEquals(2, cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.MESSAGE_COUNT)));
      assertEquals(7000, cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.DATE)));
      assertEquals(3, cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.STATUS)));
      assertEquals("new sms", cursor.getString(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET)));
    }
  }

  @Test public void zeroMessageUpdateRemovesEmptyThread() throws Exception {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 3, 4, "empty", 20, 0, 2);

    assertTrue(new ThreadDatabase(context, helper).update(42, false));
    assertEquals(0, scalar(db, "SELECT COUNT(*) FROM thread WHERE _id = 42"));
  }

  @Test public void smsOnlyUpdateRetainsExistingSnippetSemantics() throws Exception {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 0, 0, null, 0, 0, 0);
    insertSms(db, 7, 42, 3000, "sms-body", 3);

    assertFalse(new ThreadDatabase(context, helper).update(42, true));
    try (Cursor cursor = db.query("thread", null, "_id = ?", new String[]{"42"}, null, null, null)) {
      assertTrue(cursor.moveToFirst());
      assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.MESSAGE_COUNT)));
      assertEquals("sms-body", cursor.getString(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET)));
      assertEquals(3000, cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.DATE)));
      assertEquals(3, cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.STATUS)));
      assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.ARCHIVED)));
    }
  }

  private void insertThread(SQLiteDatabase db, long id, int archived, long pinnedOrder,
                            String snippet, long snippetType, int status, int count) {
    db.execSQL("INSERT INTO thread (_id, date, message_count, recipient_ids, snippet, read, type, " +
        "snippet_type, archived, status, last_seen, pinned_order) VALUES (?, 1000, ?, '1', ?, 1, 0, ?, ?, ?, 0, ?)",
        new Object[]{id, count, snippet, snippetType, archived, status, pinnedOrder});
  }

  private void insertSms(SQLiteDatabase db, long id, long threadId, long date,
                         String body, int status) {
    db.execSQL("INSERT INTO sms (_id, thread_id, date, date_sent, read, status, type, body, address, address_device_id) " +
        "VALUES (?, ?, ?, ?, 1, ?, 20, ?, '+15550001', 1)",
        new Object[]{id, threadId, date, date, status, body});
  }

  private void insertMms(SQLiteDatabase db, long id, long threadId, long date,
                         long dateReceived, String body, int status) {
    insertMmsWithType(db, id, threadId, date, dateReceived, body, status, 1);
  }

  private void insertMmsWithType(SQLiteDatabase db, long id, long threadId, long date,
                                 long dateReceived, String body, int status, long messageBox) {
    db.execSQL("INSERT INTO mms (_id, thread_id, date, date_received, msg_box, read, m_type, body, " +
        "address, part_count, st, tr_id) VALUES (?, ?, ?, ?, ?, 1, 132, ?, '+15550001', 1, ?, 'tx-7')",
        new Object[]{id, threadId, date, dateReceived, messageBox, body, status});
  }

  private int scalar(SQLiteDatabase db, String sql) {
    try (Cursor cursor = db.rawQuery(sql, null)) {
      return cursor.moveToFirst() ? cursor.getInt(0) : 0;
    }
  }

  private String scalarString(SQLiteDatabase db, String sql) {
    try (Cursor cursor = db.rawQuery(sql, null)) {
      return cursor.moveToFirst() ? cursor.getString(0) : null;
    }
  }

  private SQLiteOpenHelper helper() throws Exception {
    Class<?> helperClass = DatabaseFactory.class.getDeclaredClasses()[0];
    Constructor<?> constructor = helperClass.getDeclaredConstructor(Context.class, String.class,
        SQLiteDatabase.CursorFactory.class, int.class);
    constructor.setAccessible(true);
    return (SQLiteOpenHelper) constructor.newInstance(context, databaseFile.getPath(), null, 36);
  }
}
