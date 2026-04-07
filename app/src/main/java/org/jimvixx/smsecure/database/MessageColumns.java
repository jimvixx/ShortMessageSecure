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

package org.jimvixx.smsecure.database;

public interface MessageColumns {

  String ID = "_id";
  String NORMALIZED_DATE_SENT = "date_sent";
  String NORMALIZED_DATE_RECEIVED = "date_received";
  String THREAD_ID = "thread_id";
  String READ = "read";
  String BODY = "body";
  String ADDRESS = "address";
  String ADDRESS_DEVICE_ID = "address_device_id";
  String DATE_DELIVERY_RECEIVED = "date_delivery_received";
  String MISMATCHED_IDENTITIES = "mismatched_identities";
  String UNIQUE_ROW_ID = "unique_row_id";
  String SUBSCRIPTION_ID = "subscription_id";
  String NOTIFIED = "notified";

  class Types {
    public static final long BASE_DRAFT_TYPE = 27;
    protected static final long TOTAL_MASK = 0xFFFFFFFFL;
    // Base Types
    protected static final long BASE_TYPE_MASK = 0x1F;
    protected static final long BASE_INBOX_TYPE = 20;
    protected static final long BASE_OUTBOX_TYPE = 21;
    protected static final long BASE_SENDING_TYPE = 22;
    protected static final long BASE_SENT_TYPE = 23;
    protected static final long BASE_SENT_FAILED_TYPE = 24;
    protected static final long[] OUTGOING_MESSAGE_TYPES = {BASE_OUTBOX_TYPE, BASE_SENT_TYPE,
            BASE_SENDING_TYPE, BASE_SENT_FAILED_TYPE};

    // Message attributes
    protected static final long MESSAGE_ATTRIBUTE_MASK = 0xE0;
    protected static final long MESSAGE_FORCE_SMS_BIT = 0x40;

    // Key Exchange Information
    protected static final long KEY_EXCHANGE_MASK = 0xFF00;
    protected static final long KEY_EXCHANGE_BIT = 0x8000;
    protected static final long KEY_EXCHANGE_STALE_BIT = 0x4000;
    protected static final long KEY_EXCHANGE_PROCESSED_BIT = 0x2000;
    protected static final long KEY_EXCHANGE_CORRUPTED_BIT = 0x1000;
    protected static final long KEY_EXCHANGE_INVALID_VERSION_BIT = 0x800;
    protected static final long KEY_EXCHANGE_BUNDLE_BIT = 0x400;
    protected static final long KEY_EXCHANGE_IDENTITY_UPDATE_BIT = 0x200;

    // Secure Message Information
    protected static final long SECURE_MESSAGE_BIT = 0x800000;
    protected static final long END_SESSION_BIT = 0x400000;
    protected static final long PUSH_MESSAGE_BIT = 0x200000;

    // Group Message Information
    protected static final long GROUP_UPDATE_BIT = 0x10000;
    protected static final long GROUP_QUIT_BIT = 0x20000;

    // XMPP Message Information
    protected static final long XMPP_EXCHANGE_BIT = 0x30000;

    // Encrypted Storage Information
    protected static final long ENCRYPTION_MASK = 0xFF000000L;
    protected static final long ENCRYPTION_SYMMETRIC_BIT = 0x80000000L;
    protected static final long ENCRYPTION_ASYMMETRIC_BIT = 0x40000000;
    protected static final long ENCRYPTION_REMOTE_BIT = 0x20000000;
    protected static final long ENCRYPTION_REMOTE_FAILED_BIT = 0x10000000;
    protected static final long ENCRYPTION_REMOTE_NO_SESSION_BIT = 0x08000000;
    protected static final long ENCRYPTION_REMOTE_DUPLICATE_BIT = 0x04000000;
    protected static final long ENCRYPTION_REMOTE_LEGACY_BIT = 0x02000000;

    public static boolean isDraftMessageType(long type) {
      return (type & BASE_TYPE_MASK) == BASE_DRAFT_TYPE;
    }

    public static boolean isFailedMessageType(long type) {
      return (type & BASE_TYPE_MASK) == BASE_SENT_FAILED_TYPE;
    }

    public static boolean isOutgoingMessageType(long type) {
      for (long outgoingType : OUTGOING_MESSAGE_TYPES) {
        if ((type & BASE_TYPE_MASK) == outgoingType)
          return true;
      }

      return false;
    }

    public static boolean isForcedSms(long type) {
      return (type & MESSAGE_FORCE_SMS_BIT) != 0;
    }

    public static boolean isPendingMessageType(long type) {
      return
              (type & BASE_TYPE_MASK) == BASE_OUTBOX_TYPE ||
                      (type & BASE_TYPE_MASK) == BASE_SENDING_TYPE;
    }

    public static boolean isInboxType(long type) {
      return (type & BASE_TYPE_MASK) == BASE_INBOX_TYPE;
    }

    public static boolean isSecureType(long type) {
      return (type & SECURE_MESSAGE_BIT) != 0;
    }

    public static boolean isPushType(long type) {
      return (type & PUSH_MESSAGE_BIT) != 0;
    }

    public static boolean isEndSessionType(long type) {
      return (type & END_SESSION_BIT) != 0;
    }

    public static boolean isKeyExchangeType(long type) {
      return (type & KEY_EXCHANGE_BIT) != 0;
    }

    public static boolean isStaleKeyExchange(long type) {
      return (type & KEY_EXCHANGE_STALE_BIT) != 0;
    }

    public static boolean isProcessedKeyExchange(long type) {
      return (type & KEY_EXCHANGE_PROCESSED_BIT) != 0;
    }

    public static boolean isCorruptedKeyExchange(long type) {
      return (type & KEY_EXCHANGE_CORRUPTED_BIT) != 0;
    }

    public static boolean isInvalidVersionKeyExchange(long type) {
      return (type & KEY_EXCHANGE_INVALID_VERSION_BIT) != 0;
    }

    public static boolean isBundleKeyExchange(long type) {
      return (type & KEY_EXCHANGE_BUNDLE_BIT) != 0;
    }

    public static boolean isIdentityUpdate(long type) {
      return (type & KEY_EXCHANGE_IDENTITY_UPDATE_BIT) != 0;
    }

    public static boolean isGroupUpdate(long type) {
      return (type & GROUP_UPDATE_BIT) != 0;
    }

    public static boolean isGroupQuit(long type) {
      return (type & GROUP_QUIT_BIT) != 0;
    }

    public static boolean isSymmetricEncryption(long type) {
      return (type & ENCRYPTION_SYMMETRIC_BIT) != 0;
    }

    public static boolean isAsymmetricEncryption(long type) {
      return (type & ENCRYPTION_ASYMMETRIC_BIT) != 0;
    }

    public static boolean isFailedDecryptType(long type) {
      return (type & ENCRYPTION_REMOTE_FAILED_BIT) != 0;
    }

    public static boolean isDuplicateMessageType(long type) {
      return (type & ENCRYPTION_REMOTE_DUPLICATE_BIT) != 0;
    }

    public static boolean isDecryptInProgressType(long type) {
      return
              (type & ENCRYPTION_REMOTE_BIT) != 0 ||
                      (type & ENCRYPTION_ASYMMETRIC_BIT) != 0;
    }

    public static boolean isNoRemoteSessionType(long type) {
      return (type & ENCRYPTION_REMOTE_NO_SESSION_BIT) != 0;
    }

    public static boolean isLegacyType(long type) {
      return (type & ENCRYPTION_REMOTE_LEGACY_BIT) != 0;
    }

    public static boolean isXmppExchangeType(long type) {
      return (type & XMPP_EXCHANGE_BIT) != 0;
    }

    public static long translateFromSystemBaseType(long theirType) {
      return switch ((int) theirType) {
        case 1 -> BASE_INBOX_TYPE;
        case 2 -> BASE_SENT_TYPE;
        case 3 -> BASE_DRAFT_TYPE;
        case 4, 6 -> BASE_OUTBOX_TYPE;
        case 5 -> BASE_SENT_FAILED_TYPE;
        default -> BASE_INBOX_TYPE;
      };

    }

    public static int translateToSystemBaseType(long type) {
      if (isInboxType(type)) return 1;
      else if (isOutgoingMessageType(type)) return 2;
      else if (isFailedMessageType(type)) return 5;

      return 1;
    }
  }
}
