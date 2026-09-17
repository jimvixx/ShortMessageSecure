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
import java.security.MessageDigest;
import java.util.Arrays;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.ecc.Curve;

/** Shared legacy key-pair check; the caller retains ownership of the input buffers. */
final class SilenceKeyPairVerifier {
  static void verify(byte[] publicKey, byte[] privateKey) throws IOException {
    if (publicKey.length != 33 || publicKey[0] != Curve.DJB_TYPE || privateKey.length != 32)
      throw new IOException("Invalid key pair shape");
    byte[] derived = null, expected = null;
    try {
      // X25519 with the standard base point derives the public key from the private scalar.
      byte[] basePoint = new byte[33]; basePoint[0] = Curve.DJB_TYPE; basePoint[1] = 9;
      derived = Curve.calculateAgreement(Curve.decodePoint(basePoint, 0), Curve.decodePrivatePoint(privateKey));
      expected = Arrays.copyOfRange(publicKey, 1, 33);
      if (!MessageDigest.isEqual(expected, derived)) throw new IOException("Mismatched key pair");
    } catch (InvalidKeyException e) { throw new IOException("Invalid key pair"); }
    finally {
      if (derived != null) Arrays.fill(derived, (byte) 0);
      if (expected != null) Arrays.fill(expected, (byte) 0);
    }
  }
}
