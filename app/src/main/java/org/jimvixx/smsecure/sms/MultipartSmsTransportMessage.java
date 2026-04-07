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

package org.jimvixx.smsecure.sms;

import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.protocol.WirePrefix;
import org.jimvixx.smsecure.util.Base64;
import org.jimvixx.smsecure.util.Conversions;

import java.io.IOException;

public class MultipartSmsTransportMessage {

  public static final int SINGLE_MESSAGE_MULTIPART_OVERHEAD = 1;
  public static final int MULTI_MESSAGE_MULTIPART_OVERHEAD = 3;
  public static final int FIRST_MULTI_MESSAGE_MULTIPART_OVERHEAD = 2;
  public static final int WIRETYPE_SECURE = 1;
  public static final int WIRETYPE_PREKEY = 2;
  public static final int WIRETYPE_END_SESSION = 3;
  public static final int WIRETYPE_XMPP_EXCHANGE = 4;
  public static final int WIRETYPE_KEY = 5;
  public static final int LAST_PREFIX_TO_TEST = 5;
  private static final String TAG = MultipartSmsTransportMessage.class.getName();
  private static final int MULTIPART_SUPPORTED_AFTER_VERSION = 1;
  private static final int VERSION_OFFSET = 0;
  private static final int MULTIPART_OFFSET = 1;
  private static final int IDENTIFIER_OFFSET = 2;
  private final byte[] decodedMessage;
  private final IncomingTextMessage message;
  private int wireType;

  public MultipartSmsTransportMessage(IncomingTextMessage message) throws IOException {
    try {
      this.message = message;
      this.decodedMessage =
              Base64.decodeWithoutPadding(message.getMessageBody().substring(WirePrefix.PREFIX_SIZE));

      redecodeWirePrefix(-1);
    } catch (IllegalArgumentException e) {
      throw new IOException(e);
    }
  }

  public static String getEncodedMessage(OutgoingTextMessage message) {
    try {
      byte[] decoded = Base64.decodeWithoutPadding(message.getMessageBody());

      return getEncoded(decoded, message);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private static String getEncoded(byte[] decoded, OutgoingTextMessage message) {
    byte[] messageWithMultipartHeader = new byte[decoded.length + 1];

    System.arraycopy(decoded, 0, messageWithMultipartHeader, 1, decoded.length);

    messageWithMultipartHeader[VERSION_OFFSET] = decoded[VERSION_OFFSET];
    messageWithMultipartHeader[MULTIPART_OFFSET] = Conversions.intsToByteHighAndLow(0, 1);

    String encodedMessage = Base64.encodeBytesWithoutPadding(messageWithMultipartHeader);
    String prefix = calculateWirePrefix(message, encodedMessage);

    return prefix + encodedMessage;
  }

  private static String calculateWirePrefix(OutgoingTextMessage message, String encodedMessage) {
    if (message.isKeyExchange()) {
      return WirePrefix.calculateKeyExchangePrefix(encodedMessage);
    }

    if (message.isPreKeyBundle()) {
      return WirePrefix.calculatePreKeyBundlePrefix(encodedMessage);
    }

    if (message.isEndSession()) {
      return WirePrefix.calculateEndSessionPrefix(encodedMessage);
    }

    return WirePrefix.calculateEncryptedMesagePrefix(encodedMessage);
  }

  public void redecodeWirePrefix(int lastIncorrectWirePrefix) throws IOException {
    if (lastIncorrectWirePrefix >= LAST_PREFIX_TO_TEST) {
      throw new IOException("Invalid message!");
    }

    String body = message.getMessageBody();

    if (lastIncorrectWirePrefix < WIRETYPE_SECURE && WirePrefix.isEncryptedMessage(body)) {
      wireType = WIRETYPE_SECURE;
    } else if (lastIncorrectWirePrefix < WIRETYPE_PREKEY && WirePrefix.isPreKeyBundle(body)) {
      wireType = WIRETYPE_PREKEY;
    } else if (lastIncorrectWirePrefix < WIRETYPE_END_SESSION && WirePrefix.isEndSession(body)) {
      wireType = WIRETYPE_END_SESSION;
    } else if (lastIncorrectWirePrefix < WIRETYPE_XMPP_EXCHANGE && WirePrefix.isXmppExchange(body)) {
      wireType = WIRETYPE_XMPP_EXCHANGE;
    } else {
      wireType = WIRETYPE_KEY;
    }

    Log.w(TAG, "Decoded message with version:   " + getCurrentVersion());
    Log.w(TAG, "Decoded message with wire type: " + wireType);
  }

  public int getWireType() {
    return wireType;
  }

  public int getCurrentVersion() {
    return Conversions.highBitsToInt(decodedMessage[VERSION_OFFSET]);
  }

  public int getMultipartIndex() {
    return Conversions.highBitsToInt(decodedMessage[MULTIPART_OFFSET]);
  }

  public int getMultipartCount() {
    if (isDeprecatedTransport()) {
      return 1;
    }

    return Conversions.lowBitsToInt(decodedMessage[MULTIPART_OFFSET]);
  }

  public int getIdentifier() {
    return decodedMessage[IDENTIFIER_OFFSET] & 0xFF;
  }

  public boolean isDeprecatedTransport() {
    return getCurrentVersion() < MULTIPART_SUPPORTED_AFTER_VERSION;
  }

  public boolean isInvalid() {
    return getMultipartIndex() >= getMultipartCount();
  }

  public boolean isSinglePart() {
    return getMultipartCount() == 1;
  }

  public byte[] getStrippedMessage() {
    if (isDeprecatedTransport()) {
      return getStrippedMessageForDeprecatedTransport();
    }

    if (isSinglePart()) {
      return getStrippedMessageForSinglePart();
    }

    return getStrippedMessageForMultiPart();
  }

  private byte[] getStrippedMessageForDeprecatedTransport() {
    return decodedMessage;
  }

  private byte[] getStrippedMessageForSinglePart() {
    byte[] stripped = new byte[decodedMessage.length - 1];
    System.arraycopy(decodedMessage, 1, stripped, 0, decodedMessage.length - 1);
    stripped[0] = decodedMessage[VERSION_OFFSET];
    return stripped;
  }

  private byte[] getStrippedMessageForMultiPart() {
    byte[] strippedMessage =
            new byte[decodedMessage.length - (getMultipartIndex() == 0 ? 2 : 3)];

    int copyDestinationIndex = 0;
    int copyDestinationLength = strippedMessage.length;

    if (getMultipartIndex() == 0) {
      strippedMessage[0] = decodedMessage[VERSION_OFFSET];
      copyDestinationIndex++;
      copyDestinationLength--;
    }

    System.arraycopy(decodedMessage, 3, strippedMessage, copyDestinationIndex, copyDestinationLength);
    return strippedMessage;
  }

  public String getKey() {
    return message.getSender() + getIdentifier();
  }

  public IncomingTextMessage getBaseMessage() {
    return message;
  }
}