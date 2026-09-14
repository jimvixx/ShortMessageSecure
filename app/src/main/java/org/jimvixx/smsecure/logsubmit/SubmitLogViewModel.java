package org.jimvixx.smsecure.logsubmit;

import android.app.Application;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import org.jimvixx.smsecure.R;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns one upload across view recreation; restored processes never repeat a POST. */
public class SubmitLogViewModel extends AndroidViewModel {
  final MutableLiveData<State> state = new MutableLiveData<>();
  volatile String logs;
  private final SavedStateHandle saved;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private boolean started;
  private volatile boolean cleared;
  private java.io.File cachedLog;
  private final java.util.concurrent.Callable<String> collector;
  private final java.util.function.Function<String, LogUploadResult> uploader;
  private final java.util.function.BooleanSupplier connected;

  public SubmitLogViewModel(@NonNull Application app, SavedStateHandle saved) {
    super(app);
    this.saved = saved;
    collector = () -> LogCollector.collect(app);
    uploader = LogUploadService::upload;
    connected = this::hasInternetNetwork;
  }

  SubmitLogViewModel(Application app, SavedStateHandle saved,
                    java.util.concurrent.Callable<String> collector,
                    java.util.function.Function<String, LogUploadResult> uploader,
                    java.util.function.BooleanSupplier connected) {
    super(app);
    this.saved = saved;
    this.collector = collector;
    this.uploader = uploader;
    this.connected = connected;
  }

  void start() {
    if (started) return;
    started = true;
    boolean restoring = Boolean.TRUE.equals(saved.get("started"));
    saved.set("started", true);
    String cacheName = saved.get("cacheName");
    if (cacheName == null || !cacheName.matches("submitted-log-[a-f0-9-]+\\.txt")) {
      cacheName = "submitted-log-" + java.util.UUID.randomUUID() + ".txt";
      saved.set("cacheName", cacheName);
    }
    cachedLog = new java.io.File(getApplication().getCacheDir(), cacheName);
    Integer restoredStatus = saved.get("status");
    String restoredInfo = saved.get("info");
    state.setValue(new State(R.string.log_submit_activity__collecting_logs, null, true));
    executor.execute(() -> {
      if (restoring) {
        try (java.io.FileInputStream input = new java.io.FileInputStream(cachedLog);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
          byte[] buffer = new byte[8192];
          int count;
          while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
          logs = output.toString(java.nio.charset.StandardCharsets.UTF_8.name());
        } catch (java.io.IOException e) {
          org.jimvixx.smsecure.logging.Log.w("SubmitLogViewModel", e);
        }
        publish(restoredStatus == null ? R.string.log_submit_interrupted : restoredStatus, restoredInfo);
        return;
      }
      String collected;
      try {
        collected = collector.call();
      } catch (Exception e) {
        org.jimvixx.smsecure.logging.Log.w("SubmitLogViewModel", e);
        publish(R.string.log_submit_activity__log_collect_failed, null);
        return;
      }
      if (collected.isEmpty() || collected.startsWith("LogCollector error:")) {
        publish(R.string.log_submit_activity__log_collect_failed, null);
        return;
      }
      if (cleared) return;
      logs = collected;
      try (java.io.FileOutputStream output = new java.io.FileOutputStream(cachedLog)) {
        output.write(collected.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      } catch (java.io.IOException e) {
        org.jimvixx.smsecure.logging.Log.w("SubmitLogViewModel", e);
      }
      if (cleared) {
        cachedLog.delete();
        return;
      }
      if (!connected.getAsBoolean()) {
        publish(R.string.log_submit_no_internet, null);
        return;
      }
      state.postValue(new State(R.string.log_submit_activity__uploading_logs, null, true));
      LogUploadResult result = uploader.apply(collected);
      publish(result.success ? R.string.log_submit_uploaded_status :
              !connected.getAsBoolean() ? R.string.log_submit_no_internet :
              result.serverError ? R.string.log_submit_server_error : R.string.log_submit_unreachable,
              result.success ? result.message : null);
    });
  }

  java.io.File getCachedLog() {
    return cachedLog;
  }

  private boolean hasInternetNetwork() {
    ConnectivityManager manager = getApplication().getSystemService(ConnectivityManager.class);
    NetworkCapabilities caps = manager == null ? null : manager.getNetworkCapabilities(manager.getActiveNetwork());
    return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
  }

  private void publish(int status, String info) {
    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
      saved.set("status", status);
      saved.set("info", info);
      state.setValue(new State(status, info, false));
    });
  }

  @Override protected void onCleared() {
    cleared = true;
    executor.shutdownNow();
    if (cachedLog != null) cachedLog.delete();
  }

  static final class State {
    final int status;
    final String info;
    final boolean busy;
    State(int status, String info, boolean busy) {
      this.status = status;
      this.info = info;
      this.busy = busy;
    }
  }
}
