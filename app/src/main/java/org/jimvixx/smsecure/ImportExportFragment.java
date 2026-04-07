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

package org.jimvixx.smsecure;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import org.jimvixx.smsecure.logging.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.os.BundleCompat;
import androidx.fragment.app.Fragment;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.EncryptedBackupExporter;
import org.jimvixx.smsecure.database.PlaintextBackupExporter;
import org.jimvixx.smsecure.database.PlaintextBackupImporter;
import org.jimvixx.smsecure.permissions.Permissions;
import org.jimvixx.smsecure.service.ApplicationMigrationService;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/*
 * Fragment that provides Import/Export actions for SMSecure data.
 * Notes:
 * - All UI work is executed on the main thread.
 * - Background work uses a dedicated single-thread executor that is shut down on destroy.
 */
public class ImportExportFragment extends Fragment {

  private static final String TAG = ImportExportFragment.class.getSimpleName();

  private static final int SUCCESS  = 0;
  private static final int ERROR_IO = 2;

  @Nullable private MasterSecret masterSecret;

  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

  @Nullable private AlertDialog progressDialog;

  private ActivityResultLauncher<String>   createPlaintextDocLauncher;
  private ActivityResultLauncher<String>   createEncryptedZipLauncher;
  private ActivityResultLauncher<String[]> openEncryptedZipLauncher;
  private ActivityResultLauncher<String[]> openPlaintextXmlLauncher;

  @Override
  public void onCreate(@Nullable Bundle bundle) {
    super.onCreate(bundle);

    // Read MasterSecret from fragment arguments.
    // If it is missing, we log the error; actions will show a toast and no-op.
    Bundle args = getArguments();
    if (args != null) {
      masterSecret = BundleCompat.getParcelable(args, "master_secret", MasterSecret.class);
    }

    // Create plaintext backup document.
    createPlaintextDocLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.CreateDocument("text/xml"),
                    uri -> { if (uri != null) exportPlaintextToUri(uri); }
            );

    // Pick plaintext backup file.
    openPlaintextXmlLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    uri -> { if (uri != null) importPlaintextFromUri(uri); }
            );

    // Create encrypted zip backup.
    createEncryptedZipLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.CreateDocument("application/zip"),
                    uri -> { if (uri != null) exportEncryptedZipToUri(uri); }
            );

    // Pick encrypted zip for restore.
    openEncryptedZipLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    uri -> { if (uri != null) importEncryptedZipFromUri(uri); }
            );
  }

  @Override
  public View onCreateView(@NonNull LayoutInflater inflater,
                           @Nullable ViewGroup container,
                           @Nullable Bundle bundle) {
    View layout              = inflater.inflate(R.layout.import_export_fragment, container, false);
    View importSmsView       = layout.findViewById(R.id.import_sms);
    View importEncryptedView = layout.findViewById(R.id.import_encrypted_backup);
    View importPlaintextView = layout.findViewById(R.id.import_plaintext_backup);
    View exportEncryptedView = layout.findViewById(R.id.export_encrypted_backup);
    View exportPlaintextView = layout.findViewById(R.id.export_plaintext_backup);

    importSmsView.setOnClickListener(v -> handleImportSms());
    importEncryptedView.setOnClickListener(v -> handleImportEncryptedBackup());
    importPlaintextView.setOnClickListener(v -> handleImportPlaintextBackup());
    exportEncryptedView.setOnClickListener(v -> handleExportEncryptedBackup());
    exportPlaintextView.setOnClickListener(v -> handleExportPlaintextBackup());

    return layout;
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    dismissProgressDialogIfShowing();
  }

  @Override
  public void onDestroy() {
    // Ensure background work does not keep fragment/activity alive.
    backgroundExecutor.shutdownNow();
    super.onDestroy();
  }

  private void dismissProgressDialogIfShowing() {
    if (progressDialog != null) {
      progressDialog.dismiss();
      progressDialog = null;
    }
  }

  private void showBlockingProgress(@NonNull CharSequence title, @NonNull CharSequence message) {
    dismissProgressDialogIfShowing();

    ProgressBar progressBar = new ProgressBar(requireContext());
    progressBar.setIndeterminate(true);

    progressDialog = new AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setView(progressBar)
            .setCancelable(false)
            .show();
  }

  // Runs a background job and then delivers its integer result to a UI handler.
  private void runWithBlockingProgress(@NonNull CharSequence title,
                                       @NonNull CharSequence message,
                                       @NonNull BackgroundJob job,
                                       @NonNull ResultHandler handler) {
    if (!isAdded()) return;

    showBlockingProgress(title, message);

    final Context appContext = requireContext().getApplicationContext();

    backgroundExecutor.execute(() -> {
      final int result;
      try {
        result = job.run(appContext);
      } catch (Throwable t) {
        Log.w(TAG, t);

        // Always switch to the main thread before touching UI.
        mainHandler.post(() -> {
          dismissProgressDialogIfShowing();
          if (!isAdded()) return;

          Toast.makeText(
                  requireContext(),
                  getString(R.string.ExportFragment_error_while_writing_to_storage),
                  Toast.LENGTH_LONG
          ).show();
        });
        return;
      }

      mainHandler.post(() -> {
        dismissProgressDialogIfShowing();
        if (!isAdded()) return;
        handler.onResult(result);
      });
    });
  }

  // Returns false and shows a toast if MasterSecret is missing.
  // This protects the fragment from NPEs when launched incorrectly.
  private boolean requiresMasterSecret() {
    if (masterSecret != null) return false;

    if (isAdded()) {
      Toast.makeText(requireContext(), "Missing master secret", Toast.LENGTH_LONG).show();
    }
    Log.w(TAG, "MasterSecret is null. Was the fragment started with correct arguments?");
    return true;
  }

  private void handleImportSms() {
    if (requiresMasterSecret()) return;

    AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
    builder.setIconAttribute(R.attr.dialog_info_icon);
    builder.setTitle(getString(R.string.ImportFragment_import_system_sms_database));
    builder.setMessage(getString(R.string.ImportFragment_this_will_import_messages_from_the_system));
    builder.setPositiveButton(getString(R.string.Import), (dialog, which) ->
            Permissions.with(this)
                    .request(Manifest.permission.READ_SMS)
                    .ifNecessary()
                    .withPermanentDenialDialog(getString(
                            R.string.ImportExportFragment_smsecure_needs_the_sms_permission_in_order_to_import_sms_messages))
                    .onAllGranted(() -> {
                      if (!isAdded()) return;

                      Context ctx = requireContext();

                      Intent intent = new Intent(ctx, ApplicationMigrationService.class);
                      intent.setAction(ApplicationMigrationService.MIGRATE_DATABASE);
                      intent.putExtra("master_secret", masterSecret);
                      requireActivity().startService(intent);

                      Intent activityIntent = new Intent(ctx, DatabaseMigrationActivity.class);
                      activityIntent.putExtra(DatabaseMigrationActivity.EXTRA_NEXT_SCREEN,
                              DatabaseMigrationActivity.NEXT_SCREEN_CONVERSATION_LIST);
                      requireActivity().startActivity(activityIntent);
                    })
                    .onAnyDenied(() -> {
                      if (!isAdded()) return;
                      Toast.makeText(
                              requireContext(),
                              R.string.ImportExportFragment_smsecure_needs_the_sms_permission_in_order_to_import_sms_messages_toast,
                              Toast.LENGTH_LONG
                      ).show();
                    })
                    .execute()
    );
    builder.setNegativeButton(getString(R.string.Cancel), null);
    builder.show();
  }

  private void handleExportPlaintextBackup() {
    if (requiresMasterSecret()) return;

    AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
    builder.setIconAttribute(R.attr.dialog_alert_icon);
    builder.setTitle(getString(R.string.ExportFragment_export_plaintext_to_storage));
    builder.setMessage(getString(R.string.ExportFragment_warning_this_will_export_the_contents_of_your_messages_to_storage_in_plaintext));
    builder.setPositiveButton(getString(R.string.ExportFragment_export), (dialog, which) -> {
      // Use a timestamped file name to avoid collisions.
      String name = "SMSecurePlaintextBackup-" + System.currentTimeMillis() + ".xml";
      createPlaintextDocLauncher.launch(name);
    });
    builder.setNegativeButton(getString(R.string.Cancel), null);
    builder.show();
  }

  private void exportPlaintextToUri(@NonNull Uri uri) {
    if (requiresMasterSecret()) return;
    if (masterSecret == null) return;

    runWithBlockingProgress(
            getString(R.string.ExportFragment_exporting),
            getString(R.string.ExportFragment_exporting_plaintext_to_storage),
            appContext -> {
              try {
                PlaintextBackupExporter.exportPlaintextToUri(appContext, masterSecret, uri);
                return SUCCESS;
              } catch (IOException e) {
                Log.w(TAG, e);
                return ERROR_IO;
              }
            },
            result -> {
              if (result == SUCCESS) {
                Toast.makeText(requireContext(), getString(R.string.ExportFragment_export_successful), Toast.LENGTH_LONG).show();
              } else {
                Toast.makeText(requireContext(), getString(R.string.ExportFragment_error_while_writing_to_storage), Toast.LENGTH_LONG).show();
              }
            }
    );
  }

  private void handleImportPlaintextBackup() {
    if (requiresMasterSecret()) return;

    AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
    builder.setIconAttribute(R.attr.dialog_alert_icon);
    builder.setTitle(getString(R.string.ImportFragment_import_plaintext_backup));
    builder.setMessage(getString(R.string.ImportFragment_this_will_import_messages_from_a_plaintext_backup));
    builder.setPositiveButton(getString(R.string.Import), (dialog, which) -> {
      // This MUST show a system file picker.
      openPlaintextXmlLauncher.launch(new String[]{
              "text/xml",
              "application/xml",
              "application/octet-stream",
              "*/*"
      });
    });
    builder.setNegativeButton(getString(R.string.Cancel), null);
    builder.show();
  }

  private void importPlaintextFromUri(@NonNull Uri uri) {
    if (requiresMasterSecret()) return;
    if (masterSecret == null) return;

    runWithBlockingProgress(
            getString(R.string.Importing),
            getString(R.string.ImportFragment_import_plaintext_backup_ellipse),
            appContext -> {
              try {
                PlaintextBackupImporter.importPlaintextFromUri(appContext, masterSecret, uri);
                return SUCCESS;
              } catch (IOException e) {
                Log.w(TAG, e);
                return ERROR_IO;
              }
            },
            result -> {
              if (result == SUCCESS) {
                Toast.makeText(requireContext(), getString(R.string.ImportFragment_import_complete), Toast.LENGTH_LONG).show();
              } else {
                Toast.makeText(requireContext(), getString(R.string.ImportFragment_error_importing_backup), Toast.LENGTH_LONG).show();
              }
            }
    );
  }

  private void handleExportEncryptedBackup() {
    if (requiresMasterSecret()) return;

    AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
    builder.setIconAttribute(R.attr.dialog_info_icon);
    builder.setTitle(getString(R.string.ExportFragment_export_encrypted_backup));
    builder.setMessage(getString(R.string.ExportFragment_this_will_export_your_encrypted_keys_settings_and_messages));
    builder.setPositiveButton(getString(R.string.ExportFragment_export), (dialog, which) -> {
      // Use a timestamped file name to avoid collisions.
      String name = "SMSecureExport-" + System.currentTimeMillis() + ".zip";
      createEncryptedZipLauncher.launch(name);
    });
    builder.setNegativeButton(getString(R.string.Cancel), null);
    builder.show();
  }

  private void exportEncryptedZipToUri(@NonNull Uri uri) {
    runWithBlockingProgress(
            getString(R.string.ExportFragment_exporting),
            getString(R.string.ExportFragment_exporting_keys_settings_and_messages),
            appContext -> {
              try {
                EncryptedBackupExporter.exportToUri(appContext, uri);
                return SUCCESS;
              } catch (IOException e) {
                Log.w(TAG, e);
                return ERROR_IO;
              }
            },
            result -> {
              if (result == SUCCESS) {
                Toast.makeText(requireContext(), getString(R.string.ExportFragment_export_successful), Toast.LENGTH_LONG).show();
              } else {
                Toast.makeText(requireContext(), getString(R.string.ExportFragment_error_while_writing_to_storage), Toast.LENGTH_LONG).show();
              }
            }
    );
  }

  private void handleImportEncryptedBackup() {
    AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
    builder.setIconAttribute(R.attr.dialog_alert_icon);
    builder.setTitle(getString(R.string.ImportFragment_restore_encrypted_backup));
    builder.setMessage(getString(R.string.ImportFragment_restoring_an_encrypted_backup_will_completely_replace_your_existing_keys));
    builder.setPositiveButton(getString(R.string.Import), (dialog, which) ->
            openEncryptedZipLauncher.launch(new String[]{
                    "application/zip",
                    "application/octet-stream",
                    "*/*"
            })
    );
    builder.setNegativeButton(getString(R.string.Cancel), null);
    builder.show();
  }

  private void importEncryptedZipFromUri(@NonNull Uri uri) {
    runWithBlockingProgress(
            getString(R.string.Importing),
            getString(R.string.ImportFragment_restoring_encrypted_backup),
            appContext -> {
              try {
                EncryptedBackupExporter.stageImportFromUri(appContext, uri);
                return SUCCESS;
              } catch (IOException e) {
                Log.w(TAG, e);
                return ERROR_IO;
              }
            },
            result -> {
              if (result == SUCCESS) {
                Toast.makeText(
                        requireContext(),
                        R.string.import_export_fragment__backup_restored,
                        Toast.LENGTH_LONG
                ).show();

                // Delay so the toast has a chance to display.
                View v = getView();
                if (v != null) {
                  v.postDelayed(() ->
                          ExitActivity.exitAndRemoveFromRecentApps(requireActivity()), 700);
                } else {
                  // Fallback if the view is already destroyed.
                  mainHandler.postDelayed(() ->
                          ExitActivity.exitAndRemoveFromRecentApps(requireActivity()), 700);
                }
              } else {
                Toast.makeText(requireContext(), getString(R.string.ImportFragment_error_importing_backup), Toast.LENGTH_LONG).show();
              }
            }
    );
  }

  // Represents a background operation that returns a result code.
  private interface BackgroundJob {
    int run(@NonNull Context appContext);
  }

  // Handles the result code on the main thread.
  private interface ResultHandler {
    void onResult(int result);
  }
}
