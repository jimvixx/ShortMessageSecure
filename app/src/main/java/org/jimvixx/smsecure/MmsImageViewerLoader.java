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

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
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

/** Loads one authenticated MMS image into a bounded, wipeable bitmap. */
public final class MmsImageViewerLoader {
  static final int MAX_PLAINTEXT_BYTES = 16 * 1024 * 1024;
  private static final long MAX_SOURCE_PIXELS = 200_000_000L;
  private static final long MAX_DECODED_PIXELS = 8_000_000L;
  private static final int MAX_DECODE_DIMENSION = 8192;
  private static final int BUFFER_SIZE = 8192;
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
          1, 1, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), runnable -> {
            Thread thread = new Thread(runnable, "mms-image-viewer");
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
          }, new ThreadPoolExecutor.AbortPolicy());

  private final AttachmentDatabase attachmentDatabase;
  private final int targetWidth;
  private final int targetHeight;

  public MmsImageViewerLoader(@NonNull AttachmentDatabase attachmentDatabase,
                              @NonNull DisplayMetrics displayMetrics) {
    this.attachmentDatabase = attachmentDatabase;
    this.targetWidth = Math.max(1, Math.min(MAX_DECODE_DIMENSION, displayMetrics.widthPixels));
    this.targetHeight = Math.max(1, Math.min(MAX_DECODE_DIMENSION, displayMetrics.heightPixels));
  }

  @NonNull public Request load(long mmsId, long partId, long uniqueId,
                               @Nullable MasterSecret masterSecret,
                               @NonNull Callback callback) {
    Request request = new Request();
    FutureTask<Void> task = new FutureTask<>(() -> {
      Result result = loadOnWorker(mmsId, partId, uniqueId, masterSecret, request);
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
      Result unavailable = new Result(Status.LOAD_UNAVAILABLE, null);
      request.setPendingResult(unavailable);
      Runnable delivery = () -> request.deliver(unavailable, callback);
      request.setDelivery(delivery);
      MAIN.post(delivery);
    }
    return request;
  }

  private Result loadOnWorker(long mmsId, long partId, long uniqueId,
                              @Nullable MasterSecret masterSecret, Request request) {
    if (request.isCancelled() || Thread.currentThread().isInterrupted()) {
      return new Result(Status.CANCELLED, null);
    }

    AttachmentDatabase.PartMetadata selected = null;
    try {
      List<AttachmentDatabase.PartMetadata> parts =
              attachmentDatabase.getPartMetadataForMessage(mmsId);
      for (AttachmentDatabase.PartMetadata part : parts) {
        if (part.id == partId && part.uniqueId == uniqueId) {
          selected = part;
          break;
        }
      }
    } catch (RuntimeException exception) {
      return new Result(Status.LOAD_UNAVAILABLE, null);
    }

    if (selected == null) return new Result(Status.NOT_FOUND, null);
    if (!isSupportedImageType(selected.contentType)) {
      return new Result(Status.UNSUPPORTED_IMAGE, null);
    }
    if (masterSecret == null) return new Result(Status.MISSING_MASTER_KEY, null);

    AttachmentDatabase.AttachmentAccessResult opened = attachmentDatabase.openPart(mmsId,
            partId, uniqueId, AttachmentDatabase.FileKind.DATA, masterSecret,
            MAX_PLAINTEXT_BYTES);
    if (!opened.isSuccess() || opened.attachment == null) {
      return new Result(mapStatus(opened.status), null);
    }

    SensitiveBytes bytes = null;
    try (AttachmentDatabase.OpenedAttachment attachment = opened.attachment;
         InputStream input = attachment.openStream()) {
      bytes = readBounded(input, MAX_PLAINTEXT_BYTES);
      if (bytes == null) return new Result(Status.RESOURCE_LIMIT_EXCEEDED, null);
      if (request.isCancelled() || Thread.currentThread().isInterrupted()) {
        return new Result(Status.CANCELLED, null);
      }
      Bitmap bitmap = decode(bytes.data, bytes.length);
      if (bitmap == null) return new Result(Status.INVALID_IMAGE, null);
      return new Result(Status.OPENED, bitmap);
    } catch (IOException | RuntimeException exception) {
      return new Result(Status.AUTHENTICATION_FAILED, null);
    } catch (OutOfMemoryError error) {
      return new Result(Status.RESOURCE_LIMIT_EXCEEDED, null);
    } finally {
      if (bytes != null) bytes.clear();
    }
  }

  @Nullable private Bitmap decode(byte[] bytes, int length) {
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(bytes, 0, length, bounds);
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth > MAX_SOURCE_PIXELS || bounds.outHeight > MAX_SOURCE_PIXELS ||
            (long) bounds.outWidth * bounds.outHeight > MAX_SOURCE_PIXELS) {
      return null;
    }

    int sample = 1;
    while ((sampledDimension(bounds.outWidth, sample) > targetWidth ||
            sampledDimension(bounds.outHeight, sample) > targetHeight ||
            sampledDimension(bounds.outWidth, sample) * sampledDimension(bounds.outHeight, sample) >
                    MAX_DECODED_PIXELS)) {
      if (sample >= (1 << 29)) return null;
      sample <<= 1;
    }

    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inSampleSize = sample;
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
    if (!bitmap.isMutable() || bitmap.getWidth() > targetWidth ||
            bitmap.getHeight() > targetHeight ||
            (long) bitmap.getWidth() * bitmap.getHeight() > MAX_DECODED_PIXELS) {
      MmsImageThumbnailLoader.eraseAndRecycle(bitmap);
      return null;
    }
    return bitmap;
  }

  private static long sampledDimension(int dimension, int sample) {
    return (dimension + (long) sample - 1L) / sample;
  }

  private static boolean isSupportedImageType(@Nullable String contentType) {
    if (contentType == null) return false;
    String mime = contentType.trim().toLowerCase(Locale.ROOT);
    int parameters = mime.indexOf(';');
    if (parameters >= 0) mime = mime.substring(0, parameters).trim();
    return "image/jpeg".equals(mime) || "image/jpg".equals(mime) ||
            "image/png".equals(mime) || "image/gif".equals(mime) ||
            "image/webp".equals(mime) ||
            (Build.VERSION.SDK_INT >= 28 && "image/heic".equals(mime)) ||
            (Build.VERSION.SDK_INT >= 31 && "image/avif".equals(mime));
  }

  private static Status mapStatus(AttachmentDatabase.AccessStatus status) {
    switch (status) {
      case NOT_FOUND: return Status.NOT_FOUND;
      case MISSING_MASTER_KEY: return Status.MISSING_MASTER_KEY;
      case RESOURCE_LIMIT_EXCEEDED: return Status.RESOURCE_LIMIT_EXCEEDED;
      case UNSUPPORTED_METADATA: return Status.UNSUPPORTED_IMAGE;
      case AUTHENTICATION_FAILED: return Status.AUTHENTICATION_FAILED;
      case MISSING_FILE:
      case INVALID_PATH:
      case IO_FAILURE:
      default: return Status.UNAVAILABLE;
    }
  }

  @Nullable private static SensitiveBytes readBounded(InputStream input, int limit)
          throws IOException {
    SensitiveBytes result = new SensitiveBytes(Math.min(BUFFER_SIZE, limit));
    byte[] buffer = new byte[BUFFER_SIZE];
    try {
      int count;
      while ((count = input.read(buffer)) != -1) {
        if (Thread.currentThread().isInterrupted()) {
          result.clear();
          return null;
        }
        if (count > limit - result.length) {
          result.clear();
          return null;
        }
        result.append(buffer, count);
        Arrays.fill(buffer, 0, count, (byte) 0);
      }
      return result;
    } catch (IOException | RuntimeException | Error exception) {
      result.clear();
      throw exception;
    } finally {
      Arrays.fill(buffer, (byte) 0);
    }
  }

  public enum Status {
    OPENED, NOT_FOUND, MISSING_MASTER_KEY, RESOURCE_LIMIT_EXCEEDED,
    UNSUPPORTED_IMAGE, INVALID_IMAGE, AUTHENTICATION_FAILED, UNAVAILABLE,
    LOAD_UNAVAILABLE, CANCELLED
  }

  public interface Callback {
    void onLoaded(@NonNull Result result);
  }

  public static final class Result implements AutoCloseable {
    public final Status status;
    @Nullable private Bitmap bitmap;
    private boolean closed;

    private Result(Status status, @Nullable Bitmap bitmap) {
      this.status = status;
      this.bitmap = bitmap;
    }

    @Nullable public synchronized Bitmap takeBitmap() {
      Bitmap result = bitmap;
      bitmap = null;
      return result;
    }

    @Override public synchronized void close() {
      if (closed) return;
      closed = true;
      MmsImageThumbnailLoader.eraseAndRecycle(bitmap);
      bitmap = null;
    }
  }

  public static final class Request {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Result> pendingResult = new AtomicReference<>();
    private volatile Future<?> future;
    private volatile Runnable delivery;

    private boolean isCancelled() { return cancelled.get(); }
    private void setFuture(Future<?> future) {
      this.future = future;
      if (cancelled.get()) future.cancel(true);
    }
    private void setPendingResult(Result result) {
      pendingResult.set(result);
      if (cancelled.get()) {
        Result pending = pendingResult.getAndSet(null);
        if (pending != null) pending.close();
      }
    }
    private Result takePendingResult() { return pendingResult.getAndSet(null); }
    private void setDelivery(Runnable delivery) {
      this.delivery = delivery;
      if (cancelled.get()) MAIN.removeCallbacks(delivery);
    }
    private void deliver(Result result, Callback callback) {
      pendingResult.compareAndSet(result, null);
      delivery = null;
      if (cancelled.get()) result.close();
      else callback.onLoaded(result);
    }
    public void cancel() {
      if (!cancelled.compareAndSet(false, true)) return;
      Future<?> current = future;
      if (current != null) current.cancel(true);
      Runnable currentDelivery = delivery;
      if (currentDelivery != null) MAIN.removeCallbacks(currentDelivery);
      Result pending = pendingResult.getAndSet(null);
      if (pending != null) pending.close();
      EXECUTOR.purge();
    }
  }

  private static final class SensitiveBytes {
    byte[] data;
    int length;
    SensitiveBytes(int initialSize) { data = new byte[Math.max(1, initialSize)]; }
    void append(byte[] source, int count) {
      if (count > data.length - length) {
        int capacity = Math.min(MAX_PLAINTEXT_BYTES,
                Math.max(length + count, Math.min(MAX_PLAINTEXT_BYTES, data.length * 2)));
        byte[] replacement = new byte[capacity];
        System.arraycopy(data, 0, replacement, 0, length);
        Arrays.fill(data, (byte) 0);
        data = replacement;
      }
      System.arraycopy(source, 0, data, length, count);
      length += count;
    }
    void clear() {
      Arrays.fill(data, (byte) 0);
      data = new byte[0];
      length = 0;
    }
  }
}
