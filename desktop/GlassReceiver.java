/*
 * GlassReceiver.java - GlassTransfer desktop receiver.
 * Single source file, Java 25, no dependencies.  Run with:  java GlassReceiver.java [options]
 *
 * Reads the QR frames shown by web/sender.html straight off the screen, appends the chunks to a
 * file, and drives the sender with the Up-arrow key through java.awt.Robot.  See README.md.
 */

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.SinglePixelPackedSampleModel;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

public final class GlassReceiver {

    static final DateTimeFormatter TF = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    static void log(String s) { System.out.println(LocalTime.now().format(TF) + "  " + s); }

    static String fmtBytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1048576) return String.format("%.1f KB", n / 1024.0);
        return String.format("%.2f MB", n / 1048576.0);
    }

    // =========================================================================================
    // Command line
    // =========================================================================================

    static final class Config {
        Rectangle region;
        double armSeconds = 10;
        Path outDir = Paths.get(".");
        boolean noKeys, stats, verbose, selfTest, once, vector;
        int drop;                                   // testing: ignore every Nth chunk the first time it is seen
        int corrupt;                                // testing: flip this many cells before decoding an image
        String mode = "raster";
        String decodeImage, captureOnce, dumpFail;
    }

    static void usage() {
        System.out.println(String.join("\n",
            "GlassTransfer receiver (Java 25, no dependencies)",
            "  java GlassReceiver.java [options]",
            "    --mode raster|qr      which carrier to read; raster is the colour code and the default",
            "    --region x,y,w,h      screen area to watch (logical px); default: drag a rectangle on screen",
            "    --arm-seconds N       seconds of continuous QR detection before the transfer starts (10)",
            "    --out-dir DIR         where received files are written (current directory)",
            "    --no-keys             never press keys (dry run)",
            "    --once                exit after the first file is received",
            "    --stats               add capture/decode timing to progress lines",
            "    --verbose             log every decoded frame",
            "    --dump-fail DIR       save frames that fail to decode during a transfer (max 20)",
            "    --drop N              testing: ignore every Nth chunk once, to exercise the refetch",
            "    --corrupt N           testing: with --decode-image, damage N cells before decoding",
            "    --capture-once f.png  save one capture of the region and exit",
            "    --decode-image f.png  decode a screenshot and print the frame header",
            "    --selftest            run offline decoder/protocol checks"));
    }

    static Config parseArgs(String[] args) {
        Config c = new Config();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--region" -> {
                    String[] p = args[++i].split(",");
                    c.region = new Rectangle(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()),
                                             Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()));
                }
                case "--arm-seconds" -> c.armSeconds = Double.parseDouble(args[++i]);
                case "--out-dir" -> c.outDir = Paths.get(args[++i]);
                case "--no-keys" -> c.noKeys = true;
                case "--once" -> c.once = true;
                case "--stats" -> c.stats = true;
                case "--verbose" -> c.verbose = true;
                case "--selftest" -> c.selfTest = true;
                case "--vector" -> c.vector = true;
                case "--decode-image" -> c.decodeImage = args[++i];
                case "--capture-once" -> c.captureOnce = args[++i];
                case "--dump-fail" -> c.dumpFail = args[++i];
                case "--drop" -> c.drop = Integer.parseInt(args[++i]);
                case "--corrupt" -> c.corrupt = Integer.parseInt(args[++i]);
                case "--mode" -> {
                    c.mode = args[++i].toLowerCase();
                    if (!c.mode.equals("raster") && !c.mode.equals("qr")) throw new IllegalArgumentException("mode must be raster or qr");
                }
                case "-h", "--help" -> { usage(); System.exit(0); }
                default -> throw new IllegalArgumentException("unknown option " + a);
            }
        }
        return c;
    }

    public static void main(String[] args) throws Exception {
        Config cfg;
        try {
            cfg = parseArgs(args);
        } catch (RuntimeException e) {
            System.err.println("Bad arguments: " + e.getMessage());
            usage();
            System.exit(2);
            return;
        }
        if (cfg.vector) { printVectors(); return; }
        if (cfg.selfTest) { System.exit(SelfTest.run() ? 0 : 1); return; }
        if (cfg.decodeImage != null) {
            System.setProperty("java.awt.headless", "true");
            System.exit(decodeImageFile(cfg) ? 0 : 1);
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("No display available; only --selftest and --decode-image work headless.");
            System.exit(1);
        }
        Robot robot = new Robot();
        robot.setAutoDelay(0);
        robot.setAutoWaitForIdle(false);

        Rectangle region = cfg.region != null ? cfg.region : SelectionOverlay.select();
        region = Screens.clampToDevice(region);
        if (region == null) {
            System.err.println("The region must lie on one monitor and be at least 20x20 logical pixels.");
            System.exit(1);
        }
        log("Region (logical px): " + region.x + "," + region.y + " " + region.width + "x" + region.height
            + " on " + Screens.describe(region));

        Capture cap = new Capture(robot, region);
        if (cfg.captureOnce != null) {
            BufferedImage bi = cap.grabImage();
            ImageIO.write(bi, "png", new File(cfg.captureOnce));
            log("Captured " + bi.getWidth() + "x" + bi.getHeight() + " device px (scale " + cap.scale + ") -> " + cfg.captureOnce);
            System.exit(0);
        }
        Border border = Border.create(region);
        Keys keys = new Keys(robot, !cfg.noKeys);
        new Transfer(cfg, cap, keys, border, carrierFor(cfg)).run();
    }

    /** Reference values for checking that another implementation agrees byte for byte. */
    static void printVectors() {
        int[] small = {1, 2, 3, 4, 5, 6};
        System.out.println("rs(1..6, nsym=4)   = " + Transfer.hex(bytesOf(RS.encode(small, 4))));
        int[] mid = new int[20];
        for (int i = 0; i < mid.length; i++) mid[i] = (i * 37 + 11) & 0xFF;
        System.out.println("rs(seq20, nsym=19) = " + Transfer.hex(bytesOf(RS.encode(mid, 19))));
        int cols = 477, rows = 398;
        Raster.Layout L = Raster.layout(cols, rows);
        System.out.printf("layout %dx%d: blocks %d blockTotal %d ecc %d dataLen %d bodyLen %d capacity %d%n",
            cols, rows, L.blocks(), L.blockTotal(), L.ecc(), L.dataLen(), L.bodyLen(), Raster.frameCapacity(cols, rows));
        byte[] frame = new byte[Raster.frameCapacity(cols, rows)];
        for (int i = 0; i < frame.length; i++) frame[i] = (byte) ((i * 37 + 11) & 0xFF);
        byte[] cells = Raster.encode(frame, cols, rows);
        java.util.zip.CRC32 c = new java.util.zip.CRC32();
        c.update(cells, 0, cells.length);
        System.out.printf("cells: %d, crc32 %08x%n", cells.length, (int) c.getValue());
    }

    static byte[] bytesOf(int[] v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    static Carrier carrierFor(Config cfg) {
        return cfg.mode.equals("qr") ? new QrCarrier() : new Raster();
    }

    static boolean decodeImageFile(Config cfg) throws IOException {
        BufferedImage bi = ImageIO.read(new File(cfg.decodeImage));
        if (bi == null) { System.err.println("Cannot read image " + cfg.decodeImage); return false; }
        Raster.DEBUG = cfg.verbose;
        Img img = Capture.toImg(bi);
        if (cfg.corrupt > 0) damage(img, cfg.corrupt);
        Carrier carrier = carrierFor(cfg);
        long t0 = System.nanoTime();
        Carrier.Out r = carrier.read(img);
        long t1 = System.nanoTime();
        System.out.printf("image %dx%d  carrier %s  result %s  (%.1f ms)%n", img.w(), img.h(), carrier.name(), r.kind(), (t1 - t0) / 1e6);
        if (r.bounds() != null) System.out.println("located at " + r.bounds().x + "," + r.bounds().y
            + " " + r.bounds().width + "x" + r.bounds().height + " device px");
        if (r.kind() != Kind.OK) return false;
        System.out.println("decoded: " + r.info() + ", " + r.bytes().length + " bytes");
        if (carrier instanceof Raster) {
            int e = Raster.checkEcc(img);
            System.out.println("error correction bytes: " + (e == 0 ? "agree with the data as captured"
                : e > 0 ? ("repaired " + e + " byte(s), so the capture is noisy but the parity is sound")
                : "cannot be reconciled with the data"));
        }
        Protocol.Frame f = Protocol.parse(r.bytes());
        if (f == null) { System.out.println("not a GlassTransfer frame"); return true; }
        System.out.println("frame: " + f);
        if (f.type() == Protocol.END) {
            Protocol.EndInfo e = Protocol.parseEnd(f);
            System.out.println(e == null ? "END payload malformed"
                : String.format("END: size %d crc32 %08x sha256 %s name \"%s\"", e.size(), e.crc(), Transfer.hex(e.sha()), e.name()));
        }
        Path out = Paths.get(cfg.decodeImage + ".payload.bin");
        Files.write(out, f.payload());
        System.out.println("payload written to " + out);
        return true;
    }

    /** Testing aid: overwrite n small square patches, as if cells had been misread. */
    static void damage(Img img, int n) {
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < n; i++) {
            int x = 8 + rnd.nextInt(Math.max(1, img.w() - 16)), y = 8 + rnd.nextInt(Math.max(1, img.h() - 16));
            int colour = Raster.colourOf(rnd.nextInt(Raster.SYMS));
            for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) img.px()[(y + dy) * img.w() + x + dx] = colour;
        }
        System.out.println("damaged " + n + " spots before decoding");
    }

    // =========================================================================================
    // Screens, capture
    // =========================================================================================

    /** A captured region: packed 0xRRGGBB pixels (alpha ignored), row major. */
    record Img(int[] px, int w, int h) {
        int at(int x, int y) { return px[y * w + x]; }

        BufferedImage toImage() {
            BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            bi.setRGB(0, 0, w, h, px, 0, w);
            return bi;
        }
    }

    record Gray(byte[] px, int w, int h) {
        int at(int x, int y) { return px[y * w + x] & 0xFF; }

        static Gray of(Img img) {
            byte[] out = new byte[img.w() * img.h()];
            int[] p = img.px();
            for (int i = 0; i < out.length; i++) out[i] = (byte) ((p[i] >>> 8) & 0xFF);   // green channel
            return new Gray(out, img.w(), img.h());
        }

        BufferedImage toImage() {
            BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
            bi.getRaster().setDataElements(0, 0, w, h, px);
            return bi;
        }
    }

    enum Kind { NONE, FAIL, OK }

    /** Whatever the receiver reads off the screen: a colour raster, or a QR symbol. */
    interface Carrier {
        record Out(Kind kind, boolean changed, byte[] bytes, String info, Rectangle bounds) { }

        Out read(Img img);
        void forget();
        String name();
    }

    /** Adapts the QR decoder to the carrier interface, so either can drive a transfer. */
    static final class QrCarrier implements Carrier {
        final QrDecoder dec = new QrDecoder();

        @Override public Out read(Img img) {
            QrDecoder.Result r = dec.process(Gray.of(img));
            Rectangle b = null;
            QrDecoder.Geometry g = dec.geo;
            if (g != null)
                b = new Rectangle((int) (g.ox() - 3 * g.px()), (int) (g.oy() - 3 * g.py()),
                                  (int) ((g.size() + 6) * g.px()), (int) ((g.size() + 6) * g.py()));
            if (r.kind() != Kind.OK) return new Out(r.kind(), r.changed(), null, "", b);
            QrDecoder.Decoded d = r.decoded();
            return new Out(Kind.OK, r.changed(), d.bytes(),
                String.format("QR v%d %c mask %d, repaired %d", d.version(), "LMQH".charAt(d.ecl()), d.mask(), d.corrected()), b);
        }

        @Override public void forget() { dec.geo = null; }

        @Override public String name() { return "qr"; }
    }

    static final class Screens {
        static GraphicsDevice deviceAt(Point p) {
            for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices())
                if (gd.getDefaultConfiguration().getBounds().contains(p)) return gd;
            return null;
        }

        static Rectangle clampToDevice(Rectangle r) {
            if (r == null) return null;
            GraphicsDevice gd = deviceAt(new Point(r.x + r.width / 2, r.y + r.height / 2));
            if (gd == null) return null;
            Rectangle i = r.intersection(gd.getDefaultConfiguration().getBounds());
            if (i.width < 20 || i.height < 20) return null;
            if (!i.equals(r)) log("Region clipped to the monitor: " + i);
            return i;
        }

        static String describe(Rectangle r) {
            GraphicsDevice gd = deviceAt(new Point(r.x + r.width / 2, r.y + r.height / 2));
            if (gd == null) return "?";
            GraphicsConfiguration gc = gd.getDefaultConfiguration();
            return gd.getIDstring() + " " + gc.getBounds().width + "x" + gc.getBounds().height
                + " at " + gc.getBounds().x + "," + gc.getBounds().y + " scale " + gc.getDefaultTransform().getScaleX();
        }
    }

    static final class Capture {
        final Robot robot;
        final Rectangle full;      // what the user selected
        Rectangle region;          // what we actually grab; shrunk to the code once it is located
        volatile double scale = 1;

        Capture(Robot robot, Rectangle region) { this.robot = robot; this.full = region; this.region = region; }

        /** Shrinks the grabbed area to the located code plus a margin. BitBlt cost scales with area. */
        void tighten(Rectangle b) {
            if (region != full || b == null) return;
            int m = Math.max(12, Math.min(b.width, b.height) / 16);
            int x0 = (int) Math.floor((b.x - m) / scale);
            int y0 = (int) Math.floor((b.y - m) / scale);
            int x1 = (int) Math.ceil((b.x + b.width + m) / scale);
            int y1 = (int) Math.ceil((b.y + b.height + m) / scale);
            Rectangle r = new Rectangle(full.x + x0, full.y + y0, x1 - x0, y1 - y0).intersection(full);
            if (r.width < 40 || r.height < 40 || (long) r.width * r.height >= (long) full.width * full.height) return;
            region = r;
            log(String.format("Capture area narrowed to %dx%d logical px (was %dx%d)", r.width, r.height, full.width, full.height));
        }

        void widen() {
            if (region == full) return;
            region = full;
            log("Capture area restored to the full selection");
        }

        /** Highest-resolution variant: native device pixels even on HiDPI monitors. */
        BufferedImage grabImage() {
            List<Image> vs = robot.createMultiResolutionScreenCapture(region).getResolutionVariants();
            Image im = vs.get(vs.size() - 1);
            BufferedImage bi;
            if (im instanceof BufferedImage b) bi = b;
            else {
                bi = new BufferedImage(im.getWidth(null), im.getHeight(null), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = bi.createGraphics();
                g.drawImage(im, 0, 0, null);
                g.dispose();
            }
            scale = bi.getWidth() / (double) region.width;
            return bi;
        }

        Img grab() { return toImg(grabImage()); }

        static Img toImg(BufferedImage bi) {
            int w = bi.getWidth(), h = bi.getHeight();
            int t = bi.getType();
            if ((t == BufferedImage.TYPE_INT_RGB || t == BufferedImage.TYPE_INT_ARGB || t == BufferedImage.TYPE_INT_ARGB_PRE)
                && bi.getRaster().getDataBuffer() instanceof DataBufferInt db
                && bi.getRaster().getSampleModel() instanceof SinglePixelPackedSampleModel sm
                && bi.getRaster().getSampleModelTranslateX() == 0 && bi.getRaster().getSampleModelTranslateY() == 0) {
                int[] data = db.getData();
                int stride = sm.getScanlineStride(), off = db.getOffset();
                if (stride == w && off == 0 && data.length == w * h) return new Img(data, w, h);
                int[] out = new int[w * h];
                for (int y = 0; y < h; y++) System.arraycopy(data, off + y * stride, out, y * w, w);
                return new Img(out, w, h);
            }
            return new Img(bi.getRGB(0, 0, w, h, null, 0, w), w, h);
        }
    }

    // =========================================================================================
    // Region selection overlay (one dimmed window per monitor)
    // =========================================================================================

    static final class SelectionOverlay {
        static Rectangle select() throws Exception {
            CompletableFuture<Rectangle> fut = new CompletableFuture<>();
            List<JFrame> frames = new ArrayList<>();
            SwingUtilities.invokeAndWait(() -> {
                for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                    GraphicsConfiguration gc = gd.getDefaultConfiguration();
                    Rectangle b = gc.getBounds();
                    JFrame f = new JFrame(gc);
                    f.setUndecorated(true);
                    f.setAlwaysOnTop(true);
                    f.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                    boolean tr = gd.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
                    f.setBackground(tr ? new Color(0, 0, 0, 90) : new Color(60, 60, 60));
                    OverlayPanel p = new OverlayPanel(b, fut);
                    f.setContentPane(p);
                    f.setBounds(b);
                    f.setVisible(true);
                    p.requestFocusInWindow();
                    frames.add(f);
                }
            });
            Rectangle r = fut.get();
            SwingUtilities.invokeAndWait(() -> frames.forEach(Window::dispose));
            return r;
        }

        static final class OverlayPanel extends JPanel {
            final Rectangle screen;
            final CompletableFuture<Rectangle> fut;
            Point start, cur;

            OverlayPanel(Rectangle screen, CompletableFuture<Rectangle> fut) {
                this.screen = screen;
                this.fut = fut;
                setOpaque(false);
                setFocusable(true);
                setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                MouseAdapter ma = new MouseAdapter() {
                    @Override public void mousePressed(MouseEvent e) { start = e.getLocationOnScreen(); cur = start; repaint(); }
                    @Override public void mouseDragged(MouseEvent e) { cur = e.getLocationOnScreen(); repaint(); }
                    @Override public void mouseReleased(MouseEvent e) {
                        if (start == null) return;
                        cur = e.getLocationOnScreen();
                        Rectangle r = norm(start, cur).intersection(screen);
                        if (r.width >= 20 && r.height >= 20) fut.complete(r);
                        else { start = null; repaint(); }
                    }
                };
                addMouseListener(ma);
                addMouseMotionListener(ma);
                getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "quit");
                getActionMap().put("quit", new AbstractAction() {
                    @Override public void actionPerformed(java.awt.event.ActionEvent e) { System.exit(0); }
                });
            }

            static Rectangle norm(Point a, Point b) {
                return new Rectangle(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.abs(a.x - b.x), Math.abs(a.y - b.y));
            }

            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0;
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setFont(getFont().deriveFont(Font.BOLD, 22f));
                String msg = "GlassTransfer: drag a rectangle around the QR code (include its white margin).  Esc = quit";
                g.setColor(Color.BLACK);
                g.drawString(msg, 41, 61);
                g.setColor(Color.WHITE);
                g.drawString(msg, 40, 60);
                if (start != null && cur != null) {
                    Rectangle r = norm(start, cur);
                    r.translate(-screen.x, -screen.y);
                    g.setColor(new Color(0, 200, 0, 60));
                    g.fillRect(r.x, r.y, r.width, r.height);
                    g.setColor(Color.WHITE);
                    g.drawRect(r.x, r.y, r.width, r.height);
                    g.setFont(getFont().deriveFont(Font.PLAIN, 14f));
                    g.drawString(r.width + " x " + r.height, r.x + 4, r.y - 6);
                }
            }
        }
    }

    // =========================================================================================
    // Border: four thin always-on-top, non-focusable frames around the region + status strip
    // =========================================================================================

    static final class Border {
        static final int RING = 4, STRIP = 26;
        final JFrame[] frames = new JFrame[4];
        JLabel label;
        volatile Color color = new Color(120, 120, 120);
        volatile String text = "";

        static Border create(Rectangle region) throws Exception {
            Border b = new Border();
            SwingUtilities.invokeAndWait(() -> b.build(region));
            return b;
        }

        void build(Rectangle r) {
            GraphicsDevice gd = Screens.deviceAt(new Point(r.x + r.width / 2, r.y + r.height / 2));
            GraphicsConfiguration gc = gd.getDefaultConfiguration();
            Rectangle sb = gc.getBounds();
            boolean above = r.y - RING - STRIP >= sb.y;
            Rectangle[] rs = {
                new Rectangle(r.x - RING, above ? r.y - RING - STRIP : r.y - RING, r.width + 2 * RING, RING + (above ? STRIP : 0)),
                new Rectangle(r.x - RING, r.y + r.height, r.width + 2 * RING, RING + (above ? 0 : STRIP)),
                new Rectangle(r.x - RING, r.y, RING, r.height),
                new Rectangle(r.x + r.width, r.y, RING, r.height)
            };
            for (int i = 0; i < 4; i++) {
                JFrame f = new JFrame(gc);
                f.setType(Window.Type.UTILITY);
                f.setUndecorated(true);
                f.setAlwaysOnTop(true);
                f.setFocusableWindowState(false);
                f.setAutoRequestFocus(false);
                f.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                JPanel p = new JPanel(new BorderLayout());
                p.setBackground(color);
                f.setContentPane(p);
                f.setBounds(rs[i]);
                frames[i] = f;
            }
            JPanel strip = (JPanel) frames[above ? 0 : 1].getContentPane();
            label = new JLabel(" GlassTransfer: waiting for a QR code");
            label.setForeground(Color.WHITE);
            label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
            JButton quit = new JButton("Quit");
            quit.setFocusable(false);
            quit.setMargin(new Insets(0, 8, 0, 8));
            quit.addActionListener(e -> System.exit(0));
            strip.add(label, BorderLayout.CENTER);
            strip.add(quit, BorderLayout.EAST);
            for (JFrame f : frames) f.setVisible(true);
        }

        void setTitle(String t) {
            SwingUtilities.invokeLater(() -> { for (JFrame f : frames) f.setTitle(t); });
        }

        void setStatus(Color c, String t) {
            if (c.equals(color) && t.equals(text)) return;
            color = c;
            text = t;
            SwingUtilities.invokeLater(() -> {
                for (JFrame f : frames) f.getContentPane().setBackground(c);
                label.setText(" " + t);
            });
        }
    }

    // =========================================================================================
    // Key injection
    // =========================================================================================

    static final class Keys {
        final Robot r;
        final boolean enabled;

        Keys(Robot r, boolean enabled) {
            this.r = r;
            this.enabled = enabled;
            Runtime.getRuntime().addShutdownHook(new Thread(this::releaseAll));
            if (!enabled) log("--no-keys: key presses are disabled (dry run)");
        }

        void tap(int vk) {
            if (!enabled) return;
            try { r.keyPress(vk); } finally { r.keyRelease(vk); }
        }

        void up() { tap(KeyEvent.VK_UP); }
        void down() { tap(KeyEvent.VK_DOWN); }
        void enter() { tap(KeyEvent.VK_ENTER); }

        void typeDigits(int n) {
            boolean numpad = numLockOn();
            String s = Integer.toString(n);
            for (int i = 0; i < s.length(); i++) {
                int d = s.charAt(i) - '0';
                tap(numpad ? KeyEvent.VK_NUMPAD0 + d : KeyEvent.VK_0 + d);
                LockSupport.parkNanos(15_000_000L);
            }
        }

        static boolean numLockOn() {
            try { return Toolkit.getDefaultToolkit().getLockingKeyState(KeyEvent.VK_NUM_LOCK); }
            catch (Exception e) { return false; }
        }

        void releaseAll() {
            if (!enabled) return;
            for (int vk : new int[] {KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_ENTER}) {
                try { r.keyRelease(vk); } catch (Exception ignored) { }
            }
        }
    }

    // =========================================================================================
    // Frame protocol (must match sender.html byte for byte)
    // =========================================================================================

    static final class Protocol {
        static final int PROTO = 3;                 // bumped when the frame layout changes
        static final int HEADER = 23, DATA = 0, END = 1;
        static final int END_FIXED = 8 + 4 + 32 + 2;   // size, crc32, sha256, name length

        record Frame(int type, int fileId, int index, int total, int chunkSize, byte[] payload) {
            @Override public String toString() {
                return String.format("%s id=%08x idx=%d/%d D=%d len=%d", type == DATA ? "DATA" : "END", fileId, index, total, chunkSize, payload.length);
            }
        }

        record EndInfo(long size, int crc, byte[] sha, String name) { }

        static Frame parse(byte[] b) {
            if (b == null || b.length < HEADER || b[0] != 'G' || b[1] != 'T') return null;
            int tv = b[2] & 0xFF;
            if ((tv >>> 4) != PROTO) return null;
            int type = tv & 15;
            if (type > END) return null;
            ByteBuffer bb = ByteBuffer.wrap(b);
            int fileId = bb.getInt(3), index = bb.getInt(7), total = bb.getInt(11);
            int chunk = bb.getInt(15), crc = bb.getInt(19);
            CRC32 c = new CRC32();
            c.update(b, 0, 19);
            c.update(new byte[4], 0, 4);
            c.update(b, HEADER, b.length - HEADER);
            if ((int) c.getValue() != crc) return null;
            if (index < 0 || total < 0) return null;
            if (chunk <= 0) return null;
            if (type == DATA && (index >= total || b.length == HEADER)) return null;
            if (type == END && index != total) return null;
            return new Frame(type, fileId, index, total, chunk, Arrays.copyOfRange(b, HEADER, b.length));
        }

        static EndInfo parseEnd(Frame f) {
            byte[] p = f.payload();
            if (f.type() != END || p.length < END_FIXED) return null;
            ByteBuffer bb = ByteBuffer.wrap(p);
            long size = bb.getLong(0);
            int crc = bb.getInt(8);
            byte[] sha = Arrays.copyOfRange(p, 12, 44);
            int nl = bb.getShort(44) & 0xFFFF;
            if (p.length < END_FIXED + nl) return null;
            return new EndInfo(size, crc, sha, new String(p, END_FIXED, nl, StandardCharsets.UTF_8));
        }

        /** Builds a frame exactly like the sender does (used by the self-test). */
        static byte[] build(int type, int fileId, int index, int total, int chunkSize, byte[] payload) {
            byte[] b = new byte[HEADER + payload.length];
            b[0] = 'G'; b[1] = 'T'; b[2] = (byte) ((PROTO << 4) | type);
            ByteBuffer bb = ByteBuffer.wrap(b);
            bb.putInt(3, fileId); bb.putInt(7, index); bb.putInt(11, total); bb.putInt(15, chunkSize); bb.putInt(19, 0);
            System.arraycopy(payload, 0, b, HEADER, payload.length);
            CRC32 c = new CRC32();
            c.update(b, 0, b.length);
            bb.putInt(19, (int) c.getValue());
            return b;
        }
    }

    // =========================================================================================
    // QR decoder for axis-aligned screen renders
    // =========================================================================================

    static final class QrDecoder {
        // Tables from Nayuki qrcodegen v1.8.0; index [ECC ordinal L,M,Q,H][version], index 0 unused.
        static final int[][] ECC_CODEWORDS_PER_BLOCK = {
            {-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
            {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28},
            {-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
            {-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
        };
        static final int[][] NUM_ERROR_CORRECTION_BLOCKS = {
            {-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25},
            {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49},
            {-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68},
            {-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81},
        };
        static final int[] FORMAT_CODES = new int[32];      // index = eclFormatBits << 3 | mask
        static final int[] VERSION_CODES = new int[41];
        static final int[] FORMATBITS_TO_ORD = {1, 0, 3, 2}; // format bits 0..3 -> ordinal (M, L, H, Q)
        static final int[] ORD_TO_FORMATBITS = {1, 0, 3, 2}; // ordinal L,M,Q,H -> format bits
        static final boolean[][] FUNC_CACHE = new boolean[41][];

        static {
            for (int d = 0; d < 32; d++) {
                int rem = d;
                for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
                FORMAT_CODES[d] = ((d << 10) | rem) ^ 0x5412;
            }
            for (int v = 7; v <= 40; v++) {
                int rem = v;
                for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
                VERSION_CODES[v] = (v << 12) | rem;
            }
        }

        record Finder(double cx, double cy, double module, int n) { }
        record Geometry(double ox, double oy, double px, double py, int size, Finder tl, Finder tr, Finder bl) { }
        record Decoded(int version, int ecl, int mask, byte[] bytes, int corrected) { }
        record Result(Kind kind, boolean changed, Decoded decoded) { }

        static final Result NONE = new Result(Kind.NONE, true, null);
        static final Result FAIL = new Result(Kind.FAIL, true, null);

        Geometry geo;
        int thr = 128;
        long[] lastGrid;
        Result lastResult;
        int fails;

        /** One capture in, one result out. changed=false means the module grid equals the last decoded one. */
        Result process(Gray g) {
            long[] grid = null;
            if (geo != null) {
                if (verifyGeometry(g, geo, thr)) {
                    int t = levels(g, geo);
                    if (t > 0) { thr = t; grid = sampleGrid(g, geo, thr); }
                }
                if (grid == null) geo = null;
            }
            if (grid == null) {
                Geometry ng = locate(g);
                if (ng == null) { lastGrid = null; lastResult = null; return NONE; }
                geo = ng;
                thr = levels(g, geo);
                grid = sampleGrid(g, geo, thr);
            }
            if (lastGrid != null && lastResult != null && Arrays.equals(grid, lastGrid))
                return new Result(lastResult.kind(), false, lastResult.decoded());
            lastGrid = grid;
            Decoded d = decodeGrid(grid, geo.size());
            if (d == null) {
                if (++fails >= 5) { geo = null; fails = 0; }
                lastResult = FAIL;
                return FAIL;
            }
            fails = 0;
            lastResult = new Result(Kind.OK, true, d);
            return lastResult;
        }

        // ---- locating -------------------------------------------------------------------

        Geometry locate(Gray g) {
            int t = otsu(histogram(g));
            if (t <= 0) return null;
            List<Finder> fs = findFinders(g, t, 2);
            if (fs.size() < 3) return null;
            return chooseTriple(fs, g);
        }

        static int[] histogram(Gray g) {
            int[] h = new int[256];
            for (byte b : g.px()) h[b & 0xFF]++;
            return h;
        }

        static int otsu(int[] hist) {
            long total = 0, sum = 0;
            for (int i = 0; i < 256; i++) { total += hist[i]; sum += (long) i * hist[i]; }
            if (total == 0) return -1;
            long wB = 0, sumB = 0;
            double best = -1;
            int thr = 128;
            for (int t = 0; t < 256; t++) {
                wB += hist[t];
                if (wB == 0) continue;
                long wF = total - wB;
                if (wF == 0) break;
                sumB += (long) t * hist[t];
                double mB = sumB / (double) wB, mF = (sum - sumB) / (double) wF;
                double v = (double) wB * wF * (mB - mF) * (mB - mF);
                if (v > best) { best = v; thr = t + 1; }
            }
            return thr;
        }

        static boolean ratioOk(int[] sc) {
            int total = 0;
            for (int c : sc) { if (c == 0) return false; total += c; }
            if (total < 7) return false;
            double unit = total / 7.0, v = unit / 2;
            return Math.abs(sc[0] - unit) < v && Math.abs(sc[1] - unit) < v && Math.abs(sc[2] - 3 * unit) < 3 * v
                && Math.abs(sc[3] - unit) < v && Math.abs(sc[4] - unit) < v;
        }

        static List<Finder> findFinders(Gray g, int thr, int rowStep) {
            List<Finder> out = new ArrayList<>();
            int w = g.w(), h = g.h();
            byte[] p = g.px();
            int[] sc = new int[5];
            for (int y = 0; y < h; y += rowStep) {
                Arrays.fill(sc, 0);
                int cs = 0, row = y * w;
                for (int x = 0; x < w; x++) {
                    boolean dark = (p[row + x] & 0xFF) < thr;
                    if (dark) {
                        if ((cs & 1) == 1) cs++;
                        sc[cs]++;
                    } else if ((cs & 1) == 0) {
                        if (cs == 4) {
                            if (ratioOk(sc)) tryFinder(g, thr, x, y, sc, out);
                            sc[0] = sc[2]; sc[1] = sc[3]; sc[2] = sc[4]; sc[3] = 1; sc[4] = 0;
                            cs = 3;
                        } else {
                            cs++;
                            sc[cs]++;
                        }
                    } else {
                        sc[cs]++;
                    }
                }
                if (cs == 4 && ratioOk(sc)) tryFinder(g, thr, w, y, sc, out);
            }
            return out;
        }

        static void tryFinder(Gray g, int thr, int xEnd, int y, int[] sc, List<Finder> out) {
            int total = sc[0] + sc[1] + sc[2] + sc[3] + sc[4];
            double cx = xEnd - sc[4] - sc[3] - sc[2] / 2.0;
            double cy = crossCheck(g, thr, (int) cx, y, true, total);
            if (Double.isNaN(cy)) return;
            double cx2 = crossCheck(g, thr, (int) cy, (int) cx, false, total);
            if (Double.isNaN(cx2)) return;
            double module = total / 7.0;
            for (int i = 0; i < out.size(); i++) {
                Finder f = out.get(i);
                if (Math.abs(f.cx() - cx2) <= f.module() && Math.abs(f.cy() - cy) <= f.module()) {
                    int n = f.n() + 1;
                    out.set(i, new Finder((f.cx() * f.n() + cx2) / n, (f.cy() * f.n() + cy) / n, (f.module() * f.n() + module) / n, n));
                    return;
                }
            }
            out.add(new Finder(cx2, cy, module, 1));
        }

        static boolean darkAt(Gray g, int thr, int fixed, int i, boolean vertical) {
            return (vertical ? g.at(fixed, i) : g.at(i, fixed)) < thr;
        }

        /** Walks a 1:1:3:1:1 pattern along a line; returns its center along the scan axis or NaN. */
        static double crossCheck(Gray g, int thr, int fixed, int start, boolean vertical, int originalTotal) {
            int max = vertical ? g.h() : g.w();
            int limit = originalTotal * 2;
            int[] sc = new int[5];
            int i = start;
            while (i >= 0 && darkAt(g, thr, fixed, i, vertical) && sc[2] <= limit) { sc[2]++; i--; }
            if (i < 0 || sc[2] > limit) return Double.NaN;
            while (i >= 0 && !darkAt(g, thr, fixed, i, vertical) && sc[1] <= limit) { sc[1]++; i--; }
            if (i < 0 || sc[1] > limit) return Double.NaN;
            while (i >= 0 && darkAt(g, thr, fixed, i, vertical) && sc[0] <= limit) { sc[0]++; i--; }
            if (sc[0] > limit) return Double.NaN;
            int j = start + 1;
            while (j < max && darkAt(g, thr, fixed, j, vertical) && sc[2] <= limit) { sc[2]++; j++; }
            if (j >= max || sc[2] > limit) return Double.NaN;
            while (j < max && !darkAt(g, thr, fixed, j, vertical) && sc[3] <= limit) { sc[3]++; j++; }
            if (j >= max || sc[3] > limit) return Double.NaN;
            while (j < max && darkAt(g, thr, fixed, j, vertical) && sc[4] <= limit) { sc[4]++; j++; }
            if (sc[4] > limit) return Double.NaN;
            int total = sc[0] + sc[1] + sc[2] + sc[3] + sc[4];
            if (5 * Math.abs(total - originalTotal) >= 2 * originalTotal) return Double.NaN;
            return ratioOk(sc) ? j - sc[4] - sc[3] - sc[2] / 2.0 : Double.NaN;
        }

        static Geometry chooseTriple(List<Finder> fs, Gray g) {
            Geometry best = null;
            double bestScore = Double.MAX_VALUE;
            int n = fs.size();
            for (int a = 0; a < n; a++) for (int b = a + 1; b < n; b++) for (int c = b + 1; c < n; c++) {
                Finder[] t = {fs.get(a), fs.get(b), fs.get(c)};
                double mMin = Math.min(t[0].module(), Math.min(t[1].module(), t[2].module()));
                double mMax = Math.max(t[0].module(), Math.max(t[1].module(), t[2].module()));
                if (mMax > mMin * 1.3) continue;
                for (int k = 0; k < 3; k++) {
                    Finder tl = t[k], p = t[(k + 1) % 3], q = t[(k + 2) % 3], tr, bl;
                    if (p.cx() > tl.cx() && q.cy() > tl.cy()) { tr = p; bl = q; }
                    else if (q.cx() > tl.cx() && p.cy() > tl.cy()) { tr = q; bl = p; }
                    else continue;
                    double legX = tr.cx() - tl.cx(), legY = bl.cy() - tl.cy();
                    if (legX < 13 * mMin || legY < 13 * mMin) continue;
                    double skew = Math.abs(tr.cy() - tl.cy()) / legX + Math.abs(bl.cx() - tl.cx()) / legY;
                    if (skew > 0.06) continue;
                    double legDiff = Math.abs(legX - legY) / Math.max(legX, legY);
                    if (legDiff > 0.1) continue;
                    double mod = (tl.module() + tr.module() + bl.module()) / 3;
                    double dimF = (legX + legY) / 2 / mod + 7;
                    double snap = Math.abs(dimF - (Math.round((dimF - 17) / 4) * 4 + 17)) / 4.0;
                    double score = skew * 10 + legDiff + snap;
                    if (score >= bestScore) continue;
                    Geometry geo = buildGeometry(g, tl, tr, bl, mod);
                    if (geo != null) { bestScore = score; best = geo; }
                }
            }
            return best;
        }

        static Geometry buildGeometry(Gray g, Finder tl, Finder tr, Finder bl, double mod) {
            double legX = tr.cx() - tl.cx(), legY = bl.cy() - tl.cy();
            double dimF = (legX + legY) / 2 / mod + 7;
            int k0 = (int) Math.round((dimF - 17) / 4);
            Geometry bestG = null;
            int bestMis = Integer.MAX_VALUE;
            for (int dk = -1; dk <= 1; dk++) {
                int size = 17 + 4 * (k0 + dk);
                if (size < 21 || size > 177) continue;
                double px = legX / (size - 7), py = legY / (size - 7);
                double ox = tl.cx() - 3 * px, oy = tl.cy() - 3 * py;
                if (ox - px < 0 || oy - py < 0 || ox + size * px > g.w() || oy + size * py > g.h()) continue;
                Geometry geo = new Geometry(ox, oy, px, py, size, tl, tr, bl);
                int t = levels(g, geo);
                if (t <= 0) continue;
                int mis = timingMismatches(g, geo, t, 1);
                if (mis < bestMis) { bestMis = mis; bestG = geo; }
            }
            return bestMis <= 2 ? bestG : null;
        }

        static int pixel(Gray g, double x, double y) {
            int xi = (int) Math.round(x), yi = (int) Math.round(y);
            if (xi < 0) xi = 0; else if (xi >= g.w()) xi = g.w() - 1;
            if (yi < 0) yi = 0; else if (yi >= g.h()) yi = g.h() - 1;
            return g.at(xi, yi);
        }

        /** Threshold from the finder centres (black) and the separators (white); -1 if the contrast is poor. */
        static int levels(Gray g, Geometry geo) {
            Finder[] fs = {geo.tl(), geo.tr(), geo.bl()};
            double black = 0;
            for (Finder f : fs) black += pixel(g, f.cx(), f.cy());
            black /= 3;
            double px = geo.px(), py = geo.py();
            double white = (pixel(g, geo.tl().cx() + 4 * px, geo.tl().cy()) + pixel(g, geo.tl().cx(), geo.tl().cy() + 4 * py)
                          + pixel(g, geo.tr().cx() - 4 * px, geo.tr().cy()) + pixel(g, geo.bl().cx(), geo.bl().cy() - 4 * py)) / 4.0;
            if (white - black < 40) return -1;
            return (int) ((black + white) / 2);
        }

        static boolean moduleDark(Gray g, Geometry geo, int thr, int c, int r) {
            return pixel(g, geo.ox() + c * geo.px(), geo.oy() + r * geo.py()) < thr;
        }

        static int timingMismatches(Gray g, Geometry geo, int thr, int step) {
            int size = geo.size(), mis = 0;
            for (int c = 8; c <= size - 9; c += step) {
                boolean expect = c % 2 == 0;
                if (moduleDark(g, geo, thr, c, 6) != expect) mis++;
                if (moduleDark(g, geo, thr, 6, c) != expect) mis++;
            }
            return mis;
        }

        static boolean verifyGeometry(Gray g, Geometry geo, int thr) {
            Finder[] fs = {geo.tl(), geo.tr(), geo.bl()};
            for (Finder f : fs) if (pixel(g, f.cx(), f.cy()) >= thr) return false;
            double px = geo.px(), py = geo.py();
            if (pixel(g, geo.tl().cx() + 4 * px, geo.tl().cy()) < thr || pixel(g, geo.tl().cx(), geo.tl().cy() + 4 * py) < thr
                || pixel(g, geo.tr().cx() - 4 * px, geo.tr().cy()) < thr || pixel(g, geo.bl().cx(), geo.bl().cy() - 4 * py) < thr) return false;
            return timingMismatches(g, geo, thr, 2) <= 1;
        }

        static long[] sampleGrid(Gray g, Geometry geo, int thr) {
            int size = geo.size();
            long[] bits = new long[(size * size + 63) >>> 6];
            // Sample the module centre; average a 3x3 neighbourhood only when the offset provably
            // stays inside the module (centre rounding costs 0.5 px, so need d + 0.5 < pitch / 2).
            double minPitch = Math.min(geo.px(), geo.py());
            int d = (int) (minPitch / 4);
            boolean maj = d >= 1 && d + 0.5 < minPitch / 2;
            for (int r = 0; r < size; r++) {
                double yc = geo.oy() + r * geo.py();
                for (int c = 0; c < size; c++) {
                    double xc = geo.ox() + c * geo.px();
                    boolean dark;
                    if (maj) {
                        int cnt = 0;
                        for (int dy = -d; dy <= d; dy += d) for (int dx = -d; dx <= d; dx += d)
                            if (pixel(g, xc + dx, yc + dy) < thr) cnt++;
                        dark = cnt >= 5;
                    } else {
                        dark = pixel(g, xc, yc) < thr;
                    }
                    if (dark) { int i = r * size + c; bits[i >>> 6] |= 1L << (i & 63); }
                }
            }
            return bits;
        }

        // ---- decoding the module grid --------------------------------------------------

        static boolean bit(long[] bits, int size, int x, int y) {
            int i = y * size + x;
            return ((bits[i >>> 6] >>> (i & 63)) & 1L) != 0;
        }

        static int[] formatPos(int size, int copy, int i) {
            if (copy == 0) {
                if (i <= 5) return new int[] {8, i};
                if (i == 6) return new int[] {8, 7};
                if (i == 7) return new int[] {8, 8};
                if (i == 8) return new int[] {7, 8};
                return new int[] {14 - i, 8};
            }
            return i < 8 ? new int[] {size - 1 - i, 8} : new int[] {8, size - 15 + i};
        }

        static int[] versionPos(int size, int copy, int i) {
            int a = size - 11 + i % 3, b = i / 3;
            return copy == 0 ? new int[] {a, b} : new int[] {b, a};
        }

        static int matchFormat(int w0, int w1) {
            int best = -1, bestDist = 4;
            for (int code = 0; code < 32; code++) {
                int d = Math.min(Integer.bitCount(w0 ^ FORMAT_CODES[code]), Integer.bitCount(w1 ^ FORMAT_CODES[code]));
                if (d < bestDist) { bestDist = d; best = code; }
            }
            return best;
        }

        static int readFormat(long[] bits, int size) {
            int[] words = new int[2];
            for (int copy = 0; copy < 2; copy++) {
                int w = 0;
                for (int i = 0; i < 15; i++) { int[] p = formatPos(size, copy, i); if (bit(bits, size, p[0], p[1])) w |= 1 << i; }
                words[copy] = w;
            }
            return matchFormat(words[0], words[1]);
        }

        static int matchVersion(int w0, int w1) {
            int best = -1, bestDist = 4;
            for (int v = 7; v <= 40; v++) {
                int d = Math.min(Integer.bitCount(w0 ^ VERSION_CODES[v]), Integer.bitCount(w1 ^ VERSION_CODES[v]));
                if (d < bestDist) { bestDist = d; best = v; }
            }
            return best;
        }

        static int readVersionInfo(long[] bits, int size) {
            int[] words = new int[2];
            for (int copy = 0; copy < 2; copy++) {
                int w = 0;
                for (int i = 0; i < 18; i++) { int[] p = versionPos(size, copy, i); if (bit(bits, size, p[0], p[1])) w |= 1 << i; }
                words[copy] = w;
            }
            return matchVersion(words[0], words[1]);
        }

        static int numRawDataModules(int ver) {
            int r = (16 * ver + 128) * ver + 64;
            if (ver >= 2) {
                int na = ver / 7 + 2;
                r -= (25 * na - 10) * na - 55;
                if (ver >= 7) r -= 36;
            }
            return r;
        }

        static int numDataCodewords(int ver, int ecl) {
            return numRawDataModules(ver) / 8 - ECC_CODEWORDS_PER_BLOCK[ecl][ver] * NUM_ERROR_CORRECTION_BLOCKS[ecl][ver];
        }

        static int[] alignmentPositions(int ver) {
            if (ver == 1) return new int[0];
            int na = ver / 7 + 2, size = ver * 4 + 17;
            int step = ver == 32 ? 26 : (int) Math.ceil((ver * 4 + 4) / (double) (na * 2 - 2)) * 2;
            int[] res = new int[na];
            res[0] = 6;
            int pos = size - 7;
            for (int i = na - 1; i >= 1; i--) { res[i] = pos; pos -= step; }
            return res;
        }

        static synchronized boolean[] functionMask(int ver) {
            if (FUNC_CACHE[ver] != null) return FUNC_CACHE[ver];
            int size = ver * 4 + 17;
            boolean[] f = new boolean[size * size];
            for (int i = 0; i < size; i++) { f[6 * size + i] = true; f[i * size + 6] = true; }
            int[][] finders = {{3, 3}, {size - 4, 3}, {3, size - 4}};
            for (int[] c : finders)
                for (int dy = -4; dy <= 4; dy++) for (int dx = -4; dx <= 4; dx++) {
                    int x = c[0] + dx, y = c[1] + dy;
                    if (x >= 0 && x < size && y >= 0 && y < size) f[y * size + x] = true;
                }
            int[] ap = alignmentPositions(ver);
            int na = ap.length;
            for (int i = 0; i < na; i++) for (int j = 0; j < na; j++) {
                if ((i == 0 && j == 0) || (i == 0 && j == na - 1) || (i == na - 1 && j == 0)) continue;
                for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) f[(ap[j] + dy) * size + ap[i] + dx] = true;
            }
            for (int i = 0; i <= 8; i++) { f[i * size + 8] = true; f[8 * size + i] = true; }
            for (int i = 0; i < 8; i++) { f[8 * size + size - 1 - i] = true; f[(size - 1 - i) * size + 8] = true; }
            if (ver >= 7)
                for (int copy = 0; copy < 2; copy++) for (int i = 0; i < 18; i++) { int[] p = versionPos(size, copy, i); f[p[1] * size + p[0]] = true; }
            FUNC_CACHE[ver] = f;
            return f;
        }

        static boolean maskBit(int mask, int x, int y) {
            return switch (mask) {
                case 0 -> (x + y) % 2 == 0;
                case 1 -> y % 2 == 0;
                case 2 -> x % 3 == 0;
                case 3 -> (x + y) % 3 == 0;
                case 4 -> (x / 3 + y / 2) % 2 == 0;
                case 5 -> x * y % 2 + x * y % 3 == 0;
                case 6 -> (x * y % 2 + x * y % 3) % 2 == 0;
                default -> ((x + y) % 2 + x * y % 3) % 2 == 0;
            };
        }

        static byte[] extractCodewords(long[] bits, boolean[] func, int size, int ver, int mask) {
            int rawCw = numRawDataModules(ver) / 8, total = rawCw * 8, i = 0;
            byte[] out = new byte[rawCw];
            for (int right = size - 1; right >= 1; right -= 2) {
                if (right == 6) right = 5;
                boolean upward = ((right + 1) & 2) == 0;
                for (int vert = 0; vert < size; vert++) {
                    int y = upward ? size - 1 - vert : vert;
                    for (int j = 0; j < 2; j++) {
                        int x = right - j;
                        if (func[y * size + x]) continue;
                        if (i < total && (bit(bits, size, x, y) ^ maskBit(mask, x, y))) out[i >>> 3] |= (byte) (0x80 >>> (i & 7));
                        i++;
                    }
                }
            }
            return out;
        }

        static byte[] correctAndDeinterleave(byte[] raw, int ver, int ecl, int[] correctedOut) {
            int numBlocks = NUM_ERROR_CORRECTION_BLOCKS[ecl][ver], eccLen = ECC_CODEWORDS_PER_BLOCK[ecl][ver];
            int rawCw = numRawDataModules(ver) / 8;
            int numShort = numBlocks - rawCw % numBlocks, shortLen = rawCw / numBlocks, shortData = shortLen - eccLen;
            int[][] blocks = new int[numBlocks][];
            for (int b = 0; b < numBlocks; b++) blocks[b] = new int[shortLen + (b < numShort ? 0 : 1)];
            int k = 0;
            for (int i = 0; i <= shortData; i++)
                for (int b = 0; b < numBlocks; b++) {
                    if (i == shortData && b < numShort) continue;
                    blocks[b][i] = raw[k++] & 0xFF;
                }
            for (int i = 0; i < eccLen; i++)
                for (int b = 0; b < numBlocks; b++) blocks[b][blocks[b].length - eccLen + i] = raw[k++] & 0xFF;
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            int corrected = 0;
            for (int[] blk : blocks) {
                corrected += RS.correct(blk, eccLen);
                for (int j = 0; j < blk.length - eccLen; j++) data.write(blk[j]);
            }
            correctedOut[0] = corrected;
            return data.toByteArray();
        }

        static int readBits(byte[] d, int pos, int n) {
            int v = 0;
            for (int i = 0; i < n; i++, pos++) v = (v << 1) | ((d[pos >>> 3] >>> (7 - (pos & 7))) & 1);
            return v;
        }

        static byte[] parseSegments(byte[] data, int ver) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int bitLen = data.length * 8, pos = 0;
            while (bitLen - pos >= 4) {
                int mode = readBits(data, pos, 4);
                pos += 4;
                if (mode == 0) break;
                if (mode != 4) return null;
                int ccb = ver <= 9 ? 8 : 16;
                if (bitLen - pos < ccb) return null;
                int cnt = readBits(data, pos, ccb);
                pos += ccb;
                if (bitLen - pos < cnt * 8) return null;
                for (int i = 0; i < cnt; i++, pos += 8) out.write(readBits(data, pos, 8));
            }
            return out.toByteArray();
        }

        static Decoded decodeGrid(long[] bits, int size) {
            int ver = (size - 17) / 4;
            int fmt = readFormat(bits, size);
            if (fmt < 0) return null;
            int ecl = FORMATBITS_TO_ORD[fmt >>> 3], mask = fmt & 7;
            if (ver >= 7) {
                int vi = readVersionInfo(bits, size);
                if (vi > 0 && vi != ver) return null;
            }
            byte[] raw = extractCodewords(bits, functionMask(ver), size, ver, mask);
            int[] corr = new int[1];
            byte[] data;
            try { data = correctAndDeinterleave(raw, ver, ecl, corr); }
            catch (IllegalStateException e) { return null; }
            byte[] payload = parseSegments(data, ver);
            if (payload == null) return null;
            return new Decoded(ver, ecl, mask, payload, corr[0]);
        }
    }

    // =========================================================================================
    // Reed-Solomon over GF(256), polynomial 0x11D, first consecutive root alpha^0
    // =========================================================================================

    static final class RS {
        static final int[] EXP = new int[512], LOG = new int[256];

        static {
            int x = 1;
            for (int i = 0; i < 255; i++) {
                EXP[i] = x;
                LOG[x] = i;
                x <<= 1;
                if ((x & 0x100) != 0) x ^= 0x11D;
            }
            for (int i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
        }

        static int mul(int a, int b) { return (a == 0 || b == 0) ? 0 : EXP[LOG[a] + LOG[b]]; }
        static int inv(int a) { return EXP[255 - LOG[a]]; }
        static int pow(int a, int k) { return k == 0 ? 1 : a == 0 ? 0 : EXP[(LOG[a] * k) % 255]; }

        static int[] syndromes(int[] cw, int nsym) {
            int[] s = new int[nsym];
            for (int i = 0; i < nsym; i++) {
                int x = EXP[i], v = 0;
                for (int c : cw) v = mul(v, x) ^ c;
                s[i] = v;
            }
            return s;
        }

        /** Corrects cw (data followed by nsym ecc symbols) in place; returns the number of corrected symbols. */
        static int correct(int[] cw, int nsym) {
            int[] s = syndromes(cw, nsym);
            boolean zero = true;
            for (int v : s) if (v != 0) { zero = false; break; }
            if (zero) return 0;
            int[] C = new int[nsym + 1], B = new int[nsym + 1];
            C[0] = 1; B[0] = 1;
            int L = 0, m = 1, b = 1;
            for (int n = 0; n < nsym; n++) {
                int d = s[n];
                for (int i = 1; i <= L; i++) d ^= mul(C[i], s[n - i]);
                if (d == 0) { m++; continue; }
                int[] T = C.clone();
                int coef = mul(d, inv(b));
                for (int i = m; i <= nsym; i++) C[i] ^= mul(coef, B[i - m]);
                if (2 * L <= n) { L = n + 1 - L; B = T; b = d; m = 1; }
                else m++;
            }
            if (L == 0 || L > nsym / 2) throw new IllegalStateException("too many errors");
            int n = cw.length;
            int[] pos = new int[L];
            int found = 0;
            for (int j = 0; j < n; j++) {
                int deg = n - 1 - j;
                int xinv = EXP[(255 - deg % 255) % 255];
                int v = 0;
                for (int i = L; i >= 0; i--) v = mul(v, xinv) ^ C[i];
                if (v == 0) {
                    if (found == L) throw new IllegalStateException("locator degree mismatch");
                    pos[found++] = j;
                }
            }
            if (found != L) throw new IllegalStateException("locator roots not found");
            int[] om = new int[nsym];
            for (int i = 0; i < nsym; i++) for (int k = 0; k <= L && k <= i; k++) om[i] ^= mul(s[i - k], C[k]);
            for (int e = 0; e < L; e++) {
                int j = pos[e], deg = n - 1 - j;
                int X = EXP[deg % 255], xinv = EXP[(255 - deg % 255) % 255];
                int num = 0;
                for (int i = nsym - 1; i >= 0; i--) num = mul(num, xinv) ^ om[i];
                int den = 0;
                for (int i = 1; i <= L; i += 2) den ^= mul(C[i], pow(xinv, i - 1));
                if (den == 0) throw new IllegalStateException("zero derivative");
                cw[j] ^= mul(X, mul(num, inv(den)));
            }
            for (int v : syndromes(cw, nsym)) if (v != 0) throw new IllegalStateException("correction failed");
            return L;
        }

        /** Data + nsym ecc symbols (used by the self-test). */
        static int[] encode(int[] data, int nsym) {
            int[] div = new int[nsym];
            div[nsym - 1] = 1;
            int root = 1;
            for (int i = 0; i < nsym; i++) {
                for (int j = 0; j < nsym; j++) {
                    div[j] = mul(div[j], root);
                    if (j + 1 < nsym) div[j] ^= div[j + 1];
                }
                root = mul(root, 2);
            }
            int[] rem = new int[nsym];
            for (int b : data) {
                int factor = b ^ rem[0];
                System.arraycopy(rem, 1, rem, 0, nsym - 1);
                rem[nsym - 1] = 0;
                for (int i = 0; i < nsym; i++) rem[i] ^= mul(div[i], factor);
            }
            int[] out = Arrays.copyOf(data, data.length + nsym);
            System.arraycopy(rem, 0, out, data.length, nsym);
            return out;
        }
    }

    // =========================================================================================
    // Colour raster: the carrier that replaces QR for screen-to-screen transfer
    //
    // The channel is a lossless, axis-aligned screen grab, so none of a camera code's defences
    // are needed. Each cell is one of five colours -- black, red, green, blue, white -- drawn at a
    // few device pixels square. Five is not a power of two, so two bytes ride in seven cells as a
    // base-5 number: 2.29 bits a cell against the 2.32 the alphabet allows. Five colours rather
    // than eight because they survive anything: a cell is read by which channel dominates, not by
    // matching a shade, so a dimmed, washed out or colour managed display still decodes.
    //
    // A grey rim locates the grid (grey never occurs in the payload) and a calibration row carries
    // all five colours so the decoder measures the display rather than assuming it. Reed-Solomon
    // repairs isolated cell errors; the frame CRC decides correctness, so on a clean capture the
    // error correction costs nothing at all.
    //
    //   quiet zone (white, 4 cells)
    //   +--------------------------------------+
    //   | rim (grey, 2 cells)                  |
    //   |  +--------------------------------+  |
    //   |  | calibration row: 5 colours ...  | |
    //   |  | data cells, row major ...       | |
    //   |  +--------------------------------+  |
    //   +--------------------------------------+
    // =========================================================================================

    static final class Raster implements Carrier {
        static final int QUIET = 4, RIM = 2, CAL_ROWS = 1;
        static final int RS_BLOCK = 255, RS_ECC = 20, RS_DATA = RS_BLOCK - RS_ECC;
        static final int GREY = 0x808080, GREY_TOL = 32;
        /** Black, red, green, blue, white. Two bytes ride in seven cells as a base-5 number. */
        static final int BLACK = 0, RED = 1, GREEN = 2, BLUE = 3, WHITE = 4;
        static final int SYMS = 5, GROUP = 7, GROUP_BYTES = 2;
        static final int[] PALETTE = {0x000000, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF};
        static boolean DEBUG;

        static void say(String s) { if (DEBUG) log("  raster: " + s); }

        static int colourOf(int sym) { return PALETTE[sym]; }

        record Geom(int x0, int y0, int cellPx, int cols, int rows) {
            int width() { return cols * cellPx; }
            int height() { return rows * cellPx; }
        }

        // ---- capacity arithmetic, identical on both sides -------------------------------

        static int dataCells(int cols, int rows) { return cols * (rows - CAL_ROWS); }
        static int capacityBytes(int cols, int rows) { return dataCells(cols, rows) / GROUP * GROUP_BYTES; }

        /** How the capacity is split into Reed-Solomon blocks. Both sides compute this identically. */
        record Layout(int blocks, int blockTotal, int ecc, int dataLen, int bodyLen) { }

        static final Layout NOTHING = new Layout(0, 0, 0, 0, 0);

        static Layout layout(int cols, int rows) {
            int n = capacityBytes(cols, rows);
            if (n < 16) return NOTHING;
            int nb = Math.max(1, (n + RS_BLOCK - 1) / RS_BLOCK);   // blocks of at most 255 bytes
            int bt = n / nb;
            if (bt < 8) return NOTHING;
            int e = Math.max(2, bt * RS_ECC / RS_BLOCK);           // about 8 per cent, as whole bytes
            if (bt - e < 1) return NOTHING;
            return new Layout(nb, bt, e, nb * (bt - e), nb * bt);
        }

        static int blocks(int cols, int rows) { return layout(cols, rows).blocks(); }
        static int dataLen(int cols, int rows) { return layout(cols, rows).dataLen(); }

        /** Bytes of GlassTransfer frame that fit in one raster of this size. */
        static int frameCapacity(int cols, int rows) { return Math.max(0, dataLen(cols, rows) - 4); }

        // ---- encoding (used by the self-test and by --make-raster) -----------------------

        static byte[] encode(byte[] frame, int cols, int rows) {
            Layout L = layout(cols, rows);
            if (L.blocks() == 0 || frame.length > L.dataLen() - 4) throw new IllegalArgumentException("frame does not fit the raster");
            int dpb = L.blockTotal() - L.ecc();
            byte[] body = new byte[L.bodyLen()];
            ByteBuffer.wrap(body).putInt(0, frame.length);
            System.arraycopy(frame, 0, body, 4, frame.length);
            int[] blk = new int[dpb];
            for (int i = 0; i < L.blocks(); i++) {
                for (int j = 0; j < dpb; j++) blk[j] = body[i * dpb + j] & 0xFF;
                int[] enc = RS.encode(blk, L.ecc());
                for (int j = 0; j < L.ecc(); j++) body[L.dataLen() + i * L.ecc() + j] = (byte) enc[dpb + j];
            }
            byte[] cells = new byte[cols * rows];
            for (int c = 0; c < cols; c++) cells[c] = (byte) (c % SYMS);
            int at = cols * CAL_ROWS, groups = dataCells(cols, rows) / GROUP;
            for (int gi = 0; gi < groups; gi++) {
                int p = gi * GROUP_BYTES;
                int v = ((p < body.length ? body[p] & 0xFF : 0) << 8) | (p + 1 < body.length ? body[p + 1] & 0xFF : 0);
                for (int k = GROUP - 1; k >= 0; k--, v /= SYMS) cells[at + gi * GROUP + k] = (byte) (v % SYMS);
            }
            return cells;
        }

        /** Renders cells to pixels exactly as the sender does, for offline tests. */
        static Img render(byte[] cells, int cols, int rows, int cellPx) {
            int edge = RIM + QUIET;
            int w = (cols + 2 * edge) * cellPx, h = (rows + 2 * edge) * cellPx;
            int[] px = new int[w * h];
            Arrays.fill(px, 0xFFFFFF);
            for (int y = 0; y < h; y++) {
                int cy = y / cellPx - edge, row = y * w;
                for (int x = 0; x < w; x++) {
                    int cx = x / cellPx - edge;
                    boolean inGrid = cx >= 0 && cx < cols && cy >= 0 && cy < rows;
                    boolean inRim = !inGrid && cx >= -RIM && cx < cols + RIM && cy >= -RIM && cy < rows + RIM;
                    if (inGrid) px[row + x] = colourOf(cells[cy * cols + cx]);
                    else if (inRim) px[row + x] = GREY;
                }
            }
            return new Img(px, w, h);
        }

        // ---- locating the grid ----------------------------------------------------------

        /**
         * What white looks like on this display, per channel: the level the brightest one per cent
         * of the region reaches. The quiet zone is white, so it is always in the picture. Every
         * other threshold is expressed against this, which is what lets a dimmed, tinted or
         * washed out render still be read.
         */
        record White(int r, int g, int b) {
            int mid(int k) { return (k == 0 ? r : k == 1 ? g : b) / 2; }
            int tol(int k) { return Math.max(GREY_TOL / 2, (k == 0 ? r : k == 1 ? g : b) / 4); }
        }

        static White white(Img img) {
            int[] hr = new int[256], hg = new int[256], hb = new int[256], px = img.px();
            int step = Math.max(1, px.length / 200000), n = 0;
            for (int i = 0; i < px.length; i += step, n++) {
                int v = px[i];
                hr[(v >>> 16) & 255]++; hg[(v >>> 8) & 255]++; hb[v & 255]++;
            }
            return new White(percentile(hr, n), percentile(hg, n), percentile(hb, n));
        }

        /** The level the brightest one per cent of the samples reach, floored well above black. */
        static int percentile(int[] hist, int n) {
            int want = Math.max(1, n / 100), seen = 0;
            for (int b = 255; b >= 0; b--) { seen += hist[b]; if (seen >= want) return Math.max(64, b); }
            return 255;
        }

        /** Mid-grey: each channel about half of white, and no channel far from the others. */
        static boolean isGrey(int p, White w) {
            int r = (p >>> 16) & 255, g = (p >>> 8) & 255, b = p & 255;
            return Math.abs(r - w.mid(0)) <= w.tol(0)
                && Math.abs(g - w.mid(1)) <= w.tol(1)
                && Math.abs(b - w.mid(2)) <= w.tol(2);
        }

        /** A run of consecutive rows or columns that are mostly grey. */
        record Band(int start, int len) { int end() { return start + len - 1; } }

        static List<Band> bands(int[] count, int th, int max) {
            List<Band> out = new ArrayList<>();
            for (int i = 0; i < count.length; ) {
                if (count[i] < th) { i++; continue; }
                int j = i;
                while (j < count.length && count[j] >= th) j++;
                out.add(new Band(i, j - i));
                i = j;
            }
            out.sort((a, b) -> b.len() - a.len());          // keep the most substantial few
            return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
        }

        /**
         * Finds the grid by projecting the grey pixels onto rows and columns. The rim shows up as
         * four bands of equal thickness; other grey things on screen, such as a window border, show
         * up as bands too, so every combination is tried and the largest one that verifies wins.
         */
        /**
         * Every rectangle in the region whose four grey bands are the same thickness, largest first.
         * More than one can survive that test -- anything grey on the page below or beside the code
         * makes a false rim of its own -- and nothing in the picture says which is right, so the
         * caller decides by trying to decode them. A rim one row too far out reads every cell
         * correctly and still fails, because the row count sets where the parity bytes begin.
         */
        static List<Geom> locate(Img img) {
            int w = img.w(), h = img.h();
            White wht = white(img);
            say(String.format("white level: %d,%d,%d", wht.r(), wht.g(), wht.b()));
            int[] px = img.px(), rowN = new int[h], colN = new int[w];
            for (int y = 0; y < h; y++) {
                int row = y * w, n = 0;
                for (int x = 0; x < w; x++) if (isGrey(px[row + x], wht)) { n++; colN[x]++; }
                rowN[y] = n;
            }
            int peakRow = 0, peakCol = 0;
            for (int v : rowN) peakRow = Math.max(peakRow, v);
            for (int v : colN) peakCol = Math.max(peakCol, v);
            say(String.format("grey peaks: row %d, column %d", peakRow, peakCol));
            if (peakRow < 16 || peakCol < 8) { say("not enough grey to be a rim"); return List.of(); }

            List<Band> rowBands = bands(rowN, Math.max(8, peakRow * 7 / 10), 6);
            List<Band> colBands = bands(colN, Math.max(4, peakCol * 7 / 10), 6);
            say("row bands " + rowBands + ", column bands " + colBands);

            List<Geom> found = new ArrayList<>();
            for (Band top : rowBands) for (Band bottom : rowBands) {
                if (bottom.start() <= top.end() || bottom.len() != top.len()) continue;
                int t = top.len();
                if (t < RIM || t % RIM != 0) continue;
                int cellPx = t / RIM;
                int innerH = bottom.start() - top.end() - 1;
                if (innerH < cellPx || innerH % cellPx != 0) continue;
                int rows = innerH / cellPx;
                if (rows < CAL_ROWS + 1) continue;
                for (Band left : colBands) for (Band right : colBands) {
                    if (right.start() <= left.end() || left.len() != t || right.len() != t) continue;
                    int innerW = right.start() - left.end() - 1;
                    if (innerW % cellPx != 0) continue;
                    int cols = innerW / cellPx;
                    if (cols < 8 || layout(cols, rows).blocks() == 0) continue;
                    Geom g = new Geom(left.end() + 1, top.end() + 1, cellPx, cols, rows);
                    if (verify(img, g, wht)) found.add(g);
                }
            }
            found.sort((p, q) -> Long.compare((long) q.cols() * q.rows(), (long) p.cols() * p.rows()));
            if (found.size() > MAX_CANDIDATES) found = new ArrayList<>(found.subList(0, MAX_CANDIDATES));
            if (found.isEmpty()) say("no combination of grey bands formed a rim");
            else say("candidate grids: " + found.stream()
                .map(g -> g.cols() + "x" + g.rows() + " at " + g.cellPx() + " px").toList());
            return found;
        }

        /** Cheap check that a cached geometry still describes what is on screen. */
        static boolean verify(Img img, Geom g) { return verify(img, g, white(img)); }

        static boolean verify(Img img, Geom g, White w) {
            if (g.x0() < g.cellPx() * RIM || g.y0() < g.cellPx() * RIM) return false;
            if (g.x0() + g.width() + g.cellPx() * RIM > img.w()) return false;
            if (g.y0() + g.height() + g.cellPx() * RIM > img.h()) return false;
            int t = g.cellPx() * RIM;
            for (int i = 1; i < 8; i++) {                       // sample the rim on all four sides
                int x = g.x0() + g.width() * i / 8, y = g.y0() + g.height() * i / 8;
                if (!isGrey(img.at(x, g.y0() - t / 2), w) || !isGrey(img.at(x, g.y0() + g.height() + t / 2 - 1), w)) return false;
                if (!isGrey(img.at(g.x0() - t / 2, y), w) || !isGrey(img.at(g.x0() + g.width() + t / 2 - 1, y), w)) return false;
            }
            for (int i = 1; i < 8; i++) {                       // the inside must not be grey too
                int x = g.x0() + g.width() * i / 8, y = g.y0() + g.height() * i / 8;
                if (!isGrey(img.at(x, y), w)) return true;
            }
            return false;
        }

        // ---- reading cells --------------------------------------------------------------

        /** Mean colour of one cell, packed as 0xRRGGBB. */
        static int cellMean(Img img, Geom g, int col, int row) {
            int cp = g.cellPx(), sr = 0, sg = 0, sb = 0;
            int px0 = g.x0() + col * cp, py0 = g.y0() + row * cp;
            for (int dy = 0; dy < cp; dy++) {
                int base = (py0 + dy) * img.w() + px0;
                for (int dx = 0; dx < cp; dx++) {
                    int v = img.px()[base + dx];
                    sr += (v >>> 16) & 255; sg += (v >>> 8) & 255; sb += v & 255;
                }
            }
            int n = cp * cp;
            return ((sr / n) << 16) | ((sg / n) << 8) | (sb / n);
        }

        /**
         * The colour each of the five symbols actually came back as, measured off the calibration
         * row on every frame. A cell is then read as whichever of the five it landed nearest to.
         *
         * Nearest to a measured colour, rather than any rule about which channel is strongest: on a
         * compressed capture a black cell arrives faintly tinted, and every dominance test calls
         * that red. Measuring instead of assuming is also what makes shade irrelevant, since a
         * dimmed or tinted display moves the references exactly as far as it moves the cells.
         */
        record Levels(int[] r, int[] g, int[] b) {
            /** Squared distance from a cell whose pixel sums are these, times the pixel count. */
            long distance(int sym, int sr, int sg, int sb, int area) {
                return (long) area * (r[sym] * r[sym] + g[sym] * g[sym] + b[sym] * b[sym])
                     - 2L * (sr * r[sym] + sg * g[sym] + sb * b[sym]);
            }
        }

        /** Two references closer together than this cannot be told apart, so the frame is refused. */
        static final int MIN_SEPARATION = 64;

        /** How many candidate rectangles are worth a decode attempt before giving up on a frame. */
        static final int MAX_CANDIDATES = 6;

        static Levels levels(Img img, Geom g) {
            long[] sr = new long[SYMS], sg = new long[SYMS], sb = new long[SYMS];
            int[] n = new int[SYMS], r = new int[SYMS], gr = new int[SYMS], b = new int[SYMS];
            for (int c = 0; c < g.cols(); c++) {
                int m = cellMean(img, g, c, 0), s = c % SYMS;
                sr[s] += (m >>> 16) & 255; sg[s] += (m >>> 8) & 255; sb[s] += m & 255; n[s]++;
            }
            for (int s = 0; s < SYMS; s++) {
                if (n[s] == 0) return null;                           // fewer cells than colours
                r[s] = (int) (sr[s] / n[s]); gr[s] = (int) (sg[s] / n[s]); b[s] = (int) (sb[s] / n[s]);
            }
            for (int i = 0; i < SYMS; i++)
                for (int j = i + 1; j < SYMS; j++) {
                    int dr = r[i] - r[j], dg = gr[i] - gr[j], db = b[i] - b[j];
                    if (dr * dr + dg * dg + db * db < MIN_SEPARATION * MIN_SEPARATION) return null;
                }
            Levels lv = new Levels(r, gr, b);
            int agree = 0;                                            // does the row read back as itself?
            for (int c = 0; c < g.cols(); c++) {
                int m = cellMean(img, g, c, 0);
                long bestD = Long.MAX_VALUE;
                int best = 0;
                for (int sym = 0; sym < SYMS; sym++) {
                    long d = lv.distance(sym, (m >>> 16) & 255, (m >>> 8) & 255, m & 255, 1);
                    if (d < bestD) { bestD = d; best = sym; }
                }
                if (best == c % SYMS) agree++;
            }
            if (agree * 10 < g.cols() * 9) return null;               // not a calibration row at all
            return lv;
        }

        static byte[] readCells(Img img, Geom g, Levels lv) {
            int cols = g.cols(), rows = g.rows(), cp = g.cellPx(), area = cp * cp;
            byte[] cells = new byte[cols * rows];
            long[] fixed = new long[SYMS];                           // the part that does not vary
            for (int s = 0; s < SYMS; s++)
                fixed[s] = (long) area * (lv.r()[s] * lv.r()[s] + lv.g()[s] * lv.g()[s] + lv.b()[s] * lv.b()[s]);
            int[] px = img.px();
            for (int r = 0; r < rows; r++) {
                int py0 = g.y0() + r * cp, out = r * cols;
                for (int c = 0; c < cols; c++) {
                    int px0 = g.x0() + c * cp, sr = 0, sg = 0, sb = 0;
                    for (int dy = 0; dy < cp; dy++) {
                        int base = (py0 + dy) * img.w() + px0;
                        for (int dx = 0; dx < cp; dx++) {
                            int v = px[base + dx];
                            sr += (v >>> 16) & 255; sg += (v >>> 8) & 255; sb += v & 255;
                        }
                    }
                    int best = 0;
                    long bestD = Long.MAX_VALUE;
                    for (int sym = 0; sym < SYMS; sym++) {
                        long d = fixed[sym] - 2L * (sr * lv.r()[sym] + sg * lv.g()[sym] + sb * lv.b()[sym]);
                        if (d < bestD) { bestD = d; best = sym; }
                    }
                    cells[out + c] = (byte) best;
                }
            }
            return cells;
        }

        // ---- cells back to bytes --------------------------------------------------------

        static byte[] cellsToBody(byte[] cells, int cols, int rows) {
            byte[] body = new byte[layout(cols, rows).bodyLen()];
            int at = cols * CAL_ROWS, groups = dataCells(cols, rows) / GROUP;
            for (int gi = 0; gi < groups; gi++) {
                int p = gi * GROUP_BYTES;
                if (p >= body.length) break;
                int v = 0, off = at + gi * GROUP;
                for (int k = 0; k < GROUP; k++) v = v * SYMS + (cells[off + k] & 0xFF);
                body[p] = (byte) (v >>> 8);                           // a misread group can land above
                if (p + 1 < body.length) body[p + 1] = (byte) v;      // 65535; Reed-Solomon sees bytes
            }
            return body;
        }

        /** Pulls the frame out of the data area; returns null if the length field is nonsense. */
        static byte[] frameOf(byte[] body, Layout L) {
            if (body.length < 4) return null;
            int len = ByteBuffer.wrap(body).getInt(0);
            if (len < Protocol.HEADER || len > L.dataLen() - 4) return null;
            return Arrays.copyOfRange(body, 4, 4 + len);
        }

        /**
         * The runner-up reading of every group of cells: the byte pair that comes out if the one
         * cell in the group that was the closest call is read as whatever it nearly was instead,
         * and how close that call was.
         *
         * Seven cells carry two bytes, so a single misread cell costs a block two of its parity
         * bytes, and a block that runs out of parity has usually just had a couple of borderline
         * cells fall the wrong way. They can be picked out: on a compressed capture, 86 per cent of
         * the bytes Reed-Solomon had to correct belonged to the most doubtful tenth of their block.
         *
         * Building this means a second pass over the image, so it is only done for a frame that has
         * already failed to come out whole.
         */
        record SecondGuess(long[] margin, byte[] hi, byte[] lo, int[] budget) { }

        static SecondGuess secondGuess(Img img, Geom g, Levels lv, byte[] cells) {
            int cols = g.cols(), at = cols * CAL_ROWS, groups = dataCells(cols, g.rows()) / GROUP;
            long[] margin = new long[groups];
            byte[] hi = new byte[groups], lo = new byte[groups];
            for (int gi = 0; gi < groups; gi++) {
                int doubtful = 0, instead = 0;
                long closest = Long.MAX_VALUE;
                for (int k = 0; k < GROUP; k++) {
                    int cell = gi * GROUP + k;
                    int m = cellMean(img, g, cell % cols, cell / cols + CAL_ROWS);
                    int r = (m >>> 16) & 255, gr = (m >>> 8) & 255, b = m & 255;
                    long d1 = Long.MAX_VALUE, d2 = Long.MAX_VALUE;
                    int s1 = 0, s2 = 0;
                    for (int sym = 0; sym < SYMS; sym++) {
                        long d = lv.distance(sym, r, gr, b, 1);
                        if (d < d1) { d2 = d1; s2 = s1; d1 = d; s1 = sym; }
                        else if (d < d2) { d2 = d; s2 = sym; }
                    }
                    if (d2 - d1 >= closest) continue;
                    closest = d2 - d1;
                    doubtful = k;
                    instead = s1 != (cells[at + cell] & 0xFF) ? s1 : s2;   // the colour it nearly was
                }
                int v = 0;
                for (int k = 0; k < GROUP; k++)
                    v = v * SYMS + (k == doubtful ? instead : cells[at + gi * GROUP + k] & 0xFF);
                margin[gi] = closest;
                hi[gi] = (byte) (v >>> 8);
                lo[gi] = (byte) v;
            }
            return new SecondGuess(margin, hi, lo, new int[] {PASS_BUDGET});
        }

        /**
         * How a block that will not correct is searched. Each row is {flips, candidates}: every way
         * of reading that many of the block's most doubtful groups the other way, drawn from that
         * many of the most doubtful. One flip rescues a block that is a single cell over its
         * parity; a noisier block needs several flipped together, and flipping them all at once
         * flips the innocent ones too, so it has to be tried as combinations. Wider and deeper
         * rows cost more, which is why they come last.
         */
        static final int[][] SEARCH = {{1, 24}, {2, 20}, {3, 16}, {4, 12}};

        /** Reed-Solomon passes one frame may spend on all its failing blocks together. */
        static final int PASS_BUDGET = 6000;

        /**
         * More failing blocks than this is not noise but a torn or covered frame, which no amount of
         * guessing repairs, and the next capture will be fine. Better to give up quickly.
         */
        static final int MAX_SECOND_BLOCKS = 6;

        /** Puts one group's other reading into a trial copy of a block. */
        static void flip(int[] trial, int gi, int first, int last, SecondGuess sg) {
            int p = gi * GROUP_BYTES;
            if (p >= first && p <= last) trial[p - first] = sg.hi()[gi] & 0xFF;
            if (p + 1 >= first && p + 1 <= last) trial[p + 1 - first] = sg.lo()[gi] & 0xFF;
        }

        /**
         * Tries a block again with some of its doubtful groups read the other way, fewest flips
         * first. Each attempt is a single Reed-Solomon pass, and Reed-Solomon checks its own work, so
         * a block that comes back clean has been repaired, not merely made to look repaired. The
         * frame's CRC is the final check on all of it.
         */
        static int secondLook(int[] blk, int block, int dpb, Layout L, SecondGuess sg) {
            int first = block * dpb, last = first + dpb - 1;
            int g0 = first / GROUP_BYTES, g1 = Math.min(last / GROUP_BYTES, sg.margin().length - 1);
            if (g1 < g0) return -1;
            Integer[] order = new Integer[g1 - g0 + 1];
            for (int i = 0; i < order.length; i++) order[i] = g0 + i;
            java.util.Arrays.sort(order, (x, y) -> Long.compare(sg.margin()[x], sg.margin()[y]));
            int[] trial = new int[blk.length];
            for (int[] row : SEARCH) {
                int depth = row[0], k = Math.min(row[1], order.length);
                if (k < depth) continue;
                int[] idx = new int[depth];
                for (int i = 0; i < depth; i++) idx[i] = i;
                while (true) {
                    if (sg.budget()[0]-- <= 0) return -1;
                    System.arraycopy(blk, 0, trial, 0, blk.length);
                    for (int d = 0; d < depth; d++) flip(trial, order[idx[d]], first, last, sg);
                    try {
                        int fixed = RS.correct(trial, L.ecc());
                        System.arraycopy(trial, 0, blk, 0, blk.length);
                        say(String.format("block %d: %d doubtful group(s) read the other way, then %d byte(s) corrected",
                            block, depth, fixed));
                        return fixed;
                    } catch (IllegalStateException e) {
                        // not those cells; next combination
                    }
                    int i = depth - 1;                              // advance to the next combination
                    while (i >= 0 && idx[i] == k - depth + i) i--;
                    if (i < 0) break;
                    idx[i]++;
                    for (int j = i + 1; j < depth; j++) idx[j] = idx[j - 1] + 1;
                }
            }
            return -1;
        }

        /** Reed-Solomon over every block; returns the number of repaired bytes, or -1 if beyond repair. */
        static int repair(byte[] body, Layout L) { return repair(body, L, null); }

        static int repair(byte[] body, Layout L, SecondGuess sg) {
            int dpb = L.blockTotal() - L.ecc(), corrected = 0;
            int[] blk = new int[L.blockTotal()];
            List<Integer> failed = new ArrayList<>();
            for (int i = 0; i < L.blocks(); i++) {                      // the plain pass over every block
                load(body, L, i, dpb, blk);
                try { corrected += RS.correct(blk, L.ecc()); }
                catch (IllegalStateException e) {
                    failed.add(i);
                    // Without a second guess to try, or with too many to be noise, one is enough to know.
                    if (!DEBUG && (sg == null || failed.size() > MAX_SECOND_BLOCKS)) return -1;
                    continue;
                }
                store(body, i, dpb, blk);
            }
            if (failed.isEmpty()) return corrected;

            boolean look = sg != null && failed.size() <= MAX_SECOND_BLOCKS;
            int rescued = 0;
            List<Integer> lost = new ArrayList<>();
            for (int i : failed) {
                int fixed = -1;
                if (look) {
                    load(body, L, i, dpb, blk);                         // RS may have scribbled on it
                    fixed = secondLook(blk, i, dpb, L, sg);
                }
                if (fixed < 0) { lost.add(i); continue; }
                store(body, i, dpb, blk);
                corrected += fixed;
                rescued++;
            }
            if (rescued > 0) say(rescued + " of " + failed.size() + " failing block(s) rescued by a second look");
            if (!lost.isEmpty()) {
                say(String.format("%d of %d blocks beyond repair (%s), %d bytes fixed elsewhere;"
                    + " each block holds %d bytes and can fix %d%s",
                    lost.size(), L.blocks(), lost.size() > 8 ? "first " + lost.get(0) : lost.toString(), corrected,
                    L.blockTotal(), L.ecc() / 2,
                    look && sg.budget()[0] <= 0 ? "; the search budget ran out" : ""));
                return -1;
            }
            return corrected;
        }

        static void load(byte[] body, Layout L, int i, int dpb, int[] blk) {
            for (int j = 0; j < dpb; j++) blk[j] = body[i * dpb + j] & 0xFF;
            for (int j = 0; j < L.ecc(); j++) blk[dpb + j] = body[L.dataLen() + i * L.ecc() + j] & 0xFF;
        }

        static void store(byte[] body, int i, int dpb, int[] blk) {
            for (int j = 0; j < dpb; j++) body[i * dpb + j] = (byte) blk[j];
        }

        /** Diagnostic: do the error-correction bytes in this image agree with its data bytes? */
        static int checkEcc(Img img) {
            Raster r = new Raster();
            if (r.read(img).kind() != Kind.OK || r.geo == null) return -2;
            Levels lv = levels(img, r.geo);
            if (lv == null) return -2;
            byte[] cells = readCells(img, r.geo, lv);
            byte[] body = cellsToBody(cells, r.geo.cols(), r.geo.rows());
            Layout L = layout(r.geo.cols(), r.geo.rows());
            int fixed = repair(body, L);
            if (fixed >= 0) return fixed;
            body = cellsToBody(cells, r.geo.cols(), r.geo.rows());   // as the decoder does, second look
            return repair(body, L, secondGuess(img, r.geo, lv, cells));
        }

        // ---- carrier ---------------------------------------------------------------------

        Geom geo;
        long lastHash;
        Out lastOut;
        int fails;

        static long hashCells(byte[] cells) {                  // sparse, for change detection
            long h = 0x9E3779B97F4A7C15L;
            int step = Math.max(1, cells.length / 4096);
            for (int i = 0; i < cells.length; i += step) h = (h ^ cells[i]) * 0x100000001B3L;
            return h ^ cells.length;
        }

        @Override public void forget() { geo = null; lastOut = null; }

        @Override public String name() { return "raster"; }

        @Override public Out read(Img img) {
            if (geo != null && !verify(img, geo)) geo = null;
            if (geo != null) {                                          // the rectangle that worked last
                Out out = attempt(img, geo, true);
                if (out.kind() == Kind.OK || !out.changed()) { fails = 0; return out; }
                if (++fails < 5) return out;                            // a torn frame; keep the geometry
                geo = null;
                fails = 0;
            }
            Out first = null;                                           // else try each candidate rim
            for (Geom g : locate(img)) {
                Out out = attempt(img, g, false);
                if (out.kind() == Kind.OK) {
                    geo = g;
                    say(String.format("locked on to %dx%d cells at %d px", g.cols(), g.rows(), g.cellPx()));
                    return out;
                }
                if (first == null) first = out;
            }
            return first != null ? first : new Out(Kind.NONE, true, null, "", null);
        }

        /** One decode against one candidate rectangle. Kind.OK only when a frame comes out whole. */
        Out attempt(Img img, Geom g, boolean cached) {
            Rectangle bounds = new Rectangle(g.x0(), g.y0(), g.width(), g.height());
            Levels lv = levels(img, g);
            if (lv == null) return lastOut = new Out(Kind.NONE, true, null, "", bounds);
            byte[] cells = readCells(img, g, lv);
            long h = hashCells(cells);
            if (cached && lastOut != null && h == lastHash)
                return new Out(lastOut.kind(), false, lastOut.bytes(), lastOut.info(), bounds);
            lastHash = h;

            Layout L = layout(g.cols(), g.rows());
            byte[] body = cellsToBody(cells, g.cols(), g.rows());
            byte[] frame = frameOf(body, L);
            if (frame != null && Protocol.parse(frame) != null)         // clean capture: no repair needed
                return lastOut = new Out(Kind.OK, true, frame,
                    String.format("%dx%d cells, %d px, no repair", g.cols(), g.rows(), g.cellPx()), bounds);

            int corrected = repair(body, L);                            // something was misread
            if (corrected < 0) {                                        // a block ran out of parity
                body = cellsToBody(cells, g.cols(), g.rows());           // start over from the cells
                corrected = repair(body, L, secondGuess(img, g, lv, cells));
            }
            if (corrected >= 0) {
                frame = frameOf(body, L);
                if (frame != null && Protocol.parse(frame) != null)
                    return lastOut = new Out(Kind.OK, true, frame,
                        String.format("%dx%d cells, %d px, repaired %d byte(s)", g.cols(), g.rows(), g.cellPx(), corrected), bounds);
            }
            return lastOut = new Out(Kind.FAIL, true, null, "", bounds);
        }
    }

    // =========================================================================================
    // Transfer state machine
    // =========================================================================================

    static final class Transfer {
        enum State { ARMING, TRANSFERRING }

        static final Color GREY = new Color(120, 120, 120), GREEN = new Color(0, 160, 0), ORANGE = new Color(225, 140, 0),
                           RED = new Color(200, 30, 30), BLUE = new Color(30, 90, 220);

        final Config cfg;
        final Capture cap;
        final Keys keys;
        final Border border;
        final Carrier carrier;
        final long armNanos;

        State state = State.ARMING;
        long greenSince, lastValidAt, lastUiAt;
        boolean armHasId;
        int armFileId;
        Integer ignoreEndOf;
        Color uiColor = GREY;
        String uiText = "No QR detected";
        String title = "";

        // Current transfer. have[] is the authority on what has arrived; chunks may arrive out of
        // order, and any that are still missing when the END code appears are fetched afterwards.
        int fileId, total, chunkSize;
        boolean[] have, dropped;
        int haveCount, lowest, resumeCount, dropCount;
        long chunksDone, startAt, lastKeyAt, firstKeyAt, answerNs, lastProgressAt;
        boolean warnedKeys, outstanding;
        boolean retried, lastSeekTyped;
        int seeks, typedSeekFails, lastSeekTarget, sinceMapWrite, steppedSeeks, resends;
        FileChannel ch;
        Path part, map;

        long capNs, decNs, frames;
        int dumps, misses;

        Transfer(Config cfg, Capture cap, Keys keys, Border border, Carrier carrier) {
            this.cfg = cfg; this.cap = cap; this.keys = keys; this.border = border; this.carrier = carrier;
            armNanos = (long) (cfg.armSeconds * 1e9);
        }

        void ui(Color c, String t) { uiColor = c; uiText = t; }

        static final boolean TTY = System.console() != null;   // only a real terminal gets escapes

        /** Percentage-bearing title for the terminal and the receiver's windows. */
        void setTitle(String t) {
            if (t.equals(title)) return;
            title = t;
            if (TTY) { System.out.print("\u001b]0;" + t + "\u0007"); System.out.flush(); }
            border.setTitle(t);
        }

        int percent() { return total <= 0 ? 100 : (int) (100L * haveCount / total); }

        void run() throws Exception {
            log("Watching the region for the " + carrier.name() + " carrier. Click into the sender's chunk field;"
                + " the transfer starts after " + cfg.armSeconds + " s of continuous detection.");
            setTitle("GlassTransfer - waiting for a code");
            while (true) {
                long t0 = System.nanoTime();
                Img img = cap.grab();
                long t1 = System.nanoTime();
                Carrier.Out r = carrier.read(img);
                long now = System.nanoTime();
                capNs += t1 - t0; decNs += now - t1; frames++;
                if (r.kind() == Kind.OK) { cap.tighten(r.bounds()); misses = 0; }
                else if (r.changed() && ++misses >= 25) { cap.widen(); carrier.forget(); misses = 0; }
                Protocol.Frame f = r.kind() == Kind.OK ? Protocol.parse(r.bytes()) : null;
                if (cfg.verbose && r.changed()) log("frame " + r.kind() + (f != null ? " " + f : "") + "  " + r.info());
                try {
                    if (state == State.ARMING) arming(r, f, now); else transferring(r, f, img, now);
                } catch (IOException e) {
                    log("I/O error: " + e);
                    abort();
                }
                if (now - lastUiAt > 50_000_000L) { border.setStatus(uiColor, uiText); lastUiAt = now; }
                if (!r.changed()) LockSupport.parkNanos(1_000_000L);
                else if (state == State.ARMING) LockSupport.parkNanos(2_000_000L);
            }
        }

        // ---- arming ------------------------------------------------------------------------

        void arming(Carrier.Out r, Protocol.Frame f, long now) throws IOException {
            boolean ignoredEnd = f != null && f.type() == Protocol.END && ignoreEndOf != null && ignoreEndOf == f.fileId();
            boolean armable = f != null && !ignoredEnd && (f.type() == Protocol.DATA || f.total() == 0);
            if (armable) {
                if (!armHasId || armFileId != f.fileId()) { armHasId = true; armFileId = f.fileId(); greenSince = now; }
                else if (greenSince == 0) greenSince = now;
                lastValidAt = now;
                double left = (armNanos - (now - greenSince)) / 1e9;
                if (left <= 0) { begin(f, now); return; }
                ui(GREEN, String.format("Code detected (chunk %d of %d) - starting in %.0f s", f.index(), f.total(), Math.ceil(left)));
                setTitle(String.format("GlassTransfer - starting in %.0f s", Math.ceil(left)));
            } else if (f != null) {
                lastValidAt = now;
                greenSince = 0;
                if (ignoredEnd) ui(BLUE, "Done. Show the next file's chunk to start again");
                else ui(GREEN, "END frame shown - navigate to a data chunk to start");
            } else if (now - lastValidAt > 300_000_000L) {
                greenSince = 0;
                ui(GREY, r.kind() == Kind.FAIL ? "Code seen but not decodable" : "No code detected in the region");
                setTitle("GlassTransfer - waiting for a code");
            }
        }

        void begin(Protocol.Frame f, long now) throws IOException {
            fileId = f.fileId(); total = f.total(); chunkSize = f.chunkSize();
            have = new boolean[Math.max(total, 0)];
            dropped = new boolean[Math.max(total, 0)];
            haveCount = 0; lowest = 0; chunksDone = 0; sinceMapWrite = 0;
            seeks = 0; typedSeekFails = 0; retried = false; lastSeekTyped = false;
            steppedSeeks = 0; resends = 0; answerNs = 0; warnedKeys = false; outstanding = false;
            Files.createDirectories(cfg.outDir);
            part = cfg.outDir.resolve(String.format("%08x.part", fileId));
            map = cfg.outDir.resolve(String.format("%08x.map", fileId));
            loadMap();
            resumeCount = haveCount;
            ch = FileChannel.open(part, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            if (haveCount == 0) ch.truncate(0);
            state = State.TRANSFERRING;
            startAt = now; lastValidAt = now; lastKeyAt = now; lastProgressAt = now;
            log(String.format("Transfer started: fileId %08x, %d chunks of %d bytes, %s", fileId, total, chunkSize,
                haveCount > 0 ? "resuming with " + haveCount + " of " + total + " chunks already received" : "from chunk 0"));
            onFrame(f, now);
        }

        // ---- the received-chunk map, so a resumed transfer knows exactly what is missing ----

        void loadMap() {
            try {
                if (total <= 0 || !Files.exists(map) || !Files.exists(part)) return;
                byte[] b = Files.readAllBytes(map);
                if (b.length < 14 + (total + 7) / 8) return;
                if (b[0] != 'G' || b[1] != 'T' || b[2] != 'M' || b[3] != 'A' || b[4] != 'P' || b[5] != '1') return;
                ByteBuffer bb = ByteBuffer.wrap(b);
                if (bb.getInt(6) != total || bb.getInt(10) != chunkSize) return;
                long size = Files.size(part);
                int cnt = 0, top = -1;
                for (int i = 0; i < total; i++)
                    if ((b[14 + (i >>> 3)] & (1 << (i & 7))) != 0) { have[i] = true; cnt++; top = i; }
                if (top >= 0 && size < (long) top * chunkSize + 1) { Arrays.fill(have, false); return; }
                haveCount = cnt;
            } catch (IOException e) {
                Arrays.fill(have, false);
                haveCount = 0;
            }
        }

        void saveMap() {
            if (total <= 0) return;
            try {
                byte[] b = new byte[14 + (total + 7) / 8];
                b[0] = 'G'; b[1] = 'T'; b[2] = 'M'; b[3] = 'A'; b[4] = 'P'; b[5] = '1';
                ByteBuffer bb = ByteBuffer.wrap(b);
                bb.putInt(6, total); bb.putInt(10, chunkSize);
                for (int i = 0; i < total; i++) if (have[i]) b[14 + (i >>> 3)] |= (byte) (1 << (i & 7));
                Files.write(map, b);
            } catch (IOException e) {
                log("cannot write " + map.getFileName() + ": " + e);
            }
        }

        /** Next chunk still needed, preferring the one after `shown` so the sweep runs forward. */
        int nextNeeded(int shown) {
            while (lowest < total && have[lowest]) lowest++;
            if (lowest >= total) return -1;
            for (int j = Math.max(shown + 1, 0); j < total; j++) if (!have[j]) return j;
            return lowest;
        }

        // ---- transferring --------------------------------------------------------------

        void transferring(Carrier.Out r, Protocol.Frame f, Img img, long now) throws IOException {
            if (f != null) lastValidAt = now;
            if (!r.changed()) { idle(now, f); return; }
            if (f == null) {                       // blank canvas or torn frame: the browser is reacting, wait
                lastKeyAt = now;
                if (r.kind() == Kind.FAIL) dumpFail(img);
                return;
            }
            onFrame(f, now);
        }

        /**
         * A frame arrived, so the browser is listening. How long it took is worth remembering: the
         * measure rises to any slow frame at once and comes down gently, so the receiver keeps to
         * the slowest pace the client has actually shown rather than to a number picked here.
         */
        void noteAnswer(long now) {
            lastProgressAt = now;
            boolean alone = resends == 0;                            // one key produced this frame
            resends = 0;
            if (warnedKeys) { log("The browser is answering again"); warnedKeys = false; }
            if (!outstanding) return;                                // nothing was waiting on a key
            outstanding = false;
            answerNs = paceAfter(answerNs, now - firstKeyAt, alone);
        }

        /**
         * How the measure of a client's pace moves when a frame took this long. It rises to a slow
         * frame at once and comes down only in eighths, and only when that frame answered a single
         * key: a frame that arrived while several keys were queued says nothing about how long one
         * of them takes, and believing it is what makes a receiver outrun a slow browser.
         */
        static long paceAfter(long answerNs, long took, boolean alone) {
            if (took <= 0 || took > 30_000_000_000L) return answerNs;
            if (took > answerNs) return took;
            return alone ? (answerNs * 7 + took) / 8 : answerNs;
        }

        /** How long to wait for a frame before treating the key as missed rather than slow. */
        static long patienceFor(long answerNs) {
            if (answerNs == 0) return 2_000_000_000L;                // nothing measured yet
            return Math.max(400_000_000L, Math.min(15_000_000_000L, answerNs * 4));
        }

        /** Deaf, as opposed to slow: quiet for far longer than it ever takes, over several keys. */
        static boolean deafFor(long answerNs, int resends, long sinceProgressNs) {
            return resends >= 3 && sinceProgressNs > Math.max(4_000_000_000L, answerNs * 6);
        }

        /** Notes a key going out. The oldest one still unanswered is the one being timed. */
        void keySent(long now) {
            if (!outstanding) { firstKeyAt = now; outstanding = true; }
            lastKeyAt = now;
        }

        /**
         * Four times what this client has been taking: a client that needs two seconds a frame is
         * answering, not deaf, and sending it another key only makes it skip one.
         */
        long patience() { return patienceFor(answerNs); }

        void onFrame(Protocol.Frame f, long now) throws IOException {
            noteAnswer(now);
            if (f.fileId() != fileId) {
                log(String.format("A different file (id %08x) is shown; stopping this transfer, %s is kept for resume", f.fileId(), part.getFileName()));
                abort();
                return;
            }
            if (lastSeekTyped) {
                if (f.index() != lastSeekTarget) typedSeekMissed("chunk " + f.index() + " came up instead");
                else {
                    if (typedSeekFails >= 2) log("The chunk field is taking numbers again");
                    typedSeekFails = 0;
                    lastSeekTyped = false;
                }
            }
            if (f.type() == Protocol.DATA) {
                store(f, now);
                drive(f.index(), now);
            } else if (haveCount >= total) {
                finish(f, now);
            } else {
                drive(total, now);                 // the END code is up but chunks are still missing
            }
        }

        /** Writes a chunk if it is new. Positional and idempotent, so order does not matter. */
        void store(Protocol.Frame f, long now) throws IOException {
            int i = f.index();
            if (i < 0 || i >= total) return;
            int len = f.payload().length;
            boolean last = i == total - 1;
            if (len > chunkSize || (!last && len != chunkSize)) {
                log("Chunk " + i + " has an unexpected length " + len + "; ignored");
                return;
            }
            if (have[i]) return;
            if (cfg.drop > 0 && i % cfg.drop == 0 && !dropped[i]) {   // testing only
                dropped[i] = true;
                dropCount++;
                if (cfg.verbose) log("dropping chunk " + i + " on purpose");
                return;
            }
            ch.write(ByteBuffer.wrap(f.payload()), (long) i * chunkSize);
            have[i] = true;
            haveCount++;
            chunksDone++;
            if (++sinceMapWrite >= 100) { saveMap(); sinceMapWrite = 0; }
            report(now);
        }

        /** Asks the sender for the next chunk we need, stepping when it is simply the next one. */
        void drive(int shown, long now) {
            keySent(now);
            int target = nextNeeded(shown);
            if (target < 0) { seekTo(shown, total, now); return; }   // complete: go to the END code
            if (target < shown || shown >= total) {                  // going back for something missed
                int missing = total - haveCount;
                ui(ORANGE, String.format("%d%%   fetching %d missing chunk%s, next is %d",
                    percent(), missing, missing == 1 ? "" : "s", target));
                setTitle(String.format("GlassTransfer %d%% - %d missing", percent(), missing));
            }
            if (target == shown + 1) { keys.up(); return; }
            seekTo(shown, target, now);
        }

        /**
         * Asks for one particular chunk by typing its number into the sender's chunk field and
         * pressing Enter. Every request for a chunk that is not simply the next one goes this way,
         * however near it is: two presses of Down are no more reliable than the number itself, and
         * the number says what is wanted rather than describing the way to it.
         *
         * Stepping is kept for the case where the digits are not arriving -- a keyboard layout that
         * puts something else on the number row, say. The sender's field is checked against what was
         * asked for on the very next frame, and after two misses the receiver steps instead. It goes
         * back to typing every fourth stepped seek, so one bad seek cannot stop it asking by number
         * for the rest of the transfer.
         */
        /**
         * A number was typed into the chunk field and the chunk asked for did not come up -- either
         * something else did, or nothing did at all. Two of those in a row and the receiver stops
         * trusting the digits and steps with the arrow keys instead.
         */
        void typedSeekMissed(String why) {
            lastSeekTyped = false;
            if (++typedSeekFails == 2)
                log("The chunk field is not taking the numbers typed into it (asked for "
                    + lastSeekTarget + ", " + why + "); stepping with the arrow keys instead");
        }

        void seekTo(int shown, int target, long now) {
            int diff = target - shown;
            if (diff == 0) return;
            seeks++;
            if (typedSeekFails < 2) {
                keys.typeDigits(target);                          // the field is selected, so this
                keys.enter();                                     // replaces whatever it held
                lastSeekTyped = true;
                lastSeekTarget = target;
                if (cfg.verbose) log("seek: asked for chunk " + target + " by number (showing " + shown + ")");
            } else {
                if (diff > 0) keys.up(); else keys.down();         // one step per frame, so that a
                lastSeekTyped = false;                            // slow client is never outrun
                if (++steppedSeeks % 4 == 0) typedSeekFails = 0;   // see whether digits work again
                if (cfg.verbose) log("seek: stepped one towards " + target + " (showing " + shown + ")");
            }
            keySent(now);
        }

        void report(long now) {
            double secs = Math.max(1e-3, (now - startAt) / 1e9);
            double kbps = chunksDone * (double) chunkSize / 1024.0 / secs;
            int pct = percent();
            int missing = total - haveCount;
            ui(GREEN, String.format("%d%%   chunk %d / %d   %.1f KB/s%s", pct, haveCount, total, kbps, pace()));
            setTitle(String.format("GlassTransfer %d%% - %d/%d chunks", pct, haveCount, total));
            if (chunksDone % 100 == 0 || haveCount == total) {
                String s = String.format("%d%%  %d/%d chunks  %.1f chunks/s  %.1f KB/s", pct, haveCount, total, chunksDone / secs, kbps);
                if (missing > 0 && haveCount + missing == total && lowest < total && !have[lowest]) s += "  (" + missing + " still missing)";
                s += pace();
                if (cfg.stats) s += String.format("  capture %.1f ms  decode %.1f ms  polls %d", capNs / 1e6 / frames, decNs / 1e6 / frames, frames);
                log(s);
            }
        }

        /** Worth saying out loud only when the browser, rather than the screen, sets the pace. */
        String pace() {
            return answerNs > 500_000_000L ? String.format("  browser %.1f s a frame", answerNs / 1e9) : "";
        }

        void idle(long now, Protocol.Frame f) throws IOException {
            if (f != null && f.fileId() == fileId) {
                // Slow and deaf look the same for a moment. Only the browser that has gone quiet for
                // far longer than it has ever taken to answer, across several keys, is called deaf.
                boolean deaf = deafFor(answerNs, resends, now - lastProgressAt);
                if (deaf) {
                    ui(RED, "Browser not receiving keys - click the sender's chunk field");
                    if (!warnedKeys) {
                        warnedKeys = true;
                        log(String.format("Nothing from the browser for %.1f s over %d key press(es);"
                            + " click the sender's chunk field", (now - lastProgressAt) / 1e9, resends));
                    }
                }
                if (now - lastKeyAt > (deaf ? Math.max(2_000_000_000L, patience()) : patience())) {
                    if (lastSeekTyped) typedSeekMissed("nothing came up at all");
                    resends++;
                    if (f.type() == Protocol.DATA) { store(f, now); drive(f.index(), now); }
                    else if (haveCount >= total) finish(f, now);
                    else drive(total, now);
                }
            }
            if (f == null && now - lastValidAt > 2_000_000_000L) ui(ORANGE, "Signal lost - no code in the region");
            if (now - lastValidAt > 60_000_000_000L) {
                log("No code for 60 s; closing the file, " + part.getFileName() + " is kept for resume");
                abort();
            }
        }

        record Digests(long size, int crc, byte[] sha) { }

        /** Reads the finished file back and digests what is actually on disk. */
        static Digests digestsOf(Path p) throws IOException {
            CRC32 crc = new CRC32();
            MessageDigest md;
            try { md = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException e) { throw new IOException("SHA-256 unavailable", e); }
            long size = 0;
            byte[] buf = new byte[1 << 20];
            try (InputStream in = Files.newInputStream(p)) {
                int n;
                while ((n = in.read(buf)) > 0) { crc.update(buf, 0, n); md.update(buf, 0, n); size += n; }
            }
            return new Digests(size, (int) crc.getValue(), md.digest());
        }

        static String hex(byte[] b) {
            StringBuilder s = new StringBuilder(b.length * 2);
            for (byte x : b) s.append(String.format("%02x", x));
            return s.toString();
        }

        void finish(Protocol.Frame f, long now) throws IOException {
            Protocol.EndInfo e = Protocol.parseEnd(f);
            if (e == null) { log("END frame is malformed; ignored"); return; }
            if (haveCount < total) { drive(total, now); return; }
            ch.force(false);
            ch.close();
            ch = null;
            Digests d = digestsOf(part);
            boolean sizeOk = d.size() == e.size(), crcOk = d.crc() == e.crc(), shaOk = Arrays.equals(d.sha(), e.sha());
            if (!sizeOk || !crcOk || !shaOk) {
                log(String.format("VERIFY FAILED: size %d vs %d, crc32 %08x vs %08x, sha256 %s", d.size(), e.size(), d.crc(), e.crc(),
                    shaOk ? "ok" : hex(d.sha()) + " vs " + hex(e.sha())));
                if (!retried) {
                    retried = true;
                    log("Fetching the whole file again from chunk 0");
                    ch = FileChannel.open(part, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
                    ch.truncate(0);
                    Arrays.fill(have, false);
                    haveCount = 0; lowest = 0; chunksDone = 0; resumeCount = 0; startAt = now;
                    saveMap();
                    drive(total, now);
                    return;
                }
                log("Giving up on this file; deleting " + part.getFileName());
                Files.deleteIfExists(part);
                Files.deleteIfExists(map);
                toArming();
                ui(RED, "Verification failed twice - transfer abandoned");
                return;
            }
            Files.deleteIfExists(map);
            Path target = uniquePath(cfg.outDir, sanitize(e.name()));
            Files.move(part, target);
            double secs = Math.max(1e-3, (now - startAt) / 1e9);
            log(String.format("DONE  %s  %s in %.1f s  (%.1f chunks/s, %.1f KB/s, %d seeks)", target.toAbsolutePath(), fmtBytes(d.size()),
                secs, chunksDone / secs, chunksDone * (double) chunkSize / 1024.0 / secs, seeks));
            log("verified: size " + d.size() + ", crc32 " + String.format("%08x", d.crc()) + ", sha256 " + hex(d.sha()));
            if (dropCount > 0) log(dropCount + " chunk(s) were dropped on purpose and refetched");
            if (cfg.stats) log(String.format("stats: capture %.1f ms  decode %.1f ms  polls %d", capNs / 1e6 / frames, decNs / 1e6 / frames, frames));
            setTitle("GlassTransfer 100% - " + target.getFileName());
            if (cfg.once) { border.setStatus(BLUE, "Done: " + target.getFileName()); System.exit(0); }
            ignoreEndOf = fileId;
            toArming();
            ui(BLUE, "Done: " + target.getFileName() + " - show the next file to continue");
        }

        void abort() {
            if (ch != null) {
                try { ch.force(false); ch.close(); } catch (IOException ignored) { }
                ch = null;
                saveMap();
            }
            toArming();
            ui(GREY, "Transfer stopped");
            setTitle("GlassTransfer - stopped");
        }

        void toArming() {
            state = State.ARMING;
            greenSince = 0;
            armHasId = false;
        }

        void dumpFail(Img img) {
            if (cfg.dumpFail == null || dumps >= 20) return;
            try {
                Path dir = Paths.get(cfg.dumpFail);
                Files.createDirectories(dir);
                ImageIO.write(img.toImage(), "png", dir.resolve(String.format("fail_%02d.png", dumps++)).toFile());
            } catch (IOException e) {
                log("cannot dump frame: " + e);
            }
        }

        static String sanitize(String name) {
            String n = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
            if (n.isEmpty() || n.equals(".") || n.equals("..")) n = "received.bin";
            return n.length() > 200 ? n.substring(0, 200) : n;
        }

        static Path uniquePath(Path dir, String name) {
            Path p = dir.resolve(name);
            if (!Files.exists(p)) return p;
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
            for (int i = 2; ; i++) {
                p = dir.resolve(base + " (" + i + ")" + ext);
                if (!Files.exists(p)) return p;
            }
        }
    }

    // =========================================================================================
    // Self-test: RS, BCH, tables, protocol, and a full encode -> render -> decode round trip
    // =========================================================================================

    static final class SelfTest {
        static int failures;

        static void check(boolean ok, String what) {
            if (!ok) { failures++; System.out.println("FAIL  " + what); }
        }

        static boolean run() {
            Random rnd = new Random(42);

            // CRC32 / protocol frame layout
            byte[] payload = new byte[300];
            rnd.nextBytes(payload);
            byte[] fb = Protocol.build(Protocol.DATA, 0x1234ABCD, 7, 9, 300, payload);
            Protocol.Frame f = Protocol.parse(fb);
            check(f != null && f.fileId() == 0x1234ABCD && f.index() == 7 && f.total() == 9 && f.chunkSize() == 300
                && Arrays.equals(f.payload(), payload), "protocol frame round trip");
            fb[40] ^= 1;
            check(Protocol.parse(fb) == null, "protocol frame CRC rejects corruption");

            // END frame: size, crc32, sha256 and name survive the round trip
            byte[] sha = new byte[32];
            rnd.nextBytes(sha);
            byte[] endPayload = new byte[Protocol.END_FIXED + 9];
            ByteBuffer eb = ByteBuffer.wrap(endPayload);
            eb.putLong(0, 1234567890123L);
            eb.putInt(8, 0xCAFEBABE);
            System.arraycopy(sha, 0, endPayload, 12, 32);
            eb.putShort(44, (short) 9);
            System.arraycopy("hello.bin".getBytes(StandardCharsets.UTF_8), 0, endPayload, Protocol.END_FIXED, 9);
            Protocol.Frame ef = Protocol.parse(Protocol.build(Protocol.END, 5, 4, 4, 2932, endPayload));
            Protocol.EndInfo ei = ef == null ? null : Protocol.parseEnd(ef);
            check(ei != null && ei.size() == 1234567890123L && ei.crc() == 0xCAFEBABE
                && Arrays.equals(ei.sha(), sha) && ei.name().equals("hello.bin"), "END frame carries size, crc32, sha256 and name");

            // A frame built for a different protocol version must be rejected outright
            byte[] old = Protocol.build(Protocol.DATA, 1, 0, 1, 300, payload);
            old[2] = (byte) 0x10;
            check(Protocol.parse(old) == null, "a frame from another protocol version is rejected");

            // SHA-256 of the receiver matches the JDK for a known vector
            try {
                byte[] abc = MessageDigest.getInstance("SHA-256").digest("abc".getBytes(StandardCharsets.UTF_8));
                check(Transfer.hex(abc).equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
                    "SHA-256 of \"abc\" matches the published vector");
            } catch (NoSuchAlgorithmException e) { check(false, "SHA-256 available"); }

            // Reed-Solomon
            for (int nsym : new int[] {7, 10, 18, 30}) {
                int n = Math.min(255, nsym * 5);
                int[] data = new int[n - nsym];
                for (int i = 0; i < data.length; i++) data[i] = rnd.nextInt(256);
                int[] cw = RS.encode(data, nsym);
                check(Arrays.equals(RS.syndromes(cw, nsym), new int[nsym]), "RS encode gives zero syndromes nsym=" + nsym);
                int t = nsym / 2, okCount = 0;
                for (int trial = 0; trial < 30; trial++) {
                    int[] c = cw.clone();
                    corrupt(c, t, rnd);
                    try { RS.correct(c, nsym); if (Arrays.equals(c, cw)) okCount++; } catch (IllegalStateException ignored) { }
                }
                check(okCount == 30, "RS corrects " + t + " errors for nsym=" + nsym + " (" + okCount + "/30)");
                int detected = 0;
                for (int trial = 0; trial < 30; trial++) {
                    int[] c = cw.clone();
                    corrupt(c, t + 1, rnd);
                    try { RS.correct(c, nsym); if (!Arrays.equals(c, cw)) detected--; } catch (IllegalStateException e) { detected++; }
                }
                System.out.println("info  RS nsym=" + nsym + ": " + detected + "/30 of t+1 error patterns detected as uncorrectable");
            }

            // Format / version BCH matching with up to 3 bit flips
            for (int code = 0; code < 32; code++)
                for (int flips = 0; flips <= 3; flips++) {
                    int w = QrDecoder.FORMAT_CODES[code] ^ randomBits(15, flips, rnd);
                    check(QrDecoder.matchFormat(w, w) == code, "format BCH code " + code + " flips " + flips);
                }
            for (int v = 7; v <= 40; v++)
                for (int flips = 0; flips <= 3; flips++) {
                    int w = QrDecoder.VERSION_CODES[v] ^ randomBits(18, flips, rnd);
                    check(QrDecoder.matchVersion(w, w) == v, "version BCH " + v + " flips " + flips);
                }

            // Block structure and function-module counts for every version/level
            for (int ver = 1; ver <= 40; ver++) {
                boolean[] func = QrDecoder.functionMask(ver);
                int free = 0;
                for (boolean b : func) if (!b) free++;
                check(free == QrDecoder.numRawDataModules(ver), "raw data modules v" + ver + ": " + free + " vs " + QrDecoder.numRawDataModules(ver));
                for (int ecl = 0; ecl < 4; ecl++) {
                    int nb = QrDecoder.NUM_ERROR_CORRECTION_BLOCKS[ecl][ver], ecc = QrDecoder.ECC_CODEWORDS_PER_BLOCK[ecl][ver];
                    int raw = QrDecoder.numRawDataModules(ver) / 8, ns = nb - raw % nb, sl = raw / nb;
                    check(ns * sl + (nb - ns) * (sl + 1) == raw && sl > ecc, "block structure v" + ver + " ecl " + ecl);
                }
            }
            check(QrDecoder.numDataCodewords(40, 0) == 2956, "v40-L data codewords = 2956");

            // Full round trip: encode with the reference algorithm, render, decode
            int[] versions = {1, 2, 3, 5, 6, 7, 9, 10, 14, 20, 26, 32, 35, 40};
            double[] pitches = {3.0, 4.0, 4.5, 2.0, 6.0, 3.3};
            int pi = 0, trips = 0;
            long decodeNs = 0;
            for (int ver : versions)
                for (int ecl = 0; ecl < 4; ecl++) {
                    int mask = rnd.nextInt(8);
                    int cap = (QrDecoder.numDataCodewords(ver, ecl) * 8 - 4 - (ver <= 9 ? 8 : 16)) / 8;
                    byte[] data = new byte[cap - rnd.nextInt(Math.min(cap, 40) + 1)];
                    rnd.nextBytes(data);
                    boolean[][] m = encodeQr(data, ver, ecl, mask);
                    double pitch = pitches[pi++ % pitches.length];
                    Gray g = render(m, pitch, 11.3 + rnd.nextDouble() * 5, rnd.nextInt(40), 215 + rnd.nextInt(40));
                    QrDecoder dec = new QrDecoder();
                    long t0 = System.nanoTime();
                    QrDecoder.Result r = dec.process(g);
                    decodeNs += System.nanoTime() - t0;
                    String what = "round trip v" + ver + " ecl " + ecl + " mask " + mask + " pitch " + pitch + " bytes " + data.length;
                    boolean ok = r.kind() == Kind.OK && r.decoded().version() == ver && r.decoded().ecl() == ecl
                        && r.decoded().mask() == mask && Arrays.equals(r.decoded().bytes(), data);
                    check(ok, what + (r.kind() == Kind.OK ? " (decoded but mismatch)" : " (" + r.kind() + ")"));
                    if (ok) {
                        QrDecoder.Result again = dec.process(g);
                        check(!again.changed() && again.kind() == Kind.OK, what + " unchanged-frame detection");
                        trips++;
                    }
                }
            System.out.printf("info  %d round trips, avg decode %.2f ms%n", trips, decodeNs / 1e6 / Math.max(1, versions.length * 4));

            // Damaged symbol: a few modules flipped must still decode (RS at work)
            {
                byte[] data = new byte[1500];
                rnd.nextBytes(data);
                boolean[][] m = encodeQr(data, 40, 0, 2);
                for (int i = 0; i < 40; i++) { int x = 20 + rnd.nextInt(140), y = 20 + rnd.nextInt(140); m[y][x] = !m[y][x]; }
                QrDecoder.Result r = new QrDecoder().process(render(m, 4, 12, 0, 255));
                check(r.kind() == Kind.OK && Arrays.equals(r.decoded().bytes(), data) && r.decoded().corrected() > 0,
                    "v40-L with 40 flipped modules decodes (corrected " + (r.kind() == Kind.OK ? r.decoded().corrected() : -1) + ")");
            }


            // ---- colour raster: encode, render, read back -------------------------------
            int[][] shapes = {{64, 40, 1}, {120, 80, 2}, {200, 150, 2}, {585, 630, 2}, {97, 53, 3}};
            check(Raster.frameCapacity(16, 3) == 0 && Raster.blocks(16, 3) == 0, "a raster too small to hold a block reports no capacity");
            for (int[] shape : shapes) {
                int cols = shape[0], rows = shape[1], cellPx = shape[2];
                int cap = Raster.frameCapacity(cols, rows);
                String what = "raster " + cols + "x" + rows + " at " + cellPx + " px (frame capacity " + cap + " B)";
                if (cap <= Protocol.HEADER + 8) { check(false, what + " is too small to test"); continue; }
                byte[] chunk = new byte[cap - Protocol.HEADER];
                rnd.nextBytes(chunk);
                byte[] frame = Protocol.build(Protocol.DATA, 0x5A5A1234, 3, 9, chunk.length, chunk);
                check(frame.length == cap, what + ": frame exactly fills the capacity");
                byte[] cells = Raster.encode(frame, cols, rows);
                Img img = Raster.render(cells, cols, rows, cellPx);
                Raster dec = new Raster();
                Carrier.Out out = dec.read(img);
                boolean good = out.kind() == Kind.OK && Arrays.equals(out.bytes(), frame);
                check(good, what + " round trip" + (out.kind() == Kind.OK ? " (decoded but different)" : " (" + out.kind() + ")"));
                if (good) {
                    Raster.Geom g = dec.geo;
                    check(g.cols() == cols && g.rows() == rows && g.cellPx() == cellPx,
                        what + ": geometry recovered from the image alone");
                    check(!dec.read(img).changed(), what + ": an identical frame is reported unchanged");
                }
            }

            // A handful of misread cells must be repaired rather than rejected.
            {
                int cols = 200, rows = 150, cellPx = 2;
                byte[] chunk = new byte[Raster.frameCapacity(cols, rows) - Protocol.HEADER];
                rnd.nextBytes(chunk);
                byte[] frame = Protocol.build(Protocol.DATA, 7, 0, 2, chunk.length, chunk);
                byte[] cells = Raster.encode(frame, cols, rows);

                Raster.Layout L = Raster.layout(cols, rows);
                int dpb = L.blockTotal() - L.ecc(), perBlock = 3;
                byte[] hurt = cells.clone();
                for (int b = 0; b < L.blocks(); b++) {              // a few cells inside every block
                    int firstGroup = b * dpb / Raster.GROUP_BYTES;
                    for (int k = 0; k < perBlock; k++) {            // in separate groups: separate bytes
                        int at = cols * Raster.CAL_ROWS + (firstGroup + 2 + k * 5) * Raster.GROUP;
                        if (at < cells.length) hurt[at] = (byte) ((hurt[at] + 1 + rnd.nextInt(4)) % Raster.SYMS);
                    }
                }
                Carrier.Out out = new Raster().read(Raster.render(hurt, cols, rows, cellPx));
                check(out.kind() == Kind.OK && Arrays.equals(out.bytes(), frame),
                    "raster repairs " + perBlock + " damaged cells in each of " + L.blocks() + " blocks");

                byte[] wrecked = cells.clone();
                for (int i = 0; i < Raster.dataCells(cols, rows) / 3; i++) {
                    int at = cols * Raster.CAL_ROWS + rnd.nextInt(Raster.dataCells(cols, rows));
                    wrecked[at] = (byte) ((wrecked[at] + 1 + rnd.nextInt(4)) % Raster.SYMS);
                }
                Carrier.Out bad = new Raster().read(Raster.render(wrecked, cols, rows, cellPx));
                check(bad.kind() != Kind.OK, "raster reports failure rather than wrong data when a third of the cells are wrong");
            }

            // A display that renders the colours badly must still decode. Shade is not what
            // identifies a cell: which channel dominates is.
            {
                int cols = 160, rows = 120, cellPx = 2;
                byte[] chunk = new byte[Raster.frameCapacity(cols, rows) - Protocol.HEADER];
                rnd.nextBytes(chunk);
                byte[] frame = Protocol.build(Protocol.DATA, 3, 1, 4, chunk.length, chunk);
                byte[] cells = Raster.encode(frame, cols, rows);
                Img clean = Raster.render(cells, cols, rows, cellPx);
                String[] names = {"dimmed to 45 per cent", "contrast squeezed into 40..200",
                                  "channels gained 0.9/0.75/0.6", "noise of plus or minus 24",
                                  "dimmed, tinted and noisy at once"};
                for (int k = 0; k < names.length; k++) {
                    Img skewed = skew(clean, k, new Random(99 + k));
                    Carrier.Out out = new Raster().read(skewed);
                    check(out.kind() == Kind.OK && Arrays.equals(out.bytes(), frame),
                        "raster reads a display " + names[k]);
                }
            }

            // Rendered at a lower colour depth, which is what a remote session or a browser short
            // of colours does. All five cell colours are entries in every palette that still counts
            // as colour, so they come back exactly or near enough; the rim only has to stay between
            // black and white rather than land on any particular grey.
            {
                int cols = 160, rows = 120, cellPx = 2;
                byte[] chunk = new byte[Raster.frameCapacity(cols, rows) - Protocol.HEADER];
                rnd.nextBytes(chunk);
                byte[] frame = Protocol.build(Protocol.DATA, 5, 2, 4, chunk.length, chunk);
                Img clean = Raster.render(Raster.encode(frame, cols, rows), cols, rows, cellPx);
                Object[][] depths = {
                    {"16 bit, 5-6-5", depth(clean, 5, 6, 5, false)},
                    {"8 bit, 3-3-2", depth(clean, 3, 3, 2, false)},
                    {"8 bit, 3-3-2, dithered", depth(clean, 3, 3, 2, true)},
                    {"4 bit, 16 EGA colours", ega(clean, false)},
                    {"4 bit, 16 EGA colours, dithered", ega(clean, true)},
                };
                for (Object[] d : depths) {
                    Carrier.Out out = new Raster().read((Img) d[1]);
                    check(out.kind() == Kind.OK && Arrays.equals(out.bytes(), frame),
                        "raster reads a render at " + d[0]);
                }
                // Below four bits there is no colour between black and white for the rim to be, so
                // the grid cannot be found. It must say so rather than invent one.
                check(new Raster().read(depth(clean, 1, 1, 1, true)).kind() != Kind.OK,
                    "raster refuses a 3 bit render rather than misreading it");
            }

            // Anything grey below the code makes a second rectangle that passes for a rim, and it
            // is the larger one. Reading it gets every cell right and still fails, because the row
            // count decides where the parity begins, so every candidate has to be tried.
            {
                int cols = 160, rows = 120, cellPx = 2;
                byte[] chunk = new byte[Raster.frameCapacity(cols, rows) - Protocol.HEADER];
                rnd.nextBytes(chunk);
                byte[] frame = Protocol.build(Protocol.DATA, 9, 3, 5, chunk.length, chunk);
                byte[] cells = Raster.encode(frame, cols, rows);
                for (int i = 300; i < Raster.dataCells(cols, rows); i += 500)   // needs the parity, as a
                    cells[cols + i] = (byte) ((cells[cols + i] + 1) % Raster.SYMS);   // real capture does
                Img clean = Raster.render(cells, cols, rows, cellPx);
                int w = clean.w(), gap = 4 * cellPx, band = Raster.RIM * cellPx;
                int h = clean.h() + gap + band + cellPx;
                int[] px = new int[w * h];
                Arrays.fill(px, 0xFFFFFF);
                System.arraycopy(clean.px(), 0, px, 0, clean.px().length);
                int in = Raster.QUIET * cellPx, out2 = (Raster.QUIET + Raster.RIM) * cellPx;
                for (int y = clean.h(); y < clean.h() + gap; y++)      // sides carry on, as a window edge does
                    for (int x = 0; x < w; x++)
                        if ((x >= in && x < out2) || (x >= w - out2 && x < w - in)) px[y * w + x] = Raster.GREY;
                for (int y = clean.h() + gap; y < clean.h() + gap + band; y++)
                    for (int x = 0; x < w; x++) px[y * w + x] = Raster.GREY;
                Carrier.Out out = new Raster().read(new Img(px, w, h));
                check(out.kind() == Kind.OK && Arrays.equals(out.bytes(), frame),
                    "raster ignores a false rim below the code and finds the real one");
            }

            // Cells that came out between two colours are what exhausts a block's parity, and the
            // decoder is entitled to a second guess at them. Five such cells in one block is one
            // more than its parity can carry, and each is only just on the wrong side.
            {
                int cols = 160, rows = 120, cellPx = 2;
                byte[] chunk = new byte[Raster.frameCapacity(cols, rows) - Protocol.HEADER];
                rnd.nextBytes(chunk);
                byte[] frame = Protocol.build(Protocol.DATA, 11, 4, 6, chunk.length, chunk);
                byte[] cells = Raster.encode(frame, cols, rows);
                Img img = Raster.render(cells, cols, rows, cellPx);
                int at = cols * Raster.CAL_ROWS;
                for (int k = 0; k < 5; k++) {
                    int cell = (3 + k * 6) * Raster.GROUP + 1;        // a high digit, so both bytes move
                    int right = cells[at + cell] & 0xFF, wrong = (right + 1) % Raster.SYMS;
                    smudge(img, cols, cellPx, cell, right, wrong);
                }
                Carrier.Out out = new Raster().read(img);
                check(out.kind() == Kind.OK && Arrays.equals(out.bytes(), frame),
                    "raster reads five borderline cells the other way rather than losing the frame");
            }

            // A browser that answers every key, slowly, must never be called deaf, and must never
            // be sent a second key while the first is still being drawn. This is the whole of what
            // separates a slow client from one that is not listening.
            {
                long fast = 70_000_000L, slow = 2_200_000_000L;
                check(Transfer.patienceFor(0) >= 2_000_000_000L, "an unmeasured client is given at least 2 s");
                check(Transfer.patienceFor(fast) == 400_000_000L, "a fast client keeps the 400 ms floor");
                check(Transfer.patienceFor(slow) > slow * 3, "a slow client is waited for, not talked over");
                check(Transfer.patienceFor(60_000_000_000L) == 15_000_000_000L, "patience is capped");

                long pace = 0;                                    // a client that always takes 2.2 s
                int hurried = 0, accused = 0;
                for (int i = 0; i < 40; i++) {
                    pace = Transfer.paceAfter(pace, slow, true);
                    if (Transfer.patienceFor(pace) <= slow) hurried++;
                    if (Transfer.deafFor(pace, 0, slow)) accused++;
                }
                check(hurried == 0, "a client taking 2.2 s a frame is never talked over, across 40 frames");
                check(accused == 0, "a client taking 2.2 s a frame is never called deaf, across 40 frames");

                // Frames that arrive with keys queued say nothing, and must not drag the measure down.
                long kept = pace;
                for (int i = 0; i < 20; i++) kept = Transfer.paceAfter(kept, 50_000_000L, false);
                check(kept == pace, "a frame that answered a pile of keys does not count as quick");
                long relaxed = pace;
                for (int i = 0; i < 40; i++) relaxed = Transfer.paceAfter(relaxed, 50_000_000L, true);
                check(relaxed < pace / 4, "a client that speeds up is believed, one clean frame at a time");

                // And a browser that answers nothing at all still has to be reported.
                check(Transfer.deafFor(pace, 3, 20_000_000_000L), "silence over several keys is deaf");
                check(!Transfer.deafFor(pace, 1, 20_000_000_000L), "one unanswered key is not deaf");
                check(Transfer.deafFor(0, 3, 5_000_000_000L), "silence from an unmeasured client is deaf");
            }

            // Six borderline cells in one block are twelve bad bytes against nine it can fix, and no
            // single flip gets it under: two of the six must be read the other way together. This is
            // the shape of a real capture that failed after the one-flip search had been added.
            {
                int cols = 160, rows = 120, cellPx = 2;
                byte[] chunk = new byte[Raster.frameCapacity(cols, rows) - Protocol.HEADER];
                rnd.nextBytes(chunk);
                byte[] frame = Protocol.build(Protocol.DATA, 12, 5, 7, chunk.length, chunk);
                byte[] cells = Raster.encode(frame, cols, rows);
                Img img = Raster.render(cells, cols, rows, cellPx);
                int at = cols * Raster.CAL_ROWS;
                for (int k = 0; k < 6; k++) {
                    int cell = (3 + k * 6) * Raster.GROUP;           // the leading digit: both bytes move
                    int right = cells[at + cell] & 0xFF, wrong = (right + 1) % Raster.SYMS;
                    smudge(img, cols, cellPx, cell, right, wrong);
                }
                Carrier.Out out = new Raster().read(img);
                check(out.kind() == Kind.OK && Arrays.equals(out.bytes(), frame),
                    "raster rescues a block with six borderline cells by flipping some of them together");
            }

            // A frame with a great many failing blocks is torn or covered, not noisy. It has to fail
            // cleanly and promptly rather than burn its search budget on every block.
            {
                int cols = 160, rows = 120, cellPx = 2;
                byte[] chunk = new byte[Raster.frameCapacity(cols, rows) - Protocol.HEADER];
                rnd.nextBytes(chunk);
                byte[] frame = Protocol.build(Protocol.DATA, 13, 6, 8, chunk.length, chunk);
                byte[] cells = Raster.encode(frame, cols, rows);
                Raster.Layout L = Raster.layout(cols, rows);
                int dpb = L.blockTotal() - L.ecc();
                Img img = Raster.render(cells, cols, rows, cellPx);
                int at = cols * Raster.CAL_ROWS;
                for (int b = 0; b < L.blocks(); b++)                 // a dozen borderline cells in every block
                    for (int k = 0; k < 12; k++) {
                        int cell = ((b * dpb / Raster.GROUP_BYTES) + 2 + k * 4) * Raster.GROUP;
                        if (at + cell >= cells.length) continue;
                        int right = cells[at + cell] & 0xFF;
                        smudge(img, cols, cellPx, cell, right, (right + 1) % Raster.SYMS);
                    }
                long t0 = System.nanoTime();
                Carrier.Out out = new Raster().read(img);
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                check(out.kind() != Kind.OK, "raster reports failure rather than wrong data when every block is wrecked");
                check(ms < 1500, "a wrecked frame is given up on promptly (" + ms + " ms)");
            }

            // Nothing on screen, and a plain white field, must both read as "no code".
            {
                Img blank = new Img(new int[300 * 200], 300, 200);
                Arrays.fill(blank.px(), 0xFFFFFF);
                check(new Raster().read(blank).kind() == Kind.NONE, "a blank white field reads as no code");
            }

            // Capacity arithmetic must agree with what the layout can actually hold.
            for (int[] shape : shapes) {
                int cols = shape[0], rows = shape[1];
                int cells = Raster.dataCells(cols, rows), bytes = Raster.capacityBytes(cols, rows);
                Raster.Layout L = Raster.layout(cols, rows);
                check(bytes / Raster.GROUP_BYTES * Raster.GROUP <= cells,
                    "raster " + cols + "x" + rows + ": capacity fits the cells");
                check(L.bodyLen() <= bytes && L.blockTotal() <= Raster.RS_BLOCK,
                    "raster " + cols + "x" + rows + ": blocks fit the capacity");
            }

            System.out.println(failures == 0 ? "SELFTEST PASSED" : "SELFTEST FAILED: " + failures + " failure(s)");
            return failures == 0;
        }

        // Ordered dithering, so a colour with no palette entry alternates rather than shifting.
        static final int[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};

        /** Quantises each channel to the given number of bits, as a 16 or 8 bit display would. */
        static Img depth(Img src, int rb, int gb, int bb, boolean dither) {
            int[] px = src.px().clone();
            for (int y = 0; y < src.h(); y++) {
                for (int x = 0; x < src.w(); x++) {
                    int i = y * src.w() + x, v = px[i];
                    int d = dither ? BAYER[(y & 3) * 4 + (x & 3)] : 8;
                    px[i] = (level((v >>> 16) & 255, rb, d) << 16)
                          | (level((v >>> 8) & 255, gb, d) << 8) | level(v & 255, bb, d);
                }
            }
            return new Img(px, src.w(), src.h());
        }

        static int level(int v, int bits, int d) {
            int steps = (1 << bits) - 1;
            int t = clamp(v + (d - 8) * (255 / steps) / 16);
            return (t * steps + 127) / 255 * 255 / steps;
        }

        /** The classic 16 colour palette: no pure primaries in it, and no 0x808080 either. */
        static final int[] EGA = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xAA5500, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

        static Img ega(Img src, boolean dither) {
            int[] px = src.px().clone();
            for (int y = 0; y < src.h(); y++) {
                for (int x = 0; x < src.w(); x++) {
                    int i = y * src.w() + x, v = px[i];
                    int d = dither ? (BAYER[(y & 3) * 4 + (x & 3)] - 8) * 4 : 0;
                    int r = clamp(((v >>> 16) & 255) + d), g = clamp(((v >>> 8) & 255) + d), b = clamp((v & 255) + d);
                    int best = 0, bestD = Integer.MAX_VALUE;
                    for (int c : EGA) {
                        int dr = ((c >>> 16) & 255) - r, dg = ((c >>> 8) & 255) - g, db = (c & 255) - b;
                        int dist = dr * dr + dg * dg + db * db;
                        if (dist < bestD) { bestD = dist; best = c; }
                    }
                    px[i] = best;
                }
            }
            return new Img(px, src.w(), src.h());
        }

        /** Paints one data cell as a blend that lands just on the wrong side of the two colours. */
        static void smudge(Img img, int cols, int cellPx, int dataCell, int right, int wrong) {
            int edge = Raster.RIM + Raster.QUIET;
            int col = dataCell % cols, row = dataCell / cols + Raster.CAL_ROWS;
            int a = Raster.colourOf(right), b = Raster.colourOf(wrong), mix = 0;
            for (int sh = 0; sh <= 16; sh += 8) {                     // 45 per cent of the true colour
                int va = (a >>> sh) & 255, vb = (b >>> sh) & 255;
                mix |= ((va * 45 + vb * 55) / 100) << sh;
            }
            for (int dy = 0; dy < cellPx; dy++)
                for (int dx = 0; dx < cellPx; dx++)
                    img.px()[((edge + row) * cellPx + dy) * img.w() + (edge + col) * cellPx + dx] = mix;
        }

        /** Renders the same image the way a poor display or a lossy path would hand it back. */
        static Img skew(Img src, int kind, Random rnd) {
            int[] px = src.px().clone();
            for (int i = 0; i < px.length; i++) {
                int r = (px[i] >>> 16) & 255, g = (px[i] >>> 8) & 255, b = px[i] & 255;
                switch (kind) {
                    case 0 -> { r = r * 45 / 100; g = g * 45 / 100; b = b * 45 / 100; }
                    case 1 -> { r = 40 + r * 160 / 255; g = 40 + g * 160 / 255; b = 40 + b * 160 / 255; }
                    case 2 -> { r = r * 90 / 100; g = g * 75 / 100; b = b * 60 / 100; }
                    case 3 -> { r += rnd.nextInt(49) - 24; g += rnd.nextInt(49) - 24; b += rnd.nextInt(49) - 24; }
                    default -> {
                        r = r * 60 / 100 + rnd.nextInt(21) - 10;
                        g = g * 50 / 100 + rnd.nextInt(21) - 10;
                        b = b * 40 / 100 + rnd.nextInt(21) - 10;
                    }
                }
                px[i] = (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
            }
            return new Img(px, src.w(), src.h());
        }

        static int clamp(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }

        static void corrupt(int[] c, int count, Random rnd) {
            boolean[] used = new boolean[c.length];
            for (int i = 0; i < count; i++) {
                int p;
                do { p = rnd.nextInt(c.length); } while (used[p]);
                used[p] = true;
                c[p] ^= 1 + rnd.nextInt(255);
            }
        }

        static int randomBits(int width, int flips, Random rnd) {
            int v = 0;
            while (Integer.bitCount(v) < flips) v |= 1 << rnd.nextInt(width);
            return v;
        }

        static final class BitOut {
            int[] bits = new int[1 << 16];
            int len;
            void append(int val, int n) { for (int i = n - 1; i >= 0; i--) bits[len++] = (val >>> i) & 1; }
            int[] toBytes() {
                int[] out = new int[len / 8];
                for (int i = 0; i < len; i++) out[i >>> 3] |= bits[i] << (7 - (i & 7));
                return out;
            }
        }

        static void drawFinder(boolean[][] m, int cx, int cy) {
            int size = m.length;
            for (int dy = -4; dy <= 4; dy++) for (int dx = -4; dx <= 4; dx++) {
                int x = cx + dx, y = cy + dy;
                if (x < 0 || x >= size || y < 0 || y >= size) continue;
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                m[y][x] = dist != 2 && dist != 4;
            }
        }

        /** Reference QR encoder (byte mode) mirroring qrcodegen, for exercising the decoder offline. */
        static boolean[][] encodeQr(byte[] data, int ver, int ecl, int mask) {
            int size = ver * 4 + 17;
            boolean[][] m = new boolean[size][size];
            boolean[] func = QrDecoder.functionMask(ver);
            for (int i = 0; i < size; i++) { m[6][i] = i % 2 == 0; m[i][6] = i % 2 == 0; }
            drawFinder(m, 3, 3); drawFinder(m, size - 4, 3); drawFinder(m, 3, size - 4);
            int[] ap = QrDecoder.alignmentPositions(ver);
            int na = ap.length;
            for (int i = 0; i < na; i++) for (int j = 0; j < na; j++) {
                if ((i == 0 && j == 0) || (i == 0 && j == na - 1) || (i == na - 1 && j == 0)) continue;
                for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++)
                    m[ap[j] + dy][ap[i] + dx] = Math.max(Math.abs(dx), Math.abs(dy)) != 1;
            }
            int fmt = QrDecoder.FORMAT_CODES[(QrDecoder.ORD_TO_FORMATBITS[ecl] << 3) | mask];
            for (int copy = 0; copy < 2; copy++) for (int i = 0; i < 15; i++) {
                int[] p = QrDecoder.formatPos(size, copy, i);
                m[p[1]][p[0]] = ((fmt >>> i) & 1) != 0;
            }
            m[size - 8][8] = true;
            if (ver >= 7) {
                int vb = QrDecoder.VERSION_CODES[ver];
                for (int copy = 0; copy < 2; copy++) for (int i = 0; i < 18; i++) {
                    int[] p = QrDecoder.versionPos(size, copy, i);
                    m[p[1]][p[0]] = ((vb >>> i) & 1) != 0;
                }
            }
            int numData = QrDecoder.numDataCodewords(ver, ecl), cap = numData * 8;
            BitOut bb = new BitOut();
            bb.append(4, 4);
            bb.append(data.length, ver <= 9 ? 8 : 16);
            for (byte b : data) bb.append(b & 0xFF, 8);
            bb.append(0, Math.min(4, cap - bb.len));
            while (bb.len % 8 != 0) bb.append(0, 1);
            for (int pad = 0xEC; bb.len < cap; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);
            int[] dcw = bb.toBytes();
            int numBlocks = QrDecoder.NUM_ERROR_CORRECTION_BLOCKS[ecl][ver], eccLen = QrDecoder.ECC_CODEWORDS_PER_BLOCK[ecl][ver];
            int rawCw = QrDecoder.numRawDataModules(ver) / 8, numShort = numBlocks - rawCw % numBlocks, shortLen = rawCw / numBlocks;
            int shortData = shortLen - eccLen;
            int[][] blocks = new int[numBlocks][];
            int k = 0;
            for (int b = 0; b < numBlocks; b++) {
                int dl = shortData + (b < numShort ? 0 : 1);
                blocks[b] = RS.encode(Arrays.copyOfRange(dcw, k, k + dl), eccLen);
                k += dl;
            }
            int[] all = new int[rawCw];
            int idx = 0;
            for (int i = 0; i <= shortLen; i++) for (int b = 0; b < numBlocks; b++) {
                if (i == shortData && b < numShort) continue;
                int j = (b < numShort && i > shortData) ? i - 1 : i;
                all[idx++] = blocks[b][j];
            }
            if (idx != rawCw) throw new IllegalStateException("interleave count " + idx + " != " + rawCw);
            int bitIdx = 0, totalBits = rawCw * 8;
            for (int right = size - 1; right >= 1; right -= 2) {
                if (right == 6) right = 5;
                boolean upward = ((right + 1) & 2) == 0;
                for (int vert = 0; vert < size; vert++) {
                    int y = upward ? size - 1 - vert : vert;
                    for (int j = 0; j < 2; j++) {
                        int x = right - j;
                        if (func[y * size + x]) continue;
                        boolean bit = bitIdx < totalBits && ((all[bitIdx >>> 3] >>> (7 - (bitIdx & 7))) & 1) != 0;
                        bitIdx++;
                        m[y][x] = bit ^ QrDecoder.maskBit(mask, x, y);
                    }
                }
            }
            return m;
        }

        static Gray render(boolean[][] m, double pitch, double off, int dark, int light) {
            int size = m.length;
            int n = (int) Math.ceil(off * 2 + (size + 8) * pitch);
            byte[] px = new byte[n * n];
            Arrays.fill(px, (byte) light);
            for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
                int c = (int) Math.floor((x - off) / pitch) - 4, r = (int) Math.floor((y - off) / pitch) - 4;
                if (c >= 0 && c < size && r >= 0 && r < size && m[r][c]) px[y * n + x] = (byte) dark;
            }
            return new Gray(px, n, n);
        }
    }
}
