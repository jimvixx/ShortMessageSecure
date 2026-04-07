/*
 * Copyright (C) 2011 Open Whisper Systems
 * Copyright (C) 2025 Jimvixx
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

package org.jimvixx.smsecure.util;

import android.content.Context;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import androidx.annotation.Nullable;

import org.jimvixx.smsecure.providers.PersistentBlobProvider;

import java.util.Locale;

public final class MediaUtil {

  public static final String IMAGE_JPEG = "image/jpeg";

  private MediaUtil() {
    throw new AssertionError("No instances.");
  }

  public static @Nullable String getMimeType(Context context, Uri uri) {
    if (uri == null) {
      return null;
    }

    if (PersistentBlobProvider.isAuthority(context, uri)) {
      return PersistentBlobProvider.getMimeType(context, uri);
    }

    String type = context.getContentResolver().getType(uri);

    if (type == null) {
      String extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
      if (extension != null) {
        type = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(extension.toLowerCase(Locale.ROOT));
      }
    }

    return getCorrectedMimeType(type);
  }

  public static @Nullable String getCorrectedMimeType(@Nullable String mimeType) {
    if (mimeType == null) {
      return null;
    }

    if ("image/jpg".equals(mimeType)) {
      return MimeTypeMap.getSingleton().hasMimeType(IMAGE_JPEG)
              ? IMAGE_JPEG
              : mimeType;
    }

    return mimeType;
  }
}