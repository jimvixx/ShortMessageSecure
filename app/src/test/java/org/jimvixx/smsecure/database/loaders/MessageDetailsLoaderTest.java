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

package org.jimvixx.smsecure.database.loaders;

import android.database.Cursor;

import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.EncryptingSmsDatabase;
import org.jimvixx.smsecure.database.MessageDatabase;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class MessageDetailsLoaderTest {
  @Test
  public void smsTypeStillQueriesMessage() throws Exception {
    MessageDetailsLoader loader = mock(MessageDetailsLoader.class, CALLS_REAL_METHODS);
    Field field = MessageDetailsLoader.class.getDeclaredField("type");
    field.setAccessible(true);
    field.set(loader, MessageDatabase.SMS_TRANSPORT);
    EncryptingSmsDatabase sms = mock(EncryptingSmsDatabase.class);
    Cursor cursor = mock(Cursor.class);
    try (MockedStatic<DatabaseFactory> database = mockStatic(DatabaseFactory.class)) {
      database.when(() -> DatabaseFactory.getEncryptingSmsDatabase(null)).thenReturn(sms);
      when(sms.getMessage(0)).thenReturn(cursor);
      assertSame(cursor, loader.getCursor());
    }
  }

  @Test
  public void unsupportedTypesReturnNoDataWithoutQueryingDatabase() throws Exception {
    // Bypass Android loader construction; exercise the real background-loading path.
    try (MockedStatic<DatabaseFactory> database = mockStatic(DatabaseFactory.class)) {
      for (String type : new String[]{null, "", "mms", "unknown"}) {
        MessageDetailsLoader loader = mock(MessageDetailsLoader.class, CALLS_REAL_METHODS);
        Field field = MessageDetailsLoader.class.getDeclaredField("type");
        field.setAccessible(true);
        field.set(loader, type);
        assertNull(loader.loadInBackground());
      }
      database.verifyNoInteractions();
    }
  }
}
