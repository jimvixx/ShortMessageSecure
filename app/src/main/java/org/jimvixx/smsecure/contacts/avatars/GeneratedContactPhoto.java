package org.jimvixx.smsecure.contacts.avatars;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

import org.jimvixx.smsecure.R;

public class GeneratedContactPhoto implements ContactPhoto {

  private final String name;

  GeneratedContactPhoto(@NonNull String name) {
    this.name = name;
  }

  @Override
  public Drawable asDrawable(Context context, int color) {
    return asDrawable(context, color, false);
  }

  @Override
  public Drawable asDrawable(Context context, int color, boolean inverted) {
    int targetSize = context.getResources().getDimensionPixelSize(R.dimen.contact_photo_target_size);

    int bgColor = inverted ? Color.WHITE : color;
    int textColor = inverted ? color : Color.WHITE;

    AvatarDrawable drawable = new AvatarDrawable(getCharacter(name), bgColor, textColor);
    drawable.setBounds(0, 0, targetSize, targetSize);
    return drawable;
  }

  private String getCharacter(String name) {
    String cleanedName = name.replaceFirst("[^\\p{L}\\p{Nd}\\p{P}\\p{S}]+", "");

    if (cleanedName.isEmpty()) {
      return "#";
    } else {
      return String.valueOf(cleanedName.charAt(0));
    }
  }
}
