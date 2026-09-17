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

import com.google.protobuf.ByteString;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.state.StorageProtos.SessionStructure;

/** Synthetic session states with valid key pairs; no captured user session material. */
final class SilenceSessionTestData {
  static SessionStructure active(ECKeyPair identity) {
    ECKeyPair sender = Curve.generateKeyPair();
    return SessionStructure.newBuilder().setSessionVersion(3)
        .setLocalIdentityPublic(bytes(identity.getPublicKey().serialize()))
        .setRemoteIdentityPublic(bytes(Curve.generateKeyPair().getPublicKey().serialize()))
        .setRootKey(bytes(new byte[32]))
        .setSenderChain(SessionStructure.Chain.newBuilder()
            .setSenderRatchetKey(bytes(sender.getPublicKey().serialize()))
            .setSenderRatchetKeyPrivate(bytes(sender.getPrivateKey().serialize()))
            .setChainKey(SessionStructure.Chain.ChainKey.newBuilder().setIndex(0).setKey(bytes(new byte[32]))))
        .build();
  }
  static SessionStructure pending(ECKeyPair identity) {
    ECKeyPair base = Curve.generateKeyPair(), ratchet = Curve.generateKeyPair();
    return SessionStructure.newBuilder().setPendingKeyExchange(SessionStructure.PendingKeyExchange.newBuilder()
        .setSequence(1).setLocalIdentityKey(bytes(identity.getPublicKey().serialize()))
        .setLocalIdentityKeyPrivate(bytes(identity.getPrivateKey().serialize()))
        .setLocalBaseKey(bytes(base.getPublicKey().serialize())).setLocalBaseKeyPrivate(bytes(base.getPrivateKey().serialize()))
        .setLocalRatchetKey(bytes(ratchet.getPublicKey().serialize())).setLocalRatchetKeyPrivate(bytes(ratchet.getPrivateKey().serialize())))
        .build();
  }
  static SessionStructure.Chain receiver() {
    return SessionStructure.Chain.newBuilder().setSenderRatchetKey(bytes(Curve.generateKeyPair().getPublicKey().serialize()))
        .setChainKey(SessionStructure.Chain.ChainKey.newBuilder().setIndex(2).setKey(bytes(new byte[32]))).build();
  }
  static ByteString bytes(byte[] bytes) { return ByteString.copyFrom(bytes); }
}
