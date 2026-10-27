# Simple Waypoints (Fabric, Minecraft 26.3, client-side)

Colored waypoint labels (name + distance) PLUS a vertical beam at each waypoint, both visible through walls.

Controls
- B (rebind in Controls) -> drop a waypoint at your position
- /wp add <name>
- /wp add <name> <x> <y> <z>
- /wp remove <name>
- /wp color <name> <color>   (red green blue yellow pink cyan orange white, hex like ff8800, or chroma = rainbow)
- /wp expire <name> <seconds> (0 = permanent)
- /wp list
- /wp clear
Saved to .minecraft/config/simple-waypoints.json

## Get the jar (no software to install)
1. Create a free GitHub account and a NEW empty repository.
2. Upload ALL files from this folder to it (drag and drop in the browser works).
   Make sure the hidden ".github" folder is included.
3. Open the repo's "Actions" tab -> "Build mod jar" -> wait for the green check.
4. Open the finished run -> "Artifacts" -> download "simple-waypoints-jar" -> unzip.
5. Put simple-waypoints-1.0.0.jar in .minecraft/mods together with Fabric API for 26.3
   (and Fabric Loader 0.19.3+, Java 25).

If the build fails, open the failed step, copy the red error lines and send them to Claude.

If the build fails and the error mentions BeamRenderer: delete src/main/java/com/example/waypoints/BeamRenderer.java
and the line `BeamRenderer.init();` in WaypointsMod.java, then rebuild (you keep the labels, lose the beam).
