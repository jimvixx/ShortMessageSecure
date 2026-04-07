/*
 * Copyright (C) 2014 Open Whisper Systems
 * Copyright (C) 2025 Jimvixx
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

package org.jimvixx.smsecure;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import org.jimvixx.smsecure.logging.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import info.guardianproject.GuardianProjectRSA4096;
import info.guardianproject.trustedintents.TrustedIntents;

import org.jimvixx.smsecure.service.KeyCachingService;
import org.jimvixx.smsecure.util.SMSecurePreferences;
import org.iilab.IilabEngineeringRSA2048Pin;

/**
 * PanicKit entry point.
 *
 * Exported activity for external PanicKit triggers:
 *   info.guardianproject.panic.action.TRIGGER
 */
public class PanicResponderActivity extends Activity {

  private static final String TAG = PanicResponderActivity.class.getSimpleName();
  public static final String PANIC_TRIGGER_ACTION = "info.guardianproject.panic.action.TRIGGER";
  private static final String RIPPLE_PACKAGE = "info.guardianproject.ripple"; // Known sender package (Ripple panic button)
  public static final int REASON_PANICKIT_EXTERNAL = 1;
  public static final int REASON_DEVICE_LOCK      = 2;

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    final Intent raw = getIntent();
    final String rawAction = (raw != null) ? raw.getAction() : null;

    Log.w(TAG, "onCreate(), rawAction=" + rawAction
            + ", rawPkg=" + (raw != null ? raw.getPackage() : "null")
            + ", callingPkg=" + getCallingPackage()
            + ", referrer=" + getReferrer());

    if (!SMSecurePreferences.isPanicButtonEnabled(this)) {
      Log.w(TAG, "Panic Button is disabled in settings -> ignoring");
      finishCompat();
      return;
    }

    if (!PANIC_TRIGGER_ACTION.equals(rawAction)) {
      Log.w(TAG, "Unexpected action -> ignoring");
      finishCompat();
      return;
    }

    // 1) Try GuardianProject TrustedIntents (may return null in Activity-launch flows).
    if (isTrustedByTrustedIntents()) {
      Log.w(TAG, "Panic trigger trusted by TrustedIntents -> executing panic flow");
      triggerInternalPanic(this, REASON_PANICKIT_EXTERNAL);
      finishCompat();
      return;
    }

    // 2) Fallback: accept only from the known Ripple package based on callingPkg/referrer.
    final String senderPkg = getSenderPackage();
    Log.w(TAG, "TrustedIntents did not accept. Fallback senderPkg=" + senderPkg);

    if (!RIPPLE_PACKAGE.equals(senderPkg)) {
      Log.w(TAG, "Panic trigger NOT trusted (sender mismatch) -> ignoring");
      finishCompat();
      return;
    }

    Log.w(TAG, "Panic trigger trusted by fallback sender check -> executing panic flow");
    triggerInternalPanic(this, REASON_PANICKIT_EXTERNAL);
    finishCompat();
  }

  private boolean isTrustedByTrustedIntents() {
    try {
      TrustedIntents trustedIntents = TrustedIntents.get(this);
      trustedIntents.addTrustedSigner(GuardianProjectRSA4096.class);
      trustedIntents.addTrustedSigner(IilabEngineeringRSA2048Pin.class);

      Intent trusted = trustedIntents.getIntentFromTrustedSender(this);
      Log.w(TAG, "TrustedIntents result: trusted=" + (trusted != null)
              + ", trustedAction=" + (trusted != null ? trusted.getAction() : "null"));

      return trusted != null && PANIC_TRIGGER_ACTION.equals(trusted.getAction());
    } catch (Throwable t) {
      Log.w(TAG, "TrustedIntents check failed", t);
      return false;
    }
  }

  /**
   * Best-effort sender resolution for Activity-launched intents.
   * - getCallingPackage() is best when present
   * - referrer android-app://<pkg> often exists (as in your log)
   */
  private @Nullable String getSenderPackage() {
    String calling = getCallingPackage();
    if (calling != null && !calling.isEmpty()) return calling;

    Uri referrer = getReferrer();
    if (referrer != null && "android-app".equals(referrer.getScheme())) {
      String host = referrer.getHost(); // android-app://<pkg>
      if (host != null && !host.isEmpty()) return host;
    }

    return null;
  }

  private void finishCompat() {
    try {
      finishAndRemoveTask();
    } catch (Throwable t) {
      // Some OEMs/older APIs can be weird; fall back safely.
      finish();
    }
  }

  public static void triggerInternalPanic(@NonNull Context context, int reason) {
    Context app = context.getApplicationContext();
    Log.w(TAG, "triggerInternalPanic(), reason=" + reason);

    if (SMSecurePreferences.isPasswordDisabled(app)) {
      Log.w(TAG, "Password disabled -> ignoring internal trigger");
      return;
    }

    // Clear cached key directly.
    KeyCachingService.clearMasterSecretDirect(app, KeyCachingService.CLEAR_REASON_PANIC);

    // Exit UI (only if we truly have an Activity context).
    if (context instanceof Activity) {
      try {
        ExitActivity.exitAndRemoveFromRecentApps((Activity) context);
      } catch (Throwable t) {
        Log.w(TAG, "ExitActivity failed", t);
      }
    } else {
      Log.w(TAG, "No Activity context -> skipping UI exit");
    }
  }
}
