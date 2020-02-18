package com.dieam.reactnativepushnotification.modules;

import java.util.Map;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.Application;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.app.KeyguardManager;
import android.app.KeyguardManager.KeyguardLock;
import android.view.Window;
import android.view.WindowManager;
import android.app.Activity;

import com.dieam.reactnativepushnotification.helpers.ApplicationBadgeHelper;
import com.facebook.react.ReactApplication;
import com.facebook.react.ReactInstanceManager;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.UiThreadUtil;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static com.dieam.reactnativepushnotification.modules.RNPushNotification.LOG_TAG;

public class RNPushNotificationListenerService extends FirebaseMessagingService {
    public static final String TWI_MSG_CALL   = "twilio.voice.call";
		public static final String TWI_MSG_CANCEL = "twilio.voice.cancel";
		private static final String[] alertsNotifCategory 	 = {"CAL_SEN","CAL_ACC","CAL_REC","CAL_UPD","HEA_SEN","HEA_REC","HEA_ACC","SHO_ADD","SHO_REM","PHO_SEN","PHO_REC","PHO_ACC","VID_SEN","VID_REC","VID_ACC","MES_SEN","MES_REC","NEA_UPD"};
    private static final String[] videoCallNotifCategory = {"VIDEO_CALL_MISSED"};
		private static final String[] nearMeNotifCategory    = {"NEA_UPD","USER_NEAR_ME"};
    private ReactInstanceManager mReactInstanceManager;
		private Boolean isForeground;

    @Override
    public void onMessageReceived(RemoteMessage message) {
        String from = message.getFrom();
        RemoteMessage.Notification remoteNotification = message.getNotification();

        final Bundle bundle = new Bundle();
        // Putting it from remoteNotification first so it can be overriden if message
        // data has it
        if (remoteNotification != null) {
            // ^ It's null when message is from GCM
            bundle.putString("title", remoteNotification.getTitle());
            bundle.putString("message", remoteNotification.getBody());
        }

        for(Map.Entry<String, String> entry : message.getData().entrySet()) {
            bundle.putString(entry.getKey(), entry.getValue());
        }
        JSONObject data = getPushData(bundle.getString("data"));
        // Copy `twi_body` to `message` to support Twilio
        
        if (bundle.containsKey("twi_title")) {
            bundle.putString("title", bundle.getString("twi_title"));
        }
        if (bundle.containsKey("twi_body")) {
            bundle.putString("message", bundle.getString("twi_body"));
        }

        if (data != null) {
            if (!bundle.containsKey("message")) {
                bundle.putString("message", data.optString("alert", null));
            }
            if (!bundle.containsKey("title")) {
                bundle.putString("title", data.optString("title", null));
            }
            if (!bundle.containsKey("sound")) {
                bundle.putString("soundName", data.optString("sound", null));
            }
            if (!bundle.containsKey("color")) {
                bundle.putString("color", data.optString("color", null));
            }

            final int badge = data.optInt("badge", -1);
            if (badge >= 0) {
                ApplicationBadgeHelper.INSTANCE.setApplicationIconBadgeNumber(this, badge);
            }
        }

        Log.v(LOG_TAG, "onMessageReceived: " + bundle);

        // We need to run this on the main thread, as the React code assumes that is true.
        // Namely, DevServerHelper constructs a Handler() without a Looper, which triggers:
        // "Can't create handler inside thread that has not called Looper.prepare()"
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            public void run() {
                // Construct and load our normal React JS code bundle
								ReactContext context = mReactInstanceManager.getCurrentReactContext();
                // If it's constructed, send a notification
                if (context != null) {
										handleRemotePushNotification((ReactApplicationContext) context, bundle);
                } else {
                    // Otherwise wait for construction, then send the notification
                    mReactInstanceManager.addReactInstanceEventListener(new ReactInstanceManager.ReactInstanceEventListener() {
                        public void onReactContextInitialized(ReactContext context) {
													handleRemotePushNotification((ReactApplicationContext) context, bundle);
                        }
                    });
                    if (!mReactInstanceManager.hasStartedCreatingInitialContext()) {
                        // Construct it in the background
                        mReactInstanceManager.createReactContextInBackground();
                    }
                }
            }
        });
    }

    private JSONObject getPushData(String dataString) {
        try {
            return new JSONObject(dataString);
        } catch (Exception e) {
            return null;
        }
		}

    private void handleRemotePushNotification(ReactApplicationContext context, Bundle bundle) {

			// If notification ID is not provided by the user for push notification, generate one at random
			if (bundle.getString("id") == null) {
				Random randomNumberGenerator = new Random(System.currentTimeMillis());
				bundle.putString("id", String.valueOf(randomNumberGenerator.nextInt()));
			}

			RNPushNotificationJsDelivery jsDelivery = new RNPushNotificationJsDelivery(context);
			bundle.putBoolean("foreground", isForeground);
			bundle.putBoolean("userInteraction", false);
			jsDelivery.notifyNotification(bundle);

			// If contentAvailable is set to true, then send out a remote fetch event
			if (bundle.getString("contentAvailable", "false").equalsIgnoreCase("true")) {
					jsDelivery.notifyRemoteFetch(bundle);
			}

			Application applicationContext = (Application) context.getApplicationContext();
			RNPushNotificationHelper pushNotificationHelper = new RNPushNotificationHelper(applicationContext);

			String notifProfile = pushNotificationHelper.getNotificationSettingsPersistence().getString("notifProfile", null);
			String notifType    = bundle.getString("notifType");

			if ((notifProfile.equals("NOTIF_SILENT") || notifProfile.equals("NOTIF_SIGHT_SOUND")) && bundle.getString("isNotificationShowed", "true").equalsIgnoreCase("true")) {
				pushNotificationHelper.sendToNotificationCentre(bundle);
			} else if (notifProfile.equals("NOTIF_CUSTOMIZE") && bundle.getString("isNotificationShowed", "true").equalsIgnoreCase("true")) {
				Boolean showNotification = true;
				Boolean hasSound 				 = true;
				String soundName 				 = null;

				if (Arrays.asList(alertsNotifCategory).contains(notifType)) {
					showNotification = !Boolean.parseBoolean(pushNotificationHelper.getNotificationSettingsPersistence().getString("isAlertSilent", "false"));
					hasSound 				 = !Boolean.parseBoolean(pushNotificationHelper.getNotificationSettingsPersistence().getString("isAlertMuted", "false"));
					soundName 			 = hasSound ? pushNotificationHelper.getNotificationSettingsPersistence().getString("notifAlertSound", null) : null;

				} else if (Arrays.asList(nearMeNotifCategory).contains(notifType)) {
					showNotification = !Boolean.parseBoolean(pushNotificationHelper.getNotificationSettingsPersistence().getString("isNearMeSilent", "false"));
					hasSound 				 = !Boolean.parseBoolean(pushNotificationHelper.getNotificationSettingsPersistence().getString("isNearMeMuted", "false"));
					soundName 			 = hasSound ? pushNotificationHelper.getNotificationSettingsPersistence().getString("notifAlertSound", null) : null;
				} else {
					//Twilio Chat

					showNotification = !Boolean.parseBoolean(pushNotificationHelper.getNotificationSettingsPersistence().getString("isChatSilent", "false"));
					hasSound 				 = !Boolean.parseBoolean(pushNotificationHelper.getNotificationSettingsPersistence().getString("isChatMuted", "false"));
					soundName 			 = hasSound ? pushNotificationHelper.getNotificationSettingsPersistence().getString("notifChatSound", null) : null;
				}

				if (showNotification) {
					bundle.putBoolean("hasSound", hasSound);
					bundle.putString("soundName", soundName);
					pushNotificationHelper.sendToNotificationCentre(bundle);
				}
			}
            
			if (notifType != null) {
				if (notifType.equalsIgnoreCase("VID_DIA")) {
					if (!isForeground) {
						Class intentClass = pushNotificationHelper.getMainActivityClass();
						if (intentClass == null) {
								Log.e(LOG_TAG, "No activity class found. ");
								return;
						} else {
							KeyguardManager keyguardManager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
							KeyguardLock keyguardLock = keyguardManager.newKeyguardLock(Context.KEYGUARD_SERVICE);
							keyguardLock.disableKeyguard();
							
							PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
							PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
												PowerManager.FULL_WAKE_LOCK
											| PowerManager.ACQUIRE_CAUSES_WAKEUP
											| PowerManager.SCREEN_BRIGHT_WAKE_LOCK
											| PowerManager.ON_AFTER_RELEASE, "RNUnlockDeviceModule");
							
							wakeLock.acquire();
	
							// Window window = getCurrentActivity().getWindow();
							// window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
							// WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
							// WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
							// WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
	
							Intent activityIntent = new Intent(context, intentClass);
	
							activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK +
								WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED +
								WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD +
								WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON +
								WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
							);
							context.startActivity(activityIntent);
						}
					}
				}
			}
    }

}
