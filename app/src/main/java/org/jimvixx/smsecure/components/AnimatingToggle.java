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
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.util.ViewUtil;

public class AnimatingToggle extends FrameLayout {

  private final Animation inAnimation;
  private final Animation outAnimation;
  private View current;

  public AnimatingToggle(Context context) {
    this(context, null);
  }

  public AnimatingToggle(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public AnimatingToggle(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    this.outAnimation = AnimationUtils.loadAnimation(getContext(), R.anim.animation_toggle_out);
    this.inAnimation = AnimationUtils.loadAnimation(getContext(), R.anim.animation_toggle_in);
    this.outAnimation.setInterpolator(new FastOutSlowInInterpolator());
    this.inAnimation.setInterpolator(new FastOutSlowInInterpolator());
  }

  @Override
  public void addView(@NonNull View child, int index, ViewGroup.LayoutParams params) {
    super.addView(child, index, params);

    if (getChildCount() == 1) {
      current = child;
      child.setVisibility(View.VISIBLE);
    } else {
      child.setVisibility(View.GONE);
    }
    child.setClickable(false);
  }

  public void display(@Nullable View view) {
    if (view == current) return;
    if (current != null) ViewUtil.animateOut(current, outAnimation, View.GONE);
    if (view != null) ViewUtil.animateIn(view, inAnimation);

    current = view;
  }

  public void displayQuick(@Nullable View view) {
    if (view == current) return;
    if (current != null) current.setVisibility(View.GONE);
    if (view != null) view.setVisibility(View.VISIBLE);

    current = view;
  }
}
