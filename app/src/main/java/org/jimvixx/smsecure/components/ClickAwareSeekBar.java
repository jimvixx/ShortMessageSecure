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

package org.jimvixx.smsecure.components;

import android.content.Context;
import android.util.AttributeSet;

/**
 * SeekBar with an explicit performClick() override.
 * This satisfies accessibility + lint when we attach an OnTouchListener.
 */
public class ClickAwareSeekBar extends androidx.appcompat.widget.AppCompatSeekBar {

  public ClickAwareSeekBar(Context context) {
    super(context);
  }

  public ClickAwareSeekBar(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  public ClickAwareSeekBar(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  @Override
  public boolean performClick() {
    // Let the framework send accessibility events properly.
    return super.performClick();
  }
}
