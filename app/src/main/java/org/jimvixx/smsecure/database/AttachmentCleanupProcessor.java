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
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;

/** Processes only cleanup entries that can be safely unlinked from the app-owned parts directory. */
public final class AttachmentCleanupProcessor {
  // Future attachment writers and adopters must use this lock and never reuse allocated filenames.
  private static final Object LIFECYCLE_LOCK = new Object();
  private static final String EXPECTED_FILENAME = "part[A-Za-z0-9]+\\.mms";

  private AttachmentCleanupProcessor() {}

  static Object lifecycleLock() {
    return LIFECYCLE_LOCK;
  }

  public static int processBatch(@NonNull Context context,
                                 @NonNull SQLiteOpenHelper helper,
                                 long now,
                                 int limit) {
    return processBatch(context, helper, now, limit, AttachmentCleanupProcessor::unlinkOwnedFile);
  }

  static int processBatch(@NonNull Context context, @NonNull SQLiteOpenHelper helper, long now,
                         int limit, @NonNull FileUnlinker unlinker) {
    if (limit <= 0) return 0;
    AttachmentCleanupDatabase queue = new AttachmentCleanupDatabase(context, helper);
    queue.reactivateSharedEntries();
    int processed = 0;
    for (AttachmentCleanupDatabase.CleanupEntry entry : queue.getDueEntries(now,
            Math.min(limit, AttachmentCleanupDatabase.BATCH_LIMIT))) {
      synchronized (LIFECYCLE_LOCK) {
        processOne(context, helper, queue, entry, now, unlinker);
      }
      processed++;
    }
    return processed;
  }

  private static void processOne(Context context, SQLiteOpenHelper helper,
                                 AttachmentCleanupDatabase queue,
                                 AttachmentCleanupDatabase.CleanupEntry entry, long now,
                                 FileUnlinker unlinker) {
    SQLiteDatabase database = helper.getWritableDatabase();
    database.beginTransaction();
    try {
      if (queue.getEntry(entry.id) == null) {
        database.setTransactionSuccessful();
        return;
      }
      if (entry.type != AttachmentCleanupDatabase.TYPE_DATA &&
              entry.type != AttachmentCleanupDatabase.TYPE_THUMBNAIL) {
        queue.markBlocked(database, entry.id, AttachmentCleanupDatabase.ERROR_UNSAFE_PATH);
      } else if (queue.isReferenced(database, entry.path)) {
        queue.markBlocked(database, entry.id, AttachmentCleanupDatabase.ERROR_REFERENCED);
      } else if (entry.encrypted == null || (entry.encrypted != 0 && entry.encrypted != 1)) {
        queue.markBlocked(database, entry.id,
                AttachmentCleanupDatabase.ERROR_UNKNOWN_ENCRYPTION);
      } else {
        int status = unlinker.unlink(context, entry.path);
        if (status == AttachmentCleanupDatabase.ERROR_NONE) {
          queue.markComplete(database, entry.id);
        } else if (status == AttachmentCleanupDatabase.ERROR_IO) {
          queue.markRetry(database, entry, status, now);
        } else {
          queue.markBlocked(database, entry.id, status);
        }
      }
      database.setTransactionSuccessful();
    } finally {
      database.endTransaction();
    }
  }

  interface FileUnlinker {
    int unlink(Context context, String path);
  }

  static int unlinkOwnedFile(Context context, String candidate) {
    File root = context.getDir("parts", Context.MODE_PRIVATE);
    String rootPath = root.getAbsolutePath();
    File canonicalRoot;
    try {
      canonicalRoot = root.getCanonicalFile();
      File appDataRoot = context.getFilesDir().getCanonicalFile().getParentFile();
      if (appDataRoot == null || !appDataRoot.equals(canonicalRoot.getParentFile()) ||
              !canonicalRoot.isDirectory()) {
        return AttachmentCleanupDatabase.ERROR_UNSAFE_PATH;
      }
    } catch (IOException exception) {
      return AttachmentCleanupDatabase.ERROR_IO;
    }
    String prefix = rootPath + File.separator;
    if (candidate == null || !candidate.startsWith(prefix)) {
      return AttachmentCleanupDatabase.ERROR_UNSAFE_PATH;
    }
    String name = candidate.substring(prefix.length());
    if (!name.matches(EXPECTED_FILENAME) || name.contains(File.separator) || name.equals(".") ||
            name.equals("..")) {
      return AttachmentCleanupDatabase.ERROR_UNSAFE_PATH;
    }
    try {
      StructStat candidateAttributes = Os.lstat(candidate);
      if (!OsConstants.S_ISREG(candidateAttributes.st_mode)) {
        return AttachmentCleanupDatabase.ERROR_UNSAFE_PATH;
      }
    } catch (ErrnoException exception) {
      if (exception.errno == OsConstants.ENOENT) return AttachmentCleanupDatabase.ERROR_NONE;
      return AttachmentCleanupDatabase.ERROR_IO;
    }
    if (Build.VERSION.SDK_INT < 26) {
      return AttachmentCleanupDatabase.ERROR_UNSUPPORTED_FILESYSTEM;
    }
    try {
      return unlinkWithSecureDirectoryStream(canonicalRoot.toPath(),
              canonicalRoot.toPath().getFileSystem().getPath(name));
    } catch (IOException | SecurityException | UnsupportedOperationException exception) {
      return AttachmentCleanupDatabase.ERROR_IO;
    }
  }

  @RequiresApi(26)
  private static int unlinkWithSecureDirectoryStream(Path root, Path name) throws IOException {
    if (Files.isSymbolicLink(root)) return AttachmentCleanupDatabase.ERROR_UNSAFE_PATH;
    try (DirectoryStream<Path> opened = Files.newDirectoryStream(root)) {
      if (!(opened instanceof SecureDirectoryStream)) {
        return AttachmentCleanupDatabase.ERROR_UNSUPPORTED_FILESYSTEM;
      }
      @SuppressWarnings("unchecked")
      SecureDirectoryStream<Path> directory = (SecureDirectoryStream<Path>) opened;
      BasicFileAttributeView view = directory.getFileAttributeView(name,
              BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
      BasicFileAttributes attributes;
      try {
        attributes = view.readAttributes();
      } catch (NoSuchFileException missing) {
        return AttachmentCleanupDatabase.ERROR_NONE;
      }
      if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
        return AttachmentCleanupDatabase.ERROR_UNSAFE_PATH;
      }
      directory.deleteFile(name);
      return AttachmentCleanupDatabase.ERROR_NONE;
    }
  }
}
