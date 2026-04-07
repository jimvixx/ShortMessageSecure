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

import android.content.Context;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageButton;

/**
 * An ImageButton that supports key-repeat behavior while it is pressed.
 */
public class RepeatableImageKey extends AppCompatImageButton {

  private final Repeater repeater = new Repeater();
  private @Nullable KeyEventListener listener;
  private boolean repeating;

  public RepeatableImageKey(@NonNull Context context) {
    super(context);
    init();
  }

  public RepeatableImageKey(@NonNull Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    init();
  }

  public RepeatableImageKey(@NonNull Context context,
                            @Nullable AttributeSet attrs,
                            int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    init();
  }

  private void init() {
    setOnClickListener(v -> notifyListener());
    setOnTouchListener(this::onRepeatableTouch);
  }

  public void setOnKeyEventListener(@Nullable KeyEventListener listener) {
    this.listener = listener;
  }

  private void notifyListener() {
    KeyEventListener l = this.listener;
    if (l != null) {
      l.onKeyEvent();
    }
  }

  private boolean onRepeatableTouch(@NonNull View view, @NonNull MotionEvent event) {
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        repeating = true;
        view.postDelayed(repeater, ViewConfiguration.getKeyRepeatTimeout());
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        return true;

      case MotionEvent.ACTION_UP:
        stopRepeating(view);
        view.performClick();
        return true;

      case MotionEvent.ACTION_CANCEL:
        stopRepeating(view);
        return true;

      default:
        return false;
    }
  }

  private void stopRepeating(@NonNull View view) {
    repeating = false;
    view.removeCallbacks(repeater);
  }

  @Override
  public boolean performClick() {
    return super.performClick();
  }

  public interface KeyEventListener {
    void onKeyEvent();
  }

  private final class Repeater implements Runnable {
    @Override
    public void run() {
      if (!repeating) {
        return;
      }
      notifyListener();
      postDelayed(this, ViewConfiguration.getKeyRepeatDelay());
    }
  }
}
