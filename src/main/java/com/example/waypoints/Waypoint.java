package com.example.waypoints;

/**
 * color: 0xRRGGBB, or -1 for the rainbow "chroma" effect.
 * expiresAt: epoch millis when the waypoint disappears, 0 = permanent.
 */
public record Waypoint(String name, int x, int y, int z, int color, String dimension, long expiresAt) {
}
