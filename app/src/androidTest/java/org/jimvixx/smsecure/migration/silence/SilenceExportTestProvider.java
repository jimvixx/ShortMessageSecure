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

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Test-only SAF-shaped provider: faults are returned through real ContentResolver calls. */
public class SilenceExportTestProvider extends ContentProvider {
  static final String AUTHORITY = "org.jimvixx.smsecure.test.silence.documents";
  private Bundle files = new Bundle();
  private ArrayList<String> directories = new ArrayList<>();
  private String fault = "";

  @Override public boolean onCreate() { return true; }

  @Override public Bundle call(String method, String arg, Bundle extras) {
    files = extras.getBundle("files");
    directories = extras.getStringArrayList("directories");
    fault = extras.getString("fault", "");
    return Bundle.EMPTY;
  }

  @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
    String id = DocumentsContract.getDocumentId(uri);
    String prefix = id.equals("SilenceExport") ? "" : id.substring("SilenceExport/".length()) + "/";
    if (fault.equals("listing") && prefix.equals("files/")) return null;
    MatrixCursor cursor = new MatrixCursor(projection);
    List<String> paths = new ArrayList<>(directories);
    paths.addAll(files.keySet());
    for (String path : paths) {
      if (!path.startsWith(prefix) || path.substring(prefix.length()).contains("/")) continue;
      String name = path.substring(prefix.length());
      if (fault.equals("unsafe") && path.equals("files/blob")) name = "../escape";
      Object[] row = new Object[projection.length];
      for (int i = 0; i < projection.length; i++) {
        if (projection[i].equals(DocumentsContract.Document.COLUMN_DOCUMENT_ID)) row[i] = "SilenceExport/" + path;
        if (projection[i].equals(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) row[i] = name;
        if (projection[i].equals(DocumentsContract.Document.COLUMN_MIME_TYPE))
          row[i] = directories.contains(path) ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream";
      }
      cursor.addRow(row);
      if (fault.equals("duplicate") && path.equals("files/blob")) cursor.addRow(Arrays.copyOf(row, row.length));
    }
    Bundle extras = new Bundle();
    // Return the full fixture (including required files) with explicit incomplete/error metadata.
    if (fault.equals("loading")) extras.putBoolean(DocumentsContract.EXTRA_LOADING, true);
    if (fault.equals("error")) extras.putString(DocumentsContract.EXTRA_ERROR, "Fixture listing error");
    if (fault.equals("null-error")) extras.putString(DocumentsContract.EXTRA_ERROR, null);
    cursor.setExtras(extras);
    return cursor;
  }

  @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!mode.equals("r")) throw new FileNotFoundException("Read-only fixture");
    String path = DocumentsContract.getDocumentId(uri).substring("SilenceExport/".length());
    if (fault.equals("unreadable") && path.equals("files/blob")) throw new FileNotFoundException("Fixture read failure");
    byte[] bytes = files.getByteArray(path);
    if (bytes == null) throw new FileNotFoundException(path);
    try {
      File file = File.createTempFile("silence-fixture-", ".bin", getContext().getCacheDir());
      try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
      ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
      if (!file.delete()) { fd.close(); throw new IOException("Fixture cleanup failed"); }
      return fd;
    } catch (IOException e) { throw new FileNotFoundException(e.toString()); }
  }

  @Override public String getType(Uri uri) { return "application/octet-stream"; }
  @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
  @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
  @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
