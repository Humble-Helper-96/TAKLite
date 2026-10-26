package com.taklite.client.mod.imports;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny read-only XML tree for KML, GPX, CoT and data package manifests.
 * Element names keep only their local part ("gx:Track" becomes "Track") so
 * callers don't depend on how a file spells its namespace prefixes.
 */
final class XmlNode {

    /** Guards against pathological files; real KML/GPX/CoT is far below this. */
    private static final int MAX_NODES = 2_000_000;

    final String name;
    final Map<String, String> attrs;
    final List<XmlNode> children = new ArrayList<>();
    private final StringBuilder text = new StringBuilder();

    private XmlNode(String name, Map<String, String> attrs) {
        this.name = name;
        this.attrs = attrs;
    }

    /** An element with no attributes or children, e.g. a stand-in for a missing {@code <detail/>}. */
    static XmlNode empty(String name) {
        return new XmlNode(name, Collections.<String, String>emptyMap());
    }

    static XmlNode parse(InputStream input) throws IOException {
        BufferedInputStream in = new BufferedInputStream(input, 16384);
        rejectEntityDeclarations(in);
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(in, null);
            List<XmlNode> stack = new ArrayList<>();
            XmlNode root = null;
            int count = 0;
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event == XmlPullParser.START_TAG) {
                    if (++count > MAX_NODES) throw new IOException("XML file too large");
                    Map<String, String> attrs = parser.getAttributeCount() == 0
                            ? Collections.<String, String>emptyMap() : new HashMap<String, String>();
                    for (int i = 0; i < parser.getAttributeCount(); i++) {
                        attrs.put(localName(parser.getAttributeName(i)), parser.getAttributeValue(i));
                    }
                    XmlNode node = new XmlNode(localName(parser.getName()), attrs);
                    if (stack.isEmpty()) {
                        root = node;
                    } else {
                        stack.get(stack.size() - 1).children.add(node);
                    }
                    stack.add(node);
                } else if (event == XmlPullParser.END_TAG) {
                    stack.remove(stack.size() - 1);
                } else if ((event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) && !stack.isEmpty()) {
                    stack.get(stack.size() - 1).text.append(parser.getText());
                }
            }
            if (root == null) throw new IOException("Empty XML document");
            return root;
        } catch (XmlPullParserException e) {
            throw new IOException("Not valid XML: " + e.getMessage(), e);
        }
    }

    /**
     * Entity declarations can expand exponentially ("billion laughs"), and none of the
     * formats we read use them, so refuse any file that declares one near the top.
     */
    private static void rejectEntityDeclarations(BufferedInputStream in) throws IOException {
        byte[] head = new byte[8192];
        in.mark(head.length);
        int n = 0;
        int r;
        while (n < head.length && (r = in.read(head, n, head.length - n)) > 0) n += r;
        in.reset();
        if (new String(head, 0, n, StandardCharsets.ISO_8859_1).contains("<!ENTITY")) {
            throw new IOException("XML entity declarations are not supported");
        }
    }

    private static String localName(String name) {
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    String text() {
        return text.toString().trim();
    }

    String attr(String key) {
        return attrs.get(key);
    }

    /** First direct child with this name, or null. */
    XmlNode child(String childName) {
        for (XmlNode c : children) {
            if (c.name.equals(childName)) return c;
        }
        return null;
    }

    /** Text of the first direct child with this name, or null. */
    String childText(String childName) {
        XmlNode c = child(childName);
        return c == null ? null : c.text();
    }

    List<XmlNode> children(String childName) {
        List<XmlNode> out = new ArrayList<>();
        for (XmlNode c : children) {
            if (c.name.equals(childName)) out.add(c);
        }
        return out;
    }

    /** All descendants (depth-first, document order) with this name. */
    List<XmlNode> descendants(String descendantName) {
        List<XmlNode> out = new ArrayList<>();
        collect(descendantName, out);
        return out;
    }

    private void collect(String descendantName, List<XmlNode> out) {
        for (XmlNode c : children) {
            if (c.name.equals(descendantName)) out.add(c);
            c.collect(descendantName, out);
        }
    }
}
