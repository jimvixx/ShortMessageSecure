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

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import org.jimvixx.smsecure.components.CustomDefaultPreference;
import org.jimvixx.smsecure.preferences.widgets.ColorPickerPreference;
import org.jimvixx.smsecure.preferences.widgets.ColorPickerPreferenceDialogFragmentCompat;
import org.jimvixx.smsecure.preferences.widgets.RingtonePreference;
import org.jimvixx.smsecure.preferences.widgets.RingtonePreferenceDialogFragmentCompat;

public abstract class CorrectedPreferenceFragment extends PreferenceFragmentCompat {

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    View lv = view.findViewById(android.R.id.list);
    if (lv != null) lv.setPadding(0, 0, 0, 0);
  }

  @Override
  public void onDisplayPreferenceDialog(@NonNull Preference preference) {

    if (preference instanceof RingtonePreference) {
      RingtonePreferenceDialogFragmentCompat
              .newInstance(preference.getKey())
              .show(getParentFragmentManager(),
                      "androidx.preference.PreferenceFragment.DIALOG");
      return;
    }

    if (preference instanceof ColorPickerPreference) {
      ColorPickerPreferenceDialogFragmentCompat
              .newInstance(preference.getKey())
              .show(getParentFragmentManager(),
                      "androidx.preference.PreferenceFragment.DIALOG");
      return;
    }

    if (preference instanceof CustomDefaultPreference) {
      CustomDefaultPreference.CustomDefaultPreferenceDialogFragmentCompat
              .newInstance(preference.getKey())
              .show(getParentFragmentManager(),
                      "androidx.preference.PreferenceFragment.DIALOG");
      return;
    }

    super.onDisplayPreferenceDialog(preference);
  }

}
