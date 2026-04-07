/**
 * Copyright (C) 2015 Open Whisper Systems
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.jimvixx.smsecure.util;

import android.content.Context;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;

import androidx.annotation.AttrRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;

public class ResUtil {

  public static int getColor(@NonNull Context context, @AttrRes int attr) {
    Theme theme = context.getTheme();

    // 1) Resolve attribute directly
    TypedValue tv = new TypedValue();
    if (theme.resolveAttribute(attr, tv, true)) {
      // Direct color int
      if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
        return tv.data;
      }

      // Reference to a color resource
      if (tv.resourceId != 0) {
        try {
          return ContextCompat.getColor(context, tv.resourceId);
        } catch (Exception ignored) {
        }
      }
    }

    // 2) Fallback
    final TypedArray ta = context.obtainStyledAttributes(new int[]{attr});
    try {
      return ta.getColor(0, Color.MAGENTA);
    } finally {
      ta.recycle();
    }
  }

  public static int getDrawableRes(Context c, @AttrRes int attr) {
    return getDrawableRes(c.getTheme(), attr);
  }

  public static int getDrawableRes(Theme theme, @AttrRes int attr) {
    final TypedValue out = new TypedValue();
    theme.resolveAttribute(attr, out, true);
    return out.resourceId;
  }

  @Nullable
  public static Drawable getDrawable(@NonNull Context c, @AttrRes int attr) {
    int resId = getDrawableRes(c, attr);
    if (resId == 0) return null;
    return AppCompatResources.getDrawable(c, resId);
  }
}
