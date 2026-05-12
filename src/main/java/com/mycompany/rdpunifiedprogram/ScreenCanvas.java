package com.mycompany.rdpunifiedprogram;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ScreenCanvas extends JPanel {

    private volatile Map<String, BufferedImage> frames = new ConcurrentHashMap<>();
    private volatile String                     selectedIp = null;
    private volatile boolean                    gridMode   = false;
    private boolean                             isLightMode = false;

    private final Map<String, Float>        frameAlpha  = new LinkedHashMap<>();
    private final Map<String, BufferedImage> prevFrames = new ConcurrentHashMap<>();

    private float  waitAngle    = 0f;
    private float  waitPulse    = 0f;
    private int    waitPulseDir = 1;
    private float  waitBreath   = 0f;
    private float  waitBreathDir= 0.018f;
    private int    dotCount     = 0;
    private int    dotTick      = 0;

    private float  liveRing  = 0f;
    private float  liveAlpha = 1f;

    private float  shimmerY    = 0f;
    private String hoveredIp   = null;

    private Timer masterTimer;

    // ── Cached gradient objects — rebuilt only on size change ──────────────
    private int           cachedW = -1, cachedH = -1;
    private GradientPaint cachedVignette;
    private GradientPaint cachedVignetteLight;
    private float         lastShimmerY  = -9999f;
    private GradientPaint cachedShimmer;

    // ── Pre-allocated AlphaComposite — avoid getInstance() in hot path ─────
    // Cached per unique alpha value during cross-fade
    private float             cachedCompositeAlpha = -1f;
    private AlphaComposite    cachedComposite;

    // ── Static stroke constants ────────────────────────────────────────────
    private static final BasicStroke STROKE_RING_NORMAL  = new BasicStroke(0.8f);
    private static final BasicStroke STROKE_LIVE_RING    = new BasicStroke(1.2f);
    private static final BasicStroke STROKE_GRID_NORMAL  = new BasicStroke(0.8f);
    private static final BasicStroke STROKE_GRID_HOVER   = new BasicStroke(1.5f);

    // ── Font / Color constants ─────────────────────────────────────────────
    private static final Font MONO_SM  = new Font("Consolas", Font.BOLD, 11);
    private static final Font SANS_MED = new Font("Segoe UI", Font.PLAIN, 15);
    private static final Font SANS_SM  = new Font("Segoe UI", Font.PLAIN, 12);
    private static final Font FONT_IDX = new Font("Segoe UI", Font.BOLD, 10);
    private static final Font SANS_BOLD_12 = new Font("Segoe UI", Font.BOLD, 12);
    private static final Font SANS_PLAIN_12= new Font("Segoe UI", Font.PLAIN, 12);

    private static final Color COL_LIVE_GREEN    = new Color(34, 197, 94, 220);
    private static final Color COL_LIVE_LABEL    = new Color(200, 240, 200);
    private static final Color COL_ACCENT_BLUE   = new Color(59, 130, 246);
    private static final Color COL_WHITE_200     = new Color(255, 255, 255, 200);
    private static final Color COL_HOVER_FILL    = new Color(59, 130, 246, 28);
    private static final Color COL_HOVER_BORDER  = new Color(59, 130, 246, 160);
    private static final Color COL_WAIT_LIGHT    = new Color(226, 232, 240);
    private static final Color COL_WAIT_DARK     = new Color(20, 28, 46);
    private static final Color COL_WAIT_RING_LIGHT = new Color(203, 213, 225);
    private static final Color COL_WAIT_RING_DARK  = new Color(40, 60, 100);
    private static final Color COL_WAIT_TEXT_LIGHT = new Color(71, 85, 105);
    private static final Color COL_WAIT_TEXT_DARK  = new Color(120, 140, 170);
    private static final Color COL_WAIT_SUB_LIGHT  = new Color(100, 116, 139);
    private static final Color COL_WAIT_SUB_DARK   = new Color(50, 70, 100);
    private static final Color COL_WAIT_DOT_FILL   = new Color(59, 130, 246, 40);
    private static final Color COL_ARC_BLUE   = new Color(59, 130, 246, 210);
    private static final Color COL_ARC_PURPLE = new Color(139, 92, 246, 170);
    private static final Color COL_GRID_LABEL_LIGHT_BG = new Color(255, 255, 255, 210);
    private static final Color COL_GRID_LABEL_DARK_BG  = new Color(8, 12, 22, 210);
    private static final Color COL_GRID_LABEL_HOVER = new Color(34, 197, 94);
    private static final Color COL_GRID_LABEL_LIGHT = new Color(21, 128, 61);
    private static final Color COL_GRID_LABEL_DARK  = new Color(100, 180, 120);
    private static final Color COL_GRID_IDX = new Color(100, 116, 139);
    private static final Color COL_GRID_BORDER_LIGHT = new Color(203, 213, 225);
    private static final Color COL_GRID_BORDER_DARK  = new Color(30, 40, 60, 200);
    private static final Color COL_IDX_BG_LIGHT = new Color(241, 245, 249, 180);
    private static final Color COL_IDX_BG_DARK  = new Color(30, 40, 60, 180);

    
    private static final String[] IDX_STRINGS;
    static {
        IDX_STRINGS = new String[100];
        for (int i = 0; i < 100; i++) IDX_STRINGS[i] = String.valueOf(i + 1);
    }

    public ScreenCanvas() {
        setBackground(new Color(8, 11, 18));
        setDoubleBuffered(true);

        addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseMoved(java.awt.event.MouseEvent e) {
                String prev = hoveredIp;
                hoveredIp = gridMode ? getIpAtPoint(e.getX(), e.getY()) : null;
                if (prev == null ? hoveredIp != null : !prev.equals(hoveredIp)) repaint();
            }
        });
        addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseExited(java.awt.event.MouseEvent e) {
                if (hoveredIp != null) { hoveredIp = null; repaint(); }
            }
        });

        masterTimer = new Timer(16, e -> tick());
        masterTimer.setInitialDelay(0);
        masterTimer.start();
    }

    public void setLightMode(boolean light) {
        this.isLightMode = light;
        setBackground(light ? new Color(240, 244, 248) : new Color(8, 11, 18));
        cachedW = -1; // invalidate gradient cache
        repaint();
    }

    private void tick() {
        boolean hasFrames = !frames.isEmpty();

        waitAngle   = (waitAngle + 2.2f) % 360f;
        waitPulse  += waitPulseDir * 0.012f;
        if (waitPulse > 1f || waitPulse < 0f) waitPulseDir = -waitPulseDir;
        waitBreath += waitBreathDir;
        if (waitBreath > 1f || waitBreath < 0f) waitBreathDir = -waitBreathDir;
        dotTick++;
        if (dotTick > 28) { dotTick = 0; dotCount = (dotCount + 1) % 4; }

        if (hasFrames && !gridMode) {
            liveRing += 0.6f;
            liveAlpha = Math.max(0f, 1f - liveRing / 22f);
            if (liveRing > 22f) liveRing = 0f;
        }

        boolean shimmerChanged = false;
        if (hasFrames) {
            float oldShimmer = shimmerY;
            shimmerY += 1.8f;
            if (shimmerY > getHeight() + 60) shimmerY = -60;
            shimmerChanged = (shimmerY != oldShimmer);
        }

        boolean anyFading = false;
        for (String ip : frameAlpha.keySet().toArray(new String[0])) {
            float a = frameAlpha.getOrDefault(ip, 1f);
            if (a < 1f) {
                a = Math.min(1f, a + 0.15f);
                frameAlpha.put(ip, a);
                anyFading = true;
            }
        }

        
        if (!hasFrames || anyFading || !shimmerChanged || gridMode) {
            repaint();
        } else {
            
            repaint();
        }
    }

    
    public void updateState(Map<String, BufferedImage> newFrames, String selIp, boolean grid) {
        
        for (String ip : newFrames.keySet()) {
            if (!frames.containsKey(ip)) frameAlpha.put(ip, 0f);
        }
        
        for (String ip : newFrames.keySet()) {
            BufferedImage incoming = newFrames.get(ip);
            BufferedImage existing = frames.get(ip);
            if (existing != null && existing != incoming) prevFrames.put(ip, existing);
        }
        
        this.frames     = new ConcurrentHashMap<>(newFrames);
        this.selectedIp = selIp;
        this.gridMode   = grid;
    }

    public void clearFrames() {
        frames.clear();
        prevFrames.clear();
        frameAlpha.clear();
        selectedIp = null;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;

        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,     RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING,         RenderingHints.VALUE_RENDER_SPEED);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        if (frames.isEmpty()) { paintWaiting(g2); return; }
        if (gridMode)         { paintGrid(g2);    return; }
        paintSingle(g2);
    }

    private void ensureGradientCache(int w, int h) {
        if (w == cachedW && h == cachedH) return;
        cachedW = w; cachedH = h;
        cachedVignette      = new GradientPaint(0, 0, new Color(0, 0, 0, 160), 0, 38, new Color(0, 0, 0, 0));
        cachedVignetteLight = new GradientPaint(0, 0, new Color(0, 0, 0,  60), 0, 38, new Color(0, 0, 0, 0));
        lastShimmerY = -9999f;
    }

    private GradientPaint getShimmerPaint(int w) {
        if (Math.abs(shimmerY - lastShimmerY) >= 1f) {
            cachedShimmer = new GradientPaint(
                0, shimmerY - 30, new Color(255, 255, 255, 0),
                0, shimmerY,      new Color(255, 255, 255, 10), true);
            lastShimmerY = shimmerY;
        }
        return cachedShimmer;
    }

    
    private AlphaComposite getComposite(float alpha) {
        if (alpha != cachedCompositeAlpha) {
            cachedComposite      = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha);
            cachedCompositeAlpha = alpha;
        }
        return cachedComposite;
    }

    private void paintWaiting(Graphics2D g2) {
        int cx = getWidth() / 2, cy = getHeight() / 2 - 20;

        int glowR = (int)(70 + waitBreath * 16);
        
        RadialGradientPaint glow = new RadialGradientPaint(cx, cy, glowR,
            new float[]{0f, 1f},
            new Color[]{new Color(59, 130, 246, (int)(waitBreath * 28)), new Color(0, 0, 0, 0)});
        g2.setPaint(glow);
        g2.fillOval(cx - glowR, cy - glowR, glowR * 2, glowR * 2);

        g2.setStroke(STROKE_RING_NORMAL);
        for (int i = 3; i >= 1; i--) {
            int r = (int)(28 + i * 14 + waitPulse * 10);
            int a = (int)(25 - i * 6 + waitPulse * 15);
            g2.setColor(new Color(59, 130, 246, Math.max(0, a)));
            g2.drawOval(cx - r, cy - r, r * 2, r * 2);
        }

        int R = 26;
        g2.setColor(isLightMode ? COL_WAIT_LIGHT : COL_WAIT_DARK);
        g2.fillOval(cx - R, cy - R, R * 2, R * 2);
        g2.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(isLightMode ? COL_WAIT_RING_LIGHT : COL_WAIT_RING_DARK);
        g2.drawOval(cx - R, cy - R, R * 2, R * 2);

        int r2 = R - 3;
        g2.setColor(COL_ARC_BLUE);
        g2.drawArc(cx - r2, cy - r2, r2 * 2, r2 * 2, (int)waitAngle, 255);
        g2.setColor(COL_ARC_PURPLE);
        g2.drawArc(cx - r2, cy - r2, r2 * 2, r2 * 2, (int)waitAngle + 180, 90);

        g2.setColor(COL_WAIT_DOT_FILL); g2.fillOval(cx - 8, cy - 8, 16, 16);
        g2.setColor(COL_ACCENT_BLUE);   g2.fillOval(cx - 5, cy - 5, 10, 10);
        g2.setColor(COL_WHITE_200);     g2.fillOval(cx - 2, cy - 2, 4, 4);

        g2.setFont(SANS_MED);
        FontMetrics fm = g2.getFontMetrics();
        String main = "Waiting for stream";
        String dots = ".".repeat(dotCount);
        g2.setColor(isLightMode ? COL_WAIT_TEXT_LIGHT : COL_WAIT_TEXT_DARK);
        g2.drawString(main, cx - fm.stringWidth(main) / 2, cy + R + 22);
        g2.setColor(COL_ACCENT_BLUE);
        g2.drawString(dots, cx + fm.stringWidth(main) / 2 + 2, cy + R + 22);

        g2.setFont(SANS_SM);
        fm = g2.getFontMetrics();
        String sub = "Connect and select a role to begin";
        g2.setColor(isLightMode ? COL_WAIT_SUB_LIGHT : COL_WAIT_SUB_DARK);
        g2.drawString(sub, cx - fm.stringWidth(sub) / 2, cy + R + 44);
    }

    private void paintSingle(Graphics2D g2) {
        BufferedImage img = frames.get(selectedIp);
        if (img == null && !frames.isEmpty()) {
            selectedIp = frames.keySet().iterator().next();
            img = frames.get(selectedIp);
        }
        if (img == null) return;

        int w = getWidth(), h = getHeight();
        ensureGradientCache(w, h);

        float alpha = frameAlpha.getOrDefault(selectedIp, 1f);

        if (alpha < 1f) {
            BufferedImage prev = prevFrames.get(selectedIp);
            if (prev != null) g2.drawImage(prev, 0, 0, w, h, null);
            Composite old = g2.getComposite();
            g2.setComposite(getComposite(alpha)); // cached composite
            g2.drawImage(img, 0, 0, w, h, null);
            g2.setComposite(old);
        } else {
            g2.drawImage(img, 0, 0, w, h, null);
            prevFrames.remove(selectedIp);
        }

        
        if (alpha > 0.6f) {
            g2.setPaint(getShimmerPaint(w));
            g2.fillRect(0, (int)(shimmerY - 30), w, 60);
        }

        
        g2.setPaint(isLightMode ? cachedVignetteLight : cachedVignette);
        g2.fillRect(0, 0, w, 38);

        
        int bx = 12, by = 9;
        if (liveRing > 0) {
            g2.setColor(new Color(34, 197, 94, (int)(liveAlpha * 80)));
            g2.setStroke(STROKE_LIVE_RING);
            int lr = (int)liveRing;
            g2.drawOval(bx + 1 - lr, by + 2 - lr, lr * 2 + 4, lr * 2 + 4);
        }
        g2.setColor(COL_LIVE_GREEN); g2.fillOval(bx, by + 2, 8, 8);
        g2.setColor(COL_WHITE_200);  g2.fillOval(bx + 2, by + 4, 4, 4);

        g2.setFont(MONO_SM);
        g2.setColor(COL_LIVE_LABEL);
        g2.drawString("LIVE  " + selectedIp, bx + 13, by + 10);

        
        String res = img.getWidth() + "×" + img.getHeight();
        g2.setFont(SANS_SM);
        g2.setColor(isLightMode ? COL_WAIT_TEXT_LIGHT : new Color(60, 80, 110));
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(res, w - fm.stringWidth(res) - 10, h - 8);
    }

    private void paintGrid(Graphics2D g2) {
        
        Map<String, BufferedImage> snapshot = frames;
        int n    = snapshot.size();
        int cols = Math.max(1, (int)Math.ceil(Math.sqrt(n)));
        int rows = (int)Math.ceil((double)n / cols);
        int cW   = getWidth() / cols;
        int cH   = getHeight() / rows;
        int i    = 0;

        for (Map.Entry<String, BufferedImage> entry : snapshot.entrySet()) {
            String ip = entry.getKey();
            BufferedImage img = entry.getValue();
            int row = i / cols, col = i % cols;
            int x = col * cW, y = row * cH;

            float alpha = frameAlpha.getOrDefault(ip, 1f);

            if (alpha < 1f) {
                BufferedImage prev = prevFrames.get(ip);
                if (prev != null) g2.drawImage(prev, x, y, cW, cH, null);
                Composite old = g2.getComposite();
                g2.setComposite(getComposite(alpha));
                g2.drawImage(img, x, y, cW, cH, null);
                g2.setComposite(old);
            } else {
                g2.drawImage(img, x, y, cW, cH, null);
                prevFrames.remove(ip);
            }

            boolean hovered = ip.equals(hoveredIp);

            if (hovered) {
                g2.setColor(COL_HOVER_FILL);
                g2.fillRect(x, y, cW, cH);
            }

            
            int vigAlpha = hovered ? (isLightMode ? 80 : 180) : (isLightMode ? 40 : 140);
            g2.setPaint(new GradientPaint(x, y, new Color(0, 0, 0, vigAlpha),
                                          x, y + 32, new Color(0, 0, 0, 0)));
            g2.fillRect(x, y, cW, 32);

            g2.setColor(hovered ? COL_HOVER_BORDER
                : (isLightMode ? COL_GRID_BORDER_LIGHT : COL_GRID_BORDER_DARK));
            g2.setStroke(hovered ? STROKE_GRID_HOVER : STROKE_GRID_NORMAL);
            g2.drawRect(x, y, cW - 1, cH - 1);

            
            g2.setFont(MONO_SM);
            FontMetrics fm = g2.getFontMetrics();
            String label = (hovered ? "▶ " : "") + ip;
            int lw = fm.stringWidth(label) + 16;

            g2.setColor(isLightMode ? COL_GRID_LABEL_LIGHT_BG : COL_GRID_LABEL_DARK_BG);
            g2.fillRoundRect(x + 8, y + 7, lw, 18, 6, 6);

            g2.setColor(hovered ? COL_GRID_LABEL_HOVER
                : (isLightMode ? COL_GRID_LABEL_LIGHT : COL_GRID_LABEL_DARK));
            g2.drawString(label, x + 16, y + 20);

            
            g2.setColor(isLightMode ? COL_IDX_BG_LIGHT : COL_IDX_BG_DARK);
            g2.fillRoundRect(x + cW - 28, y + 7, 20, 16, 4, 4);
            g2.setFont(FONT_IDX);
            g2.setColor(COL_GRID_IDX);
            
            String idxStr = (i < IDX_STRINGS.length) ? IDX_STRINGS[i] : String.valueOf(i + 1);
            g2.drawString(idxStr, x + cW - 22, y + 19);

            i++;
        }
    }

    private String getIpAtPoint(int mx, int my) {
        Map<String, BufferedImage> snapshot = frames;
        if (snapshot.isEmpty()) return null;
        int n = snapshot.size(), cols = Math.max(1, (int)Math.ceil(Math.sqrt(n)));
        int cW = getWidth() / cols, cH = getHeight() / (int)Math.ceil((double)n / cols);
        if (cW <= 0 || cH <= 0) return null;
        int col = mx / cW, row = my / cH, idx = row * cols + col;
        
        int k = 0;
        for (String key : snapshot.keySet()) {
            if (k == idx) return key;
            k++;
        }
        return null;
    }
}