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

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import org.jimvixx.smsecure.ApplicationPreferencesActivity;
import org.jimvixx.smsecure.LogSubmitActivity;
import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.jimvixx.smsecure.util.dualsim.SubscriptionManagerCompat;

public class AdvancedPreferenceFragment extends PreferenceFragmentCompat {

  private static final String SUBMIT_DEBUG_LOG_PREF = "pref_submit_debug_logs";

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
    setPreferencesFromResource(R.xml.preferences_advanced, rootKey);

    initializeSubmitDebugLogsPreference();
    updateAskForSimPreferenceVisibility();
  }

  @Override
  public void onResume() {
    super.onResume();

    ApplicationPreferencesActivity activity = getHostActivity();
    if (activity == null) return;

    ActionBar actionBar = activity.getSupportActionBar();
    if (actionBar != null) {
      actionBar.setTitle(R.string.preferences__advanced);
    }
  }

  private void initializeSubmitDebugLogsPreference() {
    Preference submitPref = findPreference(SUBMIT_DEBUG_LOG_PREF);
    if (submitPref != null) {
      submitPref.setOnPreferenceClickListener(new SubmitDebugLogListener());
    }
  }

  private void updateAskForSimPreferenceVisibility() {
    if (getActivity() == null) return;

    int simCount = SubscriptionManagerCompat.from(getActivity())
            .getActiveSubscriptionInfoList()
            .size();

    Preference askForSimPref = findPreference(SMSecurePreferences.ASK_FOR_SIM_CARD);
    if (askForSimPref != null) {
      askForSimPref.setVisible(simCount > 1);
    }
  }

  @Nullable
  private ApplicationPreferencesActivity getHostActivity() {
    return (getActivity() instanceof ApplicationPreferencesActivity)
            ? (ApplicationPreferencesActivity) getActivity()
            : null;
  }

  private static class SubmitDebugLogListener implements Preference.OnPreferenceClickListener {
    @Override
    public boolean onPreferenceClick(@NonNull Preference preference) {
      Intent intent = new Intent(preference.getContext(), LogSubmitActivity.class);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      preference.getContext().startActivity(intent);
      return true;
    }
  }
}