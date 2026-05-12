package com.mycompany.rdpunifiedprogram;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.Random;
import static javax.swing.WindowConstants.EXIT_ON_CLOSE;


public class AppLauncher extends JFrame {

    // ── Design Tokens ──────────────────────────────────────────────────────
    static final Color BG_DARK       = new Color(8, 12, 20);
    static final Color BG_MID        = new Color(13, 18, 30);
    static final Color SURFACE       = new Color(20, 27, 44);
    static final Color SURFACE_ALT   = new Color(28, 37, 58);
    static final Color ACCENT_BLUE   = new Color(59, 130, 246);
    static final Color ACCENT_PURPLE = new Color(139, 92, 246);
    static final Color ACCENT_GREEN  = new Color(34, 197, 94);
    static final Color ACCENT_RED    = new Color(239, 68, 68);
    static final Color TEXT_PRIMARY  = new Color(241, 245, 249);
    static final Color TEXT_MUTED    = new Color(100, 116, 139);
    static final Color BORDER_COLOR  = new Color(30, 41, 59);
    static final Color BORDER_FOCUS  = new Color(59, 130, 246, 120);
    static final Color BORDER_GLOW   = new Color(59, 130, 246, 60);
    static final Color SURFACE_MID   = new Color(22, 30, 46);

    // ── Pre-allocated paint constants — no allocation in hot path ──────────
    private static final Color   BG_BORDER_GLOW = new Color(59, 130, 246, 50);
    private static final Color   PARTICLE_COLOR  = new Color(59, 130, 246, 0); 
    private static final BasicStroke STROKE_BORDER = new BasicStroke(1.8f);

    // ── Animation state ────────────────────────────────────────────────────
    private float fadeAlpha    = 0f;
    private float accentOffset = 0f;
    private int   shimmerDir   = 1;
    private float lastOpacity  = -1f; 

    private static final int PC = 40;
    private final float[] px  = new float[PC], py  = new float[PC];
    private final float[] pvx = new float[PC], pvy = new float[PC];
    private final float[] pr  = new float[PC], pa  = new float[PC];
    
    private final Color[] particleColors = new Color[PC];
    private final Random  rng = new Random(7);

    private Timer masterTimer;
    
    private AnimatedLogo logo;

    public AppLauncher() {
        setTitle("RDP Program");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(490, 330);
        setLocationRelativeTo(null);
        setResizable(false);
        setUndecorated(true);
        setOpacity(0f);
        try { setShape(new RoundRectangle2D.Double(0, 0, 490, 330, 24, 24)); }
        catch (Exception ignored) {}

        initParticles();

        // ── Background canvas ──────────────────────────────────────────────
        JPanel root = new JPanel(new BorderLayout()) {
            
            private final Color[] bgColors = {
                new Color(22, 33, 58), BG_MID, BG_DARK
            };
            private final float[] bgFractions = {0f, 0.55f, 1f};
            private final Color SHIMMER_BASE_START = new Color(255, 255, 255, 0);
            private final Color SHIMMER_BASE_END   = new Color(255, 255, 255, 100);
            private final Color ACCENT_BAR_CLIP_COLOR = new Color(255, 255, 255, 0);

            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();

                
                RadialGradientPaint bgPaint = new RadialGradientPaint(
                    w * 0.35f, h * 0.28f, w * 0.9f, bgFractions, bgColors);
                g2.setPaint(bgPaint);
                g2.fillRoundRect(0, 0, w, h, 24, 24);

                
                for (int i = 0; i < PC; i++) {
                    g2.setColor(particleColors[i]);
                    g2.fillOval((int)(px[i] - pr[i]), (int)(py[i] - pr[i]),
                                (int)(pr[i] * 2),     (int)(pr[i] * 2));
                }

                
                g2.setColor(BG_BORDER_GLOW);
                g2.setStroke(STROKE_BORDER);
                g2.drawRoundRect(0, 0, w - 1, h - 1, 24, 24);

                
                Shape clip = new RoundRectangle2D.Float(0, 0, w, 4, 4, 4);
                g2.setClip(clip);
                g2.setPaint(new GradientPaint(0, 0, ACCENT_PURPLE, w, 0, ACCENT_BLUE));
                g2.fillRect(0, 0, w, 4);
                float sx = accentOffset * w - 80;
                g2.setPaint(new GradientPaint(sx, 0, SHIMMER_BASE_START,
                                              sx + 80, 0, SHIMMER_BASE_END, true));
                g2.fillRect(0, 0, w, 4);
                g2.setClip(null);
                g2.dispose();
            }
        };
        root.setOpaque(false);
        root.setBorder(BorderFactory.createEmptyBorder(28, 38, 32, 38));

        // Drag support
        final int[] drag = {0, 0};
        root.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) { drag[0] = e.getX(); drag[1] = e.getY(); }
        });
        root.addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseDragged(MouseEvent e) {
                Point p = getLocation();
                setLocation(p.x + e.getX() - drag[0], p.y + e.getY() - drag[1]);
            }
        });

        // ── Header ────────────────────────────────────────────────────────
        logo = new AnimatedLogo();

        JLabel lblTitle = new JLabel("RDP Program");
        lblTitle.setFont(new Font("Segoe UI", Font.BOLD, 26));
        lblTitle.setForeground(TEXT_PRIMARY);

        JLabel lblSub = new JLabel("Remote Desktop Protocol");
        lblSub.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        lblSub.setForeground(TEXT_MUTED);

        JPanel pnlText = new JPanel();
        pnlText.setOpaque(false);
        pnlText.setLayout(new BoxLayout(pnlText, BoxLayout.Y_AXIS));
        pnlText.add(lblTitle);
        pnlText.add(Box.createVerticalStrut(3));
        pnlText.add(lblSub);

        JButton btnClose = buildCloseButton();

        JPanel pnlTop = new JPanel(new BorderLayout(14, 0));
        pnlTop.setOpaque(false);
        pnlTop.add(logo, BorderLayout.WEST);
        pnlTop.add(pnlText, BorderLayout.CENTER);
        pnlTop.add(btnClose, BorderLayout.EAST);

        // ── Gradient divider ───────────────────────────────────────────────
        JPanel divider = new JPanel() {
            private final Color DIV_START = new Color(59, 130, 246, 0);
            private final Color DIV_END   = new Color(59, 130, 246, 70);
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                int w = getWidth();
                g2.setPaint(new GradientPaint(0, 0, DIV_START, w / 2, 0, DIV_END, true));
                g2.fillRect(0, 0, w, 1);
                g2.dispose();
            }
        };
        divider.setOpaque(false);
        divider.setPreferredSize(new Dimension(1, 1));

        // ── Subtitle ──────────────────────────────────────────────────────
        JLabel lblInstruction = new JLabel("Select your role to continue");
        lblInstruction.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        lblInstruction.setForeground(TEXT_MUTED);
        lblInstruction.setAlignmentX(Component.LEFT_ALIGNMENT);

        // ── Spring cards ──────────────────────────────────────────────────
        SpringCard btnClient = new SpringCard("Client", "Connect to a remote server", "→",  ACCENT_BLUE);
        SpringCard btnServer = new SpringCard("Server", "Host and manage connections", "⊞", ACCENT_PURPLE);
        btnClient.addActionListener(e -> launchWithFade(() -> new ClientFrame().setVisible(true)));
        btnServer.addActionListener(e -> launchWithFade(() -> new ServerFrame().setVisible(true)));

        JPanel pnlCards = new JPanel(new GridLayout(1, 2, 14, 0));
        pnlCards.setOpaque(false);
        pnlCards.setAlignmentX(Component.LEFT_ALIGNMENT);
        pnlCards.add(btnClient);
        pnlCards.add(btnServer);

        JPanel pnlCenter = new JPanel();
        pnlCenter.setOpaque(false);
        pnlCenter.setLayout(new BoxLayout(pnlCenter, BoxLayout.Y_AXIS));
        pnlCenter.add(Box.createVerticalStrut(18));
        pnlCenter.add(lblInstruction);
        pnlCenter.add(Box.createVerticalStrut(12));
        pnlCenter.add(pnlCards);

        root.add(pnlTop,    BorderLayout.NORTH);
        root.add(divider,   BorderLayout.CENTER);
        root.add(pnlCenter, BorderLayout.SOUTH);
        setContentPane(root);

        startAnimations();
    }

    private void initParticles() {
        for (int i = 0; i < PC; i++) {
            px[i]  = rng.nextFloat() * 490;
            py[i]  = rng.nextFloat() * 330;
            pvx[i] = (rng.nextFloat() - 0.5f) * 0.35f;
            pvy[i] = (rng.nextFloat() - 0.5f) * 0.35f;
            pr[i]  = 1f + rng.nextFloat() * 2f;
            pa[i]  = 0.04f + rng.nextFloat() * 0.10f;
            
            particleColors[i] = new Color(59, 130, 246, Math.max(0, Math.min(255, (int)(pa[i] * 255))));
        }
    }


    private void startAnimations() {
        
        masterTimer = new Timer(16, e -> {
            // Fade in
            if (fadeAlpha < 1f) {
                fadeAlpha = Math.min(1f, fadeAlpha + 0.055f);
                float opacity = easeOut(fadeAlpha);
                if (Math.abs(opacity - lastOpacity) > 0.002f) {
                    lastOpacity = opacity;
                    setOpacity(opacity);
                }
            }

            // Shimmer
            accentOffset += 0.007f * shimmerDir;
            if (accentOffset > 1.3f) shimmerDir = -1;
            if (accentOffset < -0.3f) shimmerDir = 1;

            // Particles
            for (int i = 0; i < PC; i++) {
                px[i] += pvx[i]; py[i] += pvy[i];
                if (px[i] < 0) px[i] = 490; if (px[i] > 490) px[i] = 0;
                if (py[i] < 0) py[i] = 330; if (py[i] > 330) py[i] = 0;
            }

            
            logo.advanceAngle();

            repaint();
        });
        masterTimer.setInitialDelay(0);
        masterTimer.start();
    }

    private float easeOut(float t) { return 1f - (1f - t) * (1f - t) * (1f - t); }

    private void launchWithFade(Runnable next) {
        Timer out = new Timer(14, null);
        out.addActionListener(e -> {
            fadeAlpha -= 0.12f;
            float opacity = Math.max(0f, easeOut(Math.max(0f, fadeAlpha)));
            setOpacity(opacity);
            if (fadeAlpha <= 0f) {
                out.stop();
                masterTimer.stop();
                logo.stopTimer(); 
                next.run();
                dispose();
            }
        });
        out.setInitialDelay(0);
        out.start();
    }

    // ── Animated spinning logo ─────────────────────────────────────────────
    static class AnimatedLogo extends JPanel {
        private float angle = 0;
        
        private static final Color RING_FILL   = new Color(59, 130, 246, 22);
        private static final Color ARC_BLUE    = new Color(59, 130, 246, 210);
        private static final Color ARC_PURPLE  = new Color(139, 92, 246, 170);
        private static final Color DOT_WHITE   = new Color(255, 255, 255, 200);
        private static final BasicStroke ARC_STROKE = new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        
        private Timer internalTimer;

        AnimatedLogo() {
            setOpaque(false);
            setPreferredSize(new Dimension(54, 54));
            
            internalTimer = new Timer(16, e -> repaint());
            
        }

        
        void advanceAngle() {
            angle = (angle + 1.2f) % 360f;
        }

        
        void stopTimer() {
            if (internalTimer != null) internalTimer.stop();
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int cx = getWidth() / 2, cy = getHeight() / 2, r = 22;
            g2.setColor(RING_FILL);      g2.fillOval(cx - r, cy - r, r * 2, r * 2);
            g2.setStroke(ARC_STROKE);
            g2.setColor(ARC_BLUE);
            g2.drawArc(cx - r + 3, cy - r + 3, (r - 3) * 2, (r - 3) * 2, (int)angle, 255);
            g2.setColor(ARC_PURPLE);
            g2.drawArc(cx - r + 3, cy - r + 3, (r - 3) * 2, (r - 3) * 2, (int)angle + 180, 90);
            g2.setColor(ACCENT_BLUE);   g2.fillOval(cx - 5, cy - 5, 10, 10);
            g2.setColor(DOT_WHITE);     g2.fillOval(cx - 2, cy - 2, 4, 4);
            g2.dispose();
        }
    }

    // ── Spring-physics card ────────────────────────────────────────────────
    static class SpringCard extends JPanel {
        private float scaleAnim = 1f, scaleTarget = 1f, vel = 0f, glowA = 0f;
        private final Color accent;
        private Timer springTimer;

        
        private static final BasicStroke STROKE_BORDER_NORMAL = new BasicStroke(1.3f);
        
        private static final Font FONT_ICON  = new Font("Segoe UI Symbol", Font.BOLD, 22);
        private static final Font FONT_TITLE = new Font("Segoe UI", Font.BOLD, 15);
        private static final Font FONT_SUB   = new Font("Segoe UI", Font.PLAIN, 11);

        SpringCard(String title, String sub, String icon, Color accent) {
            this.accent = accent;
            setLayout(new BorderLayout(10, 0));
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(15, 16, 15, 16));
            setPreferredSize(new Dimension(180, 80));

            JLabel lblI = new JLabel(icon);
            lblI.setFont(FONT_ICON);
            lblI.setForeground(accent);

            JLabel lblT = new JLabel(title);
            lblT.setFont(FONT_TITLE);
            lblT.setForeground(TEXT_PRIMARY);

            JLabel lblS = new JLabel(sub);
            lblS.setFont(FONT_SUB);
            lblS.setForeground(TEXT_MUTED);

            JPanel pT = new JPanel();
            pT.setOpaque(false);
            pT.setLayout(new BoxLayout(pT, BoxLayout.Y_AXIS));
            pT.add(lblT);
            pT.add(Box.createVerticalStrut(3));
            pT.add(lblS);

            add(lblI, BorderLayout.WEST);
            add(pT,   BorderLayout.CENTER);

            springTimer = new Timer(13, e -> {
                float k = 0.28f, b = 0.72f;
                float force = -k * (scaleAnim - scaleTarget) - b * vel;
                vel += force;
                scaleAnim += vel;

                float glowTarget = (scaleTarget > 1f) ? 1f : 0f;
                glowA += (glowTarget - glowA) * 0.12f;
                glowA = Math.max(0, Math.min(1, glowA));

                if (Math.abs(vel) < 0.0003f
                        && Math.abs(scaleAnim - scaleTarget) < 0.0005f
                        && Math.abs(glowA - glowTarget) < 0.005f) {
                    scaleAnim = scaleTarget;
                    vel = 0;
                    glowA = glowTarget;
                    springTimer.stop();
                }
                repaint();
            });
            springTimer.setInitialDelay(0);
            springTimer.setRepeats(true);

            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e)  { scaleTarget = 1.038f; springTimer.restart(); }
                @Override public void mouseExited(MouseEvent e)   { scaleTarget = 1.000f; springTimer.restart(); }
                @Override public void mousePressed(MouseEvent e)  { scaleTarget = 0.965f; springTimer.restart(); }
                @Override public void mouseReleased(MouseEvent e) {
                    scaleTarget = contains(e.getPoint()) ? 1.038f : 1.000f;
                    springTimer.restart();
                }
                @Override public void mouseClicked(MouseEvent e) {
                    for (ActionListener al : listenerList.getListeners(ActionListener.class))
                        al.actionPerformed(new ActionEvent(SpringCard.this, ActionEvent.ACTION_PERFORMED, "click"));
                }
            });
        }

        public void addActionListener(ActionListener l) {
            listenerList.add(ActionListener.class, l);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2;
            g2.translate(cx, cy);
            g2.scale(scaleAnim, scaleAnim);
            g2.translate(-cx, -cy);

            g2.setColor(SURFACE_ALT);
            g2.fillRoundRect(0, 0, w, h, 14, 14);

            
            if (glowA > 0.01f) {
                RadialGradientPaint glow = new RadialGradientPaint(
                    cx, cy, Math.max(w, h) * 0.65f,
                    new float[]{0f, 1f},
                    new Color[]{
                        new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), (int)(glowA * 40)),
                        new Color(0, 0, 0, 0)
                    });
                g2.setPaint(glow);
                g2.fillRoundRect(0, 0, w, h, 14, 14);
            }

            
            int ba = (int)(60 + glowA * 140);
            g2.setColor(glowA > 0.05f
                ? new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), ba)
                : BORDER_COLOR);
            g2.setStroke(STROKE_BORDER_NORMAL);
            g2.drawRoundRect(0, 0, w - 1, h - 1, 14, 14);

            
            if (glowA > 0.01f) {
                g2.setPaint(new GradientPaint(
                    0, h - 3,
                    new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), (int)(glowA * 180)),
                    w, h - 3, new Color(0, 0, 0, 0)));
                g2.fillRoundRect(0, h - 3, w, 3, 3, 3);
            }

            g2.dispose();
            super.paintComponent(g);
        }
    }

    private JButton buildCloseButton() {
        JButton btn = new JButton("✕") {
            boolean h = false;
            private static final Color HOVER_COLOR = new Color(239, 68, 68, 210);
            { addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { h = true;  repaint(); }
                public void mouseExited(MouseEvent e)  { h = false; repaint(); }
            }); }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (h) { g2.setColor(HOVER_COLOR); g2.fillOval(0, 0, 24, 24); }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        btn.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        btn.setForeground(TEXT_MUTED);
        btn.setPreferredSize(new Dimension(24, 24));
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addActionListener(e -> System.exit(0));
        return btn;
    }

    public static void main(String[] args) {
        
        System.setProperty("sun.java2d.opengl",    "true");
        System.setProperty("sun.java2d.d3d",       "true");
        System.setProperty("sun.java2d.noddraw",   "false");
        System.setProperty("sun.java2d.translaccel","true");
        System.setProperty("sun.java2d.videoaccel", "true");
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new AppLauncher().setVisible(true));
    }
}