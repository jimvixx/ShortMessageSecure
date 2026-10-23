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

import java.io.File;
import java.lang.reflect.Constructor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class MmsReadStatusTest {
  private Context context;
  private File databaseFile;
  private SQLiteOpenHelper helper;

  @Before public void setUp() throws Exception {
    context = ApplicationProvider.getApplicationContext();
    databaseFile = new File(context.getCacheDir(), "mms-read-status-" + System.nanoTime() + ".db");
    helper = helper();
  }

  @After public void tearDown() {
    if (helper != null) helper.close();
    if (databaseFile != null) databaseFile.delete();
  }

  @Test public void mmsReadMarksOnlyMmsRowsAndPreservesMetadata() {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 0);
    insertSms(db, 7, 42, 1);
    insertMms(db, 7, 42, 0);
    db.execSQL("INSERT INTO mms_addresses (_id, mms_id, type, address, address_charset) " +
        "VALUES (71, 7, 137, '+15550001', 106)");
    db.execSQL("INSERT INTO part (_id, mid, seq, ct, _data, data_size, digest, encrypted) " +
        "VALUES (72, 7, 0, 'image/jpeg', '/private/image.jpg', 12, X'010203', 1)");

    assertEquals(1, new MessageDatabase(context, helper).getUnifiedUnreadCount(42));
    new ThreadDatabase(context, helper).setMmsRead(42);
    new ThreadDatabase(context, helper).setMmsRead(42);

    assertEquals(1, value(db, "SELECT read FROM sms WHERE _id = 7"));
    assertEquals(1, value(db, "SELECT read FROM mms WHERE _id = 7"));
    assertEquals(1, value(db, "SELECT COUNT(*) FROM mms WHERE _id = 7"));
    assertEquals("mms-body", text(db, "SELECT body FROM mms WHERE _id = 7"));
    assertEquals(0, new MessageDatabase(context, helper).getUnifiedUnreadCount(42));
    assertEquals(1, value(db, "SELECT read FROM thread WHERE _id = 42"));
    assertEquals(1, value(db, "SELECT archived FROM thread WHERE _id = 42"));
    assertEquals(9, value(db, "SELECT pinned_order FROM thread WHERE _id = 42"));
    assertEquals(1, value(db, "SELECT COUNT(*) FROM mms_addresses WHERE mms_id = 7"));
    assertEquals("+15550001", text(db, "SELECT address FROM mms_addresses WHERE _id = 71"));
    assertEquals(1, value(db, "SELECT COUNT(*) FROM part WHERE mid = 7"));
    assertEquals("/private/image.jpg", text(db, "SELECT _data FROM part WHERE _id = 72"));
    assertEquals(1, value(db, "SELECT encrypted FROM part WHERE _id = 72"));
    assertArrayEquals(new byte[]{1, 2, 3}, blob(db, "SELECT digest FROM part WHERE _id = 72"));
  }

  @Test public void existingSmsOnlyCallerDoesNotMarkHiddenMmsRead() {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 0);
    insertSms(db, 7, 42, 0);
    insertMms(db, 7, 42, 0);

    new ThreadDatabase(context, helper).setRead(42);

    assertEquals(1, value(db, "SELECT read FROM sms WHERE _id = 7"));
    assertEquals(0, value(db, "SELECT read FROM mms WHERE _id = 7"));
    assertEquals(1, new MessageDatabase(context, helper).getUnifiedUnreadCount(42));
    assertEquals(0, value(db, "SELECT read FROM thread WHERE _id = 42"));
  }

  @Test public void mmsOnlyReadMarksTheThreadRead() {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 0);
    insertMms(db, 7, 42, 0);

    new ThreadDatabase(context, helper).setMmsRead(42);

    assertEquals(1, value(db, "SELECT read FROM mms WHERE _id = 7"));
    assertEquals(0, new MessageDatabase(context, helper).getUnifiedUnreadCount(42));
    assertEquals(1, value(db, "SELECT read FROM thread WHERE _id = 42"));
  }

  @Test public void markingMmsReadLeavesAnUnreadSmsAndThreadUnread() {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 0);
    insertSms(db, 7, 42, 0);
    insertMms(db, 8, 42, 0);

    new ThreadDatabase(context, helper).setMmsRead(42);

    assertEquals(0, value(db, "SELECT read FROM sms WHERE _id = 7"));
    assertEquals(1, value(db, "SELECT read FROM mms WHERE _id = 8"));
    assertEquals(1, new MessageDatabase(context, helper).getUnifiedUnreadCount(42));
    assertEquals(0, value(db, "SELECT read FROM thread WHERE _id = 42"));
  }

  @Test public void optInUnifiedReadMarksBothTransportsWithCollidingIds() {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 0);
    insertSms(db, 7, 42, 0);
    insertMms(db, 7, 42, 0);

    new ThreadDatabase(context, helper).setReadForAllTransports(42);
    new ThreadDatabase(context, helper).setReadForAllTransports(42);

    assertEquals(1, value(db, "SELECT read FROM sms WHERE _id = 7"));
    assertEquals(1, value(db, "SELECT read FROM mms WHERE _id = 7"));
    assertEquals(0, new MessageDatabase(context, helper).getUnifiedUnreadCount(42));
    assertEquals(1, value(db, "SELECT read FROM thread WHERE _id = 42"));
  }

  @Test public void setAllThreadsReadLeavesHiddenMmsUnread() {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 41, 0);
    insertThread(db, 42, 0);
    insertSms(db, 1, 41, 0);
    insertMms(db, 2, 42, 0);

    new ThreadDatabase(context, helper).setAllThreadsRead();

    assertEquals(1, value(db, "SELECT read FROM sms WHERE _id = 1"));
    assertEquals(0, value(db, "SELECT read FROM mms WHERE _id = 2"));
    assertEquals(1, value(db, "SELECT read FROM thread WHERE _id = 41"));
    assertEquals(0, value(db, "SELECT read FROM thread WHERE _id = 42"));
  }

  @Test public void setAllMmsReadLeavesSmsUnread() {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 41, 0);
    insertThread(db, 42, 0);
    insertSms(db, 1, 41, 0);
    insertSms(db, 2, 42, 0);
    insertMms(db, 3, 41, 0);
    insertMms(db, 4, 42, 0);

    new ThreadDatabase(context, helper).setAllMmsRead();

    assertEquals(0, value(db, "SELECT read FROM sms WHERE _id = 1"));
    assertEquals(0, value(db, "SELECT read FROM sms WHERE _id = 2"));
    assertEquals(1, value(db, "SELECT read FROM mms WHERE _id = 3"));
    assertEquals(1, value(db, "SELECT read FROM mms WHERE _id = 4"));
    assertEquals(1, new MessageDatabase(context, helper).getUnifiedUnreadCount(41));
    assertEquals(0, value(db, "SELECT read FROM thread WHERE _id = 41"));
    assertEquals(0, value(db, "SELECT read FROM thread WHERE _id = 42"));
  }

  private void insertThread(SQLiteDatabase db, long id, int read) {
    db.execSQL("INSERT INTO thread (_id, date, message_count, recipient_ids, snippet, read, type, " +
        "snippet_type, archived, status, last_seen, pinned_order) VALUES (?, 1000, 0, '1', NULL, ?, 0, 0, 1, 0, 0, 9)",
        new Object[]{id, read});
  }

  private void insertSms(SQLiteDatabase db, long id, long threadId, int read) {
    db.execSQL("INSERT INTO sms (_id, thread_id, date, date_sent, read, status, type, body, address, address_device_id) " +
        "VALUES (?, ?, 1000, 1000, ?, 0, 20, 'sms-body', '+15550001', 1)",
        new Object[]{id, threadId, read});
  }

  private void insertMms(SQLiteDatabase db, long id, long threadId, int read) {
    db.execSQL("INSERT INTO mms (_id, thread_id, date, date_received, msg_box, read, m_type, body, " +
        "address, part_count, st, tr_id) VALUES (?, ?, 1000, 1000, 1, ?, 132, 'mms-body', '+15550001', 1, 0, 'tx-7')",
        new Object[]{id, threadId, read});
  }

  private int value(SQLiteDatabase db, String sql) {
    try (Cursor cursor = db.rawQuery(sql, null)) {
      return cursor.moveToFirst() ? cursor.getInt(0) : 0;
    }
  }

  private String text(SQLiteDatabase db, String sql) {
    try (Cursor cursor = db.rawQuery(sql, null)) {
      return cursor.moveToFirst() ? cursor.getString(0) : null;
    }
  }

  private byte[] blob(SQLiteDatabase db, String sql) {
    try (Cursor cursor = db.rawQuery(sql, null)) {
      return cursor.moveToFirst() ? cursor.getBlob(0) : null;
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
