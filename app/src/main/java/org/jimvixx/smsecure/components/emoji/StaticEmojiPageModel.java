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

package org.jimvixx.smsecure.components.emoji;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class StaticEmojiPageModel implements EmojiPageModel {
  @DrawableRes
  private final int categoryIcon;
  @NonNull
  private final String[] emoji;
  @Nullable
  private final String sprite;

  public StaticEmojiPageModel(@DrawableRes int categoryIcon, @NonNull String[] emoji, @Nullable String sprite) {
    this.categoryIcon = categoryIcon;
    this.emoji = emoji;
    this.sprite = sprite;
  }

  public @DrawableRes int getCategoryIcon() {
    return categoryIcon;
  }

  @NonNull
  public String[] getEmoji() {
    return emoji;
  }

  @Override
  public boolean hasSpriteMap() {
    return sprite != null;
  }

  @Override
  @Nullable
  public String getSprite() {
    return sprite;
  }

  @Override
  public boolean isDynamic() {
    return false;
  }
}
