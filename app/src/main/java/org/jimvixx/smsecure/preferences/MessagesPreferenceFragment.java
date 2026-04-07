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

import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Telephony;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import org.jimvixx.smsecure.ApplicationPreferencesActivity;
import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.preferences.widgets.SMSecurePreference;
import org.jimvixx.smsecure.util.Util;

import java.util.Locale;

public class MessagesPreferenceFragment extends PreferenceFragmentCompat {

  private static final String KITKAT_DEFAULT_PREF = "pref_set_default";

  private ActivityResultLauncher<Intent> defaultSmsLauncher;

  @Nullable
  private static Intent buildRequestDefaultSmsIntent(Context context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      RoleManager roleManager = context.getSystemService(RoleManager.class);
      if (roleManager != null
              && roleManager.isRoleAvailable(RoleManager.ROLE_SMS)
              && !roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
        return roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS);
      }
      return null;
    }

    Intent intent = new Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT);
    intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.getPackageName());
    return intent;
  }

  public static CharSequence getSummary(Context context) {
    return getIncomingSmsSummary(context);
  }

  private static CharSequence getIncomingSmsSummary(Context context) {
    final int onResId = R.string.On;
    final int offResId = R.string.Off;
    final int incomingSmsResId = R.string.ApplicationPreferencesActivity_incoming_summary;

    final boolean defaultSms = Util.isDefaultSmsProvider(context);
    final int stateResId = defaultSms ? onResId : offResId;

    return context.getString(incomingSmsResId, context.getString(stateResId).toLowerCase(Locale.getDefault()));
  }

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    defaultSmsLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> initializePlatformSpecificOptions());
  }

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
    setPreferencesFromResource(R.xml.preferences_messages, rootKey);
  }

  @Override
  public void onResume() {
    super.onResume();

    ApplicationPreferencesActivity activity = getHostActivity();
    if (activity != null) {
      ActionBar actionBar = activity.getSupportActionBar();
      if (actionBar != null) {
        actionBar.setTitle(R.string.preferences__messages);
      }
    }

    initializePlatformSpecificOptions();
  }

  private void initializePlatformSpecificOptions() {
    PreferenceScreen preferenceScreen = getPreferenceScreen();
    if (preferenceScreen == null || getActivity() == null) return;

    SMSecurePreference defaultPreference = findPreference(KITKAT_DEFAULT_PREF);
    if (defaultPreference == null) return;

    if (Util.isDefaultSmsProvider(getActivity())) {
      defaultPreference.setTitle(getString(R.string.ApplicationPreferencesActivity_sms_enabled));
      defaultPreference.setSummary(
              getString(R.string.ApplicationPreferencesActivity_smsecure_is_currently_your_default_sms_app)
      );
      defaultPreference.setRightSummary(getString(R.string.Enabled));
      defaultPreference.setEnabled(true);
      defaultPreference.setOnPreferenceClickListener(null);
    } else {
      defaultPreference.setTitle(getString(R.string.ApplicationPreferencesActivity_sms_disabled));
      defaultPreference.setSummary(
              getString(R.string.ApplicationPreferencesActivity_tap_to_make_smsecure_your_default_sms_app)
      );
      defaultPreference.setRightSummary(getString(R.string.Disabled));
      defaultPreference.setEnabled(true);
      defaultPreference.setOnPreferenceClickListener(preference -> {
        Intent intent = buildRequestDefaultSmsIntent(requireContext());
        if (intent != null) {
          defaultSmsLauncher.launch(intent);
        } else {
          defaultPreference.setEnabled(false);
        }
        return true;
      });
    }
  }

  @Nullable
  private ApplicationPreferencesActivity getHostActivity() {
    return (getActivity() instanceof ApplicationPreferencesActivity)
            ? (ApplicationPreferencesActivity) getActivity()
            : null;
  }
}