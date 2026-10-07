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
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

import org.jimvixx.smsecure.database.EncryptedBackupExporter;
import org.jimvixx.smsecure.logging.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Packages a selected SilenceExport directory for the existing encrypted restore. */
public final class SilenceBackupAdapter {
  private static final String TEMP_PREFIX = "silence-restore-";
  private static final String LEGACY_PREFS = "shared_prefs/org.smssecure.smssecure_preferences.xml";
  private static final String TARGET_PREFS = "shared_prefs/org.jimvixx.smsecure_preferences.xml";
  private static final String[] ROOTS = {"files", "databases", "shared_prefs"};
  private static final String[] REQUIRED = {
          "databases/messages.db", "shared_prefs/SecureSMS-Preferences.xml", LEGACY_PREFS
  };
  private static final String[] COLUMNS = {
          DocumentsContract.Document.COLUMN_DOCUMENT_ID,
          DocumentsContract.Document.COLUMN_DISPLAY_NAME,
          DocumentsContract.Document.COLUMN_MIME_TYPE
  };

  private SilenceBackupAdapter() {}

  public static synchronized void stageImport(Context context, Uri directory) throws IOException {
    File archive = createArchive(context, directory);
    try {
      checkCancelled();
      EncryptedBackupExporter.stageImportFromUri(context, Uri.fromFile(archive));
    } finally {
      deleteArchive(archive);
    }
  }

  // Package-visible so tests can verify the archive without scheduling a restore.
  static synchronized File createArchive(Context context, Uri directory) throws IOException {
    // stageImport holds the same lock through handoff: never remove an active archive.
    File[] abandoned = context.getCacheDir().listFiles(file -> file.isFile() &&
            file.getName().startsWith(TEMP_PREFIX) && file.getName().endsWith(".zip"));
    if (abandoned == null) throw new IOException("Cannot list temporary Silence archives");
    for (File file : abandoned) {
      if (!file.delete()) throw new IOException("Cannot remove abandoned Silence archive");
    }
    File archive = File.createTempFile(TEMP_PREFIX, ".zip", context.getCacheDir());
    FileOutputStream output;
    try {
      output = new FileOutputStream(archive);
    } catch (IOException e) {
      deleteArchive(archive);
      throw e;
    }
    return createArchive(context, directory, archive, output);
  }

  // Stream overload permits deterministic write-failure/interruption tests, not a second adapter path.
  static File createArchive(Context context, Uri directory, File archive, OutputStream output) throws IOException {
    boolean complete = false;
    try {
      try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(output))) {
        checkCancelled();
        String rootId = DocumentsContract.getTreeDocumentId(directory);
        Set<String> directories = new HashSet<>();
        Set<String> nonemptyFiles = new HashSet<>();
        copyDirectory(context, directory, rootId, "", zip, directories, nonemptyFiles, 0);
        for (String root : ROOTS) {
          if (!directories.contains(root)) throw new IOException("Missing Silence directory: " + root);
        }
        for (String required : REQUIRED) {
          if (!nonemptyFiles.contains(required)) throw new IOException("Missing or empty Silence file: " + required);
        }
      }
      checkCancelled();
      complete = true;
      return archive;
    } catch (RuntimeException e) {
      throw new IOException("Cannot read the selected Silence export", e);
    } finally {
      if (!complete) deleteArchive(archive);
    }
  }

  private static void copyDirectory(Context context, Uri tree, String id, String prefix,
                                    ZipOutputStream zip, Set<String> directories,
                                    Set<String> nonemptyFiles, int depth) throws IOException {
    checkCancelled();
    if (depth > 64) throw new IOException("Silence directory nesting is too deep");
    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
    try (Cursor cursor = context.getContentResolver().query(children, COLUMNS, null, null, null)) {
      if (cursor == null) throw new IOException("Cannot list Silence directory");
      Bundle extras = cursor.getExtras();
      if (extras != null && (extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) ||
              extras.containsKey(DocumentsContract.EXTRA_ERROR))) {
        throw new IOException("Incomplete or failed Silence directory listing");
      }
      Set<String> names = new HashSet<>();
      while (cursor.moveToNext()) {
        checkCancelled();
        String childId = cursor.getString(0);
        String name = cursor.getString(1);
        String mime = cursor.getString(2);
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") ||
                name.contains("/") || name.contains("\\") || name.indexOf('\0') >= 0 || !names.add(name)) {
          throw new IOException("Invalid or duplicate Silence entry name");
        }
        if (childId == null || mime == null) throw new IOException("Incomplete Silence document metadata");
        if (prefix.isEmpty() && !name.equals("files") && !name.equals("databases") && !name.equals("shared_prefs")) {
          continue; // Match the existing backup's three data roots, not unrelated export siblings.
        }
        String source = prefix.isEmpty() ? name : prefix + "/" + name;
        if (source.equals(TARGET_PREFS)) throw new IOException("Conflicting preferences filenames in Silence export");
        boolean isDirectory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
        if (prefix.isEmpty() && !isDirectory) throw new IOException("Silence data root is not a directory");
        String target = source.equals(LEGACY_PREFS) ? TARGET_PREFS : source;
        zip.putNextEntry(new ZipEntry(target + (isDirectory ? "/" : "")));
        if (isDirectory) {
          directories.add(source);
          zip.closeEntry();
          copyDirectory(context, tree, childId, source, zip, directories, nonemptyFiles, depth + 1);
        } else {
          Uri file = DocumentsContract.buildDocumentUriUsingTree(tree, childId);
          try (InputStream input = context.getContentResolver().openInputStream(file)) {
            if (input == null) throw new IOException("Cannot open Silence file");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
              checkCancelled();
              zip.write(buffer, 0, read);
              if (read > 0) nonemptyFiles.add(source);
            }
          }
          zip.closeEntry();
        }
      }
    }
  }

  private static void checkCancelled() throws InterruptedIOException {
    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Silence restore cancelled");
  }

  private static void deleteArchive(File archive) {
    if (!archive.delete() && archive.exists()) Log.w("SilenceBackupAdapter", "Cannot remove temporary archive");
  }
}
