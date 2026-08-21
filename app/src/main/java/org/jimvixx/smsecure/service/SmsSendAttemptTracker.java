package org.jimvixx.smsecure.service;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

public final class SmsSendAttemptTracker {

  private static final String PREFERENCES_NAME = "sms_send_attempts";
  private static final String KEY_PREFIX = "message_";

  private SmsSendAttemptTracker() {
  }

  public static synchronized void startAttempt(Context context, long messageId,
                                               String attemptId, int partsTotal) {
    getPreferences(context).edit()
            .putString(getKey(messageId), new State(attemptId, partsTotal).serialize())
            .commit();
  }

  public static synchronized void cancelAttempt(Context context, long messageId, String attemptId) {
    SharedPreferences preferences = getPreferences(context);
    State state = State.deserialize(preferences.getString(getKey(messageId), null));

    if (state != null && state.attemptId.equals(attemptId)) {
      preferences.edit().remove(getKey(messageId)).commit();
    }
  }

  public static synchronized boolean shouldProcessCallback(Context context, long messageId,
                                                           String attemptId, int partIndex,
                                                           int partsTotal,
                                                           boolean connectivityFailure) {
    SharedPreferences preferences = getPreferences(context);
    String key = getKey(messageId);
    State state = State.deserialize(preferences.getString(key, null));

    if (state == null || !state.attemptId.equals(attemptId) ||
        state.partsTotal != partsTotal || partIndex < 0 || partIndex >= partsTotal) {
      return false;
    }

    CallbackDecision decision = state.record(partIndex, connectivityFailure);

    if (state.isComplete()) {
      preferences.edit().remove(key).commit();
    } else {
      preferences.edit().putString(key, state.serialize()).commit();
    }

    return decision == CallbackDecision.PROCESS;
  }

  private static SharedPreferences getPreferences(Context context) {
    return context.getApplicationContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
  }

  private static String getKey(long messageId) {
    return KEY_PREFIX + messageId;
  }

  enum CallbackDecision {
    PROCESS,
    IGNORE
  }

  static final class State {

    private static final String FIELD_SEPARATOR = "\\|";

    private final String attemptId;
    private final int partsTotal;
    private final Set<Integer> seenParts;
    private boolean connectivityRetryClaimed;

    State(String attemptId, int partsTotal) {
      this(attemptId, partsTotal, false, new HashSet<>());
    }

    private State(String attemptId, int partsTotal, boolean connectivityRetryClaimed,
                  Set<Integer> seenParts) {
      this.attemptId = attemptId;
      this.partsTotal = partsTotal;
      this.connectivityRetryClaimed = connectivityRetryClaimed;
      this.seenParts = seenParts;
    }

    CallbackDecision record(int partIndex, boolean connectivityFailure) {
      if (!seenParts.add(partIndex)) {
        return CallbackDecision.IGNORE;
      }

      if (!connectivityFailure) {
        return CallbackDecision.PROCESS;
      }

      if (connectivityRetryClaimed) {
        return CallbackDecision.IGNORE;
      }

      connectivityRetryClaimed = true;
      return CallbackDecision.PROCESS;
    }

    boolean isComplete() {
      return seenParts.size() == partsTotal;
    }

    String serialize() {
      StringBuilder seen = new StringBuilder();
      for (Integer part : seenParts) {
        if (seen.length() > 0) seen.append(',');
        seen.append(part);
      }

      return attemptId + "|" + partsTotal + "|" + connectivityRetryClaimed + "|" + seen;
    }

    static State deserialize(String serialized) {
      if (serialized == null) return null;

      String[] fields = serialized.split(FIELD_SEPARATOR, -1);
      if (fields.length != 4) return null;

      try {
        Set<Integer> seenParts = new HashSet<>();
        if (!fields[3].isEmpty()) {
          for (String part : fields[3].split(",")) {
            seenParts.add(Integer.parseInt(part));
          }
        }

        return new State(fields[0], Integer.parseInt(fields[1]),
                         Boolean.parseBoolean(fields[2]), seenParts);
      } catch (NumberFormatException e) {
        return null;
      }
    }
  }
}
