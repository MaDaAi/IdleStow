import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.LinearGradientPaint;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws the IdleStow mark with Java2D (same geometry as assets/branding/logo.svg,
 * which uses a 512 x 512 viewBox) and writes:
 *   <outDir>/png/icon-<size>.png   for 16, 24, 32, 48, 64, 128, 256, 512
 *   <outDir>/IdleStow.ico          multi-resolution ICO (16..256, PNG-compressed entries)
 *
 * Usage (from the repo root):
 *   java scripts/branding/BrandingGenerator.java assets/branding
 */
public class BrandingGenerator {

    static final int[] PNG_SIZES = {16, 24, 32, 48, 64, 128, 256, 512};
    static final int[] ICO_SIZES = {16, 24, 32, 48, 64, 128, 256};

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: java BrandingGenerator.java <outDir>");
            System.exit(2);
        }
        Path outDir = Paths.get(args[0]);
        Path pngDir = outDir.resolve("png");
        Files.createDirectories(pngDir);

        Map<Integer, byte[]> pngs = new LinkedHashMap<>();
        for (int size : PNG_SIZES) {
            byte[] png = encodePng(render(size));
            pngs.put(size, png);
            Files.write(pngDir.resolve("icon-" + size + ".png"), png);
            System.out.println("wrote " + pngDir.resolve("icon-" + size + ".png") + " (" + png.length + " bytes)");
        }

        byte[] ico = buildIco(ICO_SIZES, pngs);
        Path icoPath = outDir.resolve("IdleStow.ico");
        Files.write(icoPath, ico);
        System.out.println("wrote " + icoPath + " (" + ico.length + " bytes, " + ICO_SIZES.length + " entries)");
    }

    /** The document outline (with the folded corner) in the 512-unit SVG space. */
    static Path2D paper() {
        Path2D p = new Path2D.Double();
        p.moveTo(142, 103);
        p.lineTo(279, 103);
        p.lineTo(371, 195);
        p.lineTo(371, 371);
        p.quadTo(371, 393, 349, 393);
        p.lineTo(142, 393);
        p.quadTo(120, 393, 120, 371);
        p.lineTo(120, 125);
        p.quadTo(120, 103, 142, 103);
        p.closePath();
        return p;
    }

    /**
     * The crescent moon cut out of the page. In logo.svg it is two arcs:
     * M263 224 A69 69 0 1 0 310 325 A59 59 0 0 1 263 224, i.e. a radius-69 disc minus a radius-59 disc
     * whose centres follow from the arc end points.
     */
    static Area crescent() {
        double c1x = 249.5773, c1y = 291.6818, r1 = 69;
        double c2x = 304.1388, c2y = 266.2919, r2 = 59;
        Area a = new Area(new Ellipse2D.Double(c1x - r1, c1y - r1, 2 * r1, 2 * r1));
        a.subtract(new Area(new Ellipse2D.Double(c2x - r2, c2y - r2, 2 * r2, 2 * r2)));
        return a;
    }

    /** Renders the mark (same geometry as assets/branding/logo.svg, 512 x 512 viewBox) at the given size. */
    static BufferedImage render(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
            // Small icons use a little more of the canvas so the page stays readable at 16 px.
            boolean small = size <= 32;
            double inset = small ? 6 : 16;
            g.scale(size / 512.0, size / 512.0);

            // Background tile: rounded square with a diagonal navy gradient.
            double w = 512 - 2 * inset;
            double radius = small ? 96 : 112;
            g.setPaint(new LinearGradientPaint(new Point2D.Double(inset, inset), new Point2D.Double(512 - inset, 512 - inset),
                    new float[]{0f, 1f}, new Color[]{new Color(0x202D45), new Color(0x101827)}));
            g.fill(new RoundRectangle2D.Double(inset, inset, w, w, radius * 2, radius * 2));

            if (small) {
                // Enlarge the page slightly around its centre for tiny sizes.
                g.translate(245.5, 248);
                g.scale(1.12, 1.12);
                g.translate(-245.5, -248);
            }

            // Soft drop shadow under the page.
            if (!small) {
                Path2D shadow = paper();
                shadow.transform(AffineTransform.getTranslateInstance(0, 9));
                g.setColor(new Color(0x0B, 0x12, 0x20, (int) Math.round(0.35 * 255)));
                g.fill(shadow);
            }

            // The page with the crescent cut out (mint gradient).
            Area page = new Area(paper());
            page.subtract(crescent());
            g.setPaint(new LinearGradientPaint(new Point2D.Double(120, 103), new Point2D.Double(120 + 0.8 * 251, 393),
                    new float[]{0f, 1f}, new Color[]{new Color(0xB0F5DB), new Color(0x48C7A5)}));
            g.fill(page);

            // Folded corner.
            Path2D fold = new Path2D.Double();
            fold.moveTo(279, 103);
            fold.lineTo(279, 174);
            fold.quadTo(279, 195, 300, 195);
            fold.lineTo(371, 195);
            fold.closePath();
            g.setColor(new Color(0x268F81));
            g.fill(fold);

            // Sparkle next to the moon (too small to read below 32 px).
            if (size > 32) {
                Path2D plus = new Path2D.Double();
                plus.moveTo(298, 228);
                plus.lineTo(298, 252);
                plus.moveTo(286, 240);
                plus.lineTo(310, 240);
                g.setColor(new Color(0x173B42));
                g.setStroke(new BasicStroke(9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(plus);
            }
        } finally {
            g.dispose();
        }
        return img;
    }

    static byte[] encodePng(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (!ImageIO.write(img, "png", baos)) {
            throw new IOException("no PNG writer available");
        }
        return baos.toByteArray();
    }

    /** ICONDIR + ICONDIRENTRY table (16 bytes each) + PNG payloads, all little-endian. */
    static byte[] buildIco(int[] sizes, Map<Integer, byte[]> pngs) {
        int count = sizes.length;
        int headerLen = 6 + 16 * count;
        int payloadLen = 0;
        for (int s : sizes) {
            payloadLen += pngs.get(s).length;
        }
        ByteBuffer bb = ByteBuffer.allocate(headerLen + payloadLen).order(ByteOrder.LITTLE_ENDIAN);

        // ICONDIR
        bb.putShort((short) 0);      // reserved
        bb.putShort((short) 1);      // type 1 = icon
        bb.putShort((short) count);  // image count

        // ICONDIRENTRY table
        int offset = headerLen;
        for (int s : sizes) {
            byte[] png = pngs.get(s);
            byte dim = (byte) (s >= 256 ? 0 : s);  // 0 means 256
            bb.put(dim);                 // width
            bb.put(dim);                 // height
            bb.put((byte) 0);            // color count (0 for >= 8bpp)
            bb.put((byte) 0);            // reserved
            bb.putShort((short) 1);      // color planes
            bb.putShort((short) 32);     // bits per pixel
            bb.putInt(png.length);       // size of image data
            bb.putInt(offset);           // offset from start of file
            offset += png.length;
        }

        // Image payloads (complete PNG files)
        for (int s : sizes) {
            bb.put(pngs.get(s));
        }
        return bb.array();
    }
}
