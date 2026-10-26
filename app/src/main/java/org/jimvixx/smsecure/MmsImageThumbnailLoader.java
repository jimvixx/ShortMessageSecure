/*
 * Copyright (C) 2026 Jimvixx
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
package org.jimvixx.smsecure;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.AttachmentDatabase;
import org.jimvixx.smsecure.util.AppExecutors;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Loads authenticated MMS image previews without disk caching or filesystem paths. */
public final class MmsImageThumbnailLoader {
  private static final int MAX_PREVIEWS = 3;
  private static final int MAX_COMPRESSED_BYTES = 8 * 1024 * 1024;
  private static final long MAX_SOURCE_PIXELS = 100_000_000L;
  private static final int MAX_DECODE_WIDTH = 640;
  private static final int MAX_DECODE_HEIGHT = 480;
  private static final int BUFFER_SIZE = 8192;
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
          2, 2, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(24), runnable -> {
            Thread thread = new Thread(runnable, "mms-image-preview");
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
          }, new ThreadPoolExecutor.AbortPolicy());

  private final AttachmentDatabase attachmentDatabase;
  private final int targetWidth;
  private final int targetHeight;

  public MmsImageThumbnailLoader(@NonNull AttachmentDatabase attachmentDatabase,
                                 @NonNull DisplayMetrics displayMetrics) {
    this.attachmentDatabase = attachmentDatabase;
    this.targetWidth = Math.min(MAX_DECODE_WIDTH,
            Math.max(1, Math.round(220f * displayMetrics.density)));
    this.targetHeight = Math.min(MAX_DECODE_HEIGHT,
            Math.max(1, Math.round(160f * displayMetrics.density)));
  }

  @NonNull public Request load(long mmsId, @Nullable MasterSecret masterSecret,
                               @NonNull Callback callback) {
    Request request = new Request();
    FutureTask<Void> task = new FutureTask<>(() -> {
      LoadResult result = loadOnWorker(mmsId, masterSecret, request);
      if (request.isCancelled()) {
        result.close();
        return null;
      }
      request.setPendingResult(result);
      Runnable delivery = () -> request.deliver(result, callback);
      request.setDelivery(delivery);
      MAIN.post(delivery);
      return null;
    });
    request.setFuture(task);
    try {
      EXECUTOR.execute(task);
    } catch (RejectedExecutionException exception) {
      request.cancel();
    }
    return request;
  }

  private LoadResult loadOnWorker(long mmsId, @Nullable MasterSecret masterSecret,
                                  Request request) {
    List<AttachmentDatabase.PartMetadata> parts;
    try {
      parts = attachmentDatabase.getPartMetadataForMessage(mmsId);
    } catch (RuntimeException exception) {
      return new LoadResult(Collections.emptyList(), true);
    }

    if (parts.isEmpty()) return new LoadResult(Collections.emptyList(), true);

    List<ImagePreview> previews = new ArrayList<>();
    boolean hasOtherAttachments = false;
    for (AttachmentDatabase.PartMetadata part : parts) {
      if (request.isCancelled() || Thread.currentThread().isInterrupted()) {
        clearPreviews(previews);
        return new LoadResult(Collections.emptyList(), true);
      }

      if (!isSupportedImageType(part.contentType)) {
        hasOtherAttachments = true;
        continue;
      }
      if (previews.size() >= MAX_PREVIEWS) {
        hasOtherAttachments = true;
        continue;
      }

      try {
        DecodeResult decoded = decodePart(mmsId, part, AttachmentDatabase.FileKind.THUMBNAIL,
                masterSecret);
        if (decoded.status == AttachmentDatabase.AccessStatus.MISSING_FILE) {
          decoded = decodePart(mmsId, part, AttachmentDatabase.FileKind.DATA, masterSecret);
        }
        previews.add(new ImagePreview(part.id, part.uniqueId, decoded.bitmap));
      } catch (OutOfMemoryError error) {
        clearPreviews(previews);
        return new LoadResult(Collections.emptyList(), true);
      }
    }

    return new LoadResult(previews, hasOtherAttachments);
  }

  private DecodeResult decodePart(long mmsId, AttachmentDatabase.PartMetadata part,
                                  AttachmentDatabase.FileKind kind,
                                  @Nullable MasterSecret masterSecret) {
    AttachmentDatabase.AttachmentAccessResult opened = attachmentDatabase.openPart(mmsId,
            part.id, part.uniqueId, kind, masterSecret, MAX_COMPRESSED_BYTES);
    if (!opened.isSuccess() || opened.attachment == null) {
      return new DecodeResult(opened.status, null);
    }

    try (AttachmentDatabase.OpenedAttachment attachment = opened.attachment;
         InputStream stream = attachment.openStream()) {
      SensitiveBytes bytes = readBounded(stream, MAX_COMPRESSED_BYTES);
      if (bytes == null) return new DecodeResult(
              AttachmentDatabase.AccessStatus.RESOURCE_LIMIT_EXCEEDED, null);
      try {
        return new DecodeResult(opened.status, decodeSampled(bytes.data, bytes.length));
      } finally {
        bytes.clear();
      }
    } catch (IOException | RuntimeException exception) {
      return new DecodeResult(AttachmentDatabase.AccessStatus.IO_FAILURE, null);
    }
  }

  @Nullable private Bitmap decodeSampled(byte[] bytes, int length) {
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(bytes, 0, length, bounds);
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            (long) bounds.outWidth * bounds.outHeight > MAX_SOURCE_PIXELS) {
      return null;
    }

    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight,
            targetWidth, targetHeight);
    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
    options.inMutable = true;
    options.inScaled = false;
    Bitmap bitmap;
    try {
      bitmap = BitmapFactory.decodeByteArray(bytes, 0, length, options);
    } catch (OutOfMemoryError error) {
      return null;
    }
    if (bitmap == null) return null;
    if (!bitmap.isMutable()) {
      // The platform was asked for mutable software pixels, but immutable pixels cannot be wiped.
      bitmap.recycle();
      return null;
    }
    if (bitmap.getWidth() > MAX_DECODE_WIDTH || bitmap.getHeight() > MAX_DECODE_HEIGHT ||
            (long) bitmap.getWidth() * bitmap.getHeight() >
                    (long) MAX_DECODE_WIDTH * MAX_DECODE_HEIGHT) {
      eraseAndRecycle(bitmap);
      return null;
    }
    return bitmap;
  }

  private static int sampleSize(int width, int height, int targetWidth, int targetHeight) {
    int sample = 1;
    while (width / (long) sample > targetWidth || height / (long) sample > targetHeight) {
      if (sample > (1 << 29)) return 1 << 30;
      sample <<= 1;
    }
    return sample;
  }

  @Nullable private static SensitiveBytes readBounded(InputStream input, int maxBytes)
          throws IOException {
    SensitiveBytes result = new SensitiveBytes(Math.min(BUFFER_SIZE, maxBytes));
    byte[] buffer = new byte[BUFFER_SIZE];
    try {
      int count;
      while ((count = input.read(buffer)) != -1) {
        if (count > maxBytes - result.length) {
          result.clear();
          return null;
        }
        result.append(buffer, count);
        java.util.Arrays.fill(buffer, 0, count, (byte) 0);
      }
      return result;
    } catch (IOException | RuntimeException | Error exception) {
      result.clear();
      throw exception;
    } finally {
      java.util.Arrays.fill(buffer, (byte) 0);
    }
  }

  private static boolean isSupportedImageType(@Nullable String contentType) {
    if (contentType == null) return false;
    String mime = contentType.trim().toLowerCase(Locale.ROOT);
    int parameters = mime.indexOf(';');
    if (parameters >= 0) mime = mime.substring(0, parameters).trim();
    return "image/jpeg".equals(mime) || "image/jpg".equals(mime) ||
            "image/png".equals(mime) || "image/gif".equals(mime) ||
            "image/webp".equals(mime) || (Build.VERSION.SDK_INT >= 28 && "image/heic".equals(mime)) ||
            (Build.VERSION.SDK_INT >= 31 && "image/avif".equals(mime));
  }

  private static void clearPreviews(List<ImagePreview> previews) {
    for (ImagePreview preview : previews) preview.clear();
  }

  static void eraseAndRecycle(@Nullable Bitmap bitmap) {
    if (bitmap == null || bitmap.isRecycled()) return;
    // Decoded previews are mutable; recycling is the only available fallback for immutable data.
    if (bitmap.isMutable()) {
      bitmap.eraseColor(android.graphics.Color.TRANSPARENT);
    }
    bitmap.recycle();
  }

  public interface Callback {
    void onLoaded(@NonNull LoadResult result);
  }

  public static final class Request {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<LoadResult> pendingResult = new AtomicReference<>();
    private volatile Future<?> future;
    private volatile Runnable delivery;

    private boolean isCancelled() { return cancelled.get(); }
    private void setFuture(Future<?> future) {
      this.future = future;
      if (cancelled.get()) future.cancel(true);
    }
    private void setPendingResult(LoadResult result) {
      pendingResult.set(result);
      if (cancelled.get()) {
        LoadResult pending = pendingResult.getAndSet(null);
        if (pending != null) pending.close();
      }
    }
    private void setDelivery(Runnable delivery) {
      this.delivery = delivery;
      if (cancelled.get()) MAIN.removeCallbacks(delivery);
    }
    private void deliver(LoadResult result, Callback callback) {
      pendingResult.compareAndSet(result, null);
      delivery = null;
      if (cancelled.get()) {
        result.close();
      } else {
        callback.onLoaded(result);
      }
    }
    public void cancel() {
      if (!cancelled.compareAndSet(false, true)) return;
      Future<?> currentFuture = future;
      if (currentFuture != null) currentFuture.cancel(true);
      Runnable currentDelivery = delivery;
      if (currentDelivery != null) MAIN.removeCallbacks(currentDelivery);
      LoadResult pending = pendingResult.getAndSet(null);
      if (pending != null) pending.close();
      EXECUTOR.purge();
    }
  }

  public static final class LoadResult implements AutoCloseable {
    public final List<ImagePreview> previews;
    public final boolean hasOtherAttachments;
    private boolean closed;

    private LoadResult(List<ImagePreview> previews, boolean hasOtherAttachments) {
      this.previews = Collections.unmodifiableList(new ArrayList<>(previews));
      this.hasOtherAttachments = hasOtherAttachments;
    }

    @Override public synchronized void close() {
      if (closed) return;
      closed = true;
      clearPreviews(previews);
    }
  }

  public static final class ImagePreview {
    public final long partId;
    public final long uniqueId;
    @Nullable private Bitmap bitmap;

    private ImagePreview(long partId, long uniqueId, @Nullable Bitmap bitmap) {
      this.partId = partId;
      this.uniqueId = uniqueId;
      this.bitmap = bitmap;
    }

    @Nullable public synchronized Bitmap takeBitmap() {
      Bitmap result = bitmap;
      bitmap = null;
      return result;
    }

    private synchronized void clear() {
      eraseAndRecycle(bitmap);
      bitmap = null;
    }
  }

  private static final class DecodeResult {
    final AttachmentDatabase.AccessStatus status;
    @Nullable final Bitmap bitmap;
    DecodeResult(AttachmentDatabase.AccessStatus status, @Nullable Bitmap bitmap) {
      this.status = status;
      this.bitmap = bitmap;
    }
  }

  private static final class SensitiveBytes {
    byte[] data;
    int length;
    SensitiveBytes(int initialSize) { data = new byte[Math.max(1, initialSize)]; }
    void append(byte[] source, int count) {
      if (count > data.length - length) {
        int capacity = Math.min(MAX_COMPRESSED_BYTES,
                Math.max(length + count, Math.min(MAX_COMPRESSED_BYTES, data.length * 2)));
        byte[] expanded = new byte[capacity];
        System.arraycopy(data, 0, expanded, 0, length);
        java.util.Arrays.fill(data, (byte) 0);
        data = expanded;
      }
      System.arraycopy(source, 0, data, length, count);
      length += count;
    }
    void clear() {
      java.util.Arrays.fill(data, (byte) 0);
      length = 0;
    }
  }
}
