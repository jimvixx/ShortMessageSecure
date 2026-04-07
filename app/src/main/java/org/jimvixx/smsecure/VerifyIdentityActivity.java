/*
 * Copyright (C) 2011 Whisper Systems
 * Copyright (C) 2013 Open Whisper Systems
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

import static org.jimvixx.smsecure.util.ThemeUtil.resolveThemeColor;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.core.widget.ImageViewCompat;

import org.jimvixx.smsecure.crypto.IdentityKeyParcelable;
import org.jimvixx.smsecure.crypto.IdentityKeyUtil;
import org.jimvixx.smsecure.crypto.MasterSecret;
import org.jimvixx.smsecure.crypto.storage.SMSecureSessionStore;
import org.jimvixx.smsecure.database.DatabaseFactory;
import org.jimvixx.smsecure.database.IdentityDatabase;
import org.jimvixx.smsecure.qr.QrScanActivity;
import org.jimvixx.smsecure.recipients.Recipient;
import org.jimvixx.smsecure.recipients.RecipientFactory;
import org.jimvixx.smsecure.util.Base64;
import org.jimvixx.smsecure.util.Dialogs;
import org.jimvixx.smsecure.util.Hex;
import org.jimvixx.smsecure.util.dualsim.SubscriptionManagerCompat;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SessionStore;

import java.io.IOException;

public class VerifyIdentityActivity extends BaseIdentityActivity {

  public static final String IDENTITY_KEY = "identity_key";

  private static final String STATE_SCAN_STATUS        = "state_scan_status";
  private static final String STATE_REMOTE_FP_EXPANDED = "state_remote_fp_expanded";

  private static final int SCAN_STATUS_IDLE         = 0;
  private static final int SCAN_STATUS_VERIFIED     = 1;
  private static final int SCAN_STATUS_NOT_VERIFIED = 2;
  private static final int SCAN_STATUS_NOT_AVAILABLE = 3;

  // Verify-specific UI
  private TextView scanStatus;
  private View scanQrButton;
  private ImageView scanStatusIcon;

  // Manual input UI
  private TextView manualCodeInput; // AppCompatEditText ok
  private TextView manualCodeHint;
  private View manualVerifyButton;

  // Parsed manual bytes cache (set by TextWatcher, consumed by button)
  @Nullable private byte[] manualParsedBytes;
  private boolean manualParsedValid = false;

  // Remote section
  private CardView remoteFingerprintCard;
  private View toggleRemoteFingerprint;
  private View sectionRemote;
  private TextView remoteFingerprint;
  private TextView remoteTextCode;
  private CardView verificationLayout;

  private boolean remoteFpExpanded = false;

  // Data
  private int lastScanStatus = SCAN_STATUS_IDLE;

  @Nullable private Recipient recipient;
  @Nullable private MasterSecret masterSecret;

  @Nullable private ActivityResultLauncher<Intent> qrScanLauncher;

  // Remote RAW (source of truth)
  @Nullable private String remoteHexRaw;
  @Nullable private String remoteBase64Raw;
  @Nullable private byte[] remoteBytesRaw;

  @Override
  protected void onPreCreate() {
    super.onPreCreate();

    qrScanLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
              if (result.getResultCode() != RESULT_OK || result.getData() == null) return;

              String scanned = result.getData().getStringExtra(QrScanActivity.EXTRA_QR_CONTENTS);
              if (scanned == null) return;

              applyScannedResult(scanned);
            }
    );
  }

  @Override
  protected void onCreate(@Nullable Bundle icicle, @NonNull MasterSecret masterSecret) {
    this.masterSecret = masterSecret;

    this.recipient = RecipientFactory.getRecipientForId(
            this, getIntent().getLongExtra("recipient", -1), true
    );

    setContentView(R.layout.identity_activity);

    androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
    if (toolbar != null) setSupportActionBar(toolbar);

    var ab = getSupportActionBar();
    if (ab != null) {
      ab.setDisplayHomeAsUpEnabled(true);
      ab.setDisplayShowHomeEnabled(true);
      ab.setTitle(R.string.IdentityActivity__verify_identity);
    }

    bindVerifyViews();

    if (icicle != null) {
      remoteFpExpanded = icicle.getBoolean(STATE_REMOTE_FP_EXPANDED, false);
      lastScanStatus   = icicle.getInt(STATE_SCAN_STATUS, SCAN_STATUS_IDLE);
    } else {
      remoteFpExpanded = false;
      lastScanStatus   = SCAN_STATUS_IDLE;
    }

    // Init base (local UI: bind, render, local actions, local spoilers)
    initBaseIdentityUi(icicle);

    // Verify-only actions/spoilers
    bindVerifyActions();
    applyRemoteSpoiler();

    updateRemoteSectionAndStatusFromDb();

    // Manual parse watcher (no DB write)
    bindManualParseWatcher();
    refreshManualParseUi(); // in case field pre-filled
  }

  @Override
  public void onResume() {
    super.onResume();
    updateRemoteSectionAndStatusFromDb();
    refreshManualParseUi();
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putBoolean(STATE_REMOTE_FP_EXPANDED, remoteFpExpanded);
    outState.putInt(STATE_SCAN_STATUS, lastScanStatus);
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      finish();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  // -------------------------
  // Base hooks
  // -------------------------

  @Override
  protected void bindBaseViews() {
    // Local spoilers
    toggleFingerprint  = findViewById(R.id.toggle_fingerprint);
    sectionFingerprint = findViewById(R.id.section_fingerprint);

    toggleTextCode  = findViewById(R.id.toggle_text_code);
    sectionTextCode = findViewById(R.id.section_text_code);

    // Local content
    identityFingerprint = findViewById(R.id.identity_fingerprint);
    identityQr          = findViewById(R.id.identity_qr);
    identityTextCode    = findViewById(R.id.identity_text_code);

    // Local actions
    copyFingerprint  = findViewById(R.id.copy_fingerprint);
    shareFingerprint = findViewById(R.id.share_fingerprint);

    shareQrImage = findViewById(R.id.share_qr_image);

    copyTextCode  = findViewById(R.id.copy_text_code);
    shareTextCode = findViewById(R.id.share_text_code);
  }

  @Nullable
  @Override
  protected IdentityKey resolveLocalIdentityKey() {
    int subscriptionId = getIntent().getIntExtra(
            "subscription_id",
            SubscriptionManagerCompat.getDefaultMessagingSubscriptionId().or(-1)
    );

    IdentityKey localKey = IdentityKeyUtil.getIdentityKey(this, subscriptionId);

    // keep intent extra if needed elsewhere
    getIntent().putExtra(IDENTITY_KEY, new IdentityKeyParcelable(localKey));

    return localKey;
  }

  // -------------------------
  // Verify-specific binding
  // -------------------------

  private void bindVerifyViews() {
    scanStatus     = findViewById(R.id.scan_status);
    scanQrButton   = findViewById(R.id.scan_qr_button);
    scanStatusIcon = findViewById(R.id.scan_status_icon);

    // Manual verify views
    manualCodeInput    = findViewById(R.id.manual_code_input);
    manualCodeHint     = findViewById(R.id.manual_code_hint);
    manualVerifyButton = findViewById(R.id.manual_verify_button);

    toggleRemoteFingerprint = findViewById(R.id.toggle_remote_fingerprint);
    sectionRemote           = findViewById(R.id.section_remote);

    remoteFingerprint = findViewById(R.id.remote_fingerprint);
    remoteTextCode    = findViewById(R.id.remote_text_code);

    verificationLayout = findViewById(R.id.layout_verify_identity);
    remoteFingerprintCard  = findViewById(R.id.card_view_remote_fingerprint);

    if (verificationLayout != null) verificationLayout.setVisibility(View.VISIBLE);
    if (remoteFingerprintCard != null) remoteFingerprintCard.setVisibility(View.VISIBLE);

    if (manualVerifyButton != null) manualVerifyButton.setEnabled(false);
  }

  private void bindVerifyActions() {
    if (toggleRemoteFingerprint != null) {
      toggleRemoteFingerprint.setOnClickListener(v -> {
        remoteFpExpanded = !remoteFpExpanded;
        applyRemoteSpoiler();
      });
    }

    if (scanQrButton != null) {
      scanQrButton.setOnClickListener(v -> initiateScan());
    }

    if (manualVerifyButton != null) {
      manualVerifyButton.setOnClickListener(v -> verifyManuallyEnteredCode());
    }
  }

  private void applyRemoteSpoiler() {
    setSpoilerState(toggleRemoteFingerprint, sectionRemote, remoteFpExpanded);
  }

  // -------------------------
  // Manual parse (live UI) + Verify button
  // -------------------------

  private void bindManualParseWatcher() {
    if (manualCodeInput == null) return;

    manualCodeInput.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
      @Override public void afterTextChanged(Editable s) {
        refreshManualParseUi();
      }
    });
  }

  private void refreshManualParseUi() {
    manualParsedBytes = null;
    manualParsedValid = false;

    if (manualCodeHint == null || manualCodeInput == null) return;

    String input = manualCodeInput.getText() != null ? manualCodeInput.getText().toString() : "";
    String trimmed = input.trim();

    if (manualVerifyButton != null) manualVerifyButton.setEnabled(false);

    if (trimmed.isEmpty()) {
      setManualHintNeutral(getString(R.string.IdentityActivity__paste_to_verify));
      return;
    }

    ParseResult parsed = parseHexOrBase64Identity(trimmed);

    if (!parsed.ok || parsed.bytes == null) {
      // show format if we can
      if (parsed.detected != DetectedFormat.UNKNOWN) {
        setManualHintError(
                getString(R.string.IdentityActivity__detected_format, detectedLabel(parsed.detected))
                        + " — " + getString(parsed.errorRes != 0 ? parsed.errorRes : R.string.IdentityActivity__invalid_code)
        );
      } else {
        setManualHintError(getString(R.string.IdentityActivity__invalid_code));
      }
      return;
    }

    manualParsedBytes = parsed.bytes;
    manualParsedValid = true;

    // If remote missing, still allow verify? I'd disable, because compare is impossible.
    if (remoteBytesRaw == null) {
      setManualHintNeutral(getString(R.string.IdentityActivity__manual_verify_no_remote));
      if (manualVerifyButton != null) manualVerifyButton.setEnabled(false);
      return;
    }

    // Parsed ok. Show "Detected: X — ready"
    setManualHintNeutral(
            getString(R.string.IdentityActivity__detected_format, detectedLabel(parsed.detected))
                    + " — " + getString(R.string.IdentityActivity__manual_ready)
    );

    if (manualVerifyButton != null) manualVerifyButton.setEnabled(true);
  }

  private void verifyManuallyEnteredCode() {
    if (!manualParsedValid || manualParsedBytes == null) {
      Toast.makeText(this, R.string.IdentityActivity__invalid_code, Toast.LENGTH_LONG).show();
      return;
    }

    if (recipient == null || remoteBytesRaw == null) {
      Toast.makeText(this, R.string.IdentityActivity__manual_verify_no_remote, Toast.LENGTH_LONG).show();
      lastScanStatus = SCAN_STATUS_NOT_AVAILABLE;
      applyScanStatus(lastScanStatus);
      return;
    }

    boolean match = constantTimeEquals(remoteBytesRaw, manualParsedBytes);

    IdentityDatabase db = DatabaseFactory.getIdentityDatabase(this);
    db.setVerificationState(
            recipient.getRecipientId(),
            match ? IdentityDatabase.VERIFY_STATE_VERIFIED : IdentityDatabase.VERIFY_STATE_MISMATCH
    );

    if (match) {
      lastScanStatus = SCAN_STATUS_VERIFIED;

      Dialogs.showInfoDialog(
              this,
              getString(R.string.IdentityActivity__verified_exclamation),
              getString(R.string.IdentityActivity__their_key_is_correct_it_is_also_necessary_to_verify_your_key_with_them_as_well)
      );
    } else {
      lastScanStatus = SCAN_STATUS_NOT_VERIFIED;

      Dialogs.showAlertDialog(
              this,
              getString(R.string.IdentityActivity__not_verified_exclamation),
              getString(R.string.IdentityActivity__warning_the_entered_key_does_not_match)
      );
    }

    applyScanStatus(lastScanStatus);
    updateRemoteSectionAndStatusFromDb();
  }

  private void setManualHintNeutral(@NonNull String text) {
    if (manualCodeHint == null) return;
    manualCodeHint.setText(text);
    manualCodeHint.setTextColor(resolveThemeColor(this, R.attr.appColorIconPrimary));
  }

  private void setManualHintError(@NonNull String text) {
    if (manualCodeHint == null) return;
    manualCodeHint.setText(text);
    manualCodeHint.setTextColor(resolveThemeColor(this, R.attr.appColorCommonAlert));
  }

  // -------------------------
  // Verify logic (QR scan)
  // -------------------------

  private void updateRemoteSectionAndStatusFromDb() {
    IdentityKey remoteIdentityKey = getRemoteIdentityKey(masterSecret, recipient);

    if (toggleRemoteFingerprint instanceof TextView) {
      ((TextView) toggleRemoteFingerprint)
              .setText(getString(R.string.IdentityActivity__current_remote_fingerprint, getContactTitle()));
    }

    if (remoteIdentityKey == null) {
      remoteHexRaw = null;
      remoteBase64Raw = null;
      remoteBytesRaw = null;

      if (remoteFingerprint != null) remoteFingerprint.setText(R.string.IdentityActivity__recipient_has_no_identity_key);
      if (remoteTextCode != null)    remoteTextCode.setText(R.string.IdentityActivity__recipient_has_no_identity_key);
    } else {
      remoteBytesRaw  = remoteIdentityKey.serialize();
      remoteHexRaw    = Hex.toStringCondensed(remoteBytesRaw);
      remoteBase64Raw = Base64.encodeBytes(remoteBytesRaw);

      if (remoteFingerprint != null) remoteFingerprint.setText(formatHexForDisplay(remoteHexRaw));
      if (remoteTextCode != null)    remoteTextCode.setText(formatBase64ForDisplay(remoteBase64Raw));
    }

    if (recipient == null || remoteIdentityKey == null) {
      lastScanStatus = SCAN_STATUS_NOT_AVAILABLE;
    } else {
      IdentityDatabase db = DatabaseFactory.getIdentityDatabase(this);
      int state = db.getVerificationState(recipient.getRecipientId());

      switch (state) {
        case IdentityDatabase.VERIFY_STATE_VERIFIED:
          lastScanStatus = SCAN_STATUS_VERIFIED;
          break;
        case IdentityDatabase.VERIFY_STATE_MISMATCH:
          lastScanStatus = SCAN_STATUS_NOT_VERIFIED;
          break;
        case IdentityDatabase.VERIFY_STATE_UNKNOWN:
        default:
          lastScanStatus = SCAN_STATUS_IDLE;
          break;
      }
    }

    applyRemoteSpoiler();
    applyScanStatus(lastScanStatus);

    // Remote may have appeared/disappeared -> update button state/hint
    refreshManualParseUi();
  }

  private void initiateScan() {
    if (qrScanLauncher == null) return;

    if (getRemoteIdentityKey(masterSecret, recipient) == null) {
      Toast.makeText(this, R.string.IdentityActivity__recipient_has_no_identity_key, Toast.LENGTH_LONG).show();
      lastScanStatus = SCAN_STATUS_NOT_AVAILABLE;
      applyScanStatus(lastScanStatus);
      return;
    }

    qrScanLauncher.launch(new Intent(this, QrScanActivity.class));
  }

  private void applyScannedResult(@NonNull String scanned) {
    IdentityKey remote = getRemoteIdentityKey(masterSecret, recipient);

    if (recipient == null || remote == null) {
      lastScanStatus = SCAN_STATUS_NOT_AVAILABLE;
      applyScanStatus(lastScanStatus);
      Toast.makeText(this, R.string.IdentityActivity__recipient_has_no_identity_key, Toast.LENGTH_LONG).show();
      return;
    }

    String expectedRaw = remoteBase64Raw != null ? remoteBase64Raw : Base64.encodeBytes(remote.serialize());

    String scannedNorm  = stripWhitespace(scanned);
    String expectedNorm = stripWhitespace(expectedRaw);

    boolean ok = scannedNorm.equals(expectedNorm);

    IdentityDatabase db = DatabaseFactory.getIdentityDatabase(this);
    db.setVerificationState(
            recipient.getRecipientId(),
            ok ? IdentityDatabase.VERIFY_STATE_VERIFIED : IdentityDatabase.VERIFY_STATE_MISMATCH
    );

    if (ok) {
      lastScanStatus = SCAN_STATUS_VERIFIED;

      Dialogs.showInfoDialog(
              this,
              getString(R.string.IdentityActivity__verified_exclamation),
              getString(R.string.IdentityActivity__their_key_is_correct_it_is_also_necessary_to_verify_your_key_with_them_as_well)
      );
    } else {
      lastScanStatus = SCAN_STATUS_NOT_VERIFIED;

      Dialogs.showAlertDialog(
              this,
              getString(R.string.IdentityActivity__not_verified_exclamation),
              getString(R.string.IdentityActivity__warning_the_scanned_key_does_not_match_please_check_the_fingerprint_text_carefully)
      );
    }

    applyScanStatus(lastScanStatus);
    updateRemoteSectionAndStatusFromDb();
  }

  private void applyScanStatus(int status) {
    if (scanStatus == null || scanStatusIcon == null) return;

    int color;

    switch (status) {
      case SCAN_STATUS_VERIFIED:
        scanStatus.setText(R.string.IdentityActivity__verification_status_verified);
        color = resolveThemeColor(this, R.attr.appColorCommonSuccess);
        scanStatusIcon.setImageResource(R.drawable.ic_check_circle);
        break;

      case SCAN_STATUS_NOT_VERIFIED:
        scanStatus.setText(R.string.IdentityActivity__verification_status_not_verified);
        color = resolveThemeColor(this, R.attr.appColorCommonAlert);
        scanStatusIcon.setImageResource(R.drawable.ic_close_circle);
        break;

      case SCAN_STATUS_NOT_AVAILABLE:
        scanStatus.setText(R.string.IdentityActivity__verification_status_not_available);
        color = resolveThemeColor(this, R.attr.appColorIconPrimary);
        scanStatusIcon.setImageResource(R.drawable.ic_minus_circle);
        break;

      case SCAN_STATUS_IDLE:
      default:
        scanStatus.setText(R.string.IdentityActivity__verification_status_idle);
        color = resolveThemeColor(this, R.attr.appColorIconPrimary);
        scanStatusIcon.setImageResource(R.drawable.ic_minus_circle);
        break;
    }

    scanStatus.setTextColor(color);
    ImageViewCompat.setImageTintList(scanStatusIcon, ColorStateList.valueOf(color));
  }

  private @Nullable IdentityKey getRemoteIdentityKey(@Nullable MasterSecret masterSecret, @Nullable Recipient recipient) {
    if (recipient == null || masterSecret == null) return null;

    IdentityKeyParcelable p = getIntent().getParcelableExtra("remote_identity");
    if (p != null) return p.get();

    int subscriptionId = SubscriptionManagerCompat.getDefaultMessagingSubscriptionId().or(-1);

    SessionStore sessionStore = new SMSecureSessionStore(this, masterSecret, subscriptionId);
    SignalProtocolAddress address = new SignalProtocolAddress(recipient.getNumber(), 1);
    SessionRecord record = sessionStore.loadSession(address);

    if (record == null) return null;
    return record.getSessionState().getRemoteIdentityKey();
  }

  private String getContactTitle() {
    if (recipient == null) return getString(R.string.AndroidManifest__verify_identity);

    String name = recipient.getName();
    if (name != null && !name.trim().isEmpty()) return name;

    return recipient.getNumber();
  }

  // -------------------------
  // Parsing / helpers
  // -------------------------

  private enum DetectedFormat { HEX, BASE64, UNKNOWN }

  private static final class ParseResult {
    final boolean ok;
    @Nullable final byte[] bytes;
    final int errorRes;
    final DetectedFormat detected;

    ParseResult(boolean ok, @Nullable byte[] bytes, int errorRes, @NonNull DetectedFormat detected) {
      this.ok = ok;
      this.bytes = bytes;
      this.errorRes = errorRes;
      this.detected = detected;
    }
  }

  private ParseResult parseHexOrBase64Identity(@NonNull String rawInput) {
    String s = stripWhitespace(rawInput);
    s = s.replace(":", "").replace("-", "");

    if (looksLikeHex(s)) {
      if ((s.length() & 1) != 0) {
        return new ParseResult(false, null, R.string.IdentityActivity__invalid_hex_length, DetectedFormat.HEX);
      }
      try {
        byte[] bytes = Hex.fromStringCondensed(s);
        return validateIdentityBytes(bytes, DetectedFormat.HEX);
      } catch (IOException | RuntimeException e) {
        return new ParseResult(false, null, R.string.IdentityActivity__invalid_hex, DetectedFormat.HEX);
      }
    }

    try {
      String b64 = s.replace('-', '+').replace('_', '/');
      int mod = b64.length() % 4;
      if (mod != 0) b64 = b64 + "====".substring(mod);

      byte[] bytes = Base64.decode(b64);
      return validateIdentityBytes(bytes, DetectedFormat.BASE64);
    } catch (Exception e) {
      return new ParseResult(false, null, R.string.IdentityActivity__invalid_base64, DetectedFormat.BASE64);
    }
  }

  private ParseResult validateIdentityBytes(@Nullable byte[] bytes, @NonNull DetectedFormat detected) {
    if (bytes == null || bytes.length == 0) {
      return new ParseResult(false, null, R.string.IdentityActivity__invalid_code, detected);
    }
    if (bytes.length < 32 || bytes.length > 128) {
      return new ParseResult(false, null, R.string.IdentityActivity__invalid_identity_length, detected);
    }
    return new ParseResult(true, bytes, 0, detected);
  }

  private static boolean looksLikeHex(@NonNull String s) {
    if (s.length() < 8) return false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      boolean hex =
              (c >= '0' && c <= '9') ||
                      (c >= 'a' && c <= 'f') ||
                      (c >= 'A' && c <= 'F');
      if (!hex) return false;
    }
    return true;
  }

  private static boolean constantTimeEquals(@NonNull byte[] a, @NonNull byte[] b) {
    if (a.length != b.length) return false;
    int r = 0;
    for (int i = 0; i < a.length; i++) r |= (a[i] ^ b[i]);
    return r == 0;
  }

  private static @NonNull String stripWhitespace(@NonNull String s) {
    int n = s.length();
    StringBuilder out = new StringBuilder(n);
    for (int i = 0; i < n; i++) {
      char c = s.charAt(i);
      if (!Character.isWhitespace(c)) out.append(c);
    }
    return out.toString();
  }

  private String detectedLabel(@NonNull DetectedFormat f) {
    return switch (f) {
      case HEX -> getString(R.string.IdentityActivity__format_hex);
      case BASE64 -> getString(R.string.IdentityActivity__format_base64);
      default -> getString(R.string.IdentityActivity__format_unknown);
    };
  }
}