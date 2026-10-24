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
import android.os.Build;
import android.system.Os;
import android.util.Log;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class MmsAttachmentCleanupTest {
  private static final String TAG = "MmsCleanupTestDiag";
  private Context context;
  private File databaseFile;
  private File partsDirectory;
  private SQLiteOpenHelper helper;
  private final List<File> syntheticFiles = new ArrayList<>();

  @Before public void setUp() throws Exception {
    context = ApplicationProvider.getApplicationContext();
    databaseFile = new File(context.getCacheDir(), "mms-cleanup-" + System.nanoTime() + ".db");
    partsDirectory = context.getDir("parts", Context.MODE_PRIVATE);
    helper = helper(38);
    helper.getWritableDatabase();
  }

  @After public void tearDown() {
    if (helper != null) helper.close();
    for (File file : syntheticFiles) file.delete();
    if (databaseFile != null) databaseFile.delete();
  }

  @Test public void deletesOwnedDataAndThumbnailWithoutChangingEncryptedOtherMessage() throws Exception {
    File data = syntheticFile();
    File thumbnail = syntheticFile();
    File otherMessageAttachment = syntheticFile();
    SQLiteDatabase db = helper.getWritableDatabase();
    db.execSQL("INSERT INTO part (_id, mid, _data, thumbnail, encrypted, digest) " +
        "VALUES (1, 90, ?, NULL, 1, X'010203')", new Object[]{otherMessageAttachment.getAbsolutePath()});
    db.beginTransaction();
    try {
      queue().enqueue(db, data.getAbsolutePath(), AttachmentCleanupDatabase.TYPE_DATA,
          89, 1, 1, 1);
      queue().enqueue(db, thumbnail.getAbsolutePath(), AttachmentCleanupDatabase.TYPE_THUMBNAIL,
          89, 1, 1, 1);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    assertEquals(2, processWithDiagnostics("owned-data-and-thumbnail", 10, 32));
    if (supportsSecureDirectoryStream()) {
      assertFalse(data.exists());
      assertFalse(thumbnail.exists());
      assertEquals(0, scalar(db, "SELECT COUNT(*) FROM attachment_cleanup"));
    } else {
      assertTrue(data.exists());
      assertTrue(thumbnail.exists());
      assertBlockedForUnsupportedFilesystem();
    }
    assertEquals(1, scalar(db, "SELECT COUNT(*) FROM part WHERE mid = 90 AND encrypted = 1 " +
        "AND hex(digest) = '010203'"));
    assertTrue(otherMessageAttachment.exists());
  }

  @Test public void sharedAndReferencedPathsAreRetainedUntilNoPartReferencesRemain() throws Exception {
    File file = syntheticFile();
    SQLiteDatabase db = helper.getWritableDatabase();
    db.execSQL("INSERT INTO part (_id, mid, _data, thumbnail, encrypted) VALUES (2, 90, ?, ?, 0)",
        new Object[]{file.getAbsolutePath(), file.getAbsolutePath()});
    enqueue(db, file, AttachmentCleanupDatabase.TYPE_DATA, 1, 0);
    enqueue(db, file, AttachmentCleanupDatabase.TYPE_THUMBNAIL, 1, 0);

    processWithDiagnostics("shared-reference-present", 10, 32);
    assertTrue(file.exists());
    assertEquals(AttachmentCleanupDatabase.STATE_BLOCKED,
        queue().getPendingEntries(2).get(0).state);
    assertEquals(2, queue().getPendingEntries(2).size());

    db.delete("part", "mid = ?", new String[]{"90"});
    processWithDiagnostics("shared-reference-removed", 20, 32);
    if (supportsSecureDirectoryStream()) {
      assertFalse(file.exists());
      assertEquals(0, queue().getPendingEntries(10).size());
    } else {
      assertTrue(file.exists());
      assertBlockedForUnsupportedFilesystem();
    }
  }

  @Test public void missingFilesResolveIdempotentlyAndDuplicateEntriesDoNotEscapeRoot() throws Exception {
    File missing = syntheticFile();
    assertTrue(missing.delete());
    enqueue(helper.getWritableDatabase(), missing, AttachmentCleanupDatabase.TYPE_DATA, 0, 1);
    enqueue(helper.getWritableDatabase(), missing, AttachmentCleanupDatabase.TYPE_DATA, 0, 1);

    processWithDiagnostics("missing-duplicate-path", 10, 32);
    assertEquals(0, queue().getPendingEntries(10).size());
  }

  @Test public void externalAndTraversalPathsRemainQueuedAndNeverDeleteFiles() throws Exception {
    File external = new File(context.getCacheDir(), "part-external-" + System.nanoTime() + ".mms");
    writeFile(external);
    enqueuePath(helper.getWritableDatabase(), external.getAbsolutePath(), 0);
    enqueuePath(helper.getWritableDatabase(), new File(partsDirectory, "../outside.mms").getPath(), 0);

    processWithDiagnostics("external-and-traversal", 10, 32);
    assertTrue(external.exists());
    List<AttachmentCleanupDatabase.CleanupEntry> entries = queue().getPendingEntries(10);
    assertEquals(2, entries.size());
    assertEquals(AttachmentCleanupDatabase.STATE_BLOCKED, entries.get(0).state);
    assertEquals(AttachmentCleanupDatabase.ERROR_UNSAFE_PATH, entries.get(0).lastError);
  }

  @Test public void symlinkEntryIsBlockedWithoutDeletingTarget() throws Exception {
    File target = syntheticFile();
    File link = new File(partsDirectory, "partlink" + System.nanoTime() + ".mms");
    Os.symlink(target.getAbsolutePath(), link.getAbsolutePath());
    syntheticFiles.add(link);
    enqueue(helper.getWritableDatabase(), link, AttachmentCleanupDatabase.TYPE_DATA, 0, 0);

    processWithDiagnostics("symlink", 10, 32);
    assertTrue(target.exists());
    assertTrue(link.exists());
    assertEquals(AttachmentCleanupDatabase.ERROR_UNSAFE_PATH,
        queue().getPendingEntries(1).get(0).lastError);
  }

  @Test public void unknownEncryptionIsBlockedAndQueueSurvivesDatabaseReopening() throws Exception {
    File file = syntheticFile();
    enqueue(helper.getWritableDatabase(), file, AttachmentCleanupDatabase.TYPE_DATA, 0, null);
    processWithDiagnostics("unknown-encryption", 10, 32);
    assertTrue(file.exists());
    helper.close();
    helper = helper(38);
    AttachmentCleanupDatabase.CleanupEntry entry = queue().getPendingEntries(1).get(0);
    assertEquals(AttachmentCleanupDatabase.ERROR_UNKNOWN_ENCRYPTION, entry.lastError);
    assertEquals(AttachmentCleanupDatabase.STATE_BLOCKED, entry.state);
  }

  @Test public void interruptedFinalizationLeavesDurableQueueAndRestartResolvesMissingFile() throws Exception {
    File file = syntheticFile();
    enqueue(helper.getWritableDatabase(), file, AttachmentCleanupDatabase.TYPE_DATA, 0, 0);
    SQLiteDatabase db = helper.getWritableDatabase();
    db.execSQL("CREATE TRIGGER reject_cleanup_finalization BEFORE DELETE ON attachment_cleanup " +
        "BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END");

    try {
      processWithDiagnostics("interrupted-finalization", 10, 1,
          (ignoredContext, ignoredPath) -> {
            return file.delete() ? AttachmentCleanupDatabase.ERROR_NONE :
                AttachmentCleanupDatabase.ERROR_IO;
          });
    } catch (RuntimeException expected) {
      assertNotNull(expected);
    }
    assertFalse(file.exists());
    assertEquals(1, queue().getPendingEntries(1).size());
    helper.close();

    helper = helper(38);
    SQLiteDatabase reopened = helper.getWritableDatabase();
    reopened.execSQL("DROP TRIGGER reject_cleanup_finalization");
    processWithDiagnostics("restart-after-unlink", 20, 1);
    assertEquals(0, queue().getPendingEntries(1).size());
  }

  @Test public void retryScheduleIsBoundedAndDelayed() throws Exception {
    File file = syntheticFile();
    enqueue(helper.getWritableDatabase(), file, AttachmentCleanupDatabase.TYPE_DATA, 0, 0);
    AttachmentCleanupDatabase.CleanupEntry entry = queue().getPendingEntries(1).get(0);
    SQLiteDatabase db = helper.getWritableDatabase();
    db.beginTransaction();
    try {
      queue().markRetry(db, entry, AttachmentCleanupDatabase.ERROR_IO, 1000);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    AttachmentCleanupDatabase.CleanupEntry retry = queue().getPendingEntries(1).get(0);
    assertEquals(AttachmentCleanupDatabase.STATE_RETRY, retry.state);
    assertEquals(1, retry.attemptCount);
    assertTrue(retry.nextAttemptAt > 1000);
    assertEquals(0, queue().getDueEntries(1000, 10).size());
    for (int attempt = 1; attempt < AttachmentCleanupDatabase.MAX_ATTEMPTS; attempt++) {
      retry = queue().getPendingEntries(1).get(0);
      db.beginTransaction();
      try {
        queue().markRetry(db, retry, AttachmentCleanupDatabase.ERROR_IO, 5000L * attempt);
        db.setTransactionSuccessful();
      } finally {
        db.endTransaction();
      }
    }
    retry = queue().getPendingEntries(1).get(0);
    assertEquals(AttachmentCleanupDatabase.MAX_ATTEMPTS, retry.attemptCount);
    assertEquals(AttachmentCleanupDatabase.STATE_BLOCKED, retry.state);
  }

  @Test public void transientUnlinkFailureRetriesLaterAndThenCompletes() throws Exception {
    File file = syntheticFile();
    enqueue(helper.getWritableDatabase(), file, AttachmentCleanupDatabase.TYPE_DATA, 0, 0);

    processWithDiagnostics("injected-transient-failure", 1000, 1,
        (ignoredContext, ignoredPath) -> AttachmentCleanupDatabase.ERROR_IO);
    assertTrue(file.exists());
    AttachmentCleanupDatabase.CleanupEntry retry = queue().getPendingEntries(1).get(0);
    assertEquals(AttachmentCleanupDatabase.STATE_RETRY, retry.state);
    assertEquals(1, retry.attemptCount);

    processWithDiagnostics("retry-after-due-time", retry.nextAttemptAt, 1);
    if (supportsSecureDirectoryStream()) {
      assertFalse(file.exists());
      assertEquals(0, queue().getPendingEntries(1).size());
    } else {
      assertTrue(file.exists());
      assertBlockedForUnsupportedFilesystem();
    }
  }

  private int processWithDiagnostics(String scenario, long now, int limit) {
    return processWithDiagnostics(scenario, now, limit, AttachmentCleanupProcessor::unlinkOwnedFile);
  }

  private int processWithDiagnostics(String scenario, long now, int limit,
                                     AttachmentCleanupProcessor.FileUnlinker unlinker) {
    AtomicBoolean reachedUnlinker = new AtomicBoolean(false);
    int processed = 0;
    RuntimeException processingFailure = null;
    try {
      processed = AttachmentCleanupProcessor.processBatch(context, helper, now, limit,
          (operationContext, path) -> {
            reachedUnlinker.set(true);
            return unlinker.unlink(operationContext, path);
          });
    } catch (RuntimeException exception) {
      processingFailure = exception;
    }
    StringBuilder queueState = new StringBuilder();
    String candidateValidation = "none";
    String rootCanonicalMatches = "unknown";
    String rootPath = partsDirectory.getAbsolutePath();
    try {
      rootCanonicalMatches = String.valueOf(rootPath.equals(partsDirectory.getCanonicalPath()));
    } catch (Exception exception) {
      rootCanonicalMatches = "error:" + exception.getClass().getSimpleName();
    }
    for (AttachmentCleanupDatabase.CleanupEntry entry : queue().getPendingEntries(64)) {
      if (queueState.length() > 0) queueState.append(',');
      queueState.append(entry.state).append(':').append(entry.lastError);
      File candidate = entry.path == null ? null : new File(entry.path);
      String candidateCheck = candidate == null ? "null" :
          "inside=" + entry.path.startsWith(rootPath + File.separator) +
          ",name=" + candidate.getName().matches("part[A-Za-z0-9]+\\.mms") +
          ",exists=" + candidate.exists();
      if ("none".equals(candidateValidation)) candidateValidation = candidateCheck;
    }
    String providerClass = "unavailable-api";
    String streamClass = "unavailable-api";
    String secureSupported = "false";
    if (Build.VERSION.SDK_INT >= 26) {
      providerClass = partsDirectory.toPath().getFileSystem().provider().getClass().getName();
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(partsDirectory.toPath())) {
        streamClass = stream.getClass().getName();
        secureSupported = String.valueOf(stream instanceof SecureDirectoryStream);
      } catch (Exception exception) {
        streamClass = "error:" + exception.getClass().getSimpleName();
        secureSupported = "false";
      }
    }
    Log.i(TAG, "scenario=" + scenario + " api=" + Build.VERSION.SDK_INT +
        " provider=" + providerClass +
        " stream=" + streamClass + " secureDirectoryStream=" + secureSupported +
        " rootCanonicalMatches=" + rootCanonicalMatches +
        " candidate=" + candidateValidation +
        " reachedUnlinker=" + reachedUnlinker.get() + " processed=" + processed +
        " queueStateErrors=" + queueState);
    if (processingFailure != null) throw processingFailure;
    return processed;
  }

  private boolean supportsSecureDirectoryStream() {
    if (Build.VERSION.SDK_INT < 26) return false;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(partsDirectory.toPath())) {
      return stream instanceof SecureDirectoryStream;
    } catch (Exception exception) {
      return false;
    }
  }

  private void assertBlockedForUnsupportedFilesystem() {
    List<AttachmentCleanupDatabase.CleanupEntry> entries = queue().getPendingEntries(64);
    assertFalse(entries.isEmpty());
    int filesystemError = filesystemFailureStatus();
    assertTrue(filesystemError == AttachmentCleanupDatabase.ERROR_UNSUPPORTED_FILESYSTEM ||
        filesystemError == AttachmentCleanupDatabase.ERROR_IO);
    for (AttachmentCleanupDatabase.CleanupEntry entry : entries) {
      assertEquals(filesystemError == AttachmentCleanupDatabase.ERROR_IO
              ? AttachmentCleanupDatabase.STATE_RETRY : AttachmentCleanupDatabase.STATE_BLOCKED,
          entry.state);
      assertEquals(filesystemError, entry.lastError);
    }
  }

  private int filesystemFailureStatus() {
    if (Build.VERSION.SDK_INT < 26) {
      return AttachmentCleanupDatabase.ERROR_UNSUPPORTED_FILESYSTEM;
    }
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(partsDirectory.toPath())) {
      return stream instanceof SecureDirectoryStream
          ? AttachmentCleanupDatabase.ERROR_NONE
          : AttachmentCleanupDatabase.ERROR_UNSUPPORTED_FILESYSTEM;
    } catch (Exception exception) {
      return AttachmentCleanupDatabase.ERROR_IO;
    }
  }

  private AttachmentCleanupDatabase queue() {
    return new AttachmentCleanupDatabase(context, helper);
  }

  private void enqueue(SQLiteDatabase db, File file, int type, long mmsId, Integer encrypted) {
    db.beginTransaction();
    try {
      queue().enqueue(db, file.getAbsolutePath(), type, mmsId, 1, encrypted,
          System.currentTimeMillis());
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  private void enqueuePath(SQLiteDatabase db, String path, Integer encrypted) {
    db.beginTransaction();
    try {
      queue().enqueue(db, path, AttachmentCleanupDatabase.TYPE_DATA, 1, 1, encrypted,
          System.currentTimeMillis());
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  private File syntheticFile() throws Exception {
    File file;
    do {
      file = new File(partsDirectory, "part" + Long.toHexString(System.nanoTime()) + ".mms");
    } while (!file.createNewFile());
    assertTrue(file.getName().matches("part[A-Za-z0-9]+\\.mms"));
    syntheticFiles.add(file);
    writeFile(file);
    return file;
  }

  private void writeFile(File file) throws Exception {
    try (FileOutputStream output = new FileOutputStream(file)) {
      output.write(new byte[]{1, 2, 3});
    }
  }

  private int scalar(SQLiteDatabase db, String sql) {
    try (Cursor cursor = db.rawQuery(sql, null)) {
      return cursor.moveToFirst() ? cursor.getInt(0) : 0;
    }
  }

  private SQLiteOpenHelper helper(int version) throws Exception {
    Class<?> helperClass = DatabaseFactory.class.getDeclaredClasses()[0];
    Constructor<?> constructor = helperClass.getDeclaredConstructor(Context.class, String.class,
        SQLiteDatabase.CursorFactory.class, int.class);
    constructor.setAccessible(true);
    return (SQLiteOpenHelper) constructor.newInstance(context, databaseFile.getPath(), null, version);
  }
}
