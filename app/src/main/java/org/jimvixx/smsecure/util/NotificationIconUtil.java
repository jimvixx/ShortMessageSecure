/*
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

import static org.jimvixx.smsecure.util.ViewUtil.dpToPx;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.drawable.DrawableCompat;

/**
 * Helpers for notification icons.
 *
 * Notes:
 * - NotificationCompat.Builder#setLargeIcon() requires a Bitmap.
 * - Many of our assets are VectorDrawables; BitmapFactory.decodeResource() is not suitable for them
 *   when tinting / theming is needed.
 * - This util converts a drawable into a tinted Bitmap and caches results.
 */
public final class NotificationIconUtil {

  // A small cache is enough: typical app has only a handful of notification icons.
  private static final int CACHE_SIZE_BYTES = 512 * 1024; // 512 KB

  private static final LruCache<String, Bitmap> BITMAP_CACHE =
          new LruCache<>(CACHE_SIZE_BYTES) {
            @Override
            protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
              return value.getByteCount();
            }
          };

  private NotificationIconUtil() {}

  /**
   * Returns a Bitmap suitable for Notification largeIcon with optional tint.
   *
   * @param context     Context
   * @param drawableRes Drawable resource (vector or bitmap)
   * @param sizeDp      Output size in dp (48dp is a good default for large icon)
   * @param tintColor   If null, no tint will be applied
   */
  @Nullable
  public static Bitmap getLargeIcon(@NonNull Context context,
                                    @DrawableRes int drawableRes,
                                    int sizeDp,
                                    @Nullable @ColorInt Integer tintColor) {

    final int sizePx = dpToPx(context.getResources(), sizeDp);
    final String cacheKey = drawableRes + "|" + sizePx + "|" + (tintColor == null ? "none" : tintColor);

    Bitmap cached = BITMAP_CACHE.get(cacheKey);
    if (cached != null && !cached.isRecycled()) {
      return cached;
    }

    Drawable drawable = AppCompatResources.getDrawable(context, drawableRes);
    if (drawable == null) return null;

    drawable = drawable.mutate();

    if (tintColor != null) {
      DrawableCompat.setTint(drawable, tintColor);
    }

    drawable.setBounds(0, 0, sizePx, sizePx);

    Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    drawable.draw(canvas);

    BITMAP_CACHE.put(cacheKey, bitmap);
    return bitmap;
  }

  /**
   * Convenience overload: no tint.
   */
  @Nullable
  public static Bitmap getLargeIcon(@NonNull Context context,
                                    @DrawableRes int drawableRes,
                                    int sizeDp) {
    return getLargeIcon(context, drawableRes, sizeDp, null);
  }

  /**
   * Clears cached bitmaps. Usually not needed, but handy for tests or theme switches.
   */
  public static void clearCache() {
    BITMAP_CACHE.evictAll();
  }
}
