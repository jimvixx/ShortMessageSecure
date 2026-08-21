package org.jimvixx.smsecure.sms;

import android.content.Context;
import android.os.Looper;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;

public class TelephonyServiceState {

  public boolean isConnected(Context context) {
    ListenThread listenThread = new ListenThread(context);
    listenThread.start();

    return listenThread.getResult();
  }

  private static class ListenThread extends Thread {

    private final Context context;

    private boolean complete;
    private boolean result;

    ListenThread(Context context) {
      this.context = context.getApplicationContext();
    }

    @Override
    public void run() {
      Looper looper = initializeLooper();
      ListenCallback callback = new ListenCallback(looper);
      TelephonyManager telephonyManager =
              (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);

      telephonyManager.listen(callback, PhoneStateListener.LISTEN_SERVICE_STATE);
      Looper.loop();
      telephonyManager.listen(callback, PhoneStateListener.LISTEN_NONE);

      setResult(callback.isConnected());
    }

    private Looper initializeLooper() {
      if (Looper.myLooper() == null) {
        Looper.prepare();
      }

      return Looper.myLooper();
    }

    synchronized boolean getResult() {
      while (!complete) {
        try {
          wait();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return false;
        }
      }

      return result;
    }

    private synchronized void setResult(boolean result) {
      this.result = result;
      this.complete = true;
      notifyAll();
    }
  }

  private static class ListenCallback extends PhoneStateListener {

    private final Looper looper;
    private volatile boolean connected;

    ListenCallback(Looper looper) {
      this.looper = looper;
    }

    @Override
    public void onServiceStateChanged(ServiceState serviceState) {
      connected = serviceState.getState() == ServiceState.STATE_IN_SERVICE;
      looper.quit();
    }

    boolean isConnected() {
      return connected;
    }
  }
}
