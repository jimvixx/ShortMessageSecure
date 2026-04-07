/*
 * Copyright (C) 2015 Whisper Systems
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

package org.jimvixx.smsecure.util;

import android.content.Context;
import android.preference.PreferenceManager;
import android.provider.Settings;

import org.jimvixx.smsecure.BuildConfig;
import org.jimvixx.smsecure.logging.Log;
import org.jimvixx.smsecure.preferences.widgets.NotificationPrivacyPreference;
import org.jimvixx.smsecure.util.dualsim.SubscriptionManagerCompat;

import java.io.IOException;

@SuppressWarnings({"BooleanMethodIsAlwaysInverted"})
public class SMSecurePreferences {

  public static final String LOG_LEVEL_PREF = "pref_log_level";
  public static final String CHANGE_PASSPHRASE_PREF = "pref_change_passphrase";
  public static final String DISABLE_PASSPHRASE_PREF = "pref_disable_passphrase";
  public static final String THEME_PREF = "pref_theme";
  public static final String LANGUAGE_PREF = "pref_language";
  public static final String THREAD_TRIM_LENGTH = "pref_trim_length";
  public static final String THREAD_TRIM_NOW = "pref_trim_now";
  public static final String RINGTONE_PREF = "pref_key_ringtone";
  public static final String PASSPHRASE_TIMEOUT_INTERVAL_PREF = "pref_timeout_interval";
  public static final String SCREEN_SECURITY_PREF = "pref_screen_security";
  public static final String ENTER_KEY_TYPE_PREF = "pref_enter_key_type";
  public static final String REGISTERED_GCM_PREF = "pref_gcm_registered";
  public static final String REPEAT_ALERTS_PREF = "pref_repeat_notification";
  public static final String NOTIFICATION_PRIVACY_PREF = "pref_notification_privacy";
  public static final String SYSTEM_EMOJI_PREF = "pref_system_emoji";
  public static final String INCOGNITO_KEYBOARD_PREF = "pref_incognito_keyboard";
  public static final String ASK_FOR_SIM_CARD = "pref_always_ask_for_sim_card";
  public static final String PASSPHRASE_TIMEOUT_PREF = "pref_timeout_passphrase";
  private static final String TAG = SMSecurePreferences.class.getSimpleName();
  private static final String ENABLE_PANIC_BUTTON_PREF = "pref_enable_panic_button";
  private static final String ENABLE_PANIC_ON_DEVICE_LOCK_PREF = "pref_enable_panic_on_device_lock_button";
  private static final String LAST_VERSION_CODE_PREF = "last_version_code";
  private static final String IS_FIRST_RUN = "is_first_run";
  private static final String PERMISSIONS_ASKED = "permissions_asked";
  private static final String NOTIFICATION_PREF = "pref_key_enable_notifications";
  private static final String AUTO_KEY_EXCHANGE_PREF = "pref_auto_complete_key_exchange";
  private static final String PROMPTED_DELIVERY_REPORTS_PREF = "pref_prompted_delivery_reports";
  private static final String SMS_DELIVERY_REPORT_PREF = "pref_delivery_report_sms";
  private static final String SMS_DELIVERY_REPORT_TOAST_PREF = "pref_delivery_report_toast_sms";
  private static final String THREAD_TRIM_ENABLED = "pref_trim_threads";
  private static final String LOCAL_NUMBER_PREF = "pref_local_number";
  private static final String PROMPTED_DEFAULT_SMS_PREF = "pref_prompted_default_sms";
  private static final String IN_THREAD_NOTIFICATION_PREF = "pref_key_inthread_notifications";
  private static final String SHOW_SENT_TIME = "pref_show_sent_time";
  private static final String HIDE_UNREAD_MESSAGE_DIVIDER = "pref_hide_unread_message_divider";
  private static final String LOCAL_REGISTRATION_ID_PREF = "pref_local_registration_id";
  private static final String RATING_LATER_PREF = "pref_rating_later";
  private static final String RATING_ENABLED_PREF = "pref_rating_enabled";
  private static final String APP_SUBSCRIPTION_ID_FOR_DEVICE_SUBSCRIPTION_ID_PREF = "app_subscription_id_for_device_subscription_id";
  private static final String LAST_APP_SUBSCRIPTION_ID_PREF = "last_app_subscription_id";
  private static final String NUMBER_FOR_APP_SUBSCRIPTION_ID_PREF = "number_for_app_subscription_id";
  private static final String ICC_ID_FOR_APP_SUBSCRIPTION_ID_PREF = "icc_id_for_app_subscription_id";
  private static final String SUBSCRIPTIONS_PREF = "pref_subscriptions";
  private static final String SYSTEM_LOG_ENABLED_PREF = "pref_system_log_enabled";

  private SMSecurePreferences() {
  }

  public static boolean isIncognitoKeyboardEnabled(Context context) {
    return getBooleanPreference(context, INCOGNITO_KEYBOARD_PREF, true);
  }

  public static boolean isSimCardAsked(Context context) {
    return SubscriptionManagerCompat.from(context).getActiveSubscriptionInfoList().size() > 1 &&
            getBooleanPreference(context, ASK_FOR_SIM_CARD, true);
  }

  public static void setSimCardAsked(Context context, boolean enabled) {
    setBooleanPreference(context, ASK_FOR_SIM_CARD, enabled);
  }

  public static NotificationPrivacyPreference getNotificationPrivacy(Context context) {
    return new NotificationPrivacyPreference(getStringPreference(context, NOTIFICATION_PRIVACY_PREF, "all"));
  }

  public static long getRatingLaterTimestamp(Context context) {
    return getLongPreference(context, RATING_LATER_PREF, 0);
  }

  public static void setRatingLaterTimestamp(Context context, long timestamp) {
    setLongPreference(context, RATING_LATER_PREF, timestamp);
  }

  public static boolean isRatingEnabled(Context context) {
    return getBooleanPreference(context, RATING_ENABLED_PREF, true);
  }

  public static void setRatingEnabled(Context context, boolean enabled) {
    setBooleanPreference(context, RATING_ENABLED_PREF, enabled);
  }

  public static int getRepeatAlertsCount(Context context) {
    try {
      return Integer.parseInt(getStringPreference(context, REPEAT_ALERTS_PREF, "0"));
    } catch (NumberFormatException e) {
      Log.w(TAG, e);
      return 0;
    }
  }

  public static void setRepeatAlertsCount(Context context, int count) {
    setStringPreference(context, REPEAT_ALERTS_PREF, String.valueOf(count));
  }

  public static boolean isPanicButtonEnabled(Context context) {
    return getBooleanPreference(context, ENABLE_PANIC_BUTTON_PREF, false);
  }

  public static void setPanicButtonEnabled(Context context, boolean enabled) {
    setBooleanPreference(context, ENABLE_PANIC_BUTTON_PREF, enabled);
  }

  public static boolean isPanicOnDeviceLockEnabled(Context context) {
    return getBooleanPreference(context, ENABLE_PANIC_ON_DEVICE_LOCK_PREF, false);
  }

  public static void setPanicOnDeviceLockEnabled(Context context, boolean enabled) {
    setBooleanPreference(context, ENABLE_PANIC_ON_DEVICE_LOCK_PREF, enabled);
  }

  public static int getLocalRegistrationId(Context context) {
    return getIntegerPreference(context, LOCAL_REGISTRATION_ID_PREF, 0);
  }

  public static void setLocalRegistrationId(Context context, int registrationId) {
    setIntegerPreference(context, LOCAL_REGISTRATION_ID_PREF, registrationId);
  }

  public static boolean isInThreadNotifications(Context context) {
    return getBooleanPreference(context, IN_THREAD_NOTIFICATION_PREF, true);
  }

  public static String getLocalNumber(Context context) {
    return getStringPreference(context, LOCAL_NUMBER_PREF, "No Stored Number");
  }

  public static void setLocalNumber(Context context, String localNumber) {
    setStringPreference(context, LOCAL_NUMBER_PREF, localNumber);
  }

  public static String getEnterKeyType(Context context) {
    return getStringPreference(context, ENTER_KEY_TYPE_PREF, "enter");
  }

  public static boolean isPasswordDisabled(Context context) {
    return getBooleanPreference(context, DISABLE_PASSPHRASE_PREF, false);
  }

  public static void setPasswordDisabled(Context context, boolean disabled) {
    setBooleanPreference(context, DISABLE_PASSPHRASE_PREF, disabled);
  }

  public static boolean isAutoRespondKeyExchangeEnabled(Context context) {
    return getBooleanPreference(context, AUTO_KEY_EXCHANGE_PREF, true);
  }

  public static boolean isScreenSecurityEnabled(Context context) {
    return getBooleanPreference(context, SCREEN_SECURITY_PREF, true);
  }

  public static int getLastVersionCode(Context context) {
    return getIntegerPreference(context, LAST_VERSION_CODE_PREF, 0);
  }

  public static void setLastVersionCode(Context context, int versionCode) throws IOException {
    if (!setIntegerPreferenceBlocking(context, LAST_VERSION_CODE_PREF, versionCode)) {
      throw new IOException("couldn't write version code to sharedpreferences");
    }
  }

  public static boolean isFirstRun(Context context) {
    return getBooleanPreference(context, IS_FIRST_RUN, true);
  }

  public static void setFirstRun(Context context) {
    setBooleanPreference(context, IS_FIRST_RUN, false);
  }

  public static boolean permissionsAsked(Context context) {
    return getBooleanPreference(context, PERMISSIONS_ASKED, false);
  }

  public static void setPermissionsAsked(Context context) {
    setBooleanPreference(context, PERMISSIONS_ASKED, true);
  }

  public static String getTheme(Context context) {
    return getStringPreference(context, THEME_PREF, "auto");
  }

  public static boolean isPushRegistered(Context context) {
    return getBooleanPreference(context, REGISTERED_GCM_PREF, false);
  }

  public static void setPushRegistered(Context context, boolean registered) {
    Log.w(TAG, "Setting push registered: " + registered);
    setBooleanPreference(context, REGISTERED_GCM_PREF, registered);
  }

  public static boolean isPassphraseTimeoutEnabled(Context context) {
    return getBooleanPreference(context, PASSPHRASE_TIMEOUT_PREF, false);
  }

  public static int getPassphraseTimeoutInterval(Context context) {
    return getIntegerPreference(context, PASSPHRASE_TIMEOUT_INTERVAL_PREF, 5 * 60);
  }

  public static void setPassphraseTimeoutInterval(Context context, int interval) {
    setIntegerPreference(context, PASSPHRASE_TIMEOUT_INTERVAL_PREF, interval);
  }

  public static String getLanguage(Context context) {
    return getStringPreference(context, LANGUAGE_PREF, "zz");
  }

  public static void setLanguage(Context context, String language) {
    setStringPreference(context, LANGUAGE_PREF, language);
  }

  public static boolean hasPromptedDeliveryReportsReminder(Context context) {
    return getBooleanPreference(context, PROMPTED_DELIVERY_REPORTS_PREF, false);
  }

  public static void setPromptedDeliveryReportsReminder(Context context) {
    setBooleanPreference(context, PROMPTED_DELIVERY_REPORTS_PREF, true);
  }

  public static void setSmsDeliveryReportsEnabled(Context context) {
    setBooleanPreference(context, SMS_DELIVERY_REPORT_PREF, true);
  }

  public static boolean isSmsDeliveryReportsEnabled(Context context) {
    return getBooleanPreference(context, SMS_DELIVERY_REPORT_PREF, false);
  }

  public static boolean isSmsDeliveryReportsToastEnabled(Context context) {
    return getBooleanPreference(context, SMS_DELIVERY_REPORT_TOAST_PREF, false);
  }

  public static boolean hasPromptedDefaultSmsProvider(Context context) {
    return getBooleanPreference(context, PROMPTED_DEFAULT_SMS_PREF, false);
  }

  public static void setPromptedDefaultSmsProvider(Context context, boolean value) {
    setBooleanPreference(context, PROMPTED_DEFAULT_SMS_PREF, value);
  }

  public static boolean isNotificationsEnabled(Context context) {
    return getBooleanPreference(context, NOTIFICATION_PREF, true);
  }

  public static String getNotificationRingtone(Context context) {
    return getStringPreference(context, RINGTONE_PREF, Settings.System.DEFAULT_NOTIFICATION_URI.toString());
  }

  public static boolean isThreadLengthTrimmingEnabled(Context context) {
    return getBooleanPreference(context, THREAD_TRIM_ENABLED, false);
  }

  public static int getThreadTrimLength(Context context) {
    return Integer.parseInt(getStringPreference(context, THREAD_TRIM_LENGTH, "500"));
  }

  public static boolean isSystemEmojiPreferred(Context context) {
    return getBooleanPreference(context, SYSTEM_EMOJI_PREF, false);
  }

  public static boolean showSentTime(Context context) {
    return getBooleanPreference(context, SHOW_SENT_TIME, false);
  }

  public static boolean hideUnreadMessageDivider(Context context) {
    return getBooleanPreference(context, HIDE_UNREAD_MESSAGE_DIVIDER, false);
  }

  public static int getLastAppSubscriptionId(Context context) {
    return getIntegerPreference(context, LAST_APP_SUBSCRIPTION_ID_PREF, 0);
  }

  public static void setLastAppSubscriptionId(Context context, int appSubscriptionId) {
    setIntegerPreference(context, LAST_APP_SUBSCRIPTION_ID_PREF, appSubscriptionId);
  }

  public static int getAppSubscriptionId(Context context, int deviceSubscriptionId) {
    return getIntegerPreference(context, APP_SUBSCRIPTION_ID_FOR_DEVICE_SUBSCRIPTION_ID_PREF + "_" + deviceSubscriptionId, -1);
  }

  public static void setAppSubscriptionId(Context context, int deviceSubscriptionId, int appSubscriptionId) {
    setIntegerPreference(context, APP_SUBSCRIPTION_ID_FOR_DEVICE_SUBSCRIPTION_ID_PREF + "_" + appSubscriptionId, deviceSubscriptionId);
    if (appSubscriptionId > getLastAppSubscriptionId(context))
      setLastAppSubscriptionId(context, appSubscriptionId);
  }

  public static void setNumberForSubscriptionId(Context context, int subscriptionId, String number) {
    setStringPreference(context, NUMBER_FOR_APP_SUBSCRIPTION_ID_PREF + "_" + subscriptionId, number);
  }

  public static String getNumberForSubscriptionId(Context context, int subscriptionId) {
    return getStringPreference(context, NUMBER_FOR_APP_SUBSCRIPTION_ID_PREF + "_" + subscriptionId, null);
  }

  public static void setIccIdForSubscriptionId(Context context, int subscriptionId, String iccId) {
    setStringPreference(context, ICC_ID_FOR_APP_SUBSCRIPTION_ID_PREF + "_" + subscriptionId, iccId);
  }

  public static String getIccIdForSubscriptionId(Context context, int subscriptionId) {
    return getStringPreference(context, ICC_ID_FOR_APP_SUBSCRIPTION_ID_PREF + "_" + subscriptionId, null);
  }

  public static void setDeviceSubscriptions(Context context, String subscriptions) {
    setStringPreference(context, SUBSCRIPTIONS_PREF, subscriptions);
  }

  public static String getDeviceSubscriptions(Context context) {
    return getStringPreference(context, SUBSCRIPTIONS_PREF, "");
  }

  public static Log.Level getLogLevel(Context context) {
    String value = getStringPreference(context, LOG_LEVEL_PREF, null);

    if (value == null) {
      return BuildConfig.DEBUG ? Log.Level.VERBOSE : Log.Level.ERRORS_ONLY;
    }

    return switch (value) {
      case "off" -> Log.Level.OFF;
      case "errors" -> Log.Level.ERRORS_ONLY;
      case "basic" -> Log.Level.BASIC;
      case "verbose" -> Log.Level.VERBOSE;
      default -> BuildConfig.DEBUG ? Log.Level.VERBOSE : Log.Level.ERRORS_ONLY;
    };
  }

  public static void setLogLevel(Context context, Log.Level level) {
    String value = switch (level) {
      case OFF -> "off";
      case ERRORS_ONLY -> "errors";
      case BASIC -> "basic";
      default -> "verbose";
    };

    setStringPreference(context, LOG_LEVEL_PREF, value);
  }

  public static boolean isSystemLogEnabled(Context context) {
    return getBooleanPreference(context, SYSTEM_LOG_ENABLED_PREF, BuildConfig.DEBUG);
  }

  public static void setSystemLogEnabled(Context context, boolean enabled) {
    setBooleanPreference(context, SYSTEM_LOG_ENABLED_PREF, enabled);
  }

  @SuppressWarnings("deprecation")
  public static void setBooleanPreference(Context context, String key, boolean value) {
    PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(key, value).apply();
  }

  @SuppressWarnings("deprecation")
  public static boolean getBooleanPreference(Context context, String key, boolean defaultValue) {
    return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(key, defaultValue);
  }

  @SuppressWarnings("deprecation")
  public static void setStringPreference(Context context, String key, String value) {
    PreferenceManager.getDefaultSharedPreferences(context).edit().putString(key, value).apply();
  }

  @SuppressWarnings("deprecation")
  public static String getStringPreference(Context context, String key, String defaultValue) {
    return PreferenceManager.getDefaultSharedPreferences(context).getString(key, defaultValue);
  }

  @SuppressWarnings("deprecation")
  private static int getIntegerPreference(Context context, String key, int defaultValue) {
    return PreferenceManager.getDefaultSharedPreferences(context).getInt(key, defaultValue);
  }

  @SuppressWarnings("deprecation")
  private static void setIntegerPreference(Context context, String key, int value) {
    PreferenceManager.getDefaultSharedPreferences(context).edit().putInt(key, value).apply();
  }

  @SuppressWarnings("deprecation")
  private static boolean setIntegerPreferenceBlocking(Context context, String key, int value) {
    return PreferenceManager.getDefaultSharedPreferences(context).edit().putInt(key, value).commit();
  }

  @SuppressWarnings("deprecation")
  private static long getLongPreference(Context context, String key, long defaultValue) {
    return PreferenceManager.getDefaultSharedPreferences(context).getLong(key, defaultValue);
  }

  @SuppressWarnings("deprecation")
  private static void setLongPreference(Context context, String key, long value) {
    PreferenceManager.getDefaultSharedPreferences(context).edit().putLong(key, value).apply();
  }
}