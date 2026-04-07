/*
 * Copyright (C) 2015 Whisper Systems
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
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import org.jimvixx.smsecure.ApplicationPreferencesActivity;
import org.jimvixx.smsecure.BaseActionBarActivity;
import org.jimvixx.smsecure.BlockedContactsActivity;
import org.jimvixx.smsecure.PassphraseChangeActivity;
import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.crypto.MasterSecretUtil;
import org.jimvixx.smsecure.preferences.widgets.SMSecurePreference;
import org.jimvixx.smsecure.preferences.widgets.SMSecureSwitchPreferenceCompat;
import org.jimvixx.smsecure.service.KeyCachingService;
import org.jimvixx.smsecure.util.SMSecurePreferences;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class AppProtectionPreferenceFragment extends PreferenceFragmentCompat {

  private static final String PREFERENCE_CATEGORY_BLOCKED = "preference_category_blocked";

  @Nullable
  private MasterSecret masterSecret;

  @Nullable
  private SMSecureSwitchPreferenceCompat disablePassphrase;

  public static CharSequence getSummary(Context context) {
    int privacySummaryResId = R.string.ApplicationPreferencesActivity_privacy_summary;
    String onRes = context.getString(R.string.On).toLowerCase(Locale.getDefault());
    String offRes = context.getString(R.string.Off).toLowerCase(Locale.getDefault());

    if (SMSecurePreferences.isPasswordDisabled(context)) {
      if (SMSecurePreferences.isScreenSecurityEnabled(context)) {
        return context.getString(privacySummaryResId, offRes, onRes);
      } else {
        return context.getString(privacySummaryResId, offRes, offRes);
      }
    } else {
      if (SMSecurePreferences.isScreenSecurityEnabled(context)) {
        return context.getString(privacySummaryResId, onRes, onRes);
      } else {
        return context.getString(privacySummaryResId, onRes, offRes);
      }
    }
  }

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    Bundle args = requireArguments();
    masterSecret = args.getParcelable("master_secret");
  }

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
    setPreferencesFromResource(R.xml.preferences_app_protection, rootKey);

    disablePassphrase = findPreference("pref_enable_passphrase_temporary");

    Preference changePass = findPreference(SMSecurePreferences.CHANGE_PASSPHRASE_PREF);
    Preference timeoutPref = findPreference(SMSecurePreferences.PASSPHRASE_TIMEOUT_INTERVAL_PREF);
    Preference blockedPref = findPreference(PREFERENCE_CATEGORY_BLOCKED);
    Preference screenSecurityPref = findPreference(SMSecurePreferences.SCREEN_SECURITY_PREF);

    if (changePass != null) {
      changePass.setOnPreferenceClickListener(new ChangePassphraseClickListener());
    }

    if (timeoutPref != null) {
      timeoutPref.setOnPreferenceClickListener(new PassphraseIntervalClickListener());
    }

    if (blockedPref != null) {
      blockedPref.setOnPreferenceClickListener(new BlockedContactsClickListener());
    }

    if (screenSecurityPref != null) {
      screenSecurityPref.setOnPreferenceChangeListener(new ScreenSecurityChangeListener());
    }

    if (disablePassphrase != null) {
      disablePassphrase.setOnPreferenceChangeListener(new DisablePassphraseClickListener());
    }
  }

  @Override
  public void onResume() {
    super.onResume();

    if (getActivity() instanceof ApplicationPreferencesActivity activity) {
      ActionBar actionBar = activity.getSupportActionBar();
      if (actionBar != null) {
        actionBar.setTitle(R.string.preferences__privacy);
      }
    }

    initializeTimeoutSummary();

    if (disablePassphrase != null) {
      disablePassphrase.setChecked(!SMSecurePreferences.isPasswordDisabled(getActivity()));
    }
  }

  private void initializeTimeoutSummary() {
    int timeoutMinutes = SMSecurePreferences.getPassphraseTimeoutInterval(getActivity());
    Preference preference = findPreference(SMSecurePreferences.PASSPHRASE_TIMEOUT_INTERVAL_PREF);
    if (preference == null) return;

    CharSequence summary = getResources().getQuantityString(
            R.plurals.AppProtectionPreferenceFragment_minutes,
            timeoutMinutes,
            timeoutMinutes
    );

    if (preference instanceof SMSecurePreference smSecurePreference) {
      smSecurePreference.setRightSummary(summary);
    } else {
      preference.setSummary(summary);
    }
  }

  private class ScreenSecurityChangeListener implements Preference.OnPreferenceChangeListener {
    @Override
    public boolean onPreferenceChange(@NonNull Preference preference, Object newValue) {
      boolean enabled = (newValue instanceof Boolean) && (Boolean) newValue;

      if (requireActivity() instanceof BaseActionBarActivity activity) {
        activity.applyScreenshotSecurity(enabled);
      } else {
        if (enabled) {
          requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
          requireActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
      }

      return true;
    }
  }

  private class BlockedContactsClickListener implements Preference.OnPreferenceClickListener {
    @Override
    public boolean onPreferenceClick(@NonNull Preference preference) {
      startActivity(new Intent(requireActivity(), BlockedContactsActivity.class));
      return true;
    }
  }

  private class ChangePassphraseClickListener implements Preference.OnPreferenceClickListener {
    @Override
    public boolean onPreferenceClick(@NonNull Preference preference) {
      if (MasterSecretUtil.isPassphraseInitialized(requireActivity())) {
        startActivity(new Intent(requireActivity(), PassphraseChangeActivity.class));
      } else {
        Toast.makeText(
                requireActivity(),
                R.string.ApplicationPreferenceActivity_you_havent_set_a_passphrase_yet,
                Toast.LENGTH_LONG
        ).show();
      }
      return true;
    }
  }

  private class PassphraseIntervalClickListener implements Preference.OnPreferenceClickListener {

    @Override
    public boolean onPreferenceClick(@NonNull Preference preference) {
      Context context = requireActivity();

      int currentTimeoutMinutes = SMSecurePreferences.getPassphraseTimeoutInterval(context);
      if (currentTimeoutMinutes < 1) currentTimeoutMinutes = 1;

      int currentHours = currentTimeoutMinutes / 60;
      int currentMinutes = currentTimeoutMinutes % 60;

      int dp16 = (int) (16 * context.getResources().getDisplayMetrics().density);
      int dp8 = (int) (8 * context.getResources().getDisplayMetrics().density);

      LinearLayout root = new LinearLayout(context);
      root.setOrientation(LinearLayout.VERTICAL);
      root.setPadding(dp16, dp16, dp16, dp16);
      root.setLayoutParams(new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.WRAP_CONTENT
      ));

      TextView title = new TextView(context);
      title.setText(R.string.preferences__pref_timeout_interval_title);
      title.setGravity(Gravity.CENTER);
      title.setPadding(0, 0, 0, dp8);
      title.setTextAppearance(R.style.SMSecure_TextAppearance_Title);

      root.addView(title, new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.WRAP_CONTENT
      ));

      LinearLayout row = new LinearLayout(context);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setLayoutParams(new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.WRAP_CONTENT
      ));

      NumberPicker hoursPicker = new NumberPicker(context);
      hoursPicker.setMinValue(0);
      hoursPicker.setMaxValue(23);
      hoursPicker.setValue(Math.min(currentHours, 23));

      NumberPicker minutesPicker = new NumberPicker(context);
      minutesPicker.setMinValue(0);
      minutesPicker.setMaxValue(59);
      minutesPicker.setValue(currentMinutes);

      View hoursColumn = createPickerColumn(
              context,
              R.string.preferences__hours,
              hoursPicker,
              dp8
      );

      View minutesColumn = createPickerColumn(
              context,
              R.string.preferences__minutes,
              minutesPicker,
              dp8
      );

      LinearLayout.LayoutParams columnLayoutParams = new LinearLayout.LayoutParams(
              0,
              ViewGroup.LayoutParams.WRAP_CONTENT,
              1f
      );

      row.addView(hoursColumn, columnLayoutParams);
      row.addView(minutesColumn, columnLayoutParams);

      root.addView(row);

      new AlertDialog.Builder(context)
              .setView(root)
              .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                int hours = hoursPicker.getValue();
                int minutes = minutesPicker.getValue();

                int timeoutMinutes = Math.max(
                        (int) TimeUnit.HOURS.toMinutes(hours) + minutes,
                        1
                );

                SMSecurePreferences.setPassphraseTimeoutInterval(requireActivity(), timeoutMinutes);
                initializeTimeoutSummary();
              })
              .setNegativeButton(android.R.string.cancel, null)
              .show();

      return true;
    }

    private View createPickerColumn(Context context,
                                    int labelRes,
                                    NumberPicker picker,
                                    int bottomPadding) {
      LinearLayout column = new LinearLayout(context);
      column.setOrientation(LinearLayout.VERTICAL);

      TextView label = new TextView(context);
      label.setText(labelRes);
      label.setGravity(Gravity.CENTER);
      label.setPadding(0, 0, 0, bottomPadding);
      label.setTextAppearance(R.style.SMSecure_TextAppearance_Title);

      column.addView(label, new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.WRAP_CONTENT
      ));

      column.addView(picker, new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.WRAP_CONTENT
      ));

      return column;
    }
  }

  private class DisablePassphraseClickListener implements Preference.OnPreferenceChangeListener {
    @Override
    public boolean onPreferenceChange(@NonNull Preference preference, Object newValue) {
      SMSecureSwitchPreferenceCompat switchPreference = (SMSecureSwitchPreferenceCompat) preference;

      if (switchPreference.isChecked()) {
        new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.ApplicationPreferencesActivity_disable_storage_encryption)
                .setMessage(R.string.ApplicationPreferencesActivity_warning_this_will_disable_storage_encryption_for_all_messages)
                .setIconAttribute(R.attr.dialog_alert_icon)
                .setPositiveButton(R.string.Disable, (dialog, which) -> {

                  if (masterSecret == null) return;
                  MasterSecretUtil.changeMasterSecretPassphrase(
                          requireActivity(),
                          masterSecret,
                          MasterSecretUtil.UNENCRYPTED_PASSPHRASE
                  );

                  SMSecurePreferences.setPasswordDisabled(requireActivity(), true);

                  switchPreference.setChecked(false);

                  Intent intent = new Intent(requireActivity(), KeyCachingService.class);
                  intent.setAction(KeyCachingService.DISABLE_ACTION);
                  requireActivity().startService(intent);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
      } else {
        startActivity(new Intent(requireActivity(), PassphraseChangeActivity.class));
      }

      return false;
    }
  }
}