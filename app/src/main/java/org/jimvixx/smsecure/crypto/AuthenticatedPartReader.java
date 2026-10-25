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
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileLock;
import java.util.Arrays;

/** Authenticates an MMS part before exposing a second-pass plaintext stream. */
public final class AuthenticatedPartReader {

  private static final int BUFFER_SIZE = 32 * 1024;
  private static final long LEGACY_OVERHEAD_AND_PADDING = 52;
  private static final String TEMP_PREFIX = "mms-ciphertext-";
  private static final Object TEMPORARY_FILE_LOCK = new Object();

  private AuthenticatedPartReader() {}

  /**
   * Copies a private ciphertext source into a unique temporary file, unlinks that file after
   * opening it, authenticates and validates one pass, then returns a second pass over the same
   * open, unlinked inode. No plaintext is written to disk or returned before authentication.
   *
   * @param maxPlaintextBytes maximum accepted plaintext size, including zero for an empty part
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

    synchronized (TEMPORARY_FILE_LOCK) {
      removeAbandonedCiphertextSnapshots(canonicalCache);
      File snapshotPath = File.createTempFile(TEMP_PREFIX, ".tmp", canonicalCache);
      FileInputStream snapshotInput = null;
      DecryptingPartInputStream validationStream = null;
      DecryptingPartInputStream consumerStream = null;
      boolean success = false;
      try {
        copyStablePrivateSource(context, encryptedFile, snapshotPath, maxPlaintextBytes);
        StructStat snapshotPathStat = lstat(snapshotPath);
        snapshotInput = new FileInputStream(snapshotPath);
        StructStat snapshotOpenedStat = fstat(snapshotInput.getFD());
        if (!OsConstants.S_ISREG(snapshotOpenedStat.st_mode) ||
                !sameFile(snapshotPathStat, snapshotOpenedStat)) {
          throw new IOException("Ciphertext snapshot changed while opening");
        }
        if (!snapshotPath.delete()) throw new IOException("Cannot unlink ciphertext snapshot");

        FileDescriptor descriptor = snapshotInput.getFD();
        validationStream = new DecryptingPartInputStream(descriptor, masterSecret);
        byte[] buffer = new byte[BUFFER_SIZE];
        long plaintextBytes = 0;
        try {
          int count;
          while ((count = validationStream.read(buffer)) != -1) {
            if (count > maxPlaintextBytes - plaintextBytes) {
              throw new IOException("Decrypted part exceeds configured limit");
            }
            plaintextBytes += count;
            Arrays.fill(buffer, 0, count, (byte) 0);
          }
        } finally {
          Arrays.fill(buffer, (byte) 0);
        }

        snapshotInput.getChannel().position(0);
        consumerStream = new DecryptingPartInputStream(descriptor, masterSecret);
        success = true;
        return new VerifiedPlaintext(snapshotInput, validationStream, consumerStream);
      } catch (IOException | RuntimeException exception) {
        IOException closeFailure = closeResources(consumerStream, validationStream, snapshotInput);
        if (closeFailure != null) exception.addSuppressed(closeFailure);
        if (snapshotPath.exists() && !snapshotPath.delete()) {
          exception.addSuppressed(new IOException("Cannot remove failed ciphertext snapshot"));
        }
        throw exception;
      } finally {
        if (!success && snapshotPath.exists()) snapshotPath.delete();
      }
    }
  }

  private static void copyStablePrivateSource(Context context, File source, File destination,
                                              long maxPlaintextBytes) throws IOException {
    if (!source.isAbsolute()) throw new IOException("Attachment source must be absolute");
    File appDataRoot = context.getFilesDir().getCanonicalFile().getParentFile();
    File canonicalSource = source.getCanonicalFile();
    if (appDataRoot == null || !isDescendant(appDataRoot, canonicalSource)) {
      throw new IOException("Attachment source is outside app-private storage");
    }

    StructStat pathStat = lstat(source);
    if (!OsConstants.S_ISREG(pathStat.st_mode)) throw new IOException("Attachment source is not regular");
    long maximumCiphertextBytes = maxPlaintextBytes > Long.MAX_VALUE - LEGACY_OVERHEAD_AND_PADDING
            ? Long.MAX_VALUE : maxPlaintextBytes + LEGACY_OVERHEAD_AND_PADDING;
    if (pathStat.st_size > maximumCiphertextBytes) {
      throw new IOException("Encrypted part exceeds configured plaintext limit");
    }

    try (FileInputStream input = new FileInputStream(canonicalSource);
         FileLock ignored = input.getChannel().lock(0L, Long.MAX_VALUE, true);
         FileOutputStream output = new FileOutputStream(destination, false)) {
      StructStat openedStat = fstat(input.getFD());
      if (!sameFile(pathStat, openedStat) || !OsConstants.S_ISREG(openedStat.st_mode)) {
        throw new IOException("Attachment source changed while opening");
      }
      long initialSize = input.getChannel().size();
      if (initialSize != openedStat.st_size || initialSize > maximumCiphertextBytes) {
        throw new IOException("Attachment source size changed");
      }

      byte[] buffer = new byte[BUFFER_SIZE];
      long copied = 0;
      try {
        int count;
        while ((count = input.read(buffer)) != -1) {
          if (count > maximumCiphertextBytes - copied) {
            throw new IOException("Encrypted part exceeds configured plaintext limit");
          }
          output.write(buffer, 0, count);
          copied += count;
        }
        output.flush();
        output.getFD().sync();
      } finally {
        Arrays.fill(buffer, (byte) 0);
      }

      StructStat finalStat = fstat(input.getFD());
      if (copied != initialSize || !sameFile(openedStat, finalStat) ||
              openedStat.st_size != finalStat.st_size || openedStat.st_mtime != finalStat.st_mtime) {
        throw new IOException("Attachment source changed during snapshot");
      }
    }
  }

  private static boolean isDescendant(File root, File candidate) {
    String prefix = root.getPath() + File.separator;
    return candidate.getPath().startsWith(prefix);
  }

  private static StructStat lstat(File file) throws IOException {
    try {
      return Os.lstat(file.getAbsolutePath());
    } catch (ErrnoException exception) {
      throw new IOException("Cannot inspect attachment source", exception);
    }
  }

  private static StructStat fstat(FileDescriptor descriptor) throws IOException {
    try {
      return Os.fstat(descriptor);
    } catch (ErrnoException exception) {
      throw new IOException("Cannot inspect encrypted attachment file", exception);
    }
  }

  private static boolean sameFile(StructStat first, StructStat second) {
    return first.st_dev == second.st_dev && first.st_ino == second.st_ino;
  }

  private static void removeAbandonedCiphertextSnapshots(File cache) throws IOException {
    File[] candidates = cache.listFiles((directory, name) -> name.startsWith(TEMP_PREFIX));
    if (candidates == null) throw new IOException("Cannot inspect private cache");
    for (File candidate : candidates) {
      if (!cache.equals(candidate.getCanonicalFile().getParentFile())) {
        throw new IOException("Unsafe temporary ciphertext path");
      }
      StructStat candidateStat = lstat(candidate);
      if (!OsConstants.S_ISREG(candidateStat.st_mode)) {
        throw new IOException("Abandoned ciphertext entry is not a regular file");
      }
      try {
        Os.remove(candidate.getAbsolutePath());
      } catch (ErrnoException exception) {
        if (exception.errno != OsConstants.ENOENT) {
          throw new IOException("Cannot remove abandoned ciphertext snapshot", exception);
        }
      }
    }
  }

  private static IOException closeResources(InputStream first, InputStream second,
                                            InputStream third) {
    IOException failure = close(first, null);
    failure = close(second, failure);
    return close(third, failure);
  }

  private static IOException close(InputStream input, IOException failure) {
    if (input == null) return failure;
    try {
      input.close();
    } catch (IOException exception) {
      if (failure == null) return exception;
      failure.addSuppressed(exception);
    }
    return failure;
  }

  /** Owns the pinned ciphertext descriptor and its verified second-pass decryptor. */
  public static final class VerifiedPlaintext implements AutoCloseable {
    private final FileInputStream snapshotInput;
    private final DecryptingPartInputStream validationStream;
    private final DecryptingPartInputStream consumerStream;
    private boolean opened;
    private boolean closed;

    private VerifiedPlaintext(FileInputStream snapshotInput,
                              DecryptingPartInputStream validationStream,
                              DecryptingPartInputStream consumerStream) {
      this.snapshotInput = snapshotInput;
      this.validationStream = validationStream;
      this.consumerStream = consumerStream;
    }

    /** Opens the authenticated second pass once. Closing this handle closes the shared source. */
    @NonNull
    public synchronized InputStream openStream() throws IOException {
      if (closed) throw new IOException("Verified attachment handle is closed");
      if (opened) throw new IOException("Verified attachment stream already opened");
      opened = true;
      return consumerStream;
    }

    @Override
    public synchronized void close() throws IOException {
      if (closed) return;
      closed = true;
      IOException failure = closeResources(consumerStream, validationStream, snapshotInput);
      if (failure != null) throw failure;
    }
  }
}
