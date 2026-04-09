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

package org.jimvixx.smsecure.util;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.HashMap;
import java.util.Map;

public class StickyHeaderDecoration<T extends RecyclerView.ViewHolder> extends RecyclerView.ItemDecoration {

  private static final long NO_HEADER_ID = -1L;

  private final Map<Long, T> headerCache;
  private final StickyHeaderAdapter<T> adapter;
  private final boolean renderInline;
  private final boolean sticky;

  public StickyHeaderDecoration(@NonNull StickyHeaderAdapter<T> adapter, boolean renderInline, boolean sticky) {
    this.adapter = adapter;
    this.headerCache = new HashMap<>();
    this.renderInline = renderInline;
    this.sticky = sticky;
  }

  @Override
  public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                             @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
    int position = parent.getChildAdapterPosition(view);
    int headerHeight = 0;

    if (position != RecyclerView.NO_POSITION && hasHeader(parent, adapter, position)) {
      View header = getHeader(parent, adapter, position).itemView;
      headerHeight = getHeaderHeightForLayout(header);
    }

    outRect.set(0, headerHeight, 0, 0);
  }

  protected boolean hasHeader(@NonNull RecyclerView parent,
                              @NonNull StickyHeaderAdapter<T> adapter,
                              int adapterPos) {
    boolean isReverse = isReverseLayout(parent);

    int itemCount = adapter.getItemCount();

    if ((isReverse && adapterPos == itemCount - 1 && adapter.getHeaderId(adapterPos) != NO_HEADER_ID) ||
            (!isReverse && adapterPos == 0)) {
      return true;
    }

    int previous = adapterPos + (isReverse ? 1 : -1);
    long headerId = adapter.getHeaderId(adapterPos);
    long previousHeaderId = adapter.getHeaderId(previous);

    return headerId != NO_HEADER_ID &&
            previousHeaderId != NO_HEADER_ID &&
            headerId != previousHeaderId;
  }

  protected @NonNull T getHeader(@NonNull RecyclerView parent,
                                 @NonNull StickyHeaderAdapter<T> adapter,
                                 int position) {
    final long key = adapter.getHeaderId(position);

    T cached = headerCache.get(key);
    if (cached != null) return cached;

    final T holder = adapter.onCreateHeaderViewHolder(parent);
    final View header = holder.itemView;

    adapter.onBindHeaderViewHolder(holder, position);

    int widthSpec = View.MeasureSpec.makeMeasureSpec(parent.getWidth(), View.MeasureSpec.EXACTLY);
    int heightSpec = View.MeasureSpec.makeMeasureSpec(parent.getHeight(), View.MeasureSpec.UNSPECIFIED);

    int childWidth = ViewGroup.getChildMeasureSpec(
            widthSpec,
            parent.getPaddingLeft() + parent.getPaddingRight(),
            header.getLayoutParams().width);

    int childHeight = ViewGroup.getChildMeasureSpec(
            heightSpec,
            parent.getPaddingTop() + parent.getPaddingBottom(),
            header.getLayoutParams().height);

    header.measure(childWidth, childHeight);
    header.layout(0, 0, header.getMeasuredWidth(), header.getMeasuredHeight());

    headerCache.put(key, holder);
    return holder;
  }

  @Override
  public void onDrawOver(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
    final int count = parent.getChildCount();

    for (int layoutPos = 0; layoutPos < count; layoutPos++) {
      final View child = parent.getChildAt(translatedChildPosition(parent, layoutPos));
      final int adapterPos = parent.getChildAdapterPosition(child);

      if (adapterPos != RecyclerView.NO_POSITION &&
              ((layoutPos == 0 && sticky) || hasHeader(parent, adapter, adapterPos))) {

        View header = getHeader(parent, adapter, adapterPos).itemView;
        c.save();
        final int left = child.getLeft();
        final int top = getHeaderTop(parent, child, header, adapterPos, layoutPos);
        c.translate(left, top);
        header.draw(c);
        c.restore();
      }
    }
  }

  protected int getHeaderTop(@NonNull RecyclerView parent, @NonNull View child, @NonNull View header,
                             int adapterPos, int layoutPos) {
    int headerHeight = getHeaderHeightForLayout(header);
    int top = getChildY(parent, child) - headerHeight;

    if (layoutPos == 0) {
      final int count = parent.getChildCount();
      final long currentId = adapter.getHeaderId(adapterPos);

      for (int i = 1; i < count; i++) {
        int adapterPosHere = parent.getChildAdapterPosition(parent.getChildAt(translatedChildPosition(parent, i)));
        if (adapterPosHere != RecyclerView.NO_POSITION) {
          long nextId = adapter.getHeaderId(adapterPosHere);
          if (nextId != currentId) {
            final View next = parent.getChildAt(translatedChildPosition(parent, i));
            final int offset = getChildY(parent, next) -
                    (headerHeight + getHeader(parent, adapter, adapterPosHere).itemView.getHeight());
            if (offset < 0) return offset;
            break;
          }
        }
      }

      if (sticky) top = Math.max(0, top);
    }

    return top;
  }

  private int translatedChildPosition(@NonNull RecyclerView parent, int position) {
    return isReverseLayout(parent) ? parent.getChildCount() - 1 - position : position;
  }

  private int getChildY(@NonNull RecyclerView parent, @NonNull View child) {
    return child.getTop() + Math.round(child.getTranslationY());
  }

  protected int getHeaderHeightForLayout(@NonNull View header) {
    return renderInline ? 0 : header.getHeight();
  }

  private boolean isReverseLayout(@NonNull RecyclerView parent) {
    RecyclerView.LayoutManager lm = parent.getLayoutManager();
    return (lm instanceof LinearLayoutManager) && ((LinearLayoutManager) lm).getReverseLayout();
  }

  public interface StickyHeaderAdapter<T extends RecyclerView.ViewHolder> {
    long getHeaderId(int position);

    int getItemCount();

    @NonNull
    T onCreateHeaderViewHolder(@NonNull ViewGroup parent);

    void onBindHeaderViewHolder(@NonNull T viewHolder, int position);
  }
}
