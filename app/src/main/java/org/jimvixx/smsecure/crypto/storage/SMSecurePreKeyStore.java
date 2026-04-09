/*
 * Copyright (C) 2015 Whisper Systems
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

package org.jimvixx.smsecure.crypto.storage;

import android.content.Context;

import org.jimvixx.smsecure.crypto.MasterCipher;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.util.Conversions;
import org.whispersystems.libsignal.InvalidKeyIdException;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.PreKeyStore;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;
import org.whispersystems.libsignal.state.SignedPreKeyStore;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.LinkedList;
import java.util.List;

public class SMSecurePreKeyStore implements PreKeyStore, SignedPreKeyStore {

  public static final String PREKEY_DIRECTORY = "prekeys";
  public static final String SIGNED_PREKEY_DIRECTORY = "signed_prekeys";

  private static final int CURRENT_VERSION_MARKER = 1;
  private static final Object FILE_LOCK = new Object();
  private static final String TAG = SMSecurePreKeyStore.class.getSimpleName();

  private final Context context;
  private final MasterSecret masterSecret;
  private final int subscriptionId;

  public SMSecurePreKeyStore(Context context, MasterSecret masterSecret, int subscriptionId) {
    this.context = context.getApplicationContext();
    this.masterSecret = masterSecret;
    this.subscriptionId = subscriptionId;
  }

  /**
   * Atomically replaces {@code dest} with {@code tmp} using rename within the same directory.
   * Falls back to delete+rename if needed.
   */
  private static void atomicReplace(File tmp, File dest) throws IOException {
    // If destination exists, rename may fail on some devices/filesystems; delete first (best-effort).
    if (dest.exists() && !dest.delete()) {
      throw new IOException("Failed to delete destination file before replace: " + dest.getAbsolutePath());
    }

    if (!tmp.renameTo(dest)) {
      // If rename fails, try one more time after ensuring destination is gone.
      if (dest.exists() && !dest.delete()) {
        throw new IOException("Failed to delete destination file during retry: " + dest.getAbsolutePath());
      }
      if (!tmp.renameTo(dest)) {
        throw new IOException("Failed to atomically replace file. tmp=" + tmp.getAbsolutePath()
                + " dest=" + dest.getAbsolutePath());
      }
    }
  }

  private static void readFully(FileInputStream in, byte[] target) throws IOException {
    int offset = 0;

    while (offset < target.length) {
      int read = in.read(target, offset, target.length - offset);
      if (read < 0) {
        throw new EOFException("Unexpected EOF while reading " + target.length + " bytes");
      }
      offset += read;
    }
  }

  private static void writeFully(FileChannel out, ByteBuffer buffer) throws IOException {
    while (buffer.hasRemaining()) {
      int written = out.write(buffer);
      if (written <= 0) {
        throw new IOException("Failed to write to FileChannel (wrote " + written + " bytes)");
      }
    }
  }

  @Override
  public PreKeyRecord loadPreKey(int preKeyId) throws InvalidKeyIdException {
    synchronized (FILE_LOCK) {
      try {
        return new PreKeyRecord(loadSerializedRecord(getPreKeyFile(preKeyId)));
      } catch (IOException | InvalidMessageException e) {
        Log.w(TAG, "Failed to load PreKey: " + preKeyId, e);
        throw new InvalidKeyIdException(e);
      }
    }
  }

  @Override
  public SignedPreKeyRecord loadSignedPreKey(int signedPreKeyId) throws InvalidKeyIdException {
    synchronized (FILE_LOCK) {
      try {
        return new SignedPreKeyRecord(loadSerializedRecord(getSignedPreKeyFile(signedPreKeyId)));
      } catch (IOException | InvalidMessageException e) {
        Log.w(TAG, "Failed to load SignedPreKey: " + signedPreKeyId, e);
        throw new InvalidKeyIdException(e);
      }
    }
  }

  @Override
  public List<SignedPreKeyRecord> loadSignedPreKeys() {
    synchronized (FILE_LOCK) {
      File directory = getSignedPreKeyDirectory();
      List<SignedPreKeyRecord> results = new LinkedList<>();

      File[] files = directory.listFiles();
      if (files == null) {
        // listFiles() may return null due to an I/O error or permissions issue.
        Log.w(TAG, "Failed to list signed prekey directory: " + directory.getAbsolutePath());
        return results;
      }

      for (File signedPreKeyFile : files) {
        if (signedPreKeyFile == null || !signedPreKeyFile.isFile()) continue;

        try {
          results.add(new SignedPreKeyRecord(loadSerializedRecord(signedPreKeyFile)));
        } catch (IOException | InvalidMessageException e) {
          Log.w(TAG, "Failed to load SignedPreKey file: " + signedPreKeyFile.getName(), e);
        }
      }

      return results;
    }
  }

  @Override
  public void storePreKey(int preKeyId, PreKeyRecord record) {
    synchronized (FILE_LOCK) {
      try {
        storeSerializedRecordAtomically(getPreKeyFile(preKeyId), record.serialize());
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    }
  }

  @Override
  public void storeSignedPreKey(int signedPreKeyId, SignedPreKeyRecord record) {
    synchronized (FILE_LOCK) {
      try {
        storeSerializedRecordAtomically(getSignedPreKeyFile(signedPreKeyId), record.serialize());
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    }
  }

  @Override
  public boolean containsPreKey(int preKeyId) {
    return getPreKeyFile(preKeyId).exists();
  }

  @Override
  public boolean containsSignedPreKey(int signedPreKeyId) {
    return getSignedPreKeyFile(signedPreKeyId).exists();
  }

  @Override
  public void removePreKey(int preKeyId) {
    File record = getPreKeyFile(preKeyId);
    if (record.exists() && !record.delete()) {
      Log.w(TAG, "Failed to delete PreKey file: " + record.getAbsolutePath());
    }
  }

  @Override
  public void removeSignedPreKey(int signedPreKeyId) {
    File record = getSignedPreKeyFile(signedPreKeyId);
    if (record.exists() && !record.delete()) {
      Log.w(TAG, "Failed to delete SignedPreKey file: " + record.getAbsolutePath());
    }
  }

  private byte[] loadSerializedRecord(File recordFile)
          throws IOException, InvalidMessageException {
    MasterCipher masterCipher = new MasterCipher(masterSecret);

    try (FileInputStream fin = new FileInputStream(recordFile)) {
      int recordVersion = readInteger(fin);

      if (recordVersion != CURRENT_VERSION_MARKER) {
        throw new AssertionError("Invalid version: " + recordVersion);
      }

      return masterCipher.decryptBytes(readBlob(fin));
    }
  }

  /**
   * Atomically stores a record by writing to a temporary file in the same directory and renaming it
   * over the destination. This prevents partially-written/corrupted files if the process crashes
   * or is killed mid-write.
   */
  private void storeSerializedRecordAtomically(File destFile, byte[] serialized) throws IOException {
    MasterCipher masterCipher = new MasterCipher(masterSecret);

    File parent = destFile.getParentFile();
    if (parent == null) {
      throw new IOException("Destination file has no parent directory: " + destFile.getAbsolutePath());
    }
    if (!parent.exists() && !parent.mkdirs()) {
      Log.w(TAG, "Record directory creation failed: " + parent.getAbsolutePath());
    }

    // Temp file must be in the same directory to make rename atomic on Linux/Android.
    File tmpFile = new File(parent, destFile.getName() + ".tmp");

    // Best-effort cleanup if a stale temp file exists.
    if (tmpFile.exists() && !tmpFile.delete()) {
      Log.w(TAG, "Failed to delete stale temp file: " + tmpFile.getAbsolutePath());
    }

    // Write the full contents to the temp file.
    try (RandomAccessFile raf = new RandomAccessFile(tmpFile, "rw");
         FileChannel out = raf.getChannel()) {
      out.position(0);
      writeInteger(CURRENT_VERSION_MARKER, out);
      writeBlob(masterCipher.encryptBytes(serialized), out);

      // Truncate any previous trailing data and flush to disk.
      out.truncate(out.position());
      out.force(true);
    }

    // Replace the destination atomically.
    atomicReplace(tmpFile, destFile);
  }

  private File getPreKeyFile(int preKeyId) {
    String suffix = subscriptionId != -1 ? "." + subscriptionId : "";
    return new File(getPreKeyDirectory(), preKeyId + suffix);
  }

  private File getSignedPreKeyFile(int signedPreKeyId) {
    String suffix = subscriptionId != -1 ? "." + subscriptionId : "";
    return new File(getSignedPreKeyDirectory(), signedPreKeyId + suffix);
  }

  private File getPreKeyDirectory() {
    return getRecordsDirectory(PREKEY_DIRECTORY);
  }

  private File getSignedPreKeyDirectory() {
    return getRecordsDirectory(SIGNED_PREKEY_DIRECTORY);
  }

  private File getRecordsDirectory(String directoryName) {
    File directory = new File(context.getFilesDir(), directoryName);

    if (!directory.exists()) {
      if (!directory.mkdirs()) {
        Log.w(TAG, "PreKey directory creation failed: " + directory.getAbsolutePath());
      }
    }

    return directory;
  }

  private byte[] readBlob(FileInputStream in) throws IOException {
    int length = readInteger(in);

    if (length < 0) {
      throw new IOException("Negative blob length: " + length);
    }

    byte[] blobBytes = new byte[length];
    readFully(in, blobBytes);
    return blobBytes;
  }

  private void writeBlob(byte[] blobBytes, FileChannel out) throws IOException {
    writeInteger(blobBytes.length, out);

    // FileChannel.write() may write fewer bytes than requested; loop until done.
    writeFully(out, ByteBuffer.wrap(blobBytes));
  }

  private int readInteger(FileInputStream in) throws IOException {
    byte[] integer = new byte[4];
    readFully(in, integer);
    return Conversions.byteArrayToInt(integer);
  }

  private void writeInteger(int value, FileChannel out) throws IOException {
    writeFully(out, ByteBuffer.wrap(Conversions.intToByteArray(value)));
  }
}
