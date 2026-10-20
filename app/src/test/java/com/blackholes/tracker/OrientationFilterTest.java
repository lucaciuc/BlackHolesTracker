package com.blackholes.tracker;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import org.junit.Test;

public class OrientationFilterTest {

    private static final long STEP_NS = 20_000_000L; // 50 Hz, like SENSOR_DELAY_GAME

    private static double[] axisAngle(double ax, double ay, double az, double angleDeg) {
        double n = Math.sqrt(ax * ax + ay * ay + az * az);
        double half = Math.toRadians(angleDeg) / 2;
        double s = Math.sin(half) / n;
        return new double[]{Math.cos(half), ax * s, ay * s, az * s};
    }

    private static double[] multiply(double[] a, double[] b) {
        double[] conjA = {a[0], -a[1], -a[2], -a[3]};
        double[] out = new double[4];
        OrientationFilter.conjugateMultiply(conjA, b, out);
        return out;
    }

    private static double angleBetweenDeg(double[] a, double[] b) {
        double dot = Math.abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]);
        return Math.toDegrees(2 * Math.acos(Math.min(1.0, dot)));
    }

    @Test
    public void matrixMatchesAndroidConventionFor90DegreesAboutZ() {
        float[] m = new float[9];
        OrientationFilter.quaternionToMatrix(axisAngle(0, 0, 1, 90), m);
        assertArrayEquals(new float[]{0, -1, 0, 1, 0, 0, 0, 0, 1}, m, 1e-6f);
    }

    @Test
    public void matrixQuaternionRoundTrip() {
        Random random = new Random(1);
        float[] m = new float[9];
        double[] back = new double[4];
        for (int i = 0; i < 1000; i++) {
            double[] q = axisAngle(random.nextGaussian(), random.nextGaussian(), random.nextGaussian(),
                random.nextDouble() * 360);
            OrientationFilter.quaternionToMatrix(q, m);
            OrientationFilter.matrixToQuaternion(m, back);
            assertTrue(angleBetweenDeg(q, back) < 1e-3);
        }
    }

    @Test
    public void firstSamplePassesStraightThrough() {
        OrientationFilter filter = new OrientationFilter(1.0, 3.0, 1.0);
        double[] q = axisAngle(1, 2, 3, 40);
        assertTrue(angleBetweenDeg(q, filter.filter(q, 0)) < 1e-9);
    }

    @Test
    public void heldStillJitterIsStronglyReduced() {
        OrientationFilter filter = new OrientationFilter(0.2, 3.0, 0.5); // compass-only tuning
        Random random = new Random(2);
        double[] truth = axisAngle(0.3, 1, 0.2, 70);
        double rawSq = 0;
        double outSq = 0;
        int n = 0;
        for (int i = 0; i < 500; i++) {
            double[] noise = axisAngle(random.nextGaussian(), random.nextGaussian(), random.nextGaussian(),
                random.nextGaussian() * 1.0);
            double[] raw = multiply(truth, noise);
            double[] out = filter.filter(raw, i * STEP_NS);
            if (i > 100) {
                rawSq += Math.pow(angleBetweenDeg(truth, raw), 2);
                outSq += Math.pow(angleBetweenDeg(truth, out), 2);
                n++;
            }
        }
        double rawRms = Math.sqrt(rawSq / n);
        double outRms = Math.sqrt(outSq / n);
        assertTrue("raw " + rawRms + " filtered " + outRms, outRms < rawRms * 0.35);
    }

    @Test
    public void followsSteadyPanWithSmallLag() {
        OrientationFilter filter = new OrientationFilter(1.0, 3.0, 1.0); // gyro tuning
        double degPerSec = 60;
        double[] out = null;
        double[] truth = null;
        for (int i = 0; i < 150; i++) {
            truth = axisAngle(0, 1, 0, degPerSec * i * STEP_NS * 1e-9);
            out = filter.filter(truth, i * STEP_NS);
        }
        assertTrue(angleBetweenDeg(truth, out) < 3.0);
    }

    @Test
    public void settlesQuicklyAfterAJump() {
        OrientationFilter filter = new OrientationFilter(1.0, 3.0, 1.0);
        double[] a = axisAngle(1, 0, 0, 0);
        double[] b = axisAngle(1, 0, 0, 30);
        long t = 0;
        for (int i = 0; i < 50; i++, t += STEP_NS) filter.filter(a, t);
        double[] out = null;
        for (int i = 0; i < 40; i++, t += STEP_NS) out = filter.filter(b, t); // 0.8 s
        assertTrue(angleBetweenDeg(b, out) < 0.5);
    }

    @Test
    public void snapsAfterAPause() {
        OrientationFilter filter = new OrientationFilter(1.0, 3.0, 1.0);
        filter.filter(axisAngle(0, 0, 1, 0), 0);
        double[] later = axisAngle(0, 0, 1, 120);
        assertTrue(angleBetweenDeg(later, filter.filter(later, 2_000_000_000L)) < 1e-9);
    }

    @Test
    public void keepsTrackingWhenTimestampsAreBroken() {
        OrientationFilter filter = new OrientationFilter(1.0, 3.0, 1.0);
        filter.filter(axisAngle(0, 0, 1, 0), 0);
        double[] target = axisAngle(0, 0, 1, 20);
        double[] out = null;
        for (int i = 0; i < 100; i++) out = filter.filter(target, 0);
        assertTrue(angleBetweenDeg(target, out) < 0.5);
    }

    @Test
    public void quaternionSignFlipDoesNotCauseASwing() {
        OrientationFilter filter = new OrientationFilter(1.0, 3.0, 1.0);
        double[] q = axisAngle(1, 1, 0, 50);
        double[] negated = {-q[0], -q[1], -q[2], -q[3]};
        filter.filter(q, 0);
        for (int i = 1; i < 20; i++) {
            double[] out = filter.filter(i % 2 == 0 ? q : negated, i * STEP_NS);
            assertTrue(angleBetweenDeg(q, out) < 1e-6);
        }
        assertEquals(1.0, Math.abs(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]), 1e-9);
    }
}
