# TAK Lite: custom maps and data import

This adds your own raster map sources and data import (TAK data packages,
KML, KMZ, GPX, CoT) to TAK Lite 1.5.0. Map sources are built for
[TileServer GL](https://github.com/maptiler/tileserver-gl) but working with any
XYZ tile server, for example:

    https://host.com/styles/mapName/512/{z}/{x}/{y}.png

## Using it

- **Settings → Map Sources → Manage Map Sources** lists your sources.
  - **Add Map Source**: a name, the tile URL with `{z}`, `{x}`, `{y}` (or
    `{-y}` for TMS), min/max zoom and tile size. Leave tile size empty to
    auto-detect it: 512 if the URL contains `/512/` or `@2x`, otherwise 256.
    **Test** downloads one tile and shows what the server returned.
  - **Import from TileServer GL**: enter the server address (for example
    `http://192.168.1.10:8080`). It reads `/styles.json` and adds the styles
    you tick as `…/styles/<id>/512/{z}/{x}/{y}.png`.
  - Tap a source to edit or delete it.
- On the map, **tap** the layers button to cycle Street → Dark → Satellite →
  Topo → your sources, as before. **Long-press** it to pick a map from a list
  or open the source manager.

### Offline use

Every tile you view is saved on the phone. It keeps showing when the server is
out of reach, even after it goes stale (7 days for TileServer GL, which sends
no cache headers). Stale tiles are refreshed when the server can be reached.

- **Download visible area**: long-press the layers button while one of your
  own sources is showing. Choose a zoom range and you get a tile count and a
  rough size first. It downloads on 4 threads and skips tiles that are already
  saved and fresh, so running it again only fetches what's missing or stale.
  Keep the app open while it runs; the screen stays on. Built-in maps can't
  be downloaded because their providers' usage policies don't allow bulk
  downloads.
- **Cache size**: Settings → Map Sources → Manage → **Cache size** (300 MB to
  16 GB; the default is 600 MB). The cache is shared by all maps. When it's
  full, the oldest tiles are removed down to 5/6 of the limit. The limit is
  capped at 95% of the phone's free space. A lower limit takes effect the next
  time the map opens.

## Imported data: data packages, KML, KMZ, GPX, CoT

- **Data packages sent over TAK**: when an ATAK, iTAK or WinTAK user sends you
  a data package, TAK Lite downloads it from the TAK server with your client
  certificate, checks its SHA-256 and adds its contents to the map. You get a
  notification, plus a toast if the map is on screen. If the package names a
  server address the phone can't reach, it retries with the server configured
  in TAK Lite. A re-sent package replaces the earlier copy; it's skipped while
  that copy still exists. The limit is 200 MB.
- **Files**: Settings → Imported Data → Manage → **Import File**, or open or
  share a `.kml`, `.kmz`, `.gpx`, `.cot` or data package `.zip` to TAK Lite
  from a file manager, email or chat app.
- **What's drawn**:
  - CoT drawings with vertices (freehand, rectangles, routes) as lines, or as
    areas when closed or filled.
  - Circles and ellipses as areas.
  - CoT markers with TAK Lite's MIL-STD-2525 or iconset symbols.
  - Photos attached to markers, shown when you tap them.
  - KML Placemarks: points, lines, polygons, MultiGeometry and gx:Track, with
    their Style/StyleMap colours and widths.
  - GPX waypoints, routes and tracks, with Garmin, OsmAnd or GPS Track Editor
    colours.
- **Tap** any imported item for its name, notes, length and photo.
- **Manage** (Settings → Manage Imported Data, or long-press the layers button
  → Imported data): zoom to, hide/show or delete each import.

Imports are converted once and kept in the app's private storage. They are
drawn under markers and teammates.

Not handled: KML ground overlays, network links and custom icon images; map
tile packages (MBTiles, SQLite) inside data packages; sending data packages
from TAK Lite; the "received" acknowledgement ATAK can send back to the
sender. ATAK exports each drawing to KML twice (the shape plus a label point
called "… center"); the label point is skipped. Polygon fills are kept
translucent so they don't hide the map.

### Notes

- Plain `http://` is allowed so a server on a laptop hotspot works.
- TileServer GL draws PNG tiles only in the full version. `tileserver-gl-light`
  lists styles but answers 404 for `.png` tiles; **Test** shows this.
- If the phone is on a Wi-Fi network with no internet access (a laptop
  hotspot) and **Test** can't reach the server, turn off mobile data. Android
  may otherwise send traffic over the cellular network instead.
- The zoom level is adjusted when you switch between 256 px and 512 px sources,
  so the map keeps the same scale on screen.

## Building

TAK Lite's upstream repository has not published source since v1.1.2, so
`build.sh` starts from the official release APK (checksum-verified) and
applies this mod to it:

```
cd mod
./build.sh
# -> out/TAKLite-V1.5.0-custommaps.apk
```

It needs `bash`, `curl`, `unzip`, `python3`, `patch` and JDK 17+. The Android
SDK is not required. The script downloads apktool, jadx (for its d8 and apksig
libraries), osmdroid and a compile-time `android.jar` into `work/`. If
`ANDROID_HOME` is set, it uses your SDK's `android.jar` instead.

On first run it creates a signing key in `keystore/taklite-mod.p12`
(password `taklite`; override with `KEYSTORE`, `KEYSTORE_PASS` and
`KEY_ALIAS`). **Back it up.** Android only installs an update over an app
signed with the same key.

### Installing

The official app is signed with the upstream author's key, so Android won't
install this build over it. Uninstall TAK Lite first (you will need to enroll
with your TAK server again), then install the APK.

## How it works

| Path | What it is |
|---|---|
| `src/com/taklite/client/mod/` | Map sources, in Java: source storage, the osmdroid tile source and dialogs (`CustomMapSources`), area download (`AreaDownloader`) and cache size (`TileCache`) |
| `src/com/taklite/client/mod/imports/` | Imported data: parsers (`CotShapes`, `KmlParser`, `GpxParser`, `Importer` for zips and data packages), storage (`ImportStore`), map layers and dialogs (`ImportedLayers`), data package downloads (`DataPackages`) and `ImportActivity` |
| `patches/taklite-1.5.0.patch` | Changes to the decoded app: hooks in `MainActivity` (map-type cycling; `onCreate` before and after the map is created; `onResume`; new `modSetMapType`, `modMarkerIcon`, `modStopFollowing`), `SettingsActivity.onCreate`, `TakManager.onCotReceived` and `TakLocationService.onCreate`; the Settings buttons; the `ImportActivity` manifest entry; `usesCleartextTraffic`; and the version name |
| `stubs/` | Compile-time stand-ins for `MainActivity` and `SettingsActivity`; they are not packaged |
| `tools/SignApk.java` | Zip-aligns, signs and verifies with apksig |

The stock code is otherwise unchanged. Custom sources are stored as JSON in
the app's existing `TAKLitePrefs` (`custom_map_sources`) and use map-type
indexes after the four built-in maps, so the saved `map_type` preference keeps
working.

When upstream releases a new APK, update `APK_URL`/`APK_SHA256` in `build.sh`
and regenerate the patch against the newly decoded APK. The hooks are small,
so this is usually quick.
