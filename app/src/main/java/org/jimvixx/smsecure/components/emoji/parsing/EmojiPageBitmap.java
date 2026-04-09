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

package org.jimvixx.smsecure.components.emoji.parsing;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;

import org.jimvixx.smsecure.components.emoji.EmojiPageModel;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.util.BitmapDecodingException;
import org.jimvixx.smsecure.util.BitmapUtil;
import org.jimvixx.smsecure.util.ListenableFutureTask;
import org.jimvixx.smsecure.util.Util;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EmojiPageBitmap {

  private static final String TAG = EmojiPageBitmap.class.getName();

  // Shared background executor: avoids spinning up threads per page.
  private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

  private final Context context;
  private final EmojiPageModel model;
  private final float decodeScale;

  private SoftReference<Bitmap> bitmapReference;
  private ListenableFutureTask<Bitmap> task;

  public EmojiPageBitmap(@NonNull Context context, @NonNull EmojiPageModel model, float decodeScale) {
    this.context = context.getApplicationContext();
    this.model = model;
    this.decodeScale = decodeScale;
  }

  public ListenableFutureTask<Bitmap> get() {
    Util.assertMainThread();

    Bitmap cached = bitmapReference != null ? bitmapReference.get() : null;
    if (cached != null) {
      return new ListenableFutureTask<>(cached);
    }

    if (task != null) {
      return task;
    }

    Callable<Bitmap> callable = () -> {
      try {
        Log.w(TAG, "loading page " + model.getSprite());
        return loadPage();
      } catch (IOException ioe) {
        Log.w(TAG, ioe);
        return null;
      }
    };

    task = new ListenableFutureTask<>(callable);

    ListenableFutureTask<Bitmap> scheduled = task;
    EXECUTOR.execute(() -> {
      try {
        scheduled.run();
      } finally {
        synchronized (EmojiPageBitmap.this) {
          if (task == scheduled) task = null;
        }
      }
    });

    return task;
  }

  private synchronized Bitmap loadPage() throws IOException {
    Bitmap cached = bitmapReference != null ? bitmapReference.get() : null;
    if (cached != null) return cached;

    try {
      final Bitmap bitmap =
              BitmapUtil.createScaledBitmap(context,
                      "file:///android_asset/" + model.getSprite(),
                      decodeScale);
      bitmapReference = new SoftReference<>(bitmap);
      Log.w(TAG, "onPageLoaded(" + model.getSprite() + ")");
      return bitmap;
    } catch (BitmapDecodingException e) {
      Log.w(TAG, e);
      throw new IOException(e);
    }
  }

  /// Optional: clears cached bitmap reference (useful if you want to free memory deterministically).
  public synchronized void clearCache() {
    bitmapReference = null;
  }

  @NonNull
  @Override
  public String toString() {
    return model.getSprite();
  }
}
