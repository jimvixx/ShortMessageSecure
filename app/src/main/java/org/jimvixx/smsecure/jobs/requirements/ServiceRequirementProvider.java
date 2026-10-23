package org.jimvixx.smsecure.jobs.requirements;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import org.whispersystems.jobqueue.requirements.RequirementListener;
import org.whispersystems.jobqueue.requirements.RequirementProvider;

import java.util.concurrent.atomic.AtomicBoolean;

public class ServiceRequirementProvider implements RequirementProvider {

  private static final long SERVICE_STABILITY_DELAY_MILLIS = 5000L;

  private static volatile ServiceRequirementProvider instance;

  private final TelephonyManager telephonyManager;
  private final Context context;
  private final LegacyServiceStateListener legacyServiceStateListener;
  private final ModernServiceStateListener modernServiceStateListener;
  private final AtomicBoolean listeningForServiceState;
  private final Handler handler;
  private final Runnable serviceStableRunnable;
  private final Runnable retryReadyRunnable;
  private long retryDeadlineMillis;

  private RequirementListener requirementListener;

  public ServiceRequirementProvider(Context context) {
    this.context = context.getApplicationContext();
    this.telephonyManager = (TelephonyManager) this.context.getSystemService(Context.TELEPHONY_SERVICE);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      this.legacyServiceStateListener = null;
      this.modernServiceStateListener = new ModernServiceStateListener();
    } else {
      this.legacyServiceStateListener = new LegacyServiceStateListener();
      this.modernServiceStateListener = null;
    }
    this.listeningForServiceState = new AtomicBoolean(false);
    this.handler = new Handler(Looper.getMainLooper());
    this.serviceStableRunnable = this::handleStableService;
    this.retryReadyRunnable = this::handleRetryReady;
    this.retryDeadlineMillis = 0;
    instance = this;
  }

  @Override
  public void setListener(RequirementListener requirementListener) {
    this.requirementListener = requirementListener;
  }

  static void listenForServiceState() {
    ServiceRequirementProvider provider = instance;

    if (provider != null) {
      provider.startListening();
    }
  }

  static boolean isServiceReady() {
    ServiceRequirementProvider provider = instance;
    return provider == null || !provider.listeningForServiceState.get();
  }

  static void waitUntil(long deadlineMillis) {
    ServiceRequirementProvider provider = instance;
    if (provider != null) {
      provider.scheduleRetryReady(deadlineMillis);
    }
  }

  private synchronized void scheduleRetryReady(long deadlineMillis) {
    if (retryDeadlineMillis == 0 || deadlineMillis < retryDeadlineMillis) {
      retryDeadlineMillis = deadlineMillis;
      handler.removeCallbacks(retryReadyRunnable);
      handler.postDelayed(retryReadyRunnable,
              Math.max(0, deadlineMillis - System.currentTimeMillis()));
    }
  }

  private void handleRetryReady() {
    synchronized (this) {
      if (retryDeadlineMillis > System.currentTimeMillis()) {
        handler.postDelayed(retryReadyRunnable,
                retryDeadlineMillis - System.currentTimeMillis());
        return;
      }
      retryDeadlineMillis = 0;
    }

    if (requirementListener != null) {
      requirementListener.onRequirementStatusChanged();
    }
  }

  private void startListening() {
    if (listeningForServiceState.compareAndSet(false, true)) {
      handler.removeCallbacks(serviceStableRunnable);

      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          telephonyManager.registerTelephonyCallback(
                  context.getMainExecutor(), modernServiceStateListener);
        } else {
          listenForServiceStateLegacy();
        }
      } catch (SecurityException e) {
        listeningForServiceState.set(false);
      }
    }
  }

  private void handleStableService() {
    if (listeningForServiceState.compareAndSet(true, false)) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        telephonyManager.unregisterTelephonyCallback(modernServiceStateListener);
      } else {
        stopListeningForServiceStateLegacy();
      }
    }

    if (requirementListener != null) {
      requirementListener.onRequirementStatusChanged();
    }
  }

  @SuppressWarnings("deprecation")
  private void listenForServiceStateLegacy() {
    telephonyManager.listen(legacyServiceStateListener, PhoneStateListener.LISTEN_SERVICE_STATE);
  }

  @SuppressWarnings("deprecation")
  private void stopListeningForServiceStateLegacy() {
    telephonyManager.listen(legacyServiceStateListener, PhoneStateListener.LISTEN_NONE);
  }

  @SuppressWarnings("deprecation")
  private class LegacyServiceStateListener extends PhoneStateListener {
    @Override
    public void onServiceStateChanged(@NonNull ServiceState serviceState) {
      handleServiceStateChanged(serviceState);
    }
  }

  @RequiresApi(Build.VERSION_CODES.S)
  private class ModernServiceStateListener extends TelephonyCallback
          implements TelephonyCallback.ServiceStateListener {
    @Override
    public void onServiceStateChanged(@NonNull ServiceState serviceState) {
      handleServiceStateChanged(serviceState);
    }
  }

  private void handleServiceStateChanged(ServiceState serviceState) {
    handler.removeCallbacks(serviceStableRunnable);

    if (serviceState.getState() == ServiceState.STATE_IN_SERVICE) {
      handler.postDelayed(serviceStableRunnable, SERVICE_STABILITY_DELAY_MILLIS);
    }
  }
}
