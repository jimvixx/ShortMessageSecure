package org.jimvixx.smsecure.database;

import static org.assertj.core.api.Assertions.assertThat;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CanonicalAddressDatabaseTest {

  private static final String AMBIGUOUS_NUMBER = "222-3333";
  private static final String SPECIFIC_NUMBER = "+49 444 222 3333";
  private static final String EMAIL = "a@b.fom";
  private static final String SIMILAR_EMAIL = "a@b.com";
  private static final String GROUP = "__textsecure_group__!000111222333";
  private static final String SIMILAR_GROUP = "__textsecure_group__!100111222333";
  private static final String ALPHA = "T-Mobile";
  private static final String SIMILAR_ALPHA = "T-Mobila";

  private CanonicalAddressDatabase db;

  @Before
  public void setUp() {
    Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    db = CanonicalAddressDatabase.getInstance(targetContext);
    db.reset(targetContext);
  }

  @After
  public void tearDown() {
    if (db != null) {
      db.close();
      db = null;
    }
  }

  @Test
  public void testNumberAddressUpdates() {
    final long id = db.getCanonicalAddressId(AMBIGUOUS_NUMBER);

    assertThat(db.getAddressFromId(id)).isEqualTo(AMBIGUOUS_NUMBER);
    assertThat(db.getCanonicalAddressId(SPECIFIC_NUMBER)).isEqualTo(id);
    assertThat(db.getAddressFromId(id)).isEqualTo(SPECIFIC_NUMBER);
    assertThat(db.getCanonicalAddressId(AMBIGUOUS_NUMBER)).isEqualTo(id);

    assertThat(db.getCanonicalAddressId(AMBIGUOUS_NUMBER)).isEqualTo(id);
    assertThat(db.getAddressFromId(id)).isEqualTo(AMBIGUOUS_NUMBER);
    assertThat(db.getCanonicalAddressId(SPECIFIC_NUMBER)).isEqualTo(id);
    assertThat(db.getAddressFromId(id)).isEqualTo(SPECIFIC_NUMBER);
    assertThat(db.getCanonicalAddressId(AMBIGUOUS_NUMBER)).isEqualTo(id);
  }

  @Test
  public void testSimilarNumbers() {
    assertThat(db.getCanonicalAddressId("This is a phone number 222-333-444"))
            .isNotEqualTo(db.getCanonicalAddressId("222-333-4444"));

    assertThat(db.getCanonicalAddressId("222-333-444"))
            .isNotEqualTo(db.getCanonicalAddressId("222-333-4444"));
    assertThat(db.getCanonicalAddressId("222-333-44"))
            .isNotEqualTo(db.getCanonicalAddressId("222-333-4444"));
    assertThat(db.getCanonicalAddressId("222-333-4"))
            .isNotEqualTo(db.getCanonicalAddressId("222-333-4444"));

    assertThat(db.getCanonicalAddressId("+49 222-333-4444"))
            .isNotEqualTo(db.getCanonicalAddressId("+1 222-333-4444"));

    long base = db.getCanonicalAddressId("222-333-4444");

    long v1 = db.getCanonicalAddressId("1 222-333-4444");
    long v2 = db.getCanonicalAddressId("1 (222) 333-4444");
    long v3 = db.getCanonicalAddressId("+12223334444");
    long v4 = db.getCanonicalAddressId("+1 (222) 333.4444");

    assertThat(v1).isGreaterThan(0);
    assertThat(v2).isGreaterThan(0);
    assertThat(v3).isGreaterThan(0);
    assertThat(v4).isGreaterThan(0);
    assertThat(base).isGreaterThan(0);

    assertThat(db.getAddressFromId(base)).isNotEmpty();
    assertThat(db.getAddressFromId(v1)).isNotEmpty();
    assertThat(db.getAddressFromId(v2)).isNotEmpty();
    assertThat(db.getAddressFromId(v3)).isNotEmpty();
    assertThat(db.getAddressFromId(v4)).isNotEmpty();
  }

  @Test
  public void testEmailAddresses() {
    final long emailId = db.getCanonicalAddressId(EMAIL);
    final long similarEmailId = db.getCanonicalAddressId(SIMILAR_EMAIL);

    assertThat(emailId).isNotEqualTo(similarEmailId);
    assertThat(db.getAddressFromId(emailId)).isEqualTo(EMAIL);
    assertThat(db.getAddressFromId(similarEmailId)).isEqualTo(SIMILAR_EMAIL);
  }

  @Test
  public void testGroups() {
    final long groupId = db.getCanonicalAddressId(GROUP);
    final long similarGroupId = db.getCanonicalAddressId(SIMILAR_GROUP);

    assertThat(groupId).isNotEqualTo(similarGroupId);
    assertThat(db.getAddressFromId(groupId)).isEqualTo(GROUP);
    assertThat(db.getAddressFromId(similarGroupId)).isEqualTo(SIMILAR_GROUP);
  }

  @Test
  public void testAlpha() {
    final long id = db.getCanonicalAddressId(ALPHA);
    final long similarId = db.getCanonicalAddressId(SIMILAR_ALPHA);

    assertThat(id).isNotEqualTo(similarId);
    assertThat(db.getAddressFromId(id)).isEqualTo(ALPHA);
    assertThat(db.getAddressFromId(similarId)).isEqualTo(SIMILAR_ALPHA);
  }

  @Test
  public void testIsNumber() {
    assertThat(CanonicalAddressDatabase.isNumberAddress("+495556666777")).isTrue();
    assertThat(CanonicalAddressDatabase.isNumberAddress("(222) 333-4444")).isTrue();
    assertThat(CanonicalAddressDatabase.isNumberAddress("1 (222) 333-4444")).isTrue();
    assertThat(CanonicalAddressDatabase.isNumberAddress("T-Mobile123")).isTrue();
    assertThat(CanonicalAddressDatabase.isNumberAddress("333-4444")).isTrue();
    assertThat(CanonicalAddressDatabase.isNumberAddress("12345")).isTrue();

    assertThat(CanonicalAddressDatabase.isNumberAddress("T-Mobile")).isFalse();
    assertThat(CanonicalAddressDatabase.isNumberAddress("T-Mobile1")).isFalse();
    assertThat(CanonicalAddressDatabase.isNumberAddress("Wherever bank")).isFalse();
    assertThat(CanonicalAddressDatabase.isNumberAddress("__textsecure_group__!afafafafafaf")).isFalse();
    assertThat(CanonicalAddressDatabase.isNumberAddress("email@domain.com")).isFalse();
  }
}
