package com.angeldrive.aibox;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends Activity implements LocationListener {
    private static final int LOCATION_REQUEST = 41;
    private static final String SPOTIFY_PACKAGE = "com.spotify.music";
    private static final String USER_AGENT = "AngelDriveAI/0.3 (personal in-car launcher)";
    private static final AtomicReference<MainActivity> INSTANCE = new AtomicReference<>();

    private WebView webView;
    private LocationManager locationManager;
    private MediaSessionManager mediaSessionManager;
    private MediaController spotifyController;
    private Location lastLocation;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private boolean pageReady = false;
    private boolean sessionsListenerRegistered = false;
    private long lastGeocodeRequestAt = 0L;

    private final Runnable mediaTicker = new Runnable() {
        @Override public void run() {
            updateMediaUi();
            handler.postDelayed(this, 1000L);
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChangedListener = controllers -> connectSpotifySession();

    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) { updateMediaUi(); }
        @Override public void onPlaybackStateChanged(PlaybackState state) { updateMediaUi(); }
        @Override public void onSessionDestroyed() { handler.postDelayed(MainActivity.this::connectSpotifySession, 250L); }
    };

    public static void notifyMediaAccessChanged() {
        MainActivity activity = INSTANCE.get();
        if (activity != null) activity.handler.post(activity::connectSpotifySession);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        INSTANCE.set(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enterImmersiveMode();

        webView = new WebView(this);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setContentView(webView);
        configureWebView();

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        mediaSessionManager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        webView.loadUrl("file:///android_asset/index.html");
        requestLocationIfNeeded();
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setUserAgentString(settings.getUserAgentString() + " AngelDriveAI/0.3");
        webView.setBackgroundColor(0xFF05070A);
        webView.addJavascriptInterface(new JsBridge(), "Android");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                pageReady = true;
                sendSystemState();
                connectSpotifySession();
                if (lastLocation != null) onLocationChanged(lastLocation);
            }
        });
    }

    private void enterImmersiveMode() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) enterImmersiveMode(); }

    @Override protected void onResume() {
        super.onResume();
        INSTANCE.set(this);
        enterImmersiveMode();
        startLocationUpdates();
        registerSessionsListener();
        connectSpotifySession();
        handler.removeCallbacks(mediaTicker);
        handler.post(mediaTicker);
    }

    @Override protected void onPause() {
        super.onPause();
        stopLocationUpdates();
        unregisterSessionsListener();
        handler.removeCallbacks(mediaTicker);
    }

    @Override protected void onDestroy() {
        unregisterSessionsListener();
        detachSpotifyController();
        handler.removeCallbacksAndMessages(null);
        networkExecutor.shutdownNow();
        if (webView != null) webView.destroy();
        INSTANCE.compareAndSet(this, null);
        super.onDestroy();
    }

    private void requestLocationIfNeeded() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST);
        } else startLocationUpdates();
    }

    private void startLocationUpdates() {
        if (locationManager == null) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, this);
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 5f, this);
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null) last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (last != null) onLocationChanged(last);
        } catch (SecurityException ignored) { }
    }

    private void stopLocationUpdates() {
        if (locationManager != null) try { locationManager.removeUpdates(this); } catch (SecurityException ignored) { }
    }

    @Override public void onLocationChanged(Location location) {
        if (location == null) return;
        lastLocation = location;
        if (!pageReady) return;
        double speedKmh = location.hasSpeed() ? location.getSpeed() * 3.6d : 0d;
        double bearing = location.hasBearing() ? location.getBearing() : 0d;
        evaluate(String.format(Locale.US, "window.AngelDrive&&window.AngelDrive.updateLocation(%f,%f,%f,%f);", location.getLatitude(), location.getLongitude(), speedKmh, bearing));
    }

    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }

    private ComponentName listenerComponent() { return new ComponentName(this, MediaListenerService.class); }

    private boolean hasNotificationAccess() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        return manager != null && manager.isNotificationListenerAccessGranted(listenerComponent());
    }

    private void registerSessionsListener() {
        if (mediaSessionManager == null || sessionsListenerRegistered || !hasNotificationAccess()) return;
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, listenerComponent(), handler);
            sessionsListenerRegistered = true;
        } catch (SecurityException ignored) { }
    }

    private void unregisterSessionsListener() {
        if (mediaSessionManager != null && sessionsListenerRegistered) try { mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener); } catch (Exception ignored) { }
        sessionsListenerRegistered = false;
    }

    private void detachSpotifyController() {
        if (spotifyController != null) try { spotifyController.unregisterCallback(mediaCallback); } catch (Exception ignored) { }
        spotifyController = null;
    }

    private void connectSpotifySession() {
        registerSessionsListener();
        if (mediaSessionManager == null || !hasNotificationAccess()) {
            detachSpotifyController();
            sendSystemState();
            updateMediaUi();
            return;
        }
        try {
            List<MediaController> sessions = mediaSessionManager.getActiveSessions(listenerComponent());
            MediaController selected = null;
            for (MediaController controller : sessions) if (SPOTIFY_PACKAGE.equals(controller.getPackageName())) { selected = controller; break; }
            boolean changed = selected == null || spotifyController == null || !selected.getSessionToken().equals(spotifyController.getSessionToken());
            if (changed) {
                detachSpotifyController();
                spotifyController = selected;
                if (spotifyController != null) spotifyController.registerCallback(mediaCallback, handler);
            }
        } catch (SecurityException ignored) { detachSpotifyController(); }
        sendSystemState();
        updateMediaUi();
    }

    private void updateMediaUi() {
        if (!pageReady) return;
        boolean access = hasNotificationAccess();
        boolean connected = spotifyController != null;
        String title = "", artist = "", artwork = "";
        boolean playing = false;
        long duration = 0L, position = 0L;
        if (connected) {
            MediaMetadata metadata = spotifyController.getMetadata();
            PlaybackState state = spotifyController.getPlaybackState();
            if (metadata != null) {
                CharSequence t = metadata.getText(MediaMetadata.METADATA_KEY_TITLE);
                CharSequence a = metadata.getText(MediaMetadata.METADATA_KEY_ARTIST);
                if (t != null) title = t.toString();
                if (a != null) artist = a.toString();
                duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
                Bitmap art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (art == null) art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (art == null && metadata.getDescription() != null) art = metadata.getDescription().getIconBitmap();
                if (art != null) artwork = bitmapToDataUri(art);
            }
            if (state != null) {
                playing = state.getState() == PlaybackState.STATE_PLAYING;
                position = Math.max(0L, state.getPosition());
                if (playing && state.getLastPositionUpdateTime() > 0) position += (long) ((android.os.SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
                if (duration > 0) position = Math.min(position, duration);
            }
        }
        evaluate("window.AngelDrive&&window.AngelDrive.updateMedia(" + access + "," + connected + "," + JSONObject.quote(title) + "," + JSONObject.quote(artist) + "," + playing + "," + position + "," + duration + "," + JSONObject.quote(artwork) + ");");
    }

    private String bitmapToDataUri(Bitmap bitmap) {
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 180, 180, true);
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, stream);
            return "data:image/jpeg;base64," + android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP);
        } catch (Exception ignored) { return ""; }
    }

    private void sendSystemState() { if (pageReady) evaluate("window.AngelDrive&&window.AngelDrive.setSystemState(" + hasNotificationAccess() + ");"); }
    private void evaluate(String script) { handler.post(() -> { if (webView != null && pageReady) webView.evaluateJavascript(script, null); }); }

    private void performMediaAction(String action) {
        if (spotifyController != null) {
            MediaController.TransportControls controls = spotifyController.getTransportControls();
            switch (action) {
                case "previous" -> controls.skipToPrevious();
                case "next" -> controls.skipToNext();
                case "play" -> controls.play();
                case "pause" -> controls.pause();
                default -> { PlaybackState state = spotifyController.getPlaybackState(); if (state != null && state.getState() == PlaybackState.STATE_PLAYING) controls.pause(); else controls.play(); }
            }
            return;
        }
        int keyCode = switch (action) { case "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; case "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; case "play" -> KeyEvent.KEYCODE_MEDIA_PLAY; case "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE; default -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; };
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager != null) { audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode)); audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode)); }
    }

    private void launchPackageOrStore(String packageName) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch != null) { launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(launch); }
        else try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + packageName))); } catch (Exception e) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + packageName))); }
    }

    private void openWaze() {
        try { Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("waze://?navigate=yes")); intent.setPackage("com.waze"); startActivity(intent); }
        catch (Exception e) { launchPackageOrStore("com.waze"); }
    }

    private void navigate(String app, double lat, double lon, String label) {
        try {
            if ("waze".equals(app)) { Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.US, "https://waze.com/ul?ll=%f,%f&navigate=yes", lat, lon))); intent.setPackage("com.waze"); startActivity(intent); }
            else { Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.US, "google.navigation:q=%f,%f&mode=d", lat, lon))); intent.setPackage("com.google.android.apps.maps"); startActivity(intent); }
        } catch (Exception e) { launchPackageOrStore("waze".equals(app) ? "com.waze" : "com.google.android.apps.maps"); }
    }

    private void openAssistant() { try { startActivity(new Intent(Intent.ACTION_VOICE_COMMAND)); } catch (Exception e) { Toast.makeText(this, "No se encontró un asistente de voz", Toast.LENGTH_SHORT).show(); } }
    private void chooseHomeApp() { try { startActivity(new Intent(Settings.ACTION_HOME_SETTINGS)); } catch (Exception e) { Intent home = new Intent(Intent.ACTION_MAIN); home.addCategory(Intent.CATEGORY_HOME); startActivity(Intent.createChooser(home, "Elegir pantalla de inicio")); } }

    private String httpGet(String urlString) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setConnectTimeout(12000); connection.setReadTimeout(18000); connection.setRequestMethod("GET"); connection.setRequestProperty("User-Agent", USER_AGENT); connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.5");
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) { String line; while ((line = reader.readLine()) != null) result.append(line); }
        finally { connection.disconnect(); }
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
        return result.toString();
    }

    private void searchDestinationNetwork(String query) {
        long now = System.currentTimeMillis();
        if (now - lastGeocodeRequestAt < 1100L) { evaluate("window.AngelDrive.onSearchResult(false,0,0,'','Espera un segundo antes de volver a buscar.');"); return; }
        lastGeocodeRequestAt = now;
        networkExecutor.execute(() -> {
            try {
                String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
                JSONArray items = new JSONArray(httpGet("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&addressdetails=0&q=" + encoded));
                if (items.length() == 0) { evaluate("window.AngelDrive.onSearchResult(false,0,0,'','No se encontró ese destino.');"); return; }
                JSONObject item = items.getJSONObject(0);
                evaluate(String.format(Locale.US, "window.AngelDrive.onSearchResult(true,%f,%f,%s,'');", item.getDouble("lat"), item.getDouble("lon"), JSONObject.quote(item.optString("display_name", query))));
            } catch (Exception e) { evaluate("window.AngelDrive.onSearchResult(false,0,0,'','Error de conexión al buscar el destino.');"); }
        });
    }

    private void routeToNetwork(double destLat, double destLon, String label) {
        Location origin = lastLocation;
        if (origin == null) { evaluate("window.AngelDrive.onRouteResult(false,'','Aún no hay una posición GPS válida.');"); return; }
        networkExecutor.execute(() -> {
            try {
                String url = String.format(Locale.US, "https://router.project-osrm.org/route/v1/driving/%f,%f;%f,%f?overview=full&geometries=geojson&steps=true&alternatives=false", origin.getLongitude(), origin.getLatitude(), destLon, destLat);
                JSONObject root = new JSONObject(httpGet(url));
                if (!"Ok".equals(root.optString("code")) || root.getJSONArray("routes").length() == 0) { evaluate("window.AngelDrive.onRouteResult(false,'','No se encontró una ruta por carretera.');"); return; }
                JSONObject route = root.getJSONArray("routes").getJSONObject(0), output = new JSONObject();
                output.put("distance", route.optDouble("distance", 0)); output.put("duration", route.optDouble("duration", 0)); output.put("geometry", route.getJSONObject("geometry"));
                JSONArray outputSteps = new JSONArray(), legs = route.optJSONArray("legs");
                if (legs != null) for (int l = 0; l < legs.length(); l++) { JSONArray steps = legs.getJSONObject(l).optJSONArray("steps"); if (steps == null) continue; for (int i = 0; i < steps.length(); i++) { JSONObject step = steps.getJSONObject(i), maneuver = step.optJSONObject("maneuver"); JSONArray location = maneuver != null ? maneuver.optJSONArray("location") : null; if (location == null || location.length() < 2) continue; JSONObject compact = new JSONObject(); compact.put("lon", location.getDouble(0)); compact.put("lat", location.getDouble(1)); compact.put("type", maneuver.optString("type", "turn")); compact.put("modifier", maneuver.optString("modifier", "straight")); compact.put("name", step.optString("name", "")); compact.put("distance", step.optDouble("distance", 0)); compact.put("duration", step.optDouble("duration", 0)); outputSteps.put(compact); } }
                output.put("steps", outputSteps); output.put("label", label);
                evaluate("window.AngelDrive.onRouteResult(true," + JSONObject.quote(output.toString()) + ",'');");
            } catch (JSONException e) { evaluate("window.AngelDrive.onRouteResult(false,'','La respuesta de rutas no era válida.');"); }
            catch (Exception e) { evaluate("window.AngelDrive.onRouteResult(false,'','Error de conexión al calcular la ruta.');"); }
        });
    }

    public class JsBridge {
        @JavascriptInterface public void open(String target) { runOnUiThread(() -> { switch (target) { case "waze" -> openWaze(); case "spotify" -> launchPackageOrStore(SPOTIFY_PACKAGE); case "maps" -> launchPackageOrStore("com.google.android.apps.maps"); case "android-settings" -> startActivity(new Intent(Settings.ACTION_SETTINGS)); case "assistant" -> openAssistant(); case "home-settings" -> chooseHomeApp(); case "notification-access" -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); case "location-settings" -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); case "exit" -> finish(); default -> Toast.makeText(MainActivity.this, "Acción no disponible", Toast.LENGTH_SHORT).show(); } }); }
        @JavascriptInterface public void media(String action) { runOnUiThread(() -> performMediaAction(action)); }
        @JavascriptInterface public void requestLocation() { runOnUiThread(MainActivity.this::requestLocationIfNeeded); }
        @JavascriptInterface public void searchDestination(String query) { searchDestinationNetwork(query == null ? "" : query.trim()); }
        @JavascriptInterface public void routeTo(double lat, double lon, String label) { routeToNetwork(lat, lon, label == null ? "Destino" : label); }
        @JavascriptInterface public void navigate(String app, double lat, double lon, String label) { runOnUiThread(() -> MainActivity.this.navigate(app, lat, lon, label)); }
    }
}
