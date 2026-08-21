package org.jimvixx.smsecure.service;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class SmsSendAttemptTrackerTest {

  @Test
  public void connectivityFailureIsProcessedOnlyOncePerAttempt() {
    SmsSendAttemptTracker.State state = new SmsSendAttemptTracker.State("attempt", 3);

    assertThat(state.record(0, true)).isEqualTo(SmsSendAttemptTracker.CallbackDecision.PROCESS);
    assertThat(state.record(1, true)).isEqualTo(SmsSendAttemptTracker.CallbackDecision.IGNORE);
    assertThat(state.record(2, true)).isEqualTo(SmsSendAttemptTracker.CallbackDecision.IGNORE);
    assertThat(state.isComplete()).isTrue();
  }

  @Test
  public void duplicatePartCallbackIsIgnored() {
    SmsSendAttemptTracker.State state = new SmsSendAttemptTracker.State("attempt", 2);

    assertThat(state.record(0, false)).isEqualTo(SmsSendAttemptTracker.CallbackDecision.PROCESS);
    assertThat(state.record(0, false)).isEqualTo(SmsSendAttemptTracker.CallbackDecision.IGNORE);
    assertThat(state.isComplete()).isFalse();
  }

  @Test
  public void serializedStatePreservesRetryClaimAndSeenParts() {
    SmsSendAttemptTracker.State state = new SmsSendAttemptTracker.State("attempt", 3);
    state.record(2, true);

    SmsSendAttemptTracker.State restored =
            SmsSendAttemptTracker.State.deserialize(state.serialize());

    assertThat(restored).isNotNull();
    assertThat(restored.record(0, true)).isEqualTo(SmsSendAttemptTracker.CallbackDecision.IGNORE);
    assertThat(restored.record(2, true)).isEqualTo(SmsSendAttemptTracker.CallbackDecision.IGNORE);
  }
}
