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

package org.jimvixx.smsecure.crypto;

import org.jimvixx.smsecure.protocol.KeyExchangeMessage;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidKeyIdException;
import org.whispersystems.libsignal.SessionCipher;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.StaleKeyExchangeException;
import org.whispersystems.libsignal.UntrustedIdentityException;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.logging.Log;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.protocol.PreKeySignalMessage;
import org.whispersystems.libsignal.ratchet.AliceSignalProtocolParameters;
import org.whispersystems.libsignal.ratchet.BobSignalProtocolParameters;
import org.whispersystems.libsignal.ratchet.RatchetingSession;
import org.whispersystems.libsignal.ratchet.SymmetricSignalProtocolParameters;
import org.whispersystems.libsignal.state.IdentityKeyStore;
import org.whispersystems.libsignal.state.PreKeyBundle;
import org.whispersystems.libsignal.state.PreKeyStore;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SessionState;
import org.whispersystems.libsignal.state.SessionStore;
import org.whispersystems.libsignal.state.SignalProtocolStore;
import org.whispersystems.libsignal.state.SignedPreKeyStore;
import org.whispersystems.libsignal.util.KeyHelper;
import org.whispersystems.libsignal.util.Medium;
import org.whispersystems.libsignal.util.guava.Optional;

public class SessionBuilder {

  private static final String TAG = SessionBuilder.class.getSimpleName();

  private final SessionStore sessionStore;
  private final PreKeyStore preKeyStore;
  private final SignedPreKeyStore signedPreKeyStore;
  private final IdentityKeyStore identityKeyStore;
  private final SignalProtocolAddress remoteAddress;

  /**
   * Constructs a SessionBuilder.
   *
   * @param sessionStore     The {@link org.whispersystems.libsignal.state.SessionStore} to store the constructed session in.
   * @param preKeyStore      The {@link  org.whispersystems.libsignal.state.PreKeyStore} where the client's local {@link org.whispersystems.libsignal.state.PreKeyRecord}s are stored.
   * @param identityKeyStore The {@link org.whispersystems.libsignal.state.IdentityKeyStore} containing the client's identity key information.
   * @param remoteAddress    The address of the remote user to build a session with.
   */
  public SessionBuilder(SessionStore sessionStore,
                        PreKeyStore preKeyStore,
                        SignedPreKeyStore signedPreKeyStore,
                        IdentityKeyStore identityKeyStore,
                        SignalProtocolAddress remoteAddress) {
    this.sessionStore = sessionStore;
    this.preKeyStore = preKeyStore;
    this.signedPreKeyStore = signedPreKeyStore;
    this.identityKeyStore = identityKeyStore;
    this.remoteAddress = remoteAddress;
  }

  /**
   * Constructs a SessionBuilder
   *
   * @param store         The {@link SignalProtocolStore} to store all state information in.
   * @param remoteAddress The address of the remote user to build a session with.
   */
  public SessionBuilder(SignalProtocolStore store, SignalProtocolAddress remoteAddress) {
    this(store, store, store, store, remoteAddress);
  }

  /*package*/
  @SuppressWarnings("unused")
  Optional<Integer> process(SessionRecord sessionRecord, PreKeySignalMessage message)
          throws InvalidKeyIdException, InvalidKeyException, UntrustedIdentityException {
    IdentityKey theirIdentityKey = message.getIdentityKey();

    if (!identityKeyStore.isTrustedIdentity(remoteAddress, theirIdentityKey, IdentityKeyStore.Direction.RECEIVING)) {
      throw new UntrustedIdentityException(remoteAddress.getName(), theirIdentityKey);
    }

    Optional<Integer> unsignedPreKeyId = processV3(sessionRecord, message);

    identityKeyStore.saveIdentity(remoteAddress, theirIdentityKey);
    return unsignedPreKeyId;
  }

  private Optional<Integer> processV3(SessionRecord sessionRecord, PreKeySignalMessage message)
          throws InvalidKeyIdException, InvalidKeyException {

    if (sessionRecord.hasSessionState(message.getMessageVersion(), message.getBaseKey().serialize())) {
      Log.w(TAG, "We've already setup a session for this V3 message, letting bundled message fall through...");
      return Optional.absent();
    }

    ECKeyPair ourSignedPreKey = signedPreKeyStore.loadSignedPreKey(message.getSignedPreKeyId()).getKeyPair();

    BobSignalProtocolParameters.Builder parameters = BobSignalProtocolParameters.newBuilder();

    parameters.setTheirBaseKey(message.getBaseKey())
            .setTheirIdentityKey(message.getIdentityKey())
            .setOurIdentityKey(identityKeyStore.getIdentityKeyPair())
            .setOurSignedPreKey(ourSignedPreKey)
            .setOurRatchetKey(ourSignedPreKey);

    if (message.getPreKeyId().isPresent()) {
      parameters.setOurOneTimePreKey(Optional.of(preKeyStore.loadPreKey(message.getPreKeyId().get()).getKeyPair()));
    } else {
      parameters.setOurOneTimePreKey(Optional.absent());
    }

    if (!sessionRecord.isFresh()) sessionRecord.archiveCurrentState();

    RatchetingSession.initializeSession(sessionRecord.getSessionState(), parameters.create());

    sessionRecord.getSessionState().setLocalRegistrationId(identityKeyStore.getLocalRegistrationId());
    sessionRecord.getSessionState().setRemoteRegistrationId(message.getRegistrationId());
    sessionRecord.getSessionState().setAliceBaseKey(message.getBaseKey().serialize());

    if (message.getPreKeyId().isPresent() && message.getPreKeyId().get() != Medium.MAX_VALUE) {
      return message.getPreKeyId();
    } else {
      return Optional.absent();
    }
  }

  public void process(PreKeyBundle preKey) throws InvalidKeyException, UntrustedIdentityException {
    synchronized (SessionCipher.SESSION_LOCK) {
      if (!identityKeyStore.isTrustedIdentity(remoteAddress, preKey.getIdentityKey(), IdentityKeyStore.Direction.SENDING)) {
        throw new UntrustedIdentityException(remoteAddress.getName(), preKey.getIdentityKey());
      }

      if (preKey.getSignedPreKey() != null &&
              !Curve.verifySignature(preKey.getIdentityKey().getPublicKey(),
                      preKey.getSignedPreKey().serialize(),
                      preKey.getSignedPreKeySignature())) {
        throw new InvalidKeyException("Invalid signature on device key!");
      }

      if (preKey.getSignedPreKey() == null) {
        throw new InvalidKeyException("No signed prekey!");
      }

      SessionRecord sessionRecord = sessionStore.loadSession(remoteAddress);
      ECKeyPair ourBaseKey = Curve.generateKeyPair();
      ECPublicKey theirSignedPreKey = preKey.getSignedPreKey();
      Optional<ECPublicKey> theirOneTimePreKey = Optional.fromNullable(preKey.getPreKey());
      Optional<Integer> theirOneTimePreKeyId = theirOneTimePreKey.isPresent() ? Optional.of(preKey.getPreKeyId()) :
              Optional.absent();

      AliceSignalProtocolParameters.Builder parameters = AliceSignalProtocolParameters.newBuilder();

      parameters.setOurBaseKey(ourBaseKey)
              .setOurIdentityKey(identityKeyStore.getIdentityKeyPair())
              .setTheirIdentityKey(preKey.getIdentityKey())
              .setTheirSignedPreKey(theirSignedPreKey)
              .setTheirRatchetKey(theirSignedPreKey)
              .setTheirOneTimePreKey(theirOneTimePreKey);

      if (!sessionRecord.isFresh()) sessionRecord.archiveCurrentState();

      RatchetingSession.initializeSession(sessionRecord.getSessionState(), parameters.create());

      sessionRecord.getSessionState().setUnacknowledgedPreKeyMessage(theirOneTimePreKeyId, preKey.getSignedPreKeyId(), ourBaseKey.getPublicKey());
      sessionRecord.getSessionState().setLocalRegistrationId(identityKeyStore.getLocalRegistrationId());
      sessionRecord.getSessionState().setRemoteRegistrationId(preKey.getRegistrationId());
      sessionRecord.getSessionState().setAliceBaseKey(ourBaseKey.getPublicKey().serialize());

      identityKeyStore.saveIdentity(remoteAddress, preKey.getIdentityKey());
      sessionStore.storeSession(remoteAddress, sessionRecord);
    }
  }

  public KeyExchangeMessage process(KeyExchangeMessage message)
          throws InvalidKeyException, UntrustedIdentityException, StaleKeyExchangeException {
    synchronized (SessionCipher.SESSION_LOCK) {
      if (!identityKeyStore.isTrustedIdentity(remoteAddress, message.getIdentityKey(), IdentityKeyStore.Direction.SENDING)) {
        throw new UntrustedIdentityException(remoteAddress.getName(), message.getIdentityKey());
      }

      KeyExchangeMessage responseMessage = null;

      if (message.isInitiate()) responseMessage = processInitiate(message);
      else processResponse(message);

      return responseMessage;
    }
  }

  private KeyExchangeMessage processInitiate(KeyExchangeMessage message) throws InvalidKeyException {
    int flags = KeyExchangeMessage.RESPONSE_FLAG;
    SessionRecord sessionRecord = sessionStore.loadSession(remoteAddress);
    SessionState sessionState = sessionRecord.getSessionState();

    if (!Curve.verifySignature(message.getIdentityKey().getPublicKey(),
            message.getBaseKey().serialize(),
            message.getBaseKeySignature())) {
      throw new InvalidKeyException("Bad signature!");
    }

    SymmetricSignalProtocolParameters.Builder builder = SymmetricSignalProtocolParameters.newBuilder();

    /*
     * IMPORTANT:
     * Some legacy/migrated databases may report "hasPendingKeyExchange() == true"
     * while the actual pending fields are null (corrupt/incomplete state).
     * In that case, never feed nulls into SymmetricSignalProtocolParameters.Builder,
     * because it throws IllegalArgumentException("Null values!") and crashes the job thread.
     */
    boolean usePending = sessionState.hasPendingKeyExchange();

    if (usePending) {
      IdentityKeyPair pendingIdentity = sessionState.getPendingKeyExchangeIdentityKey();
      ECKeyPair pendingBase = sessionState.getPendingKeyExchangeBaseKey();
      ECKeyPair pendingRatchet = sessionState.getPendingKeyExchangeRatchetKey();

      if (pendingIdentity == null || pendingBase == null || pendingRatchet == null) {
        Log.w(TAG, "Pending KeyExchange state is corrupt (null fields). Falling back to fresh keys. " +
                "identity=" + (pendingIdentity != null) +
                " base=" + (pendingBase != null) +
                " ratchet=" + (pendingRatchet != null));
        usePending = false;
      } else {
        builder.setOurIdentityKey(pendingIdentity)
                .setOurBaseKey(pendingBase)
                .setOurRatchetKey(pendingRatchet);
        flags |= KeyExchangeMessage.SIMULTAENOUS_INITIATE_FLAG;
      }
    }

    if (!usePending) {
      builder.setOurIdentityKey(identityKeyStore.getIdentityKeyPair())
              .setOurBaseKey(Curve.generateKeyPair())
              .setOurRatchetKey(Curve.generateKeyPair());
    }

    builder.setTheirBaseKey(message.getBaseKey())
            .setTheirRatchetKey(message.getRatchetKey())
            .setTheirIdentityKey(message.getIdentityKey());

    SymmetricSignalProtocolParameters parameters = builder.create();

    if (!sessionRecord.isFresh()) sessionRecord.archiveCurrentState();

    RatchetingSession.initializeSession(sessionRecord.getSessionState(), parameters);

    identityKeyStore.saveIdentity(remoteAddress, message.getIdentityKey());
    sessionStore.storeSession(remoteAddress, sessionRecord);

    byte[] baseKeySignature = Curve.calculateSignature(parameters.getOurIdentityKey().getPrivateKey(),
            parameters.getOurBaseKey().getPublicKey().serialize());

    return new KeyExchangeMessage(sessionRecord.getSessionState().getSessionVersion(),
            message.getSequence(), flags,
            parameters.getOurBaseKey().getPublicKey(),
            baseKeySignature, parameters.getOurRatchetKey().getPublicKey(),
            parameters.getOurIdentityKey().getPublicKey());
  }

  private void processResponse(KeyExchangeMessage message)
          throws StaleKeyExchangeException, InvalidKeyException {
    SessionRecord sessionRecord = sessionStore.loadSession(remoteAddress);
    SessionState sessionState = sessionRecord.getSessionState();
    boolean hasPendingKeyExchange = sessionState.hasPendingKeyExchange();
    boolean isSimultaneousInitiateResponse = message.isResponseForSimultaneousInitiate();

    if (!hasPendingKeyExchange || sessionState.getPendingKeyExchangeSequence() != message.getSequence()) {
      Log.w(TAG, "No matching sequence for response. Is simultaneous initiate response: " + isSimultaneousInitiateResponse);
      if (!isSimultaneousInitiateResponse) throw new StaleKeyExchangeException();
      else return;
    }

    IdentityKeyPair pendingIdentity = sessionState.getPendingKeyExchangeIdentityKey();
    ECKeyPair pendingBase = sessionState.getPendingKeyExchangeBaseKey();
    ECKeyPair pendingRatchet = sessionState.getPendingKeyExchangeRatchetKey();

    /*
     * Same corruption guard as initiate().
     * If pending fields are missing, treat this as stale/invalid.
     */
    if (pendingIdentity == null || pendingBase == null || pendingRatchet == null) {
      Log.w(TAG, "Pending KeyExchange response state is corrupt (null fields). " +
              "identity=" + (pendingIdentity != null) +
              " base=" + (pendingBase != null) +
              " ratchet=" + (pendingRatchet != null));
      if (!isSimultaneousInitiateResponse) throw new StaleKeyExchangeException();
      return;
    }

    SymmetricSignalProtocolParameters.Builder parameters = SymmetricSignalProtocolParameters.newBuilder();

    parameters.setOurBaseKey(pendingBase)
            .setOurRatchetKey(pendingRatchet)
            .setOurIdentityKey(pendingIdentity)
            .setTheirBaseKey(message.getBaseKey())
            .setTheirRatchetKey(message.getRatchetKey())
            .setTheirIdentityKey(message.getIdentityKey());

    if (!sessionRecord.isFresh()) sessionRecord.archiveCurrentState();

    RatchetingSession.initializeSession(sessionRecord.getSessionState(), parameters.create());

    if (!Curve.verifySignature(message.getIdentityKey().getPublicKey(),
            message.getBaseKey().serialize(),
            message.getBaseKeySignature())) {
      throw new InvalidKeyException("Base key signature doesn't match!");
    }

    identityKeyStore.saveIdentity(remoteAddress, message.getIdentityKey());
    sessionStore.storeSession(remoteAddress, sessionRecord);
  }

  public KeyExchangeMessage process() {
    synchronized (SessionCipher.SESSION_LOCK) {
      try {
        int sequence = KeyHelper.getRandomSequence(65534) + 1;
        int flags = KeyExchangeMessage.INITIATE_FLAG;
        ECKeyPair baseKey = Curve.generateKeyPair();
        ECKeyPair ratchetKey = Curve.generateKeyPair();
        IdentityKeyPair identityKey = identityKeyStore.getIdentityKeyPair();
        byte[] baseKeySignature = Curve.calculateSignature(identityKey.getPrivateKey(), baseKey.getPublicKey().serialize());
        SessionRecord sessionRecord = sessionStore.loadSession(remoteAddress);

        sessionRecord.getSessionState().setPendingKeyExchange(sequence, baseKey, ratchetKey, identityKey);
        sessionStore.storeSession(remoteAddress, sessionRecord);

        return new KeyExchangeMessage(CiphertextMessage.CURRENT_VERSION,
                sequence, flags, baseKey.getPublicKey(), baseKeySignature,
                ratchetKey.getPublicKey(), identityKey.getPublicKey());
      } catch (InvalidKeyException e) {
        throw new AssertionError(e);
      }
    }
  }
}
