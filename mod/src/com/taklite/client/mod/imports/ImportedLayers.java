package com.taklite.client.mod.imports;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.taklite.client.MainActivity;
import com.taklite.client.mod.DarkDialog;

import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.FolderOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.Polyline;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Draws imported data on the main map, and the dialogs to inspect and manage it. */
final class ImportedLayers {

    private static final String TAG = "ImportedLayers";

    private static WeakReference<MainActivity> activityRef = new WeakReference<>(null);
    private static final List<Overlay> layers = new ArrayList<>();
    private static int drawnVersion = -1;
    private static int loadGeneration;

    private ImportedLayers() {
    }

    static void attach(MainActivity activity) {
        activityRef = new WeakReference<>(activity);
        layers.clear();
        drawnVersion = -1;
        reload();
    }

    static void onResume(MainActivity activity) {
        activityRef = new WeakReference<>(activity);
        if (drawnVersion != Imports.version()) reload();
        String zoomId = Imports.pendingZoomId;
        if (zoomId != null) {
            Imports.pendingZoomId = null;
            ImportStore.Entry e = Imports.store().get(zoomId);
            MapView map = mapView(activity);
            if (e != null && map != null) map.post(() -> zoomTo(activity, map, e));
        }
    }

    /** TAK Lite's map screen if it is showing, for toasts. */
    static Activity visibleActivity() {
        MainActivity a = activityRef.get();
        return a != null && a.hasWindowFocus() ? a : null;
    }

    private static MapView mapView(Activity activity) {
        int id = activity.getResources().getIdentifier("mapView", "id", activity.getPackageName());
        View v = id == 0 ? null : activity.findViewById(id);
        return v instanceof MapView ? (MapView) v : null;
    }

    /** Re-reads every visible import and redraws it. Main thread. */
    static void reload() {
        final MainActivity activity = activityRef.get();
        if (activity == null || Imports.store() == null) return;
        final int version = Imports.version();
        final int generation = ++loadGeneration;
        new Thread(() -> {
            List<ImportStore.Entry> entries = new ArrayList<>();
            List<List<Feature>> features = new ArrayList<>();
            for (ImportStore.Entry e : Imports.store().list()) {
                if (!e.visible) continue;
                try {
                    features.add(Imports.store().features(e.id));
                    entries.add(e);
                } catch (IOException ex) {
                    Log.e(TAG, "Could not load import " + e.name, ex);
                }
            }
            new Handler(Looper.getMainLooper()).post(() -> {
                if (generation != loadGeneration || activity.isFinishing()) return;
                MapView map = mapView(activity);
                if (map == null) return;
                map.getOverlays().removeAll(layers);
                layers.clear();
                // Just above the map's tap handler (index 0), below markers and teammates.
                int index = Math.min(1, map.getOverlays().size());
                for (int i = 0; i < entries.size(); i++) {
                    FolderOverlay folder = build(activity, map, entries.get(i), features.get(i));
                    map.getOverlays().add(index++, folder);
                    layers.add(folder);
                }
                drawnVersion = version;
                map.invalidate();
            });
        }, "ImportedLayersLoad").start();
    }

    private static FolderOverlay build(MainActivity activity, MapView map, ImportStore.Entry entry, List<Feature> features) {
        FolderOverlay folder = new FolderOverlay();
        float density = activity.getResources().getDisplayMetrics().density;
        // Lines and areas first so markers draw on top of them.
        for (Feature f : features) {
            if (f.kind == Feature.POINT) continue;
            List<GeoPoint> pts = new ArrayList<>(f.pointCount());
            for (int i = 0; i < f.pointCount(); i++) pts.add(new GeoPoint(f.lat(i), f.lon(i)));
            if (f.kind == Feature.LINE) {
                Polyline line = new Polyline(map);
                line.setPoints(pts);
                line.getOutlinePaint().setColor(f.strokeColor);
                line.getOutlinePaint().setStrokeWidth(Math.max(1f, f.strokeWidth) * density);
                line.getOutlinePaint().setStrokeCap(Paint.Cap.ROUND);
                line.setInfoWindow(null);
                line.setOnClickListener((p, mv, at) -> {
                    showInfo(activity, entry, f);
                    return true;
                });
                folder.add(line);
            } else {
                Polygon area = new Polygon(map);
                area.setPoints(pts);
                area.getFillPaint().setColor(f.fillColor);
                area.getOutlinePaint().setColor(f.strokeColor);
                area.getOutlinePaint().setStrokeWidth(Math.max(1f, f.strokeWidth) * density);
                area.setInfoWindow(null);
                area.setOnClickListener((p, mv, at) -> {
                    showInfo(activity, entry, f);
                    return true;
                });
                folder.add(area);
            }
        }
        for (Feature f : features) {
            if (f.kind != Feature.POINT) continue;
            Marker marker = new Marker(map);
            marker.setPosition(new GeoPoint(f.lat(0), f.lon(0)));
            marker.setIcon(icon(activity, f));
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            marker.setTitle(f.name);
            marker.setInfoWindow(null);
            marker.setOnMarkerClickListener((m, mv) -> {
                showInfo(activity, entry, f);
                return true;
            });
            folder.add(marker);
        }
        return folder;
    }

    /** CoT units use TAK Lite's own MIL-STD-2525 / iconset symbols; other points get a dot. */
    private static Drawable icon(MainActivity activity, Feature f) {
        String label = f.image != null ? "📷 " + f.name : f.name;
        if (f.iconsetPath != null || (f.cotType != null && f.cotType.startsWith("a-"))) {
            try {
                // A path that resolves to no iconset makes TAK Lite fall back to the CoT type's symbol.
                String iconset = f.iconsetPath != null ? f.iconsetPath : "mod/" + f.cotType;
                Drawable d = activity.modMarkerIcon(label, f.cotType == null ? "" : f.cotType, iconset);
                if (d != null) return d;
            } catch (RuntimeException e) {
                Log.w(TAG, "TAK Lite icon failed for " + f.cotType, e);
            }
        }
        return dotIcon(activity, label, f.pointColor);
    }

    /** Coloured dot with a label underneath, in the style of TAK Lite's teammate markers. */
    private static Drawable dotIcon(Context context, String label, int color) {
        float d = context.getResources().getDisplayMetrics().density;
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTextSize(10 * d);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        Paint.FontMetrics fm = text.getFontMetrics();
        int pad = Math.round(3 * d);
        int dot = Math.round(14 * d);
        boolean hasLabel = label != null && !label.isEmpty();
        int labelW = hasLabel ? Math.round(text.measureText(label)) + pad * 2 : 0;
        int labelH = hasLabel ? Math.round(fm.descent - fm.ascent) + pad : 0;
        int w = Math.max(dot, labelW);
        int h = dot + (hasLabel ? pad + labelH : 0);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(color | 0xFF000000);
        c.drawCircle(w / 2f, dot / 2f, dot / 2f - d, fill);
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(1.5f * d);
        ring.setColor(0xFF000000);
        c.drawCircle(w / 2f, dot / 2f, dot / 2f - d, ring);
        if (hasLabel) {
            float top = dot + pad;
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(Color.argb(140, 0, 0, 0));
            float left = (w - labelW) / 2f;
            c.drawRoundRect(left, top, left + labelW, top + labelH, pad, pad, bg);
            c.drawText(label, left + pad, top + pad / 2f - fm.ascent, text);
        }
        return new BitmapDrawable(context.getResources(), bmp);
    }

    // ==================== Dialogs ====================

    private static void showInfo(MainActivity activity, ImportStore.Entry entry, Feature f) {
        DarkDialog d = new DarkDialog(activity, f.name.isEmpty() ? "Untitled" : f.name);
        if (f.image != null) {
            Bitmap photo = decodeSampled(new File(Imports.store().dir(entry.id), f.image), 1200);
            if (photo != null) {
                ImageView image = new ImageView(activity);
                image.setImageBitmap(photo);
                image.setAdjustViewBounds(true);
                LinearLayout.LayoutParams lp = DarkDialog.matchWrap();
                lp.bottomMargin = d.dp(12);
                d.content.addView(image, lp);
            }
        }
        d.addLabel(describe(f));
        if (!f.remarks.isEmpty()) d.addText(f.remarks);
        if (f.kind == Feature.POINT) {
            d.addText(String.format(Locale.US, "%.5f, %.5f", f.lat(0), f.lon(0)));
        }
        d.addLabel("From \"" + entry.name + "\" (" + entry.origin + ")");
        d.addAction("CLOSE", DarkDialog.COLOR_LABEL, v -> d.dismiss());
        d.show();
    }

    private static String describe(Feature f) {
        if (f.kind == Feature.POINT) {
            return f.cotType != null && !f.cotType.isEmpty() ? "Point · " + f.cotType : "Point";
        }
        double meters = 0;
        for (int i = 1; i < f.pointCount(); i++) {
            meters += new GeoPoint(f.lat(i - 1), f.lon(i - 1)).distanceToAsDouble(new GeoPoint(f.lat(i), f.lon(i)));
        }
        String length = meters >= 1000
                ? String.format(Locale.US, "%.2f km (%.2f mi)", meters / 1000, meters / 1609.344)
                : String.format(Locale.US, "%.0f m (%.0f ft)", meters, meters * 3.28084);
        return (f.kind == Feature.LINE ? "Line · " : "Area · perimeter ") + length;
    }

    private static Bitmap decodeSampled(File file, int maxSide) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0) return null;
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getPath(), opts);
    }

    /** The Data Packages menu (toolbar button): imports with visibility checkboxes, server, file. */
    static void showManager(final Context context) {
        final DarkDialog d = new DarkDialog(context, "Data Packages");
        List<ImportStore.Entry> entries = Imports.list();
        if (entries.isEmpty()) {
            d.addText("Nothing imported yet. Data packages sent to you over TAK appear here "
                    + "automatically; you can also download them from the server or import a file.");
        }
        for (final ImportStore.Entry e : entries) {
            addEntryRow(context, d, e);
        }
        d.addDivider();
        d.addButton("Download from Server", DarkDialog.COLOR_ACCENT, v -> {
            d.dismiss();
            showServerPackages(context);
        });
        d.addButton("Import File (KML, KMZ, GPX, CoT, .zip)", DarkDialog.COLOR_FIELD, v -> {
            d.dismiss();
            context.startActivity(new Intent(context, ImportActivity.class));
        });
        d.addAction("CLOSE", DarkDialog.COLOR_LABEL, v -> d.dismiss());
        d.show();
    }

    /** Checkbox toggles visibility; tapping the name opens zoom / show / delete. */
    private static void addEntryRow(final Context context, final DarkDialog d, final ImportStore.Entry e) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        final android.widget.CheckBox box = new android.widget.CheckBox(context);
        box.setChecked(e.visible);
        box.setButtonTintList(android.content.res.ColorStateList.valueOf(DarkDialog.COLOR_ACCENT));
        box.setContentDescription("Show " + e.name + " on the map");
        box.setOnCheckedChangeListener((b, checked) -> {
            try {
                Imports.setVisible(e.id, checked);
                e.visible = checked;
            } catch (IOException ex) {
                b.setChecked(!checked);
                Toast.makeText(context, "Could not save: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        row.addView(box);

        String date = DateFormat.getMediumDateFormat(context).format(new Date(e.importedAt));
        LinearLayout text = DarkDialog.row(context, e.name,
                e.origin + " \u00b7 " + e.count + " items \u00b7 " + date, Color.WHITE, v -> {
                    d.dismiss();
                    showEntry(context, e);
                });
        row.addView(text, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.ImageButton delete = new android.widget.ImageButton(context);
        delete.setImageResource(android.R.drawable.ic_menu_delete);
        delete.setImageTintList(android.content.res.ColorStateList.valueOf(DarkDialog.COLOR_DANGER));
        delete.setBackgroundColor(Color.TRANSPARENT);
        delete.setContentDescription("Delete " + e.name);
        delete.setOnClickListener(v -> {
            d.dismiss();
            confirmDelete(context, e, () -> showManager(context));
        });
        row.addView(delete, new LinearLayout.LayoutParams(d.dp(44), d.dp(44)));
        d.content.addView(row, DarkDialog.matchWrap());
    }

    /** Lists the server's data packages; tap one to download and import it. */
    private static void showServerPackages(final Context context) {
        final DarkDialog d = new DarkDialog(context, "Server Data Packages");
        final android.widget.TextView status = d.addLabel("Asking the server\u2026");
        final LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        d.content.addView(list, DarkDialog.matchWrap());
        d.addAction("BACK", DarkDialog.COLOR_LABEL, v -> {
            d.dismiss();
            showManager(context);
        });
        d.show();

        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            List<DataPackages.ServerPackage> found = null;
            String error = null;
            try {
                found = DataPackages.queryServer(context);
            } catch (IOException | RuntimeException ex) {
                Log.e(TAG, "Server query failed", ex);
                error = ex.getMessage();
            }
            final List<DataPackages.ServerPackage> packages = found;
            final String failure = error;
            main.post(() -> {
                if (!d.dialog.isShowing()) return;
                if (packages == null) {
                    status.setTextColor(DarkDialog.COLOR_DANGER);
                    status.setText("Could not get the list: " + failure);
                    return;
                }
                status.setText(packages.isEmpty() ? "The server has no data packages you can see."
                        : "Tap a package to download it and add it to the map.");
                for (final DataPackages.ServerPackage p : packages) {
                    boolean have = DataPackages.isImported(context, p.hash);
                    StringBuilder sub = new StringBuilder(formatSize(p.size));
                    if (!p.submitter.isEmpty()) sub.append(" \u00b7 ").append(p.submitter);
                    if (p.submitted.length() >= 10) sub.append(" \u00b7 ").append(p.submitted.substring(0, 10));
                    if (have) sub.append(" \u00b7 on this phone");
                    final android.widget.TextView[] title = new android.widget.TextView[1];
                    LinearLayout row = DarkDialog.row(context, have ? p.name + "  \u2713" : p.name, sub.toString(),
                            have ? DarkDialog.COLOR_ACCENT : Color.WHITE, v -> download(context, p, title[0]));
                    title[0] = (android.widget.TextView) row.getChildAt(0);
                    list.addView(row, DarkDialog.matchWrap());
                }
            });
        }, "ServerPackageQuery").start();
    }

    private static void download(final Context context, final DataPackages.ServerPackage p,
                                 final android.widget.TextView title) {
        title.setText(p.name + "  (downloading\u2026)");
        title.setTextColor(DarkDialog.COLOR_LABEL);
        final Handler main = new Handler(Looper.getMainLooper());
        try {
            DataPackages.importFromServer(context, p, (entry, error) -> main.post(() -> {
                if (entry != null) {
                    title.setText(p.name + "  \u2713");
                    title.setTextColor(DarkDialog.COLOR_ACCENT);
                    Toast.makeText(context, "\"" + entry.name + "\": " + entry.count + " items added to the map.",
                            Toast.LENGTH_LONG).show();
                } else {
                    title.setText(p.name + "  (failed)");
                    title.setTextColor(DarkDialog.COLOR_DANGER);
                    Toast.makeText(context, "Could not download " + p.name + ": " + error, Toast.LENGTH_LONG).show();
                }
            }));
        } catch (IllegalStateException ex) {
            Toast.makeText(context, ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) return "size unknown";
        if (bytes < 1024 * 1024) return Math.max(1, bytes / 1024) + " KB";
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static void showEntry(final Context context, final ImportStore.Entry e) {
        final DarkDialog d = new DarkDialog(context, e.name);
        d.addText(e.origin + "\n" + e.count + " items, imported "
                + DateFormat.getMediumDateFormat(context).format(new Date(e.importedAt)) + " "
                + DateFormat.getTimeFormat(context).format(new Date(e.importedAt)));
        d.addButton("Zoom To", DarkDialog.COLOR_ACCENT, v -> {
            d.dismiss();
            try {
                if (!e.visible) Imports.setVisible(e.id, true);
            } catch (IOException ex) {
                Log.e(TAG, "Could not show " + e.name, ex);
            }
            if (context instanceof MainActivity) {
                MapView map = mapView((MainActivity) context);
                if (map != null) zoomTo((MainActivity) context, map, e);
            } else {
                // Opened from Settings: go back to the map, which zooms when it resumes.
                Imports.pendingZoomId = e.id;
                if (context instanceof Activity) ((Activity) context).finish();
            }
        });
        d.addButton(e.visible ? "Hide on Map" : "Show on Map", DarkDialog.COLOR_FIELD, v -> {
            try {
                Imports.setVisible(e.id, !e.visible);
            } catch (IOException ex) {
                Toast.makeText(context, "Could not save: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            }
            d.dismiss();
            showManager(context);
        });
        d.addAction("DELETE", DarkDialog.COLOR_DANGER, v -> {
            d.dismiss();
            confirmDelete(context, e, () -> showEntry(context, e));
        });
        d.addActionSpacer();
        d.addAction("BACK", DarkDialog.COLOR_LABEL, v -> {
            d.dismiss();
            showManager(context);
        });
        d.show();
    }

    /** {@code back} reopens whichever dialog the delete was started from. */
    private static void confirmDelete(final Context context, final ImportStore.Entry e, final Runnable back) {
        final DarkDialog d = new DarkDialog(context, "Delete \"" + e.name + "\"?");
        d.addText("Removes its " + e.count + " items from the map and this phone.");
        d.addAction("CANCEL", DarkDialog.COLOR_LABEL, v -> {
            d.dismiss();
            back.run();
        });
        d.addAction("DELETE", DarkDialog.COLOR_DANGER, v -> {
            try {
                Imports.delete(e.id);
            } catch (IOException ex) {
                Toast.makeText(context, "Could not delete: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            }
            d.dismiss();
            showManager(context);
        });
        d.show();
    }

    private static void zoomTo(MainActivity activity, MapView map, ImportStore.Entry e) {
        activity.modStopFollowing();
        if (e.north - e.south < 1e-4 && e.east - e.west < 1e-4) {
            map.getController().setZoom(Math.max(map.getZoomLevelDouble(), 16.0));
            map.getController().animateTo(new GeoPoint((e.north + e.south) / 2, (e.east + e.west) / 2));
        } else {
            map.zoomToBoundingBox(new BoundingBox(e.north, e.east, e.south, e.west).increaseByScale(1.15f), true);
        }
    }
}
