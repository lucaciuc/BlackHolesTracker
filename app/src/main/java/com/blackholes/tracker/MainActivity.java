package com.blackholes.tracker;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.widget.TextView;
import android.widget.Spinner;
import android.widget.ToggleButton;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

@SuppressWarnings("deprecation")
public class MainActivity extends Activity implements LocationListener {

    public static class BlackHole {
        public String name;
        public double raDeg;
        public double decDeg;
        public String distanceStr;
        public BlackHole(String name, double raDeg, double decDeg, String distanceStr) {
            this.name = name;
            this.raDeg = raDeg;
            this.decDeg = decDeg;
            this.distanceStr = distanceStr;
        }
        @Override
        public String toString() { return name; }
    }

    private final List<BlackHole> blackHoles = Arrays.asList(
        new BlackHole("Sagittarius A*", 266.41684, -29.00781, "~26,670 ly"),
        new BlackHole("Cygnus X-1", 299.5903, 35.2016, "~7,200 ly"),
        new BlackHole("V616 Monocerotis", 95.6854, -0.3457, "~3,300 ly"),
        new BlackHole("GRO J1655-40", 253.5005, -39.8458, "~11,000 ly"),
        new BlackHole("M87*", 187.7059, 12.3911, "~53.5 Mly"),
        new BlackHole("TON 618", 187.10375, 31.47714, "~18.2 Gly")
    );
    private int selectedTargetIndex = 0;

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 100;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 101;
    private static final long UPDATE_INTERVAL_MS = 1000L;

    private TextView tvStatus;
    private TextView tvAltitude;
    private TextView tvAzimuth;
    private TextView tvDirection;
    private TextView tvVisibility;
    private TextView tvLocation;
    private TextView tvUpdated;
    private TextView tvDistance;
    private Spinner spinnerBlackHole;
    private ToggleButton toggleShowAll;
    private SkyView skyView;
    private CameraPreview cameraPreview;

    private LocationManager locationManager;
    private Camera camera;
    private double lastLat;
    private double lastLon;
    private boolean hasLocation = false;

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
        tvAltitude = findViewById(R.id.tvAltitude);
        tvAzimuth = findViewById(R.id.tvAzimuth);
        tvDirection = findViewById(R.id.tvDirection);
        tvVisibility = findViewById(R.id.tvVisibility);
        tvLocation = findViewById(R.id.tvLocation);
        tvUpdated = findViewById(R.id.tvUpdated);
        tvDistance = findViewById(R.id.tvDistance);
        spinnerBlackHole = findViewById(R.id.spinnerBlackHole);
        toggleShowAll = findViewById(R.id.toggleShowAll);
        skyView = findViewById(R.id.skyView);
        cameraPreview = findViewById(R.id.cameraPreview);

        ArrayAdapter<BlackHole> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, blackHoles);
        spinnerBlackHole.setAdapter(adapter);
        spinnerBlackHole.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedTargetIndex = position;
                tvDistance.setText(blackHoles.get(position).distanceStr);
                refreshPosition();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        toggleShowAll.setOnCheckedChangeListener((buttonView, isChecked) -> {
            refreshPosition();
        });

        locationManager = getSystemService(LocationManager.class);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startLocationUpdates();
        startCamera();
        handler.post(updateTask);
        skyView.start();
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

    private void startLocationUpdates() {
        boolean hasFine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
			== PackageManager.PERMISSION_GRANTED;
        boolean hasCoarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
			== PackageManager.PERMISSION_GRANTED;

        if (!hasFine && !hasCoarse) {
            requestPermissions(new String[]{
								   Manifest.permission.ACCESS_FINE_LOCATION,
								   Manifest.permission.ACCESS_COARSE_LOCATION
							   }, LOCATION_PERMISSION_REQUEST_CODE);
            return;
        }

        try {
            Location seed = null;
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
					LocationManager.GPS_PROVIDER, 5000L, 10f, this);
                seed = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            }
            if (seed == null && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
					LocationManager.NETWORK_PROVIDER, 5000L, 10f, this);
                seed = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
            if (seed != null) {
                onLocationChanged(seed);
            } else {
                tvStatus.setText("Acquiring GPS fix...");
            }
        } catch (SecurityException e) {
            tvStatus.setText("Location permission denied");
        }
    }

    private void startCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
            return;
        }
        try {
            camera = Camera.open(0);
            setCameraDisplayOrientation(0, camera);

            try {
                Camera.Parameters params = camera.getParameters();
                // Preview is rotated 90 degrees for portrait, so the sensor's
                // *vertical* view angle becomes the on-screen *horizontal*
                // (left/right, azimuth) span. If left/right panning feels
                // mismatched to the real zoom level, try getHorizontalViewAngle()
                // here instead.
                float fov = params.getVerticalViewAngle();
                if (fov > 10f && fov < 170f) {
                    skyView.setFovDegrees(fov);
                }
            } catch (Exception ignored) {
            }

            cameraPreview.setCamera(camera);
        } catch (Exception e) {
            camera = null;
            tvStatus.setText("Camera unavailable");
        }
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

    private void setCameraDisplayOrientation(int cameraId, Camera camera) {
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
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (granted) {
                startLocationUpdates();
            } else {
                tvStatus.setText("Location permission required to track visibility");
            }
        } else if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (granted) {
                startCamera();
            } else {
                tvStatus.setText("Camera permission denied - AR view disabled");
            }
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        lastLat = location.getLatitude();
        lastLon = location.getLongitude();
        hasLocation = true;
        tvStatus.setText("Tracking");
        tvLocation.setText(String.format(Locale.US, "Location: %.3f°, %.3f°", lastLat, lastLon));
        refreshPosition();
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    public void onProviderEnabled(String provider) {
    }

    public void onProviderDisabled(String provider) {
        tvStatus.setText(provider + " disabled");
    }

	/**
     * Computes the current altitude/azimuth of Sagittarius A* for the last
     * known observer location and updates the UI. 
     * Now includes Precession correction (J2000 -> Current Date) and 
     * Atmospheric Refraction modeling.
     */
    private void refreshPosition() {
        if (!hasLocation) {
            return;
        }

        double jd = julianDateFromMillis(System.currentTimeMillis());
        double yearsSinceJ2000 = (jd - 2451545.0) / 365.25;
        double gmst = greenwichSiderealTimeDeg(jd);
        double lst = normalizeDegrees(gmst + lastLon);
        double latRad = Math.toRadians(lastLat);

        List<SkyView.Target> targets = new ArrayList<>();

        for (int i = 0; i < blackHoles.size(); i++) {
            BlackHole bh = blackHoles.get(i);

            double raRad = Math.toRadians(bh.raDeg);
            double decRad = Math.toRadians(bh.decDeg);

            double deltaRaSec = 3.075 + 1.336 * Math.sin(raRad) * Math.tan(decRad);
            double deltaDecArcsec = 20.04 * Math.cos(raRad);
            double currentRaDeg = bh.raDeg + (deltaRaSec * yearsSinceJ2000 * (15.0 / 3600.0));
            double currentDecDeg = bh.decDeg + (deltaDecArcsec * yearsSinceJ2000 / 3600.0);

            double hourAngle = normalizeDegrees(lst - currentRaDeg);
            double currentDecRad = Math.toRadians(currentDecDeg);
            double haRad = Math.toRadians(hourAngle > 180.0 ? hourAngle - 360.0 : hourAngle);

            double sinAlt = Math.sin(currentDecRad) * Math.sin(latRad)
                + Math.cos(currentDecRad) * Math.cos(latRad) * Math.cos(haRad);
            double altRad = Math.asin(sinAlt);

            double cosAz = (Math.sin(currentDecRad) - Math.sin(latRad) * sinAlt)
                / (Math.cos(latRad) * Math.cos(altRad));
            cosAz = Math.max(-1.0, Math.min(1.0, cosAz));
            double azRad = Math.acos(cosAz);

            double azDeg = Math.toDegrees(azRad);
            if (Math.sin(haRad) > 0) {
                azDeg = 360.0 - azDeg;
            }
            double altDeg = Math.toDegrees(altRad);

            if (altDeg > -5.0) {
                double refractionArcmin = 1.02 / Math.tan(Math.toRadians(altDeg + (10.3 / (altDeg + 5.11))));
                altDeg += (refractionArcmin / 60.0);
            }

            boolean isSelected = (i == selectedTargetIndex);
            if (isSelected) {
                updateUi(altDeg, azDeg);
            }

            if (isSelected || toggleShowAll.isChecked()) {
                targets.add(new SkyView.Target(bh.name, altDeg, azDeg, isSelected));
            }
        }

        skyView.setTargets(targets);
    }
	

    private void updateUi(double altDeg, double azDeg) {
        tvAltitude.setText(String.format(Locale.US, "%.1f°", altDeg));
        tvAzimuth.setText(String.format(Locale.US, "%.1f°", azDeg));
        tvDirection.setText(compassDirection(azDeg));

        if (altDeg > 0) {
            tvVisibility.setText("Above horizon");
            tvVisibility.setTextColor(0xFF66FF99);
        } else {
            tvVisibility.setText("Below horizon");
            tvVisibility.setTextColor(0xFFFF6666);
        }

        tvUpdated.setText("Last update: "
						  + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));
    }

    private String compassDirection(double azDeg) {
        String[] dirs = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
			"S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
        int index = ((int) Math.round(azDeg / 22.5)) % 16;
        if (index < 0) index += 16;
        return dirs[index];
    }

    private double julianDateFromMillis(long millis) {
        return (millis / 86400000.0) + 2440587.5;
    }

    private double greenwichSiderealTimeDeg(double jd) {
        double d = jd - 2451545.0;
        double t = d / 36525.0;
        double gmst = 280.46061837
			+ 360.98564736629 * d
			+ 0.000387933 * t * t
			- (t * t * t) / 38710000.0;
        return normalizeDegrees(gmst);
    }

    private double normalizeDegrees(double deg) {
        double r = deg % 360.0;
        return r < 0 ? r + 360.0 : r;
    }
}
