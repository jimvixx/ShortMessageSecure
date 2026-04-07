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

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;

import org.jimvixx.smsecure.R;

public class EmojiPageView extends FrameLayout {

  private final GridView grid;
  private EmojiPageModel model;
  private EmojiSelectionListener listener;

  public EmojiPageView(Context context) {
    this(context, null);
  }

  public EmojiPageView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public EmojiPageView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    final View view = LayoutInflater.from(getContext()).inflate(R.layout.emoji_grid_layout, this, true);
    grid = view.findViewById(R.id.emoji);
    grid.setColumnWidth(getResources().getDimensionPixelSize(R.dimen.emoji_drawer_size) + 2 * getResources().getDimensionPixelSize(R.dimen.emoji_drawer_item_padding));
    grid.setOnItemClickListener((parent, view1, position, id) -> {
      if (listener != null) listener.onEmojiSelected(((EmojiView) view1).getEmoji());
    });
  }

  public void onSelected() {
    if (model.isDynamic() && grid != null && grid.getAdapter() != null) {
      ((EmojiGridAdapter) grid.getAdapter()).notifyDataSetChanged();
    }
  }

  public void setModel(EmojiPageModel model) {
    this.model = model;
    grid.setAdapter(new EmojiGridAdapter(getContext(), model));
  }

  public void setEmojiSelectedListener(EmojiSelectionListener listener) {
    this.listener = listener;
  }

  public interface EmojiSelectionListener {
    void onEmojiSelected(String emoji);
  }

  private static class EmojiGridAdapter extends BaseAdapter {

    protected final Context context;
    private final int emojiSize;
    private final EmojiPageModel model;

    public EmojiGridAdapter(Context context, EmojiPageModel model) {
      this.context = context;
      this.emojiSize = (int) context.getResources().getDimension(R.dimen.emoji_drawer_size);
      this.model = model;
    }

    @Override
    public int getCount() {
      return model.getEmoji().length;
    }

    @Override
    public Object getItem(int position) {
      return null;
    }

    @Override
    public long getItemId(int position) {
      return position;
    }

    @Override
    public View getView(final int position, final View convertView, final ViewGroup parent) {
      final EmojiView view;
      final int pad = context.getResources().getDimensionPixelSize(R.dimen.emoji_drawer_item_padding);
      if (convertView instanceof EmojiView) {
        view = (EmojiView) convertView;
      } else {
        final EmojiView emojiView = new EmojiView(context);
        emojiView.setPadding(pad, pad, pad, pad);
        emojiView.setLayoutParams(new AbsListView.LayoutParams(emojiSize + 2 * pad, emojiSize + 2 * pad));
        view = emojiView;
      }

      view.setEmoji(model.getEmoji()[position]);
      return view;
    }
  }
}
