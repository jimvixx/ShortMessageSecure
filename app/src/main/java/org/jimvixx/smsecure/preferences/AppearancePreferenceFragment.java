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

package org.jimvixx.smsecure.preferences;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import org.jimvixx.smsecure.ApplicationPreferencesActivity;
import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.util.SMSecurePreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AppearancePreferenceFragment extends PreferenceFragmentCompat {

  private static final String LANGUAGE_SEPARATOR = "\\|";
  private static final String KEY_LANGUAGE = "pref_language";

  public static CharSequence getSummary(@NonNull Context context) {
    LanguageListData languageData = parseLanguageList(context);

    String[] themeEntries = context.getResources().getStringArray(R.array.pref_theme_entries);
    String[] themeEntryValues = context.getResources().getStringArray(R.array.pref_theme_values);

    int langIndex = Math.max(0,
            Arrays.asList(languageData.values).indexOf(SMSecurePreferences.getLanguage(context)));

    int themeIndex = Math.max(0,
            Arrays.asList(themeEntryValues).indexOf(SMSecurePreferences.getTheme(context)));

    if (langIndex >= languageData.entries.length) langIndex = 0;
    if (themeIndex >= themeEntries.length) themeIndex = 0;

    return context.getString(
            R.string.ApplicationPreferencesActivity_appearance_summary,
            themeEntries[themeIndex],
            languageData.entries[langIndex]
    );
  }

  @NonNull
  private static LanguageListData parseLanguageList(@NonNull Context context) {
    String[] rawItems = context.getResources().getStringArray(R.array.language_items);

    List<CharSequence> entries = new ArrayList<>(rawItems.length);
    List<CharSequence> values = new ArrayList<>(rawItems.length);

    for (String rawItem : rawItems) {
      if (rawItem == null) continue;

      String[] parts = rawItem.split(LANGUAGE_SEPARATOR, 2);

      if (parts.length != 2) {
        continue;
      }

      String value = parts[0].trim();
      String entry = parts[1].trim();

      if (value.isEmpty() || entry.isEmpty()) {
        continue;
      }

      values.add(value);
      entries.add(entry);
    }

    if (entries.isEmpty()) {
      values.add("zz");
      entries.add(context.getString(R.string.Default));
    }

    return new LanguageListData(
            entries.toArray(new CharSequence[0]),
            values.toArray(new CharSequence[0])
    );
  }

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
    setPreferencesFromResource(R.xml.preferences_appearance, rootKey);
    initLanguagePreference();
  }

  @Override
  public void onStart() {
    super.onStart();

    SharedPreferences.OnSharedPreferenceChangeListener hostListener = getHostPreferenceListener();
    if (hostListener == null) return;

    PreferenceScreen screen = getPreferenceScreen();
    if (screen == null) return;

    SharedPreferences sp = screen.getSharedPreferences();
    if (sp == null) return;

    sp.registerOnSharedPreferenceChangeListener(hostListener);
  }

  @Override
  public void onResume() {
    super.onResume();

    ApplicationPreferencesActivity activity = getHostActivity();
    if (activity == null) return;

    ActionBar bar = activity.getSupportActionBar();
    if (bar != null) {
      bar.setTitle(R.string.preferences__appearance);
    }
  }

  @Override
  public void onStop() {
    SharedPreferences.OnSharedPreferenceChangeListener hostListener = getHostPreferenceListener();
    if (hostListener != null) {
      PreferenceScreen screen = getPreferenceScreen();
      if (screen != null) {
        SharedPreferences sp = screen.getSharedPreferences();
        if (sp != null) {
          sp.unregisterOnSharedPreferenceChangeListener(hostListener);
        }
      }
    }

    super.onStop();
  }

  private void initLanguagePreference() {
    ListPreference languagePreference = findPreference(KEY_LANGUAGE);
    if (languagePreference == null) return;

    LanguageListData data = parseLanguageList(requireContext());

    languagePreference.setEntries(data.entries);
    languagePreference.setEntryValues(data.values);

    languagePreference.setOnPreferenceChangeListener((preference, newValue) -> {
      String language = (String) newValue;

      Context context = requireContext();
      ApplicationPreferencesActivity activity = getHostActivity();
      if (activity == null) return false;

      SMSecurePreferences.setLanguage(context, language);

      androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
              org.jimvixx.smsecure.util.DynamicLanguage.getSelectedLocales(context)
      );

      Intent intent = new Intent(activity, ApplicationPreferencesActivity.class);
      intent.putExtra(ApplicationPreferencesActivity.EXTRA_START_CATEGORY,
              ApplicationPreferencesActivity.PREFERENCE_CATEGORY_APPEARANCE);
      intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

      activity.startActivity(intent);
      activity.overridePendingTransition(0, 0);
      activity.finish();
      activity.overridePendingTransition(0, 0);

      return true;
    });
  }

  @Nullable
  private ApplicationPreferencesActivity getHostActivity() {
    return (getActivity() instanceof ApplicationPreferencesActivity)
            ? (ApplicationPreferencesActivity) getActivity()
            : null;
  }

  @Nullable
  private SharedPreferences.OnSharedPreferenceChangeListener getHostPreferenceListener() {
    return (getActivity() instanceof SharedPreferences.OnSharedPreferenceChangeListener)
            ? (SharedPreferences.OnSharedPreferenceChangeListener) getActivity()
            : null;
  }

  private static final class LanguageListData {
    private final CharSequence[] entries;
    private final CharSequence[] values;

    private LanguageListData(@NonNull CharSequence[] entries, @NonNull CharSequence[] values) {
      this.entries = entries;
      this.values = values;
    }
  }
}