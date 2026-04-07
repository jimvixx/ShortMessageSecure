/*
 * Copyright (C) 2011 Whisper Systems
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
import android.content.res.TypedArray;
import android.graphics.Color;
import android.util.TypedValue;

import androidx.annotation.AttrRes;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

public final class ThemeUtil {

  @SuppressWarnings("unused")
  private static final String TAG = ThemeUtil.class.getSimpleName();

  private ThemeUtil() {}

  /**
   * Resolve a theme color attribute to a ColorInt.
   */
  @ColorInt
  public static int resolveThemeColor(@NonNull Context context, @AttrRes int attr) {
    TypedValue tv = new TypedValue();
    if (context.getTheme().resolveAttribute(attr, tv, true)) {
      if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
              && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
        return tv.data;
      }
    }

    // Fallback for ColorStateList / reference
    TypedArray ta = context.obtainStyledAttributes(new int[]{ attr });
    int color = ta.getColor(0, Color.TRANSPARENT);
    ta.recycle();
    return color;
  }

  @ColorInt
  public static int resolveThemeColor(@NonNull Context context,
                                      @AttrRes int attr,
                                      @ColorInt int fallback) {
    TypedValue tv = new TypedValue();
    if (!context.getTheme().resolveAttribute(attr, tv, true)) {
      return fallback;
    }

    return colorFromTypedValue(context,tv);
  }

  @ColorInt
  private static int colorFromTypedValue(@NonNull Context context, @NonNull TypedValue tv) {
    if (tv.resourceId != 0) {
      return ContextCompat.getColor(context, tv.resourceId);
    } else {
      // If it is a literal color in the theme.
      return tv.data;
    }
  }
}
