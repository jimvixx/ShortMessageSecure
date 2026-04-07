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
import org.jimvixx.smsecure.logging.Log;

import org.jimvixx.smsecure.crypto.MasterCipher;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.recipients.RecipientFactory;
import org.jimvixx.smsecure.util.Conversions;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SessionState;
import org.whispersystems.libsignal.state.SessionStore;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.LinkedList;
import java.util.List;

import static org.whispersystems.libsignal.state.StorageProtos.SessionStructure;

public class SMSecureSessionStore implements SessionStore {

  private static final String TAG                   = SMSecureSessionStore.class.getSimpleName();
  private static final String SESSIONS_DIRECTORY_V2 = "sessions-v2";
  private static final Object FILE_LOCK             = new Object();

  private static final int SINGLE_STATE_VERSION   = 1;
  private static final int ARCHIVE_STATES_VERSION = 2;
  private static final int CURRENT_VERSION        = 2;

  private final Context      context;
  private final MasterSecret masterSecret;
  private final int          subscriptionId;

  public SMSecureSessionStore(Context context, MasterSecret masterSecret, int subscriptionId) {
    Log.w(TAG, "SMSecureSessionStore for subscription ID " + subscriptionId);
    if (subscriptionId == -1) Log.w(TAG, "Subscription ID should not be -1!");

    this.context        = context.getApplicationContext();
    this.masterSecret   = masterSecret;
    this.subscriptionId = subscriptionId;
  }

  @Override
  public SessionRecord loadSession(SignalProtocolAddress address) {
    synchronized (FILE_LOCK) {
      File sessionFile = getSessionFile(address);

      try (FileInputStream in = new FileInputStream(sessionFile)) {
        MasterCipher cipher = new MasterCipher(masterSecret);

        int versionMarker = readInteger(in);

        if (versionMarker > CURRENT_VERSION) {
          throw new AssertionError("Unknown version: " + versionMarker);
        }

        byte[] serialized = cipher.decryptBytes(readBlob(in));

        if (versionMarker == SINGLE_STATE_VERSION) {
          SessionStructure sessionStructure = SessionStructure.parseFrom(serialized);
          SessionState     sessionState     = new SessionState(sessionStructure);
          return new SessionRecord(sessionState);
        } else if (versionMarker == ARCHIVE_STATES_VERSION) {
          return new SessionRecord(serialized);
        } else {
          throw new AssertionError("Unknown version: " + versionMarker);
        }

      } catch (InvalidMessageException | IOException e) {
        Log.w(TAG, "No existing session information found.");
        return new SessionRecord();
      }
    }
  }

  @Override
  public void storeSession(SignalProtocolAddress address, SessionRecord record) {
    synchronized (FILE_LOCK) {
      try {
        storeSessionAtomically(address, record);
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    }
  }

  @Override
  public boolean containsSession(SignalProtocolAddress address) {
    return getSessionFile(address).exists() &&
            loadSession(address).getSessionState().hasSenderChain();
  }

  @Override
  public void deleteSession(SignalProtocolAddress address) {
    File sessionFile = getSessionFile(address);
    if (sessionFile.exists() && !sessionFile.delete()) {
      Log.w(TAG, "Failed to delete session file: " + sessionFile.getAbsolutePath());
    }
  }

  @Override
  public void deleteAllSessions(String name) {
    List<Integer> devices = getSubDeviceSessions(name);

    deleteSession(new SignalProtocolAddress(name, 1));

    for (int device : devices) {
      deleteSession(new SignalProtocolAddress(name, device));
    }
  }

  @Override
  public List<Integer> getSubDeviceSessions(String name) {
    long recipientId = RecipientFactory.getRecipientsFromString(context, name, true)
            .getPrimaryRecipient()
            .getRecipientId();

    List<Integer> results  = new LinkedList<>();
    File          parent   = getSessionDirectory();
    String[]      children = parent.list();

    if (children == null) return results;

    for (String child : children) {
      try {
        String[] parts              = child.split("[.]", 2);
        long     sessionRecipientId = Long.parseLong(parts[0]);

        if (sessionRecipientId == recipientId && parts.length > 1) {
          results.add(Integer.parseInt(parts[1]));
        }
      } catch (NumberFormatException e) {
        Log.w(TAG, "Skipping unexpected session file: " + child, e);
      }
    }

    return results;
  }

  private void storeSessionAtomically(SignalProtocolAddress address, SessionRecord record) throws IOException {
    File destFile = getSessionFile(address);

    File parent = destFile.getParentFile();
    if (parent == null) {
      throw new IOException("Destination file has no parent directory: " + destFile.getAbsolutePath());
    }
    if (!parent.exists() && !parent.mkdirs()) {
      Log.w(TAG, "Session directory creation failed: " + parent.getAbsolutePath());
    }

    // Temp file must be in the same directory to make rename atomic on Android/Linux.
    File tmpFile = new File(parent, destFile.getName() + ".tmp");

    // Best-effort cleanup if a stale temp file exists.
    if (tmpFile.exists() && !tmpFile.delete()) {
      Log.w(TAG, "Failed to delete stale temp file: " + tmpFile.getAbsolutePath());
    }

    MasterCipher masterCipher = new MasterCipher(masterSecret);
    byte[] encrypted = masterCipher.encryptBytes(record.serialize());

    // Write the full contents to the temp file.
    try (RandomAccessFile raf = new RandomAccessFile(tmpFile, "rw");
         FileChannel out = raf.getChannel())
    {
      out.position(0);
      writeInteger(CURRENT_VERSION, out);
      writeBlob(encrypted, out);

      // Truncate any previous trailing data and flush to disk.
      out.truncate(out.position());
      out.force(true);
    }

    // Replace the destination atomically.
    atomicReplace(tmpFile, destFile);
  }

  /**
   * Atomically replaces {@code dest} with {@code tmp} using rename within the same directory.
   * Falls back to delete+rename if needed.
   */
  private static void atomicReplace(File tmp, File dest) throws IOException {
    // If destination exists, rename may fail on some devices/filesystems; delete first.
    if (dest.exists() && !dest.delete()) {
      throw new IOException("Failed to delete destination file before replace: " + dest.getAbsolutePath());
    }

    if (!tmp.renameTo(dest)) {
      // Retry once after ensuring destination is gone.
      if (dest.exists() && !dest.delete()) {
        throw new IOException("Failed to delete destination file during retry: " + dest.getAbsolutePath());
      }
      if (!tmp.renameTo(dest)) {
        throw new IOException("Failed to atomically replace file. tmp=" + tmp.getAbsolutePath()
                + " dest=" + dest.getAbsolutePath());
      }
    }
  }

  private File getSessionFile(SignalProtocolAddress address) {
    String sessionName = getSessionName(address);
    return new File(getSessionDirectory(), sessionName);
  }

  private File getSessionDirectory() {
    return getSessionDirectory(context);
  }

  public static File getSessionDirectory(Context context) {
    File directory = new File(context.getFilesDir(), SESSIONS_DIRECTORY_V2);

    if (!directory.exists()) {
      if (!directory.mkdirs()) {
        Log.w(TAG, "Session directory creation failed!");
      }
    }

    return directory;
  }

  private String getSessionName(SignalProtocolAddress axolotlAddress) {
    Recipient recipient   = RecipientFactory.getRecipientsFromString(context, axolotlAddress.getName(), true)
            .getPrimaryRecipient();
    long      recipientId = recipient.getRecipientId();

    return recipientId + (subscriptionId == -1 ? "" : "." + subscriptionId);
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
}
