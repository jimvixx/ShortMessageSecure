/*
 * Copyright (C) 2015 Open Whisper Systems
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
import android.content.SharedPreferences;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import org.jimvixx.smsecure.ApplicationPreferencesActivity;
import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.notifications.MessageNotifier;
import org.jimvixx.smsecure.preferences.widgets.AdvancedRingtonePreference;
import org.jimvixx.smsecure.preferences.widgets.RingtonePreferenceDialogFragmentCompat;
import org.jimvixx.smsecure.util.SMSecurePreferences;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NotificationsPreferenceFragment extends PreferenceFragmentCompat {

  private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
  private static final String DIALOG_FRAGMENT_TAG = "androidx.preference.PreferenceFragment.DIALOG";

  @Nullable
  private MasterSecret masterSecret;

  public static CharSequence getSummary(@NonNull Context context) {
    int onCapsResId = R.string.On;
    int offCapsResId = R.string.Off;

    return context.getString(
            SMSecurePreferences.isNotificationsEnabled(context) ? onCapsResId : offCapsResId
    );
  }

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    Bundle args = getArguments();
    masterSecret = args != null ? args.getParcelable("master_secret") : null;
  }

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
    setPreferencesFromResource(R.xml.preferences_notifications, rootKey);

    initializeRingtonePreference();
    initializeNotificationPrivacyPreference();
  }

  @Override
  public void onResume() {
    super.onResume();

    ApplicationPreferencesActivity activity = getHostActivity();
    if (activity != null) {
      ActionBar actionBar = activity.getSupportActionBar();
      if (actionBar != null) {
        actionBar.setTitle(R.string.preferences__notifications);
      }
    }
  }

  @Override
  public void onDisplayPreferenceDialog(@NonNull Preference preference) {
    if (preference instanceof AdvancedRingtonePreference) {
      if (getParentFragmentManager().findFragmentByTag(DIALOG_FRAGMENT_TAG) != null) {
        return;
      }

      RingtonePreferenceDialogFragmentCompat dialog =
              RingtonePreferenceDialogFragmentCompat.newInstance(preference.getKey());

      dialog.show(getParentFragmentManager(), DIALOG_FRAGMENT_TAG);
      return;
    }

    super.onDisplayPreferenceDialog(preference);
  }

  private void initializeRingtonePreference() {
    Preference preference = findPreference(SMSecurePreferences.RINGTONE_PREF);
    if (!(preference instanceof AdvancedRingtonePreference ringtonePreference)) {
      return;
    }

    ringtonePreference.setOnPreferenceChangeListener(new RingtoneSummaryListener());
    initializeRingtoneSummary(ringtonePreference);
  }

  private void initializeNotificationPrivacyPreference() {
    Preference preference = findPreference(SMSecurePreferences.NOTIFICATION_PRIVACY_PREF);
    if (preference != null) {
      preference.setOnPreferenceChangeListener(new NotificationPrivacyListener());
    }
  }

  private void initializeRingtoneSummary(@NonNull AdvancedRingtonePreference preference) {
    SharedPreferences sharedPreferences =
            PreferenceManager.getDefaultSharedPreferences(requireContext());

    String encodedUri = sharedPreferences.getString(preference.getKey(), null);
    Uri uri = !TextUtils.isEmpty(encodedUri) ? Uri.parse(encodedUri) : null;

    updateRingtoneSummary(preference, uri);
  }

  private void updateRingtoneSummary(@NonNull Preference preference, @Nullable Uri value) {
    Context context = requireContext();

    if (value == null) {
      preference.setSummary(R.string.Silent);
      return;
    }

    Ringtone tone = RingtoneManager.getRingtone(context, value);
    if (tone != null) {
      preference.setSummary(tone.getTitle(context));
    } else {
      preference.setSummary(R.string.Silent);
    }
  }

  @Nullable
  private ApplicationPreferencesActivity getHostActivity() {
    return (getActivity() instanceof ApplicationPreferencesActivity)
            ? (ApplicationPreferencesActivity) getActivity()
            : null;
  }

  private final class RingtoneSummaryListener implements Preference.OnPreferenceChangeListener {
    @Override
    public boolean onPreferenceChange(@NonNull Preference preference, Object newValue) {
      Uri value = (newValue instanceof Uri) ? (Uri) newValue : null;
      updateRingtoneSummary(preference, value);
      return true;
    }
  }

  private final class NotificationPrivacyListener implements Preference.OnPreferenceChangeListener {
    @Override
    public boolean onPreferenceChange(@NonNull Preference preference, Object newValue) {
      Context appContext = requireContext().getApplicationContext();
      MasterSecret ms = masterSecret;

      if (ms != null) {
        EXECUTOR.execute(() -> MessageNotifier.updateNotification(appContext, ms));
      }

      return true;
    }
  }
}