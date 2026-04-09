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

import android.app.Activity;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;

public class DynamicTheme {

  public static final String DARK = "dark";
  public static final String LIGHT = "light";

  @AppCompatDelegate.NightMode
  private int currentNightMode = AppCompatDelegate.MODE_NIGHT_UNSPECIFIED;

  public static boolean isNightMode(@NonNull Context context) {
    int mask = context.getResources().getConfiguration().uiMode
            & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
    return mask == android.content.res.Configuration.UI_MODE_NIGHT_YES;
  }

  @AppCompatDelegate.NightMode
  public static int resolveNightMode(@NonNull Context context) {
    String theme = SMSecurePreferences.getTheme(context);

    return switch (theme) {
      case DARK -> AppCompatDelegate.MODE_NIGHT_YES;
      case LIGHT -> AppCompatDelegate.MODE_NIGHT_NO;
      default -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    };

  }

  public void onCreate(@NonNull Activity activity) {
    currentNightMode = getSelectedNightMode(activity);

    if (AppCompatDelegate.getDefaultNightMode() != currentNightMode) {
      AppCompatDelegate.setDefaultNightMode(currentNightMode);
    }
  }

  public void onResume(@NonNull Activity activity) {
    @AppCompatDelegate.NightMode int selected = getSelectedNightMode(activity);

    if (selected != currentNightMode) {
      currentNightMode = selected;
      AppCompatDelegate.setDefaultNightMode(currentNightMode);
      OverridePendingTransition.invoke(activity);
      activity.recreate();
      OverridePendingTransition.invoke(activity);
    }
  }

  @AppCompatDelegate.NightMode
  protected int getSelectedNightMode(@NonNull Activity activity) {
    String theme = SMSecurePreferences.getTheme(activity);

    return switch (theme) {
      case DARK -> AppCompatDelegate.MODE_NIGHT_YES;
      case LIGHT -> AppCompatDelegate.MODE_NIGHT_NO;
      default -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    };

  }

  private static final class OverridePendingTransition {
    static void invoke(@NonNull Activity activity) {
      activity.overridePendingTransition(0, 0);
    }
  }
}
