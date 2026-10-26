package com.taklite.client.mod;

import android.util.Log;

import com.taklite.client.tak.TakUser;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells markers from people in CoT received from other TAK clients.
 *
 * Stock TAK Lite files an incoming item as a map marker only if its FIRST message
 * carries a {@code <usericon>}; everything else becomes a person (team list, no
 * delete). TAK Aware sends a new marker without one, so its markers became people
 * and were drawn as plain team dots. This classifies the raw CoT instead, then
 * gives a marker without an icon path a stand-in one, which makes TAK Lite's own
 * code file it as a marker and draw its type's MIL-STD-2525 symbol.
 */
public final class RemoteMarkers {

    private static final String TAG = "RemoteMarkers";
    private static final int REMEMBER = 2000;
    private static final Pattern EVENT_TAG = Pattern.compile("<event\\b[^>]*>", Pattern.DOTALL);

    /** uid -> whether its latest CoT described a marker. */
    private static final Map<String, Boolean> markers = Collections.synchronizedMap(
            new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > REMEMBER;
                }
            });

    private RemoteMarkers() {
    }

    /** Hook: start of TakManager.onCotReceived, with the raw CoT. */
    public static void observe(String cot) {
        if (cot == null) return;
        Matcher m = EVENT_TAG.matcher(cot);
        if (!m.find()) return;
        String tag = m.group();
        String uid = attr(tag, "uid");
        String type = attr(tag, "type");
        if (uid == null || type == null) return;
        boolean marker = isMarker(type, attr(tag, "how"), cot);
        Boolean before = markers.put(uid, marker);
        if (before == null || before != marker) {
            Log.d(TAG, uid + " type=" + type + " how=" + attr(tag, "how") + " -> " + (marker ? "marker" : "contact"));
        }
    }

    /**
     * A hand-placed map item rather than a person: an atom type ("a-..."), no
     * client identity ({@code <takv>} / {@code <__group>}, which PLIs carry), and
     * placed by hand ({@code how="h-..."}) or flagged as kept ({@code <archive/>})
     * or given a symbol ({@code <usericon>}).
     */
    static boolean isMarker(String type, String how, String cot) {
        if (!type.startsWith("a-")) return false;
        if (cot.contains("<takv") || cot.contains("<__group")) return false;
        return (how != null && how.startsWith("h-")) || cot.contains("<archive") || cot.contains("<usericon");
    }

    /** Hook: TakManager.processCoT, right after CotParser.parse. */
    public static void adjust(TakUser user) {
        if (user == null || user.isDrone()) return;
        String uid = user.getUid();
        if (uid == null || !Boolean.TRUE.equals(markers.get(uid))) return;
        String path = user.getIconsetPath();
        if (path == null || path.isEmpty()) {
            // No iconset of this name exists, so TAK Lite falls back to the CoT type's 2525 symbol.
            user.setIconsetPath("mod/" + user.getCotType());
        }
    }

    /** Attribute from a start tag; TAK clients use either quote style. */
    static String attr(String tag, String name) {
        Matcher m = Pattern.compile("\\s" + name + "\\s*=\\s*(['\"])(.*?)\\1", Pattern.DOTALL).matcher(tag);
        return m.find() ? m.group(2) : null;
    }
}
