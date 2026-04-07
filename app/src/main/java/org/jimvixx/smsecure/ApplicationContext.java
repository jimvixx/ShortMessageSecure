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

package org.jimvixx.smsecure;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.jimvixx.smsecure.database.EncryptedBackupExporter;
import org.jimvixx.smsecure.jobs.persistence.EncryptingJobSerializer;
import org.jimvixx.smsecure.jobs.requirements.MasterSecretRequirementProvider;
import org.jimvixx.smsecure.jobs.requirements.ServiceRequirementProvider;
import org.jimvixx.smsecure.logging.CrashLogCapture;
import org.jimvixx.smsecure.logging.JobQueueLoggerBridge;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.logging.SignalLoggerBridge;
import org.jimvixx.smsecure.notifications.NotificationChannels;
import org.jimvixx.smsecure.util.dualsim.SimChangedReceiver;
import org.whispersystems.jobqueue.JobManager;
import org.whispersystems.jobqueue.dependencies.DependencyInjector;
import org.whispersystems.jobqueue.logging.JobQueueLogProvider;
import org.whispersystems.jobqueue.requirements.NetworkRequirementProvider;
import org.whispersystems.libsignal.logging.SignalProtocolLoggerProvider;

public final class ApplicationContext extends Application implements DependencyInjector {

  private static final String TAG = ApplicationContext.class.getSimpleName();

  private static volatile ApplicationContext instance;
  @Nullable
  private JobManager jobManager;
  @Nullable
  private PanicLockReceiver panicLockReceiver;

  public ApplicationContext() {
    super();
  }

  @NonNull
  public static ApplicationContext getInstance(@NonNull Context context) {
    Context appContext = context.getApplicationContext();
    if (appContext instanceof ApplicationContext) {
      return (ApplicationContext) appContext;
    }
    throw new IllegalStateException("Application context is not ApplicationContext: " + appContext);
  }

  @NonNull
  public static ApplicationContext getInstance() {
    ApplicationContext local = instance;
    if (local == null) {
      throw new IllegalStateException("ApplicationContext not initialized");
    }
    return local;
  }

  @Override
  public void onCreate() {
    EncryptedBackupExporter.applyPendingRestoreIfAny(this);

    super.onCreate();

    instance = this;
    Log.initialize(this);

    Log.initialize(this);
    JobQueueLogProvider.setLogger(new JobQueueLoggerBridge());

    CrashLogCapture.install(this);

    initializeLogging();
    initializeJobManager();
    checkSimState();

    NotificationChannels.create(this);

    registerPanicLockReceiver();
  }

  @Override
  public void injectDependencies(@NonNull Object object) {
    // No-op for now.
  }

  @NonNull
  public JobManager getJobManager() {
    JobManager jm = jobManager;
    if (jm == null) {
      throw new IllegalStateException("JobManager is not initialized yet.");
    }
    return jm;
  }

  private void initializeLogging() {
    SignalProtocolLoggerProvider.setProvider(new SignalLoggerBridge());
  }

  private void initializeJobManager() {
    jobManager = JobManager.newBuilder(this)
            .withName("SMSecureJobs")
            .withDependencyInjector(this)
            .withJobSerializer(new EncryptingJobSerializer())
            .withRequirementProviders(
                    new MasterSecretRequirementProvider(this),
                    new ServiceRequirementProvider(this),
                    new NetworkRequirementProvider(this)
            )
            .withConsumerThreads(5)
            .build();
  }

  private void checkSimState() {
    SimChangedReceiver.checkSimState(this);
  }

  /**
   * Registers runtime receiver that triggers panic on screen-off.
   * This works only while the app process is alive.
   */
  private void registerPanicLockReceiver() {
    try {
      if (panicLockReceiver != null) {
        Log.w(TAG, "PanicLockReceiver already registered -> skipping");
        return;
      }

      panicLockReceiver = new PanicLockReceiver();

      IntentFilter filter = new IntentFilter();
      filter.addAction(Intent.ACTION_SCREEN_OFF);

      ContextCompat.registerReceiver(
              this,
              panicLockReceiver,
              filter,
              ContextCompat.RECEIVER_NOT_EXPORTED
      );

      Log.w(TAG, "PanicLockReceiver registered (runtime)");
    } catch (Throwable t) {
      Log.w(TAG, "Failed to register PanicLockReceiver", t);
      panicLockReceiver = null;
    }
  }
}