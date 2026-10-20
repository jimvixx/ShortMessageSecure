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

package org.jimvixx.smsecure.jobs;

import android.content.Context;
import org.whispersystems.jobqueue.Job;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Versioned wire format, independent of Java names, reflection and R8.
 * Keep version 1 readable when adding future formats; never reuse a type identifier.
 * Payload order is part of the format. Requirements are recreated by job constructors.
 */
public final class StableJobCodec {
  private StableJobCodec() {}

  public static byte[] encode(Job job) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.writeInt(1);
    if (job instanceof SmsSendJob send) {
      out.writeUTF(send.mappingGeneration == 0 ? "sms_send" : "sms_send_v2");
      out.writeLong(send.messageId);
      writeNullable(out, send.getGroupId());
      writeNullable(out, send.sendAttemptId);
      if (send.mappingGeneration != 0) out.writeLong(send.mappingGeneration);
    } else if (job instanceof SmsReceiveJob receive) {
      out.writeUTF(receive.format != null ? "sms_receive_v3"
              : (receive.deviceSubscriptionId == null ? "sms_receive" : "sms_receive_v2"));
      out.writeInt(receive.subscriptionId);
      if (receive.deviceSubscriptionId != null) {
        out.writeInt(receive.deviceSubscriptionId);
        out.writeLong(receive.mappingGeneration);
      }
      if (receive.format != null) out.writeUTF(receive.format);
      writePdus(out, receive.pdus);
    } else if (job instanceof SmsDecryptJob decrypt) {
      if (decrypt.isReceivedWhenLocked == null) throw new IOException("Missing receive state");
      boolean hasRoute = decrypt.deviceSubscriptionId != null || decrypt.mappingGeneration != 0;
      out.writeUTF(hasRoute ? "sms_decrypt_v2" : "sms_decrypt");
      out.writeLong(decrypt.messageId);
      out.writeBoolean(decrypt.manualOverride);
      out.writeBoolean(decrypt.isReceivedWhenLocked);
      if (hasRoute) {
        writeNullableInt(out, decrypt.deviceSubscriptionId);
        out.writeLong(decrypt.mappingGeneration);
      }
    } else if (job instanceof SmsSentJob sent) {
      out.writeUTF(sent.deliveryAttempt != null ? "sms_delivery_v1"
              : (sent.mappingGeneration == 0 ? "sms_sent" : "sms_sent_v2"));
      out.writeLong(sent.messageId);
      writeNullable(out, sent.action);
      out.writeInt(sent.result);
      if (sent.deliveryAttempt == null && sent.mappingGeneration != 0) out.writeLong(sent.mappingGeneration);
      if (sent.deliveryAttempt != null) {
        out.writeUTF(sent.deliveryAttempt);
        out.writeInt(sent.deliveryPart);
        out.writeInt(sent.deliveryParts);
        out.writeLong(sent.deliveryReceivedAt);
      }
    } else if (job instanceof GenerateKeysJob) {
      out.writeUTF("generate_keys");
    } else {
      throw new IOException("Unsupported persistent job type");
    }
    out.flush();
    return bytes.toByteArray();
  }

  public static Job decode(Context context, byte[] bytes) throws IOException {
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
    if (in.readInt() != 1) throw new IOException("Unsupported job format version");
    Job job;
    switch (in.readUTF()) {
      case "sms_send":
        job = new SmsSendJob(context, in.readLong(), readNullable(in), readNullable(in));
        break;
      case "sms_send_v2":
        job = new SmsSendJob(context, in.readLong(), readNullable(in), readNullable(in), in.readLong());
        break;
      case "sms_receive":
        int subscription = in.readInt();
        job = new SmsReceiveJob(context, readPdus(in), subscription, true);
        break;
      case "sms_receive_v2":
        int appSubscription = in.readInt();
        int deviceSubscription = in.readInt();
        long receiveGeneration = in.readLong();
        job = new SmsReceiveJob(context, readPdus(in), appSubscription,
                deviceSubscription, receiveGeneration);
        break;
      case "sms_receive_v3":
        int formattedAppSubscription = in.readInt();
        int formattedDeviceSubscription = in.readInt();
        long formattedReceiveGeneration = in.readLong();
        String format = in.readUTF();
        job = new SmsReceiveJob(context, readPdus(in), formattedAppSubscription,
                formattedDeviceSubscription, formattedReceiveGeneration, format);
        break;
      case "sms_decrypt":
        job = new SmsDecryptJob(context, in.readLong(), in.readBoolean(), in.readBoolean(), null, 0);
        break;
      case "sms_decrypt_v2":
        job = new SmsDecryptJob(context, in.readLong(), in.readBoolean(), in.readBoolean(),
                readNullableInt(in), in.readLong());
        break;
      case "sms_sent":
        job = new SmsSentJob(context, in.readLong(), readNullable(in), in.readInt());
        break;
      case "sms_sent_v2":
        job = new SmsSentJob(context, in.readLong(), readNullable(in), in.readInt(), in.readLong());
        break;
      case "sms_delivery_v1":
        long id = in.readLong();
        String action = readNullable(in);
        int status = in.readInt();
        if (!org.jimvixx.smsecure.service.SmsDeliveryListener.DELIVERY_STATUS_ACTION.equals(action)) {
          throw new IOException("Invalid delivery action");
        }
        job = new SmsSentJob(context, id, status, in.readUTF(), in.readInt(), in.readInt(), in.readLong());
        break;
      case "generate_keys":
        job = new GenerateKeysJob(context);
        break;
      default:
        throw new IOException("Unsupported job type");
    }
    if (in.available() != 0) throw new IOException("Unexpected job payload suffix");
    return job;
  }

  private static void writeNullable(DataOutputStream out, String value) throws IOException {
    out.writeBoolean(value != null);
    if (value != null) out.writeUTF(value);
  }

  private static String readNullable(DataInputStream in) throws IOException {
    return in.readBoolean() ? in.readUTF() : null;
  }

  private static void writeNullableInt(DataOutputStream out, Integer value) throws IOException {
    out.writeBoolean(value != null);
    if (value != null) out.writeInt(value);
  }

  private static Integer readNullableInt(DataInputStream in) throws IOException {
    return in.readBoolean() ? in.readInt() : null;
  }

  private static void writePdus(DataOutputStream out, Object[] pdus) throws IOException {
    out.writeInt(pdus.length);
    for (Object value : pdus) {
      if (!(value instanceof byte[] pdu)) throw new IOException("Invalid SMS PDU");
      out.writeInt(pdu.length);
      out.write(pdu);
    }
  }

  private static Object[] readPdus(DataInputStream in) throws IOException {
    int count = in.readInt();
    if (count < 0 || count > in.available() / 4) throw new IOException("Invalid PDU count");
    Object[] pdus = new Object[count];
    for (int i = 0; i < count; i++) {
      int length = in.readInt();
      if (length < 0 || length > in.available()) throw new IOException("Invalid PDU length");
      byte[] pdu = new byte[length];
      in.readFully(pdu);
      pdus[i] = pdu;
    }
    return pdus;
  }
}
