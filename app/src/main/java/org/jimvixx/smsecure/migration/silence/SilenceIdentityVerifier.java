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

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.whispersystems.libsignal.ecc.Curve;

/** Authenticates exported private identity keys and checks their derived public keys in memory. */
final class SilenceIdentityVerifier {
  static final String PUBLIC = "pref_identity_public_curve25519";
  static final String PRIVATE = "pref_identity_private_curve25519";

  SilenceIdentityInfo verify(Map<String, String> preferences, SilenceLegacyCipher cipher) throws IOException {
    checkCancelled();
    try {
      Set<String> slots = new HashSet<>();
      for (String key : preferences.keySet()) {
        checkCancelled();
        for (String prefix : new String[]{PUBLIC, PRIVATE}) {
          if (!key.startsWith(prefix)) continue;
          String suffix = key.substring(prefix.length());
          if (!suffix.isEmpty()) {
            if (!suffix.startsWith("_")) throw new IOException("Unsupported identity slot");
            SilenceSourceBinding.subscription(suffix.substring(1));
          }
          slots.add(suffix);
        }
      }
      for (String suffix : slots) {
        checkCancelled();
        byte[] publicKey = null, sealed = null, privateKey = null;
        try {
          publicKey = decode(preferences.get(PUBLIC + suffix));
          sealed = decode(preferences.get(PRIVATE + suffix));
          if (publicKey.length != 33 || publicKey[0] != Curve.DJB_TYPE)
            throw new IOException("Invalid identity public key");
          privateKey = cipher.decryptRecord(sealed);
          if (privateKey.length != 32) throw new IOException("Invalid identity private key");
          SilenceKeyPairVerifier.verify(publicKey, privateKey);
        } finally {
          wipe(publicKey); wipe(sealed); wipe(privateKey);
        }
      }
      return SilenceIdentityInfo.verified(slots.size());
    } catch (IOException | GeneralSecurityException e) {
      checkCancelled();
      return SilenceIdentityInfo.rejected();
    }
  }

  private static byte[] decode(String typed) throws IOException {
    if (typed == null || !typed.startsWith("string:")) throw new IOException("Incomplete identity pair");
    return SilenceLegacyCipher.decode(typed.substring(7));
  }
  private static void wipe(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
  private static void checkCancelled() throws IOException {
    if (Thread.currentThread().isInterrupted()) throw new IOException("Identity verification cancelled");
  }
}
