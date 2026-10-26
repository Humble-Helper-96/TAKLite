package com.taklite.client.mod;

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.util.MapTileIndex;

/**
 * Raster XYZ tile source built from a URL template such as
 * {@code https://host/styles/basic/512/{z}/{x}/{y}.png} (TileServer GL).
 *
 * Supported placeholders: {z}, {x}, {y} and {-y} (TMS row order).
 */
final class TemplateTileSource extends OnlineTileSourceBase {

    private final String template;

    TemplateTileSource(String cacheName, int minZoom, int maxZoom, int tileSize, String template) {
        super(cacheName, minZoom, maxZoom, tileSize, extensionOf(template), new String[]{template});
        this.template = template;
    }

    @Override
    public String getTileURLString(long index) {
        return expand(template, MapTileIndex.getZoom(index), MapTileIndex.getX(index), MapTileIndex.getY(index));
    }

    static String expand(String template, int z, int x, int y) {
        return template
                .replace("{z}", Integer.toString(z))
                .replace("{x}", Integer.toString(x))
                .replace("{-y}", Integer.toString((1 << z) - 1 - y))
                .replace("{y}", Integer.toString(y));
    }

    /** File extension osmdroid uses when caching tiles on disk. */
    private static String extensionOf(String template) {
        String path = template;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot > slash && path.length() - dot <= 5) return path.substring(dot);
        return ".png";
    }
}
