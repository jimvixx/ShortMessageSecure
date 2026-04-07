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
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SwitchPreferenceCompat;

import org.jimvixx.smsecure.R;

/**
 * A SwitchPreferenceCompat with a dedicated custom layout and right-side state summary.
 */
public class SMSecureSwitchPreferenceCompat extends SwitchPreferenceCompat {

  private final RightSummaryConfig rightSummaryConfig;
  private TextView rightSummaryView;

  public SMSecureSwitchPreferenceCompat(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
    super(context, attrs, defStyleAttr, defStyleRes);
    rightSummaryConfig = initialize(context, attrs);
  }

  public SMSecureSwitchPreferenceCompat(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    rightSummaryConfig = initialize(context, attrs);
  }

  public SMSecureSwitchPreferenceCompat(Context context, AttributeSet attrs) {
    super(context, attrs);
    rightSummaryConfig = initialize(context, attrs);
  }

  public SMSecureSwitchPreferenceCompat(Context context) {
    super(context);
    rightSummaryConfig = initialize(context, null);
  }

  private RightSummaryConfig initialize(Context context, AttributeSet attrs) {
    setLayoutResource(R.layout.smsecure_switch_preference);

    setSwitchTextOn("");
    setSwitchTextOff("");

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
  public void setChecked(boolean checked) {
    super.setChecked(checked);
    notifyChanged();
  }

  @Override
  public void setEnabled(boolean enabled) {
    super.setEnabled(enabled);
    notifyChanged();
  }

  public CharSequence getRightSummaryOn() {
    return rightSummaryConfig.getRightSummaryOn();
  }

  public void setRightSummaryOn(CharSequence text) {
    rightSummaryConfig.setRightSummaryOn(text);
    notifyChanged();
  }

  public CharSequence getRightSummaryOff() {
    return rightSummaryConfig.getRightSummaryOff();
  }

  public void setRightSummaryOff(CharSequence text) {
    rightSummaryConfig.setRightSummaryOff(text);
    notifyChanged();
  }

  public int getRightSummaryMaxLines() {
    return rightSummaryConfig.getMaxLines();
  }

  public void setRightSummaryMaxLines(int maxLines) {
    rightSummaryConfig.setMaxLines(maxLines);
    notifyChanged();
  }

  public void setHideRightSummaryWhenDisabled(boolean hide) {
    rightSummaryConfig.setHideWhenDisabled(hide);
    notifyChanged();
  }

  private CharSequence getCurrentRightSummary() {
    if (isChecked()) {
      return rightSummaryConfig.getRightSummaryOn() != null
              ? rightSummaryConfig.getRightSummaryOn()
              : "";
    } else {
      return rightSummaryConfig.getRightSummaryOff() != null
              ? rightSummaryConfig.getRightSummaryOff()
              : "";
    }
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
}