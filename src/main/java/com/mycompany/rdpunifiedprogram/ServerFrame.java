package com.mycompany.rdpunifiedprogram;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.TableModelEvent;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import javax.swing.table.*;
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

public class ServerFrame extends JFrame {

    // ── Design Tokens ─────────────────────────────────────────────────────
    static volatile Color BG_DARK      = new Color(8, 12, 20);
    static volatile Color BG_MID       = new Color(13, 18, 30);
    static volatile Color SURFACE      = new Color(18, 24, 38);
    static volatile Color SURFACE_ALT  = new Color(26, 34, 52);
    static volatile Color SURFACE_MID  = new Color(20, 27, 44);
    static final Color ACCENT_BLUE     = new Color(59, 130, 246);
    static final Color ACCENT_GREEN    = new Color(34, 197, 94);
    static final Color ACCENT_RED      = new Color(239, 68, 68);
    static final Color ACCENT_PURPLE   = new Color(139, 92, 246);
    static final Color ACCENT_ORANGE   = new Color(251, 146, 60);
    static volatile Color TEXT_PRIMARY = new Color(241, 245, 249);
    static volatile Color TEXT_MUTED   = new Color(100, 116, 139);
    static volatile Color BORDER_COLOR = new Color(30, 41, 59);
    static volatile Color BORDER_FOCUS = new Color(59, 130, 246, 120);

    static volatile boolean isLightMode = false;

    static {
        System.setProperty("sun.java2d.opengl",     "true");
        System.setProperty("sun.java2d.d3d",        "true");
        System.setProperty("sun.java2d.noddraw",    "false");
        System.setProperty("sun.java2d.translaccel","true");
        System.setProperty("sun.java2d.videoaccel", "true");
    }

    // ── Security Configuration ────────────────────────────────────────────
    private static final int MIN_PASSWORD_LENGTH = 8;

    // ── ThreadLocal date formatters ───────────────────────────────────────
    private static final ThreadLocal<SimpleDateFormat> FMT_FULL =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));
    private static final ThreadLocal<SimpleDateFormat> FMT_TIME =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("HH:mm:ss"));
    private static final ThreadLocal<SimpleDateFormat> FMT_DATE =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));

    // ── Frame coalescing per client IP ────────────────────────────────────
    private static final class FrameUpdate {
        final String ip;
        final BufferedImage img;
        final int fps, frameCount;
        final long latencyMs;
        FrameUpdate(String ip, BufferedImage img, int fps,
                    int frameCount, long latencyMs) {
            this.ip = ip; this.img = img; this.fps = fps;
            this.frameCount = frameCount; this.latencyMs = latencyMs;
        }
    }
    // One pending update slot per client IP
    private final ConcurrentHashMap<String, AtomicReference<FrameUpdate>>
        pendingFrames = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean>
        frameUpdateScheduled = new ConcurrentHashMap<>();

    // ── Components ────────────────────────────────────────────────────────
    private JPanel pnlHeader, pnlRole, pnlControls, pnlMain, topPanel;
    private JPanel pnlScreenWrapper, pnlStatusBar, pnlClients, pnlLog;
    private JPanel clientsHeader, logHeader, pnlClientBtns;
    private JLabel lblTitle, lblStatusText, lblRoleHdr, lblModeHdr,
                   lblHint, clientsTitle, logTitle;
    private JLabel lblPortHdr, lblPassHdr, lblMaxHdr;
    private JLabel lblFps, lblResolution, lblLatency, lblFrameCount;
    private JTextField txtPort, txtMaxClients;
    private JPasswordField txtPassword;
    private RippleButton btnStart;
    private RippleButton btnShareFiles;
    private RoundButton btnStop;
    private SegmentedToggle roleToggle, themeToggle;
    private ScreenCanvas screenCanvas;
    private JScrollPane scrollScreen, scrollClients, scrollLog;

    private final KeyEventDispatcher remoteKeyDispatcher = e -> dispatchRemoteKeyWhenViewFocused(e);
    private JTable tblClients;
    private DefaultTableModel clientTableModel;
    private RoundToggleButton tglShowAll;
    private RoundButton btnDisconnect, btnDisconnectAll, btnClearLog;
    private JLabel lblConnectedCount;
    private JTextArea txtLog;
    private JSplitPane splitCenter, splitBottom;
    private PulsingDot statusDot;
    private Timer durationTimer;
    private JTabbedPane logTabs;

    private boolean clientsPanelCollapsed = false;
    private boolean logPanelCollapsed     = false;
    private CollapseButton btnCollapseClients;
    private CollapseButton btnCollapseLog;

    // ── Security Logging ──────────────────────────────────────────────────
    private PrintWriter securityLog;
    private JTextArea   txtSecurityLog;
    private JScrollPane scrollSecurityLog;

    // ── State ─────────────────────────────────────────────────────────────
    private RDPServer server;
    private boolean suppressTableEvents = false;
    private final Map<String, BufferedImage> currentFrames =
        new ConcurrentHashMap<>();
    private String  selectedIp = null;
    private boolean gridMode   = false;

    // LATENCY OPTIMIZATION: Throttle mouse-move events to ~60 Hz
    private long lastMouseSentAt = 0;

    private static final int COL_NAME     = 1;
    private static final int COL_IP       = 2;
    private static final int COL_DURATION = 4;
    private static final int COL_LIMIT    = 5;
    private static final int COL_CONTROL  = 7;
    private static final int HEADER_HEIGHT = 40;

    public ServerFrame() {
        initSecurityLog();
        initComponents();
        initServer();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(remoteKeyDispatcher);
        startDurationTimer();
        log("RDP Server ready. Configure settings and click Start.");
        logSecurity("APP_START", "localhost", "Server application started");
    }

    // ══════════════════════════════════════════════════════════════════════
    // ── SECURITY METHODS ──────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════

    private void initSecurityLog() {
        try {
            String logDir = System.getProperty("user.home") + "/RDPServerLogs/";
            File dir = new File(logDir);
            if (!dir.exists()) dir.mkdirs();
            String logFile = logDir + "security_" +
                FMT_DATE.get().format(new Date()) + ".log";
            securityLog = new PrintWriter(new FileWriter(logFile, true), true);
            System.out.println("Security log initialized: " + logFile);
        } catch (IOException e) {
            System.err.println("Failed to initialize security log: " + e.getMessage());
        }
    }

    public void logSecurity(String event, String ip, String details) {
        
        String timestamp = FMT_FULL.get().format(new Date());
        String logEntry  = String.format("[%s] [%s] IP=%s | %s",
            timestamp, event, ip, details);

        if (securityLog != null) securityLog.println(logEntry);

        if (txtSecurityLog != null) {
            final String entry = logEntry;
            SwingUtilities.invokeLater(() -> {
                txtSecurityLog.append(entry + "\n");
                txtSecurityLog.setCaretPosition(
                    txtSecurityLog.getDocument().getLength());
            });
        }
        log(getEventEmoji(event) + " " + event + ": " + details + " [" + ip + "]");
    }

    private String getEventEmoji(String event) {
        switch (event) {
            case "AUTH_SUCCESS":        return "✅";
            case "AUTH_FAIL":           return "❌";
            case "IP_BLOCKED":          return "🚫";
            case "CLIENT_CONNECTED":    return "✓";
            case "CLIENT_DISCONNECTED": return "👋";
            case "INVALID_INPUT":       return "⚠️";
            case "ATTACK":              return "🛡️";
            case "PERMISSION_CHANGE":   return "🔐";
            case "TIMEOUT":             return "⏱️";
            case "IDLE_TIMEOUT":        return "💤";
            case "TIME_LIMIT_REACHED":  return "⏰";
            case "SERVER_START":        return "🚀";
            case "SERVER_STOP":         return "🛑";
            default:                    return "ℹ️";
        }
    }

    private boolean isStrongPassword(String password) {
        if (password.length() < MIN_PASSWORD_LENGTH) return false;
        boolean hasUpper = false, hasLower = false,
                hasDigit = false, hasSpecial = false;
        for (char c : password.toCharArray()) {
            if      (Character.isUpperCase(c)) hasUpper  = true;
            else if (Character.isLowerCase(c)) hasLower  = true;
            else if (Character.isDigit(c))     hasDigit  = true;
            else if ("!@#$%^&*()_+-=[]{}|;:,.<>?/~`".indexOf(c) >= 0)
                                               hasSpecial = true;
        }
        return hasUpper && hasLower && hasDigit && hasSpecial;
    }

    // ══════════════════════════════════════════════════════════════════════
    // ── UI INITIALIZATION ─────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════

    private void initComponents() {
        setTitle("RDP Server");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setPreferredSize(new Dimension(1180, 900));
        setMinimumSize(new Dimension(900, 700));
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
                g2.setPaint(new GradientPaint(0, 0,
                    new Color(59, 130, 246, 90), 6, 0, new Color(0, 0, 0, 0)));
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

        lblTitle = new JLabel("RDP Server");
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

        lblStatusText = new JLabel("Stopped");
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

        roleToggle = new SegmentedToggle(
            new String[]{"Share My Screen", "Receive Screen"});
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

        themeToggle = new SegmentedToggle(
            new String[]{"Night Mode", "Light Mode"});
        themeToggle.setPreferredSize(new Dimension(180, 32));
        themeToggle.addChangeListener(
            e -> applyThemeToUI(themeToggle.getSelectedIndex() == 1));

        pnlRoleRight.add(lblModeHdr);
        pnlRoleRight.add(themeToggle);

        pnlRole.add(pnlRoleLeft, BorderLayout.WEST);
        pnlRole.add(pnlRoleRight, BorderLayout.EAST);

        // ── Controls Panel ────────────────────────────────────────────
        pnlControls = new JPanel(new GridBagLayout());
        pnlControls.setBackground(SURFACE);
        pnlControls.setBorder(new CompoundBorder(
            new MatteBorder(0, 0, 1, 0, BORDER_COLOR),
            new EmptyBorder(12, 16, 12, 16)));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(0, 0, 0, 12);

        lblPortHdr = makeFieldLabel("Port");
        lblPassHdr = makeFieldLabel(
            "Password (min 8 chars, mixed case, number, special)");
        lblMaxHdr  = makeFieldLabel("Max Clients");

        gbc.gridy = 0;
        gbc.gridx = 0; pnlControls.add(lblPortHdr, gbc);
        gbc.gridx = 2; pnlControls.add(lblPassHdr, gbc);
        gbc.gridx = 4; pnlControls.add(lblMaxHdr, gbc);

        gbc.gridy = 1;
        gbc.insets = new Insets(4, 0, 0, 12);

        txtPort = makeField("5900", 10);
        gbc.gridx = 0; pnlControls.add(txtPort, gbc);

        txtPassword = new JPasswordField(10);
        txtPassword.setUI(new javax.swing.plaf.basic.BasicPasswordFieldUI());
        styleField(txtPassword);
        gbc.gridx = 2; pnlControls.add(txtPassword, gbc);

        txtMaxClients = makeField("5", 6);
        gbc.gridx = 4; pnlControls.add(txtMaxClients, gbc);

        gbc.gridx = 5; gbc.weightx = 1.0;
        gbc.insets = new Insets(4, 0, 0, 12);
        pnlControls.add(Box.createHorizontalGlue(), gbc);

        gbc.weightx = 0;
        gbc.insets = new Insets(4, 4, 0, 0);

        // ── Share Files button — LEFT of Start ────────────────────────────────
        btnShareFiles = new RippleButton("Share Files", ACCENT_PURPLE);
        btnShareFiles.setPreferredSize(new Dimension(130, 38));
        btnShareFiles.setEnabled(false);          // enabled only when server running
        btnShareFiles.addActionListener(e -> onShareFiles());
        gbc.gridx = 6;
        pnlControls.add(btnShareFiles, gbc);

        // ── Start button ──────────────────────────────────────────────────────
        btnStart = new RippleButton("Start", ACCENT_GREEN);
        btnStart.setPreferredSize(new Dimension(148, 38));
        btnStart.addActionListener(e -> onStartServer());
        gbc.gridx = 7;
        pnlControls.add(btnStart, gbc);

        // ── Stop button ───────────────────────────────────────────────────────
        btnStop = new RoundButton("Stop Server", new Color(70,18,18), ACCENT_RED);
        btnStop.setPreferredSize(new Dimension(158, 38));
        btnStop.setEnabled(false);
        btnStop.addActionListener(e -> onStopServer());
        gbc.gridx = 8;
        pnlControls.add(btnStop, gbc);

        // ── Screen Canvas ─────────────────────────────────────────────
        pnlScreenWrapper = new JPanel(new BorderLayout());
        pnlScreenWrapper.setBackground(BG_DARK);
        pnlScreenWrapper.setBorder(new EmptyBorder(0, 0, 0, 0));

        screenCanvas = new ScreenCanvas();
        screenCanvas.setFocusable(true);
        screenCanvas.setRequestFocusEnabled(true);
        screenCanvas.setPreferredSize(new Dimension(1000, 600));

        MouseAdapter mouseAdapter = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e)   { handleMouseInput("MOVE",    e); }
            @Override public void mouseDragged(MouseEvent e) { handleMouseInput("MOVE",    e); }
            @Override public void mousePressed(MouseEvent e) {
                screenCanvas.requestFocusInWindow();
                handleMouseInput("PRESS",   e);
            }
            @Override public void mouseReleased(MouseEvent e){ handleMouseInput("RELEASE", e); }
            @Override public void mouseWheelMoved(MouseWheelEvent e) { handleMouseWheel(e); }
        };
        screenCanvas.addMouseListener(mouseAdapter);
        screenCanvas.addMouseMotionListener(mouseAdapter);
        screenCanvas.addMouseWheelListener(mouseAdapter);

        scrollScreen = new JScrollPane(screenCanvas);
        scrollScreen.setBorder(null);
        scrollScreen.getViewport().setBackground(new Color(6, 8, 14));
        scrollScreen.getVerticalScrollBar().setUI(new DarkScrollBarUI());
        scrollScreen.getHorizontalScrollBar().setUI(new DarkScrollBarUI());

        // Status bar
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

        pnlScreenWrapper.add(scrollScreen,   BorderLayout.CENTER);
        pnlScreenWrapper.add(pnlStatusBar,   BorderLayout.SOUTH);
        pnlScreenWrapper.setVisible(false);

        // ── Clients Panel ─────────────────────────────────────────────
        pnlClients = new JPanel(new BorderLayout(0, 4));
        pnlClients.setBackground(SURFACE);
        pnlClients.setBorder(new MatteBorder(1, 0, 0, 0, BORDER_COLOR));

        clientsHeader = new JPanel(new BorderLayout());
        clientsHeader.setBackground(SURFACE);
        clientsHeader.setBorder(new EmptyBorder(7, 14, 7, 10));

        clientsTitle = new JLabel("Connected Clients");
        clientsTitle.setFont(new Font("Segoe UI", Font.BOLD, 12));
        clientsTitle.setForeground(TEXT_PRIMARY);

        lblConnectedCount = new JLabel("0 connected");
        lblConnectedCount.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        lblConnectedCount.setForeground(TEXT_MUTED);

        btnCollapseClients = new CollapseButton(false);
        btnCollapseClients.setPreferredSize(new Dimension(24, 24));
        btnCollapseClients.addActionListener(e -> toggleClientsPanel());

        JPanel clientHeaderLeft =
            new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        clientHeaderLeft.setOpaque(false);
        clientHeaderLeft.add(btnCollapseClients);
        clientHeaderLeft.add(clientsTitle);
        clientHeaderLeft.add(makeStatSep());
        clientHeaderLeft.add(lblConnectedCount);
        clientsHeader.add(clientHeaderLeft, BorderLayout.WEST);

        pnlClientBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        pnlClientBtns.setOpaque(false);

        Color babyBlue      = new Color(137, 207, 240);
        Color babyBlueHover = new Color(173, 216, 246);
        Color babyBlueSel   = new Color(100, 180, 220);

        tglShowAll = new RoundToggleButton(
            "Grid View", babyBlue, babyBlueHover, babyBlueSel);
        tglShowAll.setPreferredSize(new Dimension(100, 28));
        tglShowAll.setEnabled(false);

        btnDisconnect = new RoundButton(
            "Disconnect", ACCENT_RED, new Color(180, 50, 50));
        btnDisconnect.setPreferredSize(new Dimension(110, 28));
        btnDisconnect.setEnabled(false);

        btnDisconnectAll = new RoundButton(
            "Disconnect All", ACCENT_RED, new Color(180, 50, 50));
        btnDisconnectAll.setPreferredSize(new Dimension(130, 28));
        btnDisconnectAll.setEnabled(false);

        tglShowAll.addActionListener(e -> {
            gridMode = tglShowAll.isSelected();
            if (gridMode) {
                tblClients.clearSelection();
                selectedIp = null;
                log("Grid View Enabled.");
            }
            screenCanvas.updateState(currentFrames, selectedIp, gridMode);
        });
        btnDisconnect.addActionListener(e -> onDisconnectSelected());
        btnDisconnectAll.addActionListener(e -> onDisconnectAll());

        pnlClientBtns.add(tglShowAll);
        pnlClientBtns.add(btnDisconnect);
        pnlClientBtns.add(btnDisconnectAll);
        clientsHeader.add(pnlClientBtns, BorderLayout.EAST);

        // Table
        clientTableModel = new DefaultTableModel(
            new Object[]{"#", "Name", "Client IP", "Connected At",
                         "Duration", "Limit (min)", "Resolution", "Control"}, 0
        ) {
            @Override public Class<?> getColumnClass(int col) {
                if (col == COL_CONTROL) return Boolean.class;
                if (col == COL_LIMIT)   return Integer.class;
                return String.class;
            }
            @Override public boolean isCellEditable(int row, int col) {
                return col == COL_CONTROL || col == COL_LIMIT;
            }
        };

        tblClients = new JTable(clientTableModel) {
            @Override public Component prepareRenderer(
                    TableCellRenderer renderer, int row, int col) {
                Component c = super.prepareRenderer(renderer, row, col);
                if (!isRowSelected(row)) {
                    c.setBackground(row % 2 == 0 ? SURFACE : SURFACE_ALT);
                    c.setForeground(TEXT_PRIMARY);
                } else {
                    c.setBackground(new Color(59, 130, 246, 80));
                    c.setForeground(TEXT_PRIMARY);
                }
                return c;
            }
        };
        tblClients.setRowHeight(32);
        tblClients.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        tblClients.setBackground(SURFACE);
        tblClients.setForeground(TEXT_PRIMARY);
        tblClients.setGridColor(BORDER_COLOR);
        tblClients.setShowVerticalLines(false);
        tblClients.setShowHorizontalLines(true);
        tblClients.setFillsViewportHeight(true);
        tblClients.setIntercellSpacing(new Dimension(0, 1));
        tblClients.setSelectionBackground(new Color(59, 130, 246, 80));
        tblClients.setSelectionForeground(TEXT_PRIMARY);
        tblClients.setFocusable(false);

        JTableHeader tableHeader = tblClients.getTableHeader();
        tableHeader.setReorderingAllowed(false);

        DefaultTableCellRenderer headerRenderer = new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected,
                    boolean hasFocus, int row, int column) {
                JLabel label = (JLabel) super.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);
                label.setBackground(BG_DARK);
                label.setForeground(isLightMode ? TEXT_PRIMARY : Color.WHITE);
                label.setFont(new Font("Segoe UI", Font.BOLD, 12));
                label.setHorizontalAlignment(SwingConstants.LEFT);
                label.setBorder(new CompoundBorder(
                    new MatteBorder(0, 0, 1, 0, BORDER_COLOR),
                    new EmptyBorder(4, 8, 4, 8)));
                return label;
            }
        };
        for (int i = 0; i < tblClients.getColumnModel().getColumnCount(); i++)
            tblClients.getColumnModel().getColumn(i)
                .setHeaderRenderer(headerRenderer);

        tblClients.getColumnModel().getColumn(COL_CONTROL)
            .setCellRenderer(new ControlCellRenderer());
        tblClients.getColumnModel().getColumn(COL_CONTROL)
            .setCellEditor(new ControlCellEditor());

        tblClients.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()
                    && tblClients.getSelectedRow() != -1) {
                int row = tblClients.getSelectedRow();
                selectedIp = (String) clientTableModel.getValueAt(row, COL_IP);
                gridMode = false;
                tglShowAll.setSelected(false);
                screenCanvas.updateState(currentFrames, selectedIp, gridMode);
                log("Viewing: " + selectedIp);
            }
        });

        clientTableModel.addTableModelListener(e -> {
            if (suppressTableEvents
                    || e.getType() != TableModelEvent.UPDATE) return;
            int row = e.getFirstRow(), col = e.getColumn();
            if (row < 0 || row >= clientTableModel.getRowCount()) return;
            String ip = (String) clientTableModel.getValueAt(row, COL_IP);
            if (col == COL_CONTROL) {
                Boolean canControl =
                    (Boolean) clientTableModel.getValueAt(row, COL_CONTROL);
                if (canControl != null) {
                    server.setClientControlPermission(ip, canControl);
                    log("Client " + ip
                        + (canControl ? " granted" : " revoked") + " control.");
                    if (Boolean.TRUE.equals(canControl) && ip.equals(selectedIp)) {
                        SwingUtilities.invokeLater(() ->
                            screenCanvas.requestFocusInWindow());
                    }
                }
            }
            if (col == COL_LIMIT) {
                int newLimit;
                try {
                    newLimit = Math.max(0, Integer.parseInt(
                        clientTableModel.getValueAt(row, COL_LIMIT).toString()));
                } catch (Exception ex) { newLimit = 0; }
                suppressTableEvents = true;
                clientTableModel.setValueAt(newLimit, row, COL_LIMIT);
                suppressTableEvents = false;
                server.setClientTimeLimit(ip, newLimit);
            }
        });

        scrollClients = new JScrollPane(tblClients);
        scrollClients.setBorder(null);
        scrollClients.setBackground(SURFACE);
        scrollClients.getViewport().setBackground(SURFACE);
        scrollClients.getVerticalScrollBar().setUI(new DarkScrollBarUI());
        scrollClients.getHorizontalScrollBar().setUI(new DarkScrollBarUI());

        pnlClients.add(clientsHeader, BorderLayout.NORTH);
        pnlClients.add(scrollClients, BorderLayout.CENTER);

        // ── Log Panel ─────────────────────────────────────────────────
        pnlLog = new JPanel(new BorderLayout(0, 0));
        pnlLog.setBackground(new Color(8, 10, 18));
        pnlLog.setBorder(new MatteBorder(1, 0, 0, 0, BORDER_COLOR));

        logHeader = new JPanel(new BorderLayout());
        logHeader.setBackground(SURFACE);
        logHeader.setBorder(new EmptyBorder(6, 14, 6, 10));

        logTabs = new JTabbedPane();
        logTabs.setBackground(new Color(8, 10, 18));
        logTabs.setForeground(TEXT_PRIMARY);

        logTitle = new JLabel("Server Log");
        logTitle.setFont(new Font("Segoe UI", Font.BOLD, 12));
        logTitle.setForeground(TEXT_PRIMARY);

        btnCollapseLog = new CollapseButton(false);
        btnCollapseLog.setPreferredSize(new Dimension(24, 24));
        btnCollapseLog.addActionListener(e -> toggleLogPanel());

        JPanel logHeaderLeft =
            new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        logHeaderLeft.setOpaque(false);
        logHeaderLeft.add(btnCollapseLog);
        logHeaderLeft.add(logTitle);

        btnClearLog = new RoundButton(
            "Clear", new Color(51, 65, 85), new Color(71, 85, 105));
        btnClearLog.setPreferredSize(new Dimension(80, 26));
        btnClearLog.addActionListener(e -> {
            txtLog.setText("");
            if (txtSecurityLog != null) txtSecurityLog.setText("");
        });

        logHeader.add(logHeaderLeft, BorderLayout.WEST);
        logHeader.add(btnClearLog,   BorderLayout.EAST);

        txtLog = new JTextArea();
        txtLog.setEditable(false);
        txtLog.setFont(new Font("Consolas", Font.PLAIN, 11));
        txtLog.setBackground(new Color(8, 10, 18));
        txtLog.setForeground(new Color(130, 220, 160));
        txtLog.setCaretColor(Color.WHITE);
        txtLog.setBorder(new EmptyBorder(6, 12, 6, 12));
        txtLog.setLineWrap(true);

        scrollLog = new JScrollPane(txtLog);
        scrollLog.setBorder(null);
        scrollLog.getVerticalScrollBar().setUI(new DarkScrollBarUI());

        txtSecurityLog = new JTextArea();
        txtSecurityLog.setEditable(false);
        txtSecurityLog.setFont(new Font("Consolas", Font.PLAIN, 11));
        txtSecurityLog.setBackground(new Color(8, 10, 18));
        txtSecurityLog.setForeground(new Color(255, 200, 100));
        txtSecurityLog.setCaretColor(Color.WHITE);
        txtSecurityLog.setBorder(new EmptyBorder(6, 12, 6, 12));
        txtSecurityLog.setLineWrap(true);

        scrollSecurityLog = new JScrollPane(txtSecurityLog);
        scrollSecurityLog.setBorder(null);
        scrollSecurityLog.getVerticalScrollBar().setUI(new DarkScrollBarUI());

        logTabs.addTab("General",  scrollLog);
        logTabs.addTab("Security", scrollSecurityLog);

        pnlLog.add(logHeader, BorderLayout.NORTH);
        pnlLog.add(logTabs,   BorderLayout.CENTER);

        // ── Split Panes ───────────────────────────────────────────────
        splitBottom = new JSplitPane(
            JSplitPane.VERTICAL_SPLIT, pnlClients, pnlLog);
        splitBottom.setResizeWeight(0.55);
        splitBottom.setContinuousLayout(true);
        splitBottom.setOneTouchExpandable(false);
        splitBottom.setBorder(null);
        splitBottom.setDividerSize(0);

        splitCenter = new JSplitPane(
            JSplitPane.VERTICAL_SPLIT, pnlScreenWrapper, splitBottom);
        splitCenter.setResizeWeight(0.65);
        splitCenter.setContinuousLayout(true);
        splitCenter.setOneTouchExpandable(false);
        splitCenter.setBorder(null);
        splitCenter.setDividerSize(10);
        splitCenter.setUI(new ModernSplitPaneUI());

        // ── Main Container ────────────────────────────────────────────
        pnlMain = new JPanel(new BorderLayout());
        pnlMain.setBackground(BG_DARK);

        topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.setBackground(BG_DARK);
        topPanel.add(pnlHeader);
        topPanel.add(pnlRole);
        topPanel.add(pnlControls);

        pnlMain.add(topPanel,    BorderLayout.NORTH);
        pnlMain.add(splitCenter, BorderLayout.CENTER);

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
        topPanel.setBackground(BG_DARK);

        lblTitle.setForeground(TEXT_PRIMARY);
        lblRoleHdr.setForeground(TEXT_MUTED);
        if (lblModeHdr != null) lblModeHdr.setForeground(TEXT_MUTED);
        lblHint.setForeground(TEXT_MUTED);

        pnlRole.setBackground(isLight
            ? new Color(241, 245, 249) : new Color(14, 19, 32));
        pnlControls.setBackground(SURFACE);

        lblPortHdr.setForeground(TEXT_MUTED);
        lblPassHdr.setForeground(TEXT_MUTED);
        lblMaxHdr.setForeground(TEXT_MUTED);

        updateFieldStyle(txtPort);
        updateFieldStyle(txtPassword);
        updateFieldStyle(txtMaxClients);

        pnlScreenWrapper.setBackground(BG_DARK);
        scrollScreen.getViewport().setBackground(
            isLight ? new Color(226, 232, 240) : new Color(6, 8, 14));

        pnlClients.setBackground(SURFACE);
        clientsHeader.setBackground(SURFACE);
        clientsTitle.setForeground(TEXT_PRIMARY);
        lblConnectedCount.setForeground(TEXT_MUTED);

        tblClients.setBackground(SURFACE);
        tblClients.setForeground(TEXT_PRIMARY);
        tblClients.setGridColor(BORDER_COLOR);
        scrollClients.setBackground(SURFACE);
        scrollClients.getViewport().setBackground(SURFACE);
        tblClients.getTableHeader().repaint();
        ((ControlCellEditor) tblClients.getColumnModel()
            .getColumn(COL_CONTROL).getCellEditor()).updateTheme();

        Color logBg     = isLight ? new Color(248, 250, 252) : new Color(8, 10, 18);
        Color logFg     = isLight ? new Color(15, 118, 110)  : new Color(130, 220, 160);
        Color logSecFg  = isLight ? new Color(180, 83, 9)    : new Color(255, 200, 100);

        pnlLog.setBackground(logBg);
        logHeader.setBackground(SURFACE);
        logTabs.setBackground(logBg);
        logTabs.setForeground(TEXT_PRIMARY);
        logTitle.setForeground(TEXT_PRIMARY);

        txtLog.setBackground(logBg);
        txtLog.setForeground(logFg);
        txtLog.setCaretColor(isLight ? Color.BLACK : Color.WHITE);

        txtSecurityLog.setBackground(logBg);
        txtSecurityLog.setForeground(logSecFg);
        txtSecurityLog.setCaretColor(isLight ? Color.BLACK : Color.WHITE);

        screenCanvas.setLightMode(isLight);
        repaint();
    }

    private void updateFieldStyle(JTextField f) {
        f.setBackground(SURFACE_ALT);
        f.setForeground(TEXT_PRIMARY);
        f.setCaretColor(ACCENT_BLUE);
        f.setBorder(new CompoundBorder(
            new RoundedBorder(8, BORDER_COLOR),
            new EmptyBorder(7, 11, 7, 11)));
    }

    // ── Role UI ───────────────────────────────────────────────────────────
    private void updateRoleUI() {
        boolean isReceive = roleToggle.getSelectedIndex() == 1;
        pnlScreenWrapper.setVisible(isReceive);
        if (isReceive) {
            splitCenter.setDividerLocation(0.68);
            resetCanvasCompletely();
        }
    }

    // ── Server Actions ────────────────────────────────────────────────────
    private void onStartServer() {
        String password = new String(txtPassword.getPassword());
        if (password.isEmpty()) {
            showToast("Please enter a Server Password before starting.", true);
            logSecurity("START_FAILED", "localhost",
                "Attempt to start without password");
            return;
        }
        if (!isStrongPassword(password)) {
            showToast("Password must be at least " + MIN_PASSWORD_LENGTH
                + " characters with uppercase, lowercase, number, "
                + "and special character.", true);
            logSecurity("START_FAILED", "localhost", "Weak password rejected");
            return;
        }

        int port;
        try {
            port = Integer.parseInt(txtPort.getText().trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            showToast("Invalid port number.", true); return;
        }

        int maxClients;
        try {
            maxClients = Integer.parseInt(txtMaxClients.getText().trim());
            if (maxClients < 1 || maxClients > 20)
                throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            showToast("Invalid Max Clients value (1-20).", true); return;
        }

        String role = roleToggle.getSelectedIndex() == 0 ? "SHARE" : "RECEIVE";
        server.setServerRole(role);
        server.configure(port, password, maxClients, true, false);

        btnStart.startSpinner();
        final int   fPort   = port;
        final int   fMax    = maxClients;
        final String fRole  = role;

        Thread startThread = new Thread(() -> {
            boolean started = server.start();
            SwingUtilities.invokeLater(() -> {
                btnStart.stopSpinner();
                if (started) {
                    setServerRunning(true);
                    log("Server started on port " + fPort + " [" + fRole + "]");
                    logSecurity("SERVER_START", "localhost",
                        "Server started: port=" + fPort
                        + " role=" + fRole + " maxClients=" + fMax);
                    splitCenter.setDividerLocation(
                        fRole.equals("RECEIVE") ? 0.68 : 0.0);
                    splitBottom.setDividerLocation(0.55);
                    showToast("Server started successfully", false);
                } else {
                    log("ERROR: Could not start server on port " + fPort);
                    logSecurity("SERVER_ERROR", "localhost",
                        "Failed to start server on port " + fPort);
                    showToast("Failed to start server", true);
                }
            });
        }, "rdp-server-start");
        startThread.setDaemon(true);
        startThread.start();
    }

    private void onStopServer() {
        Thread stopThread = new Thread(() -> {
            server.stop();
            SwingUtilities.invokeLater(() -> {
                setServerRunning(false);
                clearClientTable();
                resetCanvasCompletely();
                log("Server stopped.");
                showToast("Server stopped", false);
            });
        }, "rdp-server-stop");
        stopThread.setDaemon(true);
        stopThread.start();
    }
    
    // ── File Sharing Action ────────────────────────────────────────────────
    private void onShareFiles() {
        FileTransferServer fts = server.getFileTransferServer();
        if (fts == null) {
            showToast("File transfer server not running.", true);
            return;
        }

        // Open multi-select file chooser on the EDT
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Files to Share with Clients");
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

        
        fts.queueFilesToSend(files);
        server.notifyClientsOfFiles();
        log("📁 " + files.size() + " file(s) queued for clients on port "
            + fts.getFilePort());
        showToast(files.size() + " file(s) ready for clients", false);
    }

    private void onDisconnectSelected() {
        int row = tblClients.getSelectedRow();
        if (row < 0) return;
        String ip   = (String) clientTableModel.getValueAt(row, COL_IP);
        String name = (String) clientTableModel.getValueAt(row, COL_NAME);
        currentFrames.clear();
        screenCanvas.clearFrames();
        resetStatusBar();
        Thread t = new Thread(() -> server.disconnectClient(ip),
            "rdp-disconnect-" + ip);
        t.setDaemon(true); t.start();
        log("Disconnecting: " + name + " (" + ip + ")");
        logSecurity("MANUAL_DISCONNECT", ip,
            "Admin disconnected client '" + name + "'");
    }

    private void onDisconnectAll() {
        int count = clientTableModel.getRowCount();
        Thread t = new Thread(() -> {
            server.disconnectAllClients();
            currentFrames.clear();
            SwingUtilities.invokeLater(() -> {
                screenCanvas.clearFrames();
                resetStatusBar();
                clearClientTable();
                resetCanvasCompletely();
                log("All clients disconnected.");
                logSecurity("DISCONNECT_ALL", "server",
                    count + " client(s) disconnected by admin");
            });
        }, "rdp-disconnect-all");
        t.setDaemon(true); t.start();
    }

    private void onClose() {
        logSecurity("APP_STOP", "localhost", "Server application closing");
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(remoteKeyDispatcher);
        server.stop();
        if (securityLog != null) securityLog.close();
        dispose();
        System.exit(0);
    }

    // ── Collapse / Expand ─────────────────────────────────────────────────
    private void toggleClientsPanel() {
        clientsPanelCollapsed = !clientsPanelCollapsed;
        btnCollapseClients.setCollapsed(clientsPanelCollapsed);
        scrollClients.setVisible(!clientsPanelCollapsed);
        if (pnlClientBtns != null)
            pnlClientBtns.setVisible(!clientsPanelCollapsed);

        if (clientsPanelCollapsed) {
            int hh = clientsHeader.getPreferredSize().height;
            if (hh <= 0) hh = HEADER_HEIGHT;
            splitBottom.setDividerLocation(hh);
        } else {
            splitBottom.setDividerLocation(0.55);
        }
        pnlClients.revalidate(); pnlClients.repaint();
        splitBottom.revalidate();
    }

    private void toggleLogPanel() {
        logPanelCollapsed = !logPanelCollapsed;
        btnCollapseLog.setCollapsed(logPanelCollapsed);
        if (logTabs   != null) logTabs.setVisible(!logPanelCollapsed);
        if (btnClearLog != null) btnClearLog.setVisible(!logPanelCollapsed);

        if (logPanelCollapsed) {
            int hh = logHeader.getPreferredSize().height;
            splitBottom.setDividerLocation(
                splitBottom.getHeight()
                - splitBottom.getDividerSize() - hh);
        } else {
            splitBottom.setDividerLocation(0.55);
        }
        pnlLog.revalidate(); pnlLog.repaint();
        splitBottom.revalidate();
    }

    // ── Public API ────────────────────────────────────────────────────────

    public void log(String message) {
        
        final String ts  = FMT_TIME.get().format(new Date());
        final String line = "[" + ts + "] " + message + "\n";
        SwingUtilities.invokeLater(() -> {
            txtLog.append(line);
            txtLog.setCaretPosition(txtLog.getDocument().getLength());
        });
    }

    public void addClient(String name, String ip, String resolution) {
        SwingUtilities.invokeLater(() -> {
            int row = clientTableModel.getRowCount() + 1;
            String time = FMT_TIME.get().format(new Date());
            clientTableModel.addRow(new Object[]{
                row, name, ip, time, "00:00:00", 0, resolution, Boolean.FALSE});
            updateClientCount();
            btnDisconnect.setEnabled(true);
            btnDisconnectAll.setEnabled(true);
            tglShowAll.setEnabled(true);
        });
    }

    public void removeClient(String ip) {
        SwingUtilities.invokeLater(() -> {
            suppressTableEvents = true;
            for (int i = 0; i < clientTableModel.getRowCount(); i++) {
                if (ip.equals(clientTableModel.getValueAt(i, COL_IP))) {
                    clientTableModel.removeRow(i);
                    break;
                }
            }
            suppressTableEvents = false;
            renumberRows();
            updateClientCount();

            currentFrames.remove(ip);
            pendingFrames.remove(ip);
            frameUpdateScheduled.remove(ip);
            if (ip.equals(selectedIp)) selectedIp = null;

            if (clientTableModel.getRowCount() == 0) {
                btnDisconnect.setEnabled(false);
                btnDisconnectAll.setEnabled(false);
                tglShowAll.setEnabled(false);
                resetCanvasCompletely();
            } else {
                screenCanvas.updateState(currentFrames, selectedIp, gridMode);
                if (selectedIp == null) resetStatusBar();
            }
        });
    }


    public void displayReceivedFrame(String ip, BufferedImage img,
                                     int fps, int frameCount, long latencyMs) {
        currentFrames.put(ip, img);
        if (selectedIp == null && !gridMode) selectedIp = ip;

        pendingFrames.computeIfAbsent(ip, k -> new AtomicReference<>());
        frameUpdateScheduled.putIfAbsent(ip, Boolean.FALSE);

        pendingFrames.get(ip).set(
            new FrameUpdate(ip, img, fps, frameCount, latencyMs));

        if (!Boolean.TRUE.equals(frameUpdateScheduled.get(ip))) {
            frameUpdateScheduled.put(ip, Boolean.TRUE);
            SwingUtilities.invokeLater(() -> flushFrameUpdate(ip));
        }
    }

    private void flushFrameUpdate(String ip) {
        frameUpdateScheduled.put(ip, Boolean.FALSE);
        AtomicReference<FrameUpdate> ref = pendingFrames.get(ip);
        if (ref == null) return;
        FrameUpdate upd = ref.getAndSet(null);
        if (upd == null) return;

        screenCanvas.updateState(currentFrames, selectedIp, gridMode);
        screenCanvas.repaint();
        if (ip.equals(selectedIp) && !gridMode) {
            lblFps.setText("FPS " + upd.fps);
            lblResolution.setText(
                upd.img.getWidth() + " × " + upd.img.getHeight());
            lblLatency.setText(upd.latencyMs + " ms");
            lblFrameCount.setText("Frames " + upd.frameCount);
        }
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
                    ? new Color(50, 15, 15, 230)
                    : new Color(15, 30, 20,  230));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                g2.setColor(error ? ACCENT_RED : ACCENT_GREEN);
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 12, 12);
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
            toast.setLocation(
                loc.x + getWidth()  - toast.getWidth()  - 20,
                loc.y + getHeight() - toast.getHeight() - 20);
        } catch (IllegalComponentStateException ex) {
            toast.dispose(); return;
        }

        toast.setOpacity(0f);
        toast.setVisible(true);

        final float[] a      = {0f};
        final boolean[] fading = {false};
        final int[]   hold   = {0};
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
    private void resetCanvasCompletely() {
        currentFrames.clear();
        selectedIp = null;
        gridMode   = false;
        if (tglShowAll != null) tglShowAll.setSelected(false);
        screenCanvas.clearFrames();
        resetStatusBar();
    }

    private boolean dispatchRemoteKeyWhenViewFocused(KeyEvent e) {
        if (server == null || !"RECEIVE".equals(server.getServerRole()))
            return false;
        if (gridMode || selectedIp == null)
            return false;
        if (!server.isClientControlAllowed(selectedIp))
            return false;
        int id = e.getID();
        if (id != KeyEvent.KEY_PRESSED && id != KeyEvent.KEY_RELEASED)
            return false;
        if (scrollScreen == null || !scrollScreen.isShowing())
            return false;

        Component fo = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (fo == null || !SwingUtilities.isDescendingFrom(fo, scrollScreen))
            return false;

        if (id == KeyEvent.KEY_PRESSED)
            server.sendKeyToClient(selectedIp, "PRESS", e.getKeyCode());
        else
            server.sendKeyToClient(selectedIp, "RELEASE", e.getKeyCode());
        return true;
    }

    private void handleMouseWheel(MouseWheelEvent e) {
        if (server == null || !"RECEIVE".equals(server.getServerRole())) return;
        if (gridMode || selectedIp == null) return;
        if (!server.isClientControlAllowed(selectedIp)) return;
        BufferedImage img = currentFrames.get(selectedIp);
        if (img == null) return;
        int remoteW = img.getWidth(),  remoteH = img.getHeight();
        int canvasW = screenCanvas.getWidth(), canvasH = screenCanvas.getHeight();
        if (canvasW <= 0 || canvasH <= 0) return;
        int x = Math.max(0, Math.min(remoteW - 1,
            (int)(((double) e.getX() / canvasW) * remoteW)));
        int y = Math.max(0, Math.min(remoteH - 1,
            (int)(((double) e.getY() / canvasH) * remoteH)));
        int rot = e.getWheelRotation();
        server.sendMouseToClient(selectedIp, "WHEEL", x, y, rot);
    }

    private void handleMouseInput(String action, MouseEvent e) {
        if (server == null || !"RECEIVE".equals(server.getServerRole())) return;
        if (gridMode || selectedIp == null) return;
        if (!server.isClientControlAllowed(selectedIp)) return;

        if ("MOVE".equals(action)) {
            long now = System.currentTimeMillis();
            if (now - lastMouseSentAt < 16) return;
            lastMouseSentAt = now;
        }

        BufferedImage img = currentFrames.get(selectedIp);
        if (img == null) return;
        int remoteW = img.getWidth(),  remoteH = img.getHeight();
        int canvasW = screenCanvas.getWidth(), canvasH = screenCanvas.getHeight();
        if (canvasW <= 0 || canvasH <= 0) return;
        int x = Math.max(0, Math.min(remoteW - 1,
            (int)(((double) e.getX() / canvasW) * remoteW)));
        int y = Math.max(0, Math.min(remoteH - 1,
            (int)(((double) e.getY() / canvasH) * remoteH)));
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
        server.sendMouseToClient(selectedIp, action, x, y, btn);
    }

    private void setServerRunning(boolean running) {
        btnStart.setEnabled(!running);
        btnStop.setEnabled(running);
        btnShareFiles.setEnabled(running);
        txtPort.setEnabled(!running);
        txtPassword.setEnabled(!running);
        txtMaxClients.setEnabled(!running);
        roleToggle.setEnabled(!running);
        themeToggle.setEnabled(!running);

        if (running) {
            statusDot.setColor(ACCENT_GREEN);
            statusDot.setActive(true);
            lblStatusText.setForeground(ACCENT_GREEN);
            lblStatusText.setText("Running · port "
                + txtPort.getText().trim() + "["
                + (roleToggle.getSelectedIndex() == 0
                    ? "Sharing" : "Receiving") + "]");
        } else {
            statusDot.setColor(TEXT_MUTED);
            statusDot.setActive(false);
            lblStatusText.setForeground(TEXT_MUTED);
            lblStatusText.setText("Stopped");
        }
    }

    private void startDurationTimer() {
        durationTimer = new Timer(1000, e -> {
            if (server == null || clientTableModel.getRowCount() == 0) return;
            suppressTableEvents = true;
            for (int i = 0; i < clientTableModel.getRowCount(); i++) {
                String ip = (String) clientTableModel.getValueAt(i, COL_IP);
                long secs = server.getClientDuration(ip);
                if (secs >= 0) {
                    clientTableModel.setValueAt(
                        String.format("%02d:%02d:%02d",
                            secs / 3600, (secs % 3600) / 60, secs % 60),
                        i, COL_DURATION);
                }
            }
            suppressTableEvents = false;
        });
        durationTimer.start();
    }

    private void clearClientTable() {
        suppressTableEvents = true;
        clientTableModel.setRowCount(0);
        suppressTableEvents = false;
        updateClientCount();
        btnDisconnect.setEnabled(false);
        btnDisconnectAll.setEnabled(false);
        tglShowAll.setEnabled(false);
    }

    private void renumberRows() {
        suppressTableEvents = true;
        for (int i = 0; i < clientTableModel.getRowCount(); i++)
            clientTableModel.setValueAt(i + 1, i, 0);
        suppressTableEvents = false;
    }

    private void updateClientCount() {
        int n = clientTableModel.getRowCount();
        lblConnectedCount.setText(
            n + " client" + (n == 1 ? "" : "s") + " connected");
    }

    private void resetStatusBar() {
        lblFps.setText("FPS —");
        lblResolution.setText("Resolution —");
        lblLatency.setText("Latency —");
        lblFrameCount.setText("Frames 0");
    }

    private void initServer() {
        server = new RDPServer(this);
    }

    // ── UI Factory Methods ────────────────────────────────────────────────
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
                    new RoundedBorder(8, BORDER_FOCUS),
                    new EmptyBorder(7, 11, 7, 11)));
            }
            public void focusLost(FocusEvent e) {
                f.setBorder(new CompoundBorder(
                    new RoundedBorder(8, BORDER_COLOR),
                    new EmptyBorder(7, 11, 7, 11)));
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
    // ── Inner Components ──────────────────────────────────════════════════
    // ══════════════════════════════════════════════════════════════════════


    static class PulsingDot extends JComponent {
        private volatile Color color;
        private boolean active = false;
        private float pulse = 0f, pulseDir = 0.04f;
        private Color lastCachedColor = null;

        PulsingDot(Color c) {
            this.color = c;
            setPreferredSize(new Dimension(10, 10));
            new Timer(40, e -> {
                if (active) {
                    pulse += pulseDir;
                    if (pulse > 1 || pulse < 0) pulseDir = -pulseDir;
                } else pulse = 0;
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
                int r  = (int)(pulse * 4);
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

    static class CollapseButton extends JButton {
        private boolean collapsed = false;
        private float rotation = 0f;
        private final Timer animator;

        private static final BasicStroke CHEVRON_STROKE =
            new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        private static final Path2D.Float CHEVRON_PATH = new Path2D.Float();
        static {
            CHEVRON_PATH.moveTo(-5, -2.5f);
            CHEVRON_PATH.lineTo( 0,  5);
            CHEVRON_PATH.lineTo( 5, -2.5f);
        }

        public CollapseButton(boolean initialCollapsed) {
            this.collapsed = initialCollapsed;
            this.rotation  = initialCollapsed ? 180f : 0f;

            setOpaque(false); setContentAreaFilled(false);
            setBorderPainted(false); setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Collapse/Expand");

            animator = new Timer(16, e -> {
                float target = collapsed ? 180f : 0f;
                float diff   = target - rotation;
                if (Math.abs(diff) > 0.5f) {
                    rotation += diff * 0.25f; repaint();
                } else {
                    rotation = target;
                    ((Timer) e.getSource()).stop(); repaint();
                }
            });

            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { repaint(); }
                @Override public void mouseExited(MouseEvent e)  { repaint(); }
                @Override public void mousePressed(MouseEvent e) { repaint(); }
            });
        }

        public void setCollapsed(boolean collapsed) {
            if (this.collapsed != collapsed) {
                this.collapsed = collapsed;
                animator.start();
            }
        }

        public boolean isCollapsed() { return collapsed; }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            ButtonModel model = getModel();
            if (model.isRollover()) {
                g2.setColor(new Color(59, 130, 246, 30));
                g2.fillRoundRect(0, 0, w, h, 6, 6);
            }
            if (model.isPressed()) {
                g2.setColor(new Color(59, 130, 246, 50));
                g2.fillRoundRect(0, 0, w, h, 6, 6);
            }
            g2.translate(w / 2, h / 2);
            g2.rotate(Math.toRadians(rotation));
            g2.setColor(model.isRollover() ? ACCENT_BLUE : TEXT_MUTED);
            g2.setStroke(CHEVRON_STROKE); // reuse pre-allocated stroke
            g2.draw(CHEVRON_PATH);        // reuse pre-allocated path
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
                ? new Color(59, 130, 246, 100) : new Color(59, 130, 246, 40));
            g2.drawRoundRect(px, 2, pw - 1, h - 5, 8, 8);

            for (int i = 0; i < options.length; i++) {
                g2.setFont(i == selected ? FONT_BOLD : FONT_PLAIN);
                Color tc = i == selected ? TEXT_PRIMARY : TEXT_MUTED;
                if (!enabled) tc = new Color(tc.getRed(),tc.getGreen(),tc.getBlue(),100);
                g2.setColor(tc);
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
        private float spinAngle  = 0;
        private final Timer spinTimer;

        private static final BasicStroke SPIN_STROKE =
            new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

        RippleButton(String text, Color bg) {
            super(text); this.bg = bg;
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
            spinning = true; spinTimer.start(); setText("Starting…");
        }
        public void stopSpinner() {
            spinning = false; spinTimer.stop(); setText("Start");
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
                g2.setStroke(SPIN_STROKE);
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

    static class RoundToggleButton extends JToggleButton {
        private final Color bg, bgHover, bgSelected;
        private boolean hovered = false;

        RoundToggleButton(String text, Color bg, Color bgHover, Color bgSelected) {
            super(text);
            this.bg = bg; this.bgHover = bgHover; this.bgSelected = bgSelected;
            setOpaque(false); setContentAreaFilled(false);
            setBorderPainted(false); setFocusPainted(false);
            setFont(new Font("Segoe UI", Font.BOLD, 12));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hovered = true;  repaint(); }
                public void mouseExited(MouseEvent e)  { hovered = false; repaint(); }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = !isEnabled()
                ? (isLightMode ? new Color(203, 213, 225) : new Color(40, 50, 65))
                : (isSelected() ? bgSelected : (hovered ? bgHover : bg));
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
            g2.dispose();
            setForeground(!isEnabled()
                ? (isLightMode ? new Color(148, 163, 184) : Color.GRAY)
                : (isLightMode ? TEXT_PRIMARY : Color.BLACK));
            super.paintComponent(g);
        }
    }

    static class RoundedBorder extends AbstractBorder {
        private final int r; private final Color c;
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
            g2.fillRoundRect(r.x+2, r.y+2, r.width-4, r.height-4, 6, 6);
            g2.dispose();
        }
        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
            g.setColor(isLightMode
                ? new Color(241, 245, 249) : new Color(12, 16, 26));
            g.fillRect(r.x, r.y, r.width, r.height);
        }
    }

    static class ModernSplitPaneUI extends BasicSplitPaneUI {
        @Override public BasicSplitPaneDivider createDefaultDivider() {
            return new BasicSplitPaneDivider(this) {
                private boolean hovered = false;
                {
                    setLayout(null);
                    addMouseListener(new MouseAdapter() {
                        @Override public void mouseEntered(MouseEvent e) {
                            hovered = true;  repaint();
                        }
                        @Override public void mouseExited(MouseEvent e) {
                            hovered = false; repaint();
                        }
                    });
                }
                @Override protected JButton createLeftOneTouchButton() {
                    JButton b = new JButton();
                    b.setPreferredSize(new Dimension(0, 0));
                    b.setVisible(false); return b;
                }
                @Override protected JButton createRightOneTouchButton() {
                    JButton b = new JButton();
                    b.setPreferredSize(new Dimension(0, 0));
                    b.setVisible(false); return b;
                }
                @Override public void paint(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(hovered
                        ? (isLightMode
                            ? new Color(203, 213, 225) : new Color(25, 34, 54))
                        : (isLightMode
                            ? new Color(226, 232, 240) : new Color(20, 27, 44)));
                    g2.fillRect(0, 0, getWidth(), getHeight());
                    g2.dispose();
                }
            };
        }
    }

    // ── Table cell renderer / editor ─────────────────────────────────────
    class ControlCellRenderer extends JLabel implements TableCellRenderer {
        public ControlCellRenderer() {
            setOpaque(true);
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        @Override public Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            boolean active = value != null && (Boolean) value;
            setText(active ? "Active" : "View Only");
            setForeground(active ? ACCENT_GREEN : TEXT_MUTED);
            setBackground(isSelected
                ? new Color(59, 130, 246, 60)
                : (row % 2 == 0 ? SURFACE : SURFACE_ALT));
            setFont(new Font("Segoe UI", Font.PLAIN, 12));
            return this;
        }
    }

    class ControlCellEditor extends DefaultCellEditor {
        private final JCheckBox checkBox;

        public ControlCellEditor() {
            super(new JCheckBox());
            checkBox = (JCheckBox) getComponent();
            checkBox.setHorizontalAlignment(SwingConstants.CENTER);
            checkBox.setBackground(SURFACE);
            checkBox.setFocusPainted(false);

            Icon blankIcon = new Icon() {
                @Override public void paintIcon(Component c, Graphics g, int x, int y) {}
                @Override public int getIconWidth()  { return 0; }
                @Override public int getIconHeight() { return 0; }
            };
            checkBox.setIcon(blankIcon);
            checkBox.setSelectedIcon(blankIcon);
            checkBox.setPressedIcon(blankIcon);

            checkBox.addActionListener(e -> {
                boolean active = checkBox.isSelected();
                checkBox.setText(active ? "Active" : "View Only");
                checkBox.setForeground(active ? ACCENT_GREEN : TEXT_MUTED);
            });
        }

        public void updateTheme() { checkBox.setBackground(SURFACE); }

        @Override public Component getTableCellEditorComponent(
                JTable table, Object value, boolean isSelected, int row, int column) {
            boolean active = value != null && (Boolean) value;
            checkBox.setSelected(active);
            checkBox.setText(active ? "Active" : "View Only");
            checkBox.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            checkBox.setForeground(active ? ACCENT_GREEN : TEXT_MUTED);
            return checkBox;
        }
    }
}