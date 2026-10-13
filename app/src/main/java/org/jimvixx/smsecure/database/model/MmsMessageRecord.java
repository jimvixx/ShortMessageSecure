/*
 * Copyright (C) 2026 Jimvixx
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.jimvixx.smsecure.database.model;

import android.content.Context;
import android.text.SpannableString;

import org.jimvixx.smsecure.database.MessageColumns;
import org.jimvixx.smsecure.database.SmsDatabase;
import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.recipients.Recipients;

import java.util.LinkedList;

/** Read-only MMS message model. Attachment bytes are intentionally not opened here. */
public final class MmsMessageRecord extends MessageRecord {
  private final boolean notification;
  private final int partCount;
  private final String contentLocation;
  private final long messageSize;
  private final long expiry;
  private final String transactionId;

  public MmsMessageRecord(Context context, long id, Body body, Recipients recipients,
                          Recipient individualRecipient, int recipientDeviceId,
                          long dateSent, long dateReceived, long dateDeliveryReceived,
                          long threadId, long type, int status, int partCount,
                          String contentLocation, long messageSize, long expiry,
                          String transactionId, boolean notification, int subscriptionId) {
    super(context, id, body, recipients, individualRecipient, recipientDeviceId,
        dateSent, dateReceived, threadId, status, dateDeliveryReceived, type,
        new LinkedList<>(), new LinkedList<>(), subscriptionId);
    this.notification = notification;
    this.partCount = partCount;
    this.contentLocation = contentLocation;
    this.messageSize = messageSize;
    this.expiry = expiry;
    this.transactionId = transactionId;
  }

  @Override public boolean isMms() { return true; }
  @Override public boolean isMmsNotification() { return notification; }
  @Override protected String getTransportType() { return MessageDatabaseTransport.MMS; }

  public int getPartCount() { return partCount; }
  public String getContentLocation() { return contentLocation; }
  public long getMessageSize() { return messageSize; }
  public long getExpiration() { return expiry * 1000L; }
  public String getTransactionId() { return transactionId; }

  @Override public boolean isSecure() {
    return MessageColumns.Types.isSecureType(type);
  }

  @Override public SpannableString getDisplayBody() {
    if (notification) return emphasisAdded("Multimedia message");
    if (!getBody().isPlaintext()) return emphasisAdded("Encrypted multimedia message");
    return super.getDisplayBody();
  }
}
