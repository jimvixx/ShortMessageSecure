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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class MessageTrimmingDatabaseTest {
  private Context context;
  private File databaseFile;
  private SQLiteOpenHelper helper;

  @Before public void setUp() throws Exception {
    context = ApplicationProvider.getApplicationContext();
    databaseFile = new File(context.getCacheDir(), "message-trim-" + System.nanoTime() + ".db");
    helper = helper();
  }

  @After public void tearDown() {
    if (helper != null) helper.close();
    if (databaseFile != null) databaseFile.delete();
  }

  @Test public void trimsSmsOnlyConversationToNewestRetentionLimit() throws Exception {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 0, 0);
    insertSms(db, 1, 42, 1000, "old");
    insertSms(db, 2, 42, 2000, "middle");
    insertSms(db, 3, 42, 3000, "new");

    MessageTrimmingDatabase.TrimResult result = trim(42, 2);

    assertEquals(MessageTrimmingDatabase.Status.TRIMMED, result.status);
    assertEquals(1, result.deletedSmsCount);
    assertEquals(0, result.deletedMmsCount);
    assertEquals(0, scalar(db, "SELECT COUNT(*) FROM sms WHERE _id = 1"));
    assertEquals(2, scalar(db, "SELECT COUNT(*) FROM sms WHERE thread_id = 42"));
  }

  @Test public void trimsMmsOnlyConversationAndQueuesDataAndThumbnail() throws Exception {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 1, 7);
    insertMms(db, 10, 42, 1000, "old");
    insertPart(db, 101, 10, "/synthetic/old.bin", "/synthetic/old-thumb.jpg", 1);
    insertMms(db, 11, 42, 3000, "new");

    MessageTrimmingDatabase.TrimResult result = trim(42, 1);

    assertEquals(MessageTrimmingDatabase.Status.TRIMMED, result.status);
    assertEquals(0, result.deletedSmsCount);
    assertEquals(1, result.deletedMmsCount);
    assertEquals(0, scalar(db, "SELECT COUNT(*) FROM mms WHERE _id = 10"));
    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM mms WHERE _id = 11"));
    assertEquals(0, scalar(db, "SELECT COUNT(*) FROM part WHERE mid = 10"));
    assertEquals(2, scalar(db, "SELECT COUNT(*) FROM attachment_cleanup"));
    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM attachment_cleanup WHERE file_type = 1"));
    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM attachment_cleanup WHERE file_type = 2"));
    assertEquals(1, scalar(db, "SELECT archived FROM thread WHERE _id = 42"));
    assertEquals(7, scalar(db, "SELECT pinned_order FROM thread WHERE _id = 42"));
  }

  @Test public void mixedConversationKeepsNewestAcrossTransportsAndScalesSecondTimestamps() throws Exception {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 0, 0);
    insertMms(db, 20, 42, 1600000000L, "old mms in seconds");
    insertSms(db, 21, 42, 1700000000000L, "new sms in milliseconds");
    insertSms(db, 22, 42, 1500000000000L, "older sms");

    MessageTrimmingDatabase.TrimResult result = trim(42, 1);

    assertEquals(MessageTrimmingDatabase.Status.TRIMMED, result.status);
    assertEquals(1, result.deletedSmsCount);
    assertEquals(1, result.deletedMmsCount);
    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM sms WHERE _id = 21"));
    assertEquals(0, scalar(db, "SELECT COUNT(*) FROM mms WHERE _id = 20"));
  }

  @Test public void equalTimestampsHaveDeterministicMmsBeforeSmsTieOrder() throws Exception {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 0, 0);
    insertSms(db, 7, 42, 1700000000000L, "sms");
    insertMms(db, 7, 42, 1700000000000L, "mms");

    MessageTrimmingDatabase.TrimResult result = trim(42, 1);

    assertEquals(1, result.deletedSmsCount);
    assertEquals(0, result.deletedMmsCount);
    assertEquals(0, scalar(db, "SELECT COUNT(*) FROM sms WHERE _id = 7"));
    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM mms WHERE _id = 7"));
  }

  @Test public void collidingTransportIdsRemainIndependentAndSharedPathsAreQueuedPerPart() throws Exception {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 0, 0);
    insertSms(db, 7, 42, 1000, "old sms");
    insertMms(db, 7, 42, 3000, "new mms");
    insertPart(db, 71, 7, "/synthetic/shared.bin", "/synthetic/shared.bin", 1);
    insertPart(db, 72, 7, "/synthetic/shared.bin", null, 0);

    MessageTrimmingDatabase.TrimResult result = trim(42, 1);

    assertEquals(1, result.deletedSmsCount);
    assertEquals(0, result.deletedMmsCount);
    assertEquals(0, scalar(db, "SELECT COUNT(*) FROM sms WHERE _id = 7"));
    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM mms WHERE _id = 7"));
    assertEquals(2, scalar(db, "SELECT COUNT(*) FROM part WHERE mid = 7"));
    assertEquals(0, scalar(db, "SELECT COUNT(*) FROM attachment_cleanup"));
  }

  @Test public void obsoleteMmsWithSharedPathsQueuesEachDataAndThumbnailReference() throws Exception {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 0, 0);
    insertMms(db, 7, 42, 1000, "old mms");
    insertPart(db, 71, 7, "/synthetic/shared.bin", "/synthetic/shared.bin", 1);
    insertPart(db, 72, 7, "/synthetic/shared.bin", null, 0);
    insertSms(db, 8, 42, 3000, "new sms");

    MessageTrimmingDatabase.TrimResult result = trim(42, 1);

    assertEquals(1, result.deletedMmsCount);
    assertEquals(3, scalar(db, "SELECT COUNT(*) FROM attachment_cleanup WHERE file_path = '/synthetic/shared.bin'"));
    assertEquals(2, scalar(db, "SELECT COUNT(*) FROM attachment_cleanup WHERE file_type = 1"));
    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM attachment_cleanup WHERE file_type = 2"));
  }

  @Test public void emptyConversationAndRetentionBoundariesAreNoOps() throws Exception {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 0, 0);
    assertEquals(MessageTrimmingDatabase.Status.NO_OP, trim(42, 1).status);
    insertSms(db, 1, 42, 1000, "only");
    assertEquals(MessageTrimmingDatabase.Status.NO_OP, trim(42, 1).status);
    assertEquals(MessageTrimmingDatabase.Status.NO_OP, trim(42, 0).status);
    assertEquals(MessageTrimmingDatabase.Status.NO_OP, trim(42, -1).status);
    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM sms WHERE thread_id = 42"));
  }

  @Test public void rollbackRestoresSmsMmsPartsQueueAndThreadMetadata() throws Exception {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 1, 8);
    insertSms(db, 1, 42, 1000, "old sms");
    insertMms(db, 2, 42, 2000, "old mms");
    insertPart(db, 21, 2, "/synthetic/rollback.bin", "/synthetic/rollback-thumb.jpg", 1);
    insertSms(db, 3, 42, 3000, "new sms");
    db.execSQL("CREATE TRIGGER reject_mms_trim BEFORE DELETE ON mms " +
        "BEGIN SELECT RAISE(ABORT, 'synthetic trim failure'); END");

    MessageTrimmingDatabase.TrimResult result = trim(42, 1);

    assertEquals(MessageTrimmingDatabase.Status.FAILED, result.status);
    assertEquals(2, scalar(db, "SELECT COUNT(*) FROM sms WHERE thread_id = 42"));
    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM mms WHERE _id = 2"));
    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM part WHERE mid = 2"));
    assertEquals(0, scalar(db, "SELECT COUNT(*) FROM attachment_cleanup"));
    assertEquals(1, scalar(db, "SELECT archived FROM thread WHERE _id = 42"));
    assertEquals(8, scalar(db, "SELECT pinned_order FROM thread WHERE _id = 42"));
  }

  @Test public void unrelatedConversationsAndThreadAggregationRemainUnchanged() throws Exception {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 1, 5);
    insertThread(db, 43, 0, 0);
    insertSms(db, 1, 42, 1000, "old");
    insertMms(db, 2, 42, 2000, "latest mms");
    insertPart(db, 21, 2, "/synthetic/latest.bin", null, 1);
    insertSms(db, 3, 43, 1000, "unrelated");
    insertSms(db, 4, 42, 3000, "latest sms");

    MessageTrimmingDatabase.TrimResult result = trim(42, 1);

    assertEquals(1, result.deletedSmsCount);
    assertEquals(1, result.deletedMmsCount);
    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM sms WHERE _id = 3 AND thread_id = 43"));
    assertEquals(1, scalar(db, "SELECT message_count FROM thread WHERE _id = 42"));
    assertEquals("latest sms", scalarString(db, "SELECT snippet FROM thread WHERE _id = 42"));
    assertEquals(1, scalar(db, "SELECT archived FROM thread WHERE _id = 42"));
    assertEquals(5, scalar(db, "SELECT pinned_order FROM thread WHERE _id = 42"));
    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM attachment_cleanup WHERE source_mms_id = 2"));
  }

  @Test public void threadReadStateReflectsRemainingUnreadMessage() throws Exception {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 1, 4);
    insertMms(db, 1, 42, 4000, "new unread mms");
    db.execSQL("UPDATE mms SET read = 0 WHERE _id = 1");
    insertSms(db, 2, 42, 2000, "old unread sms");
    db.execSQL("UPDATE sms SET read = 0 WHERE _id = 2");
    insertSms(db, 3, 42, 5000, "newest read sms");

    trim(42, 2);

    assertEquals(0, scalar(db, "SELECT read FROM thread WHERE _id = 42"));
    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM mms WHERE _id = 1 AND read = 0"));
    assertEquals(1, scalar(db, "SELECT archived FROM thread WHERE _id = 42"));
    assertEquals(4, scalar(db, "SELECT pinned_order FROM thread WHERE _id = 42"));
  }

  @Test public void repeatedTrimIsIdempotent() throws Exception {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 0, 0);
    insertSms(db, 1, 42, 1000, "old");
    insertSms(db, 2, 42, 2000, "new");

    MessageTrimmingDatabase.TrimResult first = trim(42, 1);
    MessageTrimmingDatabase.TrimResult second = trim(42, 1);

    assertEquals(MessageTrimmingDatabase.Status.TRIMMED, first.status);
    assertEquals(MessageTrimmingDatabase.Status.NO_OP, second.status);
    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM sms WHERE thread_id = 42"));
  }

  @Test public void existingProductionSmsOnlyTrimLeavesHiddenMmsAndAttachmentsUntouched() throws Exception {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 1, 3);
    insertSms(db, 1, 42, 1000, "old sms");
    insertSms(db, 2, 42, 2000, "middle sms");
    insertSms(db, 3, 42, 3000, "new sms");
    insertMms(db, 4, 42, 9000, "hidden mms");
    insertPart(db, 41, 4, "/synthetic/hidden.bin", "/synthetic/hidden-thumb.jpg", 1);

    new ThreadDatabase(context, helper).trimThread(42, 1);

    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM sms WHERE thread_id = 42"));
    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM mms WHERE _id = 4"));
    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM part WHERE mid = 4"));
    assertEquals(0, scalar(db, "SELECT COUNT(*) FROM attachment_cleanup"));
    assertEquals(2, scalar(db, "SELECT message_count FROM thread WHERE _id = 42"));
    assertEquals(1, scalar(db, "SELECT archived FROM thread WHERE _id = 42"));
    assertEquals(3, scalar(db, "SELECT pinned_order FROM thread WHERE _id = 42"));
  }

  private MessageTrimmingDatabase.TrimResult trim(long threadId, int limit) {
    return new MessageTrimmingDatabase(context, helper).trimThread(threadId, limit);
  }

  private SQLiteOpenHelper helper() throws Exception {
    Class<?> helperClass = DatabaseFactory.class.getDeclaredClasses()[0];
    Constructor<?> constructor = helperClass.getDeclaredConstructor(Context.class, String.class,
        SQLiteDatabase.CursorFactory.class, int.class);
    constructor.setAccessible(true);
    return (SQLiteOpenHelper) constructor.newInstance(context, databaseFile.getPath(), null, 38);
  }

  private void insertThread(SQLiteDatabase db, long id, int archived, long pinnedOrder) {
    db.execSQL("INSERT INTO thread (_id, date, message_count, recipient_ids, snippet, read, type, " +
        "snippet_type, archived, status, last_seen, pinned_order) " +
        "VALUES (?, 1000, 0, '1', 'old snippet', 1, 0, 0, ?, 0, 0, ?)",
        new Object[]{id, archived, pinnedOrder});
  }

  private void insertSms(SQLiteDatabase db, long id, long threadId, long timestamp, String body) {
    db.execSQL("INSERT INTO sms (_id, thread_id, date, date_sent, read, status, type, body, address) " +
        "VALUES (?, ?, ?, ?, 1, 0, 20, ?, '+15550001')",
        new Object[]{id, threadId, timestamp, timestamp, body});
  }

  private void insertMms(SQLiteDatabase db, long id, long threadId, long timestamp, String body) {
    db.execSQL("INSERT INTO mms (_id, thread_id, date, date_received, msg_box, read, m_type, body, " +
        "address, part_count, st, tr_id) VALUES (?, ?, ?, ?, 1, 1, 132, ?, '+15550001', 1, 0, 'tx')",
        new Object[]{id, threadId, timestamp, timestamp, body});
  }

  private void insertPart(SQLiteDatabase db, long id, long mmsId, String data, String thumbnail,
                          Integer encrypted) {
    db.execSQL("INSERT INTO part (_id, mid, seq, encrypted, _data, thumbnail, digest) " +
        "VALUES (?, ?, 0, ?, ?, ?, X'0102')", new Object[]{id, mmsId, encrypted, data, thumbnail});
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
}
