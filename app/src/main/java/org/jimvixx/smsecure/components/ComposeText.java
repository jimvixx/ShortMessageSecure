/*
 * Copyright (C) 2015 Open Whisper Systems
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

package org.jimvixx.smsecure.components;

import android.content.Context;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.TextUtils.TruncateAt;
import android.text.style.RelativeSizeSpan;
import android.util.AttributeSet;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.inputmethod.EditorInfoCompat;
import androidx.core.view.inputmethod.InputConnectionCompat;
import androidx.core.view.inputmethod.InputContentInfoCompat;

import org.jimvixx.smsecure.R;
import org.jimvixx.smsecure.TransportOption;
import org.jimvixx.smsecure.components.emoji.EmojiEditText;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.util.SMSecurePreferences;

/**
 * Compose input field used in the conversation screen.
 * <p>
 * Features:
 * - Transport-specific hint + optional sub-hint (e.g. "via SIM 1")
 * - Hint is ellipsized to the current view width
 * - Supports committing images from IMEs via InputConnectionCompat (GIF/PNG/JPEG)
 */
public class ComposeText extends EmojiEditText {

  /**
   * Reused builder for composing a two-line hint.
   */
  private final SpannableStringBuilder hintBuilder = new SpannableStringBuilder();
  /**
   * Base hint (e.g. "Message").
   */
  @Nullable
  private SpannableString hint;
  /**
   * Optional sub-hint (e.g. "via SIM 1").
   */
  @Nullable
  private SpannableString subHint;

  // ---- Hint composition cache (to avoid allocations during layout) ----
  @Nullable
  private MediaListener mediaListener;
  /**
   * Last width used for hint ellipsizing.
   */
  private int lastHintWidth = -1;

  /**
   * Last computed hint content (used to avoid calling setHint() redundantly).
   */
  @Nullable
  private CharSequence lastComposedHint;

  public ComposeText(Context context) {
    super(context);
    initialize();
  }

  public ComposeText(Context context, AttributeSet attrs) {
    super(context, attrs);
    initialize();
  }

  public ComposeText(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    initialize();
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    super.onLayout(changed, left, top, right, bottom);
    // Recompute the hint only when necessary (width changes trigger re-ellipsizing).
    rebuildHintIfNeeded();
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    if (w != oldw) {
      // Force rebuild when width changes.
      lastHintWidth = -1;
      rebuildHintIfNeeded();
    }
  }

  /**
   * Ellipsizes the given text to the available width for this view.
   */
  private CharSequence ellipsizeToWidth(@NonNull CharSequence text, int widthPx) {
    return TextUtils.ellipsize(text, getPaint(), widthPx, TruncateAt.END);
  }

  /**
   * Updates the hint and sub-hint content (both are styled with a smaller relative text size),
   * then rebuilds the displayed hint.
   */
  public void setHint(@NonNull String hint, @Nullable CharSequence subHint) {
    this.hint = new SpannableString(hint);
    this.hint.setSpan(new RelativeSizeSpan(0.8f), 0, hint.length(),
            Spannable.SPAN_INCLUSIVE_INCLUSIVE);

    if (subHint != null) {
      this.subHint = new SpannableString(subHint);
      this.subHint.setSpan(new RelativeSizeSpan(0.8f), 0, subHint.length(),
              Spannable.SPAN_INCLUSIVE_INCLUSIVE);
    } else {
      this.subHint = null;
    }

    // Hint content changed -> force rebuild on next pass (or immediately if we can).
    lastHintWidth = -1;
    rebuildHintIfNeeded();
  }

  /**
   * Appends an invite token to the current text, inserting a space if needed.
   */
  public void appendInvite(@NonNull String invite) {
    if (!TextUtils.isEmpty(getText()) && !" ".contentEquals(getText())) {
      append(" ");
    }

    append(invite);
    if (getText() != null) setSelection(getText().length());
  }

  private boolean isLandscape() {
    return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
  }

  /**
   * Configures IME options and hint based on the chosen transport (SMS / SIM / etc.).
   */
  public void setTransport(@NonNull TransportOption transport) {
    int imeOptions = (getImeOptions() & ~EditorInfo.IME_MASK_ACTION) | EditorInfo.IME_ACTION_SEND;
    int inputType = getInputType();

    // Only show a custom action label in landscape to preserve vertical space in portrait.
    if (isLandscape()) setImeActionLabel(transport.getComposeHint(), EditorInfo.IME_ACTION_SEND);
    else setImeActionLabel(null, 0);

    setInputType(inputType);
    setImeOptions(imeOptions);

    setHint(transport.getComposeHint(),
            transport.getSimName().isPresent()
                    ? getContext().getString(R.string.conversation_activity__via_sim_name,
                    transport.getSimName().get())
                    : null);
  }

  @Override
  public InputConnection onCreateInputConnection(@NonNull EditorInfo editorInfo) {
    InputConnection inputConnection = super.onCreateInputConnection(editorInfo);

    // super can legally return null.
    if (inputConnection == null) return null;

    // Allow the enter key to produce an action when "send" is selected in preferences.
    if (SMSecurePreferences.getEnterKeyType(getContext()).equals("send")) {
      editorInfo.imeOptions &= ~EditorInfo.IME_FLAG_NO_ENTER_ACTION;
    }

    if (mediaListener == null) return inputConnection;

    // Advertise supported incoming media types to the IME.
    EditorInfoCompat.setContentMimeTypes(editorInfo,
            new String[]{"image/jpeg", "image/png", "image/gif"});

    // Newer androidx.core marks createWrapper as deprecated, but it remains the most reliable
    // compatibility path (especially for API < 25 private-command based IMEs). Keep it and
    // suppress the warning intentionally.
    @SuppressWarnings("deprecation")
    InputConnection wrapped = InputConnectionCompat.createWrapper(
            inputConnection,
            editorInfo,
            new CommitContentListener(mediaListener)
    );

    return wrapped;
  }

  public void setMediaListener(@Nullable MediaListener mediaListener) {
    this.mediaListener = mediaListener;
  }

  private void initialize() {
    // FLAG_NO_PERSONALIZED_LEARNING (0x1000000) for incognito keyboard mode.
    if (SMSecurePreferences.isIncognitoKeyboardEnabled(getContext())) {
      setImeOptions(getImeOptions() | 0x01000000);
    }
  }

  /**
   * Rebuilds and applies the displayed hint only if:
   * - hint/subHint is set
   * - width is known and changed since last build (because ellipsizing depends on width)
   * - composed result differs from last applied hint
   * <p>
   * This avoids allocations and redundant setHint() calls during frequent layout passes.
   */
  private void rebuildHintIfNeeded() {
    if (TextUtils.isEmpty(hint)) return;

    final int widthPx = getWidth() - getPaddingLeft() - getPaddingRight();
    if (widthPx <= 0) return;

    if (widthPx == lastHintWidth && lastComposedHint != null) {
      return;
    }

    lastHintWidth = widthPx;

    hintBuilder.clear();
    hintBuilder.clearSpans();

    // First line.
    hintBuilder.append(ellipsizeToWidth(hint, widthPx));

    // Optional second line.
    if (!TextUtils.isEmpty(subHint)) {
      hintBuilder.append('\n');
      hintBuilder.append(ellipsizeToWidth(subHint, widthPx));
    }

    // Avoid setHint() if identical (prevents extra invalidation).
    if (!TextUtils.equals(lastComposedHint, hintBuilder)) {
      // Freeze current builder content into a stable Spannable instance.
      lastComposedHint = new SpannableString(hintBuilder);
      super.setHint(lastComposedHint);
    }
  }

  public interface MediaListener {
    void onMediaSelected(@NonNull Uri uri, @Nullable String contentType);
  }

  /**
   * Handles rich content (images) committed by IMEs.
   */
  private static class CommitContentListener implements InputConnectionCompat.OnCommitContentListener {

    private static final String TAG = CommitContentListener.class.getName();

    private final MediaListener mediaListener;

    private CommitContentListener(@NonNull MediaListener mediaListener) {
      this.mediaListener = mediaListener;
    }

    @Override
    public boolean onCommitContent(@NonNull InputContentInfoCompat inputContentInfo,
                                   int flags,
                                   Bundle opts) {
      // Request temporary read access when the IME grants it (API 25+ contract).
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 &&
              (flags & InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0) {
        try {
          inputContentInfo.requestPermission();
        } catch (Exception e) {
          Log.w(TAG, e);
          return false;
        }
      }

      if (inputContentInfo.getDescription().getMimeTypeCount() > 0) {
        mediaListener.onMediaSelected(inputContentInfo.getContentUri(),
                inputContentInfo.getDescription().getMimeType(0));
        return true;
      }

      return false;
    }
  }
}