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

import org.jimvixx.smsecure.util.Base64;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Snapshot-only legacy format adapter. No Android preferences, logging, or key-cache access. */
final class SilenceLegacyCipher implements AutoCloseable {
  private static final String PBE = "PBEWITHSHA1AND128BITAES-CBC-BC";
  private static final int MAX_ITERATIONS = 100000;
  private final byte[] encryptionKey;
  private final byte[] macKey;
  private boolean closed;

  private SilenceLegacyCipher(byte[] combined) {
    encryptionKey = Arrays.copyOfRange(combined, 0, 16);
    macKey = Arrays.copyOfRange(combined, 16, 36);
  }

  static SilenceLegacyCipher unlock(Map<String, String> preferences, char[] password)
      throws IOException, GeneralSecurityException {
    if (password == null || password.length > 1024) throw new IOException("Unsupported password length");
    int iterations = 100;
    String storedIterations = preferences.get("passphrase_iterations");
    if (storedIterations != null) {
      try {
        if (!storedIterations.startsWith("int:")) throw new IOException("Invalid KDF parameters");
        iterations = Integer.parseInt(storedIterations.substring(4));
      } catch (NumberFormatException e) { throw new IOException("Invalid KDF parameters"); }
    }
    // The reference exporter caps generated iteration counts at 100000; older backups use 100.
    if (iterations < 1 || iterations > MAX_ITERATIONS) throw new IOException("Unsupported KDF work factor");
    byte[] encryptionSalt = secret(preferences, "encryption_salt");
    byte[] macSalt = secret(preferences, "mac_salt");
    byte[] sealed = secret(preferences, "master_secret");
    if (encryptionSalt.length != 16 || macSalt.length != 16 || sealed.length != 68)
      throw new IOException("Invalid master secret record");
    byte[] derivedMac = null;
    byte[] combined = null;
    try {
      SecretKey authenticationKey = derive(password, macSalt, iterations);
      derivedMac = authenticationKey.getEncoded();
      authenticate(derivedMac, sealed, sealed.length - 20);
      SecretKey decryptionKey = derive(password, encryptionSalt, iterations);
      Cipher cipher = Cipher.getInstance(PBE);
      cipher.init(Cipher.DECRYPT_MODE, decryptionKey, new PBEParameterSpec(encryptionSalt, iterations));
      combined = cipher.doFinal(sealed, 0, sealed.length - 20);
      if (combined.length != 36) throw new GeneralSecurityException("Invalid master secret");
      return new SilenceLegacyCipher(combined);
    } finally {
      wipe(derivedMac);
      wipe(combined);
      wipe(sealed);
    }
  }

  void verifyBody(String encoded) throws IOException, GeneralSecurityException {
    if (closed) throw new IllegalStateException("Closed legacy cipher");
    byte[] sealed = decode(encoded);
    byte[] plaintext = null;
    try {
      plaintext = decryptRecord(sealed);
    } finally {
      // The caller receives only success/failure, never plaintext or a String representation.
      wipe(plaintext);
      wipe(sealed);
    }
  }

  /** Caller owns and must wipe the returned plaintext; no store or result may retain it. */
  byte[] decryptRecord(byte[] sealed) throws GeneralSecurityException {
    if (closed) throw new IllegalStateException("Closed legacy cipher");
    int authenticatedLength = sealed.length - 20;
    if (authenticatedLength < 32 || (authenticatedLength - 16) % 16 != 0)
      throw new GeneralSecurityException("Invalid encrypted record");
    authenticate(macKey, sealed, authenticatedLength);
    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new IvParameterSpec(sealed, 0, 16));
    return cipher.doFinal(sealed, 16, authenticatedLength - 16);
  }

  private static SecretKey derive(char[] password, byte[] salt, int iterations) throws GeneralSecurityException {
    PBEKeySpec spec = new PBEKeySpec(password, salt, iterations);
    try { return SecretKeyFactory.getInstance(PBE).generateSecret(spec); }
    finally { spec.clearPassword(); }
  }

  private static void authenticate(byte[] key, byte[] sealed, int length) throws GeneralSecurityException {
    Mac mac = Mac.getInstance("HmacSHA1");
    mac.init(new SecretKeySpec(key, "HmacSHA1"));
    mac.update(sealed, 0, length);
    byte[] actual = mac.doFinal();
    byte[] expected = Arrays.copyOfRange(sealed, length, sealed.length);
    try {
      if (!MessageDigest.isEqual(actual, expected)) throw new GeneralSecurityException("Authentication failed");
    } finally { wipe(actual); wipe(expected); }
  }

  private static byte[] secret(Map<String, String> preferences, String key) throws IOException {
    String value = preferences.get(key);
    if (value == null || !value.startsWith("string:")) throw new IOException("Missing master secret field");
    return decode(value.substring(7));
  }

  static byte[] decode(String encoded) throws IOException {
    if (encoded == null || encoded.length() > 1024 * 1024) throw new IOException("Encrypted record size limit exceeded");
    String compact = encoded.replaceAll("[ \t\r\n]", "");
    if (compact.isEmpty() || compact.length() % 4 != 0 || !compact.matches("[A-Za-z0-9+/]*={0,2}"))
      throw new IOException("Invalid encrypted record encoding");
    return Base64.decode(compact, Base64.DONT_GUNZIP);
  }

  private static void wipe(byte[] value) { if (value != null) Arrays.fill(value, (byte) 0); }
  @Override public void close() {
    wipe(encryptionKey);
    wipe(macKey);
    closed = true;
  }
}
