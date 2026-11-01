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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.database.Cursor;

import org.junit.Test;

public class SmsDatabaseTest {

  @Test
  public void readsServiceCenterFromSmsCursor() {
    Cursor cursor = mock(Cursor.class);
    when(cursor.getColumnIndex(SmsDatabase.SERVICE_CENTER)).thenReturn(3);
    when(cursor.getString(anyInt())).thenReturn("+15551234567");

    assertEquals("+15551234567", SmsDatabase.getServiceCenter(cursor));
  }

  @Test
  public void preservesSqlNullFromSmsCursor() {
    Cursor cursor = mock(Cursor.class);
    when(cursor.getColumnIndex(SmsDatabase.SERVICE_CENTER)).thenReturn(3);
    when(cursor.getString(3)).thenReturn(null);

    assertNull(SmsDatabase.getServiceCenter(cursor));
  }

  @Test
  public void treatsCompositeCursorWithoutServiceCenterAsNull() {
    Cursor cursor = mock(Cursor.class);
    when(cursor.getColumnIndex(SmsDatabase.SERVICE_CENTER)).thenReturn(-1);

    assertNull(SmsDatabase.getServiceCenter(cursor));
  }
}
