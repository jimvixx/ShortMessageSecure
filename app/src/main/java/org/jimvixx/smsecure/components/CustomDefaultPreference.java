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

package org.jimvixx.smsecure.components;

import android.app.Dialog;
import android.content.Context;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.DialogPreference;
import androidx.preference.PreferenceDialogFragmentCompat;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.util.SMSecurePreferences;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Preference that lets the user choose between a default value and a custom value.
 * <p>
 * Stores:
 * - a boolean toggle (customToggleKey) indicating whether "custom" is enabled
 * - a string value (customPreferenceKey) containing the custom value
 * <p>
 * Dialog UI:
 * - spinner: default vs custom
 * - either shows a default label or an editable custom value
 * - validates custom input via a validator
 */
public class CustomDefaultPreference extends DialogPreference {

  private static final String TAG = CustomDefaultPreference.class.getSimpleName();

  private final int inputType;
  private final @NonNull String customPreferenceKey;
  private final @NonNull String customToggleKey;

  private @NonNull CustomPreferenceValidator validator = value -> true;
  private @Nullable String defaultValue;

  public CustomDefaultPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);

    int[] attributeNames = new int[]{android.R.attr.inputType, R.attr.custom_pref_toggle};
    TypedArray attributes = context.obtainStyledAttributes(attrs, attributeNames);

    this.inputType = attributes.getInt(0, 0);
    this.customPreferenceKey = getKey();

    String toggle = attributes.getString(1);
    this.customToggleKey = toggle != null ? toggle : "";

    attributes.recycle();

    setPersistent(false);
    setDialogLayoutResource(R.layout.custom_default_preference_dialog);
  }

  public @NonNull CustomDefaultPreference setValidator(@NonNull CustomPreferenceValidator validator) {
    this.validator = validator;
    return this;
  }

  @Override
  public @NonNull String getSummary() {
    if (isCustom()) {
      return getContext().getString(R.string.CustomDefaultPreference_using_custom,
              pretty(getCustomValue()));
    } else {
      return getContext().getString(R.string.CustomDefaultPreference_using_default,
              pretty(getDefaultValue()));
    }
  }

  private @NonNull String pretty(@Nullable String value) {
    if (TextUtils.isEmpty(value)) {
      return getContext().getString(R.string.None);
    }
    return value;
  }

  private boolean isCustom() {
    return SMSecurePreferences.getBooleanPreference(getContext(), customToggleKey, false);
  }

  private void setCustom(boolean custom) {
    SMSecurePreferences.setBooleanPreference(getContext(), customToggleKey, custom);
  }

  private @NonNull String getCustomValue() {
    return SMSecurePreferences.getStringPreference(getContext(), customPreferenceKey, "");
  }

  private void setCustomValue(@NonNull String value) {
    SMSecurePreferences.setStringPreference(getContext(), customPreferenceKey, value);
  }

  private @NonNull String getDefaultValue() {
    return defaultValue != null ? defaultValue : "";
  }

  public @NonNull CustomDefaultPreference setDefaultValue(@Nullable String defaultValue) {
    this.defaultValue = defaultValue;
    setSummary(getSummary());
    return this;
  }

  /**
   * Validator for custom input.
   * <p>
   * Must be public because validator implementations (e.g. UriValidator) are public and may be
   * referenced from outside this class. This avoids "exposed outside its defined visibility scope".
   */
  public interface CustomPreferenceValidator {
    boolean isValid(@NonNull String value);
  }

  /**
   * Dialog fragment for CustomDefaultPreference.
   */
  public static class CustomDefaultPreferenceDialogFragmentCompat extends PreferenceDialogFragmentCompat {

    private Spinner spinner;
    private EditText customText;
    private TextView defaultLabel;

    public static CustomDefaultPreferenceDialogFragmentCompat newInstance(@NonNull String key) {
      CustomDefaultPreferenceDialogFragmentCompat fragment = new CustomDefaultPreferenceDialogFragmentCompat();
      Bundle b = new Bundle(1);
      b.putString(PreferenceDialogFragmentCompat.ARG_KEY, key);
      fragment.setArguments(b);
      return fragment;
    }

    @Override
    protected void onBindDialogView(@NonNull View view) {
      Log.w(TAG, "onBindDialogView");
      super.onBindDialogView(view);

      CustomDefaultPreference preference = (CustomDefaultPreference) getPreference();

      spinner = view.findViewById(R.id.default_or_custom);
      defaultLabel = view.findViewById(R.id.default_label);
      customText = view.findViewById(R.id.custom_edit);

      customText.setInputType(preference.inputType);
      customText.addTextChangedListener(new TextValidator());
      customText.setText(preference.getCustomValue());

      spinner.setOnItemSelectedListener(new SelectionListener());
      defaultLabel.setText(preference.pretty(preference.defaultValue));
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle instanceState) {
      Dialog dialog = super.onCreateDialog(instanceState);

      CustomDefaultPreference preference = (CustomDefaultPreference) getPreference();

      // onBindDialogView() is called during dialog creation; guard anyway.
      if (spinner != null) {
        spinner.setSelection(preference.isCustom() ? 1 : 0, true);
      }

      return dialog;
    }

    @Override
    public void onDialogClosed(boolean positiveResult) {
      CustomDefaultPreference preference = (CustomDefaultPreference) getPreference();

      if (positiveResult) {
        if (spinner != null) preference.setCustom(spinner.getSelectedItemPosition() == 1);
        if (customText != null) preference.setCustomValue(customText.getText().toString());
        preference.setSummary(preference.getSummary());
      }
    }

    /**
     * Enables/disables the positive button depending on selection and validation state.
     * <p>
     * getDialog() can be null early; getButton() can be null until the dialog is shown.
     * We guard both to avoid NPE.
     */
    private void updatePositiveButtonState() {
      if (spinner == null || customText == null) return;

      Dialog d = getDialog();
      if (!(d instanceof AlertDialog)) return;

      Button positive = ((AlertDialog) d).getButton(AlertDialog.BUTTON_POSITIVE);
      if (positive == null) return;

      CustomDefaultPreference preference = (CustomDefaultPreference) getPreference();
      boolean defaultSelected = spinner.getSelectedItemPosition() == 0;

      if (defaultSelected) {
        positive.setEnabled(true);
      } else {
        positive.setEnabled(preference.validator.isValid(customText.getText().toString()));
      }
    }

    @Override
    public void onStart() {
      super.onStart();
      // Buttons are created now; update once.
      updatePositiveButtonState();
    }

    public static class UriValidator implements CustomPreferenceValidator {
      @Override
      public boolean isValid(@NonNull String value) {
        if (TextUtils.isEmpty(value)) return true;

        try {
          new URI(value);
          return true;
        } catch (URISyntaxException ignored) {
          return false;
        }
      }
    }

    public static class HostnameValidator implements CustomPreferenceValidator {
      @Override
      public boolean isValid(@NonNull String value) {
        if (TextUtils.isEmpty(value)) return true;

        try {
          new URI(null, value, null, null);
          return true;
        } catch (URISyntaxException ignored) {
          return false;
        }
      }
    }

    public static class PortValidator implements CustomPreferenceValidator {
      @Override
      public boolean isValid(@NonNull String value) {
        try {
          Integer.parseInt(value);
          return true;
        } catch (NumberFormatException ignored) {
          return false;
        }
      }
    }

    private final class TextValidator implements TextWatcher {
      @Override
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {
      }

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {
      }

      @Override
      public void afterTextChanged(Editable s) {
        updatePositiveButtonState();
      }
    }

    private final class SelectionListener implements AdapterView.OnItemSelectedListener {
      @Override
      public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if (defaultLabel != null)
          defaultLabel.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
        if (customText != null) customText.setVisibility(position == 0 ? View.GONE : View.VISIBLE);
        updatePositiveButtonState();
      }

      @Override
      public void onNothingSelected(AdapterView<?> parent) {
        if (defaultLabel != null) defaultLabel.setVisibility(View.VISIBLE);
        if (customText != null) customText.setVisibility(View.GONE);
        updatePositiveButtonState();
      }
    }
  }
}