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

package org.jimvixx.smsecure.components.reminder;

import androidx.annotation.NonNull;

import android.view.View.OnClickListener;

public abstract class Reminder {
  private final CharSequence buttonText;
  private final CharSequence title;
  private final CharSequence text;

  private OnClickListener okListener;
  private OnClickListener dismissListener;

  public Reminder(@NonNull CharSequence title,
                  @NonNull CharSequence text,
                  @NonNull CharSequence buttonText)
  {
    this.title      = title;
    this.text       = text;
    this.buttonText = buttonText;
  }

  public CharSequence getTitle() {
    return title;
  }

  public CharSequence getText() {
    return text;
  }

  public CharSequence getButtonText() {
    return buttonText;
  }

  public OnClickListener getOkListener() {
    return okListener;
  }

  public OnClickListener getDismissListener() {
    return dismissListener;
  }

  public void setOkListener(OnClickListener okListener) {
    this.okListener = okListener;
  }

  public void setDismissListener(OnClickListener dismissListener) {
    this.dismissListener = dismissListener;
  }

  public boolean isDismissable() {
    return true;
  }
}
