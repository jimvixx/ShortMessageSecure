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

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

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

  private SQLiteOpenHelper helper() throws Exception {
    Class<?> helperClass = DatabaseFactory.class.getDeclaredClasses()[0];
    Constructor<?> constructor = helperClass.getDeclaredConstructor(Context.class, String.class,
            SQLiteDatabase.CursorFactory.class, int.class);
    constructor.setAccessible(true);
    return (SQLiteOpenHelper) constructor.newInstance(context, databaseFile.getPath(), null, 36);
  }
}
