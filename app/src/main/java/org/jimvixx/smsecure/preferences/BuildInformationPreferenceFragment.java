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

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import org.jimvixx.smsecure.ApplicationPreferencesActivity;
import org.jimvixx.smsecure.BuildConfig;
import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.logging.DiagnosticInfoCollector;

public class BuildInformationPreferenceFragment extends PreferenceFragmentCompat {

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
    setPreferencesFromResource(R.xml.preferences_about_build, rootKey);

    setCopyableValue("about_build_app_id", BuildConfig.APPLICATION_ID);
    setCopyableValue("about_build_version_name", BuildConfig.VERSION_NAME);
    setCopyableValue("about_build_version_code", String.valueOf(BuildConfig.VERSION_CODE));
    setCopyableValue("about_build_type", BuildConfig.BUILD_TYPE);
    setCopyableValue("about_build_source_commit", BuildConfig.SOURCE_COMMIT);
    setCopyableValue("about_build_apk_signer_sha256",
            DiagnosticInfoCollector.getSignerFingerprints(requireContext()));
  }

  @Override
  public void onResume() {
    super.onResume();

    if (!(getActivity() instanceof ApplicationPreferencesActivity activity)) return;

    ActionBar actionBar = activity.getSupportActionBar();
    if (actionBar != null) {
      actionBar.setTitle(R.string.preferences__about_build_information);
    }
  }

  private void setCopyableValue(String key, CharSequence value) {
    Preference preference = findPreference(key);
    if (preference == null) return;

    preference.setSummary(value);
    preference.setOnPreferenceClickListener(clickedPreference -> {
      ClipboardManager clipboard = (ClipboardManager) requireContext()
              .getSystemService(Context.CLIPBOARD_SERVICE);
      if (clipboard != null) {
        String copiedText = clickedPreference.getTitle() + ": " + value;
        clipboard.setPrimaryClip(ClipData.newPlainText(clickedPreference.getTitle(), copiedText));
        Toast.makeText(requireContext(), R.string.Copied_to_clipboard, Toast.LENGTH_SHORT).show();
      }
      return true;
    });
  }
}
