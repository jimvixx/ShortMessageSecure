package org.jimvixx.smsecure.jobs.requirements;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;

import org.whispersystems.jobqueue.requirements.RequirementListener;
import org.whispersystems.jobqueue.requirements.RequirementProvider;

import java.util.concurrent.atomic.AtomicBoolean;

public class ServiceRequirementProvider implements RequirementProvider {

  private static final long SERVICE_STABILITY_DELAY_MILLIS = 5000L;

  private static volatile ServiceRequirementProvider instance;

  private final TelephonyManager telephonyManager;
  private final ServiceStateListener serviceStateListener;
  private final AtomicBoolean listeningForServiceState;
  private final Handler handler;
  private final Runnable serviceStableRunnable;

  private RequirementListener requirementListener;

  public ServiceRequirementProvider(Context context) {
    this.telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
    this.serviceStateListener = new ServiceStateListener();
    this.listeningForServiceState = new AtomicBoolean(false);
    this.handler = new Handler(Looper.getMainLooper());
    this.serviceStableRunnable = this::handleStableService;
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

  private void startListening() {
    if (listeningForServiceState.compareAndSet(false, true)) {
      handler.removeCallbacks(serviceStableRunnable);

      try {
        telephonyManager.listen(serviceStateListener, PhoneStateListener.LISTEN_SERVICE_STATE);
      } catch (SecurityException e) {
        listeningForServiceState.set(false);
      }
    }
  }

  private void handleStableService() {
    if (listeningForServiceState.compareAndSet(true, false)) {
      telephonyManager.listen(serviceStateListener, PhoneStateListener.LISTEN_NONE);
    }

    if (requirementListener != null) {
      requirementListener.onRequirementStatusChanged();
    }
  }

  private class ServiceStateListener extends PhoneStateListener {
    @Override
    public void onServiceStateChanged(ServiceState serviceState) {
      handler.removeCallbacks(serviceStableRunnable);

      if (serviceState.getState() == ServiceState.STATE_IN_SERVICE) {
        handler.postDelayed(serviceStableRunnable, SERVICE_STABILITY_DELAY_MILLIS);
      }
    }
  }
}
