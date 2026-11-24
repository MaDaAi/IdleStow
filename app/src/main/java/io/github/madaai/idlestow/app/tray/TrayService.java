package io.github.madaai.idlestow.app.tray;

import io.github.madaai.idlestow.app.AppContext;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

/**
 * System tray integration (java.awt.SystemTray). When the tray is not supported the application falls back to
 * "exit on close" and always keeps its window.
 */
public final class TrayService {
    private static final Logger LOG = LoggerFactory.getLogger(TrayService.class);

    private final AppContext ctx;
    private final Runnable openWindow;
    private final Runnable exit;
    private TrayIcon icon;

    public TrayService(AppContext ctx, Runnable openWindow, Runnable exit) {
        this.ctx = ctx;
        this.openWindow = openWindow;
        this.exit = exit;
    }

    public static boolean supported() {
        try {
            return SystemTray.isSupported();
        } catch (RuntimeException | Error e) {
            return false;
        }
    }

    /** Adds the tray icon; returns false when the platform has no tray. */
    public boolean install() {
        if (!supported()) return false;
        try {
            PopupMenu menu = new PopupMenu();
            menu.add(item("Open IdleStow", () -> Platform.runLater(openWindow)));
            menu.addSeparator();
            menu.add(item("Pause 1 hour", () -> ctx.engine().pauseAll(Duration.ofHours(1))));
            menu.add(item("Resume", () -> ctx.engine().resumeAll()));
            menu.add(item("Scan now", () -> ctx.engine().scan(null)));
            menu.addSeparator();
            menu.add(item("Exit", exit));
            icon = new TrayIcon(image(), "IdleStow - Idle files. Reclaimed space.", menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> Platform.runLater(openWindow));
            SystemTray.getSystemTray().add(icon);
            return true;
        } catch (AWTException | RuntimeException | UnsatisfiedLinkError e) {
            LOG.warn("System tray unavailable: {}", e.toString());
            icon = null;
            return false;
        }
    }

    public void remove() {
        if (icon != null) {
            try {
                SystemTray.getSystemTray().remove(icon);
            } catch (RuntimeException ignored) {
                // shutting down
            }
            icon = null;
        }
    }

    public void notify(String title, String message, boolean error) {
        if (icon != null) {
            icon.displayMessage(title, message, error ? TrayIcon.MessageType.ERROR : TrayIcon.MessageType.INFO);
        }
    }

    private static MenuItem item(String text, Runnable action) {
        MenuItem mi = new MenuItem(text);
        mi.addActionListener(e -> action.run());
        return mi;
    }

    private static Image image() {
        for (String n : new String[]{"/branding/icon-32.png", "/branding/icon-64.png"}) {
            try (InputStream in = TrayService.class.getResourceAsStream(n)) {
                if (in != null) {
                    BufferedImage img = ImageIO.read(in);
                    if (img != null) return img;
                }
            } catch (IOException | RuntimeException ignored) {
                // fall back to the drawn icon
            }
        }
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x1E293B));
        g.fillRoundRect(1, 1, 30, 30, 8, 8);
        g.setColor(new Color(0x60A5FA));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        g.drawString("I", 11, 24);
        g.dispose();
        return img;
    }
}
