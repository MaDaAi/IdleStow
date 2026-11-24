import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
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
 * which uses a 256 x 256 viewBox) and writes:
 *   <outDir>/png/icon-<size>.png   for 16, 24, 32, 48, 64, 128, 256, 512
 *   <outDir>/IdleStow.ico          multi-resolution ICO (16..256, PNG-compressed entries)
 *
 * Usage (from the repo root):
 *   java scripts/branding/BrandingGenerator.java assets/branding
 */
public class BrandingGenerator {

    static final int[] PNG_SIZES = {16, 24, 32, 48, 64, 128, 256, 512};
    static final int[] ICO_SIZES = {16, 24, 32, 48, 64, 128, 256};

    // Theme colors
    static final Color ACCENT = new Color(0x60A5FA);
    static final Color DARK = new Color(0x0F172A);

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

    /** Renders the mark at the given pixel size. Geometry is authored in the 256-unit SVG space. */
    static BufferedImage render(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.scale(size / 256.0, size / 256.0);

            // Storage box: <rect x="44" y="48" width="168" height="160" rx="30">
            RoundRectangle2D box = new RoundRectangle2D.Double(44, 48, 168, 160, 60, 60);
            g.setColor(ACCENT);
            g.fill(box);

            // Dimmed lower half, clipped to the box (antialiased via Area intersection)
            Area lowerHalf = new Area(box);
            lowerHalf.intersect(new Area(new Rectangle2D.Double(44, 128, 168, 80)));
            g.setColor(new Color(DARK.getRed(), DARK.getGreen(), DARK.getBlue(), (int) Math.round(0.22 * 255)));
            g.fill(lowerHalf);

            // Compression chevron: M82 92 L128 138 L174 92, stroke 30, round caps and joins
            Path2D chevron = new Path2D.Double();
            chevron.moveTo(82, 92);
            chevron.lineTo(128, 138);
            chevron.lineTo(174, 92);
            g.setColor(DARK);
            g.setStroke(new BasicStroke(30f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(chevron);
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
