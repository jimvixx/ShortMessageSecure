/*
 * Modified version of
 * https://github.com/AndroidDeveloperLB/LollipopContactsRecyclerViewFastScroller
 *
 * Their license:
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jimvixx.smsecure.components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.util.Util;
import org.jimvixx.smsecure.util.ViewUtil;

public class RecyclerViewFastScroller extends LinearLayout {

  private static final int BUBBLE_ANIMATION_DURATION = 100;
  private static final int TRACK_SNAP_RANGE          = 5;

  private final @NonNull TextView bubble;
  private final @NonNull View     handle;

  private @Nullable RecyclerView recyclerView;

  private int height;
  private @Nullable ObjectAnimator currentAnimator;

  private final RecyclerView.OnScrollListener onScrollListener = new RecyclerView.OnScrollListener() {
    @Override
    public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
      if (handle.isSelected()) return;

      final int offset      = recyclerView.computeVerticalScrollOffset();
      final int range       = recyclerView.computeVerticalScrollRange();
      final int extent      = recyclerView.computeVerticalScrollExtent();
      final int offsetRange = Math.max(range - extent, 1);

      setBubbleAndHandlePosition((float) Util.clamp(offset, 0, offsetRange) / offsetRange);
    }
  };

  public interface FastScrollAdapter {
    CharSequence getBubbleText(int pos);
  }

  public RecyclerViewFastScroller(@NonNull Context context) {
    this(context, null);
  }

  public RecyclerViewFastScroller(@NonNull Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    setOrientation(HORIZONTAL);
    setClipChildren(false);
    setScrollContainer(true);

    inflate(context, R.layout.recycler_view_fast_scroller, this);
    bubble = ViewUtil.findById(this, R.id.fastscroller_bubble);
    handle = ViewUtil.findById(this, R.id.fastscroller_handle);

    // Make accessibility tools treat this as clickable when appropriate.
    setClickable(true);
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    height = h;
  }

  @Override
  public boolean onTouchEvent(@NonNull MotionEvent event) {
    final int action = event.getActionMasked();

    switch (action) {
      case MotionEvent.ACTION_DOWN: {
        // If the touch isn't on (or near) the handle, ignore it.
        final float handleX = handle.getX();
        final float handleY = handle.getY();

        if (event.getX() < handleX - handle.getPaddingLeft() ||
                event.getY() < handleY - handle.getPaddingTop() ||
                event.getY() > handleY + handle.getHeight() + handle.getPaddingBottom()) {
          return false;
        }

        if (currentAnimator != null) currentAnimator.cancel();
        if (bubble.getVisibility() != VISIBLE) showBubble();

        handle.setSelected(true);
        // Ensure we keep receiving subsequent MOVE/UP events.
        getParent().requestDisallowInterceptTouchEvent(true);

        // Treat DOWN as start of drag; fall-through to MOVE handling.
        // no break
      }

      case MotionEvent.ACTION_MOVE: {
        final float y = event.getY();
        setBubbleAndHandlePosition(y / Math.max(height, 1));
        setRecyclerViewPosition(y);
        return true;
      }

      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL: {
        handle.setSelected(false);
        hideBubble();
        getParent().requestDisallowInterceptTouchEvent(false);

        // If it was a tap (not a drag), performClick() should be invoked for accessibility.
        if (action == MotionEvent.ACTION_UP) {
          performClick();
        }
        return true;
      }
    }

    return super.onTouchEvent(event);
  }

  @Override
  public boolean performClick() {
    // Call super for accessibility events (TalkBack, etc).
    return super.performClick();
  }

  public void setRecyclerView(@NonNull RecyclerView recyclerView) {
    if (this.recyclerView != null) {
      this.recyclerView.removeOnScrollListener(onScrollListener);
    }

    this.recyclerView = recyclerView;
    recyclerView.addOnScrollListener(onScrollListener);

    recyclerView.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
      @Override
      public boolean onPreDraw() {
        recyclerView.getViewTreeObserver().removeOnPreDrawListener(this);
        if (handle.isSelected()) return true;

        final int verticalScrollOffset = recyclerView.computeVerticalScrollOffset();
        final int verticalScrollRange  = recyclerView.computeVerticalScrollRange();

        // Avoid division by zero / negative.
        final int denom = Math.max(verticalScrollRange - height, 1);
        final float proportion = (float) verticalScrollOffset / (float) denom;

        setBubbleAndHandlePosition(proportion);
        return true;
      }
    });
  }

  @Override
  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    if (recyclerView != null) {
      recyclerView.removeOnScrollListener(onScrollListener);
    }
  }

  private void setRecyclerViewPosition(float y) {
    final RecyclerView rv = this.recyclerView;
    if (rv == null) return;

    final RecyclerView.Adapter<?> adapter = rv.getAdapter();
    if (adapter == null) return;

    final int itemCount = adapter.getItemCount();
    if (itemCount <= 0) return;

    final RecyclerView.LayoutManager lm = rv.getLayoutManager();
    if (!(lm instanceof LinearLayoutManager)) return;

    float proportion;
    final float handleY = handle.getY();

    if (handleY <= 0f) {
      proportion = 0f;
    } else if (handleY + handle.getHeight() >= height - TRACK_SNAP_RANGE) {
      proportion = 1f;
    } else {
      proportion = y / (float) Math.max(height, 1);
    }

    final int targetPos = Util.clamp((int) (proportion * (float) itemCount), 0, itemCount - 1);
    ((LinearLayoutManager) lm).scrollToPositionWithOffset(targetPos, 0);

    // Bubble label is optional: only update if adapter supports it.
    if (adapter instanceof FastScrollAdapter) {
      CharSequence bubbleText = ((FastScrollAdapter) adapter).getBubbleText(targetPos);
      bubble.setText(bubbleText);
    }
  }

  /**
   * @param proportion 0..1, not pixels.
   */
  private void setBubbleAndHandlePosition(float proportion) {
    final int safeHeight = Math.max(height, 1);

    final int handleHeight = handle.getHeight();
    final int bubbleHeight = bubble.getHeight();

    final int handleY = Util.clamp((int) ((safeHeight - handleHeight) * proportion), 0, safeHeight - handleHeight);

    handle.setY(handleY);

    final int bubbleY = Util.clamp(
            handleY - bubbleHeight - bubble.getPaddingBottom() + handleHeight,
            0,
            safeHeight - bubbleHeight
    );
    bubble.setY(bubbleY);
  }

  private void showBubble() {
    bubble.setVisibility(VISIBLE);

    if (currentAnimator != null) currentAnimator.cancel();
    currentAnimator = ObjectAnimator.ofFloat(bubble, View.ALPHA, 0f, 1f)
            .setDuration(BUBBLE_ANIMATION_DURATION);
    currentAnimator.start();
  }

  private void hideBubble() {
    if (currentAnimator != null) currentAnimator.cancel();

    currentAnimator = ObjectAnimator.ofFloat(bubble, View.ALPHA, 1f, 0f)
            .setDuration(BUBBLE_ANIMATION_DURATION);

    currentAnimator.addListener(new AnimatorListenerAdapter() {
      @Override
      public void onAnimationEnd(@NonNull Animator animation) {
        bubble.setVisibility(INVISIBLE);
        currentAnimator = null;
      }

      @Override
      public void onAnimationCancel(@NonNull Animator animation) {
        bubble.setVisibility(INVISIBLE);
        currentAnimator = null;
      }
    });

    currentAnimator.start();
  }
}
