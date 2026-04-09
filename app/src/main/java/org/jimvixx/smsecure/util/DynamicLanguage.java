/*
 * Copyright (C) 2013 Open Whisper Systems
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
import android.app.Service;
import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import java.util.Locale;

public class DynamicLanguage {

  private static final String DEFAULT = "zz";

  @NonNull
  public static LocaleListCompat getSelectedLocales(@NonNull Context context) {
    String pref = SMSecurePreferences.getLanguage(context);

    if (TextUtils.isEmpty(pref) || DEFAULT.equals(pref)) {
      return LocaleListCompat.getEmptyLocaleList();
    }

    String languageTag = pref.replace("-r", "-");
    return LocaleListCompat.forLanguageTags(languageTag);
  }

  public static int getLayoutDirection(@NonNull Context context) {
    return context.getResources().getConfiguration().getLayoutDirection();
  }

  private static void applyIfNeeded(@NonNull Context context) {
    LocaleListCompat selected = getSelectedLocales(context);
    LocaleListCompat applied = AppCompatDelegate.getApplicationLocales();

    if (!TextUtils.equals(applied.toLanguageTags(), selected.toLanguageTags())) {
      AppCompatDelegate.setApplicationLocales(selected);
    }
  }

  public void onCreate(@NonNull Activity activity) {
    applyIfNeeded(activity);
  }

  public void onResume(@NonNull Activity activity) {
    applyIfNeeded(activity);
  }

  public void updateServiceLocale(@NonNull Service service) {
    applyIfNeeded(service);
  }

  @NonNull
  public Locale getCurrentLocale() {
    LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();

    if (!locales.isEmpty()) {
      Locale locale = locales.get(0);
      if (locale != null) return locale;
    }

    return Locale.getDefault();
  }
}