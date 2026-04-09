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

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.ThreadDatabase;

import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/// Trims message history in all threads with an optional UI progress dialog.
public final class Trimmer {

  private Trimmer() {
  }

  public static void trimAllThreads(@NonNull Context context, int threadLengthLimit) {
    new TrimmingProgressRunner(context).start(threadLengthLimit);
  }

  private static final class TrimmingProgressRunner implements ThreadDatabase.ProgressListener {

    private final WeakReference<Context> contextRef;
    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // UI (only if we can show it)
    private AlertDialog dialog;
    private ProgressBar progressBar;

    TrimmingProgressRunner(@NonNull Context context) {
      this.contextRef = new WeakReference<>(context);
      this.appContext = context.getApplicationContext();
    }

    private static int dp(@NonNull Context context, int dp) {
      float density = context.getResources().getDisplayMetrics().density;
      return (int) (dp * density + 0.5f);
    }

    void start(int threadLengthLimit) {
      // Create UI on main thread (if possible), then run work in background.
      mainHandler.post(() -> {
        maybeShowDialog();
        executor.execute(() -> {
          try {
            DatabaseFactory.getThreadDatabase(appContext)
                    .trimAllThreads(threadLengthLimit, this);
            postFinished(true);
          } catch (Throwable t) {
            postFinished(false);
          } finally {
            executor.shutdown();
          }
        });
      });
    }

    @Override
    public void onProgress(int complete, int total) {
      // Called from DB trimming thread; forward to main thread for UI update.
      mainHandler.post(() -> updateProgress(complete, total));
    }

    @MainThread
    private void updateProgress(int complete, int total) {
      if (progressBar == null) return;
      if (total <= 0) {
        progressBar.setIndeterminate(true);
        return;
      }
      progressBar.setIndeterminate(false);

      // Keep 0..100 progress for a simple horizontal bar.
      int percent = (int) Math.round((complete / (double) total) * 100.0);
      if (percent < 0) percent = 0;
      if (percent > 100) percent = 100;
      progressBar.setProgress(percent);
    }

    @MainThread
    private void postFinished(boolean success) {
      mainHandler.post(() -> {
        dismissDialogIfAny();
        Context ctx = contextRef.get();
        if (ctx == null) ctx = appContext;

        Toast.makeText(
                ctx,
                success
                        ? R.string.trimmer__old_messages_successfully_deleted
                        : R.string.trimmer__deleting_old_messages, // fallback message on error
                Toast.LENGTH_LONG
        ).show();
      });
    }

    @MainThread
    private void maybeShowDialog() {
      Context ctx = contextRef.get();
      if (!(ctx instanceof Activity activity)) return;

      if (activity.isFinishing()) return;

      // Build a lightweight progress UI.
      int padding = dp(activity, 20);

      LinearLayout root = new LinearLayout(activity);
      root.setOrientation(LinearLayout.VERTICAL);
      root.setPadding(padding, padding, padding, padding);

      TextView message = new TextView(activity);
      message.setText(R.string.trimmer__deleting_old_messages);
      root.addView(message, new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.WRAP_CONTENT
      ));

      progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
      progressBar.setIndeterminate(false);
      progressBar.setMax(100);
      progressBar.setProgress(0);

      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.WRAP_CONTENT
      );
      lp.topMargin = dp(activity, 12);
      root.addView(progressBar, lp);

      dialog = new AlertDialog.Builder(activity)
              .setTitle(R.string.Deleting)
              .setView(root)
              .setCancelable(false)
              .create();

      dialog.show();
    }

    @MainThread
    private void dismissDialogIfAny() {
      if (dialog != null) {
        try {
          dialog.dismiss();
        } catch (Throwable ignored) {
          // Ignore "leaked window" edge cases if activity is already gone.
        }
      }
      dialog = null;
      progressBar = null;
    }
  }
}
