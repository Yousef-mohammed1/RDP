package com.mycompany.rdpunifiedprogram;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;


public class ClientFrame extends JFrame {

    // ── Design Tokens ─────────────────────────────────────────────────────
    static volatile Color BG_DARK      = new Color(8, 12, 20);
    static volatile Color BG_MID       = new Color(13, 18, 30);
    static volatile Color SURFACE      = new Color(18, 24, 38);
    static volatile Color SURFACE_ALT  = new Color(26, 34, 52);
    static volatile Color SURFACE_MID  = new Color(20, 27, 44);
    static final Color ACCENT_BLUE     = new Color(59, 130, 246);
    static final Color ACCENT_GREEN    = new Color(34, 197, 94);
    static final Color ACCENT_RED      = new Color(239, 68, 68);
    static final Color ACCENT_ORANGE   = new Color(251, 146, 60);
    static final Color ACCENT_PURPLE   = new Color(139, 92, 246);
    static volatile Color TEXT_PRIMARY = new Color(241, 245, 249);
    static volatile Color TEXT_MUTED   = new Color(100, 116, 139);
    static volatile Color BORDER_COLOR = new Color(30, 41, 59);
    static volatile Color BORDER_FOCUS = new Color(59, 130, 246, 120);

    static volatile boolean isLightMode = false;

    // ── Security Configuration ────────────────────────────────────────────
    private static final int MIN_NAME_LENGTH = 2;
    private static final int MAX_NAME_LENGTH = 50;

    // ── ThreadLocal date formatters — SimpleDateFormat is NOT thread-safe ──
    private static final ThreadLocal<SimpleDateFormat> FMT_FULL =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));
    private static final ThreadLocal<SimpleDateFormat> FMT_DATE =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));

    // ── Frame coalescing — prevents EDT queue flood at 60 fps ─────────────
    private static final class FrameUpdate {
        final BufferedImage img;
        final int fps, frameCount;
        final long latencyMs;
        FrameUpdate(BufferedImage img, int fps, int frameCount, long latencyMs) {
            this.img = img; this.fps = fps;
            this.frameCount = frameCount; this.latencyMs = latencyMs;
        }
    }
    private final AtomicReference<FrameUpdate> pendingFrame = new AtomicReference<>();
    private volatile boolean frameUpdateScheduled = false;

    // ── Components ────────────────────────────────────────────────────────
    private JPanel pnlMain, pnlTop, pnlHeader, pnlRole, pnlConnection,
                   pnlScreenWrapper, pnlStatusBar;
    private JLabel lblTitle, lblStatusText, lblRoleHdr, lblModeHdr, lblHint;
    private JLabel lblNameHdr, lblPortHdr, lblPassHdr;
    private JLabel lblFps, lblResolution, lblLatency, lblFrameCount;
    private SegmentedToggle roleToggle, themeToggle;
    private JTextField txtName, txtPort;
    private JPasswordField txtPassword;
    private RippleButton btnConnect;
    private RippleButton btnShareFiles;
    private RoundButton btnDisconnect;
    private ScreenCanvas screenCanvas;
    private JScrollPane scrollScreen;

    // ── Security Logging ──────────────────────────────────────────────────
    private PrintWriter securityLog;

    private RDPClient client;
    private PulsingDot statusDot;
    private Dimension savedSize = new Dimension(1020, 780);

    
    private volatile int remoteWidth  = 0;
    private volatile int remoteHeight = 0;

    
    private volatile double scaleX = 1.0;
    private volatile double scaleY = 1.0;

    private final Map<String, BufferedImage> currentFrames = new ConcurrentHashMap<>();

    public ClientFrame() {
        initSecurityLog();
        initComponents();
        client = new RDPClient(this);
        updateRoleUI();
        logSecurity("APP_START", "localhost", "Client application started");
    }

    // ══════════════════════════════════════════════════════════════════════
    // ── SECURITY METHODS ──────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════

    private void initSecurityLog() {
        try {
            String logDir = System.getProperty("user.home") + "/RDPClientLogs/";
            File dir = new File(logDir);
            if (!dir.exists()) dir.mkdirs();
            String logFile = logDir + "client_security_" +
                FMT_DATE.get().format(new Date()) + ".log";
            securityLog = new PrintWriter(new FileWriter(logFile, true), true);
            System.out.println("Client security log initialized: " + logFile);
        } catch (IOException e) {
            System.err.println("Failed to initialize client security log: " + e.getMessage());
        }
    }

    private void logSecurity(String event, String target, String details) {
        
        String timestamp = FMT_FULL.get().format(new Date());
        String logEntry = String.format("[%s] [%s] Target=%s | %s",
            timestamp, event, target, details);
        if (securityLog != null) {
            securityLog.println(logEntry);
            
        }
        System.out.println("[SECURITY] " + logEntry);
    }

    private boolean isValidEnglishName(String name) {
        return name.matches("^[a-zA-Z0-9\\s._-]+$");
    }

    private boolean isValidPort(int port) {
        return port >= 1 && port <= 65535;
    }

    // ══════════════════════════════════════════════════════════════════════
    // ── UI INITIALIZATION ─────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════

    private void initComponents() {
        setTitle("RDP Client");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1020, 780);
        setMinimumSize(new Dimension(720, 520));
        getContentPane().setBackground(BG_DARK);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { onClose(); }
        });

        // ── Header ────────────────────────────────────────────────────
        pnlHeader = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(SURFACE);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setPaint(new GradientPaint(0, 0, new Color(59, 130, 246, 90),
                    6, 0, new Color(0, 0, 0, 0)));
                g2.fillRect(0, 0, 6, getHeight());
                g2.dispose();
            }
        };
        pnlHeader.setOpaque(false);
        pnlHeader.setBorder(new CompoundBorder(
            new MatteBorder(0, 0, 1, 0, BORDER_COLOR),
            new EmptyBorder(10, 16, 10, 16)));

        JPanel pnlTitleLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        pnlTitleLeft.setOpaque(false);
        pnlTitleLeft.add(Box.createHorizontalStrut(8));

        lblTitle = new JLabel("RDP Client");
        lblTitle.setFont(new Font("Segoe UI", Font.BOLD, 16));
        lblTitle.setForeground(TEXT_PRIMARY);
        pnlTitleLeft.add(lblTitle);

        JPanel vSep = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(BORDER_COLOR);
                g.fillRect(0, 4, 1, 14);
            }
        };
        vSep.setOpaque(false);
        vSep.setPreferredSize(new Dimension(1, 22));
        pnlTitleLeft.add(vSep);

        statusDot = new PulsingDot(TEXT_MUTED);
        pnlTitleLeft.add(statusDot);

        lblStatusText = new JLabel("Not connected");
        lblStatusText.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        lblStatusText.setForeground(TEXT_MUTED);
        pnlTitleLeft.add(lblStatusText);

        pnlHeader.add(pnlTitleLeft, BorderLayout.WEST);

        // ── Role Selector ─────────────────────────────────────────────
        pnlRole = new JPanel(new BorderLayout());
        pnlRole.setBackground(new Color(14, 19, 32));
        pnlRole.setBorder(new MatteBorder(0, 0, 1, 0, BORDER_COLOR));

        JPanel pnlRoleLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 8));
        pnlRoleLeft.setOpaque(false);

        lblRoleHdr = new JLabel("Role:");
        lblRoleHdr.setFont(new Font("Segoe UI", Font.BOLD, 12));
        lblRoleHdr.setForeground(TEXT_MUTED);

        roleToggle = new SegmentedToggle(new String[]{"Receive Screen", "Share My Screen"});
        roleToggle.addChangeListener(e -> updateRoleUI());

        lblHint = new JLabel("One side must Share, the other Receive");
        lblHint.setFont(new Font("Segoe UI", Font.ITALIC, 11));
        lblHint.setForeground(new Color(60, 80, 110));

        pnlRoleLeft.add(lblRoleHdr);
        pnlRoleLeft.add(roleToggle);
        pnlRoleLeft.add(Box.createHorizontalStrut(12));
        pnlRoleLeft.add(lblHint);

        JPanel pnlRoleRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 8));
        pnlRoleRight.setOpaque(false);

        lblModeHdr = new JLabel("Mode:");
        lblModeHdr.setFont(new Font("Segoe UI", Font.BOLD, 12));
        lblModeHdr.setForeground(TEXT_MUTED);

        themeToggle = new SegmentedToggle(new String[]{"Night Mode", "Light Mode"});
        themeToggle.setPreferredSize(new Dimension(180, 32));
        themeToggle.addChangeListener(e -> applyThemeToUI(themeToggle.getSelectedIndex() == 1));

        pnlRoleRight.add(lblModeHdr);
        pnlRoleRight.add(themeToggle);

        pnlRole.add(pnlRoleLeft, BorderLayout.WEST);
        pnlRole.add(pnlRoleRight, BorderLayout.EAST);

        // ── Connection Panel ──────────────────────────────────────────
        pnlConnection = new JPanel(new GridBagLayout());
        pnlConnection.setBackground(SURFACE);
        pnlConnection.setBorder(new CompoundBorder(
            new MatteBorder(0, 0, 1, 0, BORDER_COLOR),
            new EmptyBorder(12, 16, 12, 16)));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(0, 0, 0, 12);

        lblNameHdr = makeFieldLabel("Name (English only, 2-50 chars)");
        lblPortHdr = makeFieldLabel("Port");
        lblPassHdr = makeFieldLabel("Password");

        gbc.gridy = 0;
        gbc.gridx = 0; pnlConnection.add(lblNameHdr, gbc);
        gbc.gridx = 2; pnlConnection.add(lblPortHdr, gbc);
        gbc.gridx = 4; pnlConnection.add(lblPassHdr, gbc);

        gbc.gridy = 1;
        gbc.insets = new Insets(4, 0, 0, 12);

        txtName = makeField("", 12);
        txtName.addKeyListener(new KeyAdapter() {
            @Override public void keyReleased(KeyEvent e) {
                String text = txtName.getText().trim();
                if (!text.isEmpty() && !isValidEnglishName(text)) {
                    txtName.setBorder(new CompoundBorder(
                        new RoundedBorder(8, ACCENT_RED), new EmptyBorder(7, 11, 7, 11)));
                } else if (text.length() > MAX_NAME_LENGTH) {
                    txtName.setBorder(new CompoundBorder(
                        new RoundedBorder(8, ACCENT_ORANGE), new EmptyBorder(7, 11, 7, 11)));
                } else {
                    txtName.setBorder(new CompoundBorder(
                        new RoundedBorder(8, BORDER_COLOR), new EmptyBorder(7, 11, 7, 11)));
                }
            }
        });
        gbc.gridx = 0; pnlConnection.add(txtName, gbc);

        txtPort = makeField("5900", 6);
        gbc.gridx = 2; pnlConnection.add(txtPort, gbc);

        txtPassword = new JPasswordField(6);
        txtPassword.setUI(new javax.swing.plaf.basic.BasicPasswordFieldUI());
        styleField(txtPassword);
        gbc.gridx = 4; pnlConnection.add(txtPassword, gbc);

        gbc.gridx = 5; gbc.weightx = 1.0;
        gbc.insets = new Insets(4, 0, 0, 12);
        pnlConnection.add(Box.createHorizontalGlue(), gbc);

        gbc.weightx = 0;
        gbc.insets = new Insets(4, 4, 0, 0);

        // ── Share Files button — LEFT of Connect ──────────────────────────────
        btnShareFiles = new RippleButton("Share Files", ACCENT_PURPLE);
        btnShareFiles.setPreferredSize(new Dimension(130, 38));
        btnShareFiles.setEnabled(false);          
        btnShareFiles.addActionListener(e -> onShareFiles());
        gbc.gridx = 6;
        pnlConnection.add(btnShareFiles, gbc);

        // ── Connect button ────────────────────────────────────────────────────
        btnConnect = new RippleButton("Connect", ACCENT_GREEN);
        btnConnect.setPreferredSize(new Dimension(148, 38));
        btnConnect.addActionListener(e -> onConnect());
        gbc.gridx = 7;
        pnlConnection.add(btnConnect, gbc);

        // ── Disconnect button ─────────────────────────────────────────────────
        btnDisconnect = new RoundButton("Disconnect", new Color(70,18,18), ACCENT_RED);
        btnDisconnect.setPreferredSize(new Dimension(148, 38));
        btnDisconnect.setEnabled(false);
        btnDisconnect.addActionListener(e -> onDisconnect());
        gbc.gridx = 8;
        pnlConnection.add(btnDisconnect, gbc);

        // ── Screen Canvas ─────────────────────────────────────────────
        pnlScreenWrapper = new JPanel(new BorderLayout());
        pnlScreenWrapper.setBackground(BG_DARK);
        pnlScreenWrapper.setBorder(new EmptyBorder(0, 0, 0, 0));

        screenCanvas = new ScreenCanvas();
        screenCanvas.setPreferredSize(new Dimension(1000, 600));

        screenCanvas.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e)   { onMouseEvent("MOVE", e); }
            @Override public void mouseDragged(MouseEvent e) { onMouseEvent("MOVE", e); }
        });
        screenCanvas.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  {
                screenCanvas.requestFocusInWindow();
                onMouseEvent("PRESS", e);
            }
            @Override public void mouseReleased(MouseEvent e) { onMouseEvent("RELEASE", e); }
        });
        screenCanvas.addMouseWheelListener(this::onMouseWheel);
        screenCanvas.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e)  { onKeyEvent("PRESS", e); }
            @Override public void keyReleased(KeyEvent e) { onKeyEvent("RELEASE", e); }
        });
        screenCanvas.setFocusable(true);

        scrollScreen = new JScrollPane(screenCanvas);
        scrollScreen.setBorder(null);
        scrollScreen.getViewport().setBackground(new Color(6, 8, 14));
        scrollScreen.getVerticalScrollBar().setUI(new DarkScrollBarUI());
        scrollScreen.getHorizontalScrollBar().setUI(new DarkScrollBarUI());
        pnlScreenWrapper.add(scrollScreen, BorderLayout.CENTER);

        // ── Status Bar ────────────────────────────────────────────────
        pnlStatusBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 18, 5)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(isLightMode
                    ? new Color(241, 245, 249) : new Color(10, 13, 22));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
        pnlStatusBar.setOpaque(false);
        pnlStatusBar.setBorder(new MatteBorder(1, 0, 0, 0, BORDER_COLOR));

        lblFps        = makeStatLabel("FPS —");
        lblResolution = makeStatLabel("Resolution —");
        lblLatency    = makeStatLabel("Latency —");
        lblFrameCount = makeStatLabel("Frames 0");

        pnlStatusBar.add(makeStatDot(new Color(34, 197, 94)));
        pnlStatusBar.add(lblFps);
        pnlStatusBar.add(makeStatSep());
        pnlStatusBar.add(makeStatDot(ACCENT_BLUE));
        pnlStatusBar.add(lblResolution);
        pnlStatusBar.add(makeStatSep());
        pnlStatusBar.add(makeStatDot(new Color(139, 92, 246)));
        pnlStatusBar.add(lblLatency);
        pnlStatusBar.add(makeStatSep());
        pnlStatusBar.add(makeStatDot(TEXT_MUTED));
        pnlStatusBar.add(lblFrameCount);

        pnlScreenWrapper.add(pnlStatusBar, BorderLayout.SOUTH);

        // ── Main Layout ───────────────────────────────────────────────
        pnlMain = new JPanel(new BorderLayout());
        pnlMain.setBackground(BG_DARK);

        pnlTop = new JPanel();
        pnlTop.setLayout(new BoxLayout(pnlTop, BoxLayout.Y_AXIS));
        pnlTop.setBackground(BG_DARK);
        pnlTop.add(pnlHeader);
        pnlTop.add(pnlRole);
        pnlTop.add(pnlConnection);

        pnlMain.add(pnlTop, BorderLayout.NORTH);
        pnlMain.add(pnlScreenWrapper, BorderLayout.CENTER);

        setContentPane(pnlMain);
        pack();
        setLocationRelativeTo(null);
    }

    // ── Theme Switching ───────────────────────────────────────────────────
    private void applyThemeToUI(boolean isLight) {
        isLightMode = isLight;
        if (isLight) {
            BG_DARK      = new Color(240, 244, 248);
            BG_MID       = new Color(226, 232, 240);
            SURFACE      = new Color(255, 255, 255);
            SURFACE_ALT  = new Color(248, 250, 252);
            SURFACE_MID  = new Color(241, 245, 249);
            TEXT_PRIMARY = new Color(15, 23, 42);
            TEXT_MUTED   = new Color(100, 116, 139);
            BORDER_COLOR = new Color(203, 213, 225);
        } else {
            BG_DARK      = new Color(8, 12, 20);
            BG_MID       = new Color(13, 18, 30);
            SURFACE      = new Color(18, 24, 38);
            SURFACE_ALT  = new Color(26, 34, 52);
            SURFACE_MID  = new Color(20, 27, 44);
            TEXT_PRIMARY = new Color(241, 245, 249);
            TEXT_MUTED   = new Color(100, 116, 139);
            BORDER_COLOR = new Color(30, 41, 59);
        }

        getContentPane().setBackground(BG_DARK);
        pnlMain.setBackground(BG_DARK);
        pnlTop.setBackground(BG_DARK);

        lblTitle.setForeground(TEXT_PRIMARY);
        lblRoleHdr.setForeground(TEXT_MUTED);
        if (lblModeHdr != null) lblModeHdr.setForeground(TEXT_MUTED);
        lblHint.setForeground(TEXT_MUTED);

        pnlRole.setBackground(isLight ? new Color(241, 245, 249) : new Color(14, 19, 32));
        pnlConnection.setBackground(SURFACE);

        lblNameHdr.setForeground(TEXT_MUTED);
        lblPortHdr.setForeground(TEXT_MUTED);
        lblPassHdr.setForeground(TEXT_MUTED);

        updateFieldStyle(txtName);
        updateFieldStyle(txtPort);
        updateFieldStyle(txtPassword);

        pnlScreenWrapper.setBackground(BG_DARK);
        scrollScreen.getViewport().setBackground(
            isLight ? new Color(226, 232, 240) : new Color(6, 8, 14));

        screenCanvas.setLightMode(isLight);
        repaint();
    }

    private void updateFieldStyle(JTextField f) {
        f.setBackground(SURFACE_ALT);
        f.setForeground(TEXT_PRIMARY);
        f.setCaretColor(ACCENT_BLUE);
        f.setBorder(new CompoundBorder(
            new RoundedBorder(8, BORDER_COLOR), new EmptyBorder(7, 11, 7, 11)));
    }

    // ── Role UI ───────────────────────────────────────────────────────────
    private void updateRoleUI() {
        boolean isReceive = roleToggle.getSelectedIndex() == 0;
        if (isReceive) {
            if (pnlScreenWrapper.getParent() != pnlMain)
                pnlMain.add(pnlScreenWrapper, BorderLayout.CENTER);
            setMinimumSize(new Dimension(720, 520));
            if (savedSize != null && savedSize.width >= 720) setSize(savedSize);
        } else {
            savedSize = getSize();
            pnlMain.remove(pnlScreenWrapper);
            currentFrames.clear();
            screenCanvas.clearFrames();
            resetStatusBar();
            setMinimumSize(new Dimension(720, 150));
            pack();
        }
        pnlMain.revalidate();
        pnlMain.repaint();
    }

    // ── Input Events ──────────────────────────────────────────────────────
    private void onMouseEvent(String action, MouseEvent e) {

        int localW = screenCanvas.getWidth();
        int localH = screenCanvas.getHeight();
        if (localW > 0 && localH > 0 && remoteWidth > 0 && remoteHeight > 0) {

            double sx = (double) remoteWidth  / localW;
            double sy = (double) remoteHeight / localH;
            int scaledX = (int)(e.getX() * sx);
            int scaledY = (int)(e.getY() * sy);
            int btn = 0;
            if ("MOVE".equals(action)) {
                if (SwingUtilities.isLeftMouseButton(e)) btn = 1;
                else if (SwingUtilities.isMiddleMouseButton(e)) btn = 2;
                else if (SwingUtilities.isRightMouseButton(e)) btn = 3;
            } else {
                if (e.getButton() == MouseEvent.BUTTON1) btn = 1;
                else if (e.getButton() == MouseEvent.BUTTON2) btn = 2;
                else if (e.getButton() == MouseEvent.BUTTON3) btn = 3;
            }
            client.sendMouse(action, scaledX, scaledY, btn);
        }
    }

    private void onMouseWheel(MouseWheelEvent e) {
        int localW = screenCanvas.getWidth();
        int localH = screenCanvas.getHeight();
        if (localW > 0 && localH > 0 && remoteWidth > 0 && remoteHeight > 0) {
            double sx = (double) remoteWidth  / localW;
            double sy = (double) remoteHeight / localH;
            int scaledX = (int)(e.getX() * sx);
            int scaledY = (int)(e.getY() * sy);
            client.sendMouse("WHEEL", scaledX, scaledY, e.getWheelRotation());
        }
    }

    private void onKeyEvent(String action, KeyEvent e) {
        client.sendKey(action, e.getKeyCode());
    }

    // ── Connection Actions ────────────────────────────────────────────────
    private void onConnect() {
        String name = txtName.getText().trim();
        if (name.isEmpty() || name.length() < MIN_NAME_LENGTH
                || name.length() > MAX_NAME_LENGTH || !isValidEnglishName(name)) {
            showToast("Please enter a valid English name (2-50 chars).", true);
            txtName.requestFocusInWindow();
            return;
        }

        String password = new String(txtPassword.getPassword());
        if (password.isEmpty()) {
            showToast("Please enter the server password.", true);
            txtPassword.requestFocusInWindow();
            return;
        }

        int port;
        try {
            port = Integer.parseInt(txtPort.getText().trim());
            if (!isValidPort(port)) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            showToast("Invalid port number (must be 1-65535).", true);
            txtPort.requestFocusInWindow();
            return;
        }

        String role = roleToggle.getSelectedIndex() == 0 ? "RECEIVE" : "SHARE";
        client.setClientRole(role);

        if ("SHARE".equals(role) && !client.canShare()) {
            showToast("Screen capture (Robot) is not available on this system.\n"
                + "Cannot use Share mode.", true);
            return;
        }

        setConnected(false);
        btnConnect.startSpinner();
        final String finalName = name;
        final int finalPort   = port;

        logSecurity("CONNECT_ATTEMPT", "server",
            "Name=" + finalName + " Port=" + finalPort + " Role=" + role);

        Thread connectThread = new Thread(() -> {
            boolean ok = client.connect(finalPort, password, finalName);
            SwingUtilities.invokeLater(() -> {
                btnConnect.stopSpinner();
                if (ok) {
                    setConnected(true);
                    statusDot.setColor(ACCENT_GREEN);
                    statusDot.setActive(true);
                    lblStatusText.setForeground(ACCENT_GREEN);
                    lblStatusText.setText(
                        "Connected · Port " + finalPort + "[" + role + "]");
                    showToast("Connected successfully", false);
                    logSecurity("CONNECT_SUCCESS", "server",
                        "Connected as " + finalName + " [" + role + "]");
                } else {
                    resetConnectControls();
                    logSecurity("CONNECT_FAILED", "server", "Connection failed");
                    String status = lblStatusText.getText();
                    if (status != null && (status.contains("Wrong password")
                            || status.contains("Authentication failed"))) {
                        showToast("Wrong password", true);
                    } else {
                        showToast("Connection failed", true);
                    }
                }
            });
        }, "rdp-connect");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    private void onDisconnect() {
        Thread disconnectThread = new Thread(() -> {
            client.disconnect();
            SwingUtilities.invokeLater(() -> {
                setConnected(false);
                resetConnectControls();
                currentFrames.clear();
                screenCanvas.clearFrames();
                resetStatusBar();
                statusDot.setColor(TEXT_MUTED);
                statusDot.setActive(false);
                lblStatusText.setForeground(TEXT_MUTED);
                lblStatusText.setText("Not connected");
                showToast("Disconnected", false);
                logSecurity("DISCONNECT", "server", "User initiated disconnect");
            });
        }, "rdp-disconnect");
        disconnectThread.setDaemon(true);
        disconnectThread.start();
    }
    
    // ── File Sharing Action ────────────────────────────────────────────────
    private void onShareFiles() {
        FileTransferClient ftc = client.getFileTransferClient();
        if (ftc == null) {
            showToast("Not connected. Please connect first.", true);
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Files to Share");
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setApproveButtonText("Share");

        int result = chooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) return;

        java.io.File[] selected = chooser.getSelectedFiles();
        if (selected == null || selected.length == 0) return;

        java.util.List<java.io.File> files = new java.util.ArrayList<>();
        for (java.io.File f : selected)
            if (f.isFile() && f.canRead()) files.add(f);

        if (files.isEmpty()) {
            showToast("No readable files selected.", true);
            return;
        }


        btnShareFiles.setEnabled(false);
        setStatus("📤 Preparing to send " + files.size() + " file(s)...", false);

        Runnable onAllDone = () -> {
            
            if (client.getFileTransferClient() != null) {
                btnShareFiles.setEnabled(true);
                setStatus("✅ File transfer complete", false);
            }
        };

        ftc.sendFiles(files, onAllDone);
    }
    
    private void onClose() {
        logSecurity("APP_STOP", "localhost", "Client application closing");
        client.disconnect();
        if (securityLog != null) securityLog.close();
        dispose();
        System.exit(0);
    }

    // ── Public API ────────────────────────────────────────────────────────

    public void updateFrame(BufferedImage img, int fps, int frameCount, long latencyMs) {
        
        this.remoteWidth  = img.getWidth();
        this.remoteHeight = img.getHeight();

        currentFrames.put("Server", img);

        
        pendingFrame.set(new FrameUpdate(img, fps, frameCount, latencyMs));

        
        if (!frameUpdateScheduled) {
            frameUpdateScheduled = true;
            SwingUtilities.invokeLater(this::flushFrameUpdate);
        }
    }

    
    private void flushFrameUpdate() {
        frameUpdateScheduled = false;
        FrameUpdate upd = pendingFrame.getAndSet(null);
        if (upd == null) return;

        screenCanvas.updateState(currentFrames, "Server", false);
        
        screenCanvas.repaint();
        lblFps.setText("FPS " + upd.fps);
        lblResolution.setText(upd.img.getWidth() + " × " + upd.img.getHeight());
        lblLatency.setText(upd.latencyMs + " ms");
        lblFrameCount.setText("Frames " + upd.frameCount);
    }

    public void onConnectionLost(String reason) {
        SwingUtilities.invokeLater(() -> {
            setConnected(false);
            resetConnectControls();
            currentFrames.clear();
            screenCanvas.clearFrames();
            btnShareFiles.setEnabled(false);
            resetStatusBar();
            statusDot.setColor(ACCENT_RED);
            statusDot.setActive(false);
            lblStatusText.setForeground(ACCENT_RED);
            lblStatusText.setText("Connection lost");
            showToast("Connection lost: " + reason, true);
            logSecurity("CONNECTION_LOST", "server", reason);
        });
    }

    public void setStatus(String message, boolean error) {
        SwingUtilities.invokeLater(() -> {
            lblStatusText.setText(message);
            Color c = error ? ACCENT_RED : TEXT_MUTED;
            statusDot.setColor(c);
            lblStatusText.setForeground(c);
        });
    }

    // ── Toast Notification ────────────────────────────────────────────────
    private void showToast(String msg, boolean error) {
        
        if (!isShowing()) return;

        JWindow toast = new JWindow(this);
        toast.setBackground(new Color(0, 0, 0, 0));

        JPanel p = new JPanel(new BorderLayout(8, 0)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(error
                    ? new Color(50, 15, 15, 240)
                    : new Color(15, 30, 20, 240));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                g2.setColor(error ? ACCENT_RED : ACCENT_GREEN);
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                g2.dispose();
            }
        };
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));

        JLabel dot = new JLabel("●");
        dot.setForeground(error ? ACCENT_RED : ACCENT_GREEN);
        dot.setFont(new Font("Segoe UI", Font.PLAIN, 10));

        JLabel lbl = new JLabel(msg);
        lbl.setForeground(Color.WHITE);
        lbl.setFont(new Font("Segoe UI", Font.PLAIN, 13));

        p.add(dot, BorderLayout.WEST);
        p.add(lbl, BorderLayout.CENTER);
        toast.add(p);
        toast.pack();

        try {
            Point loc = getLocationOnScreen();
            toast.setLocation(loc.x + getWidth() - toast.getWidth() - 20,
                loc.y + getHeight() - toast.getHeight() - 20);
        } catch (IllegalComponentStateException ex) {
            toast.dispose();
            return;
        }

        toast.setOpacity(0f);
        toast.setVisible(true);

        
        final float[] a     = {0f};
        final boolean[] fading = {false};
        final int[]   hold  = {0};
        Timer anim = new Timer(16, null);
        anim.addActionListener(e2 -> {
            if (!fading[0]) {
                a[0] = Math.min(1f, a[0] + 0.08f);
                toast.setOpacity(a[0]);
                if (a[0] >= 1f && ++hold[0] > 160) fading[0] = true;
            } else {
                a[0] = Math.max(0f, a[0] - 0.06f);
                toast.setOpacity(a[0]);
                if (a[0] <= 0f) { anim.stop(); toast.dispose(); }
            }
        });
        anim.start();
    }

    // ── Helpers ───────────────────────────────────────────────────────────
    private void setConnected(boolean connected) {
        btnConnect.setEnabled(!connected);
        btnDisconnect.setVisible(true);
        btnDisconnect.setEnabled(connected);
        btnShareFiles.setEnabled(connected);
        txtName.setEnabled(!connected);
        txtPort.setEnabled(!connected);
        txtPassword.setEnabled(!connected);
        roleToggle.setEnabled(!connected);
        themeToggle.setEnabled(!connected);
    }

    private void resetConnectControls() {
        btnConnect.setEnabled(true);
        btnShareFiles.setEnabled(false);
        txtName.setEnabled(true);
        txtPort.setEnabled(true);
        txtPassword.setEnabled(true);
        roleToggle.setEnabled(true);
        themeToggle.setEnabled(true);
    }

    private void resetStatusBar() {
        lblFps.setText("FPS —");
        lblResolution.setText("Resolution —");
        lblLatency.setText("Latency —");
        lblFrameCount.setText("Frames 0");
    }

    private JLabel makeFieldLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        l.setForeground(TEXT_MUTED);
        return l;
    }

    private JTextField makeField(String placeholder, int cols) {
        JTextField f = new JTextField(placeholder, cols);
        styleField(f);
        return f;
    }

    private void styleField(JTextField f) {
        updateFieldStyle(f);
        f.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        f.addFocusListener(new FocusAdapter() {
            public void focusGained(FocusEvent e) {
                f.setBorder(new CompoundBorder(
                    new RoundedBorder(8, BORDER_FOCUS), new EmptyBorder(7, 11, 7, 11)));
            }
            public void focusLost(FocusEvent e) {
                f.setBorder(new CompoundBorder(
                    new RoundedBorder(8, BORDER_COLOR), new EmptyBorder(7, 11, 7, 11)));
            }
        });
    }

    private JLabel makeStatLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Consolas", Font.PLAIN, 11));
        l.setForeground(TEXT_MUTED);
        return l;
    }

    private JPanel makeStatSep() {
        JPanel sep = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(BORDER_COLOR);
                g.fillRect(0, 3, 1, 10);
            }
        };
        sep.setOpaque(false);
        sep.setPreferredSize(new Dimension(1, 16));
        return sep;
    }

    private JLabel makeStatDot(Color c) {
        JLabel l = new JLabel("●");
        l.setFont(new Font("Segoe UI", Font.PLAIN, 8));
        l.setForeground(new Color(c.getRed(), c.getGreen(), c.getBlue(), 120));
        return l;
    }

    // ══════════════════════════════════════════════════════════════════════
    // ── Inner Components ──────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════

    
    static class PulsingDot extends JComponent {
        private volatile Color color;
        private boolean active = false;
        private float pulse = 0f, pulseDir = 0.04f;

        private Color cachedPulseGlow = null;
        private Color lastCachedColor = null;

        PulsingDot(Color c) {
            this.color = c;
            setPreferredSize(new Dimension(10, 10));
            new Timer(40, e -> {
                if (active) {
                    pulse += pulseDir;
                    if (pulse > 1 || pulse < 0) pulseDir = -pulseDir;
                } else {
                    pulse = 0;
                }
                repaint();
            }).start();
        }

        public void setColor(Color c) {
            this.color = c;
            lastCachedColor = null; 
            repaint();
        }

        public void setActive(boolean a) { this.active = a; }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);

            Color col = color; 
            if (active) {
                
                if (col != lastCachedColor) {
                    lastCachedColor = col;
                    cachedPulseGlow = new Color(
                        col.getRed(), col.getGreen(), col.getBlue(), 0);
                }
                int r = (int)(pulse * 4);
                int a2 = (int)((1 - pulse) * 80);
                
                if (a2 > 0) {
                    g2.setColor(new Color(
                        col.getRed(), col.getGreen(), col.getBlue(), a2));
                    g2.fillOval(5 - r / 2, 5 - r / 2, r, r);
                }
            }
            g2.setColor(col);
            g2.fillOval(1, 1, 8, 8);
            g2.dispose();
        }
    }

    
    static class SegmentedToggle extends JPanel {
        private int selected = 0;
        private final String[] options;
        private final float[] hoverA;
        private final java.util.List<ActionListener> listeners =
            new java.util.ArrayList<>();
        private boolean enabled = true;

        
        private static final Font FONT_BOLD  =
            new Font("Segoe UI", Font.BOLD,  12);
        private static final Font FONT_PLAIN =
            new Font("Segoe UI", Font.PLAIN, 12);

        SegmentedToggle(String[] options) {
            this.options = options;
            hoverA = new float[options.length];
            setOpaque(false);
            setPreferredSize(new Dimension(options.length * 140, 32));

            addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    if (!enabled) return;
                    int idx = e.getX() / (getWidth() / options.length);
                    if (idx >= 0 && idx < options.length && idx != selected) {
                        selected = idx;
                        listeners.forEach(l -> l.actionPerformed(
                            new ActionEvent(SegmentedToggle.this, 0, "change")));
                        repaint();
                    }
                }
            });
            addMouseMotionListener(new MouseMotionAdapter() {
                public void mouseMoved(MouseEvent e) { repaint(); }
            });

            
            new Timer(20, e2 -> {
                boolean changed = false;
                for (int i = 0; i < options.length; i++) {
                    float target = (i == selected) ? 1f : 0f;
                    if (Math.abs(hoverA[i] - target) > 0.01f) {
                        hoverA[i] += (target - hoverA[i]) * 0.2f;
                        changed = true;
                    }
                }
                if (changed) repaint();
            }).start();
        }

        public void addChangeListener(ActionListener l) { listeners.add(l); }
        public int getSelectedIndex() { return selected; }

        @Override public void setEnabled(boolean en) {
            this.enabled = en; super.setEnabled(en); repaint();
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight(), segW = w / options.length;

            g2.setColor(enabled
                ? (isLightMode ? new Color(226, 232, 240) : new Color(16, 22, 36))
                : (isLightMode ? new Color(226, 232, 240, 100) : new Color(16, 22, 36, 100)));
            g2.fillRoundRect(0, 0, w, h, 10, 10);

            Color bc = BORDER_COLOR;
            g2.setColor(enabled ? bc
                : new Color(bc.getRed(), bc.getGreen(), bc.getBlue(), 80));
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(0, 0, w - 1, h - 1, 10, 10);

            int px = selected * segW + 2, pw = segW - 4;
            g2.setColor(enabled
                ? (isLightMode ? new Color(255, 255, 255) : new Color(30, 45, 75))
                : (isLightMode ? new Color(255, 255, 255, 100) : new Color(30, 45, 75, 100)));
            g2.fillRoundRect(px, 2, pw, h - 4, 8, 8);

            g2.setColor(enabled
                ? new Color(59, 130, 246, 100)
                : new Color(59, 130, 246, 40));
            g2.drawRoundRect(px, 2, pw - 1, h - 5, 8, 8);

            for (int i = 0; i < options.length; i++) {
                // Use cached fonts — no allocation per segment per tick
                g2.setFont(i == selected ? FONT_BOLD : FONT_PLAIN);
                Color textColor = i == selected ? TEXT_PRIMARY : TEXT_MUTED;
                if (!enabled) textColor = new Color(
                    textColor.getRed(), textColor.getGreen(),
                    textColor.getBlue(), 100);
                g2.setColor(textColor);
                FontMetrics fm = g2.getFontMetrics();
                int tx = i * segW + (segW - fm.stringWidth(options[i])) / 2;
                int ty = (h + fm.getAscent() - fm.getDescent()) / 2;
                g2.drawString(options[i], tx, ty);
            }
            g2.dispose();
        }
    }

    static class RippleButton extends JButton {
        private Color bg;
        private float rippleR = 0, rippleA = 0;
        private int rippleX, rippleY;
        private boolean spinning = false;
        private float spinAngle = 0;
        private final Timer spinTimer;

        private static final BasicStroke SPIN_STROKE =
            new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

        RippleButton(String text, Color bg) {
            super(text);
            this.bg = bg;
            setOpaque(false); setContentAreaFilled(false);
            setBorderPainted(false); setFocusPainted(false);
            setFont(new Font("Segoe UI", Font.BOLD, 13));
            setForeground(Color.WHITE);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            spinTimer = new Timer(16, e -> {
                spinAngle = (spinAngle + 6f) % 360;
                rippleR   = Math.min(rippleR + 5, 80);
                rippleA   = Math.max(0, rippleA - 0.025f);
                repaint();
            });

            addMouseListener(new MouseAdapter() {
                public void mousePressed(MouseEvent e) {
                    rippleX = e.getX(); rippleY = e.getY();
                    rippleR = 0; rippleA = 0.35f;
                    spinTimer.restart();
                }
                public void mouseReleased(MouseEvent e) {
                    if (!spinning) spinTimer.stop();
                }
            });
        }

        public void startSpinner() {
            spinning = true; spinTimer.start(); setText("Connecting…");
        }
        public void stopSpinner() {
            spinning = false; spinTimer.stop(); setText("Connect");
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            Color fill = isEnabled() ? bg
                : (isLightMode ? new Color(203, 213, 225) : new Color(40, 55, 70));
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, w, h, 9, 9);

            if (rippleA > 0.01f) {
                g2.setColor(new Color(255, 255, 255, (int)(rippleA * 120)));
                g2.fillOval(rippleX - (int)rippleR, rippleY - (int)rippleR,
                    (int)(rippleR * 2), (int)(rippleR * 2));
            }
            if (spinning) {
                g2.setStroke(SPIN_STROKE); // reuse pre-allocated stroke
                g2.setColor(new Color(255, 255, 255, 200));
                g2.drawArc(w - 24, h / 2 - 7, 14, 14, (int)spinAngle, 270);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    static class RoundButton extends JButton {
        private final Color bg, bgH;
        private boolean hov = false;

        RoundButton(String t, Color bg, Color bgH) {
            super(t); this.bg = bg; this.bgH = bgH;
            setOpaque(false); setContentAreaFilled(false);
            setBorderPainted(false); setFocusPainted(false);
            setFont(new Font("Segoe UI", Font.BOLD, 13));
            setForeground(Color.WHITE);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hov = true;  repaint(); }
                public void mouseExited(MouseEvent e)  { hov = false; repaint(); }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(isEnabled()
                ? (hov ? bgH : bg)
                : (isLightMode ? new Color(203, 213, 225) : new Color(35, 45, 62)));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 9, 9);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    static class RoundedBorder extends AbstractBorder {
        private final int r;
        private final Color c;
        RoundedBorder(int r, Color c) { this.r = r; this.c = c; }

        @Override public void paintBorder(Component comp, Graphics g,
                int x, int y, int w, int h) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(c);
            g2.drawRoundRect(x, y, w - 1, h - 1, r, r);
            g2.dispose();
        }

        @Override public Insets getBorderInsets(Component c2) {
            return new Insets(r / 2, r / 2, r / 2, r / 2);
        }
    }

    static class DarkScrollBarUI
            extends javax.swing.plaf.basic.BasicScrollBarUI {
        @Override protected JButton createDecreaseButton(int o) { return invis(); }
        @Override protected JButton createIncreaseButton(int o) { return invis(); }

        private JButton invis() {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(0, 0));
            b.setMinimumSize(new Dimension(0, 0));
            b.setMaximumSize(new Dimension(0, 0));
            return b;
        }

        @Override protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(isLightMode
                ? new Color(148, 163, 184) : new Color(45, 60, 88));
            g2.fillRoundRect(r.x + 2, r.y + 2,
                r.width - 4, r.height - 4, 6, 6);
            g2.dispose();
        }

        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
            g.setColor(isLightMode
                ? new Color(241, 245, 249) : new Color(12, 16, 26));
            g.fillRect(r.x, r.y, r.width, r.height);
        }
    }
}