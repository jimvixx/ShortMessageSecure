/*
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

package org.jimvixx.smsecure.util.task;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import org.jimvixx.smsecure.util.AppExecutors;

import java.lang.ref.WeakReference;

/**
 * Runs work on background thread, shows a modal progress dialog (AppCompat AlertDialog),
 * and delivers result on main thread.
 */
public final class ProgressDialogTask {

  private ProgressDialogTask() {
  }

  public static <R> void run(@NonNull Context context,
                             @NonNull String title,
                             @NonNull String message,
                             @NonNull Worker<R> worker,
                             @NonNull ResultCallback<R> onResult,
                             @Nullable ErrorCallback onError) {

    final WeakReference<Context> ctxRef = new WeakReference<>(context);
    final Handler main = AppExecutors.mainHandler();

    main.post(() -> {
      final Context ctx = ctxRef.get();
      if (ctx == null) return;

      final boolean canShowDialog = (ctx instanceof Activity) && !((Activity) ctx).isFinishing();

      final AlertDialog dialog;
      if (canShowDialog) {
        dialog = new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .create();
        try {
          dialog.show();
        } catch (Throwable t) {
          // If showing fails for any reason, continue without a dialog.
        }
      } else {
        dialog = null;
      }

      AppExecutors.background().execute(() -> {
        R result = null;
        Throwable error = null;

        try {
          result = worker.run();
        } catch (Throwable t) {
          error = t;
        }

        final R finalResult = result;
        final Throwable finalError = error;

        main.post(() -> {
          // Dismiss safely
          if (dialog != null) {
            try {
              if (dialog.isShowing()) dialog.dismiss();
            } catch (Throwable ignored) {
            }
          }

          if (finalError != null) {
            if (onError != null) onError.onError(finalError);
            return;
          }

          onResult.onResult(finalResult);
        });
      });
    });
  }

  public static <R> void run(@NonNull Context context,
                             int titleRes,
                             int messageRes,
                             @NonNull Worker<R> worker,
                             @NonNull ResultCallback<R> onResult,
                             @Nullable ErrorCallback onError) {
    run(context,
            context.getString(titleRes),
            context.getString(messageRes),
            worker,
            onResult,
            onError);
  }

  public static <R> void run(@NonNull Context context,
                             @NonNull String title,
                             @NonNull String message,
                             @NonNull Worker<R> worker,
                             @NonNull ResultCallback<R> onResult) {
    run(context, title, message, worker, onResult, null);
  }

  public interface Worker<R> {
    @Nullable
    R run() throws Exception;
  }

  public interface ResultCallback<R> {
    @MainThread
    void onResult(@Nullable R result);
  }

  public interface ErrorCallback {
    @MainThread
    void onError(@NonNull Throwable error);
  }
}
