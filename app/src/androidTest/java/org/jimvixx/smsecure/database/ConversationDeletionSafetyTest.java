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
import android.database.sqlite.SQLiteException;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class ConversationDeletionSafetyTest {
  private Context context;
  private File databaseFile;
  private SQLiteOpenHelper helper;

  @Before public void setUp() throws Exception {
    context = ApplicationProvider.getApplicationContext();
    databaseFile = new File(context.getCacheDir(), "conversation-delete-safety-" +
        System.nanoTime() + ".db");
    helper = helper();
  }

  @After public void tearDown() {
    if (helper != null) helper.close();
    if (databaseFile != null) databaseFile.delete();
  }

  @Test public void deletingSmsOnlyConversationRetainsExistingBehavior() {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 10, 1, 4);
    insertSms(db, 100, 10);
    insertDraft(db, 10);

    ThreadDatabase.ConversationDeletionResult result =
        new ThreadDatabase(context, helper).deleteConversation(10);

    assertEquals(ThreadDatabase.ConversationDeletionResult.DELETED, result);
    assertEquals(0, value(db, "SELECT COUNT(*) FROM sms WHERE thread_id = 10"));
    assertEquals(0, value(db, "SELECT COUNT(*) FROM drafts WHERE thread_id = 10"));
    assertEquals(0, value(db, "SELECT COUNT(*) FROM thread WHERE _id = 10"));
  }

  @Test public void deletingSmsOnlySelectedConversationsRetainsExistingBehavior() {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 10, 0, 0);
    insertThread(db, 11, 1, 7);
    insertSms(db, 100, 10);
    insertSms(db, 101, 11);
    insertDraft(db, 10);
    insertDraft(db, 11);

    Set<Long> selected = new HashSet<>(Arrays.asList(10L, 11L));
    ThreadDatabase.ConversationDeletionResult result =
        new ThreadDatabase(context, helper).deleteConversations(selected);

    assertEquals(ThreadDatabase.ConversationDeletionResult.DELETED, result);
    assertEquals(0, value(db, "SELECT COUNT(*) FROM sms WHERE thread_id IN (10, 11)"));
    assertEquals(0, value(db, "SELECT COUNT(*) FROM drafts WHERE thread_id IN (10, 11)"));
    assertEquals(0, value(db, "SELECT COUNT(*) FROM thread WHERE _id IN (10, 11)"));
  }

  @Test public void mixedConversationDeletionIsBlockedWithoutChangingRowsOrMetadata() {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 20, 1, 8);
    insertSms(db, 200, 20);
    insertMms(db, 200, 20);
    insertAddress(db, 201, 200);
    insertPart(db, 202, 200);
    insertDraft(db, 20);

    ThreadDatabase.ConversationDeletionResult result =
        new ThreadDatabase(context, helper).deleteConversation(20);

    assertEquals(ThreadDatabase.ConversationDeletionResult.BLOCKED_BY_MMS, result);
    assertPreservedThread(db, 20, 1, 8);
    assertEquals(1, value(db, "SELECT COUNT(*) FROM sms WHERE _id = 200 AND thread_id = 20"));
    assertEquals(1, value(db, "SELECT COUNT(*) FROM mms WHERE _id = 200 AND thread_id = 20"));
    assertEquals(1, value(db, "SELECT COUNT(*) FROM mms_addresses WHERE _id = 201 AND mms_id = 200"));
    assertEquals(1, value(db, "SELECT COUNT(*) FROM part WHERE _id = 202 AND mid = 200"));
    assertEquals(1, value(db, "SELECT COUNT(*) FROM drafts WHERE thread_id = 20"));
  }

  @Test public void mmsOnlyConversationDeletionIsBlocked() {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 30, 0, 0);
    insertMms(db, 300, 30);
    insertAddress(db, 301, 300);
    insertDraft(db, 30);

    ThreadDatabase.ConversationDeletionResult result =
        new ThreadDatabase(context, helper).deleteConversation(30);

    assertEquals(ThreadDatabase.ConversationDeletionResult.BLOCKED_BY_MMS, result);
    assertPreservedThread(db, 30, 0, 0);
    assertEquals(1, value(db, "SELECT COUNT(*) FROM mms WHERE _id = 300 AND thread_id = 30"));
    assertEquals(1, value(db, "SELECT COUNT(*) FROM mms_addresses WHERE _id = 301 AND mms_id = 300"));
    assertEquals(1, value(db, "SELECT COUNT(*) FROM drafts WHERE thread_id = 30"));
  }

  @Test public void selectedDeletionIsAllOrNothingWhenAnyConversationContainsMms() {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 40, 0, 0);
    insertThread(db, 41, 1, 5);
    insertSms(db, 400, 40);
    insertDraft(db, 40);
    insertThread(db, 42, 0, 0);
    insertSms(db, 420, 42);
    insertMms(db, 421, 42);
    insertAddress(db, 422, 421);
    insertPart(db, 423, 421);
    insertDraft(db, 42);

    Set<Long> selected = new HashSet<>(Arrays.asList(40L, 42L));
    ThreadDatabase.ConversationDeletionResult result =
        new ThreadDatabase(context, helper).deleteConversations(selected);

    assertEquals(ThreadDatabase.ConversationDeletionResult.BLOCKED_BY_MMS, result);
    assertEquals(1, value(db, "SELECT COUNT(*) FROM sms WHERE thread_id = 40"));
    assertEquals(1, value(db, "SELECT COUNT(*) FROM drafts WHERE thread_id = 40"));
    assertPreservedThread(db, 40, 0, 0);
    assertPreservedThread(db, 42, 0, 0);
    assertEquals(1, value(db, "SELECT COUNT(*) FROM sms WHERE _id = 420"));
    assertEquals(1, value(db, "SELECT COUNT(*) FROM mms WHERE _id = 421"));
    assertEquals(1, value(db, "SELECT COUNT(*) FROM mms_addresses WHERE _id = 422"));
    assertEquals(1, value(db, "SELECT COUNT(*) FROM part WHERE _id = 423"));
    assertEquals(1, value(db, "SELECT COUNT(*) FROM drafts WHERE thread_id = 42"));
  }

  @Test public void deleteAllIsBlockedWithoutChangingAnyConversationWhenMmsExists() {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 50, 1, 6);
    insertThread(db, 51, 0, 0);
    insertSms(db, 500, 50);
    insertDraft(db, 50);
    insertMms(db, 510, 51);
    insertAddress(db, 511, 510);
    insertPart(db, 512, 510);
    insertDraft(db, 51);

    ThreadDatabase.ConversationDeletionResult result =
        new ThreadDatabase(context, helper).deleteAllConversations();

    assertEquals(ThreadDatabase.ConversationDeletionResult.BLOCKED_BY_MMS, result);
    assertEquals(2, value(db, "SELECT COUNT(*) FROM thread"));
    assertEquals(1, value(db, "SELECT COUNT(*) FROM sms WHERE _id = 500"));
    assertEquals(1, value(db, "SELECT COUNT(*) FROM mms WHERE _id = 510"));
    assertEquals(1, value(db, "SELECT COUNT(*) FROM mms_addresses WHERE _id = 511"));
    assertEquals(1, value(db, "SELECT COUNT(*) FROM part WHERE _id = 512"));
    assertEquals(2, value(db, "SELECT COUNT(*) FROM drafts"));
    assertPreservedThread(db, 50, 1, 6);
    assertPreservedThread(db, 51, 0, 0);
  }

  @Test public void databaseFailureRollsBackEarlierDeletes() {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 60, 1, 3);
    insertSms(db, 600, 60);
    insertDraft(db, 60);
    db.execSQL("CREATE TRIGGER fail_draft_delete BEFORE DELETE ON drafts " +
        "BEGIN SELECT RAISE(ABORT, 'simulated database failure'); END");

    try {
      new ThreadDatabase(context, helper).deleteConversation(60);
      fail("Expected the simulated database failure");
    } catch (SQLiteException expected) {
      // The transaction must restore the SMS row deleted before the trigger fired.
    }

    assertEquals(1, value(db, "SELECT COUNT(*) FROM sms WHERE _id = 600"));
    assertEquals(1, value(db, "SELECT COUNT(*) FROM drafts WHERE thread_id = 60"));
    assertPreservedThread(db, 60, 1, 3);
  }

  private void insertThread(SQLiteDatabase db, long id, int archived, int pinnedOrder) {
    db.execSQL("INSERT INTO thread (_id, date, message_count, recipient_ids, snippet, read, type, " +
        "snippet_type, archived, status, last_seen, pinned_order) " +
        "VALUES (?, 1000, 1, '1', 'sms-body', 1, 0, 0, ?, 0, 0, ?)",
        new Object[]{id, archived, pinnedOrder});
  }

  private void insertSms(SQLiteDatabase db, long id, long threadId) {
    db.execSQL("INSERT INTO sms (_id, thread_id, date, date_sent, read, status, type, body, address, address_device_id) " +
        "VALUES (?, ?, 1000, 1000, 1, 0, 20, 'sms-body', '+15550001', 1)",
        new Object[]{id, threadId});
  }

  private void insertMms(SQLiteDatabase db, long id, long threadId) {
    db.execSQL("INSERT INTO mms (_id, thread_id, date, date_received, msg_box, read, m_type, body, " +
        "address, part_count, st, tr_id) VALUES (?, ?, 1000, 1000, 1, 0, 132, 'mms-body', " +
        "'+15550001', 1, 0, 'tx')", new Object[]{id, threadId});
  }

  private void insertAddress(SQLiteDatabase db, long id, long mmsId) {
    db.execSQL("INSERT INTO mms_addresses (_id, mms_id, address, type, address_charset) " +
        "VALUES (?, ?, '+15550001', 137, 106)", new Object[]{id, mmsId});
  }

  private void insertPart(SQLiteDatabase db, long id, long mmsId) {
    db.execSQL("INSERT INTO part (_id, mid, seq, ct, _data, data_size, digest, encrypted) " +
        "VALUES (?, ?, 0, 'image/jpeg', '/synthetic/attachment.jpg', 10, X'0102', 1)",
        new Object[]{id, mmsId});
  }

  private void insertDraft(SQLiteDatabase db, long threadId) {
    db.execSQL("INSERT INTO drafts (thread_id, type, value) VALUES (?, 'text', 'draft')",
        new Object[]{threadId});
  }

  private void assertPreservedThread(SQLiteDatabase db, long threadId, int archived, int pinnedOrder) {
    assertEquals(1, value(db, "SELECT COUNT(*) FROM thread WHERE _id = " + threadId +
        " AND archived = " + archived + " AND pinned_order = " + pinnedOrder));
  }

  private int value(SQLiteDatabase db, String sql) {
    try (Cursor cursor = db.rawQuery(sql, null)) {
      return cursor.moveToFirst() ? cursor.getInt(0) : 0;
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
