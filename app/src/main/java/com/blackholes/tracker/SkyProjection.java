package com.blackholes.tracker;

/**
 * Pinhole-camera projection of sky directions (altitude/azimuth) onto the
 * screen, given the phone's full 3D orientation.
 *
 * Working with the whole rotation matrix instead of separate azimuth/pitch/
 * roll angles means there's no gimbal lock when pointing at the zenith,
 * phone twist is handled for free, and positions stay correct at every
 * altitude (a flat "degrees times pixels-per-degree" mapping squeezes
 * azimuth wrongly as you look higher). Great circles such as the horizon
 * come out as straight lines, matching the camera image.
 *
 * Camera frame: x to the right of the screen, y to its top, z out of the
 * screen toward the viewer, so visible points have z < 0. Free of Android
 * dependencies so it can be unit-tested on the JVM.
 */
final class SkyProjection {

    private final float[] r = new float[9];
    private double azRotationDeg;
    private double sinPitch;
    private double cosPitch = 1.0;
    private double focalPx = 1.0;
    private double cx;
    private double cy;

    /**
     * @param rotation row-major device-to-world (East/North/Up) rotation, with
     *     the device axes already remapped to the screen's
     * @param azRotationDeg how far the rotation's "north" is east of true north
     *     (magnetic declination plus any manual correction)
     * @param pitchOffsetDeg extra upward tilt of the view about the screen's x axis
     */
    void set(float[] rotation, double azRotationDeg, double pitchOffsetDeg,
             double focalPx, double cx, double cy) {
        System.arraycopy(rotation, 0, r, 0, 9);
        this.azRotationDeg = azRotationDeg;
        double p = Math.toRadians(pitchOffsetDeg);
        sinPitch = Math.sin(p);
        cosPitch = Math.cos(p);
        this.focalPx = focalPx;
        this.cx = cx;
        this.cy = cy;
    }

    double focalPx() {
        return focalPx;
    }

    /** Unit vector toward (alt, true az), in the camera frame. */
    void toCamera(double altDeg, double azDeg, double[] out) {
        double alt = Math.toRadians(altDeg);
        double az = Math.toRadians(azDeg - azRotationDeg);
        double east = Math.cos(alt) * Math.sin(az);
        double north = Math.cos(alt) * Math.cos(az);
        double up = Math.sin(alt);
        worldToCamera(east, north, up, out);
    }

    /** World up (zenith) in the camera frame; the horizon is the plane normal to it. */
    void upInCamera(double[] out) {
        worldToCamera(0, 0, 1, out);
    }

    private void worldToCamera(double east, double north, double up, double[] out) {
        // Rotation matrices are orthonormal, so world-to-device is the transpose.
        double dx = r[0] * east + r[3] * north + r[6] * up;
        double dy = r[1] * east + r[4] * north + r[7] * up;
        double dz = r[2] * east + r[5] * north + r[8] * up;
        out[0] = dx;
        out[1] = dy * cosPitch + dz * sinPitch;
        out[2] = -dy * sinPitch + dz * cosPitch;
    }

    /**
     * Projects a camera-frame vector to screen pixels (y grows downward).
     *
     * @return false if the point is behind the camera (out is untouched)
     */
    boolean cameraToScreen(double[] cam, double[] out) {
        double depth = -cam[2];
        if (depth < 1e-6) {
            return false;
        }
        out[0] = cx + focalPx * cam[0] / depth;
        out[1] = cy - focalPx * cam[1] / depth;
        return true;
    }

    /** Projects (alt, true az) to screen pixels; false if behind the camera. */
    boolean project(double altDeg, double azDeg, double[] tmpCam, double[] out) {
        toCamera(altDeg, azDeg, tmpCam);
        return cameraToScreen(tmpCam, out);
    }

    /** Where the center of the screen points: {altDeg, true azDeg}. */
    void pointing(double[] out) {
        // Camera forward is (0, 0, -1) in the camera frame; undo the pitch
        // offset to get it in the device frame, then rotate into the world.
        double dy = sinPitch;
        double dz = -cosPitch;
        double east = r[1] * dy + r[2] * dz;
        double north = r[4] * dy + r[5] * dz;
        double up = r[7] * dy + r[8] * dz;
        out[0] = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, up))));
        out[1] = Astronomy.normalizeDegrees(Math.toDegrees(Math.atan2(east, north)) + azRotationDeg);
    }
}
