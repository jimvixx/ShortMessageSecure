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

package org.jimvixx.smsecure.migration.silence;

import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.jimvixx.smsecure.database.EncryptedBackupExporter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SilenceBackupAdapterTest {
  private static final String LEGACY = "shared_prefs/org.smssecure.smssecure_preferences.xml";
  private static final String TARGET = "shared_prefs/org.jimvixx.smsecure_preferences.xml";
  private final Uri tree = DocumentsContract.buildTreeDocumentUri(SilenceExportTestProvider.AUTHORITY, "SilenceExport");
  private File root;
  private Context context;
  private Bundle files;
  private ArrayList<String> directories;

  @Before public void setUp() throws Exception {
    Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
    root = File.createTempFile("adapter-test-", "", base.getCacheDir());
    assertTrue(root.delete()); assertTrue(root.mkdir());
    context = new ContextWrapper(base) {
      private File dir(String name) { File file = new File(root, name); file.mkdirs(); return file; }
      @Override public File getFilesDir() { return dir("files"); }
      @Override public File getCacheDir() { return dir("cache"); }
      @Override public File getNoBackupFilesDir() { return dir("no_backup"); }
    };
    directories = new ArrayList<>(Arrays.asList("files", "files/sessions-v2", "databases", "shared_prefs"));
    files = new Bundle();
    files.putByteArray("databases/messages.db", new byte[]{0, 1, -1, 42});
    files.putByteArray(LEGACY, "<map/>".getBytes(StandardCharsets.UTF_8));
    files.putByteArray("shared_prefs/SecureSMS-Preferences.xml", "<map>keys</map>".getBytes(StandardCharsets.UTF_8));
    files.putByteArray("files/blob", new byte[]{4, 0, 7});
    files.putByteArray("files/sessions-v2/44.1", new byte[]{-1, 0, 9});
    files.putByteArray("files/org.smssecure.smssecure_preferences.xml", new byte[]{10});
    files.putByteArray("files/empty", new byte[0]);
    configure("");
  }

  @After public void tearDown() {
    Thread.interrupted();
    delete(root);
  }

  private void configure(String fault) {
    Bundle config = new Bundle(); config.putBundle("files", files);
    config.putStringArrayList("directories", directories); config.putString("fault", fault);
    context.getContentResolver().call(Uri.parse("content://" + SilenceExportTestProvider.AUTHORITY), "configure", null, config);
  }

  @Test public void normalizesRootAndOnlyRenamesLegacyPreferencesPreservingEveryByte() throws Exception {
    File archive = SilenceBackupAdapter.createArchive(context, tree);
    Map<String, byte[]> actual = unzip(archive);
    assertEquals(files.size() + directories.size(), actual.size());
    for (String dir : directories) assertTrue(actual.containsKey(dir + "/"));
    for (String path : files.keySet()) assertArrayEquals(files.getByteArray(path), actual.get(path.equals(LEGACY) ? TARGET : path));
    assertFalse(actual.containsKey(LEGACY));
    for (String path : actual.keySet()) assertFalse(path.startsWith("SilenceExport/"));
    // Re-read the provider: the adapter must not rename or write anything in the source.
    File second = SilenceBackupAdapter.createArchive(context, tree);
    Map<String, byte[]> again = unzip(second);
    for (String path : actual.keySet()) assertArrayEquals(actual.get(path), again.get(path));
    assertFalse(archive.exists()); assertTrue(second.delete()); assertNoArchive();
  }

  @Test public void missingOrEmptyRequiredFilesFailWithoutStaging() throws Exception {
    for (String path : Arrays.asList("databases/messages.db", LEGACY, "shared_prefs/SecureSMS-Preferences.xml")) {
      byte[] saved = files.getByteArray(path);
      files.remove(path); configure(""); expectFailure();
      files.putByteArray(path, new byte[0]); configure(""); expectFailure();
      files.putByteArray(path, saved);
    }
  }

  @Test public void missingDirectoryAndWrongRootTypeFail() throws Exception {
    directories.remove("files"); configure(""); expectFailure();
    files.putByteArray("files", new byte[]{1}); configure(""); expectFailure();
  }

  @Test public void conflictingPreferencesFailWithoutOverwriting() throws Exception {
    files.putByteArray(TARGET, new byte[]{2}); configure(""); expectFailure();
  }

  @Test public void unreadableListingUnsafeAndDuplicateNamesFailAndCleanUp() throws Exception {
    for (String fault : Arrays.asList("unreadable", "listing", "unsafe", "duplicate")) {
      configure(fault); expectFailure();
    }
  }

  @Test public void cancellationRemovesAdapterArchive() throws Exception {
    Thread.currentThread().interrupt();
    try { SilenceBackupAdapter.stageImport(context, tree); fail("Expected cancellation"); }
    catch (InterruptedIOException expected) { /* Expected. */ }
    finally { Thread.interrupted(); }
    assertNoArchive(); assertNoPendingRestore();
  }

  @Test public void adapterHandsOffToExistingRestoreAndCleansArchive() throws Exception {
    SilenceBackupAdapter.stageImport(context, tree);
    assertNoArchive();
    assertTrue(new File(context.getNoBackupFilesDir(), "pending_encrypted_restore.marker").isFile());
    for (String path : files.keySet()) {
      String target = path.equals(LEGACY) ? TARGET : path;
      assertArrayEquals(files.getByteArray(path), read(new File(context.getNoBackupFilesDir(), "restore_staging/" + target)));
    }
    EncryptedBackupExporter.applyPendingRestoreIfAny(context);
    assertNoPendingRestore();
    for (String path : files.keySet()) assertArrayEquals(files.getByteArray(path), read(new File(root, path.equals(LEGACY) ? TARGET : path)));
  }

  @Test public void handoffFailureStillDeletesAdapterArchive() throws Exception {
    File blocked = new File(root, "not-a-directory");
    assertTrue(blocked.createNewFile());
    Context unavailable = new ContextWrapper(context) {
      @Override public File getNoBackupFilesDir() { return blocked; }
    };
    try { SilenceBackupAdapter.stageImport(unavailable, tree); fail("Expected staging failure"); }
    catch (IOException expected) { /* Expected. */ }
    assertNoArchive(); assertNoPendingRestore();
  }

  @Test public void extractionFailureRemovesAlreadyWrittenFiles() throws Exception {
    File archive = new File(root, "broken.zip");
    try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
      zip.putNextEntry(new ZipEntry("databases/messages.db")); zip.write(1); zip.closeEntry();
      zip.putNextEntry(new ZipEntry("files/collision")); zip.write(2); zip.closeEntry();
      zip.putNextEntry(new ZipEntry("files/collision/child")); zip.write(3); zip.closeEntry();
    }
    try { EncryptedBackupExporter.stageImportFromUri(context, Uri.fromFile(archive)); fail("Expected extraction failure"); }
    catch (IOException expected) { /* Expected. */ }
    assertNoPendingRestore();
  }

  @Test public void ordinarySmsSecureRestoreStillStagesAndAppliesUnchangedBytes() throws Exception {
    File archive = normalArchive();
    EncryptedBackupExporter.stageImportFromUri(context, Uri.fromFile(archive));
    EncryptedBackupExporter.applyPendingRestoreIfAny(context);
    assertArrayEquals(new byte[]{7, 8, 9}, read(new File(root, "databases/messages.db")));
    assertArrayEquals(new byte[]{5}, read(new File(root, TARGET)));
    assertNoPendingRestore();
  }

  @Test public void failedRestoreClearsPartialStagingAndPreviousMarker() throws Exception {
    File archive = normalArchive();
    EncryptedBackupExporter.stageImportFromUri(context, Uri.fromFile(archive));
    try {
      EncryptedBackupExporter.stageImportFromUri(context, Uri.fromFile(new File(root, "missing.zip")));
      fail("Expected read failure");
    } catch (IOException expected) { /* Expected. */ }
    assertNoPendingRestore();
  }

  @Test public void interruptedRestoreClearsStagingAndMarker() throws Exception {
    File archive = normalArchive();
    Thread.currentThread().interrupt();
    try { EncryptedBackupExporter.stageImportFromUri(context, Uri.fromFile(archive)); fail("Expected cancellation"); }
    catch (InterruptedIOException expected) { /* Expected. */ }
    finally { Thread.interrupted(); }
    assertNoPendingRestore();
  }

  @Test public void requiredFilesPresentButLoadingListingFailsClosed() throws Exception {
    configure("loading");
    expectFailure();
  }

  @Test public void directoryErrorExtraFailsClosedEvenWithNullMessage() throws Exception {
    configure("error"); expectFailure();
    configure("null-error"); expectFailure();
  }

  @Test public void interruptionDuringCopyDeletesPartialZip() throws Exception {
    addLargeFile();
    File archive = File.createTempFile("silence-restore-", ".zip", context.getCacheDir());
    long[] bytesWritten = {0};
    OutputStream output = new FilterOutputStream(new FileOutputStream(archive)) {
      @Override public void write(byte[] bytes, int offset, int length) throws IOException {
        out.write(bytes, offset, length);
        bytesWritten[0] += length;
        Thread.currentThread().interrupt();
      }
    };
    assertFalse(Thread.currentThread().isInterrupted());
    try { SilenceBackupAdapter.createArchive(context, tree, archive, output); fail("Expected cancellation during copy"); }
    catch (InterruptedIOException expected) { /* Expected. */ }
    finally { Thread.interrupted(); }
    assertTrue("Some ZIP bytes must have reached disk before interruption", bytesWritten[0] > 0);
    assertTrue("Copy must stop before consuming the large file", bytesWritten[0] < 256 * 1024);
    assertFalse(archive.exists()); assertNoArchive(); assertNoPendingRestore();
  }

  @Test public void zipWriteFailureAfterPartialOutputDeletesPartialZip() throws Exception {
    addLargeFile();
    File archive = File.createTempFile("silence-restore-", ".zip", context.getCacheDir());
    long[] bytesWritten = {0};
    OutputStream output = new FilterOutputStream(new FileOutputStream(archive)) {
      @Override public void write(byte[] bytes, int offset, int length) throws IOException {
        if (bytesWritten[0] == 0) {
          out.write(bytes, offset, Math.min(length, 1024));
          bytesWritten[0] = archive.length();
        }
        throw new IOException("Injected ZIP output failure");
      }
    };
    try { SilenceBackupAdapter.createArchive(context, tree, archive, output); fail("Expected ZIP write failure"); }
    catch (IOException expected) { assertEquals("Injected ZIP output failure", expected.getMessage()); }
    assertTrue("Partial output must exist before failure", bytesWritten[0] > 0);
    assertFalse(archive.exists()); assertNoArchive(); assertNoPendingRestore();
  }

  @Test public void nextAdapterUseCleansOnlyAbandonedZipAndPreservesPendingRestore() throws Exception {
    EncryptedBackupExporter.stageImportFromUri(context, Uri.fromFile(normalArchive()));
    File marker = new File(context.getNoBackupFilesDir(), "pending_encrypted_restore.marker");
    File staged = new File(context.getNoBackupFilesDir(), "restore_staging/databases/messages.db");
    byte[] markerBefore = read(marker), stagedBefore = read(staged);
    File abandoned = new File(context.getCacheDir(), "silence-restore-previous-process.zip");
    try (FileOutputStream out = new FileOutputStream(abandoned)) { out.write(new byte[]{1, 2}); }
    File unrelated = new File(context.getCacheDir(), "other-backup.zip");
    File differentSuffix = new File(context.getCacheDir(), "silence-restore-note.txt");
    File directory = new File(context.getCacheDir(), "silence-restore-directory.zip");
    assertTrue(unrelated.createNewFile()); assertTrue(differentSuffix.createNewFile()); assertTrue(directory.mkdir());
    File archive = SilenceBackupAdapter.createArchive(context, tree);
    assertFalse(abandoned.exists()); assertTrue(archive.isFile());
    assertTrue(unrelated.isFile()); assertTrue(differentSuffix.isFile()); assertTrue(directory.isDirectory());
    assertArrayEquals(markerBefore, read(marker)); assertArrayEquals(stagedBefore, read(staged));
    assertTrue(archive.delete());
  }

  @Test public void startupRemovesAbandonedStagingWithoutMarker() throws Exception {
    File staged = new File(context.getNoBackupFilesDir(), "restore_staging/databases/messages.db");
    assertTrue(staged.getParentFile().mkdirs());
    try (FileOutputStream out = new FileOutputStream(staged)) { out.write(new byte[]{1, 2}); }
    File unrelated = new File(context.getNoBackupFilesDir(), "keep-me");
    assertTrue(unrelated.createNewFile());
    EncryptedBackupExporter.applyPendingRestoreIfAny(new ContextWrapper(context));
    assertNoPendingRestore(); assertTrue(unrelated.isFile());
    assertFalse(new File(root, "databases/messages.db").exists());
  }

  @Test public void validPendingRestoreSurvivesRestartAndStillApplies() throws Exception {
    EncryptedBackupExporter.stageImportFromUri(context, Uri.fromFile(normalArchive()));
    File marker = new File(context.getNoBackupFilesDir(), "pending_encrypted_restore.marker");
    File staged = new File(context.getNoBackupFilesDir(), "restore_staging/databases/messages.db");
    // Startup cannot resolve a target: valid pending data must remain available to retry.
    Context temporarilyUnavailable = new ContextWrapper(context) {
      @Override public File getFilesDir() { return new File("/"); }
    };
    EncryptedBackupExporter.applyPendingRestoreIfAny(temporarilyUnavailable);
    assertTrue(marker.isFile()); assertArrayEquals(new byte[]{7, 8, 9}, read(staged));
    EncryptedBackupExporter.applyPendingRestoreIfAny(new ContextWrapper(context));
    assertArrayEquals(new byte[]{7, 8, 9}, read(new File(root, "databases/messages.db")));
    assertArrayEquals(new byte[]{5}, read(new File(root, TARGET)));
    assertNoPendingRestore();
  }

  private void addLargeFile() {
    byte[] data = new byte[256 * 1024];
    new Random(481).nextBytes(data); // Incompressible enough to force writes during copying.
    files.putByteArray("files/blob", data); configure("");
  }

  private void expectFailure() throws Exception {
    try { SilenceBackupAdapter.stageImport(context, tree); fail("Expected invalid export"); }
    catch (IOException expected) { /* Expected. */ }
    assertNoArchive(); assertNoPendingRestore();
  }
  private void assertNoArchive() { assertEquals(0, context.getCacheDir().list().length); }
  private void assertNoPendingRestore() {
    assertFalse(new File(context.getNoBackupFilesDir(), "pending_encrypted_restore.marker").exists());
    assertFalse(new File(context.getNoBackupFilesDir(), "restore_staging").exists());
  }
  private File normalArchive() throws Exception {
    File file = new File(root, "normal.zip");
    try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
      zip.putNextEntry(new ZipEntry("databases/messages.db")); zip.write(new byte[]{7, 8, 9}); zip.closeEntry();
      zip.putNextEntry(new ZipEntry(TARGET)); zip.write(new byte[]{5}); zip.closeEntry();
    }
    return file;
  }
  private static Map<String, byte[]> unzip(File file) throws Exception {
    Map<String, byte[]> result = new HashMap<>();
    try (ZipInputStream zip = new ZipInputStream(new FileInputStream(file))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int read;
        while ((read = zip.read(buffer)) != -1) out.write(buffer, 0, read);
        assertNull(result.put(entry.getName(), out.toByteArray()));
      }
    }
    return result;
  }
  private static byte[] read(File file) throws Exception {
    try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096]; int read;
      while ((read = input.read(buffer)) != -1) out.write(buffer, 0, read);
      return out.toByteArray();
    }
  }
  private static void delete(File file) {
    if (file == null) return;
    File[] children = file.listFiles();
    if (children != null) for (File child : children) delete(child);
    file.delete();
  }
}
