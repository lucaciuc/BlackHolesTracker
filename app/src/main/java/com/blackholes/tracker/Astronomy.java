package com.blackholes.tracker;

/**
 * Positional astronomy for fixed (non-moving) sky targets. Deliberately free
 * of Android dependencies so it can be unit-tested on the JVM.
 *
 * Accuracy is on the order of an arcminute, far finer than a phone compass
 * can resolve.
 */
final class Astronomy {

    /** Length of one sidereal day (one full turn of the sky) in milliseconds. */
    static final double SIDEREAL_DAY_MS = 86_164_090.5;

    /**
     * Geometric altitude at which a point source appears exactly on the
     * horizon (standard 34' of refraction). Used for rise/set times.
     */
    static final double HORIZON_ALT_DEG = -0.5667;

    private static final double J2000_JD = 2_451_545.0;

    private Astronomy() {
    }

    static double julianDate(long utcMillis) {
        return utcMillis / 86_400_000.0 + 2_440_587.5;
    }

    static double greenwichSiderealTimeDeg(double jd) {
        double d = jd - J2000_JD;
        double t = d / 36_525.0;
        double gmst = 280.46061837
            + 360.98564736629 * d
            + 0.000387933 * t * t
            - (t * t * t) / 38_710_000.0;
        return normalizeDegrees(gmst);
    }

    static double localSiderealTimeDeg(double jd, double lonDeg) {
        return normalizeDegrees(greenwichSiderealTimeDeg(jd) + lonDeg);
    }

    /**
     * Precesses J2000 coordinates to the given date using the annual-rate
     * approximation, which is good to well under a tenth of a degree for
     * several decades either side of 2000.
     *
     * @return {raDeg, decDeg} of date
     */
    static double[] precessFromJ2000(double raDeg, double decDeg, double jd) {
        double years = (jd - J2000_JD) / 365.25;
        double raRad = Math.toRadians(raDeg);
        double decRad = Math.toRadians(decDeg);
        double deltaRaSec = 3.075 + 1.336 * Math.sin(raRad) * Math.tan(decRad);
        double deltaDecArcsec = 20.04 * Math.cos(raRad);
        return new double[]{
            normalizeDegrees(raDeg + deltaRaSec * years * (15.0 / 3600.0)),
            decDeg + deltaDecArcsec * years / 3600.0
        };
    }

    /**
     * Converts equatorial coordinates (of date) to geometric altitude and
     * azimuth, with azimuth measured from true north through east.
     *
     * @return {altDeg, azDeg}
     */
    static double[] equatorialToHorizontal(double raDeg, double decDeg, double latDeg, double lstDeg) {
        double ha = Math.toRadians(lstDeg - raDeg);
        double dec = Math.toRadians(decDeg);
        double lat = Math.toRadians(latDeg);
        double east = -Math.cos(dec) * Math.sin(ha);
        double north = Math.sin(dec) * Math.cos(lat) - Math.cos(dec) * Math.cos(ha) * Math.sin(lat);
        double up = Math.sin(dec) * Math.sin(lat) + Math.cos(dec) * Math.cos(ha) * Math.cos(lat);
        double altDeg = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, up))));
        double azDeg = normalizeDegrees(Math.toDegrees(Math.atan2(east, north)));
        return new double[]{altDeg, azDeg};
    }

    /**
     * Apparent (refracted) altitude and azimuth of a J2000 position for an
     * observer at the given place and instant.
     *
     * @return {altDeg, azDeg}
     */
    static double[] apparentHorizontal(double raJ2000, double decJ2000, double latDeg, double lonDeg, long utcMillis) {
        double jd = julianDate(utcMillis);
        double[] eq = precessFromJ2000(raJ2000, decJ2000, jd);
        double[] horizontal = equatorialToHorizontal(eq[0], eq[1], latDeg, localSiderealTimeDeg(jd, lonDeg));
        horizontal[0] += refractionDeg(horizontal[0]);
        return horizontal;
    }

    /**
     * Atmospheric refraction (Saemundsson's formula) for a true altitude.
     * Cut off well below the horizon, where the formula diverges.
     */
    static double refractionDeg(double trueAltDeg) {
        if (trueAltDeg <= -5.0) {
            return 0.0;
        }
        double arcmin = 1.02 / Math.tan(Math.toRadians(trueAltDeg + 10.3 / (trueAltDeg + 5.11)));
        return Math.max(0.0, arcmin / 60.0);
    }

    /** Highest geometric altitude the target reaches (its upper culmination). */
    static double culminationAltitudeDeg(double decDeg, double latDeg) {
        return 90.0 - Math.abs(latDeg - decDeg);
    }

    /** When a target next crosses the horizon, if it ever does. */
    static final class RiseSet {
        static final int RISES_AND_SETS = 0;
        static final int ALWAYS_UP = 1;
        static final int NEVER_UP = 2;

        final int kind;
        /** UTC millis; only meaningful when kind == RISES_AND_SETS. */
        final long nextRiseMillis;
        /** UTC millis; only meaningful when kind == RISES_AND_SETS. */
        final long nextSetMillis;

        RiseSet(int kind, long nextRiseMillis, long nextSetMillis) {
            this.kind = kind;
            this.nextRiseMillis = nextRiseMillis;
            this.nextSetMillis = nextSetMillis;
        }
    }

    static RiseSet riseSet(double raJ2000, double decJ2000, double latDeg, double lonDeg, long nowMillis) {
        double jd = julianDate(nowMillis);
        double[] eq = precessFromJ2000(raJ2000, decJ2000, jd);
        double lat = Math.toRadians(latDeg);
        double dec = Math.toRadians(eq[1]);
        double num = Math.sin(Math.toRadians(HORIZON_ALT_DEG)) - Math.sin(lat) * Math.sin(dec);
        double den = Math.cos(lat) * Math.cos(dec);
        if (Math.abs(den) < 1e-12) {
            // Observer at a pole: the target circles at constant altitude.
            return new RiseSet(num < 0 ? RiseSet.ALWAYS_UP : RiseSet.NEVER_UP, 0, 0);
        }
        double cosH0 = num / den;
        if (cosH0 <= -1.0) {
            return new RiseSet(RiseSet.ALWAYS_UP, 0, 0);
        }
        if (cosH0 >= 1.0) {
            return new RiseSet(RiseSet.NEVER_UP, 0, 0);
        }
        // The target is up while its hour angle is within +/-h0; hour angle
        // grows by 360 degrees per sidereal day.
        double h0 = Math.toDegrees(Math.acos(cosH0));
        double hourAngle = normalizeDegrees(localSiderealTimeDeg(jd, lonDeg) - eq[0]);
        double toRiseDeg = normalizeDegrees(-h0 - hourAngle);
        double toSetDeg = normalizeDegrees(h0 - hourAngle);
        return new RiseSet(RiseSet.RISES_AND_SETS,
            nowMillis + Math.round(toRiseDeg / 360.0 * SIDEREAL_DAY_MS),
            nowMillis + Math.round(toSetDeg / 360.0 * SIDEREAL_DAY_MS));
    }

    static double normalizeDegrees(double deg) {
        double r = deg % 360.0;
        return r < 0 ? r + 360.0 : r;
    }
}
