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

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;

import org.junit.Test;
import org.mockito.MockedConstruction;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class RecipientPreferenceDatabaseTest {

  @Test public void recoveryClearsEveryRecipientUsingTheAmbiguousSubscription() {
    Context context = mock(Context.class);
    ContentResolver resolver = mock(ContentResolver.class);
    SQLiteOpenHelper helper = mock(SQLiteOpenHelper.class);
    SQLiteDatabase sqlite = mock(SQLiteDatabase.class);
    when(context.getContentResolver()).thenReturn(resolver);
    when(helper.getWritableDatabase()).thenReturn(sqlite);
    when(sqlite.update(eq("recipient_preferences"), any(ContentValues.class),
            eq("default_subscription_id = ?"),
            org.mockito.AdditionalMatchers.aryEq(new String[]{"7"}))).thenReturn(3);

    Uri preferenceUri = mock(Uri.class);
    try (org.mockito.MockedStatic<Uri> uris = mockStatic(Uri.class);
         MockedConstruction<ContentValues> values = mockConstruction(ContentValues.class)) {
      uris.when(() -> Uri.parse(anyString())).thenReturn(preferenceUri);
      RecipientPreferenceDatabase database = new RecipientPreferenceDatabase(context, helper);

      assertEquals(3, database.clearDefaultSubscriptionId(7));
      verify(values.constructed().get(0)).put("default_subscription_id", -1);
      verify(resolver).notifyChange(any(Uri.class), isNull());
    }
  }
}
