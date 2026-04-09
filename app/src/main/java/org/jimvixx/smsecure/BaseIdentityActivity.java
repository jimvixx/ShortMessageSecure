/*
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

import static org.jimvixx.smsecure.util.ViewUtil.dpToPx;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.jimvixx.smsecure.util.Base64;
import org.jimvixx.smsecure.util.Hex;
import org.whispersystems.libsignal.IdentityKey;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Base screen for displaying + sharing/copying local identity data.
 * Keep it UI-focused. All "business logic" stays in subclasses.
 */
public abstract class BaseIdentityActivity extends PassphraseRequiredActionBarActivity {

  private static final String STATE_FP_EXPANDED = "state_fp_expanded";
  private static final String STATE_TEXT_EXPANDED = "state_text_expanded";

  // Spoilers (local)
  @Nullable
  protected View toggleFingerprint;
  @Nullable
  protected View sectionFingerprint;
  @Nullable
  protected View toggleTextCode;
  @Nullable
  protected View sectionTextCode;

  protected boolean fpExpanded = false;
  protected boolean textExpanded = false;

  // Local content views
  @Nullable
  protected TextView identityFingerprint;
  @Nullable
  protected ImageView identityQr;
  @Nullable
  protected TextView identityTextCode;

  @Nullable
  protected ImageButton copyFingerprint;
  @Nullable
  protected ImageButton shareFingerprint;
  @Nullable
  protected ImageButton shareQrImage;
  @Nullable
  protected ImageButton copyTextCode;
  @Nullable
  protected ImageButton shareTextCode;

  // Local computed data (RAW)
  @Nullable
  protected IdentityKey localIdentityKey;
  @Nullable
  protected String localHexRaw;
  @Nullable
  protected String localBase64Raw;
  @Nullable
  protected Bitmap qrBitmap;

  private static Bitmap renderQr(@NonNull String text, int width, int height) throws WriterException {
    QRCodeWriter writer = new QRCodeWriter();
    BitMatrix matrix = writer.encode(text, BarcodeFormat.QR_CODE, width, height);

    Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    for (int x = 0; x < width; x++) {
      for (int y = 0; y < height; y++) {
        bmp.setPixel(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
      }
    }
    return bmp;
  }

  @NonNull
  private static String formatGrouped(@NonNull String s, int groupSize) {
    StringBuilder out = new StringBuilder(s.length() + s.length() / groupSize);
    int i = 0;

    while (i < s.length()) {
      int end = Math.min(i + groupSize, s.length());
      out.append(s, i, end);
      i = end;

      if (i >= s.length()) break;

      out.append(' ');
    }

    return out.toString();
  }

  @NonNull
  protected static String formatHexForDisplay(@NonNull String hex) {
    return formatGrouped(hex, 2);
  }

  @NonNull
  protected static String formatBase64ForDisplay(@NonNull String b64) {
    return formatGrouped(b64, 4);
  }

  /**
   * Subclass must bind at least the "local" views + spoiler toggles
   */
  protected abstract void bindBaseViews();

  /**
   * Subclass provides local key (or null). Called from base in onCreate after views are bound.
   */
  @Nullable
  protected abstract IdentityKey resolveLocalIdentityKey();

  // -------------------------
  // Rendering / formatting
  // -------------------------

  /**
   * Subclass may hide/show additional sections after base render. Optional.
   */
  protected void afterBaseRendered() {
  }

  @Override
  protected void onRestoreInstanceState(@NonNull android.os.Bundle savedInstanceState) {
    super.onRestoreInstanceState(savedInstanceState);
    fpExpanded = savedInstanceState.getBoolean(STATE_FP_EXPANDED, false);
    textExpanded = savedInstanceState.getBoolean(STATE_TEXT_EXPANDED, false);
  }

  @Override
  public void onSaveInstanceState(@NonNull android.os.Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putBoolean(STATE_FP_EXPANDED, fpExpanded);
    outState.putBoolean(STATE_TEXT_EXPANDED, textExpanded);
  }

  /**
   * Call this from subclass onCreate() after setContentView() and toolbar setup.
   */
  protected final void initBaseIdentityUi(@Nullable android.os.Bundle icicle) {
    bindBaseViews();

    if (icicle != null) {
      fpExpanded = icicle.getBoolean(STATE_FP_EXPANDED, false);
      textExpanded = icicle.getBoolean(STATE_TEXT_EXPANDED, false);
    }

    localIdentityKey = resolveLocalIdentityKey();

    renderAndPopulateLocal();
    bindBaseActions();
    applySpoilers();

    afterBaseRendered();
  }

  private void renderAndPopulateLocal() {
    if (identityFingerprint == null || identityTextCode == null || identityQr == null) return;

    if (localIdentityKey == null) {
      identityFingerprint.setText(R.string.IdentityActivity__you_do_not_have_an_identity_key);
      identityTextCode.setText("");
      identityQr.setImageDrawable(null);
      localHexRaw = null;
      localBase64Raw = null;
      qrBitmap = null;
      return;
    }

    localHexRaw = Hex.toStringCondensed(localIdentityKey.serialize());
    localBase64Raw = Base64.encodeBytes(localIdentityKey.serialize());

    // DISPLAY strings
    identityFingerprint.setText(formatHexForDisplay(localHexRaw));
    identityTextCode.setText(formatBase64ForDisplay(localBase64Raw));

    // QR bitmap (uses RAW base64)
    try {
      int sizePx = calculateQrSizePx();
      qrBitmap = renderQr(localBase64Raw, sizePx, sizePx);
      identityQr.setImageBitmap(qrBitmap);
    } catch (WriterException e) {
      qrBitmap = null;
      identityQr.setImageDrawable(null);
      Toast.makeText(this, R.string.IdentityActivity__failed_to_render, Toast.LENGTH_LONG).show();
    }
  }

  protected final int calculateQrSizePx() {
    int w = getResources().getDisplayMetrics().widthPixels;
    int h = getResources().getDisplayMetrics().heightPixels;
    int minDim = Math.min(w, h);

    int size = (int) (minDim * 0.70f);
    int min = dpToPx(getResources(), 220);
    int max = dpToPx(getResources(), 520);
    return Math.max(min, Math.min(max, size));
  }

  // -------------------------
  // Actions / spoilers
  // -------------------------

  private void bindBaseActions() {
    if (toggleFingerprint != null) {
      toggleFingerprint.setOnClickListener(v -> {
        fpExpanded = !fpExpanded;
        applySpoilers();
      });
    }

    if (toggleTextCode != null) {
      toggleTextCode.setOnClickListener(v -> {
        textExpanded = !textExpanded;
        applySpoilers();
      });
    }

    if (copyFingerprint != null) {
      copyFingerprint.setOnClickListener(v -> copyToClipboard(
              getString(R.string.IdentityActivity__hex_code),
              localHexRaw
      ));
    }

    if (shareFingerprint != null) {
      shareFingerprint.setOnClickListener(v -> shareText(
              getString(R.string.share_identity_fingerprint),
              localHexRaw
      ));
    }

    if (copyTextCode != null) {
      copyTextCode.setOnClickListener(v -> copyToClipboard(
              getString(R.string.IdentityActivity__base64_code_title),
              localBase64Raw
      ));
    }

    if (shareTextCode != null) {
      shareTextCode.setOnClickListener(v -> shareText(
              getString(R.string.IdentityActivity__share_base64),
              localBase64Raw
      ));
    }

    if (shareQrImage != null) {
      shareQrImage.setOnClickListener(v -> shareQrImage());
    }
  }

  protected final void applySpoilers() {
    setSpoilerState(toggleFingerprint, sectionFingerprint, fpExpanded);
    setSpoilerState(toggleTextCode, sectionTextCode, textExpanded);
  }

  protected final void setSpoilerState(@Nullable View toggle, @Nullable View section, boolean expanded) {
    if (toggle == null || section == null) return;

    section.setVisibility(expanded ? View.VISIBLE : View.GONE);

    if (toggle instanceof TextView) {
      ((TextView) toggle).setCompoundDrawablesRelativeWithIntrinsicBounds(
              0, 0, expanded ? R.drawable.ic_expand_less : R.drawable.ic_expand_more, 0
      );
    }
  }

  protected final void copyToClipboard(@NonNull String label, @Nullable String text) {
    if (text == null || text.isEmpty()) {
      Toast.makeText(this, R.string.IdentityActivity__empty_data, Toast.LENGTH_LONG).show();
      return;
    }

    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
    if (cm == null) {
      Toast.makeText(this, R.string.IdentityActivity__share_failed, Toast.LENGTH_LONG).show();
      return;
    }

    cm.setPrimaryClip(ClipData.newPlainText(label, text));
    Toast.makeText(this, R.string.Copied_to_clipboard, Toast.LENGTH_SHORT).show();
  }

  protected final void shareText(@NonNull String chooserTitle, @Nullable String text) {
    if (text == null || text.isEmpty()) {
      Toast.makeText(this, R.string.IdentityActivity__empty_data, Toast.LENGTH_LONG).show();
      return;
    }

    Intent share = new Intent(Intent.ACTION_SEND);
    share.setType("text/plain");
    share.putExtra(Intent.EXTRA_TEXT, text);
    startActivity(Intent.createChooser(share, chooserTitle));
  }

  protected final void shareQrImage() {
    File cacheDir = new File(getCacheDir(), "shared_qr");

    if ((qrBitmap == null) || (!cacheDir.exists() && !cacheDir.mkdirs())) {
      Toast.makeText(this, R.string.IdentityActivity__share_failed, Toast.LENGTH_LONG).show();
      return;
    }

    File outFile = new File(cacheDir, "identity_qr.png");

    try (FileOutputStream fos = new FileOutputStream(outFile)) {
      boolean ok = qrBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
      fos.flush();
      if (!ok) throw new IOException("Bitmap.compress returned false");
    } catch (IOException e) {
      Toast.makeText(this, R.string.IdentityActivity__share_failed, Toast.LENGTH_LONG).show();
      return;
    }

    Uri uri = FileProvider.getUriForFile(
            this,
            getPackageName() + ".fileprovider",
            outFile
    );

    Intent share = new Intent(Intent.ACTION_SEND);
    share.setType("image/png");
    share.putExtra(Intent.EXTRA_STREAM, uri);
    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

    // Optional: helps some receivers, doesn't guarantee preview in chooser
    share.setClipData(ClipData.newUri(getContentResolver(), "identity_qr", uri));

    startActivity(Intent.createChooser(share, getString(R.string.IdentityActivity__share_image)));
  }
}