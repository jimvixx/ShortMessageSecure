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
import java.io.File;
import java.lang.reflect.Constructor;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class MmsDeletionQueueTest {
  private Context context;
  private File databaseFile;
  private SQLiteOpenHelper helper;

  @Before public void setUp() throws Exception {
    context = ApplicationProvider.getApplicationContext();
    databaseFile = new File(context.getCacheDir(), "mms-delete-queue-" + System.nanoTime() + ".db");
    helper = helper();
    helper.getWritableDatabase();
  }

  @After public void tearDown() {
    if (helper != null) helper.close();
    if (databaseFile != null) databaseFile.delete();
  }

  @Test public void singleDeletionQueuesDataAndThumbnailAndPreservesCollidingSms() throws Exception {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 1, 6);
    insertSms(db, 7, 42, 3000, "still here");
    insertMms(db, 7, 42, 5000, "delete me");
    insertAddress(db, 71, 7);
    insertPart(db, 71, 7, "/synthetic/data.bin", "/synthetic/thumb.jpg", 1);

    assertEquals(MmsDatabase.DeletionResult.DELETED,
        new MmsDatabase(context, helper).deleteMessage(7));

    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM sms WHERE _id = 7"));
    assertEquals(0, scalar(db, "SELECT COUNT(*) FROM mms WHERE _id = 7"));
    assertEquals(0, scalar(db, "SELECT COUNT(*) FROM mms_addresses WHERE mms_id = 7"));
    assertEquals(0, scalar(db, "SELECT COUNT(*) FROM part WHERE mid = 7"));
    assertEquals(2, scalar(db, "SELECT COUNT(*) FROM attachment_cleanup"));
    assertEquals(1, scalar(db, "SELECT encrypted FROM attachment_cleanup WHERE file_type = 1"));
    assertEquals(1, scalar(db, "SELECT message_count FROM thread WHERE _id = 42"));
    assertEquals("still here", scalarString(db, "SELECT snippet FROM thread WHERE _id = 42"));
    assertEquals(1, scalar(db, "SELECT archived FROM thread WHERE _id = 42"));
    assertEquals(6, scalar(db, "SELECT pinned_order FROM thread WHERE _id = 42"));
    assertEquals(1, scalar(db, "SELECT read FROM thread WHERE _id = 42"));
  }

  @Test public void deletingFinalMmsRemovesMmsOnlyThread() throws Exception {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 0, 0);
    insertMms(db, 8, 42, 5000, "last");
    insertPart(db, 81, 8, "/synthetic/last.bin", null, 0);

    assertEquals(MmsDatabase.DeletionResult.DELETED,
        new MmsDatabase(context, helper).deleteMessage(8));
    assertEquals(0, scalar(db, "SELECT COUNT(*) FROM thread WHERE _id = 42"));
    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM attachment_cleanup"));
  }

  @Test public void duplicateSharedPathsAreQueuedSeparatelyAndInOriginalOrder() throws Exception {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 0, 0);
    insertMms(db, 9, 42, 5000, "shared");
    insertPart(db, 91, 9, "/synthetic/shared.bin", "/synthetic/shared.bin", 0);
    insertPart(db, 92, 9, "/synthetic/shared.bin", null, 1);

    assertEquals(MmsDatabase.DeletionResult.DELETED,
        new MmsDatabase(context, helper).deleteMessage(9));
    assertEquals(3, scalar(db, "SELECT COUNT(*) FROM attachment_cleanup"));
    assertEquals(2, scalar(db, "SELECT COUNT(*) FROM attachment_cleanup WHERE file_path = '/synthetic/shared.bin' AND file_type = 1"));
    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM attachment_cleanup WHERE file_path = '/synthetic/shared.bin' AND file_type = 2"));
  }

  @Test public void nullAndEmptyPathsAreNotQueuedButInvalidNonemptyPathsArePreserved() throws Exception {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 0, 0);
    insertMms(db, 10, 42, 5000, "paths");
    insertPart(db, 101, 10, null, "", 0);
    insertPart(db, 102, 10, "../outside/file.bin", null, 0);
    insertPart(db, 103, 10, "/synthetic/unknown-encryption.bin", null, null);

    assertEquals(MmsDatabase.DeletionResult.DELETED,
        new MmsDatabase(context, helper).deleteMessage(10));
    assertEquals(2, scalar(db, "SELECT COUNT(*) FROM attachment_cleanup"));
    assertEquals("../outside/file.bin", scalarString(db,
        "SELECT file_path FROM attachment_cleanup WHERE source_part_id = 102"));
    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM attachment_cleanup " +
        "WHERE source_part_id = 103 AND encrypted IS NULL"));
  }

  @Test public void repeatedDeletionIsNotFoundAndDoesNotDuplicateQueue() throws Exception {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 0, 0);
    insertMms(db, 11, 42, 5000, "once");
    insertPart(db, 111, 11, "/synthetic/once.bin", null, 0);
    MmsDatabase messages = new MmsDatabase(context, helper);

    assertEquals(MmsDatabase.DeletionResult.DELETED, messages.deleteMessage(11));
    assertEquals(MmsDatabase.DeletionResult.NOT_FOUND, messages.deleteMessage(11));
    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM attachment_cleanup"));
  }

  @Test public void failureRollsBackQueueMmsAddressesPartsAndThreadMetadata() throws Exception {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 1, 6);
    insertMms(db, 12, 42, 5000, "rollback");
    insertAddress(db, 121, 12);
    insertPart(db, 121, 12, "/synthetic/rollback.bin", "/synthetic/rollback-thumb.jpg", 1);
    db.execSQL("CREATE TRIGGER reject_mms_delete BEFORE DELETE ON mms " +
        "BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END");

    assertEquals(MmsDatabase.DeletionResult.FAILED,
        new MmsDatabase(context, helper).deleteMessage(12));
    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM mms WHERE _id = 12"));
    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM mms_addresses WHERE mms_id = 12"));
    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM part WHERE mid = 12"));
    assertEquals(0, scalar(db, "SELECT COUNT(*) FROM attachment_cleanup"));
    assertEquals(1, scalar(db, "SELECT archived FROM thread WHERE _id = 42"));
    assertEquals(6, scalar(db, "SELECT pinned_order FROM thread WHERE _id = 42"));
  }

  @Test public void cleanupQueueSurvivesHelperCloseAndReopen() throws Exception {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 0, 0);
    insertMms(db, 13, 42, 5000, "durable");
    insertPart(db, 131, 13, "/synthetic/durable.bin", null, 0);
    assertEquals(MmsDatabase.DeletionResult.DELETED,
        new MmsDatabase(context, helper).deleteMessage(13));
    helper.close();

    helper = helper();
    List<AttachmentCleanupDatabase.CleanupEntry> entries =
        new AttachmentCleanupDatabase(context, helper).getPendingEntries(10);
    assertEquals(1, entries.size());
    assertEquals("/synthetic/durable.bin", entries.get(0).path);
    assertEquals(13, entries.get(0).sourceMmsId);
    assertEquals(131, entries.get(0).sourcePartId);
  }

  @Test public void remainingMmsAggregationAndThreadStateAreUpdated() throws Exception {
    SQLiteDatabase db = helper.getWritableDatabase();
    insertThread(db, 42, 1, 6);
    insertMms(db, 14, 42, 5000, "remove older");
    insertMms(db, 15, 42, 7000, "keep newer");
    db.execSQL("UPDATE mms SET read = 0 WHERE _id = 15");
    insertPart(db, 141, 14, "/synthetic/older.bin", null, 0);
    insertPart(db, 151, 15, "/synthetic/newer.bin", null, 1);

    assertEquals(MmsDatabase.DeletionResult.DELETED,
        new MmsDatabase(context, helper).deleteMessage(14));
    assertEquals(1, scalar(db, "SELECT message_count FROM thread WHERE _id = 42"));
    assertEquals(8000, scalar(db, "SELECT date FROM thread WHERE _id = 42"));
    assertEquals(1, scalar(db, "SELECT archived FROM thread WHERE _id = 42"));
    assertEquals(6, scalar(db, "SELECT pinned_order FROM thread WHERE _id = 42"));
    assertEquals(0, scalar(db, "SELECT read FROM thread WHERE _id = 42"));
    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM mms WHERE _id = 15"));
    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM part WHERE mid = 15"));
    assertEquals("/synthetic/newer.bin", scalarString(db, "SELECT _data FROM part WHERE mid = 15"));
    assertEquals(1, scalar(db, "SELECT encrypted FROM part WHERE mid = 15"));
    assertEquals("0102", scalarString(db, "SELECT hex(digest) FROM part WHERE mid = 15"));
  }

  private SQLiteOpenHelper helper() throws Exception {
    Class<?> helperClass = DatabaseFactory.class.getDeclaredClasses()[0];
    Constructor<?> constructor = helperClass.getDeclaredConstructor(Context.class, String.class,
        SQLiteDatabase.CursorFactory.class, int.class);
    constructor.setAccessible(true);
    return (SQLiteOpenHelper) constructor.newInstance(context, databaseFile.getPath(), null, 37);
  }

  private void insertThread(SQLiteDatabase db, long id, int archived, long pinnedOrder) {
    db.execSQL("INSERT INTO thread (_id, date, message_count, recipient_ids, snippet, read, type, " +
        "snippet_type, archived, status, last_seen, pinned_order) " +
        "VALUES (?, 1000, 0, '1', 'old sms snippet', 1, 0, 0, ?, 0, 0, ?)",
        new Object[]{id, archived, pinnedOrder});
  }

  private void insertSms(SQLiteDatabase db, long id, long threadId, long date, String body) {
    db.execSQL("INSERT INTO sms (_id, thread_id, date, date_sent, read, status, type, body, address) " +
        "VALUES (?, ?, ?, ?, 1, 0, 20, ?, '+15550001')",
        new Object[]{id, threadId, date, date, body});
  }

  private void insertMms(SQLiteDatabase db, long id, long threadId, long date, String body) {
    db.execSQL("INSERT INTO mms (_id, thread_id, date, date_received, msg_box, read, m_type, body, " +
        "address, part_count, st, tr_id) VALUES (?, ?, ?, ?, 1, 1, 132, ?, '+15550001', 0, 0, 'tx')",
        new Object[]{id, threadId, date, date + 1000, body});
  }

  private void insertAddress(SQLiteDatabase db, long id, long mmsId) {
    db.execSQL("INSERT INTO mms_addresses (_id, mms_id, address, type, address_charset) " +
        "VALUES (?, ?, '+15550001', 137, 106)", new Object[]{id, mmsId});
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
