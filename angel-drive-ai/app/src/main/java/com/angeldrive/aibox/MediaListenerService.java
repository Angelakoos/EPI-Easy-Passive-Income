package com.angeldrive.aibox;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class MediaListenerService extends NotificationListenerService {
    @Override public void onListenerConnected() { super.onListenerConnected(); MainActivity.notifyMediaAccessChanged(); }
    @Override public void onListenerDisconnected() { super.onListenerDisconnected(); MainActivity.notifyMediaAccessChanged(); }
    @Override public void onNotificationPosted(StatusBarNotification sbn) { if (sbn != null && "com.spotify.music".equals(sbn.getPackageName())) MainActivity.notifyMediaAccessChanged(); }
    @Override public void onNotificationRemoved(StatusBarNotification sbn) { if (sbn != null && "com.spotify.music".equals(sbn.getPackageName())) MainActivity.notifyMediaAccessChanged(); }
}
