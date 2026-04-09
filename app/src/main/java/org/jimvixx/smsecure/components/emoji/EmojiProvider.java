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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.components.emoji.parsing.EmojiDrawInfo;
import org.jimvixx.smsecure.components.emoji.parsing.EmojiPageBitmap;
import org.jimvixx.smsecure.components.emoji.parsing.EmojiParser;
import org.jimvixx.smsecure.components.emoji.parsing.EmojiTree;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.util.FutureTaskListener;
import org.jimvixx.smsecure.util.Util;

import java.util.List;

class EmojiProvider {

  public static final int EMOJI_RAW_HEIGHT = 102;
  public static final int EMOJI_RAW_WIDTH = 102;
  public static final int EMOJI_VERT_PAD = 0;
  public static final int EMOJI_PER_ROW = 15;
  private static final String TAG = EmojiProvider.class.getSimpleName();
  private static final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
  private static volatile EmojiProvider instance = null;
  private final EmojiTree emojiTree = new EmojiTree();
  private final float decodeScale;
  private final float verticalPad;

  private EmojiProvider(Context context) {
    this.decodeScale = Math.min(1f, context.getResources().getDimension(R.dimen.emoji_drawer_size) / EMOJI_RAW_HEIGHT);
    this.verticalPad = EMOJI_VERT_PAD * this.decodeScale;

    for (EmojiPageModel page : EmojiPages.PAGES) {
      if (page.hasSpriteMap()) {
        EmojiPageBitmap pageBitmap = new EmojiPageBitmap(context, page, decodeScale);

        for (int i = 0; i < page.getEmoji().length; i++) {
          emojiTree.add(page.getEmoji()[i], new EmojiDrawInfo(pageBitmap, i));
        }
      }
    }
  }

  public static EmojiProvider getInstance(Context context) {
    if (instance == null) {
      synchronized (EmojiProvider.class) {
        if (instance == null) {
          instance = new EmojiProvider(context);
        }
      }
    }
    return instance;
  }

  @Nullable
  Spannable emojify(@Nullable CharSequence text, @NonNull TextView tv) {
    if (text == null) return null;

    List<EmojiParser.Candidate> matches = new EmojiParser(emojiTree).findCandidates(text);
    SpannableStringBuilder builder = new SpannableStringBuilder(text);

    for (EmojiParser.Candidate candidate : matches) {
      Drawable drawable = getEmojiDrawable(candidate.getDrawInfo());

      if (drawable != null) {
        builder.setSpan(new EmojiSpan(drawable, tv), candidate.getStartIndex(), candidate.getEndIndex(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
    }

    return builder;
  }

  @Nullable
  Drawable getEmojiDrawable(CharSequence emoji) {
    EmojiDrawInfo drawInfo = emojiTree.getEmoji(emoji, 0, emoji.length());
    return getEmojiDrawable(drawInfo);
  }

  private @Nullable Drawable getEmojiDrawable(@Nullable EmojiDrawInfo drawInfo) {
    if (drawInfo == null) {
      return null;
    }

    final EmojiDrawable drawable = new EmojiDrawable(drawInfo, decodeScale);
    drawInfo.getPage().get().addListener(new FutureTaskListener<>() {
      @Override
      public void onSuccess(final Bitmap result) {
        Util.runOnMain(() -> drawable.setBitmap(result));
      }

      @Override
      public void onFailure(Throwable error) {
        Log.w(TAG, error);
      }
    });
    return drawable;
  }

  class EmojiDrawable extends Drawable {
    private final EmojiDrawInfo info;
    private final float intrinsicWidth;
    private final float intrinsicHeight;
    private Bitmap bmp;

    EmojiDrawable(EmojiDrawInfo info, float decodeScale) {
      this.info = info;
      this.intrinsicWidth = EMOJI_RAW_WIDTH * decodeScale;
      this.intrinsicHeight = EMOJI_RAW_HEIGHT * decodeScale;
    }

    @Override
    public int getIntrinsicWidth() {
      return (int) intrinsicWidth;
    }

    @Override
    public int getIntrinsicHeight() {
      return (int) intrinsicHeight;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
      if (bmp == null) {
        return;
      }

      final int row = info.getIndex() / EMOJI_PER_ROW;
      final int row_index = info.getIndex() % EMOJI_PER_ROW;

      canvas.drawBitmap(bmp,
              new Rect((int) (row_index * intrinsicWidth),
                      (int) (row * intrinsicHeight + row * verticalPad),
                      (int) ((row_index + 1) * intrinsicWidth),
                      (int) ((row + 1) * intrinsicHeight + row * verticalPad)),
              getBounds(),
              paint);
    }

    public void setBitmap(Bitmap bitmap) {
      Util.assertMainThread();
      if (bmp == null || !bmp.sameAs(bitmap)) {
        bmp = bitmap;
        invalidateSelf();
      }
    }

    @Override
    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
    }
  }

}
