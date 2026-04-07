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

package org.jimvixx.smsecure;

import static org.jimvixx.smsecure.util.ViewUtil.requireById;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcelable;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.database.SmsMigrator.ProgressDescription;
import org.jimvixx.smsecure.service.ApplicationMigrationService;
import org.jimvixx.smsecure.service.ApplicationMigrationService.ImportState;

import java.lang.ref.WeakReference;

public class DatabaseMigrationActivity extends PassphraseRequiredActionBarActivity {

  public static final String EXTRA_NEXT_SCREEN = "next_screen";
  public static final String NEXT_SCREEN_CONVERSATION_LIST = "conversation_list";

  private final ImportServiceConnection serviceConnection = new ImportServiceConnection();
  private final ImportStateHandler importStateHandler = new ImportStateHandler(this);
  private final BroadcastReceiver completedReceiver = new NullReceiver();

  private ProgressBar progress;
  private TextView progressLabel;
  private ApplicationMigrationService importService;
  private boolean isVisible = false;
  private boolean isBound = false;
  private boolean importStarted = false;

  @Override
  protected void onCreate(Bundle bundle, @NonNull MasterSecret masterSecret) {
    super.onCreate(bundle, masterSecret);

    setContentView(R.layout.database_migration_activity);

    androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);

    if (getSupportActionBar() != null) {
      getSupportActionBar().setDisplayHomeAsUpEnabled(false);
      getSupportActionBar().setHomeButtonEnabled(false);
    }

    getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
      @Override
      public void handleOnBackPressed() {
        // Intentionally blocked during migration.
      }
    });

    initializeViews();
    initializeServiceBinding();
    startImportIfNeeded();
  }

  @Override
  public void onResume() {
    super.onResume();
    isVisible = true;
    registerForCompletedNotification();
  }

  @Override
  public void onPause() {
    super.onPause();
    isVisible = false;
    unregisterForCompletedNotification();
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    shutdownServiceBinding();
  }

  @Override
  public boolean onSupportNavigateUp() {
    return true;
  }

  private void initializeViews() {
    progress = requireById(this, R.id.import_progress);
    progressLabel = requireById(this, R.id.import_status);
  }

  private void initializeServiceBinding() {
    Intent intent = new Intent(this, ApplicationMigrationService.class);
    isBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
  }

  private void startImportIfNeeded() {
    if (importStarted) return;
    importStarted = true;

    Intent intent = new Intent(this, ApplicationMigrationService.class);
    intent.setAction(ApplicationMigrationService.MIGRATE_DATABASE);

    Parcelable ms = getIntent().getParcelableExtra("master_secret");
    if (ms != null) {
      intent.putExtra("master_secret", ms);
    }

    startService(intent);
  }

  private void registerForCompletedNotification() {
    IntentFilter filter = new IntentFilter();
    filter.addAction(ApplicationMigrationService.COMPLETED_ACTION);
    filter.setPriority(1000);

    ContextCompat.registerReceiver(
            this,
            completedReceiver,
            filter,
            null,
            null,
            ContextCompat.RECEIVER_NOT_EXPORTED
    );
  }

  private void unregisterForCompletedNotification() {
    try {
      unregisterReceiver(completedReceiver);
    } catch (IllegalArgumentException ignored) {
      // Receiver was not registered.
    }
  }

  private void shutdownServiceBinding() {
    if (importService != null) {
      importService.setImportStateHandler(null);
      importService = null;
    }

    if (isBound) {
      try {
        unbindService(serviceConnection);
      } catch (IllegalArgumentException ignored) {
        // Already unbound.
      }
      isBound = false;
    }
  }

  private void handleStateIdle() {
    if (ApplicationMigrationService.isDatabaseNotImported(this)) {
      startImportIfNeeded();
    } else {
      handleImportComplete();
    }
  }

  private void handleStateProgress(@NonNull ProgressDescription update) {
    if (progressLabel != null) {
      progressLabel.setText(getString(
              R.string.database_migration_activity__progress,
              update.primaryComplete,
              update.primaryTotal
      ));
    }

    if (progress == null) return;

    double max = progress.getMax();

    double primaryTotal = Math.max(1.0, update.primaryTotal);
    double primaryComplete = Math.max(0.0, update.primaryComplete);
    double secondaryTotal = Math.max(1.0, update.secondaryTotal);
    double secondaryComplete = Math.max(0.0, update.secondaryComplete);

    progress.setProgress((int) Math.round((primaryComplete / primaryTotal) * max));
    progress.setSecondaryProgress((int) Math.round((secondaryComplete / secondaryTotal) * max));
  }

  private void handleImportComplete() {
    if (isVisible) {
      Intent intent = new Intent(this, ConversationListActivity.class)
              .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
      startActivity(intent);
    }

    finish();
  }

  private static class ImportStateHandler extends android.os.Handler {
    private final WeakReference<DatabaseMigrationActivity> activityRef;

    ImportStateHandler(@NonNull DatabaseMigrationActivity activity) {
      super(Looper.getMainLooper());
      this.activityRef = new WeakReference<>(activity);
    }

    @Override
    public void handleMessage(@NonNull Message message) {
      DatabaseMigrationActivity activity = activityRef.get();
      if (activity == null || activity.isFinishing()) return;

      switch (message.what) {
        case ImportState.STATE_IDLE:
          activity.handleStateIdle();
          break;
        case ImportState.STATE_MIGRATING_IN_PROGRESS:
          if (message.obj instanceof ProgressDescription) {
            activity.handleStateProgress((ProgressDescription) message.obj);
          }
          break;
        case ImportState.STATE_MIGRATING_COMPLETE:
          activity.handleImportComplete();
          break;
      }
    }
  }

  private static class NullReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
      abortBroadcast();
    }
  }

  private class ImportServiceConnection implements ServiceConnection {
    @Override
    public void onServiceConnected(ComponentName className, IBinder service) {
      importService = ((ApplicationMigrationService.ApplicationMigrationBinder) service).getService();
      importService.setImportStateHandler(importStateHandler);

      ImportState state = importService.getState();
      importStateHandler.obtainMessage(state.state, state.progress).sendToTarget();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
      if (importService != null) {
        importService.setImportStateHandler(null);
        importService = null;
      }
    }
  }
}