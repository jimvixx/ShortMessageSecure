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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class DatabaseFactoryMmsMigrationTest {
  private Context context;
  private File databaseFile;
  private SQLiteOpenHelper helper;

  @Before public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    databaseFile = new File(context.getCacheDir(), "mms-migration-" + System.nanoTime() + ".db");
  }

  @After public void tearDown() {
    if (helper != null) helper.close();
    if (databaseFile != null) databaseFile.delete();
  }

  @Test public void cleanInstallCreatesCompatibleMmsSchemaAndIndexes() throws Exception {
    SQLiteOpenHelper helper = helper();
    SQLiteDatabase db = helper.getWritableDatabase();

    assertColumns(db, "mms", "_id", "thread_id", "date", "date_received", "body", "subscription_id", "notified");
    assertColumns(db, "mms_addresses", "_id", "mms_id", "address", "type", "address_charset");
    assertColumns(db, "part", "_id", "mid", "_data", "data_size", "thumbnail", "unique_id");
    assertColumns(db, "attachment_cleanup", "_id", "file_path", "file_type", "source_mms_id",
        "source_part_id", "encrypted", "created_at");
    assertTrue(indexExists(db, "attachment_cleanup_created_index"));
    assertTrue(indexExists(db, "mms_thread_id_index"));
    assertTrue(indexExists(db, "mms_addresses_mms_id_index"));
    assertTrue(indexExists(db, "part_mms_id_index"));
    assertEquals(37, db.getVersion());
    helper.close();
  }

  @Test public void upgrade35PreservesMmsRowsAddressesAndAttachmentPaths() throws Exception {
    createVersion35Database(false);
    SQLiteOpenHelper helper = helper();
    SQLiteDatabase db = helper.getWritableDatabase();

    assertEquals(1, count(db, "mms"));
    assertEquals(1, count(db, "mms_addresses"));
    assertEquals(1, count(db, "part"));
    assertEquals("/existing/attachment", scalar(db, "SELECT _data FROM part"));
    assertEquals("+15551234567", scalar(db, "SELECT address FROM mms_addresses"));
    assertEquals(37, db.getVersion());
    helper.close();
  }

  @Test public void upgrade35CompletesPartialMmsTablesWithoutReplacingRows() throws Exception {
    createVersion35Database(true);
    SQLiteOpenHelper helper = helper();
    SQLiteDatabase db = helper.getWritableDatabase();

    assertEquals(1, count(db, "mms"));
    assertEquals(1, count(db, "part"));
    assertColumns(db, "mms", "date_received", "body", "address", "notified");
    assertColumns(db, "part", "_data", "data_size", "thumbnail", "unique_id");
    assertTrue(indexExists(db, "mms_thread_id_index"));
    assertTrue(indexExists(db, "part_mms_id_index"));
    assertEquals("/partial/attachment", scalar(db, "SELECT _data FROM part"));
    helper.close();
  }

  @Test public void partialSchemaSupportsReadOnlyQueriesAndPreservesMultipleRows() throws Exception {
    createPartialReadableDatabase();
    helper = helper();
    SQLiteDatabase db = helper.getWritableDatabase();

    assertEquals(2, count(db, "mms"));
    assertEquals(3, count(db, "part"));
    assertEquals(106, scalarInt(db, "SELECT address_charset FROM mms_addresses WHERE _id = 1"));
    assertEquals(106, scalarInt(db, "SELECT address_charset FROM mms_addresses WHERE _id = 2"));

    assertEquals(2, new MmsDatabase(context, helper).getMessagesForThread(1).size());
    assertEquals(1, new MmsAddressDatabase(context, helper).getAddressesForMessage(1).size());
    List<AttachmentDatabase.AttachmentRecord> firstAttachments =
        new AttachmentDatabase(context, helper).getAttachmentsForMessage(1);
    assertEquals(1, firstAttachments.size());
    assertEquals("/partial/one.mms", firstAttachments.get(0).path);
    assertEquals(1, firstAttachments.get(0).encrypted);
    assertArrayEquals(new byte[]{9, 8, 7}, firstAttachments.get(0).digest);

    List<AttachmentDatabase.AttachmentRecord> secondAttachments =
        new AttachmentDatabase(context, helper).getAttachmentsForMessage(2);
    assertEquals(2, secondAttachments.size());
    assertEquals("/partial/two-a.mms", secondAttachments.get(0).path);
    assertEquals("/partial/two-b.mms", secondAttachments.get(1).path);
    helper.close();
    helper = null;
  }

  @Test public void incompatibleMmsTableFailsMigrationSafely() throws Exception {
    createVersion35Database(false);
    SQLiteDatabase db = SQLiteDatabase.openDatabase(databaseFile.getPath(), null, SQLiteDatabase.OPEN_READWRITE);
    db.execSQL("DROP TABLE mms");
    db.execSQL("CREATE TABLE mms (row_key INTEGER PRIMARY KEY, thread_id INTEGER)");
    db.setVersion(35);
    db.close();

    SQLiteOpenHelper incompatible = helper();
    try {
      incompatible.getWritableDatabase();
      fail("incompatible MMS schema must not report a successful migration");
    } catch (IllegalStateException expected) {
      assertTrue(expected.getMessage().contains("missing required column _id"));
    } finally {
      incompatible.close();
    }
  }

  @Test public void upgradeRealSilenceMmsSchema30To37PreservesPduAndAttachmentData() throws Exception {
    createSilenceSchema30Database();
    SQLiteOpenHelper helper = helper();
    SQLiteDatabase db = helper.getWritableDatabase();

    assertEquals(37, db.getVersion());
    assertEquals(1, count(db, "mms"));
    assertEquals("transaction-1", scalar(db, "SELECT tr_id FROM mms"));
    assertEquals(1, count(db, "mms_addresses"));
    assertEquals("+15551234567", scalar(db, "SELECT address FROM mms_addresses"));
    assertEquals("/silence/parts/1.mms", scalar(db, "SELECT _data FROM part"));
    assertColumns(db, "mms_addresses", "address_charset");
    assertColumns(db, "part", "data_size", "thumbnail", "unique_id");
    assertEquals(0, count(db, "attachment_cleanup"));
    helper.close();
  }

  @Test public void upgrade36CreatesCleanupQueueWithoutChangingMmsRowsOrPaths() throws Exception {
    createVersion35Database(false);
    SQLiteOpenHelper version36 = helper(36);
    SQLiteDatabase version36Db = version36.getWritableDatabase();
    assertFalse(tableExists(version36Db, "attachment_cleanup"));
    version36.close();

    helper = helper();
    SQLiteDatabase db = helper.getWritableDatabase();
    assertEquals(37, db.getVersion());
    assertEquals(1, count(db, "mms"));
    assertEquals(1, count(db, "mms_addresses"));
    assertEquals(1, count(db, "part"));
    assertEquals("/existing/attachment", scalar(db, "SELECT _data FROM part"));
    assertEquals(0, count(db, "attachment_cleanup"));
  }

  private SQLiteOpenHelper helper() throws Exception {
    return helper(37);
  }

  private SQLiteOpenHelper helper(int version) throws Exception {
    Class<?> helperClass = DatabaseFactory.class.getDeclaredClasses()[0];
    Constructor<?> constructor = helperClass.getDeclaredConstructor(Context.class, String.class,
            SQLiteDatabase.CursorFactory.class, int.class);
    constructor.setAccessible(true);
    return (SQLiteOpenHelper) constructor.newInstance(context, databaseFile.getPath(), null, version);
  }

  private void createVersion35Database(boolean partial) throws Exception {
    SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(databaseFile, null);
    db.execSQL("CREATE TABLE sms (_id INTEGER PRIMARY KEY, thread_id INTEGER, date INTEGER, date_sent INTEGER, read INTEGER DEFAULT 0, type INTEGER, body TEXT, address TEXT, address_device_id INTEGER DEFAULT 1, date_delivery_received INTEGER DEFAULT 0, mismatched_identities TEXT, subscription_id INTEGER DEFAULT -1, notified INTEGER DEFAULT 0)");
    db.execSQL("CREATE TABLE thread (_id INTEGER PRIMARY KEY, recipient_ids TEXT, snippet TEXT, snippet_type INTEGER DEFAULT 0, snippet_uri TEXT, archived INTEGER DEFAULT 0, status INTEGER DEFAULT 0, last_seen INTEGER DEFAULT 0, pinned_order INTEGER DEFAULT 0)");
    db.execSQL("CREATE TABLE identities (_id INTEGER PRIMARY KEY, recipient INTEGER UNIQUE, identity_key TEXT, mac TEXT, verified INTEGER DEFAULT 0)");
    db.execSQL("CREATE TABLE drafts (_id INTEGER PRIMARY KEY, thread_id INTEGER, type TEXT, value TEXT)");
    db.execSQL("CREATE TABLE recipient_preferences (_id INTEGER PRIMARY KEY, recipient_ids TEXT UNIQUE, block INTEGER DEFAULT 0, notification TEXT, vibrate INTEGER DEFAULT 0, mute_until INTEGER DEFAULT 0, color TEXT, default_subscription_id INTEGER DEFAULT -1)");
    if (partial) {
      db.execSQL("CREATE TABLE mms (_id INTEGER PRIMARY KEY, thread_id INTEGER)");
      db.execSQL("CREATE TABLE part (_id INTEGER PRIMARY KEY, mid INTEGER, _data TEXT)");
      db.execSQL("CREATE INDEX mms_thread_id_index ON mms (thread_id)");
      db.execSQL("CREATE INDEX part_mms_id_index ON part (mid)");
    } else {
      db.execSQL("CREATE TABLE mms (_id INTEGER PRIMARY KEY, thread_id INTEGER, date INTEGER, date_received INTEGER, msg_box INTEGER, read INTEGER DEFAULT 0, body TEXT, part_count INTEGER, address TEXT, address_device_id INTEGER DEFAULT 1, delivery_receipt_count INTEGER DEFAULT 0, date_delivery_received INTEGER DEFAULT 0, mismatched_identities TEXT, network_failures TEXT, subscription_id INTEGER DEFAULT -1, notified INTEGER DEFAULT 0)");
      db.execSQL("CREATE TABLE mms_addresses (_id INTEGER PRIMARY KEY, mms_id INTEGER, address TEXT, type INTEGER, charset INTEGER)");
      db.execSQL("CREATE TABLE part (_id INTEGER PRIMARY KEY, mid INTEGER, _data TEXT, pending_push INTEGER, data_size INTEGER DEFAULT 0, file_name TEXT, thumbnail TEXT, aspect_ratio REAL, unique_id INTEGER NOT NULL DEFAULT 0, digest BLOB)");
      db.execSQL("INSERT INTO mms_addresses VALUES (1, 1, '+15551234567', 137, 106)");
    }
    db.execSQL("INSERT INTO thread VALUES (1, '1', 'snippet', 0, NULL, 0, 0, 0, 0)");
    db.execSQL("INSERT INTO part (_id, mid, _data) VALUES (1, 1, '" + (partial ? "/partial/attachment" : "/existing/attachment") + "')");
    db.execSQL("INSERT INTO mms (_id, thread_id) VALUES (1, 1)");
    db.setVersion(35);
    db.close();
  }

  private void createPartialReadableDatabase() throws Exception {
    createVersion35Database(true);
    SQLiteDatabase db = SQLiteDatabase.openDatabase(databaseFile.getPath(), null, SQLiteDatabase.OPEN_READWRITE);
    db.execSQL("ALTER TABLE mms ADD COLUMN date INTEGER");
    db.execSQL("ALTER TABLE mms ADD COLUMN date_received INTEGER");
    db.execSQL("ALTER TABLE mms ADD COLUMN body TEXT");
    db.execSQL("ALTER TABLE mms ADD COLUMN address TEXT");
    db.execSQL("ALTER TABLE mms ADD COLUMN part_count INTEGER");
    db.execSQL("UPDATE mms SET date = 1000, date_received = 1100, body = 'one', address = '+15550001', part_count = 1 WHERE _id = 1");
    db.execSQL("INSERT INTO mms (_id, thread_id, date, date_received, body, address, part_count) VALUES (2, 1, 2000, 2100, 'two', '+15550002', 2)");
    db.execSQL("ALTER TABLE part ADD COLUMN seq INTEGER DEFAULT 0");
    db.execSQL("ALTER TABLE part ADD COLUMN encrypted INTEGER");
    db.execSQL("ALTER TABLE part ADD COLUMN digest BLOB");
    db.execSQL("UPDATE part SET seq = 0, encrypted = 1, digest = X'090807', _data = '/partial/one.mms' WHERE _id = 1");
    db.execSQL("INSERT INTO part (_id, mid, seq, encrypted, digest, _data) VALUES (2, 2, 0, 1, X'0102', '/partial/two-a.mms')");
    db.execSQL("INSERT INTO part (_id, mid, seq, encrypted, digest, _data) VALUES (3, 2, 1, 0, X'0304', '/partial/two-b.mms')");
    db.execSQL("CREATE TABLE mms_addresses (_id INTEGER PRIMARY KEY, mms_id INTEGER, address TEXT, type INTEGER, charset INTEGER)");
    db.execSQL("INSERT INTO mms_addresses (_id, mms_id, address, type, charset) VALUES (1, 1, '+15550001', 137, 106)");
    db.execSQL("INSERT INTO mms_addresses (_id, mms_id, address, type, charset) VALUES (2, 2, '+15550002', 137, 106)");
    db.setVersion(35);
    db.close();
  }

  /** DDL is taken from the locally available Silence MMS database sources. */
  private void createSilenceSchema30Database() throws Exception {
    createVersion35Database(false);
    SQLiteDatabase db = SQLiteDatabase.openDatabase(databaseFile.getPath(), null, SQLiteDatabase.OPEN_READWRITE);
    db.execSQL("DROP TABLE mms");
    db.execSQL("DROP TABLE mms_addresses");
    db.execSQL("DROP TABLE part");
    db.execSQL("CREATE TABLE mms (_id INTEGER PRIMARY KEY, thread_id INTEGER, date INTEGER, date_received INTEGER, msg_box INTEGER, read INTEGER DEFAULT 0, m_id TEXT, sub TEXT, sub_cs INTEGER, body TEXT, part_count INTEGER, ct_t TEXT, ct_l TEXT, address TEXT, address_device_id INTEGER, exp INTEGER, m_cls TEXT, m_type INTEGER, v INTEGER, m_size INTEGER, pri INTEGER, rr INTEGER, rpt_a INTEGER, resp_st INTEGER, st INTEGER, tr_id TEXT, retr_st INTEGER, retr_txt TEXT, retr_txt_cs INTEGER, read_status INTEGER, ct_cls INTEGER, resp_txt TEXT, d_tm INTEGER, date_delivery_received INTEGER DEFAULT 0, mismatched_identities TEXT DEFAULT NULL, network_failures TEXT DEFAULT NULL, d_rpt INTEGER, subscription_id INTEGER DEFAULT -1, notified INTEGER DEFAULT 0)");
    db.execSQL("CREATE TABLE mms_addresses (_id INTEGER PRIMARY KEY, mms_id INTEGER, type INTEGER, address TEXT, address_charset INTEGER)");
    db.execSQL("CREATE TABLE part (_id INTEGER PRIMARY KEY, mid INTEGER, seq INTEGER DEFAULT 0, ct TEXT, name TEXT, chset INTEGER, cd TEXT, fn TEXT, cid TEXT, cl TEXT, ctt_s INTEGER, ctt_t TEXT, encrypted INTEGER, pending_push INTEGER, _data TEXT, data_size INTEGER, thumbnail TEXT, aspect_ratio REAL, unique_id INTEGER NOT NULL, digest BLOB)");
    db.execSQL("INSERT INTO mms (_id, thread_id, date, date_received, msg_box, read, body, part_count, tr_id) VALUES (1, 1, 1000, 1000, 1, 0, 'silence-body', 1, 'transaction-1')");
    db.execSQL("INSERT INTO mms_addresses (_id, mms_id, type, address, address_charset) VALUES (1, 1, 137, '+15551234567', 106)");
    db.execSQL("INSERT INTO part (_id, mid, ct, _data, unique_id) VALUES (1, 1, 'image/jpeg', '/silence/parts/1.mms', 1)");
    db.setVersion(30);
    db.close();
  }

  private static void assertColumns(SQLiteDatabase db, String table, String... expected) {
    Set<String> actual = new HashSet<>();
    try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
      while (cursor.moveToNext()) actual.add(cursor.getString(cursor.getColumnIndexOrThrow("name")));
    }
    for (String column : expected) assertTrue(table + " missing " + column, actual.contains(column));
  }

  private static boolean indexExists(SQLiteDatabase db, String index) {
    try (Cursor cursor = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='index' AND name=?", new String[]{index})) {
      return cursor.moveToFirst();
    }
  }

  private static boolean tableExists(SQLiteDatabase db, String table) {
    try (Cursor cursor = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
        new String[]{table})) {
      return cursor.moveToFirst();
    }
  }

  private static int count(SQLiteDatabase db, String table) {
    try (Cursor cursor = db.rawQuery("SELECT count(*) FROM " + table, null)) {
      assertTrue(cursor.moveToFirst());
      return cursor.getInt(0);
    }
  }

  private static String scalar(SQLiteDatabase db, String sql) {
    try (Cursor cursor = db.rawQuery(sql, null)) {
      assertTrue(cursor.moveToFirst());
      return cursor.getString(0);
    }
  }

  private static int scalarInt(SQLiteDatabase db, String sql) {
    try (Cursor cursor = db.rawQuery(sql, null)) {
      assertTrue(cursor.moveToFirst());
      return cursor.getInt(0);
    }
  }
}
