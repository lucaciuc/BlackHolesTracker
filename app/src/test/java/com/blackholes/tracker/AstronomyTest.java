package com.blackholes.tracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AstronomyTest {

    @Test
    public void julianDateOfUnixEpoch() {
        assertEquals(2_440_587.5, Astronomy.julianDate(0L), 1e-9);
    }

    @Test
    public void siderealTimeMatchesMeeusExample12a() {
        // 1987 April 10, 0h UT: GMST = 13h10m46.3668s
        assertEquals(197.693195, Astronomy.greenwichSiderealTimeDeg(2_446_895.5), 1e-4);
    }

    @Test
    public void horizontalMatchesMeeusExample13b() {
        // Venus from Washington (38d55'17"N, 77d03'56"W), 1987 April 10, 19:21 UT.
        // Meeus: h = 15.1249 deg, A = 68.0337 deg west of south.
        double jd = 2_446_896.30625;
        double lst = Astronomy.localSiderealTimeDeg(jd, -77.065556);
        double[] h = Astronomy.equatorialToHorizontal(347.3193375, -6.719892, 38.921389, lst);
        assertEquals(15.1249, h[0], 0.01);
        assertEquals(68.0337 + 180.0, h[1], 0.01);
    }

    @Test
    public void precessionMatchesMeeusExample21bWithoutProperMotion() {
        // Theta Persei to 2028 Nov 13.19; Meeus' result minus the star's proper motion.
        double[] eq = Astronomy.precessFromJ2000(41.054063, 49.227750, 2_462_088.69);
        assertEquals(41.5431, eq[0], 0.01);
        assertEquals(49.3492, eq[1], 0.01);
    }

    @Test
    public void polarisAltitudeIsCloseToLatitude() {
        long now = 1_790_000_000_000L;
        for (double lat : new double[]{10, 30, 45, 60}) {
            double[] h = Astronomy.apparentHorizontal(37.95456, 89.26411, lat, 26.1, now);
            assertEquals(lat, h[0], 0.8);
        }
    }

    @Test
    public void refractionIsHalfADegreeAtTheHorizonAndFadesWithAltitude() {
        assertEquals(0.48, Astronomy.refractionDeg(0), 0.03);
        assertTrue(Astronomy.refractionDeg(10) < Astronomy.refractionDeg(0));
        assertTrue(Astronomy.refractionDeg(45) < 0.02);
        assertEquals(0.0, Astronomy.refractionDeg(-6), 0.0);
        for (double alt = -4.9; alt <= 90; alt += 0.1) {
            assertTrue(Astronomy.refractionDeg(alt) >= 0);
        }
    }

    @Test
    public void circumpolarAndNeverRisingTargets() {
        long now = 1_790_000_000_000L;
        assertEquals(Astronomy.RiseSet.ALWAYS_UP,
            Astronomy.riseSet(0, 60, 45, 0, now).kind);
        assertEquals(Astronomy.RiseSet.NEVER_UP,
            Astronomy.riseSet(0, -60, 45, 0, now).kind);
        // GRS 1124-683 can never be seen from mid-northern latitudes.
        assertEquals(Astronomy.RiseSet.NEVER_UP,
            Astronomy.riseSet(171.61081, -68.67580, 44.4, 26.1, now).kind);
        assertTrue(Astronomy.culminationAltitudeDeg(-68.67580, 44.4) < 0);
    }

    @Test
    public void riseAndSetTimesLandOnTheHorizon() {
        double ra = 266.41684;
        double dec = -29.00781; // Sagittarius A*
        double lat = 44.4;
        double lon = 26.1;
        for (long now = 1_790_000_000_000L; now < 1_790_000_000_000L + 86_400_000L; now += 3_600_000L) {
            Astronomy.RiseSet rs = Astronomy.riseSet(ra, dec, lat, lon, now);
            assertEquals(Astronomy.RiseSet.RISES_AND_SETS, rs.kind);
            assertTrue(rs.nextRiseMillis >= now && rs.nextRiseMillis - now <= Astronomy.SIDEREAL_DAY_MS);
            assertTrue(rs.nextSetMillis >= now && rs.nextSetMillis - now <= Astronomy.SIDEREAL_DAY_MS);

            assertEquals(0.0, Astronomy.apparentHorizontal(ra, dec, lat, lon, rs.nextRiseMillis)[0], 0.05);
            assertEquals(0.0, Astronomy.apparentHorizontal(ra, dec, lat, lon, rs.nextSetMillis)[0], 0.05);

            // Whichever event comes first tells us whether it's up right now.
            boolean upNow = Astronomy.apparentHorizontal(ra, dec, lat, lon, now)[0] > 0;
            assertEquals(upNow, rs.nextSetMillis < rs.nextRiseMillis);
        }
    }

    @Test
    public void peakAltitudeIsReachedBetweenRiseAndSet() {
        double ra = 266.41684;
        double dec = -29.00781;
        double lat = 44.4;
        double lon = 26.1;
        long now = 1_790_000_000_000L;
        Astronomy.RiseSet rs = Astronomy.riseSet(ra, dec, lat, lon, now);
        long rise = rs.nextRiseMillis;
        long set = rs.nextSetMillis > rise ? rs.nextSetMillis : rs.nextSetMillis + (long) Astronomy.SIDEREAL_DAY_MS;
        double transitAlt = Astronomy.apparentHorizontal(ra, dec, lat, lon, (rise + set) / 2)[0];
        assertEquals(Astronomy.culminationAltitudeDeg(dec, lat), transitAlt, 0.2);
    }
}
