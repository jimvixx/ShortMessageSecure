/*
 * Copyright (C) 2014 Open Whisper Systems
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
import android.content.SharedPreferences;
import android.graphics.Rect;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.LinearLayoutCompat;

import android.util.AttributeSet;
import org.jimvixx.smsecure.logging.Log;
import android.view.Surface;
import android.view.ViewTreeObserver;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.util.ServiceUtil;
import org.jimvixx.smsecure.util.Util;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * LinearLayout that reports when it thinks a soft keyboard has been opened
 * and provides an estimated keyboard height for custom input panels (emoji, etc).
 */
public class KeyboardAwareLinearLayout extends LinearLayoutCompat {

  private static final String TAG = KeyboardAwareLinearLayout.class.getSimpleName();
  private static final String PREFS_NAME = "org.jimvixx.smsecure.keyboard";
  private static final String KEY_KEYBOARD_HEIGHT_PORTRAIT = "keyboard_height_portrait";

  private final Rect rect = new Rect();

  private final Set<OnKeyboardHiddenListener> hiddenListeners = new HashSet<>();
  private final Set<OnKeyboardShownListener>  shownListeners  = new HashSet<>();

  private int minKeyboardSize;
  private final int minCustomKeyboardSize;
  private final int defaultCustomKeyboardSize;
  private final int minCustomKeyboardTopMargin;

  private boolean keyboardOpen = false;
  private int     rotation     = -1;

  // Insets-based state
  private int lastImeBottom = 0;

  // Fallback-based state
  private int lastFallbackHeight = 0;

  // Last known "real" keyboard height (from insets or fallback).
  private int lastKnownKeyboardHeight = 0;

  public KeyboardAwareLinearLayout(Context context) {
    this(context, null);
  }

  public KeyboardAwareLinearLayout(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public KeyboardAwareLinearLayout(Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);

    // Initialize ALL final fields here (fixes "might not have been initialized").
    minKeyboardSize            = getResources().getDimensionPixelSize(R.dimen.min_keyboard_size);
    minCustomKeyboardSize      = getResources().getDimensionPixelSize(R.dimen.min_custom_keyboard_size);
    defaultCustomKeyboardSize  = getResources().getDimensionPixelSize(R.dimen.default_custom_keyboard_size);
    minCustomKeyboardTopMargin = getResources().getDimensionPixelSize(R.dimen.min_custom_keyboard_top_margin);

    // Primary path: IME insets (public API).
    ViewCompat.setOnApplyWindowInsetsListener(this, (v, insets) -> {
      boolean imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
      int imeBottom      = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;

      onKeyboardFromInsets(imeVisible, Math.max(imeBottom, 0));

      return insets;
    });

    // Fallback path: visible display frame heuristic.
    getViewTreeObserver().addOnGlobalLayoutListener(globalLayoutListener);
  }

  private void onKeyboardFromInsets(boolean imeVisible, int imeBottom) {
    // Keep original behavior for landscape.
    if (isLandscape()) {
      if (keyboardOpen) onKeyboardClose();
      lastImeBottom = 0;
      return;
    }

    if (imeVisible && imeBottom > 0) {
      lastImeBottom = imeBottom;
      lastKnownKeyboardHeight = imeBottom;

      if (getKeyboardPortraitHeight() != imeBottom) {
        setKeyboardPortraitHeight(imeBottom);
      }
    }

    // State comes from visibility, not from a size threshold.
    if (imeVisible) {
      if (!keyboardOpen) onKeyboardOpen(getKeyboardHeight());
    } else {
      if (keyboardOpen) onKeyboardClose();
    }
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    ViewCompat.requestApplyInsets(this);
  }

  @Override
  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    try {
      getViewTreeObserver().removeOnGlobalLayoutListener(globalLayoutListener);
    } catch (Throwable ignored) {
      // ignore
    }
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    updateRotation();
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
  }

  private void updateRotation() {
    int oldRotation = rotation;
    rotation = getDeviceRotation();
    if (oldRotation != rotation) {
      Log.w(TAG, "rotation changed");
      if (keyboardOpen) onKeyboardClose();
    }
  }

  // Fallback listener: compute keyboard height as "root height - visible frame height".
  private final ViewTreeObserver.OnGlobalLayoutListener globalLayoutListener =
          () -> {
            if (isLandscape()) return;

            int rootHeight = getRootView() != null ? getRootView().getHeight() : 0;
            if (rootHeight <= 0) return;

            getWindowVisibleDisplayFrame(rect);
            int visibleHeight = rect.bottom - rect.top;
            int diff = Math.max(0, rootHeight - visibleHeight);

            int height = diff > minKeyboardSize ? diff : 0;

            if (height == lastFallbackHeight) return;
            lastFallbackHeight = height;

            // If insets already indicate keyboard, prefer that.
            if (lastImeBottom > 0) return;

            if (height > 0) {
              lastKnownKeyboardHeight = height;

              if (getKeyboardPortraitHeight() != height) {
                setKeyboardPortraitHeight(height);
              }
              if (!keyboardOpen) onKeyboardOpen(height);
            } else {
              if (keyboardOpen) onKeyboardClose();
            }
          };

  protected void onKeyboardOpen(int keyboardHeight) {
    Log.w(TAG, String.format(Locale.ROOT, "onKeyboardOpen(%d)", keyboardHeight));
    keyboardOpen = true;
    notifyShownListeners();
  }

  protected void onKeyboardClose() {
    Log.w(TAG, "onKeyboardClose()");
    keyboardOpen = false;
    notifyHiddenListeners();
  }

  public boolean isKeyboardOpen() {
    return keyboardOpen;
  }

  /**
   * Height to use for custom input panels (emoji drawer, etc).
   * If IME hasn't been shown yet, we fall back to a reasonable portion of the screen.
   */
  public int getKeyboardHeight() {
    if (isLandscape()) return getKeyboardLandscapeHeight();

    // 1) Prefer last known real height (insets/fallback).
    int h = lastKnownKeyboardHeight;

    // 2) Then persisted portrait height.
    if (h <= 0) h = getKeyboardPortraitHeight();

    // 3) If we still don't have a good value (first run), use a reasonable default (~45% of root).
    if (h <= 0 || h == defaultCustomKeyboardSize || h < minCustomKeyboardSize) {
      h = getReasonableDefaultKeyboardHeight();
    }

    return h;
  }

  private int getReasonableDefaultKeyboardHeight() {
    int rootH = getRootView() != null ? getRootView().getHeight() : 0;
    if (rootH <= 0) return defaultCustomKeyboardSize;

    int h = (int) (rootH * 0.45f);
    int max = Math.max(minCustomKeyboardSize, rootH - minCustomKeyboardTopMargin);

    return Util.clamp(h, minCustomKeyboardSize, max);
  }

  public boolean isLandscape() {
    int r = getDeviceRotation();
    return r == Surface.ROTATION_90 || r == Surface.ROTATION_270;
  }

  private int getDeviceRotation() {
    return ServiceUtil.getWindowManager(getContext()).getDefaultDisplay().getRotation();
  }

  private int getKeyboardLandscapeHeight() {
    return Math.max(getHeight(), getRootView().getHeight()) / 2;
  }

  private SharedPreferences getPrefs() {
    return getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  private int getKeyboardPortraitHeight() {
    int keyboardHeight = getPrefs().getInt(KEY_KEYBOARD_HEIGHT_PORTRAIT, defaultCustomKeyboardSize);
    int rootH = getRootView() != null ? getRootView().getHeight() : 0;
    int max = Math.max(minCustomKeyboardSize, rootH - minCustomKeyboardTopMargin);

    return Util.clamp(keyboardHeight, minCustomKeyboardSize, max);
  }

  private void setKeyboardPortraitHeight(int height) {
    getPrefs().edit().putInt(KEY_KEYBOARD_HEIGHT_PORTRAIT, height).apply();
  }

  public void postOnKeyboardClose(final Runnable runnable) {
    if (keyboardOpen) {
      addOnKeyboardHiddenListener(new OnKeyboardHiddenListener() {
        @Override public void onKeyboardHidden() {
          removeOnKeyboardHiddenListener(this);
          runnable.run();
        }
      });
    } else {
      runnable.run();
    }
  }

  public void postOnKeyboardOpen(final Runnable runnable) {
    if (!keyboardOpen) {
      addOnKeyboardShownListener(new OnKeyboardShownListener() {
        @Override public void onKeyboardShown() {
          removeOnKeyboardShownListener(this);
          runnable.run();
        }
      });
    } else {
      runnable.run();
    }
  }

  public void addOnKeyboardHiddenListener(@NonNull OnKeyboardHiddenListener listener) {
    hiddenListeners.add(listener);
  }

  public void removeOnKeyboardHiddenListener(@NonNull OnKeyboardHiddenListener listener) {
    hiddenListeners.remove(listener);
  }

  public void addOnKeyboardShownListener(@NonNull OnKeyboardShownListener listener) {
    shownListeners.add(listener);
  }

  public void removeOnKeyboardShownListener(@NonNull OnKeyboardShownListener listener) {
    shownListeners.remove(listener);
  }

  private void notifyHiddenListeners() {
    final Set<OnKeyboardHiddenListener> listeners = new HashSet<>(hiddenListeners);
    for (OnKeyboardHiddenListener listener : listeners) {
      listener.onKeyboardHidden();
    }
  }

  private void notifyShownListeners() {
    final Set<OnKeyboardShownListener> listeners = new HashSet<>(shownListeners);
    for (OnKeyboardShownListener listener : listeners) {
      listener.onKeyboardShown();
    }
  }

  public interface OnKeyboardHiddenListener {
    void onKeyboardHidden();
  }

  public interface OnKeyboardShownListener {
    void onKeyboardShown();
  }
}
