# Black Holes Tracker

An Android Augmented Reality (AR) compass that allows you to point your phone at the sky and track the real-time location of known Black Holes across the galaxy and beyond!

## Features
- Real-time 3D AR rendering of black hole accretion disks over the live camera feed.
- Tracks 11 black holes, sorted closest to farthest: Gaia BH1, Gaia BH3, V616 Monocerotis, Gaia BH2, Cygnus X-1, GRO J1655-40, GRS 1124-683, Sagittarius A*, M87*, Phoenix A* and TON 618.
- "Show all" or just the selected one. Tap any marker to select it.
- Off-screen arrow with how far to turn (left/right) and tilt (up/down), a center reticle that turns green with a haptic tick when you're on target, and a tinted ground below the horizon.
- Rise/set countdown, peak altitude, and "never rises at your latitude" for each target.
- Computes J2000 celestial coordinates with Earth precession and atmospheric refraction corrections.

## Pointing accuracy
- Uses the gyroscope-fused rotation-vector sensor when the phone has a gyroscope (falls back to accelerometer + magnetometer), smoothed by an adaptive filter that holds steady when you hold still and keeps up when you pan.
- Corrects the compass for magnetic declination at your location (magnetic north can be 10°+ away from true north).
- Places markers with a true camera projection matched to the camera's real field of view, so they line up with the image at any altitude and don't spin when you point straight up.
- If the compass is off, drag the view to nudge it into place; double-tap to reset. The app tells you when the compass needs a figure-8 calibration.

## Development
Unit tests for the astronomy, projection and filtering math run on the JVM:

```
./gradlew testDebugUnitTest
```
