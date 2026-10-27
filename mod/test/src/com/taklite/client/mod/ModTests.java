package com.taklite.client.mod;

import java.lang.reflect.Field;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unit tests for the map-side classes: tile URL templates, the received-marker
 * classifier, stable marker uids and size formatting. Plain JVM; no Android.
 */
public class ModTests {

    private static int failures;

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + name);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        templates();
        classifier();
        uids();
        sizes();
        System.out.println(failures == 0 ? "ModTests: ALL OK" : "ModTests: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---- TemplateTileSource ----

    private static void templates() {
        check("quadkey matches Bing's documented example (3,3,5) -> 213",
                TemplateTileSource.quadKey(3, 3, 5).equals("213"));
        check("quadkey at zoom 0 is empty", TemplateTileSource.quadKey(0, 0, 0).isEmpty());
        check("{q} expands", TemplateTileSource.expand("https://h/t/a{q}?g=1", 3, 3, 5)
                .equals("https://h/t/a213?g=1"));
        check("{z}/{x}/{y} expands", TemplateTileSource.expand("https://h/{z}/{x}/{y}.png", 10, 5, 7)
                .equals("https://h/10/5/7.png"));
        check("{-y} flips rows (TMS)", TemplateTileSource.expand("https://h/{z}/{x}/{-y}.png", 2, 1, 0)
                .equals("https://h/2/1/3.png"));
        String t = "https://{s:a,b,c}.h/{z}/{x}/{y}.png";
        check("{s:...} rotates over servers",
                TemplateTileSource.expand(t, 10, 0, 7).startsWith("https://b.")
                        && TemplateTileSource.expand(t, 10, 1, 7).startsWith("https://c.")
                        && TemplateTileSource.expand(t, 10, 2, 7).startsWith("https://a."));
        check("{s:...} with one server", TemplateTileSource.expand("https://{s:x}.h/{z}", 1, 0, 0)
                .equals("https://x.h/1"));
        check("valid: z/x/y", TemplateTileSource.isValidTemplate("https://h/{z}/{x}/{y}"));
        check("valid: z/x/-y", TemplateTileSource.isValidTemplate("https://h/{z}/{x}/{-y}"));
        check("valid: q alone", TemplateTileSource.isValidTemplate("https://h/{q}"));
        check("invalid: no y", !TemplateTileSource.isValidTemplate("https://h/{z}/{x}"));
        check("tile size guess 512 from /512/", CustomMapSources.guessTileSize("https://h/styles/x/512/{z}/{x}/{y}.png") == 512);
        check("tile size guess 256 otherwise", CustomMapSources.guessTileSize("https://h/{z}/{x}/{y}.png") == 256);
        check("server address normalised", "http://192.168.1.10:8080".equals(CustomMapSources.normalizeServer("192.168.1.10:8080/styles.json")));
    }

    // ---- RemoteMarkers ----

    private static Pattern pattern(String field) throws Exception {
        Field f = RemoteMarkers.class.getDeclaredField(field);
        f.setAccessible(true);
        return (Pattern) f.get(null);
    }

    private static boolean marker(String cot) throws Exception {
        Matcher m = Pattern.compile("<event\\b[^>]*>", Pattern.DOTALL).matcher(cot);
        if (!m.find()) return false;
        String tag = m.group();
        String type = RemoteMarkers.attr(tag, pattern("TYPE"));
        String how = type.startsWith("a-") ? RemoteMarkers.attr(tag, pattern("HOW")) : null;
        return RemoteMarkers.isMarker(type, how, cot);
    }

    private static void classifier() throws Exception {
        check("TAK Aware hand-placed marker (no usericon) is a marker",
                marker("<event version='2.0' uid='m1' type='a-h-G' how='h-g-i-g-o'><point/><detail><contact callsign='X'/></detail></event>"));
        check("double-quoted attributes", marker("<event version=\"2.0\" uid=\"m2\" type=\"a-f-G\" how=\"h-e\"><detail/></event>"));
        check("archived machine-placed item is a marker", marker("<event uid='m3' type='a-n-G' how='m-g'><detail><archive/></detail></event>"));
        check("item with usericon is a marker", marker("<event uid='m4' type='a-u-G' how='m-g'><detail><usericon iconsetpath='x/y.png'/></detail></event>"));
        check("ATAK position report is not a marker", !marker("<event uid='p1' type='a-f-G-U-C' how='m-g'><detail><takv device='x'/><__group name='Cyan'/></detail></event>"));
        check("hand-placed position report with takv is not a marker", !marker("<event uid='p2' type='a-f-G-U-C' how='h-e'><detail><takv/></detail></event>"));
        check("chat is not a marker", !marker("<event uid='c1' type='b-t-f' how='h-g-i-g-o'><detail/></event>"));
        check("drawing is not a marker", !marker("<event uid='d1' type='u-d-r' how='h-e'><detail/></event>"));
        check("uid attribute read", "abc-1".equals(RemoteMarkers.attr("<event uid='abc-1' type='a'>", pattern("UID"))));
        check("no false match on xuid", RemoteMarkers.attr("<event xuid='q'>", pattern("UID")) == null);
        check("stand-in iconset has no slash (no file lookup)",
                RemoteMarkers.STAND_IN_ICONSET.indexOf('/') < 0 && !RemoteMarkers.STAND_IN_ICONSET.isEmpty());
        RemoteMarkers.observe(null);
        RemoteMarkers.observe("not xml at all <<<");
        RemoteMarkers.observe("<event uid='z' type='a-h-G' how='h-e'><detail/></event>");
        check("observe never throws on odd input", true);
    }

    // ---- MarkerIds ----

    private static void uids() {
        String a = MarkerIds.stableUid("ANDROID-dev1", "Hostile-1700000000000");
        check("same device and marker -> same uid", a.equals(MarkerIds.stableUid("ANDROID-dev1", "Hostile-1700000000000")));
        check("different marker -> different uid", !a.equals(MarkerIds.stableUid("ANDROID-dev1", "Hostile-1700000000001")));
        check("different device -> different uid", !a.equals(MarkerIds.stableUid("ANDROID-dev2", "Hostile-1700000000000")));
        check("uid is a UUID", a.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        check("no device uid still works", MarkerIds.stableUid(null, "k").length() == 36);
    }

    // ---- ModUtil ----

    private static void sizes() {
        String[][] cases = {{"0", "0 KB"}, {"500", "1 KB"}, {"204800", "200 KB"}, {"4404019", "4.2 MB"},
                {"38797312", "37 MB"}, {"629145600", "600 MB"}, {"1610612736", "1.5 GB"}, {"17179869184", "16 GB"}};
        for (String[] c : cases) {
            String got = ModUtil.formatSize(Long.parseLong(c[0]));
            check("formatSize(" + c[0] + ") = " + got, got.equals(c[1]));
        }
        check("formatSize clamps negatives", ModUtil.formatSize(-5).equals("0 KB"));
    }
}
