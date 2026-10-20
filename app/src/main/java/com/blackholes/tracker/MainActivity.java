package com.blackholes.tracker;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.hardware.GeomagneticField;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Surface;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@SuppressWarnings("deprecation")
public class MainActivity extends Activity implements LocationListener, SkyView.Listener {

    public static class BlackHole {
        public String name;
        public double raDeg;
        public double decDeg;
        public String distanceStr;
        public double distanceLy;
        public String kind;
        public String mass;
        public BlackHole(String name, double raDeg, double decDeg, String distanceStr, double distanceLy,
                         String kind, String mass) {
            this.name = name;
            this.raDeg = raDeg;
            this.decDeg = decDeg;
            this.distanceStr = distanceStr;
            this.distanceLy = distanceLy;
            this.kind = kind;
            this.mass = mass;
        }
        @Override
        public String toString() { return name; }
    }

    private static final String SUPERMASSIVE = "Supermassive";
    private static final String STELLAR = "Stellar-mass";

    private final List<BlackHole> blackHoles = Arrays.asList(
        new BlackHole("Sagittarius A*", 266.41684, -29.00781, "~26,670 ly", 26_670, SUPERMASSIVE, "4.3 million solar masses"),
        new BlackHole("Cygnus X-1", 299.5903, 35.2016, "~7,200 ly", 7_200, STELLAR, "21 solar masses"),
        new BlackHole("V616 Monocerotis (A0620-00)", 95.6854, -0.3457, "~3,300 ly", 3_300, STELLAR, "6.6 solar masses"),
        new BlackHole("GRO J1655-40", 253.5005, -39.8458, "~11,000 ly", 11_000, STELLAR, "~6 solar masses"),
        new BlackHole("M87*", 187.7059, 12.3911, "~53.5 Mly", 53_500_000, SUPERMASSIVE, "6.5 billion solar masses"),
        new BlackHole("TON 618", 187.10375, 31.47714, "~18.2 Gly", 18_200_000_000.0, SUPERMASSIVE, "~40 billion solar masses"),
        new BlackHole("Gaia BH1", 262.17124, -0.58098, "~1,560 ly", 1_560, STELLAR, "9.6 solar masses"),
        new BlackHole("Gaia BH3", 294.82796, 14.93167, "~1,936 ly", 1_936, STELLAR, "33 solar masses"),
        new BlackHole("Gaia BH2", 207.56978, -59.23898, "~3,800 ly", 3_800, STELLAR, "8.9 solar masses"),
        new BlackHole("GRS 1124-683 (Nova Muscae 1991)", 171.61081, -68.67580, "~16,100 ly", 16_100, STELLAR, "~11 solar masses"),
        new BlackHole("Phoenix A*", 356.17042, -42.69833, "~8.6 Gly", 8_600_000_000.0, SUPERMASSIVE, "~100 billion solar masses (est.)")
    );
    private int selectedTargetIndex = 0;

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final long UPDATE_INTERVAL_MS = 1000L;
    /** The sky barely shifts over a few km, so once a recent fix is this good, stop listening. */
    private static final float GOOD_ENOUGH_ACCURACY_M = 5_000f;
    private static final long FRESH_FIX_AGE_MS = 10 * 60 * 1000L;

    private static final String PREFS_NAME = "tracker";
    private static final String KEY_LAT = "lat";
    private static final String KEY_LON = "lon";
    private static final String KEY_TARGET = "target";
    private static final String KEY_SHOW_ALL = "showAll";
    private static final String STATE_PERMISSIONS_REQUESTED = "permissionsRequested";

    private static final int COLOR_OK = 0xFF66FF99;
    private static final int COLOR_WARN = 0xFFFFCC66;
    private static final int COLOR_INFO = 0xFF8FC8FF;

    private TextView tvStatus;
    private TextView tvHint;
    private TextView tvDetails;
    private TextView tvAltitude;
    private TextView tvAzimuth;
    private TextView tvDistance;
    private TextView tvVisibility;
    private TextView tvRiseSet;
    private TextView tvLocation;
    private Spinner spinnerBlackHole;
    private Switch switchShowAll;
    private SkyView skyView;
    private CameraPreview cameraPreview;

    private SharedPreferences prefs;
    private LocationManager locationManager;
    private DateFormat timeFormat;
    private Camera camera;
    private boolean cameraFailed = false;
    private boolean permissionsRequested = false;
    private boolean locationServicesOff = false;
    private double lastLat;
    private double lastLon;
    private boolean hasLocation = false;
    private boolean locationIsSaved = false; // a fix remembered from a previous session
    private float locationAccuracyM = -1f;
    private float declinationDeg = 0f;
    private Runnable statusAction; // what tapping the status line does, if anything

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable updateTask = new Runnable() {
        @Override
        public void run() {
            refreshPosition();
            handler.postDelayed(this, UPDATE_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        tvStatus = findViewById(R.id.tvStatus);
        tvHint = findViewById(R.id.tvHint);
        tvDetails = findViewById(R.id.tvDetails);
        tvAltitude = findViewById(R.id.tvAltitude);
        tvAzimuth = findViewById(R.id.tvAzimuth);
        tvDistance = findViewById(R.id.tvDistance);
        tvVisibility = findViewById(R.id.tvVisibility);
        tvRiseSet = findViewById(R.id.tvRiseSet);
        tvLocation = findViewById(R.id.tvLocation);
        spinnerBlackHole = findViewById(R.id.spinnerBlackHole);
        switchShowAll = findViewById(R.id.switchShowAll);
        skyView = findViewById(R.id.skyView);
        cameraPreview = findViewById(R.id.cameraPreview);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        locationManager = getSystemService(LocationManager.class);
        timeFormat = android.text.format.DateFormat.getTimeFormat(this);
        if (savedInstanceState != null) {
            permissionsRequested = savedInstanceState.getBoolean(STATE_PERMISSIONS_REQUESTED);
        }

        Collections.sort(blackHoles, (a, b) -> Double.compare(a.distanceLy, b.distanceLy));

        ArrayAdapter<BlackHole> adapter = new ArrayAdapter<>(this, R.layout.spinner_item, blackHoles);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerBlackHole.setAdapter(adapter);
        int savedTarget = indexOfTarget(prefs.getString(KEY_TARGET, null));
        if (savedTarget >= 0) {
            selectedTargetIndex = savedTarget;
            spinnerBlackHole.setSelection(savedTarget, false);
        }
        spinnerBlackHole.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedTargetIndex = position;
                prefs.edit().putString(KEY_TARGET, blackHoles.get(position).name).apply();
                showTargetDetails();
                refreshPosition();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        switchShowAll.setChecked(prefs.getBoolean(KEY_SHOW_ALL, false));
        switchShowAll.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_SHOW_ALL, isChecked).apply();
            refreshPosition();
        });

        tvStatus.setOnClickListener(v -> {
            if (statusAction != null) {
                statusAction.run();
            }
        });

        // Keep the off-screen arrows clear of the panels.
        View topPanel = findViewById(R.id.topPanel);
        View bottomPanel = findViewById(R.id.bottomPanel);
        View.OnLayoutChangeListener insetUpdater = (v, l, t, r, b, ol, ot, or, ob) ->
            skyView.setOverlayInsets(topPanel.getBottom(), skyView.getHeight() - bottomPanel.getTop());
        topPanel.addOnLayoutChangeListener(insetUpdater);
        bottomPanel.addOnLayoutChangeListener(insetUpdater);

        skyView.setListener(this);
        restoreSavedLocation();
        showTargetDetails();
        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        requestMissingPermissionsOnce();
        startLocationUpdates();
        startCamera();
        handler.post(updateTask);
        skyView.start();
        updateStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(updateTask);
        skyView.stop();
        stopCamera();
        try {
            locationManager.removeUpdates(this);
        } catch (SecurityException ignored) {
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_PERMISSIONS_REQUESTED, permissionsRequested);
    }

    private int indexOfTarget(String name) {
        for (int i = 0; i < blackHoles.size(); i++) {
            if (blackHoles.get(i).name.equals(name)) {
                return i;
            }
        }
        return -1;
    }

    // ---- Permissions ----

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private List<String> missingPermissions() {
        List<String> missing = new ArrayList<>();
        if (!hasCameraPermission()) {
            missing.add(Manifest.permission.CAMERA);
        }
        if (!hasLocationPermission()) {
            // Approximate location is plenty; on Android 12+ the user can pick either.
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        return missing;
    }

    /**
     * Asks for everything in one dialog, once per launch. Asking from
     * onResume every time made a denial re-prompt in a loop, and two
     * separate requests in a row made the second one fail instantly.
     */
    private void requestMissingPermissionsOnce() {
        if (permissionsRequested) {
            return;
        }
        List<String> missing = missingPermissions();
        if (!missing.isEmpty()) {
            permissionsRequested = true;
            requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    /** Re-asks if Android still allows it, otherwise opens the app's settings page. */
    private void askForPermissionsAgain() {
        List<String> missing = missingPermissions();
        if (missing.isEmpty()) {
            return;
        }
        for (String permission : missing) {
            if (shouldShowRequestPermissionRationale(permission)) {
                requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST_CODE);
                return;
            }
        }
        openSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", getPackageName(), null)));
    }

    private void openSettings(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // onResume runs next and starts whatever is now allowed.
        updateStatus();
    }

    // ---- Location ----

    private void restoreSavedLocation() {
        if (!prefs.contains(KEY_LAT) || !prefs.contains(KEY_LON)) {
            return;
        }
        lastLat = Double.longBitsToDouble(prefs.getLong(KEY_LAT, 0));
        lastLon = Double.longBitsToDouble(prefs.getLong(KEY_LON, 0));
        hasLocation = true;
        locationIsSaved = true;
        updateDeclination(0);
        updateLocationText();
    }

    private void startLocationUpdates() {
        if (!hasLocationPermission()) {
            return;
        }
        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        Location newest = null;
        for (String provider : locationManager.getProviders(true)) {
            try {
                Location candidate = locationManager.getLastKnownLocation(provider);
                if (candidate != null && (newest == null || candidate.getTime() > newest.getTime())) {
                    newest = candidate;
                }
            } catch (SecurityException | IllegalArgumentException ignored) {
            }
        }

        boolean listening = requestUpdatesFrom(LocationManager.NETWORK_PROVIDER);
        // GPS needs fine location; with only approximate granted it throws.
        if (fine) {
            listening |= requestUpdatesFrom(LocationManager.GPS_PROVIDER);
        }
        locationServicesOff = !listening;

        if (newest != null) {
            onLocationChanged(newest);
        }
        updateStatus();
    }

    private boolean requestUpdatesFrom(String provider) {
        try {
            if (!locationManager.isProviderEnabled(provider)) {
                return false;
            }
            locationManager.requestLocationUpdates(provider, 30_000L, 500f, this);
            return true;
        } catch (SecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        lastLat = location.getLatitude();
        lastLon = location.getLongitude();
        hasLocation = true;
        locationIsSaved = false;
        locationAccuracyM = location.hasAccuracy() ? location.getAccuracy() : -1f;
        prefs.edit()
            .putLong(KEY_LAT, Double.doubleToRawLongBits(lastLat))
            .putLong(KEY_LON, Double.doubleToRawLongBits(lastLon))
            .apply();
        updateDeclination(location.hasAltitude() ? location.getAltitude() : 0);

        long ageMs = (SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos()) / 1_000_000L;
        if (location.hasAccuracy() && location.getAccuracy() <= GOOD_ENOUGH_ACCURACY_M && ageMs < FRESH_FIX_AGE_MS) {
            // Good enough for the sky; stop the GPS so it doesn't drain the battery.
            locationManager.removeUpdates(this);
        }

        updateLocationText();
        updateStatus();
        refreshPosition();
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
        startLocationUpdates();
    }

    @Override
    public void onProviderDisabled(String provider) {
        startLocationUpdates();
    }

    /** Compasses point at magnetic north; the sky is laid out around true north. */
    private void updateDeclination(double altitudeM) {
        GeomagneticField field = new GeomagneticField(
            (float) lastLat, (float) lastLon, (float) altitudeM, System.currentTimeMillis());
        declinationDeg = field.getDeclination();
        skyView.setDeclination(declinationDeg);
    }

    // ---- Camera ----

    private void startCamera() {
        if (camera != null || !hasCameraPermission()) {
            return;
        }
        int cameraId = findBackCamera();
        try {
            camera = Camera.open(cameraId);
        } catch (RuntimeException e) {
            camera = null;
        }
        cameraFailed = camera == null;
        if (camera == null) {
            updateStatus();
            return;
        }
        configureCamera(setCameraDisplayOrientation(cameraId, camera));
        cameraPreview.setCamera(camera);
        updateStatus();
    }

    private static int findBackCamera() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Picks a preview size shaped like the screen, focuses at infinity, and
     * tells the overlay exactly how much sky the (cropped) preview shows so
     * the markers line up with the image.
     */
    private void configureCamera(int displayOrientation) {
        Camera.Parameters params;
        try {
            params = camera.getParameters();
        } catch (RuntimeException e) {
            return;
        }
        // Camera sizes are in the sensor's own (landscape) orientation.
        boolean rotated = displayOrientation % 180 != 0;
        View root = (View) cameraPreview.getParent();
        int viewW = root.getWidth() > 0 ? root.getWidth() : getResources().getDisplayMetrics().widthPixels;
        int viewH = root.getHeight() > 0 ? root.getHeight() : getResources().getDisplayMetrics().heightPixels;
        double targetAspect = rotated ? (double) viewH / viewW : (double) viewW / viewH;

        Camera.Size best = choosePreviewSize(params.getSupportedPreviewSizes(), targetAspect);
        if (best != null) {
            params.setPreviewSize(best.width, best.height);
        }
        List<String> focusModes = params.getSupportedFocusModes();
        if (focusModes != null) {
            if (focusModes.contains(Camera.Parameters.FOCUS_MODE_INFINITY)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_INFINITY);
            } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            }
        }
        try {
            camera.setParameters(params);
        } catch (RuntimeException e) {
            // Some drivers reject combinations they advertise; keep their defaults.
            try {
                params = camera.getParameters();
            } catch (RuntimeException e2) {
                return;
            }
        }

        Camera.Size size = params.getPreviewSize();
        if (size == null || size.width <= 0 || size.height <= 0) {
            return;
        }
        float hAngle = params.getHorizontalViewAngle();
        float vAngle = params.getVerticalViewAngle();
        if (!(hAngle > 10f && hAngle < 170f && vAngle > 10f && vAngle < 170f)) {
            // Bogus values on some devices; assume a typical phone main camera.
            hAngle = 65f;
            vAngle = 51f;
        }
        double tanX = Math.tan(Math.toRadians(hAngle / 2));
        double tanY = Math.tan(Math.toRadians(vAngle / 2));
        // The angles describe the full sensor; a preview with a different
        // aspect ratio is a crop of it.
        double previewAspect = (double) size.width / size.height;
        if (previewAspect > tanX / tanY) {
            tanY = tanX / previewAspect;
        } else {
            tanX = tanY * previewAspect;
        }
        if (rotated) {
            cameraPreview.setContentSize(size.height, size.width);
            skyView.setCameraFov(tanY, tanX);
        } else {
            cameraPreview.setContentSize(size.width, size.height);
            skyView.setCameraFov(tanX, tanY);
        }
    }

    private static Camera.Size choosePreviewSize(List<Camera.Size> sizes, double targetAspect) {
        if (sizes == null) {
            return null;
        }
        Camera.Size best = null;
        double bestDiff = Double.MAX_VALUE;
        for (Camera.Size s : sizes) {
            // Sharp enough for a background, without wasting bandwidth.
            if (s.height < 480 || s.width > 2560 || s.height > 1440) {
                continue;
            }
            double diff = Math.abs((double) s.width / s.height - targetAspect);
            boolean closer = diff < bestDiff - 0.01;
            boolean asCloseButBigger = Math.abs(diff - bestDiff) <= 0.01
                && best != null && s.width * s.height > best.width * best.height;
            if (best == null || closer || asCloseButBigger) {
                best = s;
                bestDiff = diff;
            }
        }
        return best;
    }

    private void stopCamera() {
        if (camera != null) {
            try {
                camera.stopPreview();
            } catch (Exception ignored) {
            }
            camera.release();
            camera = null;
        }
        cameraPreview.setCamera(null);
    }

    private int setCameraDisplayOrientation(int cameraId, Camera camera) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0: degrees = 0; break;
            case Surface.ROTATION_90: degrees = 90; break;
            case Surface.ROTATION_180: degrees = 180; break;
            case Surface.ROTATION_270: degrees = 270; break;
        }
        int result;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;
        } else {
            result = (info.orientation - degrees + 360) % 360;
        }
        camera.setDisplayOrientation(result);
        return result;
    }

    // ---- SkyView callbacks ----

    @Override
    public void onTargetTapped(String name) {
        int index = indexOfTarget(name);
        if (index >= 0) {
            spinnerBlackHole.setSelection(index);
        }
    }

    @Override
    public void onViewStateChanged() {
        updateStatus();
    }

    // ---- UI ----

    /**
     * Computes the current altitude/azimuth of the black holes for the last
     * known observer location (with precession and refraction, see
     * Astronomy) and updates the overlay and the info panel.
     */
    private void refreshPosition() {
        if (!hasLocation) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean showAll = switchShowAll.isChecked();
        List<SkyView.Target> targets = new ArrayList<>();
        for (int i = 0; i < blackHoles.size(); i++) {
            boolean isSelected = i == selectedTargetIndex;
            if (!isSelected && !showAll) {
                continue;
            }
            BlackHole bh = blackHoles.get(i);
            double[] horizontal = Astronomy.apparentHorizontal(bh.raDeg, bh.decDeg, lastLat, lastLon, now);
            if (isSelected) {
                updateTargetInfo(bh, horizontal[0], horizontal[1], now);
            }
            targets.add(new SkyView.Target(bh.name, horizontal[0], horizontal[1], isSelected));
        }
        skyView.setTargets(targets);
    }

    private void showTargetDetails() {
        BlackHole bh = blackHoles.get(selectedTargetIndex);
        tvDetails.setText(getString(R.string.details_format, bh.kind, bh.mass));
        tvDistance.setText(bh.distanceStr);
        if (!hasLocation) {
            tvAltitude.setText(R.string.placeholder);
            tvAzimuth.setText(R.string.placeholder);
            tvVisibility.setText(R.string.placeholder);
            tvRiseSet.setText(R.string.rise_set_needs_location);
        }
    }

    private void updateTargetInfo(BlackHole bh, double altDeg, double azDeg, long now) {
        tvAltitude.setText(String.format(Locale.getDefault(), "%+.1f°", altDeg));
        tvAzimuth.setText(String.format(Locale.getDefault(), "%.1f° %s", azDeg, compassDirection(azDeg)));
        boolean up = altDeg > 0;
        tvVisibility.setText(up ? R.string.above_horizon : R.string.below_horizon);
        tvVisibility.setTextColor(up ? COLOR_OK : 0xFFFF6666);

        Astronomy.RiseSet riseSet = Astronomy.riseSet(bh.raDeg, bh.decDeg, lastLat, lastLon, now);
        double peakDeg = Astronomy.culminationAltitudeDeg(bh.decDeg, lastLat);
        if (riseSet.kind == Astronomy.RiseSet.ALWAYS_UP) {
            tvRiseSet.setText(getString(R.string.never_sets, peakDeg));
        } else if (riseSet.kind == Astronomy.RiseSet.NEVER_UP) {
            tvRiseSet.setText(R.string.never_rises);
        } else {
            boolean setsFirst = riseSet.nextSetMillis < riseSet.nextRiseMillis;
            long when = setsFirst ? riseSet.nextSetMillis : riseSet.nextRiseMillis;
            tvRiseSet.setText(getString(setsFirst ? R.string.sets_in : R.string.rises_in,
                formatDuration(when - now), timeFormat.format(new Date(when)), peakDeg));
        }
    }

    private String formatDuration(long ms) {
        long minutes = ms / 60_000L;
        if (minutes < 1) {
            return getString(R.string.duration_under_a_minute);
        }
        if (minutes < 60) {
            return getString(R.string.duration_minutes, minutes);
        }
        return getString(R.string.duration_hours_minutes, minutes / 60, minutes % 60);
    }

    private void updateLocationText() {
        if (!hasLocation) {
            tvLocation.setText(R.string.placeholder);
            return;
        }
        StringBuilder text = new StringBuilder(
            String.format(Locale.getDefault(), "%.3f°, %.3f°", lastLat, lastLon));
        if (locationIsSaved) {
            text.append(" · ").append(getString(R.string.location_saved));
        } else if (locationAccuracyM > 0) {
            text.append(" · ").append(locationAccuracyM < 1000
                ? getString(R.string.accuracy_m, Math.round(locationAccuracyM))
                : getString(R.string.accuracy_km, locationAccuracyM / 1000f));
        }
        if (skyView.getSensorMode() != SkyView.SensorMode.NONE) {
            text.append(" · ").append(getString(R.string.declination_format, declinationDeg));
        }
        tvLocation.setText(text);
    }

    /** Status line (location/permissions/camera) plus a hint line about pointing accuracy. */
    private void updateStatus() {
        String status;
        int statusColor = COLOR_WARN;
        statusAction = null;
        if (!hasLocation && !hasLocationPermission()) {
            status = getString(R.string.status_location_permission);
            statusAction = this::askForPermissionsAgain;
        } else if (!hasLocation && locationServicesOff) {
            status = getString(R.string.status_location_off);
            statusAction = () -> openSettings(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        } else if (!hasLocation) {
            status = getString(R.string.status_acquiring);
        } else if (permissionsRequested && !hasCameraPermission()) {
            status = getString(R.string.status_camera_permission);
            statusAction = this::askForPermissionsAgain;
        } else if (cameraFailed) {
            status = getString(R.string.status_camera_unavailable);
        } else if (locationIsSaved) {
            status = getString(R.string.status_saved_location);
            statusColor = COLOR_INFO;
        } else {
            status = getString(R.string.status_tracking);
            statusColor = COLOR_OK;
        }
        tvStatus.setText(status);
        tvStatus.setTextColor(statusColor);
        tvStatus.setClickable(statusAction != null);

        String hint = null;
        int hintColor = COLOR_INFO;
        SkyView.SensorMode mode = skyView.getSensorMode();
        if (skyView.needsCompassCalibration()) {
            hint = getString(R.string.hint_calibrate);
            hintColor = COLOR_WARN;
        } else if (skyView.hasManualOffset()) {
            hint = getString(R.string.hint_offset, skyView.getManualAzOffset(), skyView.getManualPitchOffset());
        } else if (mode == SkyView.SensorMode.NONE) {
            hint = getString(R.string.hint_no_sensors);
        } else if (mode == SkyView.SensorMode.COMPASS_ONLY) {
            hint = getString(R.string.hint_no_gyro);
        }
        tvHint.setVisibility(hint == null ? View.GONE : View.VISIBLE);
        tvHint.setText(hint);
        tvHint.setTextColor(hintColor);
    }

    private String compassDirection(double azDeg) {
        String[] dirs = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
        int index = ((int) Math.round(azDeg / 22.5)) % 16;
        if (index < 0) index += 16;
        return dirs[index];
    }
}
