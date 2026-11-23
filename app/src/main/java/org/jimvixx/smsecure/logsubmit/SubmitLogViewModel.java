/*
 * Copyright (C) 2026 Jimvixx
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

package org.jimvixx.smsecure.logsubmit;

import android.app.Application;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;

import org.jimvixx.smsecure.R;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Owns one upload and its optional follow-up message across view recreation. */
public class SubmitLogViewModel extends AndroidViewModel {
  final MutableLiveData<State> state = new MutableLiveData<>();
  volatile String logs;
  private final SavedStateHandle saved;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private boolean started;
  private volatile boolean cleared;
  private boolean messageSending;
  private java.io.File cachedLog;
  private final Callable<String> collector;
  private final Function<String, LogUploadResult> uploader;
  private final BooleanSupplier connected;
  private final MessageSender messageSender;
  private String reportId;
  private String objectKey;
  private String messageToken;

  public SubmitLogViewModel(@NonNull Application app, SavedStateHandle saved) {
    super(app);
    this.saved = saved;
    collector = () -> LogCollector.collect(app);
    uploader = LogUploadService::upload;
    connected = this::hasInternetNetwork;
    messageSender = LogMessageSubmissionService::submit;
  }

  SubmitLogViewModel(Application app, SavedStateHandle saved,
                    Callable<String> collector,
                    Function<String, LogUploadResult> uploader,
                    BooleanSupplier connected) {
    this(app, saved, collector, uploader, connected, LogMessageSubmissionService::submit);
  }

  SubmitLogViewModel(Application app, SavedStateHandle saved,
                    Callable<String> collector,
                    Function<String, LogUploadResult> uploader,
                    BooleanSupplier connected,
                    MessageSender messageSender) {
    super(app);
    this.saved = saved;
    this.collector = collector;
    this.uploader = uploader;
    this.connected = connected;
    this.messageSender = messageSender;
  }

  void start() {
    if (started) return;
    started = true;
    boolean restoring = Boolean.TRUE.equals(saved.get("started"));
    saved.set("started", true);
    restoreMessageState();

    String cacheName = saved.get("cacheName");
    if (cacheName == null || !cacheName.matches("submitted-log-[a-f0-9-]+\\.txt")) {
      cacheName = "submitted-log-" + java.util.UUID.randomUUID() + ".txt";
      saved.set("cacheName", cacheName);
    }
    cachedLog = new java.io.File(getApplication().getCacheDir(), cacheName);

    Integer restoredStatus = saved.get("status");
    String restoredInfo = saved.get("info");
    state.setValue(new State(R.string.log_submit__collecting_logs, null, true));
    executor.execute(() -> {
      if (restoring) {
        if (cachedLog.isFile()) {
          try (java.io.FileInputStream input = new java.io.FileInputStream(cachedLog);
               java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            logs = output.toString(java.nio.charset.StandardCharsets.UTF_8.name());
          } catch (java.io.IOException e) {
            org.jimvixx.smsecure.logging.Log.w("SubmitLogViewModel", e);
          }
        }
        publish(restoredStatus == null ? R.string.log_submit__interrupted : restoredStatus,
                restoredInfo, false);
        return;
      }

      String collected;
      try {
        collected = collector.call();
      } catch (Exception e) {
        org.jimvixx.smsecure.logging.Log.w("SubmitLogViewModel", e);
        publish(R.string.log_submit__log_collect_failed, null, false);
        return;
      }
      if (collected.isEmpty() || collected.startsWith("LogCollector error:")) {
        publish(R.string.log_submit__log_collect_failed, null, false);
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
        publish(R.string.log_submit__no_internet, null, false);
        return;
      }

      state.postValue(new State(R.string.log_submit__uploading_logs, null, true,
              false, false, false, 0));
      LogUploadResult result = uploader.apply(collected);
      if (result.success) {
        reportId = result.reportId;
        objectKey = result.objectKey;
        messageToken = isUsableMessageToken(result.messageToken) ? result.messageToken : null;
        saved.set("reportId", reportId);
        saved.set("objectKey", objectKey);
        saved.set("messageToken", messageToken);
        saved.set("messageSent", false);
        saved.set("messageStatus", 0);
        publish(R.string.log_submit__log_uploaded_status, result.message, false);
      } else {
        reportId = null;
        objectKey = null;
        messageToken = null;
        publish(!connected.getAsBoolean() ? R.string.log_submit__no_internet :
                result.serverError ? R.string.log_submit__log_server_error :
                R.string.log_submit__log_server_unreachable, null, false);
      }
    });
  }

  java.io.File getCachedLog() {
    return cachedLog;
  }

  String getMessageText() {
    String text = saved.get("messageText");
    return text == null ? "" : text;
  }

  void setMessageText(String text) {
    saved.set("messageText", text == null ? "" : text);
  }

  void sendMessage(@NonNull String text) {
    setMessageText(text);
    if (!hasMessageToken() || messageSending || isMessageSent()) return;

    if (text.trim().isEmpty()) {
      setMessageStatus(R.string.log_submit__message_empty);
      publishCurrentMessageState();
      return;
    }

    if (!connected.getAsBoolean()) {
      setMessageStatus(R.string.log_submit__no_internet);
      publishCurrentMessageState();
      return;
    }

    messageSending = true;
    saved.set("messageSending", true);
    setMessageStatus(0);
    publishCurrentMessageState();
    executor.execute(() -> {
      LogMessageSubmissionService.Result result;
      try {
        result = messageSender.submit(reportId, objectKey, messageToken, text);
      } catch (Exception e) {
        org.jimvixx.smsecure.logging.Log.w("SubmitLogViewModel", e);
        result = LogMessageSubmissionService.Result.error("Message submission failed", false);
      }
      if (cleared) return;

      LogMessageSubmissionService.Result finalResult = result;
      new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
        messageSending = false;
        saved.set("messageSending", false);
        if (finalResult.success) {
          saved.set("messageSent", true);
          messageToken = null;
          saved.set("messageToken", null);
          setMessageStatus(R.string.log_submit__message_sent);
        } else {
          setMessageStatus(!connected.getAsBoolean() ? R.string.log_submit__no_internet :
                  finalResult.serverError ? R.string.log_submit__message_server_error :
                  R.string.log_submit__message_server_unreachable);
        }
        publishCurrentMessageState();
      });
    });
  }

  private void restoreMessageState() {
    reportId = saved.get("reportId");
    objectKey = saved.get("objectKey");
    messageToken = saved.get("messageToken");
    messageSending = false;
    if (Boolean.TRUE.equals(saved.get("messageSending"))) {
      setMessageStatus(R.string.log_submit__message_interrupted);
    }
    saved.set("messageSending", false);
  }

  private boolean hasMessageToken() {
    return reportId != null && objectKey != null && isUsableMessageToken(messageToken);
  }

  private boolean isMessageAvailable() {
    return hasMessageToken() || isMessageSent();
  }

  private boolean isMessageSent() {
    return Boolean.TRUE.equals(saved.get("messageSent"));
  }

  private static boolean isUsableMessageToken(String token) {
    return token != null && token.matches("[A-Za-z0-9_-]{43}");
  }

  private void setMessageStatus(int status) {
    saved.set("messageStatus", status);
  }

  private int getMessageStatus() {
    Integer status = saved.get("messageStatus");
    return status == null ? 0 : status;
  }

  private void publishCurrentMessageState() {
    State current = state.getValue();
    int status = current == null ? R.string.log_submit__log_uploaded_status : current.status;
    String info = current == null ? null : current.info;
    state.setValue(new State(status, info, false, isMessageAvailable(), messageSending,
            isMessageSent(), getMessageStatus()));
  }

  private boolean hasInternetNetwork() {
    ConnectivityManager manager = getApplication().getSystemService(ConnectivityManager.class);
    NetworkCapabilities caps = manager == null ? null :
            manager.getNetworkCapabilities(manager.getActiveNetwork());
    return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
  }

  private void publish(int status, String info, boolean busy) {
    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
      saved.set("status", status);
      saved.set("info", info);
      state.setValue(new State(status, info, busy, isMessageAvailable(), messageSending,
              isMessageSent(), getMessageStatus()));
    });
  }

  @Override protected void onCleared() {
    cleared = true;
    executor.shutdownNow();
    if (cachedLog != null) cachedLog.delete();
  }

  interface MessageSender {
    LogMessageSubmissionService.Result submit(String reportId, String objectKey,
                                              String token, String message);
  }

  static final class State {
    final int status;
    final String info;
    final boolean busy;
    final boolean messageAvailable;
    final boolean messageBusy;
    final boolean messageSent;
    final int messageStatus;

    State(int status, String info, boolean busy) {
      this(status, info, busy, false, false, false, 0);
    }

    State(int status, String info, boolean busy, boolean messageAvailable,
          boolean messageBusy, boolean messageSent, int messageStatus) {
      this.status = status;
      this.info = info;
      this.busy = busy;
      this.messageAvailable = messageAvailable;
      this.messageBusy = messageBusy;
      this.messageSent = messageSent;
      this.messageStatus = messageStatus;
    }
  }
}
