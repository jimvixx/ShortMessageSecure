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

import androidx.annotation.Nullable;

/**
 * Shared configuration for right-side summary rendering.
 */
public final class RightSummaryConfig {

  @Nullable
  private CharSequence rightSummary;
  @Nullable
  private CharSequence rightSummaryOn;
  @Nullable
  private CharSequence rightSummaryOff;
  @Nullable
  private CharSequence passwordMaskText = "••••••••";

  private boolean passwordMask;
  private boolean hideWhenDisabled = true;
  private int maxLines = 1;

  @Nullable
  public CharSequence getRightSummary() {
    return rightSummary;
  }

  public void setRightSummary(@Nullable CharSequence rightSummary) {
    this.rightSummary = rightSummary;
  }

  @Nullable
  public CharSequence getRightSummaryOn() {
    return rightSummaryOn;
  }

  public void setRightSummaryOn(@Nullable CharSequence rightSummaryOn) {
    this.rightSummaryOn = rightSummaryOn;
  }

  @Nullable
  public CharSequence getRightSummaryOff() {
    return rightSummaryOff;
  }

  public void setRightSummaryOff(@Nullable CharSequence rightSummaryOff) {
    this.rightSummaryOff = rightSummaryOff;
  }

  public boolean isPasswordMask() {
    return passwordMask;
  }

  public void setPasswordMask(boolean passwordMask) {
    this.passwordMask = passwordMask;
  }

  @Nullable
  public CharSequence getPasswordMaskText() {
    return passwordMaskText;
  }

  public void setPasswordMaskText(@Nullable CharSequence passwordMaskText) {
    this.passwordMaskText = passwordMaskText;
  }

  public boolean isHideWhenDisabled() {
    return hideWhenDisabled;
  }

  public void setHideWhenDisabled(boolean hideWhenDisabled) {
    this.hideWhenDisabled = hideWhenDisabled;
  }

  public int getMaxLines() {
    return maxLines;
  }

  public void setMaxLines(int maxLines) {
    this.maxLines = Math.max(1, maxLines);
  }
}