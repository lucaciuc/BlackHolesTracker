package com.blackholes.tracker;

import android.content.Context;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Display;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/**
 * Transparent AR overlay drawn on top of the live camera feed (see
 * CameraPreview, which sits behind this in main.xml): a horizon with compass
 * ticks, a center reticle, and 3D rotating markers for the targets (set via
 * setTargets).
 *
 * Orientation comes from the fused rotation-vector sensor (gyroscope +
 * accelerometer + magnetometer) when the phone has a gyroscope, falling back
 * to accelerometer + magnetometer only, and is smoothed by an adaptive
 * OrientationFilter. Raw accelerometer + magnetometer readings are what made
 * the overlay shake: every hand tremor shows up as a fake tilt, and the
 * magnetometer is noisy. The gyroscope measures rotation directly.
 *
 * Targets are placed with a pinhole projection of the full 3D orientation
 * (SkyProjection) instead of separate azimuth/pitch/roll angles, so there's
 * no gimbal lock when pointing at the zenith, phone twist is handled for
 * free, and markers line up with the camera image at any altitude.
 *
 * Touch: drag to nudge the view (corrects compass error, or looks around when
 * there are no sensors), double-tap to undo that, tap a marker to select it.
 */
public class SkyView extends View implements SensorEventListener {

    public interface Listener {
        void onTargetTapped(String name);

        /** Compass accuracy or the manual view offset changed. */
        void onViewStateChanged();
    }

    /** Where orientation comes from, best first. */
    public enum SensorMode { GYRO_FUSED, COMPASS_ONLY, NONE }

    public static class Target {
        public String name;
        public double altDeg;
        public double azDeg;
        public boolean isSelected;
        public Target(String name, double altDeg, double azDeg, boolean isSelected) {
            this.name = name;
            this.altDeg = altDeg;
            this.azDeg = azDeg;
            this.isSelected = isSelected;
        }
    }

    private static final String[] CARDINALS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    /** Upright with the back camera facing north; used until the sensors report, or for good without them. */
    private static final float[] DEFAULT_ROTATION = {1, 0, 0, 0, 0, -1, 0, 1, 0};

    private static final double LOCK_ON_DEG = 2.0;
    private static final double LOCK_OFF_DEG = 3.5;
    private static final float MAX_PITCH_OFFSET_DEG = 85f;

    private final SensorManager sensorManager;
    private final Sensor rotationVector;
    private final Sensor accelerometer;
    private final Sensor magnetometer;
    private final SensorMode sensorMode;
    private final OrientationFilter filter;

    private final float[] gravityData = new float[3];
    private final float[] geomagneticData = new float[3];
    private boolean hasGravity = false;
    private boolean hasGeomagnetic = false;
    private final float[] rawMatrix = new float[9];
    private final float[] rawQuaternionF = new float[4];
    private final double[] rawQuaternion = new double[4];
    private final float[] deviceMatrix = DEFAULT_ROTATION.clone();
    private final float[] screenMatrix = new float[9];
    private boolean hasOrientation = false;
    private int magneticAccuracy = -1; // unknown until the magnetometer reports
    private int displayRotation = Surface.ROTATION_0;

    private float declinationDeg = 0f;
    private float manualAzOffset = 0f;
    private float manualPitchOffset = 0f;

    // Half-angle tangents of the camera image as displayed (see setCameraFov).
    private double tanHalfFovX = Math.tan(Math.toRadians(27.5));
    private double tanHalfFovY = 0;

    private int insetTop = 0;
    private int insetBottom = 0;

    private List<Target> targets = new ArrayList<>();
    private boolean running = false;
    private boolean lockedOn = false;
    private Listener listener;

    // Marker positions from the last frame, for tap-to-select.
    private float[] hitX = new float[0];
    private float[] hitY = new float[0];
    private String[] hitName = new String[0];
    private int hitCount = 0;

    private final SkyProjection projection = new SkyProjection();
    private final double[] cam = new double[3];
    private final double[] point = new double[2];
    private final double[] point2 = new double[2];
    private final double[] pointing = new double[2];
    private final float[] cornerX = new float[4];
    private final float[] cornerY = new float[4];
    private final float[] polyX = new float[8];
    private final float[] polyY = new float[8];
    private final Path groundPath = new Path();
    private final Path arrowPath = new Path();
    private final StringBuilder hintText = new StringBuilder();

    private final float dp;
    private final float diskRadiusSelected;
    private final float diskRadiusOther;
    private final float glowRadius;
    private final float coreRadius;
    private final float cardinalTextOffset;
    private final float hintTextOffset;

    private final Paint horizonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardinalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint northPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zenithPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zenithTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint diskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgeDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reticlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lockTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Built once at the origin and positioned with canvas transforms, so
    // drawing a frame allocates nothing (GC pauses show up as stutter).
    private final Shader selectedDiskShader;
    private final Shader otherDiskShader;
    private final Shader glowShader;

    private final Camera camera3d = new Camera();
    private final Matrix matrix3d = new Matrix();

    private final String zenithLabel;
    private final String belowHorizonLabel;
    private final String onTargetLabel;

    private final GestureDetector gestures;
    private final long startTimeMs = System.currentTimeMillis();

    public SkyView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = getResources().getDisplayMetrics().density;

        sensorManager = context.getSystemService(SensorManager.class);
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        Sensor gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (rotationVector != null && gyroscope != null) {
            sensorMode = SensorMode.GYRO_FUSED;
            // Gyro fusion is already smooth; just take the edge off hand tremor.
            filter = new OrientationFilter(1.0, 3.0, 1.0);
        } else if (rotationVector != null || (accelerometer != null && magnetometer != null)) {
            sensorMode = SensorMode.COMPASS_ONLY;
            // Accelerometer + magnetometer only is noisy, so smooth much harder
            // while still. Tuned in simulation against the old fixed filter:
            // about a third less jitter, half the lag when panning.
            filter = new OrientationFilter(0.2, 3.0, 0.5);
        } else {
            sensorMode = SensorMode.NONE;
            filter = null;
        }

        diskRadiusSelected = 22 * dp;
        diskRadiusOther = 15 * dp;
        glowRadius = 10 * dp;
        coreRadius = 3.5f * dp;

        horizonPaint.setColor(0xCC4DA3FF);
        horizonPaint.setStrokeWidth(2 * dp);
        groundPaint.setColor(0x47000814);
        tickPaint.setColor(0xCCFFFFFF);
        tickPaint.setStrokeWidth(1.5f * dp);
        setupText(cardinalPaint, 14, 0xFFFFFFFF, true);
        setupText(northPaint, 14, 0xFFFF6B6B, true);
        zenithPaint.setColor(0x99FFFFFF);
        zenithPaint.setStyle(Paint.Style.STROKE);
        zenithPaint.setStrokeWidth(1.5f * dp);
        setupText(zenithTextPaint, 11, 0x99FFFFFF, false);
        corePaint.setColor(0xFF000000);
        setupText(labelPaint, 13, 0xFFFFFFFF, false);
        setupText(selectedLabelPaint, 14, 0xFFFFFFFF, true);
        setupText(subLabelPaint, 11, 0xCCFFFFFF, false);
        edgePaint.setColor(0xFFFF8C42);
        edgeDotPaint.setColor(0xB34DA3FF);
        setupText(hintPaint, 13, 0xFFFFFFFF, true);
        reticlePaint.setColor(0x99FFFFFF);
        reticlePaint.setStyle(Paint.Style.STROKE);
        reticlePaint.setStrokeWidth(1.5f * dp);
        lockPaint.setColor(0xFF66FF99);
        lockPaint.setStyle(Paint.Style.STROKE);
        lockPaint.setStrokeWidth(2.5f * dp);
        setupText(lockTextPaint, 12, 0xFF66FF99, true);
        cardinalTextOffset = textCenterOffset(cardinalPaint);
        hintTextOffset = textCenterOffset(hintPaint);

        selectedDiskShader = new RadialGradient(0, 0, diskRadiusSelected,
            new int[]{0xFFFFE0A3, 0xFFFF8C42, 0x00000000}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        otherDiskShader = new RadialGradient(0, 0, diskRadiusOther,
            new int[]{0xFFFFFFFF, 0xFF428CFF, 0x00000000}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        glowShader = new RadialGradient(0, 0, glowRadius,
            new int[]{0xFFFFFFFF, 0xFF88AAFF, 0x00000000}, new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP);
        glowPaint.setShader(glowShader);

        float arrow = 1.2f * dp;
        arrowPath.moveTo(14 * arrow, 0);
        arrowPath.lineTo(-10 * arrow, -14 * arrow);
        arrowPath.lineTo(-10 * arrow, 14 * arrow);
        arrowPath.close();

        zenithLabel = context.getString(R.string.marker_zenith);
        belowHorizonLabel = context.getString(R.string.marker_below_horizon);
        onTargetLabel = context.getString(R.string.marker_on_target);

        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                float degPerPx = (float) Math.toDegrees(1.0 / focalLengthPx(getWidth(), getHeight()));
                // "Grab the sky" semantics: the sky follows the finger.
                manualAzOffset = wrapDelta(manualAzOffset + distanceX * degPerPx);
                manualPitchOffset = clamp(manualPitchOffset - distanceY * degPerPx,
                    -MAX_PITCH_OFFSET_DEG, MAX_PITCH_OFFSET_DEG);
                notifyViewStateChanged();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                String name = targetAt(e.getX(), e.getY());
                if (name != null && listener != null) {
                    listener.onTargetTapped(name);
                }
                performClick();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                resetManualOffset();
                return true;
            }
        });
    }

    private void setupText(Paint paint, float sp, int color, boolean bold) {
        paint.setColor(color);
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics()));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setShadowLayer(3 * dp, 0f, 0f, 0xFF000000);
        if (bold) {
            paint.setTypeface(Typeface.DEFAULT_BOLD);
        }
    }

    public void start() {
        running = true;
        if (filter != null) {
            filter.reset();
        }
        hasGravity = false;
        hasGeomagnetic = false;
        if (rotationVector != null) {
            sensorManager.registerListener(this, rotationVector, SensorManager.SENSOR_DELAY_GAME);
            if (magnetometer != null) {
                // Only for its calibration status; the rotation vector already fuses its data.
                sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI);
            }
        } else if (sensorMode == SensorMode.COMPASS_ONLY) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
            sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME);
        }
        postInvalidateOnAnimation();
    }

    public void stop() {
        running = false;
        sensorManager.unregisterListener(this);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setTargets(List<Target> newTargets) {
        this.targets = newTargets;
    }

    /**
     * Field of view of the camera image as shown on screen, given as the
     * tangents of the half-angles across the displayed image's width and
     * height. The preview is scaled to fill the view and cropped, so the
     * view shows at most this much.
     */
    public void setCameraFov(double tanHalfWidth, double tanHalfHeight) {
        tanHalfFovX = tanHalfWidth;
        tanHalfFovY = tanHalfHeight;
    }

    /** Magnetic declination (degrees east of true north) at the observer. */
    public void setDeclination(float degrees) {
        declinationDeg = degrees;
    }

    /** Heights covered by opaque UI at the top and bottom, kept clear of edge arrows. */
    public void setOverlayInsets(int top, int bottom) {
        insetTop = top;
        insetBottom = bottom;
    }

    public SensorMode getSensorMode() {
        return sensorMode;
    }

    public boolean needsCompassCalibration() {
        return sensorMode != SensorMode.NONE
            && (magneticAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
                || magneticAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW);
    }

    /** True when the user has dragged the view away from where the sensors point it. */
    public boolean hasManualOffset() {
        return sensorMode != SensorMode.NONE
            && (Math.abs(manualAzOffset) >= 0.1f || Math.abs(manualPitchOffset) >= 0.1f);
    }

    public float getManualAzOffset() {
        return manualAzOffset;
    }

    public float getManualPitchOffset() {
        return manualPitchOffset;
    }

    public void resetManualOffset() {
        manualAzOffset = 0f;
        manualPitchOffset = 0f;
        notifyViewStateChanged();
    }

    private void notifyViewStateChanged() {
        if (listener != null) {
            listener.onViewStateChanged();
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        switch (event.sensor.getType()) {
            case Sensor.TYPE_ROTATION_VECTOR:
                SensorManager.getQuaternionFromVector(rawQuaternionF, event.values);
                for (int i = 0; i < 4; i++) {
                    rawQuaternion[i] = rawQuaternionF[i];
                }
                applyOrientation(event.timestamp);
                break;
            case Sensor.TYPE_ACCELEROMETER:
                System.arraycopy(event.values, 0, gravityData, 0, 3);
                hasGravity = true;
                if (hasGeomagnetic && SensorManager.getRotationMatrix(rawMatrix, null, gravityData, geomagneticData)) {
                    OrientationFilter.matrixToQuaternion(rawMatrix, rawQuaternion);
                    applyOrientation(event.timestamp);
                }
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                updateMagneticAccuracy(event.accuracy);
                if (rotationVector == null) {
                    System.arraycopy(event.values, 0, geomagneticData, 0, 3);
                    hasGeomagnetic = true;
                }
                break;
            default:
                break;
        }
    }

    private void applyOrientation(long timestampNs) {
        OrientationFilter.quaternionToMatrix(filter.filter(rawQuaternion, timestampNs), deviceMatrix);
        hasOrientation = true;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            updateMagneticAccuracy(accuracy);
        }
    }

    private void updateMagneticAccuracy(int accuracy) {
        if (accuracy != magneticAccuracy) {
            magneticAccuracy = accuracy;
            notifyViewStateChanged();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateDisplayRotation();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateDisplayRotation();
    }

    private void updateDisplayRotation() {
        Display display = getDisplay();
        if (display != null) {
            displayRotation = display.getRotation();
        }
    }

    /** Sensor axes follow the device's natural orientation; re-express them along the screen's. */
    private void remapForDisplay(float[] in, float[] out) {
        switch (displayRotation) {
            case Surface.ROTATION_90:
                SensorManager.remapCoordinateSystem(in, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, out);
                break;
            case Surface.ROTATION_180:
                SensorManager.remapCoordinateSystem(in, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, out);
                break;
            case Surface.ROTATION_270:
                SensorManager.remapCoordinateSystem(in, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, out);
                break;
            default:
                System.arraycopy(in, 0, out, 0, 9);
                break;
        }
    }

    private double focalLengthPx(int w, int h) {
        double f = Math.max(1, w) / (2 * tanHalfFovX);
        if (tanHalfFovY > 0) {
            f = Math.max(f, h / (2 * tanHalfFovY));
        }
        return f;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        gestures.onTouchEvent(event);
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private String targetAt(float x, float y) {
        float hitRadius = 32 * dp;
        String best = null;
        float bestDistSq = hitRadius * hitRadius;
        for (int i = 0; i < hitCount; i++) {
            float dx = hitX[i] - x;
            float dy = hitY[i] - y;
            float distSq = dx * dx + dy * dy;
            if (distSq <= bestDistSq) {
                bestDistSq = distSq;
                best = hitName[i];
            }
        }
        return best;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float wrapDelta(float deg) {
        float d = deg % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    /** Baseline shift that vertically centers the paint's text on a point. */
    private static float textCenterOffset(Paint paint) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        return -(fm.ascent + fm.descent) / 2f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        float cx = w / 2f;
        float cy = h / 2f;

        if (hasOrientation) {
            remapForDisplay(deviceMatrix, screenMatrix);
        } else {
            System.arraycopy(DEFAULT_ROTATION, 0, screenMatrix, 0, 9);
        }
        // The sensors measure against magnetic north; without them there's nothing to correct.
        float azRotation = manualAzOffset + (sensorMode == SensorMode.NONE ? 0f : declinationDeg);
        projection.set(screenMatrix, azRotation, manualPitchOffset, focalLengthPx(w, h), cx, cy);
        projection.pointing(pointing);

        double elapsed = (System.currentTimeMillis() - startTimeMs) / 1000.0;
        float pulse = (float) (0.5 + 0.5 * Math.sin(elapsed * 2.0));

        drawGroundAndHorizon(canvas, w, h, cx, cy);
        drawCompassTicks(canvas, w, h);
        drawZenith(canvas, w, h);

        List<Target> current = targets;
        if (hitX.length < current.size()) {
            hitX = new float[current.size()];
            hitY = new float[current.size()];
            hitName = new String[current.size()];
        }
        hitCount = 0;
        Target selected = null;
        for (int i = 0; i < current.size(); i++) {
            Target t = current.get(i);
            if (t.isSelected) {
                selected = t;
            } else {
                drawTarget(canvas, t, w, h, cx, cy, pulse, elapsed);
            }
        }
        if (selected != null) {
            drawTarget(canvas, selected, w, h, cx, cy, pulse, elapsed); // last, so it's on top
        }
        updateLockOn(selected);
        drawReticle(canvas, cx, cy);

        if (running) {
            postInvalidateOnAnimation();
        }
    }

    /** Tints everything below the horizon and draws the horizon line itself. */
    private void drawGroundAndHorizon(Canvas canvas, int w, int h, float cx, float cy) {
        // A screen point (x, y) looks along ((x-cx)/f, -(y-cy)/f, -1) in the
        // camera frame; it's sky when that ray points above the horizon
        // plane, i.e. has a positive dot product with the up vector. Clip
        // the screen rectangle to the ground half-plane.
        projection.upInCamera(cam);
        double f = projection.focalPx();
        cornerX[0] = 0; cornerY[0] = 0;
        cornerX[1] = w; cornerY[1] = 0;
        cornerX[2] = w; cornerY[2] = h;
        cornerX[3] = 0; cornerY[3] = h;
        int n = 0;
        int crossings = 0;
        float hx0 = 0, hy0 = 0, hx1 = 0, hy1 = 0;
        for (int i = 0; i < 4; i++) {
            float x1 = cornerX[i], y1 = cornerY[i];
            float x2 = cornerX[(i + 1) % 4], y2 = cornerY[(i + 1) % 4];
            double g1 = cam[0] * (x1 - cx) / f - cam[1] * (y1 - cy) / f - cam[2];
            double g2 = cam[0] * (x2 - cx) / f - cam[1] * (y2 - cy) / f - cam[2];
            if (g1 <= 0) {
                polyX[n] = x1;
                polyY[n] = y1;
                n++;
            }
            if ((g1 < 0 && g2 > 0) || (g1 > 0 && g2 < 0)) {
                double t = g1 / (g1 - g2);
                float ix = (float) (x1 + t * (x2 - x1));
                float iy = (float) (y1 + t * (y2 - y1));
                polyX[n] = ix;
                polyY[n] = iy;
                n++;
                if (crossings == 0) {
                    hx0 = ix;
                    hy0 = iy;
                } else {
                    hx1 = ix;
                    hy1 = iy;
                }
                crossings++;
            }
        }
        if (n >= 3) {
            groundPath.rewind();
            groundPath.moveTo(polyX[0], polyY[0]);
            for (int i = 1; i < n; i++) {
                groundPath.lineTo(polyX[i], polyY[i]);
            }
            groundPath.close();
            canvas.drawPath(groundPath, groundPaint);
        }
        if (crossings == 2) {
            canvas.drawLine(hx0, hy0, hx1, hy1, horizonPaint);
        }
    }

    /** Ticks every 15 degrees along the horizon, labelled at the 8 compass points. */
    private void drawCompassTicks(Canvas canvas, int w, int h) {
        float margin = 60 * dp;
        for (int i = 0; i < 24; i++) {
            double az = i * 15.0;
            if (!projection.project(0, az, cam, point)
                || point[0] < -margin || point[0] > w + margin || point[1] < -margin || point[1] > h + margin
                || !projection.project(1.0, az, cam, point2)) {
                continue;
            }
            // Unit vector pointing "up the sky" at this spot, so ticks and
            // labels stay perpendicular to the horizon however the phone is held.
            double ux = point2[0] - point[0];
            double uy = point2[1] - point[1];
            double len = Math.hypot(ux, uy);
            if (len < 1e-6) {
                continue;
            }
            ux /= len;
            uy /= len;
            boolean major = i % 3 == 0;
            float tick = (major ? 9 : 5) * dp;
            canvas.drawLine((float) (point[0] - ux * tick), (float) (point[1] - uy * tick),
                (float) (point[0] + ux * tick), (float) (point[1] + uy * tick), tickPaint);
            if (major) {
                Paint paint = i == 0 ? northPaint : cardinalPaint;
                float gap = tick + 12 * dp;
                canvas.drawText(CARDINALS[i / 3], (float) (point[0] + ux * gap),
                    (float) (point[1] + uy * gap) + cardinalTextOffset, paint);
            }
        }
    }

    private void drawZenith(Canvas canvas, int w, int h) {
        if (projection.project(90, 0, cam, point)
            && point[0] >= 0 && point[0] <= w && point[1] >= 0 && point[1] <= h) {
            float x = (float) point[0];
            float y = (float) point[1];
            canvas.drawCircle(x, y, 5 * dp, zenithPaint);
            canvas.drawText(zenithLabel, x, y - 12 * dp, zenithTextPaint);
        }
    }

    private void drawTarget(Canvas canvas, Target t, int w, int h, float cx, float cy, float pulse, double elapsed) {
        projection.toCamera(t.altDeg, t.azDeg, cam);
        boolean onScreen = projection.cameraToScreen(cam, point)
            && point[0] >= 0 && point[0] <= w && point[1] >= insetTop && point[1] <= h - insetBottom;
        if (onScreen) {
            float sx = (float) point[0];
            float sy = (float) point[1];
            drawMarker(canvas, t, sx, sy, pulse, elapsed);
            hitX[hitCount] = sx;
            hitY[hitCount] = sy;
            hitName[hitCount] = t.name;
            hitCount++;
        } else {
            drawEdgeIndicator(canvas, t, w, h, cx, cy, pulse);
        }
    }

    private void drawMarker(Canvas canvas, Target t, float sx, float sy, float pulse, double elapsed) {
        float diskRadius = t.isSelected ? diskRadiusSelected : diskRadiusOther;
        float scale = 1f + 0.15f * pulse;

        // Accretion disk, tilted and spinning in 3D.
        canvas.save();
        canvas.translate(sx, sy);
        camera3d.save();
        camera3d.rotateX(55f);
        camera3d.rotateZ((float) ((elapsed * 35) % 360));
        camera3d.getMatrix(matrix3d);
        camera3d.restore();
        canvas.concat(matrix3d);
        canvas.scale(scale, scale);
        diskPaint.setShader(t.isSelected ? selectedDiskShader : otherDiskShader);
        canvas.drawCircle(0, 0, diskRadius, diskPaint);
        canvas.restore();

        float glowScale = (diskRadius / diskRadiusSelected) * (1f + 0.2f * pulse);
        canvas.save();
        canvas.translate(sx, sy);
        canvas.scale(glowScale, glowScale);
        canvas.drawCircle(0, 0, glowRadius, glowPaint);
        canvas.restore();

        canvas.drawCircle(sx, sy, coreRadius, corePaint);

        boolean belowHorizon = t.altDeg < 0;
        Paint paint = t.isSelected ? selectedLabelPaint : labelPaint;
        paint.setAlpha(belowHorizon ? 160 : 255);
        float labelY = sy + diskRadius * scale + 18 * dp;
        canvas.drawText(t.name, sx, labelY, paint);
        if (belowHorizon) {
            canvas.drawText(belowHorizonLabel, sx, labelY + 15 * dp, subLabelPaint);
        }
    }

    /** Arrow at the screen edge pointing toward a target that's out of view. */
    private void drawEdgeIndicator(Canvas canvas, Target t, int w, int h, float cx, float cy, float pulse) {
        // The target's direction projected onto the screen plane. Valid even
        // when it's behind the camera: it's still the way to turn.
        double dx = cam[0];
        double dy = -cam[1];
        double len = Math.hypot(dx, dy);
        if (len < 1e-6) {
            dx = 1; // dead behind: either way round works
            dy = 0;
            len = 1;
        }
        dx /= len;
        dy /= len;

        float margin = (t.isSelected ? 30 : 12) * dp;
        float left = margin;
        float right = w - margin;
        float top = insetTop + margin;
        float bottom = h - insetBottom - margin;
        if (right <= left || bottom <= top) {
            return;
        }
        double toEdge = Double.MAX_VALUE;
        if (dx > 1e-9) toEdge = Math.min(toEdge, (right - cx) / dx);
        if (dx < -1e-9) toEdge = Math.min(toEdge, (left - cx) / dx);
        if (dy > 1e-9) toEdge = Math.min(toEdge, (bottom - cy) / dy);
        if (dy < -1e-9) toEdge = Math.min(toEdge, (top - cy) / dy);
        if (toEdge == Double.MAX_VALUE || toEdge < 0) {
            return;
        }
        float ex = (float) (cx + dx * toEdge);
        float ey = (float) (cy + dy * toEdge);

        if (!t.isSelected) {
            // Other targets get a quiet dot, so "Show all" doesn't bury the selected one's arrow.
            canvas.drawCircle(ex, ey, 4.5f * dp, edgeDotPaint);
            return;
        }

        canvas.save();
        canvas.translate(ex, ey);
        canvas.rotate((float) Math.toDegrees(Math.atan2(dy, dx)));
        edgePaint.setAlpha((int) (150 + 100 * pulse));
        canvas.drawPath(arrowPath, edgePaint);
        canvas.restore();

        // Explicit degrees on each axis: how far to turn left/right AND how
        // far to tilt up/down, instead of one combined distance.
        double deltaAz = wrapDelta((float) (t.azDeg - pointing[1]));
        double deltaAlt = t.altDeg - pointing[0];
        hintText.setLength(0);
        hintText.append(Math.round(Math.abs(deltaAz))).append('°').append(deltaAz >= 0 ? 'R' : 'L')
            .append("   ")
            .append(Math.round(Math.abs(deltaAlt))).append('°').append(deltaAlt >= 0 ? 'U' : 'D');
        float halfWidth = hintPaint.measureText(hintText, 0, hintText.length()) / 2f;
        // Sit just inside the arrow, toward the center, clamped onto the screen.
        float tx = clamp((float) (ex - dx * 36 * dp), halfWidth + 8 * dp, w - halfWidth - 8 * dp);
        float ty = (float) (ey - dy * 36 * dp) + hintTextOffset;
        canvas.drawText(hintText, 0, hintText.length(), tx, ty, hintPaint);
    }

    private void updateLockOn(Target selected) {
        boolean wasLocked = lockedOn;
        if (selected == null) {
            lockedOn = false;
            return;
        }
        projection.toCamera(selected.altDeg, selected.azDeg, cam);
        double offCenterDeg = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, -cam[2]))));
        lockedOn = offCenterDeg < (wasLocked ? LOCK_OFF_DEG : LOCK_ON_DEG);
        if (lockedOn && !wasLocked) {
            performHapticFeedback(Build.VERSION.SDK_INT >= 30
                ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY);
        }
    }

    private void drawReticle(Canvas canvas, float cx, float cy) {
        Paint paint = lockedOn ? lockPaint : reticlePaint;
        float r = 16 * dp;
        float inner = r + 3 * dp;
        float outer = r + 10 * dp;
        canvas.drawCircle(cx, cy, r, paint);
        canvas.drawLine(cx - outer, cy, cx - inner, cy, paint);
        canvas.drawLine(cx + inner, cy, cx + outer, cy, paint);
        canvas.drawLine(cx, cy - outer, cx, cy - inner, paint);
        canvas.drawLine(cx, cy + inner, cx, cy + outer, paint);
        if (lockedOn) {
            canvas.drawText(onTargetLabel, cx, cy - outer - 8 * dp, lockTextPaint);
        }
    }
}
