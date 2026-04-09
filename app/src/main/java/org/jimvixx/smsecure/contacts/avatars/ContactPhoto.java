package org.jimvixx.smsecure.contacts.avatars;

import android.content.Context;
import android.graphics.drawable.Drawable;

public interface ContactPhoto {

  Drawable asDrawable(Context context, int color);

  Drawable asDrawable(Context context, int color, boolean inverted);


}
