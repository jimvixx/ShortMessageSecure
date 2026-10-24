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
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.system.Os;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class AttachmentBackupRestorerTest {
  private Context context;
  private File root;
  private SQLiteOpenHelper helper;

  @Before public void setUp() throws Exception {
    Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
    root = File.createTempFile("attachment-backup-", "", base.getCacheDir());
    assertTrue(root.delete());
    assertTrue(root.mkdir());
    context = new ContextWrapper(base) {
      @Override public File getFilesDir() { return directory("files"); }
      @Override public File getCacheDir() { return directory("cache"); }
      @Override public File getNoBackupFilesDir() { return directory("no_backup"); }
      @Override public File getDatabasePath(String name) {
        File databases = directory("databases");
        return new File(databases, name);
      }
      private File directory(String name) {
        File directory = new File(root, name);
        directory.mkdirs();
        return directory;
      }
    };
    helper = new TestDatabaseHelper(context);
    helper.getWritableDatabase();
  }

  @After public void tearDown() {
    if (helper != null) helper.close();
    deleteTree(root);
  }

  @Test public void ownBackupRoundTripPreservesEncryptedBytesAndOnlyReferencedFiles() throws Exception {
    File parts = AttachmentBackupRestorer.getOwnedPartsRoot(context);
    assertTrue(parts.mkdirs());
    File data = attachment(parts, "partSourceData.mms", new byte[]{0, -1, 5, 9, 42});
    File thumbnail = attachment(parts, "partSourceThumb.mms", new byte[]{-8, 0, 3, 11});
    File stale = attachment(parts, "partStale.mms", new byte[]{100});
    SQLiteDatabase db = helper.getWritableDatabase();
    db.execSQL("INSERT INTO part (_id, _data, thumbnail, encrypted, digest) VALUES (1, ?, ?, 1, X'010203')",
            new Object[]{data.getAbsolutePath(), thumbnail.getAbsolutePath()});
    db.execSQL("INSERT INTO part (_id, _data, thumbnail, encrypted) VALUES (2, ?, ?, 0)",
            new Object[]{data.getAbsolutePath(), data.getAbsolutePath()});

    File archive = new File(root, "backup.zip");
    EncryptedBackupExporter.exportToUri(context, Uri.fromFile(archive), helper);
    Map<String, byte[]> entries = unzip(archive);
    assertArrayEquals(new byte[]{0, -1, 5, 9, 42}, entries.get("app_parts/partSourceData.mms"));
    assertArrayEquals(new byte[]{-8, 0, 3, 11}, entries.get("app_parts/partSourceThumb.mms"));
    assertFalse(entries.containsKey("app_parts/partStale.mms"));

    helper.close();
    helper = null;
    EncryptedBackupExporter.stageImportFromUri(context, Uri.fromFile(archive));
    File stagedDatabase = new File(context.getNoBackupFilesDir(), "restore_staging/databases/messages.db");
    String stagedDataPath = stringValue(stagedDatabase, "SELECT _data FROM part WHERE _id = 1");
    String stagedThumbnailPath = stringValue(stagedDatabase, "SELECT thumbnail FROM part WHERE _id = 1");
    assertNotEquals(data.getAbsolutePath(), stagedDataPath);
    assertNotEquals(thumbnail.getAbsolutePath(), stagedThumbnailPath);
    assertEquals(stagedDataPath, stringValue(stagedDatabase, "SELECT _data FROM part WHERE _id = 2"));
    assertEquals(stagedDataPath, stringValue(stagedDatabase, "SELECT thumbnail FROM part WHERE _id = 2"));

    AttachmentBackupRestorer.publishStagedAttachments(context,
            new File(context.getNoBackupFilesDir(), "restore_staging"));
    AttachmentBackupRestorer.publishStagedAttachments(context,
            new File(context.getNoBackupFilesDir(), "restore_staging"));

    assertTrue(EncryptedBackupExporter.applyPendingRestoreIfAny(context));
    File restoredData = new File(stagedDataPath);
    File restoredThumbnail = new File(stagedThumbnailPath);
    assertArrayEquals(new byte[]{0, -1, 5, 9, 42}, read(restoredData));
    assertArrayEquals(new byte[]{-8, 0, 3, 11}, read(restoredThumbnail));
    assertArrayEquals(new byte[]{100}, read(stale));
    SQLiteDatabase restored = SQLiteDatabase.openDatabase(
            context.getDatabasePath("messages.db").getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
    try {
      assertEquals(stagedDataPath, stringValue(restored, "SELECT _data FROM part WHERE _id = 1"));
      assertEquals(1, scalarInt(restored, "SELECT encrypted FROM part WHERE _id = 1"));
      assertArrayEquals(new byte[]{1, 2, 3}, scalarBlob(restored, "SELECT digest FROM part WHERE _id = 1"));
    } finally {
      restored.close();
    }
  }

  @Test public void missingAndUnsafeHistoricalReferencesRemainUnchanged() throws Exception {
    File staging = stagingRoot();
    File sourceParts = new File(staging, "app_parts");
    assertTrue(sourceParts.mkdir());
    File source = attachment(sourceParts, "partAvailable.mms", new byte[]{4, 5, 6});
    File dbFile = createStagedDatabase(staging);
    SQLiteDatabase db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
    String missing = "/data/user/0/org.smssecure.smssecure/app_parts/partMissing.mms";
    String traversal = "/data/user/0/org.smssecure.smssecure/../app_parts/partAvailable.mms";
    db.execSQL("INSERT INTO part (_id, _data) VALUES (1, ?)", new Object[]{missing});
    db.execSQL("INSERT INTO part (_id, _data) VALUES (2, ?)", new Object[]{traversal});
    db.close();

    AttachmentBackupRestorer.prepareStagedRestore(context, staging);
    db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
    try {
      assertEquals(missing, stringValue(db, "SELECT _data FROM part WHERE _id = 1"));
      assertEquals(traversal, stringValue(db, "SELECT _data FROM part WHERE _id = 2"));
    } finally {
      db.close();
    }
    assertFalse(source.exists());
    assertFalse(new File(staging, "restored_parts").list().length > 0);
  }

  @Test public void silenceAbsolutePathsMapOnlyToMatchingArchiveMembers() throws Exception {
    File source = new File(root, "partLegacyBytes.mms");
    byte[] payload = new byte[]{-1, 0, 13, 7, 31};
    try (FileOutputStream output = new FileOutputStream(source)) { output.write(payload); }
    File sourceDb = new File(root, "silence-messages.db");
    SQLiteDatabase legacy = SQLiteDatabase.openOrCreateDatabase(sourceDb, null);
    legacy.execSQL("CREATE TABLE part (_id INTEGER PRIMARY KEY, _data TEXT, thumbnail TEXT, encrypted INTEGER, digest BLOB)");
    legacy.execSQL("INSERT INTO part (_id, _data, encrypted, digest) VALUES (1, ?, 1, X'CAFE')",
            new Object[]{"/data/user/0/org.smssecure.smssecure/app_parts/partLegacyBytes.mms"});
    legacy.close();
    File archive = new File(root, "silence-full-export.zip");
    try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive));
         FileInputStream databaseInput = new FileInputStream(sourceDb);
         FileInputStream attachmentInput = new FileInputStream(source)) {
      putEntry(zip, "databases/messages.db", databaseInput);
      putEntry(zip, "app_parts/partLegacyBytes.mms", attachmentInput);
    }

    helper.close();
    helper = null;
    EncryptedBackupExporter.stageImportFromUri(context, Uri.fromFile(archive));
    File stagedDb = new File(context.getNoBackupFilesDir(), "restore_staging/databases/messages.db");
    String rebased = stringValue(stagedDb, "SELECT _data FROM part WHERE _id = 1");
    assertTrue(rebased.startsWith(AttachmentBackupRestorer.getOwnedPartsRoot(context).getAbsolutePath()));
    assertFalse(rebased.contains("org.smssecure.smssecure"));
    assertTrue(EncryptedBackupExporter.applyPendingRestoreIfAny(context));

    File restoredAttachment = new File(rebased);
    assertArrayEquals(payload, read(restoredAttachment));
    SQLiteDatabase restoredDb = SQLiteDatabase.openDatabase(
            context.getDatabasePath("messages.db").getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
    try {
      assertEquals(rebased, stringValue(restoredDb, "SELECT _data FROM part WHERE _id = 1"));
      assertEquals(1, scalarInt(restoredDb, "SELECT encrypted FROM part WHERE _id = 1"));
      assertArrayEquals(new byte[]{(byte) 0xca, (byte) 0xfe},
              scalarBlob(restoredDb, "SELECT digest FROM part WHERE _id = 1"));
    } finally {
      restoredDb.close();
    }
  }

  @Test public void failedDatabaseUpdateRollsBackPathChangesAndStagedFiles() throws Exception {
    File staging = stagingRoot();
    File sourceParts = new File(staging, "app_parts");
    assertTrue(sourceParts.mkdir());
    File source = attachment(sourceParts, "partRollback.mms", new byte[]{1, 4, 9});
    File dbFile = createStagedDatabase(staging);
    SQLiteDatabase db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
    String legacy = "/data/user/0/org.smssecure.smssecure/app_parts/partRollback.mms";
    db.execSQL("INSERT INTO part (_id, _data) VALUES (1, ?)", new Object[]{legacy});
    db.execSQL("CREATE TRIGGER reject_part_path BEFORE UPDATE ON part " +
            "BEGIN SELECT RAISE(ABORT, 'synthetic path update failure'); END");
    db.close();

    try {
      AttachmentBackupRestorer.prepareStagedRestore(context, staging);
      fail("Expected staged database update failure");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("prepare staged"));
    }
    db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
    try {
      assertEquals(legacy, stringValue(db, "SELECT _data FROM part WHERE _id = 1"));
    } finally {
      db.close();
    }
    assertTrue(source.isFile());
    assertFalse(new File(staging, "restored_parts").exists());
  }

  @Test public void nestedAttachmentDirectoriesAreRejectedWithoutFlattening() throws Exception {
    File staging = stagingRoot();
    File nested = new File(staging, "app_parts/nested");
    assertTrue(nested.mkdirs());
    try {
      AttachmentBackupRestorer.prepareStagedRestore(context, staging);
      fail("Expected nested layout to be rejected");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("Nested"));
    }
    assertTrue(nested.isDirectory());
  }

  @Test public void symbolicLinkSourceIsRejectedWithoutFollowingItsTarget() throws Exception {
    File staging = stagingRoot();
    File sourceParts = new File(staging, "app_parts");
    assertTrue(sourceParts.mkdir());
    File outside = attachment(staging, "outside.mms", new byte[]{6, 7, 8});
    File link = new File(sourceParts, "partSymlink.mms");
    Os.symlink(outside.getAbsolutePath(), link.getAbsolutePath());

    try {
      AttachmentBackupRestorer.prepareStagedRestore(context, staging);
      fail("Expected symbolic link to be rejected");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("regular file"));
    }
    assertArrayEquals(new byte[]{6, 7, 8}, read(outside));
  }

  @Test public void destinationCollisionFailsWithoutOverwritingExistingFile() throws Exception {
    File staging = stagingRoot();
    File sourceParts = new File(staging, "app_parts");
    assertTrue(sourceParts.mkdir());
    attachment(sourceParts, "partCollision.mms", new byte[]{11, 22, 33});
    File dbFile = createStagedDatabase(staging);
    SQLiteDatabase db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
    db.execSQL("INSERT INTO part (_id, _data) VALUES (1, ?)", new Object[]{
            "/data/user/0/org.smssecure.smssecure/app_parts/partCollision.mms"});
    db.close();
    AttachmentBackupRestorer.prepareStagedRestore(context, staging);
    String destinationPath = stringValue(dbFile, "SELECT _data FROM part WHERE _id = 1");
    File destination = new File(destinationPath);
    assertTrue(destination.getParentFile().mkdirs());
    assertTrue(destination.createNewFile());
    byte[] collision = new byte[]{99};
    try (FileOutputStream output = new FileOutputStream(destination)) { output.write(collision); }

    try {
      AttachmentBackupRestorer.publishStagedAttachments(context, staging);
      fail("Expected destination collision to fail closed");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("collision"));
    }
    assertArrayEquals(collision, read(destination));
  }

  private File stagingRoot() {
    File staging = new File(context.getNoBackupFilesDir(), "isolated-stage-" + System.nanoTime());
    assertTrue(staging.mkdir());
    return staging;
  }

  private File createStagedDatabase(File staging) throws Exception {
    File databases = new File(staging, "databases");
    assertTrue(databases.mkdirs());
    File file = new File(databases, "messages.db");
    SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null);
    db.execSQL("CREATE TABLE part (_id INTEGER PRIMARY KEY, _data TEXT, thumbnail TEXT, encrypted INTEGER, digest BLOB)");
    db.close();
    return file;
  }

  private File attachment(File directory, String name, byte[] data) throws Exception {
    File file = new File(directory, name);
    try (FileOutputStream output = new FileOutputStream(file)) { output.write(data); }
    return file;
  }

  private static String stringValue(File database, String sql) throws Exception {
    SQLiteDatabase db = SQLiteDatabase.openDatabase(database.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
    try { return stringValue(db, sql); } finally { db.close(); }
  }

  private static String stringValue(SQLiteDatabase db, String sql) {
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

  private static byte[] scalarBlob(SQLiteDatabase db, String sql) {
    try (Cursor cursor = db.rawQuery(sql, null)) {
      assertTrue(cursor.moveToFirst());
      return cursor.getBlob(0);
    }
  }

  private static Map<String, byte[]> unzip(File archive) throws Exception {
    Map<String, byte[]> result = new HashMap<>();
    try (ZipInputStream input = new ZipInputStream(new FileInputStream(archive))) {
      ZipEntry entry;
      while ((entry = input.getNextEntry()) != null) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        if (!entry.isDirectory()) result.put(entry.getName(), output.toByteArray());
      }
    }
    return result;
  }

  private static void putEntry(ZipOutputStream zip, String name, FileInputStream input) throws Exception {
    zip.putNextEntry(new ZipEntry(name));
    byte[] buffer = new byte[4096];
    int read;
    while ((read = input.read(buffer)) != -1) zip.write(buffer, 0, read);
    zip.closeEntry();
  }

  private static byte[] read(File file) throws Exception {
    try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int read;
      while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
      return output.toByteArray();
    }
  }

  private static void deleteTree(File file) {
    if (file == null) return;
    File[] children = file.listFiles();
    if (children != null) for (File child : children) deleteTree(child);
    file.delete();
  }

  private static final class TestDatabaseHelper extends SQLiteOpenHelper {
    TestDatabaseHelper(Context context) { super(context, "messages.db", null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
      db.execSQL("CREATE TABLE part (_id INTEGER PRIMARY KEY, _data TEXT, thumbnail TEXT, encrypted INTEGER, digest BLOB)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}
  }
}
