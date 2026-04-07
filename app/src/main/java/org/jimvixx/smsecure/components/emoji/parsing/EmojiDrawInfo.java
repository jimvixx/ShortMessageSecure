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

package org.jimvixx.smsecure.components.emoji.parsing;


import androidx.annotation.NonNull;

public class EmojiDrawInfo {

  private final EmojiPageBitmap page;
  private final int index;

  public EmojiDrawInfo(final @NonNull EmojiPageBitmap page, final int index) {
    this.page = page;
    this.index = index;
  }

  public @NonNull EmojiPageBitmap getPage() {
    return page;
  }

  public int getIndex() {
    return index;
  }

  @NonNull
  @Override
  public String toString() {
    return "DrawInfo{" +
            "page=" + page +
            ", index=" + index +
            '}';
  }
}
