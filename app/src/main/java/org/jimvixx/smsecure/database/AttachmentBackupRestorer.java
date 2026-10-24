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

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import androidx.annotation.NonNull;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stages verified MMS attachment bytes and rebases only matching part paths. */
public final class AttachmentBackupRestorer {
  private static final String DATABASE_ENTRY = "databases/messages.db";
  private static final String SOURCE_PARTS_ENTRY = "app_parts";
  private static final String STAGED_PARTS_ENTRY = "restored_parts";
  private static final String PART_FILENAME = "part[A-Za-z0-9]+\\.mms";
  private static final Pattern LEGACY_SILENCE_PATH = Pattern.compile(
          "^/data/(?:data/org\\.smssecure\\.smssecure|user(?:_de)?/[0-9]+/org\\.smssecure\\.smssecure)/app_parts/([^/]+)$");
  private static final String TEMP_PREFIX = ".smsecure-restore-";
  private static final long MAX_ATTACHMENT_BYTES = 512L * 1024L * 1024L;

  private AttachmentBackupRestorer() {}

  /**
   * Rewrites only references whose exact app_parts member exists in the staged backup.
   * Missing and unsupported references remain unchanged for a future UI to report safely.
   */
  public static void prepareStagedRestore(@NonNull Context context,
                                          @NonNull File stagingRoot) throws IOException {
    File sourceRoot = new File(stagingRoot, SOURCE_PARTS_ENTRY);
    if (!sourceRoot.exists()) return;
    requireDirectory(sourceRoot);
    requireDirectChild(sourceRoot, stagingRoot, SOURCE_PARTS_ENTRY);
    rejectNestedDirectories(sourceRoot);

    File stagedDatabase = new File(stagingRoot, DATABASE_ENTRY);
    if (!stagedDatabase.isFile()) {
      deleteTree(sourceRoot);
      return;
    }

    File destinationRoot = getOwnedPartsRoot(context);
    File mappedFiles = new File(stagingRoot, STAGED_PARTS_ENTRY);
    Map<String, String> pathMapping = new HashMap<>();
    SQLiteDatabase database = null;
    boolean transactionOpen = false;
    boolean complete = false;
    try {
      database = SQLiteDatabase.openDatabase(stagedDatabase.getAbsolutePath(), null,
              SQLiteDatabase.OPEN_READWRITE);
      if (!tableExists(database, "part") || !columnExists(database, "part", "_data")) {
        deleteTree(sourceRoot);
        complete = true;
        return;
      }

      boolean hasThumbnail = columnExists(database, "part", "thumbnail");
      String[] projection = hasThumbnail ? new String[]{"_id", "_data", "thumbnail"} :
              new String[]{"_id", "_data"};
      Map<Long, String[]> updates = new HashMap<>();
      Map<String, String> sourcePathByMember = new HashMap<>();
      if (!mappedFiles.mkdirs() && !mappedFiles.isDirectory()) {
        throw new IOException("Cannot create staged attachment directory");
      }

      try (Cursor cursor = database.query("part", projection, null, null, null, null, "_id ASC")) {
        while (cursor.moveToNext()) {
          long partId = cursor.getLong(0);
          String dataPath = cursor.isNull(1) ? null : cursor.getString(1);
          String thumbnailPath = hasThumbnail && !cursor.isNull(2) ? cursor.getString(2) : null;
          String restoredData = restoreReference(sourceRoot, mappedFiles, destinationRoot,
                  dataPath, pathMapping, sourcePathByMember);
          String restoredThumbnail = restoreReference(sourceRoot, mappedFiles, destinationRoot,
                  thumbnailPath, pathMapping, sourcePathByMember);
          if (!equal(dataPath, restoredData) || !equal(thumbnailPath, restoredThumbnail)) {
            updates.put(partId, new String[]{restoredData, restoredThumbnail});
          }
        }
      }

      database.beginTransaction();
      transactionOpen = true;
      for (Map.Entry<Long, String[]> update : updates.entrySet()) {
        ContentValues values = new ContentValues();
        values.put("_data", update.getValue()[0]);
        if (hasThumbnail) values.put("thumbnail", update.getValue()[1]);
        int changed = database.update("part", values, "_id = ?",
                new String[]{String.valueOf(update.getKey())});
        if (changed != 1) throw new IOException("Staged attachment metadata changed unexpectedly");
      }
      database.setTransactionSuccessful();
      database.endTransaction();
      transactionOpen = false;

      deleteTree(sourceRoot);
      complete = true;
    } catch (RuntimeException | IOException exception) {
      if (exception instanceof IOException) throw (IOException) exception;
      throw new IOException("Cannot prepare staged MMS attachments", exception);
    } finally {
      if (transactionOpen && database != null && database.inTransaction()) database.endTransaction();
      if (database != null) database.close();
      if (!complete) deleteTree(mappedFiles);
    }
  }

  /** Publishes only fresh, staged files; replay is safe after process death. */
  public static void publishStagedAttachments(@NonNull Context context,
                                               @NonNull File stagingRoot) throws IOException {
    File stagedParts = new File(stagingRoot, STAGED_PARTS_ENTRY);
    if (!stagedParts.exists()) return;
    requireDirectory(stagedParts);
    requireDirectChild(stagedParts, stagingRoot, STAGED_PARTS_ENTRY);
    File targetRoot = getOwnedPartsRoot(context);
    if (targetRoot.exists()) requireDirectory(targetRoot);
    else if (!targetRoot.mkdirs() && !targetRoot.isDirectory()) {
      throw new IOException("Cannot create app-owned attachment directory");
    }
    File canonicalRoot = targetRoot.getCanonicalFile();
    File appRoot = context.getFilesDir().getCanonicalFile().getParentFile();
    if (appRoot == null || !canonicalRoot.getParentFile().equals(appRoot) ||
            !canonicalRoot.getName().equals("app_parts")) {
      throw new IOException("Attachment destination is outside the app-owned data root");
    }
    syncDirectory(canonicalRoot.getParentFile());
    syncDirectory(canonicalRoot);

    removeInterruptedTemporaries(canonicalRoot);
    File[] stagedFiles = stagedParts.listFiles();
    if (stagedFiles == null) throw new IOException("Cannot list staged attachments");
    for (File stagedFile : stagedFiles) {
      if (!stagedFile.getName().matches(PART_FILENAME)) {
        throw new IOException("Unexpected staged attachment name");
      }
      requireRegularFile(stagedFile, stagedParts.getCanonicalFile());
      File destination = new File(canonicalRoot, stagedFile.getName());
      if (destination.exists()) {
        requireRegularFile(destination, canonicalRoot);
        if (!sameContents(stagedFile, destination)) {
          throw new IOException("Attachment destination collision");
        }
        continue;
      }

      File temporary = new File(canonicalRoot, TEMP_PREFIX + UUID.randomUUID() + ".tmp");
      try {
        copyAndSync(stagedFile, temporary);
        if (!temporary.renameTo(destination)) {
          if (!destination.exists() || !sameContents(stagedFile, destination)) {
            throw new IOException("Cannot publish staged attachment");
          }
        }
        syncDirectory(canonicalRoot);
      } finally {
        if (temporary.exists() && !temporary.delete()) {
          throw new IOException("Cannot remove temporary attachment file");
        }
      }
    }
  }

  /** Returns only existing, regular files referenced by the database and owned by app_parts. */
  public static Map<String, File> getReferencedOwnedFiles(@NonNull Context context,
                                                          @NonNull SQLiteDatabase database)
      throws IOException {
    Map<String, File> result = new HashMap<>();
    if (!tableExists(database, "part") || !columnExists(database, "part", "_data")) return result;
    File root = getOwnedPartsRoot(context);
    if (!root.exists()) return result;
    requireDirectory(root);
    File canonicalRoot = root.getCanonicalFile();
    boolean hasThumbnail = columnExists(database, "part", "thumbnail");
    String[] projection = hasThumbnail ? new String[]{"_data", "thumbnail"} :
            new String[]{"_data"};
    try (Cursor cursor = database.query("part", projection, null, null, null, null, null)) {
      while (cursor.moveToNext()) {
        for (int column = 0; column < projection.length; column++) {
          if (cursor.isNull(column)) continue;
          String path = cursor.getString(column);
          File candidate = new File(path);
          if (!candidate.isAbsolute() || candidate.getParentFile() == null ||
                  !candidate.getName().matches(PART_FILENAME) ||
                  !candidate.getParentFile().getCanonicalFile().equals(canonicalRoot)) continue;
          if (!candidate.exists()) continue;
          requireRegularFile(candidate, canonicalRoot);
          result.put(candidate.getName(), candidate);
        }
      }
    }
    return result;
  }

  public static File getOwnedPartsRoot(@NonNull Context context) throws IOException {
    File files = context.getFilesDir();
    File appRoot = files.getCanonicalFile().getParentFile();
    if (appRoot == null) throw new IOException("App data directory is unavailable");
    File parts = new File(appRoot, "app_parts");
    if (parts.exists() && !parts.getCanonicalFile().equals(parts.getAbsoluteFile())) {
      throw new IOException("Attachment directory resolves outside its expected location");
    }
    return parts;
  }

  private static String restoreReference(File sourceRoot, File mappedFiles, File destinationRoot,
                                        String sourcePath, Map<String, String> mappings,
                                        Map<String, String> sourcePathByMember)
      throws IOException {
    if (sourcePath == null || sourcePath.isEmpty()) return sourcePath;
    String relative = verifiedMemberName(sourcePath, destinationRoot);
    if (relative == null) return sourcePath;
    String existing = mappings.get(sourcePath);
    if (existing != null) return existing;

    String previousPath = sourcePathByMember.putIfAbsent(relative, sourcePath);
    if (previousPath != null && !previousPath.equals(sourcePath)) {
      throw new IOException("Ambiguous attachment reference in backup");
    }

    File source = new File(sourceRoot, relative);
    if (!source.exists()) return sourcePath;
    requireRegularFile(source, sourceRoot.getCanonicalFile());
    if (source.length() > MAX_ATTACHMENT_BYTES) throw new IOException("Staged attachment exceeds size limit");

    String destinationName = "part" + UUID.randomUUID().toString().replace("-", "") + ".mms";
    File stagedDestination = new File(mappedFiles, destinationName);
    copyAndSync(source, stagedDestination);
    String destinationPath = new File(destinationRoot, destinationName).getAbsolutePath();
    mappings.put(sourcePath, destinationPath);
    return destinationPath;
  }

  private static String verifiedMemberName(String sourcePath, File destinationRoot) throws IOException {
    if (!new File(sourcePath).isAbsolute() || sourcePath.indexOf('\0') >= 0 ||
            sourcePath.indexOf('\\') >= 0 || sourcePath.indexOf("/../") >= 0 ||
            sourcePath.endsWith("/..") || sourcePath.indexOf("/./") >= 0) return null;
    String currentPrefix = destinationRoot.getAbsolutePath() + File.separator;
    String relative;
    if (sourcePath.startsWith(currentPrefix)) {
      relative = sourcePath.substring(currentPrefix.length());
    } else {
      Matcher legacy = LEGACY_SILENCE_PATH.matcher(sourcePath);
      if (!legacy.matches()) return null;
      relative = legacy.group(1);
    }
    if (relative.isEmpty() || relative.contains("/") || !relative.matches(PART_FILENAME)) return null;
    return relative;
  }

  private static boolean tableExists(SQLiteDatabase database, String table) {
    try (Cursor cursor = database.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", new String[]{table})) {
      return cursor.moveToFirst();
    }
  }

  private static boolean columnExists(SQLiteDatabase database, String table, String column) {
    try (Cursor cursor = database.rawQuery("PRAGMA table_info(" + table + ")", null)) {
      while (cursor.moveToNext()) {
        if (column.equals(cursor.getString(cursor.getColumnIndexOrThrow("name")))) return true;
      }
    }
    return false;
  }

  private static void rejectNestedDirectories(File root) throws IOException {
    File[] children = root.listFiles();
    if (children == null) throw new IOException("Cannot list staged app_parts");
    for (File child : children) {
      if (child.isDirectory()) throw new IOException("Nested app_parts directories are unsupported");
      requireRegularFile(child, root.getCanonicalFile());
      if (!child.getName().matches(PART_FILENAME)) {
        throw new IOException("Unexpected file in staged app_parts");
      }
    }
  }

  private static void requireDirectory(File directory) throws IOException {
    try {
      if (!OsConstants.S_ISDIR(Os.lstat(directory.getAbsolutePath()).st_mode)) {
        throw new IOException("Expected an app-owned directory");
      }
    } catch (ErrnoException exception) {
      throw new IOException("Cannot verify directory", exception);
    }
  }

  private static void requireDirectChild(File child, File parent, String expectedName)
      throws IOException {
    File canonicalParent = parent.getCanonicalFile();
    File canonicalChild = child.getCanonicalFile();
    if (!expectedName.equals(child.getName()) ||
            !canonicalParent.equals(canonicalChild.getParentFile())) {
      throw new IOException("Attachment staging directory is outside its expected parent");
    }
  }

  private static void requireRegularFile(File file, File expectedParent) throws IOException {
    try {
      if (!OsConstants.S_ISREG(Os.lstat(file.getAbsolutePath()).st_mode) ||
              !file.getCanonicalFile().getParentFile().equals(expectedParent.getCanonicalFile())) {
        throw new IOException("Expected a regular file in the approved directory");
      }
    } catch (ErrnoException exception) {
      throw new IOException("Cannot verify attachment file", exception);
    }
  }

  private static void copyAndSync(File source, File destination) throws IOException {
    try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(source));
         FileOutputStream raw = new FileOutputStream(destination);
         BufferedOutputStream output = new BufferedOutputStream(raw)) {
      byte[] buffer = new byte[64 * 1024];
      int read;
      long total = 0;
      while ((read = input.read(buffer)) != -1) {
        total += read;
        if (total > MAX_ATTACHMENT_BYTES) throw new IOException("Attachment exceeds size limit");
        output.write(buffer, 0, read);
      }
      output.flush();
      raw.getFD().sync();
    }
  }

  private static void syncDirectory(File directory) throws IOException {
    java.io.FileDescriptor descriptor = null;
    try {
      if (!OsConstants.S_ISDIR(Os.lstat(directory.getAbsolutePath()).st_mode)) {
        throw new IOException("Expected a directory to sync");
      }
      descriptor = Os.open(directory.getAbsolutePath(), OsConstants.O_RDONLY, 0);
      Os.fsync(descriptor);
    } catch (ErrnoException | SecurityException exception) {
      throw new IOException("Cannot sync attachment directory", exception);
    } finally {
      if (descriptor != null) {
        try { Os.close(descriptor); }
        catch (ErrnoException ignored) { }
      }
    }
  }

  private static boolean sameContents(File first, File second) throws IOException {
    if (first.length() != second.length()) return false;
    return MessageDigest.isEqual(digest(first), digest(second));
  }

  private static byte[] digest(File file) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
      }
      return digest.digest();
    } catch (NoSuchAlgorithmException exception) {
      throw new AssertionError(exception);
    }
  }

  private static void removeInterruptedTemporaries(File root) throws IOException {
    File[] files = root.listFiles(file -> file.getName().matches("\\.smsecure-restore-[A-Za-z0-9-]+\\.tmp"));
    if (files == null) throw new IOException("Cannot inspect attachment directory");
    for (File file : files) {
      requireRegularFile(file, root);
      if (!file.delete()) throw new IOException("Cannot remove interrupted attachment staging file");
    }
  }

  private static void deleteTree(File file) throws IOException {
    if (file == null || !file.exists()) return;
    File[] children = file.listFiles();
    if (children != null) {
      for (File child : children) deleteTree(child);
    }
    if (!file.delete() && file.exists()) throw new IOException("Cannot clear staged attachment data");
  }

  private static boolean equal(String left, String right) {
    return left == null ? right == null : left.equals(right);
  }
}
