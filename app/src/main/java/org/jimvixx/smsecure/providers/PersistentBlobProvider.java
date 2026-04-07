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

package org.jimvixx.smsecure.providers;

import android.annotation.SuppressLint;
import android.content.ContentUris;
import android.content.Context;
import android.content.UriMatcher;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jimvixx.smsecure.crypto.EncryptingPartOutputStream;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.util.Util;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PersistentBlobProvider {

  public static final String AUTHORITY = "org.jimvixx.smsecure";
  public static final String EXPECTED_PATH = "capture/*/*/#";
  public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/capture");

  private static final String TAG = PersistentBlobProvider.class.getSimpleName();
  private static final String URI_STRING = "content://" + AUTHORITY + "/capture";
  private static final String BLOB_EXTENSION = "blob";
  private static final int MATCH = 1;
  private static final int MIMETYPE_PATH_SEGMENT = 1;

  private static final UriMatcher MATCHER = buildUriMatcher();

  private static volatile PersistentBlobProvider instance;

  private final Context context;

  @SuppressLint("UseSparseArrays")
  private final Map<Long, byte[]> cache = Collections.synchronizedMap(new HashMap<>());

  private final ExecutorService executor = Executors.newCachedThreadPool();

  private PersistentBlobProvider(@NonNull Context context) {
    this.context = context.getApplicationContext();
  }

  public static PersistentBlobProvider getInstance(@NonNull Context context) {
    if (instance == null) {
      synchronized (PersistentBlobProvider.class) {
        if (instance == null) {
          instance = new PersistentBlobProvider(context);
        }
      }
    }

    return instance;
  }

  private static @NonNull UriMatcher buildUriMatcher() {
    UriMatcher matcher = new UriMatcher(UriMatcher.NO_MATCH);
    matcher.addURI(AUTHORITY, EXPECTED_PATH, MATCH);
    return matcher;
  }

  public static boolean isAuthority(@NonNull Context context, @NonNull Uri uri) {
    return MATCHER.match(uri) == MATCH || isExternalBlobUri(context, uri);
  }

  public static @Nullable String getMimeType(@NonNull Context context,
                                             @NonNull Uri persistentBlobUri) {
    if (!isAuthority(context, persistentBlobUri)) {
      return null;
    }

    if (isExternalBlobUri(context, persistentBlobUri)) {
      return getMimeTypeFromExtension(persistentBlobUri);
    }

    return persistentBlobUri.getPathSegments().get(MIMETYPE_PATH_SEGMENT);
  }

  private static @NonNull String getMimeTypeFromExtension(@NonNull Uri uri) {
    String extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
    String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
    return mimeType != null ? mimeType : "application/octet-stream";
  }

  private static boolean isExternalBlobUri(@NonNull Context context, @NonNull Uri uri) {
    try {
      String path = uri.getPath();
      return path != null && path.startsWith(getExternalDir(context).getAbsolutePath());
    } catch (IOException ioe) {
      return false;
    }
  }

  private static @NonNull File getExternalDir(@NonNull Context context) throws IOException {
    File externalDir = context.getExternalFilesDir(null);

    if (externalDir == null) {
      throw new IOException("No external files directory");
    }

    return externalDir;
  }

  public @NonNull Uri create(@NonNull MasterSecret masterSecret,
                             @NonNull byte[] blobBytes,
                             @NonNull String mimeType) {
    long id = System.currentTimeMillis();
    cache.put(id, blobBytes);
    return create(masterSecret, new ByteArrayInputStream(blobBytes), id, mimeType);
  }

  public @NonNull Uri create(@NonNull MasterSecret masterSecret,
                             @NonNull InputStream input,
                             @NonNull String mimeType) {
    return create(masterSecret, input, System.currentTimeMillis(), mimeType);
  }

  private @NonNull Uri create(@NonNull MasterSecret masterSecret,
                              @NonNull InputStream input,
                              long id,
                              @NonNull String mimeType) {
    persistToDisk(masterSecret, id, input);

    Uri uniqueUri = CONTENT_URI.buildUpon()
            .appendPath(mimeType)
            .appendEncodedPath(String.valueOf(System.currentTimeMillis()))
            .build();

    return ContentUris.withAppendedId(uniqueUri, id);
  }

  private void persistToDisk(@NonNull MasterSecret masterSecret,
                             long id,
                             @NonNull InputStream input) {
    executor.submit(() -> {
      try (InputStream in = input;
           OutputStream output = new EncryptingPartOutputStream(getFile(id), masterSecret)) {
        Log.w(TAG, "Starting stream copy...");
        Util.copy(in, output);
        Log.w(TAG, "Stream copy finished.");
      } catch (IOException e) {
        Log.w(TAG, e);
      } finally {
        cache.remove(id);
      }
    });
  }

  public boolean delete(@NonNull Uri uri) {
    if (MATCHER.match(uri) == MATCH) {
      long id = ContentUris.parseId(uri);
      cache.remove(id);
      return getFile(id).delete();
    }

    String path = uri.getPath();
    return path != null && new File(path).delete();
  }

  private @NonNull File getFile(long id) {
    return new File(context.getDir("captures", Context.MODE_PRIVATE), id + "." + BLOB_EXTENSION);
  }
}