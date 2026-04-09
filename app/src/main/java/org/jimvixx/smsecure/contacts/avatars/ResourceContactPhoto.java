package org.jimvixx.smsecure.contacts.avatars;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.widget.ImageView;

import androidx.annotation.DrawableRes;
import androidx.core.content.ContextCompat;

import com.makeramen.roundedimageview.RoundedDrawable;

public class ResourceContactPhoto implements ContactPhoto {

  private final int resourceId;

  ResourceContactPhoto(@DrawableRes int resourceId) {
    this.resourceId = resourceId;
  }

  @Override
  public Drawable asDrawable(Context context, int color) {
    return asDrawable(context, color, false);
  }

  @Override
  public Drawable asDrawable(Context context, int color, boolean inverted) {
    int bgColor = inverted ? Color.WHITE : color;
    int textColor = inverted ? color : Color.WHITE;

    AvatarDrawable background = new AvatarDrawable(" ", bgColor, textColor);

    Drawable raw = ContextCompat.getDrawable(context, resourceId);
    if (raw == null) {
      return background;
    }

    RoundedDrawable foreground = (RoundedDrawable) RoundedDrawable.fromDrawable(raw);
    foreground.setScaleType(ImageView.ScaleType.CENTER);

    if (inverted) {
      foreground.setColorFilter(color, PorterDuff.Mode.SRC_ATOP);
    }

    return new ExpandingLayerDrawable(new Drawable[]{background, foreground});
  }

  private static class ExpandingLayerDrawable extends LayerDrawable {
    public ExpandingLayerDrawable(Drawable[] layers) {
      super(layers);
    }

    @Override
    public int getIntrinsicWidth() {
      return -1;
    }

    @Override
    public int getIntrinsicHeight() {
      return -1;
    }
  }
}
