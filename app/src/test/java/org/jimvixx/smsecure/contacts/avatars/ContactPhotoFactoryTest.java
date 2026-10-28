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

package org.jimvixx.smsecure.contacts.avatars;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.text.TextUtils;

import org.junit.Test;
import org.mockito.MockedStatic;

public class ContactPhotoFactoryTest {

  @Test
  public void contactTimestampInvalidatesGlideCacheKey() {
    Uri thumbnailUri = mock(Uri.class);
    when(thumbnailUri.toString()).thenReturn("content://contacts/photo/42");

    String firstKey = ContactPhotoFactory.getContactPhotoCacheKey(thumbnailUri, "100");
    String updatedKey = ContactPhotoFactory.getContactPhotoCacheKey(thumbnailUri, "101");

    assertNotEquals(firstKey, updatedKey);
    assertEquals(firstKey, ContactPhotoFactory.getContactPhotoCacheKey(thumbnailUri, "100"));
  }

  @Test
  public void contactPhotoLookupRequestsLastUpdatedTimestamp() {
    Context context = mock(Context.class);
    ContentResolver resolver = mock(ContentResolver.class);
    Cursor cursor = mock(Cursor.class);
    Uri contactUri = mock(Uri.class);

    when(context.getContentResolver()).thenReturn(resolver);
    when(resolver.query(eq(contactUri), any(String[].class), isNull(), isNull(), isNull()))
            .thenReturn(cursor);
    when(cursor.moveToFirst()).thenReturn(true);
    when(cursor.getString(0)).thenReturn(null);
    when(cursor.getString(1)).thenReturn("100");

    try (MockedStatic<TextUtils> textUtils = mockStatic(TextUtils.class)) {
      ContactPhoto photo = ContactPhotoFactory.getContactPhoto(context, contactUri, "Alice");

      assertTrue(photo instanceof GeneratedContactPhoto);
      verify(resolver).query(eq(contactUri),
              org.mockito.ArgumentMatchers.argThat(projection ->
                      java.util.Arrays.equals(projection, new String[]{
                              ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
                              ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP
                      })),
              isNull(), isNull(), isNull());
    }
  }

  @Test
  public void providerWithoutTimestampColumnStillUsesFallback() {
    Context context = mock(Context.class);
    ContentResolver resolver = mock(ContentResolver.class);
    Cursor cursor = mock(Cursor.class);
    Uri contactUri = mock(Uri.class);

    when(context.getContentResolver()).thenReturn(resolver);
    when(resolver.query(eq(contactUri), any(String[].class), isNull(), isNull(), isNull()))
            .thenThrow(new IllegalArgumentException("unsupported column"))
            .thenReturn(cursor);
    when(cursor.moveToFirst()).thenReturn(true);
    when(cursor.getString(0)).thenReturn(null);

    try (MockedStatic<TextUtils> textUtils = mockStatic(TextUtils.class)) {
      ContactPhoto photo = ContactPhotoFactory.getContactPhoto(context, contactUri, "Alice");

      assertTrue(photo instanceof GeneratedContactPhoto);
      Uri thumbnailUri = mock(Uri.class);
      when(thumbnailUri.toString()).thenReturn("content://contacts/photo/42");
      assertEquals("content://contacts/photo/42|unknown",
              ContactPhotoFactory.getContactPhotoCacheKey(thumbnailUri, null));
      verify(resolver, times(2)).query(eq(contactUri), any(String[].class),
              isNull(), isNull(), isNull());
    }
  }
}
