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

package org.jimvixx.smsecure.preferences.widgets;

import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.takisoft.colorpicker.ColorPickerDialog;
import com.takisoft.colorpicker.OnColorSelectedListener;

/*
 * Dialog fragment for ColorPickerPreference.
 *
 * This implementation does not rely on deprecated setTargetFragment() and does not extend
 * PreferenceDialogFragmentCompat, so it works without the target-fragment contract.
 */
public class ColorPickerPreferenceDialogFragmentCompat extends DialogFragment
        implements OnColorSelectedListener {

  private static final String ARG_KEY = "key";

  @Nullable private ColorPickerPreference preference;

  public static ColorPickerPreferenceDialogFragmentCompat newInstance(@NonNull String key) {
    ColorPickerPreferenceDialogFragmentCompat fragment = new ColorPickerPreferenceDialogFragmentCompat();
    Bundle b = new Bundle(1);
    b.putString(ARG_KEY, key);
    fragment.setArguments(b);
    return fragment;
  }

  @NonNull
  @Override
  public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
    final ColorPickerPreference pref = preference = resolvePreferenceOrThrow();

    ColorPickerDialog.Params params = new ColorPickerDialog.Params.Builder(requireContext())
            .setSelectedColor(pref.getColor())
            .setColors(pref.getColors())
            .setColorContentDescriptions(pref.getColorDescriptions())
            .setSize(pref.getSize())
            .setSortColors(pref.isSortColors())
            .setColumns(pref.getColumns())
            .build();

    ColorPickerDialog dialog = new ColorPickerDialog(requireActivity(), this, params);

    final CharSequence dialogTitle = pref.getDialogTitle();
    final CharSequence titleToUse = (dialogTitle != null) ? dialogTitle : pref.getTitle();
    if (titleToUse != null) {
      dialog.setTitle(titleToUse);
    }

    return dialog;
  }

  @Override
  public void onColorSelected(int color) {
    // Apply immediately when a color is selected to mimic the old "positive click" flow.
    final ColorPickerPreference pref = preference;
    if (pref != null) {
      pref.setColor(color);
    }
    dismissAllowingStateLoss();
  }

  @NonNull
  private ColorPickerPreference resolvePreferenceOrThrow() {
    String key = getArguments() != null ? getArguments().getString(ARG_KEY) : null;
    if (key == null) throw new IllegalStateException("Missing preference key");

    // 1) Try parent fragment first (works if the dialog is shown as a child of the preference fragment).
    Fragment parent = getParentFragment();
    ColorPickerPreference fromParent = findInFragment(parent, key);
    if (fromParent != null) return fromParent;

    // 2) Try the fragment manager used to show the dialog.
    ColorPickerPreference fromParentManager = findInFragmentManager(getParentFragmentManager(), key);
    if (fromParentManager != null) return fromParentManager;

    // 3) Fallback to activity fragment manager (covers cases where the dialog is shown by the activity).
    ColorPickerPreference fromActivityManager =
            findInFragmentManager(requireActivity().getSupportFragmentManager(), key);
    if (fromActivityManager != null) return fromActivityManager;

    throw new IllegalStateException("Unable to resolve ColorPickerPreference for key: " + key);
  }

  @Nullable
  private static ColorPickerPreference findInFragment(@Nullable Fragment fragment, @NonNull String key) {
    if (fragment instanceof PreferenceFragmentCompat prefFragment) {
      Preference p = prefFragment.findPreference(key);
      if (p instanceof ColorPickerPreference) return (ColorPickerPreference) p;
    }

    // Search children recursively (covers nested fragments).
    if (fragment != null) {
      return findInFragmentManager(fragment.getChildFragmentManager(), key);
    }

    return null;
  }

  @Nullable
  private static ColorPickerPreference findInFragmentManager(@NonNull FragmentManager fm, @NonNull String key) {
    for (Fragment f : fm.getFragments()) {
      ColorPickerPreference found = findInFragment(f, key);
      if (found != null) return found;
    }
    return null;
  }
}
