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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertArrayEquals;

import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.DisplayMetrics;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.jimvixx.smsecure.MmsImageThumbnailLoader;
import org.jimvixx.smsecure.MmsImageViewerLoader;
import org.jimvixx.smsecure.crypto.EncryptingPartOutputStream;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;

import javax.crypto.spec.SecretKeySpec;

@RunWith(AndroidJUnit4.class)
public final class MmsImageThumbnailLoaderTest {
  private static final long MMS_ID = 83001;
  private static final int PREVIEW_INPUT_LIMIT = 8 * 1024 * 1024;
  private static final int VIEWER_INPUT_LIMIT = 16 * 1024 * 1024;

  private Context baseContext;
  private Context fileContext;
  private File testRoot;
  private File cacheDirectory;
  private File partsDirectory;
  private SQLiteOpenHelper helper;
  private AttachmentDatabase attachmentDatabase;
  private MasterSecret masterSecret;
  private MmsImageThumbnailLoader loader;
  private MmsImageViewerLoader viewerLoader;
  private final List<File> fixtureFiles = new ArrayList<>();

  @Before public void setUp() throws Exception {
    baseContext = ApplicationProvider.getApplicationContext();
    testRoot = new File(baseContext.getCacheDir(), "mms-image-loader-" + UUID.randomUUID());
    cacheDirectory = new File(testRoot, "cache");
    assertTrue(cacheDirectory.mkdirs());
    partsDirectory = baseContext.getDir("parts", Context.MODE_PRIVATE).getCanonicalFile();
    fileContext = new ContextWrapper(baseContext) {
      @Override public File getDir(String name, int mode) {
        return "parts".equals(name) ? partsDirectory : super.getDir(name, mode);
      }
      @Override public File getCacheDir() { return cacheDirectory; }
      @Override public Context getApplicationContext() { return this; }
    };
    helper = isolatedHelper(new File(testRoot, "isolated.db"));
    SQLiteDatabase database = helper.getWritableDatabase();
    database.execSQL("INSERT INTO mms (_id, thread_id, date, date_received, msg_box, read, " +
            "m_type, body, address, part_count) VALUES (?, 1, 100, 100, 1, 1, 132, 'synthetic', '+15550001', 0)",
            new Object[]{MMS_ID});
    masterSecret = syntheticSecret(13);
    attachmentDatabase = new AttachmentDatabase(fileContext, helper);
    DisplayMetrics metrics = baseContext.getResources().getDisplayMetrics();
    loader = new MmsImageThumbnailLoader(attachmentDatabase, metrics);
    viewerLoader = new MmsImageViewerLoader(attachmentDatabase, metrics);
  }

  @After public void tearDown() {
    if (helper != null) helper.close();
    for (File file : fixtureFiles) {
      if (file.exists()) assertTrue("Could not remove synthetic MMS image", file.delete());
    }
    deleteTree(testRoot);
  }

  @Test public void loadsMultipleThumbnailsAndFallsBackToOriginalWhenThumbnailIsMissing()
          throws Exception {
    byte[] original = createPng(48, 32, Color.RED);
    byte[] thumbnail = createPng(16, 12, Color.BLUE);
    addImagePart(1, "image/png", original, thumbnail);
    addImagePart(2, "image/jpeg", createPng(64, 48, Color.GREEN), null);

    MmsImageThumbnailLoader.LoadResult result = await(MMS_ID, masterSecret);
    try {
      assertEquals(2, result.previews.size());
      assertFalse(result.hasOtherAttachments);
      Bitmap first = result.previews.get(0).takeBitmap();
      Bitmap second = result.previews.get(1).takeBitmap();
      assertNotNull(first);
      assertNotNull(second);
      assertEquals(16, first.getWidth());
      assertEquals(12, first.getHeight());
      assertEquals(64, second.getWidth());
      assertEquals(48, second.getHeight());
      eraseAndRecycle(first);
      eraseAndRecycle(second);
    } finally {
      result.close();
    }
  }

  @Test public void viewerOpensTheSelectedImageFromMultiplePartsWithoutChangingSource()
          throws Exception {
    addImagePart(1, "image/png", createPng(24, 16, Color.RED), null);
    File selectedFile = addImagePart(2, "image/png", createPng(40, 24, Color.GREEN), null);
    byte[] originalDigest = digestFile(selectedFile);

    MmsImageViewerLoader.Result result = awaitViewer(2, 20, masterSecret);
    try {
      assertEquals(MmsImageViewerLoader.Status.OPENED, result.status);
      Bitmap bitmap = result.takeBitmap();
      assertNotNull(bitmap);
      assertTrue(bitmap.isMutable());
      assertEquals(Color.GREEN, bitmap.getPixel(bitmap.getWidth() / 2, bitmap.getHeight() / 2));
      eraseAndRecycle(bitmap);
      assertArrayEquals(originalDigest, digestFile(selectedFile));
    } finally {
      result.close();
      Arrays.fill(originalDigest, (byte) 0);
    }
  }

  @Test public void viewerRejectsWrongKeysInvalidHmacAndMalformedImages() throws Exception {
    File encrypted = addImagePart(1, "image/png", createPng(20, 20, Color.BLUE), null);
    addImagePart(2, "image/png", new byte[]{1, 2, 3, 4}, null);

    MmsImageViewerLoader.Result wrongKey = awaitViewer(1, 10, syntheticSecret(91));
    try {
      assertEquals(MmsImageViewerLoader.Status.AUTHENTICATION_FAILED, wrongKey.status);
      assertNull(wrongKey.takeBitmap());
    } finally {
      wrongKey.close();
    }

    try (RandomAccessFile file = new RandomAccessFile(encrypted, "rw")) {
      long lastByte = file.length() - 1;
      file.seek(lastByte);
      int value = file.read();
      file.seek(lastByte);
      file.write(value ^ 0x01);
    }
    MmsImageViewerLoader.Result invalidHmac = awaitViewer(1, 10, masterSecret);
    try {
      assertEquals(MmsImageViewerLoader.Status.AUTHENTICATION_FAILED, invalidHmac.status);
      assertNull(invalidHmac.takeBitmap());
    } finally {
      invalidHmac.close();
    }

    MmsImageViewerLoader.Result malformed = awaitViewer(2, 20, masterSecret);
    try {
      assertEquals(MmsImageViewerLoader.Status.INVALID_IMAGE, malformed.status);
      assertNull(malformed.takeBitmap());
    } finally {
      malformed.close();
    }
  }

  @Test public void viewerRejectsOversizedImageWithinThePreviewSpecificBudget() throws Exception {
    File oversized = writeEncryptedPartOfLength("viewer-oversized",
            VIEWER_INPUT_LIMIT + 1L);
    insertPart(1, "image/png", oversized, null,
            VIEWER_INPUT_LIMIT + 1L);
    byte[] originalDigest = digestFile(oversized);

    MmsImageViewerLoader.Result result = awaitViewer(1, 10, masterSecret);
    try {
      assertEquals(MmsImageViewerLoader.Status.RESOURCE_LIMIT_EXCEEDED, result.status);
      assertNull(result.takeBitmap());
      assertEquals(0, ciphertextSnapshotCount());
      assertArrayEquals(originalDigest, digestFile(oversized));
    } finally {
      result.close();
      Arrays.fill(originalDigest, (byte) 0);
    }
  }

  @Test public void cancellingViewerLoadPreventsDelivery() throws Exception {
    addImagePart(1, "image/png", createPng(24, 16, Color.YELLOW), null);
    CountDownLatch delivered = new CountDownLatch(1);
    synchronized (AttachmentCleanupProcessor.lifecycleLock()) {
      MmsImageViewerLoader.Request request = viewerLoader.load(MMS_ID, 1, 10, masterSecret,
              result -> {
                result.close();
                delivered.countDown();
              });
      request.cancel();
    }
    assertFalse("Cancelled image load must not deliver", delivered.await(300, TimeUnit.MILLISECONDS));
  }

  @Test public void corruptImagesWrongKeysAndUnsupportedMimeRemainPlaceholders() throws Exception {
    addImagePart(1, "image/png", new byte[]{1, 2, 3, 4}, null);
    addImagePart(2, "image/jpeg", createPng(20, 20, Color.MAGENTA), null);
    addMetadataOnlyPart(3, "application/pdf");
    addMetadataOnlyPart(4, "image/png");

    MmsImageThumbnailLoader.LoadResult wrongKey = await(MMS_ID, syntheticSecret(77));
    try {
      assertEquals(3, wrongKey.previews.size());
      assertNull(wrongKey.previews.get(0).takeBitmap());
      assertNull(wrongKey.previews.get(1).takeBitmap());
      assertNull(wrongKey.previews.get(2).takeBitmap());
      assertTrue(wrongKey.hasOtherAttachments);
    } finally {
      wrongKey.close();
    }

    MmsImageThumbnailLoader.LoadResult corrupt = await(MMS_ID, masterSecret);
    try {
      assertEquals(3, corrupt.previews.size());
      assertNull(corrupt.previews.get(0).takeBitmap());
      Bitmap decoded = corrupt.previews.get(1).takeBitmap();
      assertNotNull(decoded);
      eraseAndRecycle(decoded);
      assertNull(corrupt.previews.get(2).takeBitmap());
      assertTrue(corrupt.hasOtherAttachments);
    } finally {
      corrupt.close();
    }

    MmsImageThumbnailLoader.LoadResult missingKey = await(MMS_ID, null);
    try {
      assertNull(missingKey.previews.get(0).takeBitmap());
      assertNull(missingKey.previews.get(1).takeBitmap());
      assertNull(missingKey.previews.get(2).takeBitmap());
    } finally {
      missingKey.close();
    }
  }

  @Test public void oversizedImageDimensionsAreRejectedBeforeBitmapAllocation() throws Exception {
    addImagePart(1, "image/png", pngHeaderOnly(50_000, 50_000), null);
    MmsImageThumbnailLoader.LoadResult result = await(MMS_ID, masterSecret);
    try {
      assertEquals(1, result.previews.size());
      assertNull(result.previews.get(0).takeBitmap());
    } finally {
      result.close();
    }
  }

  @Test public void invalidHmacDoesNotProduceAnImagePreview() throws Exception {
    File encrypted = addImagePart(1, "image/png", createPng(20, 20, Color.RED), null);
    try (RandomAccessFile file = new RandomAccessFile(encrypted, "rw")) {
      long lastByte = file.length() - 1;
      file.seek(lastByte);
      int value = file.read();
      file.seek(lastByte);
      file.write(value ^ 0x01);
    }

    MmsImageThumbnailLoader.LoadResult result = await(MMS_ID, masterSecret);
    try {
      assertEquals(1, result.previews.size());
      assertNull(result.previews.get(0).takeBitmap());
    } finally {
      result.close();
    }
  }

  @Test public void decodedImageDimensionsAreSampledToPreviewBounds() throws Exception {
    addImagePart(1, "image/png", createPng(1600, 1200, Color.CYAN), null);
    MmsImageThumbnailLoader.LoadResult result = await(MMS_ID, masterSecret);
    try {
      Bitmap bitmap = result.previews.get(0).takeBitmap();
      assertNotNull(bitmap);
      assertTrue("Decrypted preview must be mutable for pixel erasure", bitmap.isMutable());
      assertTrue(bitmap.getWidth() <= 640);
      assertTrue(bitmap.getHeight() <= 480);
      eraseAndRecycle(bitmap);
    } finally {
      result.close();
    }
  }

  @Test public void resultCleanupIsIdempotentAndClearsOwnedPreview() throws Exception {
    addImagePart(1, "image/png", createPng(32, 24, Color.BLUE), null);
    MmsImageThumbnailLoader.LoadResult result = await(MMS_ID, masterSecret);
    assertEquals(1, result.previews.size());

    result.close();
    result.close();

    assertNull(result.previews.get(0).takeBitmap());
  }

  @Test public void compressedImageInputIsBounded() throws Exception {
    byte[] oversized = new byte[PREVIEW_INPUT_LIMIT + 1];
    addImagePart(1, "image/png", oversized, null);
    Arrays.fill(oversized, (byte) 0);

    MmsImageThumbnailLoader.LoadResult result = await(MMS_ID, masterSecret);
    try {
      assertEquals(1, result.previews.size());
      assertNull(result.previews.get(0).takeBitmap());
    } finally {
      result.close();
    }
  }

  @Test public void previewLimitHandlesEncryptionBoundaryAndMisleadingPartSize()
          throws Exception {
    File belowLimit = writeEncryptedPartOfLength("below-limit", PREVIEW_INPUT_LIMIT - 1L);
    insertPart(1, "image/png", belowLimit, null, PREVIEW_INPUT_LIMIT - 1L);
    AttachmentDatabase.AttachmentAccessResult below = attachmentDatabase.openPart(MMS_ID, 1,
            10, AttachmentDatabase.FileKind.DATA, masterSecret, PREVIEW_INPUT_LIMIT);
    assertEquals(AttachmentDatabase.AccessStatus.OPENED, below.status);
    assertNotNull(below.attachment);
    below.attachment.close();

    File aboveLimit = writeEncryptedPartOfLength("above-limit", PREVIEW_INPUT_LIMIT + 1L);
    insertPart(2, "image/png", aboveLimit, null, 0L);
    AttachmentDatabase.AttachmentAccessResult above = attachmentDatabase.openPart(MMS_ID, 2,
            20, AttachmentDatabase.FileKind.DATA, masterSecret, PREVIEW_INPUT_LIMIT);
    assertEquals(AttachmentDatabase.AccessStatus.RESOURCE_LIMIT_EXCEEDED, above.status);
    assertNull(above.attachment);
    assertEquals(0, ciphertextSnapshotCount());
  }

  @Test public void oversizedMissingThumbnailFallbackIsRejectedBeforeSnapshot()
          throws Exception {
    File oversizedOriginal = writeEncryptedPartOfLength("oversized-original",
            PREVIEW_INPUT_LIMIT + 64 * 1024L);
    insertPart(1, "image/png", oversizedOriginal, null, 1L);
    byte[] originalDigest = digestFile(oversizedOriginal);
    File unrelated = new File(cacheDirectory, "unrelated-marker.bin");
    byte[] unrelatedBytes = new byte[]{3, 1, 4, 1, 5, 9};
    assertTrue(writeFile(unrelated, unrelatedBytes));

    AttachmentDatabase.AttachmentAccessResult direct = attachmentDatabase.openPart(MMS_ID, 1,
            10, AttachmentDatabase.FileKind.DATA, masterSecret, PREVIEW_INPUT_LIMIT);
    assertEquals(AttachmentDatabase.AccessStatus.RESOURCE_LIMIT_EXCEEDED, direct.status);
    assertNull(direct.attachment);

    MmsImageThumbnailLoader.LoadResult result = await(MMS_ID, masterSecret);
    try {
      assertEquals(1, result.previews.size());
      assertNull(result.previews.get(0).takeBitmap());
    } finally {
      result.close();
    }

    try (android.database.Cursor cursor = helper.getReadableDatabase().rawQuery(
            "SELECT _data, data_size FROM part WHERE _id = 1 AND mid = ?",
            new String[]{String.valueOf(MMS_ID)})) {
      assertTrue(cursor.moveToFirst());
      assertEquals(oversizedOriginal.getAbsolutePath(), cursor.getString(0));
      assertEquals(1L, cursor.getLong(1));
    }
    assertTrue(oversizedOriginal.isFile());
    assertTrue(Arrays.equals(originalDigest, digestFile(oversizedOriginal)));
    assertTrue(Arrays.equals(unrelatedBytes, readFile(unrelated)));
    assertEquals(0, ciphertextSnapshotCount());
  }

  @Test public void cancelledRequestDoesNotDeliverStaleResult() throws Exception {
    addImagePart(1, "image/png", createPng(40, 30, Color.YELLOW), null);
    CountDownLatch delivered = new CountDownLatch(1);
    synchronized (AttachmentCleanupProcessor.lifecycleLock()) {
      MmsImageThumbnailLoader.Request request = loader.load(MMS_ID, masterSecret, result -> {
        result.close();
        delivered.countDown();
      });
      Thread.sleep(100);
      request.cancel();
    }
    assertFalse(delivered.await(500, TimeUnit.MILLISECONDS));
  }

  private MmsImageThumbnailLoader.LoadResult await(long mmsId, MasterSecret secret)
          throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<MmsImageThumbnailLoader.LoadResult> result = new AtomicReference<>();
    loader.load(mmsId, secret, loaded -> {
      result.set(loaded);
      latch.countDown();
    });
    assertTrue("Image loader did not finish", latch.await(15, TimeUnit.SECONDS));
    assertNotNull(result.get());
    return result.get();
  }

  private MmsImageViewerLoader.Result awaitViewer(long partId, long uniqueId, MasterSecret secret)
          throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<MmsImageViewerLoader.Result> result = new AtomicReference<>();
    viewerLoader.load(MMS_ID, partId, uniqueId, secret, loaded -> {
      result.set(loaded);
      latch.countDown();
    });
    assertTrue("MMS viewer loader did not finish", latch.await(15, TimeUnit.SECONDS));
    assertNotNull(result.get());
    return result.get();
  }

  private File addImagePart(long partId, String contentType, byte[] original, byte[] thumbnail)
          throws Exception {
    File dataFile = writeEncryptedPart("data" + partId, original);
    File thumbnailFile = thumbnail == null ? null : writeEncryptedPart("thumb" + partId, thumbnail);
    insertPart(partId, contentType, dataFile, thumbnailFile, original.length);
    return dataFile;
  }

  private void addMetadataOnlyPart(long partId, String contentType) {
    insertPart(partId, contentType, null, null, 0);
  }

  private void insertPart(long id, String contentType, File data, File thumbnail, long dataSize) {
    ContentValues values = new ContentValues();
    values.put("_id", id);
    values.put("mid", MMS_ID);
    values.put("seq", id);
    values.put("ct", contentType);
    if (data != null) values.put("_data", data.getAbsolutePath());
    values.put("data_size", dataSize);
    if (thumbnail != null) values.put("thumbnail", thumbnail.getAbsolutePath());
    values.put("unique_id", id * 10);
    values.put("digest", new byte[]{1, 3, 5});
    values.put("encrypted", 0);
    helper.getWritableDatabase().insertOrThrow("part", null, values);
  }

  private File writeEncryptedPart(String suffix, byte[] plaintext) throws Exception {
    File file = new File(partsDirectory,
            "part" + UUID.randomUUID().toString().replace("-", "") + suffix + ".mms");
    fixtureFiles.add(file);
    try (EncryptingPartOutputStream output = new EncryptingPartOutputStream(file, masterSecret)) {
      output.write(plaintext);
    }
    return file;
  }

  private File writeEncryptedPartOfLength(String suffix, long length) throws Exception {
    String safeSuffix = suffix.replaceAll("[^A-Za-z0-9]", "");
    File file = new File(partsDirectory,
            "part" + UUID.randomUUID().toString().replace("-", "") + safeSuffix + ".mms");
    assertEquals(partsDirectory.getCanonicalPath(), file.getParentFile().getCanonicalPath());
    assertTrue(file.getName().matches("part[A-Za-z0-9]+\\.mms"));
    fixtureFiles.add(file);
    byte[] buffer = new byte[8192];
    try (EncryptingPartOutputStream output = new EncryptingPartOutputStream(file, masterSecret)) {
      long remaining = length;
      while (remaining > 0) {
        int count = (int) Math.min(buffer.length, remaining);
        output.write(buffer, 0, count);
        remaining -= count;
      }
    } finally {
      Arrays.fill(buffer, (byte) 0);
    }
    return file;
  }

  private int ciphertextSnapshotCount() {
    File[] snapshots = cacheDirectory.listFiles((directory, name) ->
            name.startsWith("mms-ciphertext-"));
    return snapshots == null ? 0 : snapshots.length;
  }

  private byte[] digestFile(File file) throws Exception {
    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
    try (java.io.InputStream input = new java.io.FileInputStream(file)) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
      Arrays.fill(buffer, (byte) 0);
    }
    return digest.digest();
  }

  private byte[] readFile(File file) throws Exception {
    byte[] bytes = new byte[(int) file.length()];
    try (java.io.InputStream input = new java.io.FileInputStream(file)) {
      int offset = 0;
      while (offset < bytes.length) {
        int count = input.read(bytes, offset, bytes.length - offset);
        if (count < 0) throw new java.io.EOFException();
        offset += count;
      }
    }
    return bytes;
  }

  private boolean writeFile(File file, byte[] bytes) {
    try (java.io.FileOutputStream output = new java.io.FileOutputStream(file)) {
      output.write(bytes);
      return true;
    } catch (java.io.IOException exception) {
      return false;
    }
  }

  private byte[] createPng(int width, int height, int color) {
    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(color);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
    bitmap.eraseColor(Color.TRANSPARENT);
    bitmap.recycle();
    return output.toByteArray();
  }

  private byte[] pngHeaderOnly(int width, int height) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    DataOutputStream data = new DataOutputStream(output);
    data.write(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
    data.writeInt(13);
    byte[] type = new byte[]{'I', 'H', 'D', 'R'};
    data.write(type);
    byte[] header = ByteBuffer.allocate(13).putInt(width).putInt(height)
            .put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0).array();
    data.write(header);
    CRC32 crc = new CRC32();
    crc.update(type);
    crc.update(header);
    data.writeInt((int) crc.getValue());
    return output.toByteArray();
  }

  private MasterSecret syntheticSecret(int seed) {
    byte[] encryption = new byte[16];
    byte[] mac = new byte[20];
    for (int i = 0; i < encryption.length; i++) encryption[i] = (byte) (seed + i);
    for (int i = 0; i < mac.length; i++) mac[i] = (byte) (seed + 50 + i);
    return new MasterSecret(new SecretKeySpec(encryption, "AES"),
            new SecretKeySpec(mac, "HmacSHA1"));
  }

  private void eraseAndRecycle(Bitmap bitmap) {
    assertTrue("Decrypted preview must be mutable for pixel erasure", bitmap.isMutable());
    if (!bitmap.isRecycled()) {
      bitmap.eraseColor(Color.TRANSPARENT);
      bitmap.recycle();
    }
    if (!bitmap.isRecycled()) {
      bitmap.eraseColor(Color.TRANSPARENT);
      bitmap.recycle();
    }
  }

  private SQLiteOpenHelper isolatedHelper(File file) throws Exception {
    Class<?> helperClass = DatabaseFactory.class.getDeclaredClasses()[0];
    Constructor<?> constructor = helperClass.getDeclaredConstructor(Context.class, String.class,
            SQLiteDatabase.CursorFactory.class, int.class);
    constructor.setAccessible(true);
    return (SQLiteOpenHelper) constructor.newInstance(baseContext, file.getAbsolutePath(), null, 38);
  }

  private static void deleteTree(File file) {
    if (file == null) return;
    File[] children = file.listFiles();
    if (children != null) for (File child : children) deleteTree(child);
    file.delete();
  }
}
