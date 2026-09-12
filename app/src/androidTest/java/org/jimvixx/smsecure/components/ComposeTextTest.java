package org.jimvixx.smsecure.components;

import static org.junit.Assert.*;

import android.content.Context;
import android.text.InputType;
import android.text.StaticLayout;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ComposeTextTest {
  @Test
  public void initialMeasureIncludesBothHintLines() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      for (float textSize : new float[]{18, 30}) {
        for (int mode : new int[]{View.MeasureSpec.EXACTLY, View.MeasureSpec.AT_MOST}) {
          verifyInitialMeasure(textSize, mode, "Über LIDL Connect");
          verifyInitialMeasure(textSize, mode,
                  "Über LIDL Connect very long SIM carrier name that must be ellipsized");
        }
      }
    });
  }

  private void verifyInitialMeasure(float textSize, int mode, String carrier) {
    Context context = new ContextThemeWrapper(
            InstrumentationRegistry.getInstrumentation().getTargetContext(),
            androidx.appcompat.R.style.Theme_AppCompat);
    ComposeText field = new ComposeText(context);
    field.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    field.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize);
    field.setPadding(6, 6, 6, 6);
    field.setMinHeight(0);
    field.setMaxLines(4);
    field.setHint("Nachricht", carrier);
    int width = View.MeasureSpec.makeMeasureSpec(400, mode);
    int height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
    assertFalse(field.hasFocus());
    assertEquals(0, field.getWidth());
    field.measure(width, height);
    assertNotNull(field.getHint());
    assertEquals(2, field.getHint().toString().split("\n", -1).length);
    int initialHeight = field.getMeasuredHeight();
    StaticLayout hintLayout = StaticLayout.Builder.obtain(field.getHint(), 0,
            field.getHint().length(), field.getPaint(), field.getMeasuredWidth()
                    - field.getCompoundPaddingLeft() - field.getCompoundPaddingRight())
            .setIncludePad(field.getIncludeFontPadding())
            .setLineSpacing(field.getLineSpacingExtra(), field.getLineSpacingMultiplier())
            .build();
    assertEquals("Long carrier names must stay on the second line", 2, hintLayout.getLineCount());
    assertTrue("The complete hint must fit before layout or focus",
            initialHeight >= hintLayout.getHeight()
                    + field.getCompoundPaddingTop() + field.getCompoundPaddingBottom());
    field.layout(0, 0, field.getMeasuredWidth(), initialHeight);
    field.forceLayout();
    field.measure(width, height);
    assertEquals("First layout must not change the required height", initialHeight,
            field.getMeasuredHeight());
    field.requestFocus();
    field.forceLayout();
    field.measure(width, height);
    assertEquals("Focus must not repair the hint height", initialHeight, field.getMeasuredHeight());
    field.clearFocus();
    field.setHint("Nachricht", null);
    field.measure(width, height);
    assertTrue("Two lines need more height than one", initialHeight > field.getMeasuredHeight());
    field.setHint("Nachricht", carrier);
    field.measure(width, height);
    assertEquals(initialHeight, field.getMeasuredHeight());
    field.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY), height);
    assertEquals("Narrow width must retain two lines", initialHeight, field.getMeasuredHeight());
  }
}
