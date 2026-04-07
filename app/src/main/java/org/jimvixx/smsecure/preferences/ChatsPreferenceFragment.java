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

package org.jimvixx.smsecure.preferences;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import org.jimvixx.smsecure.ApplicationPreferencesActivity;
import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.preferences.widgets.SMSecureEditTextPreference;
import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.jimvixx.smsecure.util.Trimmer;

public class ChatsPreferenceFragment extends PreferenceFragmentCompat {

  private static final String TAG = ChatsPreferenceFragment.class.getSimpleName();

  public static CharSequence getSummary() {
    return null;
  }

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
    setPreferencesFromResource(R.xml.preferences_chats, rootKey);
    setupPreferenceListeners();
  }

  @Override
  public void onResume() {
    super.onResume();
    setActivityTitleSafely(R.string.preferences__chats);
  }

  private void setupPreferenceListeners() {
    Preference trimNow = findPreference(SMSecurePreferences.THREAD_TRIM_NOW);
    if (trimNow != null) {
      trimNow.setOnPreferenceClickListener(new TrimNowClickListener());
    } else {
      Log.w(TAG, "Preference not found: " + SMSecurePreferences.THREAD_TRIM_NOW);
    }

    Preference trimLengthPreference = findPreference(SMSecurePreferences.THREAD_TRIM_LENGTH);
    if (trimLengthPreference instanceof SMSecureEditTextPreference trimLength) {

      trimLength.setRightSummaryFormatter((context, value) -> {
        if (value == null || value.trim().isEmpty()) {
          return null;
        }

        try {
          int count = Integer.parseInt(value.trim());
          if (count < 1) return null;

          return context.getResources().getQuantityString(
                  R.plurals.preferences__msgs_short,
                  count,
                  count
          );
        } catch (NumberFormatException e) {
          Log.w(TAG, e);
          return value;
        }
      });

      trimLength.setOnPreferenceChangeListener(new TrimLengthValidationListener());

    } else {
      Log.w(TAG, "Preference not found or wrong type: " + SMSecurePreferences.THREAD_TRIM_LENGTH);
    }
  }

  private void setActivityTitleSafely(int titleResId) {
    if (!(getActivity() instanceof ApplicationPreferencesActivity activity)) {
      return;
    }

    ActionBar actionBar = activity.getSupportActionBar();
    if (actionBar != null) {
      actionBar.setTitle(titleResId);
    } else {
      Log.w(TAG, "SupportActionBar is null. Title not set.");
    }
  }

  private static final class TrimLengthValidationListener implements Preference.OnPreferenceChangeListener {
    @Override
    public boolean onPreferenceChange(@NonNull Preference preference, Object newValue) {
      String text = (newValue instanceof String) ? (String) newValue : null;

      if (text == null || text.trim().isEmpty()) {
        return false;
      }

      try {
        int value = Integer.parseInt(text.trim());
        return value >= 1;
      } catch (NumberFormatException nfe) {
        Log.w(TAG, nfe);
        return false;
      }
    }
  }

  private final class TrimNowClickListener implements Preference.OnPreferenceClickListener {
    @Override
    public boolean onPreferenceClick(@NonNull Preference preference) {
      Context context = getContext();
      if (context == null) return true;

      final int threadLengthLimit = SMSecurePreferences.getThreadTrimLength(context);

      AlertDialog.Builder builder = new AlertDialog.Builder(context);
      builder.setTitle(R.string.ApplicationPreferencesActivity_delete_all_old_messages_now);
      builder.setMessage(getResources().getQuantityString(
              R.plurals.ApplicationPreferencesActivity_this_will_immediately_trim_all_conversations_to_the_d_most_recent_messages,
              threadLengthLimit,
              threadLengthLimit
      ));

      builder.setPositiveButton(
              R.string.Delete,
              (dialog, which) -> {
                Context ctx = getContext();
                if (ctx == null) return;
                Trimmer.trimAllThreads(ctx, threadLengthLimit);
              });

      builder.setNegativeButton(android.R.string.cancel, null);
      builder.show();

      return true;
    }
  }
}