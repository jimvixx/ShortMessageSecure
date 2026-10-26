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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.ContextWrapper;
import android.system.Os;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@RunWith(AndroidJUnit4.class)
public final class MmsAttachmentCryptoCompatibilityTest {
  private static final String PREFS = "SecureSMS-Preferences";
  private final Context testContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
  private File tempDirectory;

  @After public void tearDown() throws Exception {
    if (tempDirectory != null) deleteTree(tempDirectory);
    testContext.deleteSharedPreferences("mms-crypto-source-" + PREFS);
    testContext.deleteSharedPreferences("mms-crypto-restored-" + PREFS);
  }

  @Test public void currentDecryptorReadsLegacyFormatForDataAndThumbnail() throws Exception {
    MasterSecret key = syntheticKey(3);
    byte[][] payloads = {new byte[]{0, 1, -1, 17}, new byte[]{9, 8, 7}};
    for (int i = 0; i < payloads.length; i++) {
      File file = tempFile("legacy-part-" + i);
      writeLegacyFormat(file, payloads[i], key);
      assertArrayEquals(payloads[i], readCurrent(file, key));
    }
  }

  @Test public void currentEncryptorUsesLegacyReadableFormat() throws Exception {
    MasterSecret key = syntheticKey(11);
    byte[] payload = new byte[]{4, 0, 5, 6, -2, 7};
    File file = tempFile("current-part");
    try (EncryptingPartOutputStream output = new EncryptingPartOutputStream(file, key)) {
      output.write(payload);
    }
    assertArrayEquals(payload, readLegacyFormat(file, key));
  }

  @Test public void restoredEquivalentMasterSecretReadsAndPassphraseChangePreservesIt() throws Exception {
    Context source = isolatedPreferences("mms-crypto-source-");
    Context restored = isolatedPreferences("mms-crypto-restored-");
    source.deleteSharedPreferences("mms-crypto-source-" + PREFS);
    restored.deleteSharedPreferences("mms-crypto-restored-" + PREFS);

    MasterSecret original = MasterSecretUtil.generateMasterSecret(source, "synthetic-old-passphrase");
    assertNotNull(original);
    copyPreferences(source, restored);
    MasterSecret restoredKey = MasterSecretUtil.getMasterSecret(restored, "synthetic-old-passphrase");
    assertSameKeys(original, restoredKey);

    File file = tempFile("restored-key-part");
    byte[] payload = new byte[]{21, 22, 23};
    writeLegacyFormat(file, payload, original);
    assertArrayEquals(payload, readCurrent(file, restoredKey));

    MasterSecretUtil.changeMasterSecretPassphrase(restored, restoredKey, "synthetic-new-passphrase");
    MasterSecret afterChange = MasterSecretUtil.getMasterSecret(restored, "synthetic-new-passphrase");
    assertSameKeys(original, afterChange);
    assertArrayEquals(payload, readCurrent(file, afterChange));
    try {
      MasterSecretUtil.getMasterSecret(restored, "synthetic-old-passphrase");
      fail("Old passphrase unexpectedly unlocked the rewrapped master secret");
    } catch (InvalidPassphraseException expected) { }
  }

  @Test public void differentMasterSecretAndCorruptedCiphertextFailIntegrityCheck() throws Exception {
    MasterSecret correctKey = syntheticKey(31);
    File wrongKeyFile = tempFile("wrong-key-part");
    writeLegacyFormat(wrongKeyFile, new byte[]{1, 3, 3, 7}, correctKey);
    assertReadFails(wrongKeyFile, syntheticKey(61));

    File corruptedFile = tempFile("corrupt-part");
    writeLegacyFormat(corruptedFile, new byte[]{2, 4, 6, 8}, correctKey);
    byte[] bytes = readFile(corruptedFile);
    bytes[16] ^= 0x40;
    try (FileOutputStream output = new FileOutputStream(corruptedFile)) {
      output.write(bytes);
    }
    assertReadFails(corruptedFile, correctKey);
  }

  @Test public void readsHonorOffsetsLengthsSingleByteAndInputStreamValidation() throws Exception {
    MasterSecret key = syntheticKey(77);
    byte[] payload = new byte[]{0, 1, 2, 3, 4, 5, 6, 7, (byte) 0xff};
    File file = tempFile("read-contract");
    writeLegacyFormat(file, payload, key);

    byte[] target = new byte[16];
    Arrays.fill(target, (byte) 0x55);
    try (InputStream input = new DecryptingPartInputStream(file, key)) {
      assertEquals(3, input.read(target, 4, 3));
      assertEquals(0x55, target[3] & 0xff);
      assertArrayEquals(new byte[]{0, 1, 2}, Arrays.copyOfRange(target, 4, 7));
      assertEquals(0x55, target[7] & 0xff);
      assertEquals(0, input.read(target, 5, 0));
      assertEquals(3, input.read());
      assertEquals(4, input.read());
      assertEquals(5, input.read());
      assertEquals(6, input.read());
      assertEquals(7, input.read());
      assertEquals(255, input.read());
      assertEquals(-1, input.read());
      assertEquals(-1, input.read(target, 0, target.length));
      assertEquals(0, input.read(target, 0, 0));
      try {
        input.read(target, -1, 1);
        fail("Negative offset was accepted");
      } catch (IndexOutOfBoundsException expected) { }
      try {
        input.read(target, 0, target.length + 1);
        fail("Excessive length was accepted");
      } catch (IndexOutOfBoundsException expected) { }
    }

    try (InputStream input = new DecryptingPartInputStream(file, key)) {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      byte[] small = new byte[2];
      int count;
      while ((count = input.read(small, 1, 1)) != -1) output.write(small, 1, count);
      assertArrayEquals(payload, output.toByteArray());
    }
  }

  @Test public void truncatedInputModifiedIvAndAuthenticatedInvalidPaddingFail() throws Exception {
    MasterSecret key = syntheticKey(93);
    File truncated = tempFile("truncated-part");
    writeLegacyFormat(truncated, new byte[]{8, 6, 7, 5, 3, 0, 9}, key);
    byte[] valid = readFile(truncated);
    try (FileOutputStream output = new FileOutputStream(truncated)) {
      output.write(valid, 0, valid.length - 1);
    }
    assertReadFails(truncated, key);

    File modifiedIv = tempFile("modified-iv-part");
    writeLegacyFormat(modifiedIv, new byte[]{1, 2, 3, 4}, key);
    byte[] ivBytes = readFile(modifiedIv);
    ivBytes[0] ^= 0x20;
    writeFile(modifiedIv, ivBytes);
    assertReadFails(modifiedIv, key);

    File badPadding = tempFile("invalid-padding-part");
    byte[] blockPlaintext = new byte[16];
    writeLegacyFormat(badPadding, blockPlaintext, key);
    byte[] malformed = readFile(badPadding);
    malformed[31] ^= 0x01;
    rewriteMac(malformed, key);
    writeFile(badPadding, malformed);
    assertReadFails(badPadding, key);
  }

  @Test public void authenticatedReaderPublishesOnlyVerifiedContentAndCleansTemporaryFiles()
          throws Exception {
    MasterSecret key = syntheticKey(107);
    File file = tempFile("authenticated-part");
    byte[] payload = new byte[]{19, 18, 17, 16, 15};
    writeLegacyFormat(file, payload, key);
    int before = ciphertextTempCount();

    AuthenticatedPartReader.VerifiedPlaintext verified = AuthenticatedPartReader.authenticate(
            testContext, file, key, payload.length);
    assertEquals(before, ciphertextTempCount());
    try (InputStream input = verified.openStream()) {
      assertArrayEquals(payload, readAll(input));
    } finally {
      verified.close();
    }
    assertEquals(before, ciphertextTempCount());

    byte[] corrupted = readFile(file);
    corrupted[corrupted.length - 1] ^= 0x01;
    writeFile(file, corrupted);
    try {
      AuthenticatedPartReader.authenticate(testContext, file, key, payload.length);
      fail("Corrupt attachment returned an authenticated handle");
    } catch (IOException expected) { }
    assertEquals(before, ciphertextTempCount());

    writeLegacyFormat(file, payload, key);
    try {
      AuthenticatedPartReader.authenticate(testContext, file, syntheticKey(108), payload.length);
      fail("Wrong master secret returned an authenticated handle");
    } catch (IOException expected) { }
    assertEquals(before, ciphertextTempCount());
    try {
      AuthenticatedPartReader.authenticate(testContext, file, key, payload.length - 1);
      fail("Plaintext limit was ignored");
    } catch (IOException expected) { }
    assertEquals(before, ciphertextTempCount());
  }

  @Test public void sourceReplacementAfterAuthenticationCannotChangePinnedContent() throws Exception {
    MasterSecret key = syntheticKey(141);
    File source = tempFile("pinned-source");
    byte[] authenticatedPayload = new byte[]{1, 4, 9, 16, 25};
    writeLegacyFormat(source, authenticatedPayload, key);

    AuthenticatedPartReader.VerifiedPlaintext verified = AuthenticatedPartReader.authenticate(
            testContext, source, key, authenticatedPayload.length);
    writeLegacyFormat(source, new byte[]{99, 98, 97}, key);
    try (InputStream input = verified.openStream()) {
      assertArrayEquals(authenticatedPayload, readAll(input));
    } finally {
      verified.close();
    }
    assertEquals(0, ciphertextTempCount());
  }

  @Test public void sourceSymlinkIsRejectedBeforeSnapshot() throws Exception {
    MasterSecret key = syntheticKey(151);
    File target = tempFile("symlink-target");
    File link = tempFile("symlink-source");
    writeLegacyFormat(target, new byte[]{7, 7, 7}, key);
    assertTrue(link.delete());
    Os.symlink(target.getAbsolutePath(), link.getAbsolutePath());
    try {
      AuthenticatedPartReader.authenticate(testContext, link, key, 3);
      fail("Symlink source was accepted");
    } catch (IOException expected) { }
    assertEquals(0, ciphertextTempCount());
  }

  @Test public void concurrentSourceModificationEitherFailsOrReturnsOneWholeAuthenticatedValue()
          throws Exception {
    MasterSecret key = syntheticKey(163);
    File source = tempFile("concurrent-source");
    byte[] original = new byte[2 * 1024 * 1024];
    byte[] replacement = new byte[original.length];
    Arrays.fill(original, (byte) 0x31);
    Arrays.fill(replacement, (byte) 0x6c);
    writeLegacyFormat(source, original, key);
    File replacementFile = tempFile("concurrent-replacement");
    writeLegacyFormat(replacementFile, replacement, key);
    byte[] replacementCiphertext = readFile(replacementFile);

    CountDownLatch start = new CountDownLatch(1);
    final Throwable[] writerFailure = new Throwable[1];
    Thread writer = new Thread(() -> {
      try {
        if (!start.await(5, TimeUnit.SECONDS)) throw new IOException("Writer start timed out");
        try (java.io.RandomAccessFile output = new java.io.RandomAccessFile(source, "rw")) {
          output.setLength(0);
          for (int offset = 0; offset < replacementCiphertext.length; offset += 4096) {
            int count = Math.min(4096, replacementCiphertext.length - offset);
            output.write(replacementCiphertext, offset, count);
            Thread.yield();
          }
        }
      } catch (Throwable exception) {
        writerFailure[0] = exception;
      }
    });
    writer.start();
    start.countDown();

    AuthenticatedPartReader.VerifiedPlaintext verified = null;
    try {
      verified = AuthenticatedPartReader.authenticate(testContext, source, key, original.length);
    } catch (IOException expectedDuringMutation) { }
    writer.join(10000);
    assertFalse(writer.isAlive());
    if (writerFailure[0] != null) throw new AssertionError("Concurrent writer failed", writerFailure[0]);

    if (verified != null) {
      try (InputStream input = verified.openStream()) {
        byte[] result = readAll(input);
        assertTrue(Arrays.equals(original, result) || Arrays.equals(replacement, result));
      } finally {
        verified.close();
      }
    }
    assertEquals(0, ciphertextTempCount());
  }

  @Test public void sourceGrowthDuringSnapshotCannotExceedCiphertextBudget() throws Exception {
    File source = tempFile("growing-source");
    try (FileOutputStream output = new FileOutputStream(source)) {
      output.write(new byte[64]);
    }

    long maximumCiphertextBytes = 32 + 52;
    long[] copiedBytes = {0};
    try {
      AuthenticatedPartReader.snapshot(testContext, source, 32, ciphertextBytes -> {
        copiedBytes[0] = ciphertextBytes;
        try (java.io.RandomAccessFile output = new java.io.RandomAccessFile(source, "rw")) {
          output.seek(output.length());
          output.write(new byte[64]);
        }
      });
      fail("Growing source should exceed the pinned ciphertext budget");
    } catch (IOException expected) {
      assertEquals(64L, copiedBytes[0]);
      assertTrue(copiedBytes[0] <= maximumCiphertextBytes);
      assertEquals(128L, source.length());
    }

    assertEquals(0, ciphertextTempCount());
  }

  @Test public void earlyCloseAndConsumerExceptionReleasePinnedCiphertext() throws Exception {
    MasterSecret key = syntheticKey(173);
    File file = tempFile("early-close-part");
    writeLegacyFormat(file, new byte[]{3, 1, 4, 1, 5, 9}, key);

    AuthenticatedPartReader.VerifiedPlaintext early = AuthenticatedPartReader.authenticate(
            testContext, file, key, 6);
    early.close();
    try {
      early.openStream();
      fail("Closed handle reopened its stream");
    } catch (IOException expected) { }

    AuthenticatedPartReader.VerifiedPlaintext exceptional = AuthenticatedPartReader.authenticate(
            testContext, file, key, 6);
    try {
      try (InputStream input = exceptional.openStream()) {
        assertEquals(3, input.read());
        throw new IOException("Synthetic consumer failure");
      }
    } catch (IOException expected) {
      assertEquals("Synthetic consumer failure", expected.getMessage());
    } finally {
      exceptional.close();
    }
    assertEquals(0, ciphertextTempCount());
  }

  @Test public void abandonedEncryptedSnapshotIsRemovedOnNextUse() throws Exception {
    MasterSecret key = syntheticKey(181);
    File source = tempFile("restart-source");
    writeLegacyFormat(source, new byte[]{6, 2, 6, 4}, key);
    File abandoned = File.createTempFile("mms-ciphertext-", ".tmp", testContext.getCacheDir());
    try {
      writeFile(abandoned, new byte[]{1, 2, 3, 4});
      try (AuthenticatedPartReader.VerifiedPlaintext verified = AuthenticatedPartReader.authenticate(
              testContext, source, key, 4);
           InputStream input = verified.openStream()) {
        assertArrayEquals(new byte[]{6, 2, 6, 4}, readAll(input));
      }
      assertFalse(abandoned.exists());
      assertEquals(0, ciphertextTempCount());
    } finally {
      if (abandoned.exists()) assertTrue(abandoned.delete());
    }
  }

  @Test public void authenticatedReaderAcceptsEmptyLegacyPart() throws Exception {
    MasterSecret key = syntheticKey(119);
    File file = tempFile("empty-authenticated-part");
    writeLegacyFormat(file, new byte[0], key);
    try (AuthenticatedPartReader.VerifiedPlaintext verified = AuthenticatedPartReader.authenticate(
            testContext, file, key, 0);
         InputStream input = verified.openStream()) {
      assertEquals(-1, input.read());
    }
  }

  @Test public void authenticatedReaderProcessesLargePartsWithBoundedBuffers() throws Exception {
    MasterSecret key = syntheticKey(121);
    File file = tempFile("large-authenticated-part");
    MessageDigest expected = MessageDigest.getInstance("SHA-256");
    byte[] source = new byte[64 * 1024];
    for (int i = 0; i < source.length; i++) source[i] = (byte) (i * 31);
    long expectedLength = 8L * 1024L * 1024L;
    try (EncryptingPartOutputStream output = new EncryptingPartOutputStream(file, key)) {
      for (long written = 0; written < expectedLength; written += source.length) {
        output.write(source);
        expected.update(source);
      }
    }

    MessageDigest actual = MessageDigest.getInstance("SHA-256");
    long readLength = 0;
    try (AuthenticatedPartReader.VerifiedPlaintext verified = AuthenticatedPartReader.authenticate(
            testContext, file, key, expectedLength);
         InputStream input = verified.openStream()) {
      byte[] buffer = new byte[4096];
      int count;
      while ((count = input.read(buffer)) != -1) {
        actual.update(buffer, 0, count);
        readLength += count;
      }
    }
    assertEquals(expectedLength, readLength);
    assertArrayEquals(expected.digest(), actual.digest());
  }

  @Test public void rawStreamDoesNotTreatPlaintextAsTrustedBeforeMacFailure() throws Exception {
    MasterSecret key = syntheticKey(133);
    File file = tempFile("unauthenticated-prefix");
    byte[] payload = new byte[64];
    for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i + 31);
    writeLegacyFormat(file, payload, key);
    byte[] corrupted = readFile(file);
    corrupted[corrupted.length - 1] ^= 0x40;
    writeFile(file, corrupted);

    try (InputStream input = new DecryptingPartInputStream(file, key)) {
      byte[] prefix = new byte[2];
      assertEquals(2, input.read(prefix));
      assertArrayEquals(Arrays.copyOf(payload, 2), prefix);
      try {
        while (input.read(new byte[16]) != -1) { }
        fail("Corrupted MAC was accepted");
      } catch (IOException expected) { }
    }
  }

  private Context isolatedPreferences(String prefix) {
    return new ContextWrapper(testContext) {
      @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
        return super.getSharedPreferences(prefix + name, mode);
      }
    };
  }

  private static void copyPreferences(Context source, Context destination) {
    android.content.SharedPreferences.Editor editor = destination
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear();
    for (java.util.Map.Entry<String, ?> entry : source
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getAll().entrySet()) {
      Object value = entry.getValue();
      if (value instanceof String) editor.putString(entry.getKey(), (String) value);
      else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
      else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
      else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
      else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
      else if (value instanceof java.util.Set) {
        @SuppressWarnings("unchecked") java.util.Set<String> strings = (java.util.Set<String>) value;
        editor.putStringSet(entry.getKey(), strings);
      } else {
        throw new AssertionError("Unexpected synthetic preference value type");
      }
    }
    if (!editor.commit()) throw new AssertionError("Cannot copy synthetic preference state");
  }

  private File tempFile(String prefix) throws IOException {
    if (tempDirectory == null) {
      tempDirectory = new File(testContext.getCacheDir(), "synthetic-mms-crypto-" +
              System.nanoTime());
      if (!tempDirectory.mkdirs()) throw new IOException("Cannot create synthetic test directory");
    }
    return File.createTempFile(prefix, ".mms", tempDirectory);
  }

  private static MasterSecret syntheticKey(int seed) {
    byte[] encryption = new byte[16];
    byte[] mac = new byte[20];
    for (int i = 0; i < encryption.length; i++) encryption[i] = (byte) (seed + i);
    for (int i = 0; i < mac.length; i++) mac[i] = (byte) (seed * 3 + i);
    return new MasterSecret(new SecretKeySpec(encryption, "AES"),
            new SecretKeySpec(mac, "HmacSHA1"));
  }

  private static void writeLegacyFormat(File file, byte[] plaintext, MasterSecret key)
          throws Exception {
    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(Cipher.ENCRYPT_MODE, key.getEncryptionKey());
    byte[] iv = cipher.getIV();
    byte[] ciphertext = cipher.doFinal(plaintext);
    Mac mac = Mac.getInstance("HmacSHA1");
    mac.init(key.getMacKey());
    mac.update(iv);
    mac.update(ciphertext);
    try (FileOutputStream output = new FileOutputStream(file)) {
      output.write(iv);
      output.write(ciphertext);
      output.write(mac.doFinal());
    }
  }

  private static byte[] readLegacyFormat(File file, MasterSecret key) throws Exception {
    byte[] bytes = readFile(file);
    byte[] iv = Arrays.copyOfRange(bytes, 0, 16);
    byte[] ciphertext = Arrays.copyOfRange(bytes, 16, bytes.length - 20);
    byte[] expectedMac = Arrays.copyOfRange(bytes, bytes.length - 20, bytes.length);
    Mac mac = Mac.getInstance("HmacSHA1");
    mac.init(key.getMacKey());
    mac.update(iv);
    mac.update(ciphertext);
    if (!MessageDigest.isEqual(expectedMac, mac.doFinal())) {
      throw new IOException("Legacy-format attachment authentication failed");
    }
    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(Cipher.DECRYPT_MODE, key.getEncryptionKey(), new IvParameterSpec(iv));
    return cipher.doFinal(ciphertext);
  }

  private static byte[] readCurrent(File file, MasterSecret key) throws IOException {
    try (InputStream input = new DecryptingPartInputStream(file, key);
         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[128];
      int count;
      while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
      return output.toByteArray();
    }
  }

  private static void assertReadFails(File file, MasterSecret key) throws Exception {
    try {
      readCurrent(file, key);
      fail("Invalid attachment bytes unexpectedly passed authentication");
    } catch (IOException expected) { }
  }

  private static byte[] readFile(File file) throws IOException {
    try (InputStream input = new FileInputStream(file);
         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int count;
      while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
      return output.toByteArray();
    }
  }

  private static byte[] readAll(InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[256];
    int count;
    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    return output.toByteArray();
  }

  private static void writeFile(File file, byte[] bytes) throws IOException {
    try (FileOutputStream output = new FileOutputStream(file)) {
      output.write(bytes);
    }
  }

  private static void rewriteMac(byte[] encoded, MasterSecret key) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA1");
    mac.init(key.getMacKey());
    mac.update(encoded, 0, encoded.length - 20);
    byte[] value = mac.doFinal();
    System.arraycopy(value, 0, encoded, encoded.length - value.length, value.length);
  }

  private int ciphertextTempCount() {
    File[] files = testContext.getCacheDir().listFiles((directory, name) ->
            name.startsWith("mms-ciphertext-"));
    return files == null ? 0 : files.length;
  }

  private static void assertSameKeys(MasterSecret expected, MasterSecret actual) {
    assertArrayEquals(expected.getEncryptionKey().getEncoded(), actual.getEncryptionKey().getEncoded());
    assertArrayEquals(expected.getMacKey().getEncoded(), actual.getMacKey().getEncoded());
  }

  private static void deleteTree(File file) {
    File[] children = file.listFiles();
    if (children != null) for (File child : children) deleteTree(child);
    if (!file.delete() && file.exists()) throw new AssertionError("Cannot remove synthetic test file");
  }
}
