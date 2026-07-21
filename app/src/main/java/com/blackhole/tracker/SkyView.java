package com.blackhole.tracker;

import android.content.Context;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import java.util.List;
import java.util.ArrayList;

/**
 * Transparent AR overlay drawn on top of the live camera feed (see
 * CameraPreview, which sits behind this in main.xml): a horizon line,
 * cardinal direction ticks, and a 3D rotating marker for the current
 * target (set via setTarget). Panned in real time by the device's
 * accelerometer + magnetometer, with touch-drag as a manual override
 * that always works even without sensors.
 *
 * Device pitch/azimuth from SensorManager.getOrientation are used as-is;
 * if auto-rotation feels inverted on a real device, flip the sign on
 * devicePitchDeg (or deviceAzimuthDeg) below. Touch-drag is independent
 * of that and is unaffected either way.
 */
public class SkyView extends View implements SensorEventListener {

    private float fovDeg = 55f; // overwritten by MainActivity with the real camera FOV once available

    private final SensorManager sensorManager;
    private final Sensor accelerometer;
    private final Sensor magnetometer;

    private final float[] gravityData = new float[3];
    private final float[] geomagneticData = new float[3];
    private boolean hasGravity = false;
    private boolean hasGeomagnetic = false;

    private final float[] rotationMatrix = new float[9];
    private final float[] orientationValues = new float[3];

    private float deviceAzimuthDeg = 0f;
    private float devicePitchDeg = 0f;
	
	private float deviceRollDeg = 0f; // Add this line


    private float manualAzOffset = 0f;
    private float manualPitchOffset = 0f;
    private float lastTouchX = 0f;
    private float lastTouchY = 0f;

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

    private List<Target> targets = new ArrayList<>();
    private boolean running = false;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint diskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Camera camera3d = new Camera();
    private final Matrix matrix3d = new Matrix();

    private final long startTimeMs = System.currentTimeMillis();

    public SkyView(Context context, AttributeSet attrs) {
        super(context, attrs);
        sensorManager = context.getSystemService(SensorManager.class);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        linePaint.setColor(0xAA2A8CFF);
        linePaint.setStrokeWidth(3f);
        tickTextPaint.setColor(0xFFFFFFFF);
        tickTextPaint.setTextSize(30f);
        tickTextPaint.setTextAlign(Paint.Align.CENTER);
        tickTextPaint.setShadowLayer(6f, 0f, 0f, 0xFF000000);
        labelPaint.setColor(0xFFFFFFFF);
        labelPaint.setTextSize(32f);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setShadowLayer(6f, 0f, 0f, 0xFF000000);
        edgePaint.setColor(0xFFFF8C42);
        edgePaint.setStyle(Paint.Style.FILL);
    }

    public void start() {
        running = true;
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        }
        if (magnetometer != null) {
            sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME);
        }
        postInvalidateOnAnimation();
    }

    public void stop() {
        running = false;
        sensorManager.unregisterListener(this);
    }

    public void setTargets(List<Target> newTargets) {
        this.targets = newTargets;
    }

    public void setFovDegrees(float degrees) {
        fovDeg = degrees;
    }

	@Override
    public void onSensorChanged(SensorEvent event) {
	// Smoothing factor (0.0 to 1.0). 
        // Lower = smoother but slightly more delayed. 0.15f is a great sweet spot.
        final float alpha = 0.15f; 

        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
		if (!hasGravity) {
			// First reading: jump straight to the value
                System.arraycopy(event.values, 0, gravityData, 0, 3);
                hasGravity = true;
				} else {
			// Subsequent readings: smoothly blend the new data with the old data
                for (int i = 0; i < 3; i++) {
				gravityData[i] = gravityData[i] + alpha * (event.values[i] - gravityData[i]);
					}
				}
			} else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
		if (!hasGeomagnetic) {
			// First reading: jump straight to the value
                System.arraycopy(event.values, 0, geomagneticData, 0, 3);
                hasGeomagnetic = true;
				} else {
			// Subsequent readings: smoothly blend the new data with the old data
                for (int i = 0; i < 3; i++) {
				geomagneticData[i] = geomagneticData[i] + alpha * (event.values[i] - geomagneticData[i]);
					}
				}
			}

        if (hasGravity && hasGeomagnetic
		&& SensorManager.getRotationMatrix(rotationMatrix, null, gravityData, geomagneticData)) {
            SensorManager.getOrientation(rotationMatrix, orientationValues);

            float az = (float) Math.toDegrees(orientationValues[0]);
            float pitch = (float) Math.toDegrees(orientationValues[1]);
            float roll = (float) Math.toDegrees(orientationValues[2]);

            // Fix the "zenith flip" when pointing the phone past straight up
            if (Math.abs(roll) > 90) {
			az += 180f;
                pitch = pitch > 0 ? 180f - pitch : -180f - pitch;
				}

            if (az < 0) az += 360f;
            deviceAzimuthDeg = az % 360f;
            devicePitchDeg = pitch;
            deviceRollDeg = roll; 
			}
		}
	
	

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - lastTouchX;
                float dy = event.getY() - lastTouchY;
                float degPerPx = fovDeg / Math.max(1, getWidth());
                manualAzOffset -= dx * degPerPx;
                manualPitchOffset = clamp(manualPitchOffset + dy * degPerPx, -85f, 85f);
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                return true;
            default:
                return true;
        }
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

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }

		float viewAz = deviceAzimuthDeg + manualAzOffset;
        float viewPitch = devicePitchDeg + manualPitchOffset;
        float cx = w / 2f;
        float cy = h / 2f;
        float pxPerDeg = w / fovDeg;

        // --- NEW CODE START ---
        canvas.save();
        // Spin the canvas backward to counteract the phone twisting
        canvas.rotate(-deviceRollDeg, cx, cy);
        // --- NEW CODE END ---

        double elapsed = (System.currentTimeMillis() - startTimeMs) / 1000.0;

        drawHorizon(canvas, viewAz, viewPitch, cx, cy, pxPerDeg);
        for (Target t : targets) {
            drawTarget(canvas, viewAz, viewPitch, cx, cy, pxPerDeg, elapsed, t);
        }

        // --- NEW CODE START ---
        canvas.restore();
        // --- NEW CODE END ---

        if (running) {
		postInvalidateOnAnimation();
			}
		
    }

    private void drawHorizon(Canvas canvas, float viewAz, float viewPitch, float cx, float cy, float pxPerDeg) {
        float y = cy + viewPitch * pxPerDeg;
        canvas.drawLine(0, y, canvas.getWidth(), y, linePaint);

        String[] dirs = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        for (int i = 0; i < dirs.length; i++) {
            float dirAz = i * 45f;
            float deltaAz = wrapDelta(dirAz - viewAz);
            if (Math.abs(deltaAz) < fovDeg / 2f + 10f) {
                float x = cx + deltaAz * pxPerDeg;
                canvas.drawText(dirs[i], x, y - 18f, tickTextPaint);
            }
        }
    }

    private void drawTarget(Canvas canvas, float viewAz, float viewPitch, float cx, float cy, float pxPerDeg, double elapsed, Target target) {
        float deltaAz = wrapDelta((float) target.azDeg - viewAz);
        float deltaAlt = (float) target.altDeg - viewPitch;
        boolean onScreen = Math.abs(deltaAz) < fovDeg / 2f && Math.abs(deltaAlt) < fovDeg / 2f;
        float pulse = (float) (0.5 + 0.5 * Math.sin(elapsed * 2.0));

        if (onScreen) {
            float sx = cx + deltaAz * pxPerDeg;
            float sy = cy - deltaAlt * pxPerDeg;

            canvas.save();
            camera3d.save();
            camera3d.rotateX(55f);
            camera3d.rotateZ((float) ((elapsed * 35) % 360));
            camera3d.getMatrix(matrix3d);
            camera3d.restore();
            matrix3d.preTranslate(-sx, -sy);
            matrix3d.postTranslate(sx, sy);
            canvas.concat(matrix3d);

            float diskRadius = 55f + 8f * pulse;
            if (target.isSelected) {
                diskPaint.setShader(new RadialGradient(sx, sy, diskRadius,
                                                       new int[]{0xFFFFE0A3, 0xFFFF8C42, 0x00000000},
                                                       new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            } else {
                diskPaint.setShader(new RadialGradient(sx, sy, diskRadius,
                                                       new int[]{0xFFFFFFFF, 0xFF428CFF, 0x00000000},
                                                       new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            }
            canvas.drawCircle(sx, sy, diskRadius, diskPaint);
            canvas.restore();

            float glowRadius = 26f + 6f * pulse;
            glowPaint.setShader(new RadialGradient(sx, sy, glowRadius,
                                                   new int[]{0xFFFFFFFF, 0xFF88AAFF, 0x00000000},
                                                   new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(sx, sy, glowRadius, glowPaint);

            corePaint.setColor(0xFF000000);
            canvas.drawCircle(sx, sy, 9f, corePaint);

            labelPaint.setAlpha(target.altDeg > 0 ? 255 : 150);
            canvas.drawText(target.name, sx, sy + diskRadius + 36f, labelPaint);
        } else {
            boolean isRight = deltaAz > 0;
            float hintY = clamp(cy - deltaAlt * pxPerDeg, 80f, canvas.getHeight() - 80f);
            float hintX = isRight ? canvas.getWidth() - 50f : 50f;

            Path arrow = new Path();
            if (isRight) {
                arrow.moveTo(hintX + 14f, hintY);
                arrow.lineTo(hintX - 10f, hintY - 14f);
                arrow.lineTo(hintX - 10f, hintY + 14f);
            } else {
                arrow.moveTo(hintX - 14f, hintY);
                arrow.lineTo(hintX + 10f, hintY - 14f);
                arrow.lineTo(hintX + 10f, hintY + 14f);
            }
            arrow.close();
            edgePaint.setAlpha((int) (150 + 100 * pulse));
            canvas.drawPath(arrow, edgePaint);

            float angularDistance = (float) Math.hypot(deltaAz, deltaAlt);
            tickTextPaint.setAlpha(255);
            canvas.drawText(Math.round(angularDistance) + "°", hintX, hintY + 46f, tickTextPaint);
        }
    }
}
