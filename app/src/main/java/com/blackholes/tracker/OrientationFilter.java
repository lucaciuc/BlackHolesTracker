package com.blackholes.tracker;

/**
 * Adaptive smoothing for device orientation: the "1 Euro filter" (Casiez,
 * Roussel & Vogel, CHI 2012) applied to unit quaternions.
 *
 * A fixed low-pass filter forces a bad trade-off: smooth enough to hide hand
 * tremor and magnetometer noise while the phone is held still means
 * sluggish when it's swung around, and vice versa. This filter raises its
 * cutoff frequency with angular speed, so it smooths hard when still and
 * follows closely when moving.
 *
 * Quaternions are (w, x, y, z), the order SensorManager.getQuaternionFromVector
 * produces. Free of Android dependencies so it can be unit-tested on the JVM.
 */
final class OrientationFilter {

    /** After a gap this long (e.g. app paused), snap to the new pose instead of easing in. */
    private static final double MAX_GAP_SEC = 0.5;
    /** Stand-in sample interval for devices that report repeated or zero timestamps. */
    private static final double FALLBACK_DT_SEC = 0.02;

    private final double minCutoffHz;
    private final double beta;
    private final double derivativeCutoffHz;

    private final double[] state = new double[4];
    private final double[] previousRaw = new double[4];
    private final double[] velocity = new double[3]; // smoothed angular velocity, rad/s
    private final double[] delta = new double[4];
    private long lastTimestampNs;
    private boolean initialized = false;

    /**
     * @param minCutoffHz cutoff while still; lower = steadier but laggier
     * @param beta how fast the cutoff rises with angular speed (Hz per rad/s)
     * @param derivativeCutoffHz smoothing of the speed estimate itself
     */
    OrientationFilter(double minCutoffHz, double beta, double derivativeCutoffHz) {
        this.minCutoffHz = minCutoffHz;
        this.beta = beta;
        this.derivativeCutoffHz = derivativeCutoffHz;
    }

    void reset() {
        initialized = false;
    }

    /**
     * Feeds one raw orientation sample and returns the filtered orientation.
     * The returned array belongs to the filter and is overwritten by the next call.
     */
    double[] filter(double[] q, long timestampNs) {
        double dt = (timestampNs - lastTimestampNs) * 1e-9;
        if (!initialized || dt > MAX_GAP_SEC) {
            System.arraycopy(q, 0, state, 0, 4);
            normalize(state);
            System.arraycopy(state, 0, previousRaw, 0, 4);
            velocity[0] = velocity[1] = velocity[2] = 0;
            lastTimestampNs = timestampNs;
            initialized = true;
            return state;
        }
        lastTimestampNs = timestampNs;
        if (dt <= 0) {
            // Rather than freezing on broken sensor timestamps, assume a typical interval.
            dt = FALLBACK_DT_SEC;
        }

        // Angular velocity between consecutive raw samples. It's a signed
        // vector, so sensor noise averages out in the low-pass below while
        // real motion doesn't.
        conjugateMultiply(previousRaw, q, delta);
        System.arraycopy(q, 0, previousRaw, 0, 4);
        if (delta[0] < 0) {
            for (int i = 0; i < 4; i++) delta[i] = -delta[i];
        }
        double vecLen = Math.sqrt(delta[1] * delta[1] + delta[2] * delta[2] + delta[3] * delta[3]);
        double angle = 2.0 * Math.atan2(vecLen, delta[0]);
        double scale = vecLen > 1e-12 ? angle / (vecLen * dt) : 0.0;
        double derivativeAlpha = alpha(derivativeCutoffHz, dt);
        for (int i = 0; i < 3; i++) {
            velocity[i] += derivativeAlpha * (delta[i + 1] * scale - velocity[i]);
        }
        double speed = Math.sqrt(velocity[0] * velocity[0] + velocity[1] * velocity[1] + velocity[2] * velocity[2]);

        slerp(state, q, alpha(minCutoffHz + beta * speed, dt), state);
        return state;
    }

    /** Smoothing factor of a first-order low-pass filter with the given cutoff. */
    static double alpha(double cutoffHz, double dtSec) {
        double tau = 1.0 / (2.0 * Math.PI * cutoffHz);
        return 1.0 / (1.0 + tau / dtSec);
    }

    /** out = conj(a) * b, i.e. the rotation from a to b in a's frame. */
    static void conjugateMultiply(double[] a, double[] b, double[] out) {
        double aw = a[0], ax = -a[1], ay = -a[2], az = -a[3];
        double bw = b[0], bx = b[1], by = b[2], bz = b[3];
        out[0] = aw * bw - ax * bx - ay * by - az * bz;
        out[1] = aw * bx + ax * bw + ay * bz - az * by;
        out[2] = aw * by - ax * bz + ay * bw + az * bx;
        out[3] = aw * bz + ax * by - ay * bx + az * bw;
    }

    /** Spherical interpolation along the shortest path; out may alias a or b. */
    static void slerp(double[] a, double[] b, double t, double[] out) {
        double dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
        double sign = 1.0;
        if (dot < 0) {
            // q and -q are the same rotation; go the short way round.
            dot = -dot;
            sign = -1.0;
        }
        double wa;
        double wb;
        if (dot > 0.9995) {
            wa = 1.0 - t;
            wb = t;
        } else {
            double theta = Math.acos(dot);
            double sinTheta = Math.sin(theta);
            wa = Math.sin((1.0 - t) * theta) / sinTheta;
            wb = Math.sin(t * theta) / sinTheta;
        }
        wb *= sign;
        double w = wa * a[0] + wb * b[0];
        double x = wa * a[1] + wb * b[1];
        double y = wa * a[2] + wb * b[2];
        double z = wa * a[3] + wb * b[3];
        out[0] = w;
        out[1] = x;
        out[2] = y;
        out[3] = z;
        normalize(out);
    }

    static void normalize(double[] q) {
        double n = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if (n < 1e-12) {
            q[0] = 1;
            q[1] = q[2] = q[3] = 0;
            return;
        }
        for (int i = 0; i < 4; i++) q[i] /= n;
    }

    /**
     * Row-major 3x3 rotation matrix (device frame to world East/North/Up),
     * same convention as SensorManager.getRotationMatrixFromVector.
     */
    static void quaternionToMatrix(double[] q, float[] m) {
        double w = q[0], x = q[1], y = q[2], z = q[3];
        m[0] = (float) (1 - 2 * (y * y + z * z));
        m[1] = (float) (2 * (x * y - z * w));
        m[2] = (float) (2 * (x * z + y * w));
        m[3] = (float) (2 * (x * y + z * w));
        m[4] = (float) (1 - 2 * (x * x + z * z));
        m[5] = (float) (2 * (y * z - x * w));
        m[6] = (float) (2 * (x * z - y * w));
        m[7] = (float) (2 * (y * z + x * w));
        m[8] = (float) (1 - 2 * (x * x + y * y));
    }

    /** Inverse of quaternionToMatrix (Shepperd's method). */
    static void matrixToQuaternion(float[] m, double[] q) {
        double trace = m[0] + m[4] + m[8];
        if (trace > 0) {
            double s = Math.sqrt(trace + 1.0) * 2;
            q[0] = 0.25 * s;
            q[1] = (m[7] - m[5]) / s;
            q[2] = (m[2] - m[6]) / s;
            q[3] = (m[3] - m[1]) / s;
        } else if (m[0] > m[4] && m[0] > m[8]) {
            double s = Math.sqrt(1.0 + m[0] - m[4] - m[8]) * 2;
            q[0] = (m[7] - m[5]) / s;
            q[1] = 0.25 * s;
            q[2] = (m[1] + m[3]) / s;
            q[3] = (m[2] + m[6]) / s;
        } else if (m[4] > m[8]) {
            double s = Math.sqrt(1.0 + m[4] - m[0] - m[8]) * 2;
            q[0] = (m[2] - m[6]) / s;
            q[1] = (m[1] + m[3]) / s;
            q[2] = 0.25 * s;
            q[3] = (m[5] + m[7]) / s;
        } else {
            double s = Math.sqrt(1.0 + m[8] - m[0] - m[4]) * 2;
            q[0] = (m[3] - m[1]) / s;
            q[1] = (m[2] + m[6]) / s;
            q[2] = (m[5] + m[7]) / s;
            q[3] = 0.25 * s;
        }
        normalize(q);
    }
}
