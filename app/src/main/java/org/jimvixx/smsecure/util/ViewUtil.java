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

package org.jimvixx.smsecure.util;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.text.TextUtils.TruncateAt;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.TextView;

import androidx.annotation.IdRes;
import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import org.jimvixx.smsecure.util.concurrent.ListenableFuture;
import org.jimvixx.smsecure.util.concurrent.SettableFuture;
import org.jimvixx.smsecure.util.views.Stub;

import java.util.Objects;

public class ViewUtil {

  public static void setBackground(@NonNull View v, @Nullable Drawable drawable) {
    v.setBackground(drawable);
  }

  public static CharSequence ellipsize(@Nullable CharSequence text, @NonNull TextView view) {
    if (TextUtils.isEmpty(text) || view.getWidth() == 0 || view.getEllipsize() != TruncateAt.END) {
      return text;
    }

    return TextUtils.ellipsize(
            text,
            view.getPaint(),
            view.getWidth() - view.getPaddingRight() - view.getPaddingLeft(),
            TruncateAt.END
    );
  }

  public static <T extends View> @NonNull T inflateStub(@NonNull View parent,
                                                        @IdRes int stubId,
                                                        @NonNull Class<T> type) {
    ViewStub stub = parent.findViewById(stubId);
    if (stub == null) {
      throw new IllegalStateException("ViewStub not found: id=" + stubId + " in " + parent);
    }

    View inflated = stub.inflate();
    T casted = type.cast(inflated);

    // satisfy @NonNull analysis and fail fast if something is wrong
    return Objects.requireNonNull(casted, "Inflated view is null for stubId=" + stubId);
  }

  public static <T extends View> T findById(@NonNull View parent, @IdRes int resId) {
    return parent.findViewById(resId);
  }

  @NonNull
  public static <T extends View> T requireById(@NonNull View parent, @IdRes int resId) {
    T view = parent.findViewById(resId);
    if (view == null) {
      throw new IllegalStateException("Required view not found: " +
              parent.getResources().getResourceName(resId));
    }
    return view;
  }

  @NonNull
  public static <T extends View> T requireById(@NonNull Activity activity, @IdRes int resId) {
    T view = activity.findViewById(resId);
    if (view == null) {
      throw new IllegalStateException("Required view not found: " +
              activity.getResources().getResourceName(resId));
    }
    return view;
  }

  public static <T extends View> Stub<T> findStubById(@NonNull Activity parent,
                                                      @IdRes int resId,
                                                      @NonNull Class<T> type) {
    return new Stub<>(parent.findViewById(resId), type);
  }

  private static Animation getAlphaAnimation(float from, float to, int duration) {
    Animation anim = new AlphaAnimation(from, to);
    anim.setInterpolator(new FastOutSlowInInterpolator());
    anim.setDuration(duration);
    return anim;
  }

  public static void fadeIn(@NonNull View view, int duration) {
    animateIn(view, getAlphaAnimation(0f, 1f, duration));
  }

  public static ListenableFuture<Boolean> fadeOut(@NonNull View view, int duration) {
    return fadeOut(view, duration, View.GONE);
  }

  public static ListenableFuture<Boolean> fadeOut(@NonNull View view, int duration, int visibility) {
    return animateOut(view, getAlphaAnimation(1f, 0f, duration), visibility);
  }

  public static ListenableFuture<Boolean> animateOut(@NonNull View view,
                                                     @NonNull Animation animation,
                                                     int visibility) {
    // Use generics to avoid raw-type warnings.
    final SettableFuture<Boolean> future = new SettableFuture<>();

    if (view.getVisibility() == visibility) {
      future.set(Boolean.TRUE);
      return future;
    }

    view.clearAnimation();
    animation.reset();
    animation.setStartTime(0);

    animation.setAnimationListener(new Animation.AnimationListener() {
      @Override
      public void onAnimationStart(Animation animation) {
        // No-op
      }

      @Override
      public void onAnimationRepeat(Animation animation) {
        // No-op
      }

      @Override
      public void onAnimationEnd(Animation animation) {
        view.setVisibility(visibility);
        future.set(Boolean.TRUE);
      }
    });

    view.startAnimation(animation);
    return future;
  }

  public static void animateIn(@NonNull View view, @NonNull Animation animation) {
    if (view.getVisibility() == View.VISIBLE) return;

    view.clearAnimation();
    animation.reset();
    animation.setStartTime(0);
    view.setVisibility(View.VISIBLE);
    view.startAnimation(animation);
  }

  @SuppressWarnings("unchecked")
  public static <T extends View> T inflate(@NonNull LayoutInflater inflater,
                                           @NonNull ViewGroup parent,
                                           @LayoutRes int layoutResId) {
    return (T) inflater.inflate(layoutResId, parent, false);
  }

  public static void setTextViewGravityStart(@NonNull TextView textView, @NonNull Context context) {
    textView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
  }

  public static void mirrorIfRtl(@NonNull View view, @NonNull Context context) {
    if (DynamicLanguage.getLayoutDirection(context) == View.LAYOUT_DIRECTION_RTL) {
      view.setScaleX(-1.0f);
    }
  }

  public static int dpToPx(@NonNull Resources res, float dp) {
    return Math.round(dp * res.getDisplayMetrics().density);
  }

  public static int pxToDp(@NonNull Resources res, float px) {
    return Math.round(px / res.getDisplayMetrics().density);
  }
}
