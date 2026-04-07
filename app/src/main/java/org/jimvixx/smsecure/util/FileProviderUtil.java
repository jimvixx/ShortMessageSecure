package org.jimvixx.smsecure.util;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import java.io.File;

public class FileProviderUtil {

  private static final String AUTHORITY = "org.jimvixx.smsecure.fileprovider";

  public static Uri getUriFor(@NonNull Context context, @NonNull File file) {
    return FileProvider.getUriForFile(context, AUTHORITY, file);
  }

}
