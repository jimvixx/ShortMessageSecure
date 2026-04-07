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

package org.jimvixx.smsecure.components.emoji;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.AppCompatImageButton;

import org.jimvixx.smsecure.R;

public class EmojiToggle extends AppCompatImageButton implements EmojiDrawer.EmojiDrawerListener {

  private @Nullable Drawable emojiToggle;
  private @Nullable Drawable imeToggle;

  public EmojiToggle(@NonNull Context context) {
    super(context);
    initialize();
  }

  public EmojiToggle(@NonNull Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    initialize();
  }

  public EmojiToggle(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);
    initialize();
  }

  public void setToEmoji() {
    setImageDrawable(emojiToggle);
  }

  public void setToIme() {
    setImageDrawable(imeToggle);
  }

  private void initialize() {

    // Hardcoded drawables (no theme / no attrs)
    emojiToggle = AppCompatResources.getDrawable(getContext(), R.drawable.ic_emoticon_outline);
    imeToggle = AppCompatResources.getDrawable(getContext(), R.drawable.ic_keyboard);

    setToEmoji();
  }

  public void attach(@NonNull EmojiDrawer drawer) {
    drawer.setDrawerListener(this);
  }

  @Override
  public void onShown() {
    setToIme();
  }

  @Override
  public void onHidden() {
    setToEmoji();
  }
}