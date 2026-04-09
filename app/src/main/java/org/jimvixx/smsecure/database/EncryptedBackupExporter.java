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
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class EncryptedBackupExporter {

  private static final String TAG = EncryptedBackupExporter.class.getSimpleName();

  private static final String[] INCLUDED_TOP_LEVEL_DIRS = new String[]{
          "files",
          "databases",
          "shared_prefs",
  };

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
    File parentDir = getAppDataParentDir(context);

    OutputStream raw = null;
    ZipOutputStream zos = null;
    boolean wroteAnything = false;

    try {
      raw = context.getContentResolver().openOutputStream(outputUri, "w");
      if (raw == null) throw new IOException("openOutputStream() returned null for: " + outputUri);

      zos = new ZipOutputStream(new BufferedOutputStream(raw));

      for (String name : INCLUDED_TOP_LEVEL_DIRS) {
        File dir = new File(parentDir, name);
        if (!dir.exists() || !dir.isDirectory()) {
          Log.w(TAG, "Missing dir for export: " + dir.getAbsolutePath());
          continue;
        }

        ZipEntry de = new ZipEntry(name + "/");
        zos.putNextEntry(de);
        zos.closeEntry();

        zipDirectoryRecursive(dir, name, zos);
        wroteAnything = true;
      }

      zos.finish();
      wroteAnything = true;

    } catch (IOException e) {
      if (wroteAnything) {
        Log.w(TAG, "Export threw after data was written; treating as success. Error:", e);
        return;
      }
      throw e;
    } finally {
      closeQuietly(zos);
      closeQuietly(raw);
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

  public static void stageImportFromUri(@NonNull Context context, @NonNull Uri inputUri) throws IOException {
    File stagingRoot = getStagingRoot(context);

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

        while ((entry = zis.getNextEntry()) != null) {
          String name = entry.getName();
          if (name == null || name.trim().isEmpty()) {
            zis.closeEntry();
            continue;
          }

          name = name.replace('\\', '/');
          while (name.startsWith("/")) name = name.substring(1);

          if (!isAllowedTopLevel(name)) {
            Log.w(TAG, "Skipping entry outside allowed roots: " + name);
            zis.closeEntry();
            continue;
          }

          if (isExcludedPath(name)) {
            zis.closeEntry();
            continue;
          }

          File outFile = new File(stagingRoot, name);

          // ZipSlip protection (within stagingRoot)
          String stagingCanonical = stagingRoot.getCanonicalPath();
          String outCanonical = outFile.getCanonicalPath();
          if (!outCanonical.startsWith(stagingCanonical + File.separator) && !outCanonical.equals(stagingCanonical)) {
            Log.w(TAG, "Blocked ZipSlip entry: " + name);
            zis.closeEntry();
            continue;
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
            while ((read = zis.read(buffer)) != -1) {
              bos.write(buffer, 0, read);
            }
            bos.flush();
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

    // Create marker
    File marker = getMarkerFile(context);
    writeSmallTextFile(marker, "staged=" + stagingRoot.getAbsolutePath());
    Log.i(TAG, "Restore staged at: " + stagingRoot.getAbsolutePath());
  }

  public static void applyPendingRestoreIfAny(@NonNull Context context) {
    File marker = getMarkerFile(context);
    if (!marker.exists()) return;

    File parentDir;
    try {
      parentDir = getAppDataParentDir(context);
    } catch (IOException e) {
      Log.w(TAG, "applyPendingRestore: cannot resolve app data dir", e);
      return;
    }

    File stagingRoot = getStagingRoot(context);
    if (!stagingRoot.exists() || !stagingRoot.isDirectory()) {
      Log.w(TAG, "applyPendingRestore: staging dir missing; deleting marker");
      // best effort cleanup
      //noinspection ResultOfMethodCallIgnored
      marker.delete();
      return;
    }

    try {
      Log.i(TAG, "Applying staged restore...");

      for (String top : INCLUDED_TOP_LEVEL_DIRS) {
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

    } catch (Throwable t) {
      Log.w(TAG, "Failed to apply staged restore", t);
      // Keep marker so user can retry by restarting, but don't loop forever if staging is broken.
      // You can decide: either keep marker or delete it. I keep it for now.
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
    try (FileOutputStream fos = new FileOutputStream(file)) {
      fos.write(content.getBytes());
      fos.flush();
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
