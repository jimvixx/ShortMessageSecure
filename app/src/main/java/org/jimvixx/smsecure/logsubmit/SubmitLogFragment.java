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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.logging.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class SubmitLogFragment extends Fragment {
  private SubmitLogViewModel model;
  private final ActivityResultLauncher<String> saveLog = registerForActivityResult(
          new ActivityResultContracts.CreateDocument("text/plain"), this::saveLogs);

  public static SubmitLogFragment newInstance() {
    return new SubmitLogFragment();
  }

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.submit_log_fragment, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    model = new ViewModelProvider(this).get(SubmitLogViewModel.class);
    TextView status = view.findViewById(R.id.log_submit_status);
    TextView preview = view.findViewById(R.id.log_submit_preview);
    ProgressBar progress = view.findViewById(R.id.log_submit_progress);
    Button save = view.findViewById(R.id.log_submit_save_button);
    Button shareInfo = view.findViewById(R.id.log_submit_share_link_button);
    Button shareLog = view.findViewById(R.id.log_submit_share_logs_button);
    save.setOnClickListener(v -> saveLog.launch("smsecure-logcat.txt"));
    shareInfo.setOnClickListener(v -> shareInfo());
    shareLog.setOnClickListener(v -> shareLogs());
    model.state.observe(getViewLifecycleOwner(), state -> {
      status.setText(state.status);
      preview.setText(state.info == null ? "" : state.info + "\n\n" + getString(R.string.log_submit_activity__thanks));
      progress.setVisibility(state.busy ? View.VISIBLE : View.GONE);
      save.setEnabled(model.logs != null);
      shareLog.setEnabled(model.logs != null);
      shareInfo.setEnabled(state.info != null);
    });
    model.start();
  }

  private void saveLogs(@Nullable Uri uri) {
    if (uri == null) return;
    Context context = requireContext().getApplicationContext();
    String logs = model.logs;
    File cachedLog = model.getCachedLog();
    new Thread(() -> {
      int message = R.string.log_submit_saved;
      try (OutputStream out = context.getContentResolver().openOutputStream(uri, "wt")) {
        if (out == null) throw new java.io.IOException("No output stream");
        if (logs != null) {
          out.write(logs.getBytes(StandardCharsets.UTF_8));
        } else {
          try (java.io.FileInputStream input = new java.io.FileInputStream(cachedLog)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
          }
        }
      } catch (Exception e) {
        Log.w("SubmitLogFragment", e);
        message = R.string.log_submit_save_failed;
      }
      int result = message;
      new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
              Toast.makeText(context, result, Toast.LENGTH_SHORT).show());
    }, "SMSecure-LogExport").start();
  }

  private void shareInfo() {
    SubmitLogViewModel.State state = model.state.getValue();
    if (state == null || state.info == null) return;
    Intent intent = new Intent(Intent.ACTION_SEND);
    intent.setType("text/plain");
    intent.putExtra(Intent.EXTRA_SUBJECT, "SMSecure log upload info");
    intent.putExtra(Intent.EXTRA_TEXT, state.info);
    launchShare(intent, R.string.log_submit_share_info);
  }

  private void shareLogs() {
    if (model.logs == null) return;
    try {
      File dir = new File(requireContext().getCacheDir(), "shares");
      if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("Cannot create share directory");
      File file = new File(dir, "smsecure-logcat.txt");
      try (FileOutputStream out = new FileOutputStream(file)) {
        out.write(model.logs.getBytes(StandardCharsets.UTF_8));
      }
      Uri uri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".fileprovider", file);
      Intent intent = new Intent(Intent.ACTION_SEND);
      intent.setType("text/plain");
      intent.putExtra(Intent.EXTRA_SUBJECT, "SMSecure logs");
      intent.putExtra(Intent.EXTRA_STREAM, uri);
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      launchShare(intent, R.string.log_submit__button_share_logs);
    } catch (Exception e) {
      Log.w("SubmitLogFragment", e);
      Toast.makeText(requireContext(), R.string.log_submit_share_failed, Toast.LENGTH_SHORT).show();
    }
  }

  private void launchShare(Intent intent, int title) {
    try {
      startActivity(Intent.createChooser(intent, getString(title)));
    } catch (ActivityNotFoundException e) {
      Toast.makeText(requireContext(), R.string.log_submit_share_failed, Toast.LENGTH_SHORT).show();
    }
  }
}
