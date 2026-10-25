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

package org.jimvixx.smsecure.database;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.jimvixx.smsecure.database.model.MessageRecord;
import org.jimvixx.smsecure.crypto.MasterCipher;
import org.jimvixx.smsecure.crypto.MasterSecret;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class MmsDatabaseReadOnlyTest {
  private Context context;
  private File databaseFile;
  private SQLiteOpenHelper helper;

  @Before public void setUp() throws Exception {
    context = ApplicationProvider.getApplicationContext();
    databaseFile = new File(context.getCacheDir(), "mms-read-only-" + System.nanoTime() + ".db");
    helper = helper();
    SQLiteDatabase db = helper.getWritableDatabase();
    db.execSQL("INSERT INTO mms (_id, thread_id, date, date_received, msg_box, read, body, part_count, tr_id) VALUES (7, 9, 1000, 2000, 1, 0, 'encrypted-body', 2, 'tx-7')");
    db.execSQL("INSERT INTO mms_addresses (_id, mms_id, type, address, address_charset) VALUES (1, 7, 137, '+15550001', 106)");
    db.execSQL("INSERT INTO mms_addresses (_id, mms_id, type, address, address_charset) VALUES (2, 7, 151, '+15550002', 106)");
    db.execSQL("INSERT INTO part (_id, mid, seq, ct, name, cd, cl, _data, data_size, thumbnail, aspect_ratio, unique_id, digest, encrypted, pending_push) VALUES (11, 7, 0, 'image/jpeg', 'one.jpg', 'attachment', 'cid-one', '/encrypted/one.mms', 111, '/encrypted/one.thumb', 1.5, 101, X'010203', 1, 0)");
    db.execSQL("INSERT INTO part (_id, mid, seq, ct, name, cd, cl, _data, data_size, thumbnail, aspect_ratio, unique_id, digest, encrypted, pending_push) VALUES (12, 7, 1, 'audio/amr', 'two.amr', 'attachment', 'cid-two', '/encrypted/two.mms', 222, NULL, 0.0, 102, X'0405', 1, 2)");
  }

  @After public void tearDown() {
    if (helper != null) helper.close();
    if (databaseFile != null) databaseFile.delete();
  }

  @Test public void readsMmsAddressesAndMultipleAttachmentMetadataWithoutOpeningFiles() {
    MmsDatabase.MmsRecord message = new MmsDatabase(context, helper).getMessage(7);
    assertNotNull(message);
    assertEquals("encrypted-body", message.body);
    assertEquals("tx-7", message.transactionId);
    assertEquals(2, message.partCount);

    List<MmsAddressDatabase.AddressRecord> addresses = new MmsAddressDatabase(context, helper).getAddressesForMessage(7);
    assertEquals(2, addresses.size());
    assertEquals("+15550001", addresses.get(0).address);
    assertEquals(106, addresses.get(0).charset);

    List<AttachmentDatabase.AttachmentRecord> parts = new AttachmentDatabase(context, helper).getAttachmentsForMessage(7);
    assertEquals(2, parts.size());
    assertEquals("/encrypted/one.mms", parts.get(0).path);
    assertEquals("/encrypted/two.mms", parts.get(1).path);
    assertEquals(1, parts.get(0).encrypted);
    assertEquals(2, parts.get(1).transferState);
    assertArrayEquals(new byte[]{1, 2, 3}, parts.get(0).digest);
  }

  @Test public void readsOnlyAndDoesNotChangeStoredRows() {
    MmsDatabase database = new MmsDatabase(context, helper);
    assertEquals(1, database.getMessageCountForThread(9));
    assertEquals(1, database.getMessagesForThread(9).size());
    assertNull(database.getMessage(999));
    assertEquals(2, new AttachmentDatabase(context, helper).getAttachmentsForMessage(7).size());
    assertEquals(1, database.getMessageCountForThread(9));
  }

  @Test public void unifiedReadOnlyQueriesSupportMmsOnlyMixedAndCollidingIds() {
    SQLiteDatabase db = helper.getWritableDatabase();
    db.execSQL("INSERT INTO sms (_id, thread_id, date, date_sent, read, status, type, body, address, address_device_id) " +
        "VALUES (7, 9, 3000, 3000, 1, 0, 20, 'sms-7', '+15550001', 1)");
    db.execSQL("UPDATE mms SET thread_id = 9, date = 1000, date_received = 2000, msg_box = 1, " +
        "m_type = 132, body = 'mms-7', address = '+15550001', part_count = 1 WHERE _id = 7");
    db.execSQL("INSERT INTO mms (_id, thread_id, date, date_received, msg_box, read, m_type, body, address, part_count) " +
        "VALUES (8, 10, 4000, 5000, 0, 0, 130, NULL, '+15550002', 0)");

    MessageDatabase messages = new MessageDatabase(context, helper);
    assertEquals(2, messages.getUnifiedConversationCount(9));
    assertEquals(1, messages.getUnifiedConversationCount(10));

    try (android.database.Cursor cursor = messages.getUnifiedConversation(9)) {
      assertTrue(cursor.moveToNext());
      assertEquals(MessageDatabase.SMS_TRANSPORT,
          cursor.getString(cursor.getColumnIndexOrThrow(MessageDatabase.TRANSPORT)));
      assertEquals("SMS::7::3000",
          cursor.getString(cursor.getColumnIndexOrThrow(MessageColumns.UNIQUE_ROW_ID)));
      assertTrue(cursor.moveToNext());
      assertEquals(MessageDatabase.MMS_TRANSPORT,
          cursor.getString(cursor.getColumnIndexOrThrow(MessageDatabase.TRANSPORT)));
      assertEquals("MMS::7::1000",
          cursor.getString(cursor.getColumnIndexOrThrow(MessageColumns.UNIQUE_ROW_ID)));
    }

    MessageDatabase.Reader smsOnlyReader = messages.readerFor(messages.getConversation(9));
    MessageRecord smsOnly = smsOnlyReader.getNext();
    assertNotNull(smsOnly);
    assertEquals(7, smsOnly.getId());
    assertEquals(false, smsOnly.isMms());
    assertNull(smsOnlyReader.getNext());
    smsOnlyReader.close();

    MessageDatabase.Reader mixedReader = messages.readerFor(messages.getUnifiedConversation(9));
    MessageRecord first = mixedReader.getNext();
    MessageRecord second = mixedReader.getNext();
    mixedReader.close();
    assertNotNull(first);
    assertNotNull(second);
    assertEquals(7, first.getId());
    assertEquals(7, second.getId());
    assertEquals(3000, first.getDateReceived());
    assertEquals(2000, second.getDateReceived());
    assertTrue(first.isMms() != second.isMms());
    assertEquals(false, first.equals(second));

    MessageDatabase.Reader pageReader = messages.readerFor(messages.getUnifiedConversation(9, 1));
    assertNotNull(pageReader.getNext());
    assertNull(pageReader.getNext());
    pageReader.close();

    MessageDatabase.Reader mmsOnlyReader = messages.readerFor(messages.getUnifiedConversation(10));
    MessageRecord notification = mmsOnlyReader.getNext();
    assertNotNull(notification);
    assertTrue(notification.isMms());
    assertTrue(notification.isMmsNotification());
    assertNull(mmsOnlyReader.getNext());
    mmsOnlyReader.close();
  }

  private SQLiteOpenHelper helper() throws Exception {
    Class<?> helperClass = DatabaseFactory.class.getDeclaredClasses()[0];
    Constructor<?> constructor = helperClass.getDeclaredConstructor(Context.class, String.class,
            SQLiteDatabase.CursorFactory.class, int.class);
    constructor.setAccessible(true);
    return (SQLiteOpenHelper) constructor.newInstance(context, databaseFile.getPath(), null, 36);
  }

  @Test public void unifiedReaderBuildsTextOnlyAndAttachmentMmsRecordsWithCorrectDirection() {
    SQLiteDatabase db = helper.getWritableDatabase();
    MasterSecret secret = syntheticMasterSecret();
    String encryptedBody = new MasterCipher(secret).encryptBody("incoming text");
    db.execSQL("UPDATE mms SET thread_id = 9, date = 1000, date_received = 2000, " +
        "msg_box = ?, m_type = 132, body = ?, address = '+15550001', part_count = 1 WHERE _id = 7",
        new Object[]{20L | 0x80000000L, encryptedBody});
    db.execSQL("INSERT INTO mms (_id, thread_id, date, date_received, msg_box, read, m_type, body, " +
        "address, part_count, st) VALUES (8, 9, 3000, 4000, 23, 1, 128, 'outgoing text', '+15550001', 1, 8)");
    db.execSQL("INSERT INTO part (_id, mid, seq, ct, name, _data, data_size) " +
        "VALUES (31, 8, 0, 'text/plain', NULL, NULL, 14)");

    MessageDatabase messages = new MessageDatabase(context, helper);
    try (android.database.Cursor cursor = messages.getUnifiedConversation(9)) {
      MessageDatabase.Reader reader = messages.readerFor(cursor, secret);
      MessageRecord outgoing = reader.getNext();
      MessageRecord incoming = reader.getNext();

      assertNotNull(outgoing);
      assertTrue(outgoing.isMms());
      assertTrue(outgoing.isOutgoing());
      assertEquals("outgoing text", outgoing.getDisplayBody().toString());
      assertFalse(outgoing.hasAttachments());
      assertEquals(4000, outgoing.getDateReceived());

      assertNotNull(incoming);
      assertTrue(incoming.isMms());
      assertFalse(incoming.isOutgoing());
      assertEquals("incoming text", incoming.getDisplayBody().toString());
      assertTrue(incoming.hasAttachments());
      assertEquals(2000, incoming.getTimestamp());
    }
  }

  private MasterSecret syntheticMasterSecret() {
    byte[] encryption = new byte[16];
    byte[] mac = new byte[20];
    for (int i = 0; i < encryption.length; i++) encryption[i] = (byte) (i + 3);
    for (int i = 0; i < mac.length; i++) mac[i] = (byte) (i + 71);
    return new MasterSecret(new SecretKeySpec(encryption, "AES"),
            new SecretKeySpec(mac, "HmacSHA1"));
  }
}
