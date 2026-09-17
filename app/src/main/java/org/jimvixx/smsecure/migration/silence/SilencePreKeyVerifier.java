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
import java.util.Arrays;
import java.util.Map;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.ecc.Curve;

/** Checks a prekey against the exact verified source identity slot, never a target-device SIM. */
final class SilencePreKeyVerifier {
  private final Map<String, String> preferences;
  private final SilenceIdentityInfo identities;
  SilencePreKeyVerifier(Map<String, String> preferences, SilenceIdentityInfo identities) {
    this.preferences = preferences;
    this.identities = identities;
  }

  /** Takes ownership of decoded record buffers and wipes them even when validation fails. */
  void verify(int subscription, byte[] publicKey, byte[] privateKey, byte[] signature) throws IOException {
    byte[] identity = null;
    try {
      if (Thread.currentThread().isInterrupted()) throw new IOException("Prekey verification cancelled");
      if (identities.getStatus() != SilenceIdentityInfo.Status.VERIFIED)
        throw new IOException("Prekeys require verified source identities");
      String suffix = subscription == -1 ? "" : "_" + subscription;
      String value = preferences.get(SilenceIdentityVerifier.PUBLIC + suffix);
      if (value == null || !value.startsWith("string:")) throw new IOException("Missing source identity slot");
      identity = SilenceLegacyCipher.decode(value.substring(7));
      if (identity.length != 33 || identity[0] != Curve.DJB_TYPE) throw new IOException("Invalid source identity");
      SilenceKeyPairVerifier.verify(publicKey, privateKey);
      if (signature != null && (signature.length != 64 ||
          !Curve.verifySignature(Curve.decodePoint(identity, 0), publicKey, signature)))
        throw new IOException("Invalid signed prekey signature");
    } catch (InvalidKeyException e) { throw new IOException("Invalid signed prekey key"); }
    finally {
      Arrays.fill(publicKey, (byte) 0);
      Arrays.fill(privateKey, (byte) 0);
      if (signature != null) Arrays.fill(signature, (byte) 0);
      if (identity != null) Arrays.fill(identity, (byte) 0);
    }
  }
}
