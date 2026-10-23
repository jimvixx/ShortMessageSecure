package org.jimvixx.smsecure.jobs.requirements;

import android.content.Context;

import org.jimvixx.smsecure.sms.TelephonyServiceState;
import org.whispersystems.jobqueue.dependencies.ContextDependent;
import org.whispersystems.jobqueue.requirements.Requirement;

import java.io.Serial;

public class ServiceRequirement implements Requirement, ContextDependent {

  // Preserve compatibility with jobs persisted before the retry deadline was added.
  @Serial
  private static final long serialVersionUID = 557797171214192137L;

  private transient Context context;
  private final long notBeforeMillis;

  public ServiceRequirement(Context context) {
    this(context, 0);
  }

  public ServiceRequirement(Context context, long notBeforeMillis) {
    this.context = context;
    this.notBeforeMillis = notBeforeMillis;
  }

  @Override
  public void setContext(Context context) {
    this.context = context;
  }

  @Override
  public boolean isPresent() {
    if (notBeforeMillis > System.currentTimeMillis()) {
      ServiceRequirementProvider.waitUntil(notBeforeMillis);
      return false;
    }

    boolean connected = new TelephonyServiceState().isConnected(context);

    if (!connected) {
      ServiceRequirementProvider.listenForServiceState();
      return false;
    }

    return ServiceRequirementProvider.isServiceReady();
  }
}
