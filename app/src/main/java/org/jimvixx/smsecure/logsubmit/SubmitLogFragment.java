/*
 * Copyright (C) 2013 Open Whisper Systems
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

package org.jimvixx.smsecure.logsubmit;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.logging.CrashLogCapture;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SubmitLogFragment extends Fragment {

  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private ProgressBar progress;
  private TextView status;
  private TextView preview;
  private Button copyButton;
  private Button shareLogsButton;
  private Button shareLinkButton;
  @Nullable
  private String collectedLogs;
  @Nullable
  private String uploadedUrl;

  public static SubmitLogFragment newInstance() {
    return new SubmitLogFragment();
  }

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater,
                           @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.submit_log_fragment, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    status = view.findViewById(R.id.log_submit_status);
    progress = view.findViewById(R.id.log_submit_progress);
    preview = view.findViewById(R.id.log_submit_preview);
    copyButton = view.findViewById(R.id.log_submit_copy_button);
    shareLogsButton = view.findViewById(R.id.log_submit_share_logs_button);
    shareLinkButton = view.findViewById(R.id.log_submit_share_link_button);

    copyButton.setOnClickListener(v -> {
      if (collectedLogs != null && !collectedLogs.isEmpty()) {
        copyToClipboard("SMSecure logs", collectedLogs);
        Toast.makeText(requireContext(), R.string.log_submit_copied, Toast.LENGTH_SHORT).show();
      } else {
        notifyFailure();
      }
    });

    shareLogsButton.setOnClickListener(v -> {
      if (collectedLogs != null && !collectedLogs.isEmpty()) {
        shareLogsAsFile(collectedLogs);
      } else {
        notifyFailure();
      }
    });

    shareLinkButton.setOnClickListener(v -> {
      if (uploadedUrl != null && !uploadedUrl.trim().isEmpty()) {
        shareText("SMSecure log URL", uploadedUrl.trim());
      } else {
        notifyFailure();
      }
    });

    collectLogsAsync();
  }

  private void collectLogsAsync() {
    final Context appContext = requireContext().getApplicationContext();

    executor.execute(() -> {
      final String logs = LogCollector.collect(appContext);

      if (!isAdded()) return;

      final android.app.Activity activity = getActivity();
      if (activity == null) return;

      if (logs.isEmpty()) {
        activity.runOnUiThread(() -> showError("LogCollector returned empty result"));
        return;
      }

      if (logs.startsWith("LogCollector error:")) {
        activity.runOnUiThread(() -> showError(logs));
        return;
      }

      collectedLogs = logs;

      activity.runOnUiThread(() -> {
        status.setText(R.string.log_submit_activity__uploading_logs);
        preview.setText(limitForPreview(logs));
        copyButton.setEnabled(true);
        shareLogsButton.setEnabled(true);
      });

      PasteResult result = PasteService.upload(logs);

      if (!isAdded()) return;

      final android.app.Activity activity2 = getActivity();
      if (activity2 == null) return;

      activity2.runOnUiThread(() -> {
        progress.setVisibility(View.GONE);

        if (result.success && result.url != null) {
          uploadedUrl = result.url;
          copyToClipboard("log url", result.url);
          showUrl(result.url);
          notifySuccess();
        } else {
          String message = (result.error != null) ? result.error : "Paste upload failed";
          showUploadFailure(message);
          notifyFailure();
        }
      });
    });
  }

  private void showError(@NonNull String message) {
    progress.setVisibility(View.GONE);
    status.setText(R.string.log_submit_activity__log_collect_failed);
    preview.setText(message);

    copyButton.setEnabled(collectedLogs != null && !collectedLogs.isEmpty());
    shareLogsButton.setEnabled(collectedLogs != null && !collectedLogs.isEmpty());
    shareLinkButton.setEnabled(false);
  }

  private void showUploadFailure(@NonNull String message) {
    status.setText(R.string.log_submit_activity__log_upload_failed);
    preview.setText(message);
    preview.append("\n\n" + limitForPreview(collectedLogs != null ? collectedLogs : ""));

    copyButton.setEnabled(collectedLogs != null && !collectedLogs.isEmpty());
    shareLogsButton.setEnabled(collectedLogs != null && !collectedLogs.isEmpty());
    shareLinkButton.setEnabled(false);
  }

  private void showUrl(@NonNull String url) {
    status.setText(R.string.log_submit_activity__log_uploaded);
    preview.setText(url);

    copyButton.setEnabled(collectedLogs != null && !collectedLogs.isEmpty());
    shareLogsButton.setEnabled(collectedLogs != null && !collectedLogs.isEmpty());
    shareLinkButton.setEnabled(true);
  }

  private void copyToClipboard(@NonNull String label, @NonNull String text) {
    ClipboardManager clipboard =
            (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);

    if (clipboard != null) {
      ClipData clip = ClipData.newPlainText(label, text);
      clipboard.setPrimaryClip(clip);
    }
  }

  private void shareText(@NonNull String subject, @NonNull String text) {
    try {
      Intent send = new Intent(Intent.ACTION_SEND);
      send.setType("text/plain");
      send.putExtra(Intent.EXTRA_SUBJECT, subject);
      send.putExtra(Intent.EXTRA_TEXT, text);
      startActivity(Intent.createChooser(send, getString(R.string.log_submit__button_share_link)));
      CrashLogCapture.clearCrashReport(requireContext().getApplicationContext());
      notifySuccess();
    } catch (ActivityNotFoundException e) {
      notifyFailure();
    }
  }

  private void shareLogsAsFile(@NonNull String logs) {
    try {
      File dir = new File(requireContext().getCacheDir(), "shares");
      //noinspection ResultOfMethodCallIgnored
      dir.mkdirs();

      File file = new File(dir, "smsecure-logcat.txt");
      try (FileOutputStream os = new FileOutputStream(file, false)) {
        os.write(logs.getBytes(StandardCharsets.UTF_8));
      }

      Uri uri = FileProvider.getUriForFile(
              requireContext(),
              requireContext().getPackageName() + ".fileprovider",
              file
      );

      Intent send = new Intent(Intent.ACTION_SEND);
      send.setType("text/plain");
      send.putExtra(Intent.EXTRA_SUBJECT, "SMSecure logs");
      send.putExtra(Intent.EXTRA_STREAM, uri);
      send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

      startActivity(Intent.createChooser(send, getString(R.string.log_submit__button_share_logs)));
      CrashLogCapture.clearCrashReport(requireContext().getApplicationContext());
      notifySuccess();
    } catch (Exception e) {
      notifyFailure();
    }
  }

  private String limitForPreview(@NonNull String text) {
    final int max = 40_000;
    if (text.length() <= max) return text;
    return text.substring(text.length() - max);
  }

  private void notifySuccess() {
    OnLogSubmittedListener listener = getListener();
    if (listener != null) listener.onSuccess();
  }

  private void notifyFailure() {
    OnLogSubmittedListener listener = getListener();
    if (listener != null) listener.onFailure();
  }

  @Nullable
  private OnLogSubmittedListener getListener() {
    if (getActivity() instanceof OnLogSubmittedListener) {
      return (OnLogSubmittedListener) getActivity();
    }
    return null;
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    progress = null;
    status = null;
    preview = null;
    copyButton = null;
    shareLogsButton = null;
    shareLinkButton = null;
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    executor.shutdownNow();
  }

  public interface OnLogSubmittedListener {
    void onSuccess();

    void onFailure();
  }
}