/*
 * Copyright (C) 2026 Jimvixx
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

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import org.jimvixx.smsecure.ApplicationPreferencesActivity;
import org.jimvixx.smsecure.BuildConfig;
import org.jimvixx.smsecure.R;

public class AboutPreferenceFragment extends PreferenceFragmentCompat {

  private static final String PREFERENCE_GITHUB_README = "preference_about_github_readme";
  private static final String PREFERENCE_PRIVACY_POLICY = "preference_about_privacy_policy";
  private static final String PREFERENCE_OPEN_SOURCE_LICENSES = "preference_about_licenses";
  private static final String PREFERENCE_BUILD_INFO = "preference_about_build_info";

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
    setPreferencesFromResource(R.xml.preferences_about, rootKey);

    setClickListenerIfPresent(PREFERENCE_GITHUB_README, preference -> {
      ApplicationPreferencesActivity activity = getHostActivity();
      if (activity != null) activity.openWebPage(BuildConfig.ABOUT_URL);
      return true;
    });

    setClickListenerIfPresent(PREFERENCE_PRIVACY_POLICY, preference -> {
      ApplicationPreferencesActivity activity = getHostActivity();
      if (activity != null) activity.openWebPage(BuildConfig.PRIVACY_POLICY_URL);
      return true;
    });

    setClickListenerIfPresent(PREFERENCE_OPEN_SOURCE_LICENSES, preference -> {
      ApplicationPreferencesActivity activity = getHostActivity();
      if (activity != null) {
        activity.openAboutSection(ApplicationPreferencesActivity.PREFERENCE_CATEGORY_ABOUT_LICENSES);
      }
      return true;
    });

    setClickListenerIfPresent(PREFERENCE_BUILD_INFO, preference -> {
      ApplicationPreferencesActivity activity = getHostActivity();
      if (activity != null) {
        activity.openAboutSection(ApplicationPreferencesActivity.PREFERENCE_CATEGORY_ABOUT_BUILD_INFO);
      }
      return true;
    });
  }

  @Override
  public void onResume() {
    super.onResume();

    ApplicationPreferencesActivity activity = getHostActivity();
    if (activity == null) return;

    ActionBar actionBar = activity.getSupportActionBar();
    if (actionBar != null) actionBar.setTitle(R.string.preferences__about);
  }

  private void setClickListenerIfPresent(@NonNull String key,
                                         @NonNull Preference.OnPreferenceClickListener listener) {
    Preference preference = findPreference(key);
    if (preference != null) preference.setOnPreferenceClickListener(listener);
  }

  @Nullable
  private ApplicationPreferencesActivity getHostActivity() {
    return (getActivity() instanceof ApplicationPreferencesActivity)
            ? (ApplicationPreferencesActivity) getActivity()
            : null;
  }
}
