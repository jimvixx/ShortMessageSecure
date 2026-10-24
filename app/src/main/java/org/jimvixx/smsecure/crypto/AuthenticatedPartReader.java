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

package org.jimvixx.smsecure.crypto;

import android.content.Context;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Provides plaintext only after an encrypted part has been completely authenticated. */
public final class AuthenticatedPartReader {

  private static final int COPY_BUFFER_SIZE = 32 * 1024;
  private static final String TEMP_PREFIX = "mms-verified-";

  private AuthenticatedPartReader() {}

  /**
   * Decrypts and authenticates the complete legacy part into app-private cache storage.
   * The returned handle must be closed promptly; no plaintext stream is exposed on failure.
   *
   * @param maxPlaintextBytes maximum plaintext staged, including zero for an empty part
   */
  @NonNull
  public static VerifiedPlaintext authenticate(@NonNull Context context,
                                                @NonNull File encryptedFile,
                                                @NonNull MasterSecret masterSecret,
                                                long maxPlaintextBytes) throws IOException {
    if (maxPlaintextBytes < 0) throw new IllegalArgumentException("Negative plaintext limit");
    File cache = context.getCacheDir();
    if (cache == null || !cache.isDirectory()) throw new IOException("Private cache unavailable");
    File canonicalCache = cache.getCanonicalFile();
    File staging = File.createTempFile(TEMP_PREFIX, ".tmp", canonicalCache);
    try {
      File canonicalStaging = staging.getCanonicalFile();
      if (!canonicalCache.equals(canonicalStaging.getParentFile()) || !canonicalStaging.isFile()) {
        throw new IOException("Unsafe private staging file");
      }

      long total = 0;
      byte[] buffer = new byte[COPY_BUFFER_SIZE];
      try (DecryptingPartInputStream input = new DecryptingPartInputStream(encryptedFile, masterSecret);
           FileOutputStream output = new FileOutputStream(canonicalStaging, false)) {
        int count;
        while ((count = input.read(buffer)) != -1) {
          if (count > maxPlaintextBytes - total) {
            throw new IOException("Decrypted part exceeds configured limit");
          }
          output.write(buffer, 0, count);
          total += count;
        }
        output.flush();
        output.getFD().sync();
      } finally {
        java.util.Arrays.fill(buffer, (byte) 0);
      }

      return new VerifiedPlaintext(canonicalStaging);
    } catch (IOException | RuntimeException exception) {
      if (staging.exists() && !staging.delete()) {
        exception.addSuppressed(new IOException("Unable to remove failed plaintext staging file"));
      }
      throw exception;
    }
  }

  /** Handle for fully authenticated plaintext stored in private cache until closed. */
  public static final class VerifiedPlaintext implements AutoCloseable {
    private final File file;
    private InputStream activeStream;
    private boolean opened;
    private boolean closed;

    private VerifiedPlaintext(File file) {
      this.file = file;
    }

    /** Opens the verified contents once. The file itself is never exposed. */
    @NonNull
    public synchronized InputStream openStream() throws IOException {
      if (closed) throw new IOException("Verified plaintext handle is closed");
      if (opened) throw new IOException("Verified plaintext stream already opened");
      activeStream = new FileInputStream(file);
      opened = true;
      return activeStream;
    }

    @Override
    public synchronized void close() throws IOException {
      if (closed) return;
      closed = true;
      IOException failure = null;
      if (activeStream != null) {
        try {
          activeStream.close();
        } catch (IOException exception) {
          failure = exception;
        }
      }
      if (file.exists() && !file.delete()) {
        IOException deleteFailure = new IOException("Unable to remove verified plaintext staging file");
        if (failure == null) failure = deleteFailure;
        else failure.addSuppressed(deleteFailure);
      }
      if (failure != null) throw failure;
    }
  }
}
