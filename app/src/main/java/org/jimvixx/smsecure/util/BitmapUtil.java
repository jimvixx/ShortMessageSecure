/*
 * Copyright (C) 2014 Open Whisper Systems
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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Pair;

import com.bumptech.glide.Glide;

import org.jimvixx.smsecure.logging.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class BitmapUtil {

  private static final String TAG = BitmapUtil.class.getSimpleName();

  public static <T> Bitmap createScaledBitmap(Context context, T model, float scale)
          throws BitmapDecodingException {
    Pair<Integer, Integer> dimens = getDimensionsForModel(context, model);
    int width = (int) (dimens.first * scale);
    int height = (int) (dimens.second * scale);
    return loadScaledBitmap(context, model, width, height);
  }

  private static <T> Bitmap loadScaledBitmap(Context context, T model, int width, int height)
          throws BitmapDecodingException {
    try {
      Bitmap bitmap = Glide.with(context)
              .asBitmap()
              .load(model)
              .submit(width, height)
              .get();
      if (bitmap == null) {
        throw new BitmapDecodingException("Glide returned null Bitmap");
      }
      return bitmap;
    } catch (InterruptedException | ExecutionException e) {
      throw new BitmapDecodingException(e);
    }
  }

  private static <T> Pair<Integer, Integer> getDimensionsForModel(Context context, T model)
          throws BitmapDecodingException {
    try {
      Bitmap bitmap = Glide.with(context)
              .asBitmap()
              .load(model)
              .submit()
              .get();
      if (bitmap == null) {
        throw new BitmapDecodingException("Glide returned null Bitmap while reading dimensions");
      }
      int width = bitmap.getWidth();
      int height = bitmap.getHeight();
      bitmap.recycle();
      return new Pair<>(width, height);
    } catch (InterruptedException | ExecutionException e) {
      throw new BitmapDecodingException(e);
    }
  }

  public static InputStream toCompressedJpeg(Bitmap bitmap) {
    ByteArrayOutputStream thumbnailBytes = new ByteArrayOutputStream();
    bitmap.compress(CompressFormat.JPEG, 85, thumbnailBytes);
    return new ByteArrayInputStream(thumbnailBytes.toByteArray());
  }

  public static Bitmap createFromDrawable(final Drawable drawable, final int width, final int height) {
    final AtomicBoolean created = new AtomicBoolean(false);
    final Bitmap[] result = new Bitmap[1];

    Runnable runnable = () -> {
      if (drawable instanceof BitmapDrawable) {
        result[0] = ((BitmapDrawable) drawable).getBitmap();
      } else {
        int canvasWidth = drawable.getIntrinsicWidth();
        if (canvasWidth <= 0) canvasWidth = width;

        int canvasHeight = drawable.getIntrinsicHeight();
        if (canvasHeight <= 0) canvasHeight = height;

        Bitmap bitmap;

        try {
          bitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888);
          Canvas canvas = new Canvas(bitmap);
          drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
          drawable.draw(canvas);
        } catch (Exception e) {
          Log.w(TAG, e);
          bitmap = null;
        }

        result[0] = bitmap;
      }

      synchronized (result) {
        created.set(true);
        result.notifyAll();
      }
    };

    Util.runOnMain(runnable);

    synchronized (result) {
      while (!created.get()) Util.wait(result, 0);
      return result[0];
    }
  }
}
