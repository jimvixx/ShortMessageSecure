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

package org.whispersystems.jobqueue.persistence;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.whispersystems.jobqueue.Job;
import org.whispersystems.jobqueue.dependencies.AggregateDependencyInjector;
import org.whispersystems.jobqueue.logging.Log;
import java.io.IOException;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class PersistentStorageTest {
  @Test public void encryptedUnreadablePayloadStaysEncrypted() throws Exception {
    Fixture f = new Fixture();
    when(f.cursor.getInt(2)).thenReturn(1);
    org.whispersystems.jobqueue.EncryptionKeys keys = mock(org.whispersystems.jobqueue.EncryptionKeys.class);
    when(f.serializer.deserialize(keys, true, "original")).thenThrow(new IOException());
    try (MockedConstruction<ContentValues> values = mockConstruction(ContentValues.class);
         MockedStatic<Log> logs = mockStatic(Log.class)) {
      assertTrue(f.storage.getAllEncrypted(keys).isEmpty());
      verify(values.constructed().get(0)).put("item", "original");
      verify(values.constructed().get(0)).put("encrypted", true);
    }
  }

  @Test public void unreadablePayloadIsPreservedBeforeRemoval() throws Exception {
    Fixture f = new Fixture();
    when(f.serializer.deserialize(null, false, "original")).thenThrow(new IOException("old class"));
    try (MockedConstruction<ContentValues> values = mockConstruction(ContentValues.class);
         MockedStatic<Log> logs = mockStatic(Log.class)) {
      assertTrue(f.storage.getAllUnencrypted().isEmpty());
      ContentValues saved = values.constructed().get(0);
      verify(saved).put("item", "original");
      verify(saved).put("encrypted", false);
      InOrder order = inOrder(f.db);
      order.verify(f.db).beginTransaction();
      order.verify(f.db).insertOrThrow(eq("queue_unreadable"), isNull(), same(saved));
      order.verify(f.db).delete(eq("queue"), eq("_id = ?"), aryEq("42"));
      order.verify(f.db).setTransactionSuccessful();
      order.verify(f.db).endTransaction();
    }
  }

  @Test public void failedQuarantineDoesNotDeleteOriginal() throws Exception {
    Fixture f = new Fixture();
    when(f.serializer.deserialize(null, false, "original")).thenThrow(new IOException());
    when(f.db.insertOrThrow(eq("queue_unreadable"), isNull(), any())).thenThrow(new IllegalStateException("disk"));
    try (MockedConstruction<ContentValues> values = mockConstruction(ContentValues.class)) {
      assertThrows(IllegalStateException.class, () -> f.storage.getAllUnencrypted());
      verify(f.db, never()).delete(anyString(), anyString(), any());
      verify(f.db, never()).setTransactionSuccessful();
      verify(f.db).endTransaction();
    }
  }

  @Test public void readableLegacyJobIsRewrittenUnderSameIdWithoutExecution() throws Exception {
    Fixture f = new Fixture();
    Job job = mock(Job.class);
    when(f.serializer.deserialize(null, false, "original")).thenReturn(job);
    when(f.serializer.serialize(job)).thenReturn("stable");
    try (MockedConstruction<ContentValues> values = mockConstruction(ContentValues.class)) {
      assertSame(job, f.storage.getAllUnencrypted().get(0));
      verify(values.constructed().get(0)).put("item", "stable");
      verify(job).setPersistentId(42);
      verify(f.db).update(eq("queue"), any(), eq("_id = ?"), aryEq("42"));
      verify(f.db, never()).delete(anyString(), anyString(), any());
      verify(job, never()).onRun();
    }
  }

  private static String[] aryEq(String value) {
    return org.mockito.AdditionalMatchers.aryEq(new String[]{value});
  }

  private static class Fixture {
    final SQLiteDatabase db = mock(SQLiteDatabase.class);
    final JobSerializer serializer = mock(JobSerializer.class);
    final Cursor cursor = mock(Cursor.class);
    final PersistentStorage storage;
    Fixture() {
      SQLiteOpenHelper helper = mock(SQLiteOpenHelper.class);
      when(helper.getWritableDatabase()).thenReturn(db);
      when(db.query(eq("queue"), isNull(), anyString(), isNull(), isNull(), isNull(), eq("_id ASC"), isNull())).thenReturn(cursor);
      when(cursor.moveToNext()).thenReturn(true, false);
      when(cursor.getColumnIndexOrThrow("_id")).thenReturn(0);
      when(cursor.getColumnIndexOrThrow("item")).thenReturn(1);
      when(cursor.getColumnIndexOrThrow("encrypted")).thenReturn(2);
      when(cursor.getLong(0)).thenReturn(42L);
      when(cursor.getString(1)).thenReturn("original");
      storage = new PersistentStorage(mock(Context.class), helper, serializer, mock(AggregateDependencyInjector.class));
    }
  }
}
