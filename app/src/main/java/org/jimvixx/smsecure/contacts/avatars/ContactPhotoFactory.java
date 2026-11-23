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

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.ContactsContract;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.signature.ObjectKey;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.util.VisibleForTesting;

import java.util.concurrent.ExecutionException;

public class ContactPhotoFactory {

  private static final String TAG = ContactPhotoFactory.class.getSimpleName();
  private static final String[] CONTACT_PHOTO_PROJECTION = {
          ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
          ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP
  };
  private static final String[] LEGACY_CONTACT_PHOTO_PROJECTION = {
          ContactsContract.Contacts.PHOTO_THUMBNAIL_URI
  };

  public static ContactPhoto getLoadingPhoto() {
    return new TransparentContactPhoto();
  }

  public static ContactPhoto getDefaultContactPhoto(@Nullable String name) {
    if (!TextUtils.isEmpty(name)) return new GeneratedContactPhoto(name);
    else return new GeneratedContactPhoto("#");
  }

  public static ContactPhoto getDefaultGroupPhoto() {
    return new ResourceContactPhoto(R.drawable.ic_account_multiple);
  }

  public static ContactPhoto getContactPhoto(Context context, Uri contactUri, String name) {
    ContactPhotoData photoData = getContactPhotoData(context, contactUri);
    if (photoData == null || photoData.thumbnailUri == null) return getDefaultContactPhoto(name);

    try {
      int targetSize = context.getResources().getDimensionPixelSize(R.dimen.contact_photo_target_size);
      Bitmap bitmap = Glide.with(context)
              .asBitmap()
              .load(photoData.thumbnailUri)
              .signature(new ObjectKey(getContactPhotoCacheKey(photoData.thumbnailUri,
                      photoData.lastUpdatedTimestamp)))
              .centerCrop()
              .submit(targetSize, targetSize)
              .get();
      return new BitmapContactPhoto(bitmap);
    } catch (ExecutionException e) {
      return getDefaultContactPhoto(name);
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  @Nullable
  private static ContactPhotoData getContactPhotoData(Context context, Uri contactUri) {
    try {
      return queryContactPhotoData(context, contactUri, CONTACT_PHOTO_PROJECTION, true);
    } catch (IllegalArgumentException e) {
      // Some contact providers may not expose the timestamp column even on a supported API.
      try {
        return queryContactPhotoData(context, contactUri, LEGACY_CONTACT_PHOTO_PROJECTION, false);
      } catch (Exception ignored) {
        return null;
      }
    } catch (Exception ignored) {
      return null;
    }
  }

  @Nullable
  private static ContactPhotoData queryContactPhotoData(Context context,
                                                        Uri contactUri,
                                                        String[] projection,
                                                        boolean includesLastUpdatedTimestamp) {
    try (Cursor c = context.getContentResolver().query(contactUri, projection, null, null, null)) {
      if (c == null || !c.moveToFirst()) return null;

      String thumbnail = c.getString(0);
      String lastUpdatedTimestamp = includesLastUpdatedTimestamp ? c.getString(1) : null;

      return new ContactPhotoData(thumbnail == null ? null : Uri.parse(thumbnail),
              lastUpdatedTimestamp);
    }
  }

  @VisibleForTesting
  static String getContactPhotoCacheKey(Uri thumbnailUri, @Nullable String lastUpdatedTimestamp) {
    return thumbnailUri + "|" + (lastUpdatedTimestamp == null ? "unknown" : lastUpdatedTimestamp);
  }

  private static class ContactPhotoData {
    @Nullable
    private final Uri thumbnailUri;
    @Nullable
    private final String lastUpdatedTimestamp;

    private ContactPhotoData(@Nullable Uri thumbnailUri, @Nullable String lastUpdatedTimestamp) {
      this.thumbnailUri = thumbnailUri;
      this.lastUpdatedTimestamp = lastUpdatedTimestamp;
    }
  }
}
