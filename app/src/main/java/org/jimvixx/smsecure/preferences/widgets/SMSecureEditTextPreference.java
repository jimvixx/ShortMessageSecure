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

package org.jimvixx.smsecure.preferences.widgets;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceViewHolder;

import org.jimvixx.smsecure.R;

/**
 * An EditTextPreference with a custom full layout and right-side text summary.
 */
public class SMSecureEditTextPreference extends EditTextPreference {

  private final RightSummaryConfig rightSummaryConfig;
  private TextView rightSummaryView;
  @Nullable
  private RightSummaryFormatter rightSummaryFormatter;

  public SMSecureEditTextPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
    super(context, attrs, defStyleAttr, defStyleRes);
    rightSummaryConfig = initialize(context, attrs);
  }

  public SMSecureEditTextPreference(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    rightSummaryConfig = initialize(context, attrs);
  }

  public SMSecureEditTextPreference(Context context, AttributeSet attrs) {
    super(context, attrs);
    rightSummaryConfig = initialize(context, attrs);
  }

  public SMSecureEditTextPreference(Context context) {
    super(context);
    rightSummaryConfig = initialize(context, null);
  }

  private RightSummaryConfig initialize(Context context, AttributeSet attrs) {
    setLayoutResource(R.layout.smsecure_preference);
    return RightSummaryStyleableReader.read(context, attrs);
  }

  @Override
  public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
    super.onBindViewHolder(holder);
    rightSummaryView = (TextView) holder.findViewById(R.id.right_summary);
    bindRightSummary();
    PreferenceEnabledStateBinder.bind(holder, this);
  }

  @Override
  public void setText(String text) {
    super.setText(text);
    notifyChanged();
  }

  @Override
  protected boolean persistString(String value) {
    boolean persisted = super.persistString(value);

    if (persisted) {
      notifyChanged();
    }

    return persisted;
  }

  @Override
  public void setEnabled(boolean enabled) {
    super.setEnabled(enabled);
    notifyChanged();
  }

  public boolean isRightSummaryPasswordMask() {
    return rightSummaryConfig.isPasswordMask();
  }

  public void setRightSummaryPasswordMask(boolean enabled) {
    rightSummaryConfig.setPasswordMask(enabled);
    notifyChanged();
  }

  @Nullable
  public CharSequence getRightSummaryPasswordMaskText() {
    return rightSummaryConfig.getPasswordMaskText();
  }

  public void setRightSummaryPasswordMaskText(@Nullable CharSequence text) {
    rightSummaryConfig.setPasswordMaskText(text);
    notifyChanged();
  }

  public int getRightSummaryMaxLines() {
    return rightSummaryConfig.getMaxLines();
  }

  public void setRightSummaryMaxLines(int maxLines) {
    rightSummaryConfig.setMaxLines(maxLines);
    notifyChanged();
  }

  public boolean isHideRightSummaryWhenDisabled() {
    return rightSummaryConfig.isHideWhenDisabled();
  }

  public void setHideRightSummaryWhenDisabled(boolean hide) {
    rightSummaryConfig.setHideWhenDisabled(hide);
    notifyChanged();
  }

  @Nullable
  public RightSummaryFormatter getRightSummaryFormatter() {
    return rightSummaryFormatter;
  }

  public void setRightSummaryFormatter(@Nullable RightSummaryFormatter formatter) {
    this.rightSummaryFormatter = formatter;
    notifyChanged();
  }

  @Nullable
  private CharSequence getCurrentRightSummary() {
    String text = getText();

    if (text == null || text.trim().isEmpty()) {
      return null;
    }

    if (rightSummaryConfig.isPasswordMask()) {
      return rightSummaryConfig.getPasswordMaskText();
    }

    if (rightSummaryFormatter != null) {
      return rightSummaryFormatter.format(getContext(), text);
    }

    return text;
  }

  private void bindRightSummary() {
    PreferenceRightSummaryBinder.bind(
            rightSummaryView,
            getCurrentRightSummary(),
            rightSummaryConfig.getMaxLines(),
            rightSummaryConfig.isHideWhenDisabled(),
            this
    );
  }

  public interface RightSummaryFormatter {
    @Nullable
    CharSequence format(@NonNull Context context, @Nullable String value);
  }
}