package org.jimvixx.smsecure.recipients;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.Context;

import org.jimvixx.smsecure.color.MaterialColor;
import org.jimvixx.smsecure.contacts.avatars.ContactPhoto;
import org.jimvixx.smsecure.contacts.avatars.ContactPhotoFactory;
import org.jimvixx.smsecure.util.Util;
import org.junit.Test;
import org.mockito.MockedStatic;

public class RecipientProviderTest {

  @Test
  public void missingContactsPermissionSkipsContactProviderQuery() {
    Context context = mock(Context.class);
    ContactPhoto fallbackPhoto = mock(ContactPhoto.class);
    MaterialColor fallbackColor = mock(MaterialColor.class);

    try (MockedStatic<ContactPhotoFactory> photoFactory = mockStatic(ContactPhotoFactory.class);
         MockedStatic<Util> util = mockStatic(Util.class)) {
      photoFactory.when(() -> ContactPhotoFactory.getDefaultContactPhoto(null))
              .thenReturn(fallbackPhoto);
      util.when(() -> Util.missingContactsPermissions(context)).thenReturn(true);

      RecipientProvider.RecipientDetails details =
              new RecipientProvider().resolveIndividualRecipientDetails(context, "+15551234567", fallbackColor);

      verify(context, never()).getContentResolver();
      assertNull(details.name);
      assertEquals("+15551234567", details.number);
      assertNull(details.contactUri);
      assertSame(fallbackPhoto, details.avatar);
      assertSame(fallbackColor, details.color);
    }
  }
}
