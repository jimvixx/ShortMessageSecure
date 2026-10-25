/*
 * Copyright (C) 2011 Whisper Systems
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

package org.jimvixx.smsecure.crypto;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Decrypts a legacy MMS part. Plaintext from this streaming API is untrusted until
 * the stream reaches EOF without an exception; use {@link AuthenticatedPartReader}
 * when a consumer must not see any plaintext before complete authentication.
 */
public class DecryptingPartInputStream extends FileInputStream {

  private static final int IV_LENGTH = 16;
  private static final int MAC_LENGTH = 20;
  private static final int BLOCK_LENGTH = 16;
  private static final int CIPHER_BUFFER_SIZE = 8 * 1024;

  private Cipher cipher;
  private Mac mac;
  private long ciphertextRemaining;
  private byte[] plaintextBuffer;
  private int plaintextOffset;
  private boolean finalBlockProcessed;

  public DecryptingPartInputStream(File file, MasterSecret masterSecret)
          throws FileNotFoundException {
    super(file);
    initialize(masterSecret);
  }

  DecryptingPartInputStream(FileDescriptor descriptor, MasterSecret masterSecret)
          throws FileNotFoundException {
    super(descriptor);
    initialize(masterSecret);
  }

  private void initialize(MasterSecret masterSecret) throws FileNotFoundException {
    try {
      long fileLength = getChannel().size();
      long ciphertextLength = fileLength - IV_LENGTH - MAC_LENGTH;
      if (ciphertextLength < BLOCK_LENGTH || ciphertextLength % BLOCK_LENGTH != 0) {
        throw new IOException("Invalid encrypted part length");
      }

      Mac initializedMac = initializeMac(masterSecret.getMacKey());
      byte[] iv = readExactly(IV_LENGTH);
      initializedMac.update(iv);
      Cipher initializedCipher = initializeCipher(masterSecret.getEncryptionKey(), iv);

      this.mac = initializedMac;
      this.cipher = initializedCipher;
      this.ciphertextRemaining = ciphertextLength;
    } catch (InvalidKeyException exception) {
      closeAfterConstructionFailure();
      throw fileNotFound("Invalid part key", exception);
    } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException |
             NoSuchPaddingException exception) {
      closeAfterConstructionFailure();
      throw new AssertionError(exception);
    } catch (IOException exception) {
      closeAfterConstructionFailure();
      throw fileNotFound("Unable to read encrypted part header", exception);
    }
  }

  @Override
  public int read() throws IOException {
    byte[] oneByte = new byte[1];
    int count = read(oneByte, 0, 1);
    return count == -1 ? -1 : oneByte[0] & 0xff;
  }

  @Override
  public int read(byte[] buffer) throws IOException {
    if (buffer == null) throw new NullPointerException("buffer");
    return read(buffer, 0, buffer.length);
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    if (buffer == null) throw new NullPointerException("buffer");
    if (offset < 0 || length < 0 || length > buffer.length - offset) {
      throw new IndexOutOfBoundsException();
    }
    if (length == 0) return 0;

    while (plaintextBuffer == null || plaintextOffset >= plaintextBuffer.length) {
      if (!fillPlaintextBuffer()) return -1;
    }

    int count = Math.min(length, plaintextBuffer.length - plaintextOffset);
    System.arraycopy(plaintextBuffer, plaintextOffset, buffer, offset, count);
    plaintextOffset += count;
    if (plaintextOffset == plaintextBuffer.length) clearPlaintextBuffer();
    return count;
  }

  @Override
  public long skip(long byteCount) throws IOException {
    if (byteCount <= 0) return 0;
    long skipped = 0;
    byte[] discard = new byte[(int) Math.min(4096, byteCount)];
    while (skipped < byteCount) {
      int count = read(discard, 0, (int) Math.min(discard.length, byteCount - skipped));
      if (count == -1) break;
      skipped += count;
    }
    return skipped;
  }

  @Override
  public int available() {
    if (plaintextBuffer == null) return 0;
    return plaintextBuffer.length - plaintextOffset;
  }

  @Override
  public boolean markSupported() {
    return false;
  }

  @Override
  public void close() throws IOException {
    clearPlaintextBuffer();
    super.close();
  }

  private boolean fillPlaintextBuffer() throws IOException {
    clearPlaintextBuffer();
    while (ciphertextRemaining > 0) {
      int count = (int) Math.min(CIPHER_BUFFER_SIZE, ciphertextRemaining);
      byte[] encrypted = readExactly(count);
      ciphertextRemaining -= count;
      mac.update(encrypted);
      byte[] produced = cipher.update(encrypted);
      if (produced != null && produced.length > 0) {
        plaintextBuffer = produced;
        return true;
      }
    }

    if (finalBlockProcessed) return false;
    finalBlockProcessed = true;
    try {
      byte[] suppliedMac = readExactly(MAC_LENGTH);
      byte[] expectedMac = mac.doFinal();
      if (!MessageDigest.isEqual(expectedMac, suppliedMac)) {
        throw new IOException("Encrypted part authentication failed");
      }
      if (super.read() != -1) throw new IOException("Unexpected trailing encrypted part data");
      byte[] finalPlaintext = cipher.doFinal();
      if (finalPlaintext.length == 0) return false;
      plaintextBuffer = finalPlaintext;
      return true;
    } catch (IllegalBlockSizeException | BadPaddingException exception) {
      throw new IOException("Encrypted part decryption failed", exception);
    }
  }

  private byte[] readExactly(int length) throws IOException {
    byte[] result = new byte[length];
    int offset = 0;
    while (offset < length) {
      int count = super.read(result, offset, length - offset);
      if (count < 0) throw new EOFException("Truncated encrypted part");
      if (count == 0) continue;
      offset += count;
    }
    return result;
  }

  private void clearPlaintextBuffer() {
    if (plaintextBuffer != null) {
      java.util.Arrays.fill(plaintextBuffer, (byte) 0);
      plaintextBuffer = null;
      plaintextOffset = 0;
    }
  }

  private void closeAfterConstructionFailure() {
    try {
      super.close();
    } catch (IOException ignored) {
    }
  }

  private static FileNotFoundException fileNotFound(String message, Exception cause) {
    FileNotFoundException exception = new FileNotFoundException(message);
    exception.initCause(cause);
    return exception;
  }

  private static Mac initializeMac(SecretKeySpec key)
          throws NoSuchAlgorithmException, InvalidKeyException {
    Mac hmac = Mac.getInstance("HmacSHA1");
    hmac.init(key);
    return hmac;
  }

  private static Cipher initializeCipher(SecretKeySpec key, byte[] iv)
          throws InvalidKeyException, InvalidAlgorithmParameterException,
          NoSuchAlgorithmException, NoSuchPaddingException {
    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
    return cipher;
  }
}
