package io.github.madaai.idlestow.app.ui;

import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Logo / icon resources. Defensive: when the branding PNGs are missing a drawn vector logo is used. */
public final class Branding {
    private static final int[] SIZES = {16, 32, 64, 256};

    private Branding() { }

    public static boolean hasPng(int size) {
        return Branding.class.getResource("/branding/icon-" + size + ".png") != null;
    }

    private static final java.util.Map<Integer, Image> CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static List<Image> windowIconCache;

    /** Decoded once and shared; the image is never mutated so every logo view can use the same instance. */
    public static Image image(int size) {
        Image cached = CACHE.get(size);
        if (cached != null) return cached;
        Image img = load(size);
        if (img != null) CACHE.put(size, img);
        return img;
    }

    private static Image load(int size) {
        try (InputStream in = Branding.class.getResourceAsStream("/branding/icon-" + size + ".png")) {
            return in == null ? null : new Image(in);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Logo for the sidebar / dialogs. */
    public static Node logo(double size) {
        Image img = image(64);
        if (img != null && !img.isError()) {
            ImageView v = new ImageView(img);
            v.setFitWidth(size);
            v.setFitHeight(size);
            v.setPreserveRatio(true);
            v.setSmooth(true);
            return v;
        }
        return Icons.LOGO.node(size, "icon-accent");
    }

    public static synchronized List<Image> windowIcons() {
        if (windowIconCache == null) windowIconCache = loadWindowIcons();
        return windowIconCache;
    }

    private static List<Image> loadWindowIcons() {
        List<Image> list = new ArrayList<>();
        for (int s : SIZES) {
            if (hasPng(s)) {
                Image i = image(s);
                if (i != null && !i.isError()) list.add(i);
            }
        }
        return list;
    }
}
