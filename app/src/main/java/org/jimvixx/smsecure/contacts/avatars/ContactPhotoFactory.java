package org.jimvixx.smsecure.contacts.avatars;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.ContactsContract;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;

import org.jimvixx.smsecure.R;

import java.util.concurrent.ExecutionException;

public class ContactPhotoFactory {

  private static final String TAG = ContactPhotoFactory.class.getSimpleName();

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
    Uri thumb = getContactPhotoThumbUri(context, contactUri);
    if (thumb == null) return getDefaultContactPhoto(name);

    try {
      int targetSize = context.getResources().getDimensionPixelSize(R.dimen.contact_photo_target_size);
      Bitmap bitmap = Glide.with(context)
              .asBitmap()
              .load(thumb)
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
  private static Uri getContactPhotoThumbUri(Context context, Uri contactUri) {
    String[] projection = {ContactsContract.Contacts.PHOTO_THUMBNAIL_URI};
    try (Cursor c = context.getContentResolver().query(contactUri, projection, null, null, null)) {
      if (c != null && c.moveToFirst()) {
        String s = c.getString(0);
        return (s != null) ? Uri.parse(s) : null;
      }
    } catch (Exception ignored) {
    }
    return null;
  }
}
