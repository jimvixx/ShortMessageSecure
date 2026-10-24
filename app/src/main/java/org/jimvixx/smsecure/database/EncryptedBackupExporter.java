/*
 * Copyright (C) 2015 Open Whisper Systems
 * Copyright (C) 2025 Jimvixx
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
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jimvixx.smsecure.logging.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class EncryptedBackupExporter {

  private static final String TAG = EncryptedBackupExporter.class.getSimpleName();

  private static final String[] INCLUDED_TOP_LEVEL_DIRS = new String[]{
          "files",
          "databases",
          "shared_prefs",
          "app_parts",
  };

  private static final int MAX_ARCHIVE_ENTRIES = 50000;
  private static final long MAX_ARCHIVE_ENTRY_BYTES = 512L * 1024L * 1024L;
  private static final long MAX_ARCHIVE_BYTES = 4L * 1024L * 1024L * 1024L;

  private static final String[] EXCLUDED_TOP_LEVEL_DIRS = new String[]{
          "cache",
          "code_cache",
          "app_webview",
          "app_textures",
          "lib"
  };

  private static final String MARKER_FILE_NAME = "pending_encrypted_restore.marker";
  private static final String STAGING_DIR_NAME = "restore_staging";

  // -------------------------
  // EXPORT (SAF ZIP)
  // -------------------------

  public static void exportToUri(@NonNull Context context, @NonNull Uri outputUri) throws IOException {
    exportToUri(context, outputUri, DatabaseFactory.getDatabaseHelper(context));
  }

  static void exportToUri(@NonNull Context context, @NonNull Uri outputUri,
                          @NonNull SQLiteOpenHelper databaseHelper) throws IOException {
    File attachmentRoot = AttachmentBackupRestorer.getOwnedPartsRoot(context);
    if (attachmentRoot.isDirectory()) {
      synchronized (AttachmentCleanupProcessor.lifecycleLock()) {
        SQLiteDatabase database = databaseHelper.getWritableDatabase();
        database.beginTransaction();
        try {
          exportSnapshot(context, outputUri, database);
          database.setTransactionSuccessful();
        } finally {
          database.endTransaction();
        }
      }
    } else {
      exportSnapshot(context, outputUri, null);
    }
  }

  private static void exportSnapshot(@NonNull Context context, @NonNull Uri outputUri,
                                     SQLiteDatabase database) throws IOException {
    File parentDir = getAppDataParentDir(context);

    OutputStream raw = null;
    ZipOutputStream zos = null;
    try {
      raw = context.getContentResolver().openOutputStream(outputUri, "w");
      if (raw == null) throw new IOException("openOutputStream() returned null for: " + outputUri);

      zos = new ZipOutputStream(new BufferedOutputStream(raw));

      for (String name : INCLUDED_TOP_LEVEL_DIRS) {
        if (name.equals("app_parts")) continue;
        File dir = new File(parentDir, name);
        if (!dir.exists() || !dir.isDirectory()) {
          Log.w(TAG, "Missing dir for export: " + dir.getAbsolutePath());
          continue;
        }

        ZipEntry de = new ZipEntry(name + "/");
        zos.putNextEntry(de);
        zos.closeEntry();

        zipDirectoryRecursive(dir, name, zos);
      }

      if (database != null) zipReferencedAttachments(context, database, zos);

      zos.finish();

    } catch (IOException e) {
      throw e;
    } finally {
      closeQuietly(zos);
      closeQuietly(raw);
    }
  }

  private static void zipReferencedAttachments(@NonNull Context context,
                                               @NonNull SQLiteDatabase database,
                                               @NonNull ZipOutputStream zip) throws IOException {
    Map<String, File> attachments = AttachmentBackupRestorer.getReferencedOwnedFiles(context, database);
    if (attachments.isEmpty()) return;
    long totalBytes = 0;
    zip.putNextEntry(new ZipEntry("app_parts/"));
    zip.closeEntry();
    byte[] buffer = new byte[64 * 1024];
    for (Map.Entry<String, File> attachment : attachments.entrySet()) {
      long fileBytes = attachment.getValue().length();
      totalBytes += fileBytes;
      if (fileBytes > MAX_ARCHIVE_ENTRY_BYTES || totalBytes > MAX_ARCHIVE_BYTES) {
        throw new IOException("Referenced attachments exceed the backup size limit");
      }
      zip.putNextEntry(new ZipEntry("app_parts/" + attachment.getKey()));
      try (FileInputStream input = new FileInputStream(attachment.getValue());
           BufferedInputStream buffered = new BufferedInputStream(input)) {
        int read;
        while ((read = buffered.read(buffer)) != -1) zip.write(buffer, 0, read);
      }
      zip.closeEntry();
    }
  }

  private static void zipDirectoryRecursive(@NonNull File directory,
                                            @NonNull String zipPrefix,
                                            @NonNull ZipOutputStream zos) throws IOException {

    File[] contents = directory.listFiles();
    if (contents == null) return;

    for (File f : contents) {
      String entryName = zipPrefix + "/" + f.getName();
      if (isExcludedPath(entryName)) continue;
      if (f.getName().contains("libcurve25519.so")) continue;

      if (f.isDirectory()) {
        ZipEntry dirEntry = new ZipEntry(entryName + "/");
        zos.putNextEntry(dirEntry);
        zos.closeEntry();
        zipDirectoryRecursive(f, entryName, zos);
      } else if (f.isFile()) {
        ZipEntry e = new ZipEntry(entryName);
        zos.putNextEntry(e);

        try (FileInputStream fis = new FileInputStream(f);
             BufferedInputStream bis = new BufferedInputStream(fis)) {

          byte[] buffer = new byte[64 * 1024];
          int read;
          while ((read = bis.read(buffer)) != -1) {
            zos.write(buffer, 0, read);
          }

        } catch (IOException ioe) {
          // Unreadable files should not fail the whole export.
          Log.w(TAG, "Skipping unreadable file: " + f.getAbsolutePath(), ioe);
        } finally {
          try {
            zos.closeEntry();
          } catch (IOException ignored) {
          }
        }
      }
    }
  }

  // -------------------------
  // IMPORT (STAGE NOW, APPLY ON NEXT START)
  // -------------------------

  public static synchronized void stageImportFromUri(@NonNull Context context, @NonNull Uri inputUri) throws IOException {
    File stagingRoot = getStagingRoot(context);

    File marker = getMarkerFile(context);
    // An interrupted/failed attempt must never leave an applicable partial restore.
    if (marker.exists() && !marker.delete()) throw new IOException("Cannot clear pending restore marker");
    boolean staged = false;
    try {
      deleteRecursive(stagingRoot);
      if (!stagingRoot.mkdirs() && !stagingRoot.exists()) {
        throw new IOException("Failed to create staging dir: " + stagingRoot.getAbsolutePath());
      }

      // Extract ZIP into stagingRoot with strong validation.
      try (InputStream is = context.getContentResolver().openInputStream(inputUri)) {
        if (is == null) throw new IOException("openInputStream() returned null for: " + inputUri);

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(is))) {
          ZipEntry entry;
          byte[] buffer = new byte[64 * 1024];
          Set<String> entryNames = new HashSet<>();
          long totalBytes = 0;
          int entryCount = 0;

          while ((entry = zis.getNextEntry()) != null) {
            checkImportCancelled();
            String name = entry.getName();
            validateArchiveEntryName(name);
            if (++entryCount > MAX_ARCHIVE_ENTRIES) throw new IOException("Backup has too many entries");
            if (!entryNames.add(name)) throw new IOException("Backup contains duplicate entry names");

            if (!isAllowedTopLevel(name)) {
              Log.w(TAG, "Skipping entry outside allowed roots: " + name);
              totalBytes = drainEntryWithLimits(zis, buffer, totalBytes);
              zis.closeEntry();
              continue;
            }

            if (isExcludedPath(name)) {
              totalBytes = drainEntryWithLimits(zis, buffer, totalBytes);
              zis.closeEntry();
              continue;
            }

            if (name.startsWith("app_parts/") && name.substring("app_parts/".length()).contains("/")) {
              throw new IOException("Nested app_parts entries are unsupported");
            }

            File outFile = new File(stagingRoot, name);

            // ZipSlip protection (within stagingRoot)
            String stagingCanonical = stagingRoot.getCanonicalPath();
            String outCanonical = outFile.getCanonicalPath();
            if (!outCanonical.startsWith(stagingCanonical + File.separator) && !outCanonical.equals(stagingCanonical)) {
              throw new IOException("Backup entry escapes restore staging");
            }

            if (entry.isDirectory() || name.endsWith("/")) {
              if (!outFile.exists() && !outFile.mkdirs()) {
                throw new IOException("Failed to create directory: " + outFile.getAbsolutePath());
              }
              zis.closeEntry();
              continue;
            }

            File p = outFile.getParentFile();
            if (p != null && !p.exists() && !p.mkdirs()) {
              throw new IOException("Failed to create directory: " + p.getAbsolutePath());
            }

            try (FileOutputStream fos = new FileOutputStream(outFile);
                 BufferedOutputStream bos = new BufferedOutputStream(fos)) {
              int read;
              long entryBytes = 0;
              while ((read = zis.read(buffer)) != -1) {
                checkImportCancelled();
                entryBytes += read;
                totalBytes += read;
                if (entryBytes > MAX_ARCHIVE_ENTRY_BYTES || totalBytes > MAX_ARCHIVE_BYTES) {
                  throw new IOException("Backup exceeds the restore size limit");
                }
                bos.write(buffer, 0, read);
              }
              bos.flush();
              fos.getFD().sync();
            }

            zis.closeEntry();
          }
        }
      }

      // Minimal validation: must contain at least one of the core artifacts
      File msgDb = new File(stagingRoot, "databases/messages.db");
      File prefs = new File(stagingRoot, "shared_prefs/org.jimvixx.smsecure_preferences.xml");
      if (!msgDb.exists() && !prefs.exists()) {
        deleteRecursive(stagingRoot);
        throw new IOException("Staged restore does not look like a valid SMSecure backup (missing core files).");
      }

      AttachmentBackupRestorer.prepareStagedRestore(context, stagingRoot);
      syncDirectoryTree(stagingRoot, context.getNoBackupFilesDir());

      // Create marker
      checkImportCancelled();
      writeSmallTextFile(marker, "staged=" + stagingRoot.getAbsolutePath());
      Log.i(TAG, "Restore staged at: " + stagingRoot.getAbsolutePath());
      staged = true;
    } finally {
      if (!staged) {
        deleteRecursive(stagingRoot);
        //noinspection ResultOfMethodCallIgnored
        marker.delete();
      }
    }
  }

  private static void checkImportCancelled() throws InterruptedIOException {
    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Restore cancelled");
  }

  public static boolean applyPendingRestoreIfAny(@NonNull Context context) {
    File marker = getMarkerFile(context);
    File stagingRoot = getStagingRoot(context);
    if (!marker.isFile()) {
      // Process death before handoff leaves no marker: this staging is not applicable.
      deleteRecursive(stagingRoot);
      return true;
    }

    File parentDir;
    try {
      parentDir = getAppDataParentDir(context);
    } catch (IOException e) {
      Log.w(TAG, "applyPendingRestore: cannot resolve app data dir", e);
      return false;
    }

    if (!stagingRoot.exists() || !stagingRoot.isDirectory()) {
      Log.w(TAG, "applyPendingRestore: staging dir missing; deleting marker");
      // best effort cleanup
      //noinspection ResultOfMethodCallIgnored
      marker.delete();
      return false;
    }

    try {
      Log.i(TAG, "Applying staged restore...");

      AttachmentBackupRestorer.publishStagedAttachments(context, stagingRoot);

      for (String top : new String[]{"files", "shared_prefs", "databases"}) {
        File staged = new File(stagingRoot, top);
        if (!staged.exists()) continue;

        File target = new File(parentDir, top);

        if (target.exists()) deleteRecursive(target);
        if (!target.mkdirs() && !target.exists()) {
          throw new IOException("Failed to create target dir: " + target.getAbsolutePath());
        }
        copyRecursive(staged, target);
      }

      // Cleanup
      deleteRecursive(stagingRoot);
      //noinspection ResultOfMethodCallIgnored
      marker.delete();

      Log.i(TAG, "Staged restore applied successfully.");
      return true;

    } catch (Throwable t) {
      Log.w(TAG, "Failed to apply staged restore", t);
      return false;
    }
  }

  // -------------------------
  // Helpers
  // -------------------------

  private static File getAppDataParentDir(@NonNull Context context) throws IOException {
    File filesDir = context.getFilesDir();
    File parent = filesDir.getParentFile();
    if (parent == null)
      throw new IOException("Files directory has no parent: " + filesDir.getAbsolutePath());
    return parent;
  }

  private static File getMarkerFile(@NonNull Context context) {
    return new File(context.getNoBackupFilesDir(), MARKER_FILE_NAME);
  }

  private static File getStagingRoot(@NonNull Context context) {
    return new File(context.getNoBackupFilesDir(), STAGING_DIR_NAME);
  }

  private static boolean isAllowedTopLevel(@NonNull String entryName) {
    String top = entryName;
    int slash = top.indexOf('/');
    if (slash >= 0) top = top.substring(0, slash);

    for (String allowed : INCLUDED_TOP_LEVEL_DIRS) {
      if (allowed.equals(top)) return true;
    }
    return false;
  }

  private static void validateArchiveEntryName(@Nullable String name) throws IOException {
    if (name == null || name.trim().isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0 ||
            name.indexOf('\0') >= 0 || name.matches("^[A-Za-z]:.*")) {
      throw new IOException("Invalid backup entry path");
    }
    String normalized = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
    if (normalized.isEmpty()) throw new IOException("Invalid backup entry path");
    for (String segment : normalized.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        throw new IOException("Backup entry contains traversal components");
      }
    }
  }

  private static long drainEntryWithLimits(ZipInputStream input, byte[] buffer, long totalBytes)
      throws IOException {
    long entryBytes = 0;
    int read;
    while ((read = input.read(buffer)) != -1) {
      entryBytes += read;
      totalBytes += read;
      if (entryBytes > MAX_ARCHIVE_ENTRY_BYTES || totalBytes > MAX_ARCHIVE_BYTES) {
        throw new IOException("Backup exceeds the restore size limit");
      }
    }
    return totalBytes;
  }

  private static boolean isExcludedPath(@NonNull String entryName) {
    String normalized = entryName.replace('\\', '/');
    String top = normalized;
    int slash = top.indexOf('/');
    if (slash >= 0) top = top.substring(0, slash);

    for (String excluded : EXCLUDED_TOP_LEVEL_DIRS) {
      if (excluded.equals(top)) return true;
    }

    if (normalized.contains("libcurve25519.so")) return true;
    return normalized.equals("lib") || normalized.startsWith("lib/");
  }

  private static void copyRecursive(@NonNull File from, @NonNull File to) throws IOException {
    if (from.isDirectory()) {
      File[] children = from.listFiles();
      if (children == null) return;
      for (File c : children) {
        File dest = new File(to, c.getName());
        if (c.isDirectory()) {
          if (!dest.mkdirs() && !dest.exists())
            throw new IOException("Failed to mkdir: " + dest.getAbsolutePath());
          copyRecursive(c, dest);
        } else {
          copyFile(c, dest);
        }
      }
    } else {
      copyFile(from, to);
    }
  }

  private static void copyFile(@NonNull File from, @NonNull File to) throws IOException {
    try (FileInputStream fis = new FileInputStream(from);
         BufferedInputStream bis = new BufferedInputStream(fis);
         FileOutputStream fos = new FileOutputStream(to);
         BufferedOutputStream bos = new BufferedOutputStream(fos)) {
      byte[] buffer = new byte[64 * 1024];
      int read;
      while ((read = bis.read(buffer)) != -1) {
        bos.write(buffer, 0, read);
      }
      bos.flush();
    }
  }

  private static void deleteRecursive(@NonNull File f) {
    if (!f.exists()) return;
    if (f.isDirectory()) {
      File[] children = f.listFiles();
      if (children != null) {
        for (File c : children) deleteRecursive(c);
      }
    }
    //noinspection ResultOfMethodCallIgnored
    f.delete();
  }

  private static void writeSmallTextFile(@NonNull File file, @NonNull String content) throws IOException {
    File parent = file.getParentFile();
    if (parent != null && !parent.exists() && !parent.mkdirs()) {
      throw new IOException("Failed to create parent dir for marker: " + parent.getAbsolutePath());
    }
    File temporary = new File(parent, file.getName() + ".tmp");
    try (FileOutputStream fos = new FileOutputStream(temporary)) {
      fos.write(content.getBytes());
      fos.flush();
      fos.getFD().sync();
    }
    if (!temporary.renameTo(file)) {
      //noinspection ResultOfMethodCallIgnored
      temporary.delete();
      throw new IOException("Failed to publish restore marker");
    }
    syncDirectory(parent);
  }

  private static void syncDirectoryTree(File directory, File noBackupRoot) throws IOException {
    File canonicalRoot = directory.getCanonicalFile();
    File canonicalNoBackupRoot = noBackupRoot.getCanonicalFile();
    if (!STAGING_DIR_NAME.equals(canonicalRoot.getName()) ||
            !canonicalNoBackupRoot.equals(canonicalRoot.getParentFile())) {
      throw new IOException("Restore staging is outside the app-private no-backup directory");
    }
    syncDirectoryTreeValidated(directory, canonicalRoot);
  }

  private static void syncDirectoryTreeValidated(File directory, File expectedCanonicalPath)
      throws IOException {
    File canonicalDirectory = directory.getCanonicalFile();
    if (!canonicalDirectory.equals(expectedCanonicalPath) ||
            !isDirectoryWithoutFollowingFinalLink(directory)) {
      throw new IOException("Restore staging contains an unsafe directory");
    }
    File[] children = directory.listFiles();
    if (children == null) throw new IOException("Cannot sync staged restore directory");
    for (File child : children) {
      android.system.StructStat childStat;
      try {
        childStat = android.system.Os.lstat(child.getAbsolutePath());
      } catch (android.system.ErrnoException exception) {
        throw new IOException("Cannot inspect staged restore entry", exception);
      }
      File expectedChild = new File(canonicalDirectory, child.getName());
      if (!child.getCanonicalFile().equals(expectedChild) ||
              (!android.system.OsConstants.S_ISDIR(childStat.st_mode) &&
                      !android.system.OsConstants.S_ISREG(childStat.st_mode))) {
        throw new IOException("Restore staging contains an unsafe entry");
      }
      if (android.system.OsConstants.S_ISDIR(childStat.st_mode)) {
        syncDirectoryTreeValidated(child, expectedChild);
      }
    }
    syncDirectory(directory);
  }

  private static boolean isDirectoryWithoutFollowingFinalLink(File directory) throws IOException {
    try {
      return android.system.OsConstants.S_ISDIR(
              android.system.Os.lstat(directory.getAbsolutePath()).st_mode);
    } catch (android.system.ErrnoException exception) {
      throw new IOException("Cannot inspect restore staging directory", exception);
    }
  }

  private static void syncDirectory(File directory) throws IOException {
    java.io.FileDescriptor descriptor = null;
    try {
      if (!isDirectoryWithoutFollowingFinalLink(directory)) {
        throw new IOException("Expected a directory to sync");
      }
      descriptor = android.system.Os.open(directory.getAbsolutePath(),
              android.system.OsConstants.O_RDONLY, 0);
      android.system.Os.fsync(descriptor);
    } catch (android.system.ErrnoException | SecurityException exception) {
      throw new IOException("Cannot sync restore directory", exception);
    } finally {
      if (descriptor != null) {
        try { android.system.Os.close(descriptor); }
        catch (android.system.ErrnoException ignored) { }
      }
    }
  }

  private static void closeQuietly(@Nullable Closeable c) {
    if (c == null) return;
    try {
      c.close();
    } catch (Throwable t) {
      Log.w(TAG, "closeQuietly() ignored:", t);
    }
  }
}
