package org.jimvixx.smsecure.jobs;

import android.content.Context;

import org.jimvixx.smsecure.crypto.MasterSecret;
import org.whispersystems.jobqueue.JobParameters;

public abstract class SendJob extends MasterSecretJob {

  private final static String TAG = SendJob.class.getSimpleName();

  public SendJob(Context context, JobParameters parameters) {
    super(context, parameters);
  }

  @Override
  public final void onRun(MasterSecret masterSecret) throws Exception {
    onSend(masterSecret);
  }

  protected abstract void onSend(MasterSecret masterSecret) throws Exception;

}
