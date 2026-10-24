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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.ContextWrapper;

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
    bytes[bytes.length - 1] ^= 0x40;
    try (FileOutputStream output = new FileOutputStream(corruptedFile)) {
      output.write(bytes);
    }
    assertReadFails(corruptedFile, correctKey);
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
