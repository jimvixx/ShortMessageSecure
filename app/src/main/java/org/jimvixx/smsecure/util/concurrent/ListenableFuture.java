package org.jimvixx.smsecure.util.concurrent;

import java.util.concurrent.ExecutionException;

public interface ListenableFuture<T> {
  void addListener(Listener<T> listener);

  interface Listener<T> {
    void onSuccess(T result);

    void onFailure(ExecutionException e);
  }
}
