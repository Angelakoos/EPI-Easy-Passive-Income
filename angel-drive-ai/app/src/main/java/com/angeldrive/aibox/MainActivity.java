package com.angeldrive.aibox;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends Activity implements LocationListener {
    private static final int LOCATION_REQUEST = 41;
    private static final AtomicReference<MainActivity> INSTANCE = new AtomicReference<>();
    private WebView webView;
    private LocationManager locationManager;
    private boolean pageReady;

    public static void notifyMediaAccessChanged() {
        MainActivity activity = INSTANCE.get();
        if (activity != null) activity.sendAccessState();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        INSTANCE.set(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();

        webView = new WebView(this);
        setContentView(webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        webView.setBackgroundColor(0xFF05070A);
        webView.addJavascriptInterface(new Bridge(), "Android");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                pageReady = true;
                sendAccessState();
            }
        });
        webView.loadUrl("file:///android_asset/index.html");

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        requestLocation();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    private void requestLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST);
        } else startLocation();
    }

    private void startLocation() {
        if (locationManager == null) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, this);
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) onLocationChanged(last);
        } catch (Exception ignored) { }
    }

    @Override public void onLocationChanged(Location location) {
        if (!pageReady) return;
        double speed = location.hasSpeed() ? location.getSpeed() * 3.6d : 0d;
        double bearing = location.hasBearing() ? location.getBearing() : 0d;
        String js = String.format(Locale.US,
                "window.AngelDrive&&window.AngelDrive.updateLocation(%f,%f,%f,%f);",
                location.getLatitude(), location.getLongitude(), speed, bearing);
        webView.evaluateJavascript(js, null);
    }

    @Override protected void onResume() {
        super.onResume();
        INSTANCE.set(this);
        immersive();
        startLocation();
    }

    @Override protected void onPause() {
        super.onPause();
        try { if (locationManager != null) locationManager.removeUpdates(this); } catch (Exception ignored) { }
    }

    @Override protected void onDestroy() {
        INSTANCE.compareAndSet(this, null);
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    private void sendAccessState() {
        if (pageReady && webView != null) webView.evaluateJavascript("window.AngelDrive&&window.AngelDrive.setReady();", null);
    }

    private void openPackage(String packageName) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch != null) {
            startActivity(launch);
            return;
        }
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + packageName))); }
        catch (Exception e) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + packageName))); }
    }

    private void mediaKey(int keyCode) {
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio == null) return;
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }

    public class Bridge {
        @JavascriptInterface public void open(String target) {
            runOnUiThread(() -> {
                try {
                    switch (target) {
                        case "waze" -> openPackage("com.waze");
                        case "spotify" -> openPackage("com.spotify.music");
                        case "maps" -> openPackage("com.google.android.apps.maps");
                        case "assistant" -> startActivity(new Intent(Intent.ACTION_VOICE_COMMAND));
                        case "settings" -> startActivity(new Intent(Settings.ACTION_SETTINGS));
                        case "home" -> startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
                        case "location" -> requestLocation();
                        case "exit" -> finish();
                        default -> Toast.makeText(MainActivity.this, "Acción no disponible", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "No se pudo abrir la aplicación", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface public void media(String action) {
            runOnUiThread(() -> {
                int key = switch (action) {
                    case "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS;
                    case "next" -> KeyEvent.KEYCODE_MEDIA_NEXT;
                    default -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
                };
                mediaKey(key);
            });
        }
    }
}
