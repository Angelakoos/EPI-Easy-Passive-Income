package com.angeldrive.aibox;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.maplibre.android.MapLibre;
import org.maplibre.android.annotations.Marker;
import org.maplibre.android.annotations.MarkerOptions;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends Activity implements LocationListener {
    private static final int LOCATION_REQUEST = 41;
    private static final String SPOTIFY_PACKAGE = "com.spotify.music";
    private static final AtomicReference<MainActivity> INSTANCE = new AtomicReference<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int bronze = Color.rgb(199, 139, 100);
    private final int panel = Color.argb(232, 10, 13, 18);

    private MapView mapView;
    private MapLibreMap map;
    private Marker userMarker;
    private LocationManager locationManager;
    private Location lastLocation;
    private boolean followPosition = true;

    private MediaSessionManager mediaSessionManager;
    private MediaController spotifyController;
    private boolean sessionsListenerRegistered;

    private ImageView albumImage;
    private TextView trackText;
    private TextView artistText;
    private TextView mediaStatusText;
    private TextView playButton;
    private TextView speedText;
    private TextView clockText;

    private final Runnable clockTicker = new Runnable() {
        @Override public void run() {
            if (clockText != null) {
                clockText.setText(new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date()));
            }
            updateMediaUi();
            handler.postDelayed(this, 1000L);
        }
    };

    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) { updateMediaUi(); }
        @Override public void onPlaybackStateChanged(PlaybackState state) { updateMediaUi(); }
        @Override public void onSessionDestroyed() { handler.postDelayed(MainActivity.this::connectSpotifySession, 300L); }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChangedListener = controllers -> connectSpotifySession();

    public static void notifyMediaAccessChanged() {
        MainActivity activity = INSTANCE.get();
        if (activity != null) activity.handler.post(activity::connectSpotifySession);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        INSTANCE.set(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enterImmersiveMode();

        try {
            MapLibre.getInstance(this);
            createUi(savedInstanceState);
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            mediaSessionManager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            requestLocationIfNeeded();
        } catch (Throwable error) {
            showSafeFallback(error);
        }
    }

    private void createUi(Bundle savedInstanceState) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(5, 7, 10));
        setContentView(root);

        mapView = new MapView(this);
        mapView.onCreate(savedInstanceState);
        root.addView(mapView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        mapView.getMapAsync(mapLibreMap -> {
            map = mapLibreMap;
            map.getUiSettings().setCompassEnabled(false);
            map.getUiSettings().setLogoEnabled(false);
            map.getUiSettings().setAttributionEnabled(false);
            map.setStyle(new Style.Builder().fromUri("https://tiles.openfreemap.org/styles/dark"), style -> {
                if (lastLocation != null) updateMapLocation(lastLocation, true);
            });
            map.addOnMoveListener(new MapLibreMap.OnMoveListener() {
                @Override public void onMoveBegin(org.maplibre.android.gestures.MoveGestureDetector detector) { followPosition = false; }
                @Override public void onMove(org.maplibre.android.gestures.MoveGestureDetector detector) { }
                @Override public void onMoveEnd(org.maplibre.android.gestures.MoveGestureDetector detector) { }
            });
        });

        root.addView(buildNavigationCard(), position(dp(28), dp(28), dp(380), dp(142), Gravity.TOP | Gravity.START));
        root.addView(buildSpotifyCard(), position(dp(28), dp(28), dp(430), dp(178), Gravity.TOP | Gravity.END));
        root.addView(buildBottomDock(), position(0, dp(22), dp(480), dp(84), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        root.addView(buildSpeedPill(), position(0, dp(122), dp(190), dp(48), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        root.addView(buildStatusPill(), position(dp(28), dp(30), dp(180), dp(52), Gravity.BOTTOM | Gravity.END));
    }

    private View buildNavigationCard() {
        LinearLayout card = verticalPanel(22);
        card.setPadding(dp(22), dp(18), dp(22), dp(16));
        card.setOnClickListener(v -> openGoogleMaps());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView arrow = label("↱", 42, Color.WHITE, Typeface.BOLD);
        TextView text = label("Abrir navegación\nGoogle Maps o Waze", 20, Color.WHITE, Typeface.BOLD);
        text.setPadding(dp(18), 0, 0, 0);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        card.addView(row);
        TextView subtitle = label("El mapa muestra tu GPS real. Pulsa para elegir navegador.", 13, Color.rgb(195, 200, 208), Typeface.NORMAL);
        subtitle.setPadding(0, dp(12), 0, 0);
        card.addView(subtitle);
        return card;
    }

    private View buildSpotifyCard() {
        LinearLayout card = verticalPanel(22);
        card.setPadding(dp(18), dp(14), dp(18), dp(12));
        card.setOnClickListener(v -> {
            if (!hasNotificationAccess()) startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            else launchPackageOrStore(SPOTIFY_PACKAGE);
        });

        TextView title = label("●  Spotify", 14, Color.WHITE, Typeface.BOLD);
        title.setTextColor(Color.rgb(30, 215, 96));
        card.addView(title);

        LinearLayout mediaRow = new LinearLayout(this);
        mediaRow.setOrientation(LinearLayout.HORIZONTAL);
        mediaRow.setGravity(Gravity.CENTER_VERTICAL);
        mediaRow.setPadding(0, dp(10), 0, dp(7));

        albumImage = new ImageView(this);
        albumImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        albumImage.setImageDrawable(roundRect(Color.rgb(30, 215, 96), 12, 0, 0));
        mediaRow.addView(albumImage, new LinearLayout.LayoutParams(dp(66), dp(66)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(14), 0, 0, 0);
        trackText = label("Activa el acceso multimedia", 18, Color.WHITE, Typeface.BOLD);
        trackText.setSingleLine(true);
        artistText = label("Pulsa la tarjeta para configurarlo", 14, Color.rgb(190, 196, 204), Typeface.NORMAL);
        artistText.setSingleLine(true);
        titles.addView(trackText);
        titles.addView(artistText);
        mediaRow.addView(titles, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        card.addView(mediaRow);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        TextView previous = mediaButton("◀");
        playButton = mediaButton("▶");
        TextView next = mediaButton("▶");
        previous.setOnClickListener(v -> performMediaAction("previous"));
        playButton.setOnClickListener(v -> performMediaAction("toggle"));
        next.setOnClickListener(v -> performMediaAction("next"));
        controls.addView(previous, new LinearLayout.LayoutParams(dp(86), dp(44)));
        controls.addView(playButton, new LinearLayout.LayoutParams(dp(86), dp(44)));
        controls.addView(next, new LinearLayout.LayoutParams(dp(86), dp(44)));
        card.addView(controls);

        mediaStatusText = label("", 11, Color.rgb(165, 171, 180), Typeface.NORMAL);
        mediaStatusText.setGravity(Gravity.CENTER);
        card.addView(mediaStatusText);
        return card;
    }

    private View buildBottomDock() {
        LinearLayout dock = horizontalPanel(28);
        dock.setGravity(Gravity.CENTER);
        dock.setPadding(dp(10), dp(8), dp(10), dp(8));
        dock.addView(dockButton("▦", v -> openAppChooser()));
        dock.addView(dockButton("◉", v -> openAssistant()));
        dock.addView(dockButton("⌖", v -> recenterMap()));
        dock.addView(dockButton("≋", v -> launchPackageOrStore(SPOTIFY_PACKAGE)));
        dock.addView(dockButton("⚙", v -> openSettingsMenu()));
        return dock;
    }

    private View buildSpeedPill() {
        speedText = label("GPS · esperando ubicación", 15, Color.WHITE, Typeface.BOLD);
        speedText.setGravity(Gravity.CENTER);
        speedText.setBackground(roundRect(Color.argb(220, 5, 8, 12), 18, Color.argb(90, 80, 150, 255), 1));
        speedText.setOnClickListener(v -> recenterMap());
        return speedText;
    }

    private View buildStatusPill() {
        LinearLayout pill = horizontalPanel(26);
        pill.setGravity(Gravity.CENTER);
        TextView signal = label("▂▄▆█   4G", 14, Color.WHITE, Typeface.NORMAL);
        clockText = label("--:--", 18, Color.WHITE, Typeface.BOLD);
        clockText.setPadding(dp(18), 0, 0, 0);
        pill.addView(signal);
        pill.addView(clockText);
        return pill;
    }

    private void showSafeFallback(Throwable error) {
        LinearLayout fallback = new LinearLayout(this);
        fallback.setOrientation(LinearLayout.VERTICAL);
        fallback.setGravity(Gravity.CENTER);
        fallback.setPadding(dp(34), dp(34), dp(34), dp(34));
        fallback.setBackgroundColor(Color.rgb(5, 7, 10));
        TextView title = label("Ángel Drive", 32, Color.WHITE, Typeface.BOLD);
        TextView message = label("El mapa no pudo iniciarse, pero la aplicación sigue abierta.\n\nActualiza Android System WebView y reinicia la aplicación.", 18, Color.rgb(205, 210, 218), Typeface.NORMAL);
        message.setGravity(Gravity.CENTER);
        TextView maps = actionButton("Abrir Google Maps", this::openGoogleMaps);
        TextView spotify = actionButton("Abrir Spotify", () -> launchPackageOrStore(SPOTIFY_PACKAGE));
        fallback.addView(title);
        fallback.addView(message, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150)));
        fallback.addView(maps, new LinearLayout.LayoutParams(dp(280), dp(58)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(280), dp(58)); p.topMargin = dp(14);
        fallback.addView(spotify, p);
        setContentView(fallback);
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
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, this);
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 5f, this);
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null) last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (last != null) onLocationChanged(last);
        } catch (Exception ignored) { }
    }

    private void stopLocationUpdates() {
        try { if (locationManager != null) locationManager.removeUpdates(this); } catch (Exception ignored) { }
    }

    @Override public void onLocationChanged(Location location) {
        if (location == null) return;
        lastLocation = location;
        double speed = location.hasSpeed() ? location.getSpeed() * 3.6d : 0d;
        if (speedText != null) speedText.setText(String.format(Locale.getDefault(), "GPS · %.0f km/h", speed));
        updateMapLocation(location, false);
    }

    private void updateMapLocation(Location location, boolean force) {
        if (map == null) return;
        LatLng point = new LatLng(location.getLatitude(), location.getLongitude());
        if (userMarker == null) userMarker = map.addMarker(new MarkerOptions().position(point).title("Tu posición"));
        else userMarker.setPosition(point);
        if (followPosition || force) {
            float bearing = location.hasBearing() ? location.getBearing() : 0f;
            CameraPosition camera = new CameraPosition.Builder().target(point).zoom(16.5).tilt(52).bearing(bearing).build();
            map.animateCamera(CameraUpdateFactory.newCameraPosition(camera), 700);
        }
    }

    private void recenterMap() {
        followPosition = true;
        if (lastLocation != null) updateMapLocation(lastLocation, true);
        else Toast.makeText(this, "Esperando una posición GPS", Toast.LENGTH_SHORT).show();
    }

    private ComponentName listenerComponent() { return new ComponentName(this, MediaListenerService.class); }

    private boolean hasNotificationAccess() {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            return manager != null && manager.isNotificationListenerAccessGranted(listenerComponent());
        } catch (Throwable ignored) {
            String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            return enabled != null && enabled.contains(getPackageName());
        }
    }

    private void connectSpotifySession() {
        if (mediaSessionManager == null) return;
        if (!hasNotificationAccess()) {
            detachSpotifyController();
            updateMediaUi();
            return;
        }
        registerSessionsListener();
        try {
            MediaController selected = null;
            List<MediaController> sessions = mediaSessionManager.getActiveSessions(listenerComponent());
            for (MediaController controller : sessions) {
                if (SPOTIFY_PACKAGE.equals(controller.getPackageName())) { selected = controller; break; }
            }
            if (selected != spotifyController) {
                detachSpotifyController();
                spotifyController = selected;
                if (spotifyController != null) spotifyController.registerCallback(mediaCallback, handler);
            }
        } catch (Throwable ignored) {
            detachSpotifyController();
        }
        updateMediaUi();
    }

    private void registerSessionsListener() {
        if (mediaSessionManager == null || sessionsListenerRegistered || !hasNotificationAccess()) return;
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, listenerComponent(), handler);
            sessionsListenerRegistered = true;
        } catch (Throwable ignored) { }
    }

    private void unregisterSessionsListener() {
        if (mediaSessionManager != null && sessionsListenerRegistered) {
            try { mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener); } catch (Throwable ignored) { }
        }
        sessionsListenerRegistered = false;
    }

    private void detachSpotifyController() {
        if (spotifyController != null) {
            try { spotifyController.unregisterCallback(mediaCallback); } catch (Throwable ignored) { }
        }
        spotifyController = null;
    }

    private void updateMediaUi() {
        if (trackText == null || artistText == null || playButton == null) return;
        if (!hasNotificationAccess()) {
            trackText.setText("Activa el acceso multimedia");
            artistText.setText("Pulsa la tarjeta para configurarlo");
            mediaStatusText.setText("Spotify real, sin datos inventados");
            playButton.setText("▶");
            albumImage.setImageDrawable(roundRect(Color.rgb(30, 215, 96), 12, 0, 0));
            return;
        }
        if (spotifyController == null) {
            trackText.setText("Spotify no está reproduciendo");
            artistText.setText("Abre Spotify e inicia una canción");
            mediaStatusText.setText("Esperando sesión multimedia");
            playButton.setText("▶");
            return;
        }

        MediaMetadata metadata = spotifyController.getMetadata();
        PlaybackState state = spotifyController.getPlaybackState();
        String title = "Spotify";
        String artist = "";
        Bitmap art = null;
        if (metadata != null) {
            CharSequence t = metadata.getText(MediaMetadata.METADATA_KEY_TITLE);
            CharSequence a = metadata.getText(MediaMetadata.METADATA_KEY_ARTIST);
            if (t != null && t.length() > 0) title = t.toString();
            if (a != null) artist = a.toString();
            art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        }
        trackText.setText(title);
        artistText.setText(artist);
        if (art != null) albumImage.setImageBitmap(art);
        boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        playButton.setText(playing ? "Ⅱ" : "▶");
        mediaStatusText.setText(playing ? "Reproduciendo" : "Pausado");
    }

    private void performMediaAction(String action) {
        try {
            if (spotifyController != null) {
                MediaController.TransportControls controls = spotifyController.getTransportControls();
                if ("previous".equals(action)) controls.skipToPrevious();
                else if ("next".equals(action)) controls.skipToNext();
                else {
                    PlaybackState state = spotifyController.getPlaybackState();
                    if (state != null && state.getState() == PlaybackState.STATE_PLAYING) controls.pause(); else controls.play();
                }
                return;
            }
            int code = "previous".equals(action) ? KeyEvent.KEYCODE_MEDIA_PREVIOUS : "next".equals(action) ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (audio != null) {
                audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
            }
        } catch (Throwable ignored) { }
    }

    private void openGoogleMaps() {
        Intent chooser = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="));
        try { startActivity(Intent.createChooser(chooser, "Abrir navegación")); }
        catch (Exception e) { launchPackageOrStore("com.google.android.apps.maps"); }
    }

    private void openAppChooser() {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_APP_MARKET);
        try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        catch (Exception ignored) { }
    }

    private void openSettingsMenu() {
        try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
        catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }

    private void openAssistant() {
        try { startActivity(new Intent(Intent.ACTION_VOICE_COMMAND)); }
        catch (Exception e) { Toast.makeText(this, "No se encontró un asistente", Toast.LENGTH_SHORT).show(); }
    }

    private void launchPackageOrStore(String packageName) {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
            if (launch != null) { startActivity(launch); return; }
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + packageName)));
        } catch (Exception e) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + packageName))); }
            catch (Exception ignored) { }
        }
    }

    private LinearLayout verticalPanel(int radius) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackground(roundRect(panel, radius, Color.argb(75, 231, 181, 143), 1));
        return layout;
    }

    private LinearLayout horizontalPanel(int radius) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setBackground(roundRect(panel, radius, Color.argb(75, 231, 181, 143), 1));
        return layout;
    }

    private TextView label(String text, int sp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        return view;
    }

    private TextView mediaButton(String text) {
        TextView button = label(text, 22, Color.WHITE, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        return button;
    }

    private TextView dockButton(String text, View.OnClickListener listener) {
        TextView button = label(text, 27, Color.WHITE, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setBackground(roundRect(Color.argb(80, 255, 255, 255), 20, 0, 0));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(76), dp(64));
        params.setMargins(dp(7), 0, dp(7), 0);
        button.setLayoutParams(params);
        return button;
    }

    private TextView actionButton(String text, Runnable action) {
        TextView button = label(text, 17, Color.rgb(15, 15, 15), Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setBackground(roundRect(bronze, 16, 0, 0));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private GradientDrawable roundRect(int fill, int radiusDp, int stroke, int strokeWidthDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeWidthDp > 0) drawable.setStroke(dp(strokeWidthDp), stroke);
        return drawable;
    }

    private FrameLayout.LayoutParams position(int horizontalMargin, int verticalMargin, int width, int height, int gravity) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height, gravity);
        params.setMargins(horizontalMargin, verticalMargin, horizontalMargin, verticalMargin);
        return params;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override protected void onResume() {
        super.onResume();
        INSTANCE.set(this);
        enterImmersiveMode();
        if (mapView != null) mapView.onResume();
        startLocationUpdates();
        connectSpotifySession();
        handler.removeCallbacks(clockTicker);
        handler.post(clockTicker);
    }

    @Override protected void onPause() {
        if (mapView != null) mapView.onPause();
        stopLocationUpdates();
        unregisterSessionsListener();
        handler.removeCallbacks(clockTicker);
        super.onPause();
    }

    @Override protected void onStart() { super.onStart(); if (mapView != null) mapView.onStart(); }
    @Override protected void onStop() { if (mapView != null) mapView.onStop(); super.onStop(); }
    @Override public void onLowMemory() { super.onLowMemory(); if (mapView != null) mapView.onLowMemory(); }
    @Override protected void onSaveInstanceState(Bundle outState) { super.onSaveInstanceState(outState); if (mapView != null) mapView.onSaveInstanceState(outState); }

    @Override protected void onDestroy() {
        unregisterSessionsListener();
        detachSpotifyController();
        handler.removeCallbacksAndMessages(null);
        if (mapView != null) mapView.onDestroy();
        INSTANCE.compareAndSet(this, null);
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_REQUEST) startLocationUpdates();
    }
}
