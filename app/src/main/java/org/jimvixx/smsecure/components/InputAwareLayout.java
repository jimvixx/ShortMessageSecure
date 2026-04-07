/*
 * Copyright (C) 2011 Whisper Systems
 * Copyright (C) 2013 Open Whisper Systems
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
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jimvixx.smsecure.components.KeyboardAwareLinearLayout.OnKeyboardShownListener;
import org.jimvixx.smsecure.util.ServiceUtil;

import java.util.concurrent.atomic.AtomicBoolean;

public class InputAwareLayout extends KeyboardAwareLinearLayout implements OnKeyboardShownListener {

  private static final int FALLBACK_HIDE_DELAY_MS = 200;

  private InputView current;

  public InputAwareLayout(Context context) {
    this(context, null);
  }

  public InputAwareLayout(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public InputAwareLayout(Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);
    addOnKeyboardShownListener(this);
  }

  @Override
  public void onKeyboardShown() {
    hideAttachedInput(true);
  }

  public void show(@NonNull final EditText imeTarget, @NonNull final InputView input) {
    if (isKeyboardOpen()) {
      hideSoftkey(imeTarget, () -> {
        hideAttachedInput(true);
        showInputWithHeight(input);
        current = input;
      });
    } else {
      if (current != null) current.hide(true);
      showInputWithHeight(input);
      current = input;
    }
  }

  public InputView getCurrentInput() {
    return current;
  }

  public void hideCurrentInput(EditText imeTarget) {
    if (isKeyboardOpen()) hideSoftkey(imeTarget, null);
    else                  hideAttachedInput(false);
  }

  public void hideAttachedInput(boolean instant) {
    if (current != null) current.hide(instant);
    current = null;
  }

  public boolean isInputOpen() {
    return isKeyboardOpen() || (current != null && current.isShowing());
  }

  public void showSoftkey(final EditText inputTarget) {
    postOnKeyboardOpen(() -> hideAttachedInput(true));

    inputTarget.post(() -> {
      inputTarget.requestFocus();
      ServiceUtil.getInputMethodManager(inputTarget.getContext()).showSoftInput(inputTarget, 0);
    });
  }

  private void showInputWithHeight(@NonNull InputView input) {
    int height = getKeyboardHeight();
    if (height <= 0) {
      height = getResources().getDimensionPixelSize(
              org.jimvixx.smsecure.R.dimen.default_custom_keyboard_size
      );
    }

    if (input instanceof View v) {
      ViewGroup.LayoutParams lp = v.getLayoutParams();
      if (lp != null && lp.height != height) {
        lp.height = height;
        v.setLayoutParams(lp);
      }
      v.setVisibility(View.VISIBLE);
      v.requestLayout();
    }

    input.show(height, current != null);
  }

  private void hideSoftkey(final EditText inputTarget, @Nullable Runnable runAfterClose) {
    if (runAfterClose != null) {
      final AtomicBoolean ran = new AtomicBoolean(false);

      Runnable once = () -> {
        if (ran.compareAndSet(false, true)) {
          runAfterClose.run();
        }
      };

      postOnKeyboardClose(once);

      // Fallback: if we never receive a close signal (OEM/insets quirks), still proceed.
      postDelayed(once, FALLBACK_HIDE_DELAY_MS);
    }

    ServiceUtil.getInputMethodManager(inputTarget.getContext())
            .hideSoftInputFromWindow(inputTarget.getWindowToken(), 0);
  }

  public interface InputView {
    void show(int height, boolean immediate);
    void hide(boolean immediate);
    boolean isShowing();
  }
}
