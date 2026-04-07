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

package org.jimvixx.smsecure.preferences.widgets;

import android.Manifest;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MergeCursor;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.CursorAdapter;
import android.widget.HeaderViewListAdapter;
import android.widget.ListView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.util.SMSecurePreferences;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RingtonePreferenceDialogFragmentCompat extends DialogFragment {

  private static final String TAG = RingtonePreferenceDialogFragmentCompat.class.getSimpleName();
  private static final String ARG_KEY = "key";
  private static final String CURSOR_DEFAULT_ID = "-2";
  private static final String CURSOR_NONE_ID = "-1";
  private static final String RECIPIENT_RINGTONE_KEY = "pref_key_recipient_ringtone";

  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private int selectedIndex = -1;

  @Nullable
  private Cursor cursor;

  @Nullable
  private RingtoneManager ringtoneManager;

  @Nullable
  private Ringtone defaultRingtone;

  @Nullable
  private ActivityResultLauncher<String[]> pickAudioLauncher;

  @Nullable
  private ActivityResultLauncher<String> requestWritePermissionLauncher;

  @Nullable
  private RingtonePreference preference;

  public static RingtonePreferenceDialogFragmentCompat newInstance(@NonNull String key) {
    RingtonePreferenceDialogFragmentCompat fragment = new RingtonePreferenceDialogFragmentCompat();
    Bundle args = new Bundle(1);
    args.putString(ARG_KEY, key);
    fragment.setArguments(args);
    return fragment;
  }

  @Nullable
  private static RingtonePreference findInFragment(@Nullable Fragment fragment, @NonNull String key) {
    if (fragment instanceof PreferenceFragmentCompat preferenceFragment) {
      Preference found = preferenceFragment.findPreference(key);
      if (found instanceof RingtonePreference ringtonePreference) {
        return ringtonePreference;
      }
    }

    if (fragment != null) {
      return findInFragmentManager(fragment.getChildFragmentManager(), key);
    }

    return null;
  }

  @Nullable
  private static RingtonePreference findInFragmentManager(@NonNull FragmentManager fragmentManager,
                                                          @NonNull String key) {
    for (Fragment fragment : fragmentManager.getFragments()) {
      RingtonePreference found = findInFragment(fragment, key);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private static int extraRowsCount(boolean showDefault, boolean showSilent) {
    int count = 0;
    if (showDefault) count++;
    if (showSilent) count++;
    return count;
  }

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    pickAudioLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
              if (uri == null) {
                toast(R.string.RingtonePreference_unable_to_add_ringtone);
                return;
              }

              Context context = getContext();
              if (context == null) {
                return;
              }

              try {
                context.getContentResolver().takePersistableUriPermission(
                        uri,
                        IntentFlags.READ_URI_PERMISSION
                );
              } catch (Exception ignore) {
              }

              installPickedRingtone(uri);
            }
    );

    requestWritePermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            granted -> {
              if (granted) {
                launchPickAudio();
              } else {
                toast(R.string.Permissions_permission_required);
              }
            }
    );
  }

  @Override
  public void onPause() {
    super.onPause();
    stopPlaying();
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    stopPlaying();
    closeCursorQuietly();
    io.shutdown();
  }

  @NonNull
  @Override
  public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
    preference = resolvePreferenceOrThrow();

    closeCursorQuietly();
    createCursor(preference.getRingtone());

    if (cursor == null) {
      throw new IllegalStateException("Cursor was not created");
    }

    Context context = requireContext();
    boolean showDefault = preference.isShowDefault();
    boolean showSilent = preference.isShowSilent();
    Uri defaultUri = showDefault ? RingtoneManager.getDefaultUri(preference.getRingtoneType()) : null;

    String titleColumn = cursor.getColumnName(RingtoneManager.TITLE_COLUMN_INDEX);

    AlertDialog dialog = new AlertDialog.Builder(context)
            .setTitle(preference.getTitle())
            .setSingleChoiceItems(cursor, selectedIndex, titleColumn, (dialogInterface, which) -> {
              if (cursor == null || ringtoneManager == null) {
                return;
              }

              if (which >= cursor.getCount()) {
                newRingtone();
                return;
              }

              selectedIndex = which;
              stopPlaying();

              if (showDefault && which == 0) {
                playDefaultRingtone(context, defaultUri);
                return;
              }

              if (showSilent && which == (showDefault ? 1 : 0)) {
                ringtoneManager.stopPreviousRingtone();
                return;
              }

              int realIndex = which - extraRowsCount(showDefault, showSilent);
              Ringtone ringtone = ringtoneManager.getRingtone(realIndex);
              if (ringtone != null) {
                ringtone.play();
              }
            })
            .setNegativeButton(android.R.string.cancel, (dialogInterface, which) -> {
              stopPlaying();
              dismissAllowingStateLoss();
            })
            .setPositiveButton(android.R.string.ok, (dialogInterface, which) -> {
              stopPlaying();
              applySelection();
              dismissAllowingStateLoss();
            })
            .create();

    dialog.setOnShowListener(ignored -> {
      if (preference == null || !preference.shouldShowAdd()) {
        return;
      }

      ListView listView = dialog.getListView();
      if (listView == null) {
        return;
      }

      if (listView.getFooterViewsCount() > 0) {
        return;
      }

      CursorAdapter adapter = extractCursorAdapter(listView);
      if (adapter == null) {
        return;
      }

      View footerView = getLayoutInflater().inflate(R.layout.add_ringtone_item, listView, false);
      footerView.setOnClickListener(v -> newRingtone());

      listView.addFooterView(footerView, null, true);
      listView.setAdapter(adapter);

      if (selectedIndex >= 0) {
        listView.setItemChecked(selectedIndex, true);
        listView.setSelection(selectedIndex);
      }
    });

    return dialog;
  }

  @Override
  public void onDismiss(@NonNull DialogInterface dialog) {
    stopPlaying();
    super.onDismiss(dialog);
  }

  private boolean isRecipientRingtonePreference() {
    return preference != null && RECIPIENT_RINGTONE_KEY.equals(preference.getKey());
  }

  @NonNull
  private String getDefaultRowTitle() {
    if (isRecipientRingtonePreference()) {
      return getString(R.string.RingtonePreference_application_default);
    }

    return getString(R.string.RingtonePreference_system_default);
  }

  @Nullable
  private Uri getApplicationDefaultRingtoneUri(@NonNull Context context) {
    String ringtone = SMSecurePreferences.getNotificationRingtone(context);

    if (TextUtils.isEmpty(ringtone)) {
      return null;
    }

    try {
      return Uri.parse(ringtone);
    } catch (Exception e) {
      Log.w(TAG, "Unable to parse application default ringtone", e);
      return null;
    }
  }

  private void playDefaultRingtone(@NonNull Context context, @Nullable Uri defaultUri) {
    if (defaultRingtone != null) {
      defaultRingtone.play();
      return;
    }

    Uri effectiveDefaultUri = isRecipientRingtonePreference()
            ? getApplicationDefaultRingtoneUri(context)
            : defaultUri;

    if (effectiveDefaultUri == null) {
      return;
    }

    defaultRingtone = RingtoneManager.getRingtone(context, effectiveDefaultUri);
    if (defaultRingtone != null) {
      defaultRingtone.play();
    }
  }

  private void applySelection() {
    if (preference == null || ringtoneManager == null || selectedIndex < 0) {
      return;
    }

    boolean showDefault = preference.isShowDefault();
    boolean showSilent = preference.isShowSilent();

    Uri uri;
    if (showDefault && selectedIndex == 0) {
      uri = RingtoneManager.getDefaultUri(preference.getRingtoneType());
    } else if (showSilent && selectedIndex == (showDefault ? 1 : 0)) {
      uri = null;
    } else {
      int realIndex = selectedIndex - extraRowsCount(showDefault, showSilent);
      uri = ringtoneManager.getRingtoneUri(realIndex);
    }

    if (preference.callChangeListener(uri)) {
      preference.setRingtone(uri);
    }
  }

  private void stopPlaying() {
    if (defaultRingtone != null && defaultRingtone.isPlaying()) {
      defaultRingtone.stop();
    }

    if (ringtoneManager != null) {
      ringtoneManager.stopPreviousRingtone();
    }
  }

  private void createCursor(@Nullable Uri ringtoneUri) {
    if (preference == null) {
      throw new IllegalStateException("Preference not resolved");
    }

    ringtoneManager = new RingtoneManager(requireContext());
    ringtoneManager.setType(preference.getRingtoneType());
    ringtoneManager.setStopPreviousRingtone(true);

    Cursor ringtoneCursor = ringtoneManager.getCursor();
    String idColumn = ringtoneCursor.getColumnName(RingtoneManager.ID_COLUMN_INDEX);
    String titleColumn = ringtoneCursor.getColumnName(RingtoneManager.TITLE_COLUMN_INDEX);

    @SuppressWarnings("resource")
    MatrixCursor extras = new MatrixCursor(new String[]{idColumn, titleColumn});

    boolean showDefault = preference.isShowDefault();
    boolean showSilent = preference.isShowSilent();

    if (showDefault) {
      extras.addRow(new String[]{CURSOR_DEFAULT_ID, getDefaultRowTitle()});
    }

    if (showSilent) {
      extras.addRow(new String[]{CURSOR_NONE_ID, getString(R.string.Silent)});
    }

    selectedIndex = ringtoneManager.getRingtonePosition(ringtoneUri);

    if (selectedIndex >= 0) {
      selectedIndex += extraRowsCount(showDefault, showSilent);
    } else if (showDefault && ringtoneUri != null && RingtoneManager.getDefaultType(ringtoneUri) != -1) {
      selectedIndex = 0;
    } else if (showSilent) {
      selectedIndex = showDefault ? 1 : 0;
    }

    Log.d(TAG, "Selected index = " + selectedIndex + ", uri = " + ringtoneUri);

    cursor = new MergeCursor(new Cursor[]{extras, ringtoneCursor});
  }

  private void newRingtone() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      launchPickAudio();
      return;
    }

    Context context = getContext();
    if (context == null || requestWritePermissionLauncher == null) {
      return;
    }

    if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED) {
      launchPickAudio();
    } else {
      requestWritePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }
  }

  private void launchPickAudio() {
    if (pickAudioLauncher != null) {
      pickAudioLauncher.launch(new String[]{"audio/*", "application/ogg"});
    }
  }

  private void installPickedRingtone(@NonNull Uri pickedUri) {
    Context context = getContext();
    if (context == null || preference == null) {
      return;
    }

    int ringtoneType = preference.getRingtoneType();

    io.execute(() -> {
      try {
        Uri newUri = RingtoneImportUtil.addCustomRingtoneToMediaStore(context, pickedUri, ringtoneType);
        runOnUiThread(() -> applyNewRingtoneUri(newUri));
      } catch (Exception e) {
        Log.e(TAG, "Unable to add new ringtone", e);
        postToast(R.string.RingtonePreference_unable_to_add_ringtone);
      }
    });
  }

  private void applyNewRingtoneUri(@NonNull Uri newUri) {
    Dialog dialog = getDialog();
    if (!(dialog instanceof AlertDialog alertDialog)) {
      return;
    }

    ListView listView = alertDialog.getListView();
    if (listView == null) {
      return;
    }

    CursorAdapter adapter = extractCursorAdapter(listView);
    if (adapter == null) {
      return;
    }

    closeCursorQuietly();
    createCursor(newUri);

    if (cursor == null) {
      return;
    }

    adapter.changeCursor(cursor);

    if (selectedIndex >= 0 && selectedIndex < cursor.getCount()) {
      listView.setItemChecked(selectedIndex, true);
      listView.setSelection(selectedIndex);
    }

    listView.clearFocus();
  }

  @Nullable
  private CursorAdapter extractCursorAdapter(@NonNull ListView listView) {
    if (listView.getAdapter() instanceof HeaderViewListAdapter headerAdapter
            && headerAdapter.getWrappedAdapter() instanceof CursorAdapter wrappedAdapter) {
      return wrappedAdapter;
    }

    if (listView.getAdapter() instanceof CursorAdapter directAdapter) {
      return directAdapter;
    }

    return null;
  }

  @NonNull
  private RingtonePreference resolvePreferenceOrThrow() {
    String key = getArguments() != null ? getArguments().getString(ARG_KEY) : null;
    if (key == null) {
      throw new IllegalStateException("Missing preference key");
    }

    RingtonePreference fromParentManager = findInFragmentManager(getParentFragmentManager(), key);
    if (fromParentManager != null) {
      return fromParentManager;
    }

    RingtonePreference fromActivityManager = findInFragmentManager(
            requireActivity().getSupportFragmentManager(),
            key
    );
    if (fromActivityManager != null) {
      return fromActivityManager;
    }

    throw new IllegalStateException("Unable to resolve RingtonePreference for key: " + key);
  }

  private void closeCursorQuietly() {
    try {
      if (cursor != null) {
        cursor.close();
      }
    } catch (Exception ignore) {
    } finally {
      cursor = null;
    }
  }

  private void toast(int resId) {
    Context context = getContext();
    if (context != null) {
      Toast.makeText(context, resId, Toast.LENGTH_SHORT).show();
    }
  }

  private void postToast(int resId) {
    runOnUiThread(() -> toast(resId));
  }

  private void runOnUiThread(@NonNull Runnable runnable) {
    if (isAdded()) {
      requireActivity().runOnUiThread(runnable);
    }
  }

  private static final class IntentFlags {
    static final int READ_URI_PERMISSION = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;

    private IntentFlags() {
    }
  }
}