package com.blackholes.tracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SkyProjectionTest {

    private static final double F = 1000;
    private static final double CX = 540;
    private static final double CY = 1200;

    // Device-to-world matrices (columns = device X, Y, Z axes in East/North/Up).
    /** Upright, back camera facing north. */
    private static final float[] FACING_NORTH = {1, 0, 0, 0, 0, -1, 0, 1, 0};
    /** Upright, back camera facing east. */
    private static final float[] FACING_EAST = {0, 0, -1, -1, 0, 0, 0, 1, 0};
    /** Camera pointing straight up, top of the phone toward north. */
    private static final float[] FACING_ZENITH = {-1, 0, 0, 0, 1, 0, 0, 0, -1};
    /** Facing north but twisted 90 degrees: the phone's top points west. */
    private static final float[] FACING_NORTH_ROLLED = {0, -1, 0, 0, 0, -1, 1, 0, 0};

    private final double[] cam = new double[3];
    private final double[] screen = new double[2];

    private SkyProjection projection(float[] rotation, double azRotation, double pitchOffset) {
        SkyProjection p = new SkyProjection();
        p.set(rotation, azRotation, pitchOffset, F, CX, CY);
        return p;
    }

    @Test
    public void targetStraightAheadIsAtTheCenter() {
        assertTrue(projection(FACING_NORTH, 0, 0).project(0, 0, cam, screen));
        assertEquals(CX, screen[0], 1e-6);
        assertEquals(CY, screen[1], 1e-6);

        assertTrue(projection(FACING_EAST, 0, 0).project(0, 90, cam, screen));
        assertEquals(CX, screen[0], 1e-6);
        assertEquals(CY, screen[1], 1e-6);
    }

    @Test
    public void offsetsFollowPinholeGeometry() {
        SkyProjection p = projection(FACING_NORTH, 0, 0);
        assertTrue(p.project(0, 10, cam, screen));
        assertEquals(CX + F * Math.tan(Math.toRadians(10)), screen[0], 1e-6);
        assertEquals(CY, screen[1], 1e-6);

        assertTrue(p.project(10, 0, cam, screen));
        assertEquals(CX, screen[0], 1e-6);
        assertEquals(CY - F * Math.tan(Math.toRadians(10)), screen[1], 1e-6);
    }

    @Test
    public void targetsBehindTheCameraAreRejected() {
        assertFalse(projection(FACING_NORTH, 0, 0).project(0, 180, cam, screen));
    }

    @Test
    public void noGimbalLockAtTheZenith() {
        SkyProjection p = projection(FACING_ZENITH, 0, 0);
        assertTrue(p.project(90, 123, cam, screen));
        assertEquals(CX, screen[0], 1e-6);
        assertEquals(CY, screen[1], 1e-6);
        // 10 degrees below the zenith toward north = toward the top of the phone.
        assertTrue(p.project(80, 0, cam, screen));
        assertEquals(CX, screen[0], 1e-6);
        assertEquals(CY - F * Math.tan(Math.toRadians(10)), screen[1], 1e-6);
        double[] pointing = new double[2];
        p.pointing(pointing);
        assertEquals(90, pointing[0], 1e-6);
    }

    @Test
    public void phoneTwistRotatesTheOverlay() {
        // With the top of the phone pointing west, east is toward the bottom of the screen...
        SkyProjection p = projection(FACING_NORTH_ROLLED, 0, 0);
        assertTrue(p.project(0, 10, cam, screen));
        assertEquals(CX, screen[0], 1e-6);
        assertTrue(screen[1] > CY);
        // and the sky is to the right.
        assertTrue(p.project(10, 0, cam, screen));
        assertTrue(screen[0] > CX);
        assertEquals(CY, screen[1], 1e-6);
    }

    @Test
    public void declinationShiftsTrueAzimuth() {
        // Sensor frame north is magnetic north, 8 degrees east of true north.
        SkyProjection p = projection(FACING_NORTH, 8, 0);
        assertTrue(p.project(0, 8, cam, screen));
        assertEquals(CX, screen[0], 1e-6);
        double[] pointing = new double[2];
        p.pointing(pointing);
        assertEquals(8, pointing[1], 1e-6);
    }

    @Test
    public void pitchOffsetTiltsTheView() {
        SkyProjection p = projection(FACING_NORTH, 0, 15);
        assertTrue(p.project(15, 0, cam, screen));
        assertEquals(CX, screen[0], 1e-6);
        assertEquals(CY, screen[1], 1e-6);
        double[] pointing = new double[2];
        p.pointing(pointing);
        assertEquals(15, pointing[0], 1e-6);
        assertEquals(0, pointing[1], 1e-6);
    }

    @Test
    public void upVectorDefinesTheHorizon() {
        SkyProjection p = projection(FACING_NORTH, 0, 0);
        p.upInCamera(cam);
        assertEquals(0, cam[0], 1e-6);
        assertEquals(1, cam[1], 1e-6);
        assertEquals(0, cam[2], 1e-6);
    }
}
