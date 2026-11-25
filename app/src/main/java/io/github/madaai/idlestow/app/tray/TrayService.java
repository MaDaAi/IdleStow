package io.github.madaai.idlestow.app.tray;

import io.github.madaai.idlestow.app.AppContext;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

/**
 * System tray integration (java.awt.SystemTray). The right-click menu is a themed JavaFX popup ({@link TrayMenu})
 * instead of the native AWT menu, so it matches the application. When the tray is not supported the application
 * falls back to "exit on close" and always keeps its window.
 */
public final class TrayService {
    private static final Logger LOG = LoggerFactory.getLogger(TrayService.class);

    private final Runnable openWindow;
    private final TrayMenu menu;
    private TrayIcon icon;

    public TrayService(AppContext ctx, Runnable openWindow, Runnable exit) {
        this.openWindow = openWindow;
        this.menu = new TrayMenu(ctx, openWindow, exit);
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
            icon = new TrayIcon(image(), "IdleStow - Idle files. Reclaimed space.");
            icon.setImageAutoSize(true);
            icon.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseReleased(MouseEvent e) {
                    if (e.isPopupTrigger() || e.getButton() == MouseEvent.BUTTON3) {
                        // Tray icon mouse events carry absolute screen coordinates.
                        double x = e.getXOnScreen();
                        double y = e.getYOnScreen();
                        Platform.runLater(() -> menu.showAt(x, y));
                    } else if (e.getButton() == MouseEvent.BUTTON1) {
                        Platform.runLater(() -> {
                            menu.hide();
                            openWindow.run();
                        });
                    }
                }
            });
            SystemTray.getSystemTray().add(icon);
            return true;
        } catch (AWTException | RuntimeException | UnsatisfiedLinkError e) {
            LOG.warn("System tray unavailable: {}", e.toString());
            icon = null;
            return false;
        }
    }

    public void remove() {
        Platform.runLater(menu::dispose);
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
        // Minimal fallback in the logo colors: navy tile with a mint page.
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x16213A));
        g.fillRoundRect(1, 1, 30, 30, 10, 10);
        g.setColor(new Color(0x6FE0BE));
        g.fillRoundRect(9, 6, 14, 20, 3, 3);
        g.dispose();
        return img;
    }
}
