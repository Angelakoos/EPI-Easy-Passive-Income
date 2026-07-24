package com.angeldrive.aibox;

import android.service.notification.NotificationListenerService;

public class MediaListenerService extends NotificationListenerService {
    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        MainActivity.notifyMediaAccessChanged();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        MainActivity.notifyMediaAccessChanged();
    }
}
