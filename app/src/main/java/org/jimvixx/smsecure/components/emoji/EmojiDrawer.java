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
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.components.InputAwareLayout.InputView;
import org.jimvixx.smsecure.components.RepeatableImageKey;
import org.jimvixx.smsecure.components.emoji.EmojiPageView.EmojiSelectionListener;
import org.jimvixx.smsecure.util.ResUtil;

import java.util.LinkedList;
import java.util.List;

public class EmojiDrawer extends LinearLayout implements InputView {

  private static final KeyEvent DELETE_KEY_EVENT =
          new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL);

  private ViewPager2 pager;
  private TabLayout tabs;
  private TabLayoutMediator tabMediator;

  private List<EmojiPageModel> models;
  private RecentEmojiPageModel recentModel;

  private EmojiEventListener listener;
  private EmojiDrawerListener drawerListener;

  private boolean pageCallbackRegistered = false;

  public EmojiDrawer(Context context) {
    this(context, null);
  }

  public EmojiDrawer(Context context, @Nullable android.util.AttributeSet attrs) {
    super(context, attrs);
    setOrientation(VERTICAL);
  }

  private void initView() {
    final View v = LayoutInflater.from(getContext()).inflate(R.layout.emoji_drawer, this, true);
    initializeResources(v);
    initializePageModels();
    initializeEmojiGrid();
  }

  public void setEmojiEventListener(EmojiEventListener listener) {
    this.listener = listener;
  }

  public void setDrawerListener(EmojiDrawerListener listener) {
    this.drawerListener = listener;
  }

  private void initializeResources(@NonNull View v) {
    this.pager = v.findViewById(R.id.emoji_pager);
    this.tabs = v.findViewById(R.id.tabs);

    RepeatableImageKey backspace = v.findViewById(R.id.backspace);
    backspace.setOnKeyEventListener(() -> {
      if (listener != null) listener.onKeyEvent(DELETE_KEY_EVENT);
    });
  }

  @Override
  public boolean isShowing() {
    return getVisibility() == VISIBLE;
  }

  @Override
  public void show(int height, boolean immediate) {
    if (this.pager == null) initView();

    int targetHeight = Math.max(0, height - getBottomSystemInset());

    ViewGroup.LayoutParams params = getLayoutParams();
    if (params != null) {
      params.height = targetHeight;
      setLayoutParams(params);
    }

    setVisibility(VISIBLE);
    requestLayout();

    if (drawerListener != null) drawerListener.onShown();
  }

  @Override
  public void hide(boolean immediate) {
    setVisibility(GONE);

    ViewGroup.LayoutParams params = getLayoutParams();
    if (params != null) {
      params.height = 0;
      setLayoutParams(params);
    }

    if (drawerListener != null) drawerListener.onHidden();
  }

  private int getBottomSystemInset() {
    WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(this);
    if (insets == null) return 0;

    Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
    return Math.max(bars.bottom, 0);
  }

  private void initializeEmojiGrid() {
    pager.setAdapter(new EmojiPagerAdapter(getContext(), models, emoji -> {
      recentModel.onCodePointSelected(emoji);
      if (listener != null) listener.onEmojiSelected(emoji);
    }));

    if (!pageCallbackRegistered) {
      pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
        @Override
        public void onPageSelected(int position) {
          notifyPageSelected(position);
        }
      });
      pageCallbackRegistered = true;
    }

    final int startPos;
    if (recentModel.getEmoji().length == 0) {
      startPos = 1;
      pager.setCurrentItem(1, false);
      notifyPageSelected(1);
    } else {
      startPos = 0;
      notifyPageSelected(0);
    }

    if (tabMediator != null) tabMediator.detach();

    tabs.setTabIconTint(makeTabIconTint());

    tabMediator = new TabLayoutMediator(
            tabs,
            pager,
            (tab, position) -> {
              Drawable icon = AppCompatResources.getDrawable(getContext(), models.get(position).getCategoryIcon());

              if (icon != null) {
                int inset = (int) (getResources().getDisplayMetrics().density);
                Drawable insetIcon = new InsetDrawable(icon, inset, inset, inset * 2, inset);
                tab.setIcon(insetIcon);
              } else {
                tab.setIcon(models.get(position).getCategoryIcon());
              }
            }
    );
    tabMediator.attach();

    tabs.post(() -> tabs.post(() -> {
      tabs.selectTab(null);

      int pos = pager.getCurrentItem();
      TabLayout.Tab t = tabs.getTabAt(pos);
      if (t == null) t = tabs.getTabAt(startPos);

      if (t != null) {
        tabs.selectTab(t, true);
      }

      tabs.invalidate();
      tabs.requestLayout();
    }));
  }

  private ColorStateList makeTabIconTint() {
    final int selected;
    final int normal;

    selected = ResUtil.getColor(getContext(), R.attr.appColorIconPrimary);
    normal = ResUtil.getColor(getContext(), R.attr.appColorIconInactive);

    return new ColorStateList(
            new int[][]{
                    new int[]{android.R.attr.state_selected},
                    new int[]{-android.R.attr.state_selected}
            },
            new int[]{selected, normal}
    );
  }

  private void notifyPageSelected(int position) {
    View child = pager.getChildAt(0);
    if (!(child instanceof RecyclerView rv)) return;

    RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(position);
    if (vh instanceof SelectablePageViewHolder selectable) {
      selectable.onSelected();
    }
  }

  private void initializePageModels() {
    this.models = new LinkedList<>();
    this.recentModel = new RecentEmojiPageModel(getContext());
    this.models.add(recentModel);
    this.models.addAll(EmojiPages.PAGES);
  }

  @Override
  protected void onDetachedFromWindow() {
    if (tabMediator != null) {
      tabMediator.detach();
      tabMediator = null;
    }
    super.onDetachedFromWindow();
  }

  private interface SelectablePageViewHolder {
    void onSelected();
  }

  public interface EmojiEventListener extends EmojiSelectionListener {
    void onKeyEvent(KeyEvent keyEvent);
  }

  public interface EmojiDrawerListener {
    void onShown();

    void onHidden();
  }

  public static final class EmojiPagerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private final Context context;
    private final List<EmojiPageModel> pages;
    @Nullable
    private final EmojiSelectionListener listener;

    public EmojiPagerAdapter(@NonNull Context context,
                             @NonNull List<EmojiPageModel> pages,
                             @Nullable EmojiSelectionListener listener) {
      this.context = context;
      this.pages = pages;
      this.listener = listener;
      setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
      return (pages.get(position).getClass().getName().hashCode() * 31L) + position;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
      EmojiPageView page = new EmojiPageView(context);
      page.setLayoutParams(new ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT
      ));
      return new PageVH(page);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
      ((PageVH) holder).bind(pages.get(position), listener);
    }

    @Override
    public int getItemCount() {
      return pages.size();
    }

    static final class PageVH extends RecyclerView.ViewHolder
            implements EmojiDrawer.SelectablePageViewHolder {

      private final EmojiPageView page;

      PageVH(@NonNull View itemView) {
        super(itemView);
        this.page = (EmojiPageView) itemView;
      }

      void bind(@NonNull EmojiPageModel model, @Nullable EmojiSelectionListener listener) {
        page.setModel(model);
        page.setEmojiSelectedListener(listener);
      }

      @Override
      public void onSelected() {
        page.onSelected();
      }
    }
  }
}
