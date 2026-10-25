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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.system.Os;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.jimvixx.smsecure.crypto.EncryptingPartOutputStream;
import org.jimvixx.smsecure.crypto.MasterSecret;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@RunWith(AndroidJUnit4.class)
public final class AuthenticatedMmsAttachmentAccessTest {
  private static final long MMS_ID = 5001;
  private static final long PART_ID = 7001;
  private static final long UNIQUE_ID = 9001;
  private static final long MAX_ATTACHMENT_BYTES = 512L * 1024L * 1024L;

  private Context baseContext;
  private Context fileContext;
  private File testRoot;
  private File partsDirectory;
  private String fixturePrefix;
  private final List<File> fixturePartFiles = new ArrayList<>();
  private File databaseFile;
  private SQLiteOpenHelper helper;
  private AttachmentDatabase attachments;
  private MasterSecret masterSecret;

  @Before public void setUp() throws Exception {
    baseContext = ApplicationProvider.getApplicationContext();
    testRoot = new File(baseContext.getCacheDir(), "mms-access-" + System.nanoTime());
    File cache = new File(testRoot, "cache");
    partsDirectory = baseContext.getDir("parts", Context.MODE_PRIVATE).getCanonicalFile();
    fixturePrefix = Long.toHexString(System.nanoTime());
    assertTrue(testRoot.mkdirs());
    assertTrue(cache.mkdirs());
    fileContext = new ContextWrapper(baseContext) {
      @Override public File getDir(String name, int mode) {
        return "parts".equals(name) ? partsDirectory : super.getDir(name, mode);
      }
      @Override public File getCacheDir() { return cache; }
      @Override public Context getApplicationContext() { return this; }
    };

    databaseFile = new File(testRoot, "isolated-mms.db");
    helper = isolatedHelper();
    SQLiteDatabase database = helper.getWritableDatabase();
    database.execSQL("INSERT INTO mms (_id, thread_id, date, date_received, msg_box, read, " +
            "m_type, body, address, part_count) VALUES (?, 1, 100, 100, 1, 0, 132, 'synthetic', '+15550001', 1)",
            new Object[]{MMS_ID});
    database.execSQL("INSERT INTO mms (_id, thread_id, date, date_received, msg_box, read, " +
            "m_type, body, address, part_count) VALUES (5002, 2, 200, 200, 1, 0, 132, 'unrelated', '+15550002', 1)");
    database.execSQL("INSERT INTO sms (_id, thread_id, date, date_sent, read, status, type, body, address) " +
            "VALUES (5001, 1, 100, 100, 1, 0, 20, 'same-id-sms', '+15550001')");
    attachments = new AttachmentDatabase(fileContext, helper);
    masterSecret = syntheticSecret(4);
  }

  @After public void tearDown() throws Exception {
    if (helper != null) helper.close();
    for (File file : fixturePartFiles) {
      if (file.exists()) assertTrue("Could not remove synthetic fixture entry", file.delete());
    }
    if (testRoot != null) deleteTree(testRoot);
  }

  @Test public void opensDataAndThumbnailOnlyAfterAuthenticationWithoutChangingMetadata() throws Exception {
    byte[] original = "synthetic original attachment".getBytes(StandardCharsets.UTF_8);
    byte[] thumbnail = new byte[]{9, 8, 7, 6};
    File dataFile = writeLegacyPart("partdata1.mms", original);
    File thumbnailFile = writeLegacyPart("partthumb1.mms", thumbnail);
    insertPart(dataFile, thumbnailFile, 0, original.length);
    byte[] dataCiphertextBefore = readFile(dataFile);
    byte[] thumbnailCiphertextBefore = readFile(thumbnailFile);
    int snapshotsBefore = ciphertextSnapshotCount();

    AttachmentDatabase.AttachmentAccessResult data = attachments.openPart(MMS_ID, PART_ID,
            UNIQUE_ID, AttachmentDatabase.FileKind.DATA, masterSecret);
    assertEquals(AttachmentDatabase.AccessStatus.OPENED, data.status);
    assertNotNull(data.attachment);
    assertEquals("image/jpeg", data.attachment.contentType);
    assertEquals(AttachmentDatabase.FileKind.DATA, data.attachment.fileKind);
    try (AttachmentDatabase.OpenedAttachment handle = data.attachment;
         InputStream input = handle.openStream()) {
      assertArrayEquals(original, readAll(input));
    }

    AttachmentDatabase.AttachmentAccessResult thumb = attachments.openPart(MMS_ID, PART_ID,
            UNIQUE_ID, AttachmentDatabase.FileKind.THUMBNAIL, masterSecret);
    assertEquals(AttachmentDatabase.AccessStatus.OPENED, thumb.status);
    assertEquals(AttachmentDatabase.FileKind.THUMBNAIL, thumb.attachment.fileKind);
    try (AttachmentDatabase.OpenedAttachment handle = thumb.attachment;
         InputStream input = handle.openStream()) {
      assertArrayEquals(thumbnail, readAll(input));
    }

    assertArrayEquals(dataCiphertextBefore, readFile(dataFile));
    assertArrayEquals(thumbnailCiphertextBefore, readFile(thumbnailFile));
    assertEquals(snapshotsBefore, ciphertextSnapshotCount());
    assertPartMetadata(dataFile, thumbnailFile, original.length);
    assertEquals(1, count("SELECT COUNT(*) FROM sms WHERE _id = 5001"));
    assertEquals(1, count("SELECT COUNT(*) FROM mms WHERE _id = 5002"));
    assertEquals(1, count("SELECT COUNT(*) FROM part WHERE _id = " + PART_ID));
  }

  @Test public void wrongKeyModifiedCiphertextAndTruncationFailClosed() throws Exception {
    File dataFile = writePart("partbadkey1.mms", new byte[]{1, 2, 3, 4});
    File thumbFile = writePart("partbadkey2.mms", new byte[]{5, 6, 7});
    insertPart(dataFile, thumbFile, 1, 4);

    assertStatus(attachments.openPart(MMS_ID, PART_ID, UNIQUE_ID, AttachmentDatabase.FileKind.DATA,
            syntheticSecret(55)), AttachmentDatabase.AccessStatus.AUTHENTICATION_FAILED);

    byte[] modified = readFile(dataFile);
    modified[modified.length - 1] ^= 0x20;
    writeFile(dataFile, modified);
    assertStatus(attachments.openPart(MMS_ID, PART_ID, UNIQUE_ID, AttachmentDatabase.FileKind.DATA,
            masterSecret), AttachmentDatabase.AccessStatus.AUTHENTICATION_FAILED);

    byte[] valid = encrypt(new byte[]{11, 12, 13});
    writeFile(dataFile, Arrays.copyOf(valid, valid.length - 1));
    assertStatus(attachments.openPart(MMS_ID, PART_ID, UNIQUE_ID, AttachmentDatabase.FileKind.DATA,
            masterSecret), AttachmentDatabase.AccessStatus.AUTHENTICATION_FAILED);
    assertEquals(1, count("SELECT COUNT(*) FROM part WHERE _id = " + PART_ID));
  }

  @Test public void missingKeyMissingFileAndIdentityMismatchHaveDistinctResults() throws Exception {
    File missing = fixturePartFile("partmissing1.mms");
    insertPart(missing, null, 1, 3);
    assertStatus(attachments.openPart(MMS_ID, PART_ID, UNIQUE_ID, AttachmentDatabase.FileKind.DATA,
            null), AttachmentDatabase.AccessStatus.MISSING_MASTER_KEY);
    assertStatus(attachments.openPart(MMS_ID, PART_ID, UNIQUE_ID, AttachmentDatabase.FileKind.DATA,
            masterSecret), AttachmentDatabase.AccessStatus.MISSING_FILE);
    assertStatus(attachments.openPart(MMS_ID, PART_ID, UNIQUE_ID,
            AttachmentDatabase.FileKind.THUMBNAIL, masterSecret),
            AttachmentDatabase.AccessStatus.MISSING_FILE);
    assertStatus(attachments.openPart(MMS_ID + 1, PART_ID, UNIQUE_ID,
            AttachmentDatabase.FileKind.DATA, masterSecret), AttachmentDatabase.AccessStatus.NOT_FOUND);
    assertStatus(attachments.openPart(MMS_ID, PART_ID, UNIQUE_ID + 1,
            AttachmentDatabase.FileKind.DATA, masterSecret), AttachmentDatabase.AccessStatus.NOT_FOUND);
    assertStatus(attachments.openPart(MMS_ID, PART_ID + 1, UNIQUE_ID,
            AttachmentDatabase.FileKind.DATA, masterSecret), AttachmentDatabase.AccessStatus.NOT_FOUND);
  }

  @Test public void externalTraversalUriSymlinkAndOversizeReferencesAreRejected() throws Exception {
    File outside = new File(testRoot, "outside.mms");
    writeEncrypted(outside, new byte[]{1, 2});
    insertPart(outside, null, 1, 2);
    assertStatus(attachments.openPart(MMS_ID, PART_ID, UNIQUE_ID, AttachmentDatabase.FileKind.DATA,
            masterSecret), AttachmentDatabase.AccessStatus.INVALID_PATH);

    File parts = partsDirectory;
    File inside = writePart("partinside1.mms", new byte[]{1, 2});
    updatePath(inside.getAbsolutePath() + "/../outside.mms", null, 2);
    assertStatus(attachments.openPart(MMS_ID, PART_ID, UNIQUE_ID, AttachmentDatabase.FileKind.DATA,
            masterSecret), AttachmentDatabase.AccessStatus.INVALID_PATH);
    updatePath("content://synthetic/part", null, 2);
    assertStatus(attachments.openPart(MMS_ID, PART_ID, UNIQUE_ID, AttachmentDatabase.FileKind.DATA,
            masterSecret), AttachmentDatabase.AccessStatus.INVALID_PATH);

    File symlink = fixturePartFile("partsymlink1.mms");
    fixturePartFiles.add(symlink);
    Os.symlink(outside.getAbsolutePath(), symlink.getAbsolutePath());
    updatePath(symlink.getAbsolutePath(), null, 2);
    assertStatus(attachments.openPart(MMS_ID, PART_ID, UNIQUE_ID, AttachmentDatabase.FileKind.DATA,
            masterSecret), AttachmentDatabase.AccessStatus.INVALID_PATH);

    updatePath(inside.getAbsolutePath(), null, MAX_ATTACHMENT_BYTES + 1);
    assertStatus(attachments.openPart(MMS_ID, PART_ID, UNIQUE_ID, AttachmentDatabase.FileKind.DATA,
            masterSecret), AttachmentDatabase.AccessStatus.RESOURCE_LIMIT_EXCEEDED);

    File oversizedThumbnail = fixturePartFile("partoversized1.mms");
    fixturePartFiles.add(oversizedThumbnail);
    try (RandomAccessFile output = new RandomAccessFile(oversizedThumbnail, "rw")) {
      output.setLength(32L * 1024L * 1024L + 53L);
    }
    updatePath(inside.getAbsolutePath(), oversizedThumbnail.getAbsolutePath(), 2);
    assertStatus(attachments.openPart(MMS_ID, PART_ID, UNIQUE_ID,
            AttachmentDatabase.FileKind.THUMBNAIL, masterSecret),
            AttachmentDatabase.AccessStatus.RESOURCE_LIMIT_EXCEEDED);

    ContentValues invalidType = new ContentValues();
    invalidType.put("ct", "not-a-mime-type");
    helper.getWritableDatabase().update("part", invalidType, "_id = ?",
            new String[]{String.valueOf(PART_ID)});
    assertStatus(attachments.openPart(MMS_ID, PART_ID, UNIQUE_ID, AttachmentDatabase.FileKind.DATA,
            masterSecret), AttachmentDatabase.AccessStatus.UNSUPPORTED_METADATA);

    File directory = fixturePartFile("partdirectory1.mms");
    fixturePartFiles.add(directory);
    assertTrue(directory.mkdir());
    ContentValues directoryReference = new ContentValues();
    directoryReference.put("ct", "image/jpeg");
    directoryReference.put("_data", directory.getAbsolutePath());
    directoryReference.put("data_size", 2);
    helper.getWritableDatabase().update("part", directoryReference, "_id = ?",
            new String[]{String.valueOf(PART_ID)});
    assertStatus(attachments.openPart(MMS_ID, PART_ID, UNIQUE_ID, AttachmentDatabase.FileKind.DATA,
            masterSecret), AttachmentDatabase.AccessStatus.INVALID_PATH);
    assertEquals(1, count("SELECT COUNT(*) FROM mms WHERE _id = " + MMS_ID));
  }

  @Test public void lifecycleLockCoversSnapshotButNotAuthenticatedConsumption() throws Exception {
    byte[] payload = new byte[]{31, 32, 33};
    File dataFile = writePart("partlock1.mms", payload);
    insertPart(dataFile, null, 0, payload.length);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      CountDownLatch acquired = new CountDownLatch(1);
      AttachmentDatabase.AttachmentAccessResult[] result = new AttachmentDatabase.AttachmentAccessResult[1];
      Future<?> future;
      synchronized (AttachmentCleanupProcessor.lifecycleLock()) {
        future = executor.submit(() -> result[0] = attachments.openPart(MMS_ID, PART_ID, UNIQUE_ID,
                AttachmentDatabase.FileKind.DATA, masterSecret));
        Thread.sleep(100);
        assertFalse(future.isDone());
      }
      future.get(10, TimeUnit.SECONDS);
      assertEquals(AttachmentDatabase.AccessStatus.OPENED, result[0].status);
      executor.submit(() -> {
        synchronized (AttachmentCleanupProcessor.lifecycleLock()) {
          acquired.countDown();
        }
      });
      assertTrue(acquired.await(5, TimeUnit.SECONDS));
      try (AttachmentDatabase.OpenedAttachment handle = result[0].attachment;
           InputStream input = handle.openStream()) {
        assertArrayEquals(payload, readAll(input));
      }
    } finally {
      executor.shutdownNow();
    }
  }

  private void insertPart(File data, File thumbnail, int encrypted, long dataSize) {
    ContentValues values = new ContentValues();
    values.put("_id", PART_ID);
    values.put("mid", MMS_ID);
    values.put("seq", 0);
    values.put("ct", "image/jpeg");
    values.put("_data", data.getAbsolutePath());
    values.put("data_size", dataSize);
    if (thumbnail != null) values.put("thumbnail", thumbnail.getAbsolutePath());
    values.put("unique_id", UNIQUE_ID);
    values.put("digest", new byte[]{4, 5, 6});
    values.put("encrypted", encrypted);
    helper.getWritableDatabase().insertOrThrow("part", null, values);
  }

  private void updatePath(String path, String thumbnail, long dataSize) {
    ContentValues values = new ContentValues();
    values.put("_data", path);
    values.put("thumbnail", thumbnail);
    values.put("data_size", dataSize);
    helper.getWritableDatabase().update("part", values, "_id = ?",
            new String[]{String.valueOf(PART_ID)});
  }

  private File writePart(String name, byte[] plaintext) throws Exception {
    File file = fixturePartFile(name);
    fixturePartFiles.add(file);
    writeEncrypted(file, plaintext);
    return file;
  }

  private File writeLegacyPart(String name, byte[] plaintext) throws Exception {
    File file = fixturePartFile(name);
    fixturePartFiles.add(file);
    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(Cipher.ENCRYPT_MODE, masterSecret.getEncryptionKey());
    byte[] iv = cipher.getIV();
    byte[] ciphertext = cipher.doFinal(plaintext);
    Mac mac = Mac.getInstance("HmacSHA1");
    mac.init(masterSecret.getMacKey());
    mac.update(iv);
    mac.update(ciphertext);
    try (FileOutputStream output = new FileOutputStream(file)) {
      output.write(iv);
      output.write(ciphertext);
      output.write(mac.doFinal());
    }
    return file;
  }

  private File fixturePartFile(String name) {
    String suffix = name.startsWith("part") ? name.substring(4) : name;
    File file = new File(partsDirectory, "part" + fixturePrefix + suffix);
    assertEquals(partsDirectory.getAbsolutePath(), file.getAbsoluteFile().getParent());
    return file;
  }

  private void writeEncrypted(File file, byte[] plaintext) throws Exception {
    try (EncryptingPartOutputStream output = new EncryptingPartOutputStream(file, masterSecret)) {
      output.write(plaintext);
    }
  }

  private byte[] encrypt(byte[] plaintext) throws Exception {
    File file = new File(testRoot, "temporary-encrypted.mms");
    writeEncrypted(file, plaintext);
    byte[] encrypted = readFile(file);
    assertTrue(file.delete());
    return encrypted;
  }

  private void assertPartMetadata(File path, File thumbnail, long dataSize) {
    try (Cursor cursor = helper.getReadableDatabase().query("part",
            new String[]{"_data", "thumbnail", "data_size", "unique_id", "digest", "encrypted"},
            "_id = ?", new String[]{String.valueOf(PART_ID)}, null, null, null)) {
      assertTrue(cursor.moveToFirst());
      assertEquals(path.getAbsolutePath(), cursor.getString(0));
      assertEquals(thumbnail.getAbsolutePath(), cursor.getString(1));
      assertEquals(dataSize, cursor.getLong(2));
      assertEquals(UNIQUE_ID, cursor.getLong(3));
      assertArrayEquals(new byte[]{4, 5, 6}, cursor.getBlob(4));
      assertEquals(0, cursor.getInt(5));
    }
  }

  private int count(String sql) {
    try (Cursor cursor = helper.getReadableDatabase().rawQuery(sql, null)) {
      assertTrue(cursor.moveToFirst());
      return cursor.getInt(0);
    }
  }

  private int ciphertextSnapshotCount() {
    File[] files = new File(testRoot, "cache").listFiles(
            (directory, name) -> name.startsWith("mms-ciphertext-"));
    return files == null ? 0 : files.length;
  }

  private void assertStatus(AttachmentDatabase.AttachmentAccessResult result,
                            AttachmentDatabase.AccessStatus expected) {
    assertEquals(expected, result.status);
    assertEquals(expected == AttachmentDatabase.AccessStatus.OPENED, result.attachment != null);
  }

  private SQLiteOpenHelper isolatedHelper() throws Exception {
    Class<?> helperClass = DatabaseFactory.class.getDeclaredClasses()[0];
    Constructor<?> constructor = helperClass.getDeclaredConstructor(Context.class, String.class,
            SQLiteDatabase.CursorFactory.class, int.class);
    constructor.setAccessible(true);
    return (SQLiteOpenHelper) constructor.newInstance(baseContext, databaseFile.getPath(), null, 36);
  }

  private MasterSecret syntheticSecret(int seed) {
    byte[] encryption = new byte[16];
    byte[] mac = new byte[20];
    for (int i = 0; i < encryption.length; i++) encryption[i] = (byte) (seed + i);
    for (int i = 0; i < mac.length; i++) mac[i] = (byte) (seed + 50 + i);
    return new MasterSecret(new SecretKeySpec(encryption, "AES"),
            new SecretKeySpec(mac, "HmacSHA1"));
  }

  private static byte[] readAll(InputStream input) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[128];
    int read;
    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
    return output.toByteArray();
  }

  private static byte[] readFile(File file) throws Exception {
    try (InputStream input = new java.io.FileInputStream(file)) {
      return readAll(input);
    }
  }

  private static void writeFile(File file, byte[] bytes) throws Exception {
    try (FileOutputStream output = new FileOutputStream(file, false)) {
      output.write(bytes);
    }
  }

  private static void deleteTree(File file) {
    File[] children = file.listFiles();
    if (children != null) for (File child : children) deleteTree(child);
    file.delete();
  }
}
