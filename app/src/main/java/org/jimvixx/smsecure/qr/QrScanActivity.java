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

package org.jimvixx.smsecure.qr;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;
import com.journeyapps.barcodescanner.Size;
import com.journeyapps.barcodescanner.ViewfinderView;

import org.jimvixx.smsecure.R;

import java.util.Collections;

public class QrScanActivity extends AppCompatActivity {

  public static final String EXTRA_QR_CONTENTS = "org.jimvixx.smsecure.qr.CONTENTS";

  private DecoratedBarcodeView scannerView;
  private ImageButton flashButton;
  private TextView instruction;

  private View root;
  private View frameBorder;
  private CutoutOverlayView cutout;

  private boolean hasFlash = false;
  private boolean torchOn = false;
  private boolean finished = false;

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.qr_scan_activity);

    root = findViewById(R.id.qr_scan_root);

    applySystemBarInsets();

    scannerView = findViewById(R.id.qr_scanner_view);

    ImageButton closeButton = findViewById(R.id.qr_scan_close);
    flashButton = findViewById(R.id.qr_scan_flash);
    instruction = findViewById(R.id.qr_scan_instruction);

    FrameLayout overlay = findViewById(R.id.qr_overlay);
    frameBorder = findViewById(R.id.qr_frame);

    cutout = new CutoutOverlayView(this);
    overlay.addView(cutout, 0);

    scannerView.setStatusText("");
    scannerView.getBarcodeView().setDecoderFactory(
            new DefaultDecoderFactory(Collections.singletonList(BarcodeFormat.QR_CODE))
    );

    ViewfinderView viewFinder = scannerView.getViewFinder();
    if (viewFinder != null) viewFinder.setLaserVisibility(false);

    getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
      @Override
      public void handleOnBackPressed() {
        finishCanceled();
      }
    });

    closeButton.setOnClickListener(v -> finishCanceled());

    hasFlash = getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH);
    if (!hasFlash) {
      flashButton.setVisibility(View.GONE);
    } else {
      flashButton.setImageResource(R.drawable.ic_flash_off);
      flashButton.setOnClickListener(v -> toggleTorch());
    }

    // Important: we control framing rect size, then sync overlay + border.
    applyDesiredFramingRectSizeAndSync();

    scannerView.decodeSingle(new BarcodeCallback() {
      @Override
      public void barcodeResult(@NonNull BarcodeResult result) {
        if (finished) return;

        String text = result.getText();
        if (text == null || text.isEmpty()) return;

        finishOk(text);
      }

      @Override
      public void possibleResultPoints(java.util.List<com.google.zxing.ResultPoint> resultPoints) {
        // no-op
      }
    });
  }

  @Override
  protected void onResume() {
    super.onResume();
    scannerView.resume();

    // On some devices framing is recalculated after resume/rotation.
    root.post(this::applyDesiredFramingRectSizeAndSync);

    if (instruction != null) instruction.setVisibility(View.VISIBLE);
  }

  @Override
  protected void onPause() {
    super.onPause();
    scannerView.pause();
  }

  private void applySystemBarInsets() {
    final View close = findViewById(R.id.qr_scan_close);
    final View flash = findViewById(R.id.qr_scan_flash);
    final TextView bottom = findViewById(R.id.qr_scan_instruction);

    final FrameLayout.LayoutParams closeLp = (FrameLayout.LayoutParams) close.getLayoutParams();
    final int closeTop0 = closeLp.topMargin;
    final int closeStart0 = closeLp.getMarginStart();

    final FrameLayout.LayoutParams flashLp = (FrameLayout.LayoutParams) flash.getLayoutParams();
    final int flashTop0 = flashLp.topMargin;
    final int flashEnd0 = flashLp.getMarginEnd();

    final int bottomPad0 = bottom != null ? bottom.getPaddingBottom() : 0;

    ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
      Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());

      FrameLayout.LayoutParams lp1 = (FrameLayout.LayoutParams) close.getLayoutParams();
      lp1.topMargin = closeTop0 + bars.top;
      lp1.setMarginStart(closeStart0 + bars.left);
      close.setLayoutParams(lp1);

      FrameLayout.LayoutParams lp2 = (FrameLayout.LayoutParams) flash.getLayoutParams();
      lp2.topMargin = flashTop0 + bars.top;
      lp2.setMarginEnd(flashEnd0 + bars.right);
      flash.setLayoutParams(lp2);

      if (bottom != null) {
        bottom.setPadding(
                bottom.getPaddingLeft(),
                bottom.getPaddingTop(),
                bottom.getPaddingRight(),
                bottomPad0 + bars.bottom
        );
      }

      return insets;
    });

    ViewCompat.requestApplyInsets(root);
  }

  /**
   * 1) Compute square size that looks good for current orientation
   * 2) Tell BarcodeView to use exactly that framing rect size
   * 3) Read resulting framingRect and sync cutout + border to it
   */
  private void applyDesiredFramingRectSizeAndSync() {
    if (root == null || scannerView == null || cutout == null || frameBorder == null) return;

    // Wait until views have real size
    root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
      @Override
      public void onGlobalLayout() {
        root.getViewTreeObserver().removeOnGlobalLayoutListener(this);

        int vw = scannerView.getWidth();
        int vh = scannerView.getHeight();
        if (vw <= 0 || vh <= 0) return;

        int orientation = getResources().getConfiguration().orientation;
        int minDim = Math.min(vw, vh);

        int sizePx;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
          // Example: 55% of the smaller dimension, clamped
          sizePx = (int) (minDim * 0.55f);
          sizePx = clamp(sizePx, dpToPx(180), dpToPx(280));
        } else {
          // Portrait (default): 52% of smaller dimension, clamped
          sizePx = (int) (minDim * 0.52f);
          sizePx = clamp(sizePx, dpToPx(220), dpToPx(320));
        }

        try {
          scannerView.getBarcodeView().setFramingRectSize(new Size(sizePx, sizePx));
        } catch (Throwable ignore) {
          // If older library doesn't support it, we'll still sync to whatever it returns.
        }

        syncOverlayToFramingRect();
      }
    });
  }

  private void syncOverlayToFramingRect() {
    Rect framing = scannerView.getBarcodeView().getFramingRect();
    if (framing == null) {
      // Sometimes it's null for a moment; retry.
      root.post(this::syncOverlayToFramingRect);
      return;
    }

    Rect framingInRoot = convertRectFromViewToRoot(scannerView.getBarcodeView(), root, framing);

    // Mask cutout
    cutout.setCutout(framingInRoot.left, framingInRoot.top, framingInRoot.right, framingInRoot.bottom);

    // Decorative border
    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) frameBorder.getLayoutParams();
    lp.width = framingInRoot.width();
    lp.height = framingInRoot.height();
    lp.leftMargin = framingInRoot.left;
    lp.topMargin = framingInRoot.top;
    lp.gravity = 0;
    frameBorder.setLayoutParams(lp);
  }

  private int dpToPx(int dp) {
    float d = getResources().getDisplayMetrics().density;
    return Math.round(dp * d);
  }

  private static int clamp(int v, int min, int max) {
    return Math.max(min, Math.min(max, v));
  }

  private static Rect convertRectFromViewToRoot(@NonNull View fromView, @NonNull View root, @NonNull Rect rectInFromView) {
    int[] fromLoc = new int[2];
    int[] rootLoc = new int[2];
    fromView.getLocationOnScreen(fromLoc);
    root.getLocationOnScreen(rootLoc);

    int dx = fromLoc[0] - rootLoc[0];
    int dy = fromLoc[1] - rootLoc[1];

    return new Rect(
            rectInFromView.left + dx,
            rectInFromView.top + dy,
            rectInFromView.right + dx,
            rectInFromView.bottom + dy
    );
  }

  private void toggleTorch() {
    if (!hasFlash) return;

    torchOn = !torchOn;
    if (torchOn) {
      scannerView.setTorchOn();
      flashButton.setImageResource(R.drawable.ic_flash_on);
    } else {
      scannerView.setTorchOff();
      flashButton.setImageResource(R.drawable.ic_flash_off);
    }
  }

  private void finishOk(@NonNull String contents) {
    finished = true;
    Intent data = new Intent();
    data.putExtra(EXTRA_QR_CONTENTS, contents);
    setResult(Activity.RESULT_OK, data);
    finish();
  }

  private void finishCanceled() {
    finished = true;
    setResult(Activity.RESULT_CANCELED);
    finish();
  }
}
