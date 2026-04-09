/*
 * Copyright (C) 2015 Open Whisper Systems
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

import android.content.Context;

import org.jimvixx.smsecure.protocol.KeyExchangeMessage;
import org.jimvixx.smsecure.recipients.RecipientFactory;
import org.jimvixx.smsecure.recipients.Recipients;
import org.jimvixx.smsecure.sms.IncomingEncryptedMessage;
import org.jimvixx.smsecure.sms.IncomingKeyExchangeMessage;
import org.jimvixx.smsecure.sms.IncomingPreKeyBundleMessage;
import org.jimvixx.smsecure.sms.IncomingTextMessage;
import org.jimvixx.smsecure.sms.OutgoingKeyExchangeMessage;
import org.jimvixx.smsecure.sms.OutgoingPrekeyBundleMessage;
import org.jimvixx.smsecure.sms.OutgoingTextMessage;
import org.jimvixx.smsecure.sms.SmsTransportDetails;
import org.whispersystems.libsignal.DuplicateMessageException;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidKeyIdException;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.InvalidVersionException;
import org.whispersystems.libsignal.LegacyMessageException;
import org.whispersystems.libsignal.NoSessionException;
import org.whispersystems.libsignal.SessionCipher;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.StaleKeyExchangeException;
import org.whispersystems.libsignal.UntrustedIdentityException;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.protocol.PreKeySignalMessage;
import org.whispersystems.libsignal.protocol.SignalMessage;
import org.whispersystems.libsignal.state.SignalProtocolStore;

import java.io.IOException;

public class SmsCipher {

  private final SmsTransportDetails transportDetails = new SmsTransportDetails();

  private final SignalProtocolStore signalProtocolStore;

  public SmsCipher(SignalProtocolStore signalProtocolStore) {
    this.signalProtocolStore = signalProtocolStore;
  }

  public IncomingTextMessage decrypt(Context context, IncomingTextMessage message)
          throws LegacyMessageException, InvalidMessageException, DuplicateMessageException,
          NoSessionException, UntrustedIdentityException {
    try {
      byte[] decoded = transportDetails.getDecodedMessage(message.getMessageBody().getBytes());
      SignalMessage signalMessage = new SignalMessage(decoded);
      SessionCipher sessionCipher = new SessionCipher(signalProtocolStore, new SignalProtocolAddress(message.getSender(), 1));
      byte[] padded = sessionCipher.decrypt(signalMessage);
      byte[] plaintext = transportDetails.getStrippedPaddingMessageBody(padded);

      if (message.isEndSession() && "TERMINATE".equals(new String(plaintext))) {
        signalProtocolStore.deleteSession(new SignalProtocolAddress(message.getSender(), 1));
      }

      return message.withMessageBody(new String(plaintext));
    } catch (IOException | IllegalArgumentException | NullPointerException e) {
      throw new InvalidMessageException(e);
    }
  }

  public IncomingEncryptedMessage decrypt(Context context, IncomingPreKeyBundleMessage message)
          throws InvalidVersionException, InvalidMessageException, DuplicateMessageException,
          UntrustedIdentityException, LegacyMessageException {
    try {
      byte[] decoded = transportDetails.getDecodedMessage(message.getMessageBody().getBytes());
      PreKeySignalMessage preKeyMessage = new PreKeySignalMessage(decoded);
      SessionCipher sessionCipher = new SessionCipher(signalProtocolStore, new SignalProtocolAddress(message.getSender(), 1));
      byte[] padded = sessionCipher.decrypt(preKeyMessage);
      byte[] plaintext = transportDetails.getStrippedPaddingMessageBody(padded);

      return new IncomingEncryptedMessage(message, new String(plaintext));
    } catch (IOException | InvalidKeyException | InvalidKeyIdException e) {
      throw new InvalidMessageException(e);
    }
  }

  public OutgoingTextMessage encrypt(OutgoingTextMessage message)
          throws NoSessionException, UntrustedIdentityException {
    byte[] paddedBody = transportDetails.getPaddedMessageBody(message.getMessageBody().getBytes());
    String recipientNumber = message.getRecipients().getPrimaryRecipient().getNumber();

    if (!signalProtocolStore.containsSession(new SignalProtocolAddress(recipientNumber, 1))) {
      throw new NoSessionException("No session for: " + recipientNumber);
    }

    SessionCipher cipher = new SessionCipher(signalProtocolStore, new SignalProtocolAddress(recipientNumber, 1));
    CiphertextMessage ciphertextMessage = cipher.encrypt(paddedBody);
    String encodedCiphertext = new String(transportDetails.getEncodedMessage(ciphertextMessage.serialize()));

    if (ciphertextMessage.getType() == CiphertextMessage.PREKEY_TYPE) {
      return new OutgoingPrekeyBundleMessage(message, encodedCiphertext);
    } else {
      return message.withBody(encodedCiphertext);
    }
  }

  public OutgoingKeyExchangeMessage process(Context context, IncomingKeyExchangeMessage message)
          throws UntrustedIdentityException, StaleKeyExchangeException,
          InvalidVersionException, LegacyMessageException, InvalidMessageException {
    try {
      Recipients recipients = RecipientFactory.getRecipientsFromString(context, message.getSender(), false);
      SignalProtocolAddress signalProtocolAddress = new SignalProtocolAddress(message.getSender(), 1);
      KeyExchangeMessage exchangeMessage = new KeyExchangeMessage(transportDetails.getDecodedMessage(message.getMessageBody().getBytes()));
      SessionBuilder sessionBuilder = new SessionBuilder(signalProtocolStore, signalProtocolAddress);

      KeyExchangeMessage response = sessionBuilder.process(exchangeMessage);

      if (response != null) {
        byte[] serializedResponse = transportDetails.getEncodedMessage(response.serialize());
        return new OutgoingKeyExchangeMessage(recipients, new String(serializedResponse), message.getSubscriptionId());
      } else {
        return null;
      }
    } catch (IOException | InvalidKeyException e) {
      throw new InvalidMessageException(e);
    }
  }

}
