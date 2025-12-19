import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.util.*;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import javax.sound.sampled.*;

public class MazeV2 extends JPanel {

    private final int ROWS = 21;
    private final int COLS = 21;
    private final int CELL_SIZE = 32;
    private final int DELAY_SCAN = 10;
    private final int DELAY_PATH = 30;

    private final int TYPE_WALL = 0;
    private final int TYPE_GRASS = 1;
    private final int TYPE_MUD = 2;
    private final int TYPE_WATER = 3;

    private final Color COL_BG_DARK = new Color(30, 30, 35);
    private final Color COL_WALL_BASE = new Color(50, 50, 55);
    private final Color COL_WALL_TOP = new Color(70, 70, 75);

    private static final Color COLOR_BFS = new Color(0, 255, 255);
    private static final Color COLOR_DFS = new Color(255, 200, 0);
    private static final Color COLOR_DIJKSTRA = new Color(255, 255, 255);
    private static final Color COLOR_ASTAR = new Color(255, 50, 80);

    private int[][] maze;
    private Point startPos = new Point(1, 1);
    private Point exitPos = new Point(ROWS - 2, COLS - 2);

    private Point currentHead = null;
    private Set<Point> currentVisited = new HashSet<>();
    private Map<String, List<Point>> activePaths = new ConcurrentHashMap<>();

    private boolean isRunning = false;

    private static JLabel lblStatus;
    private static JLabel statBFS, statDFS, statDijk, statAStar;

    private Clip bgmClip;
    private FloatControl volumeControl;
    private boolean isMuted = false;
    private int sliderValue = 75;

    public MazeV2() {
        this.setPreferredSize(new Dimension(COLS * CELL_SIZE, ROWS * CELL_SIZE));
        this.setBackground(COL_BG_DARK);
        generateComplexMaze();
        initAudio();
    }

    private void initAudio() {
        try {
            File audioFile = new File("Outsource/Liyue.wav");
            if (audioFile.exists()) {
                AudioInputStream audioStream = AudioSystem.getAudioInputStream(audioFile);
                bgmClip = AudioSystem.getClip();
                bgmClip.open(audioStream);
                if (bgmClip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                    volumeControl = (FloatControl) bgmClip.getControl(FloatControl.Type.MASTER_GAIN);
                    setVolumeByPercentage(sliderValue);
                }
                bgmClip.loop(Clip.LOOP_CONTINUOUSLY);
                bgmClip.start();
            } else {
                updateStatus("Audio Missing: Outsource/Liyue.wav");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setVolumeByPercentage(int percentage) {
        this.sliderValue = percentage;
        if (volumeControl == null) return;
        if (percentage == 0) {
            volumeControl.setValue(volumeControl.getMinimum());
            return;
        }
        if (isMuted) return;
        float fraction = percentage / 100.0f;
        float dB = (float) (Math.log10(fraction) * 20.0);
        if (dB > volumeControl.getMaximum()) dB = volumeControl.getMaximum();
        if (dB < volumeControl.getMinimum()) dB = volumeControl.getMinimum();
        volumeControl.setValue(dB);
    }

    public void toggleMute() {
        if (volumeControl == null) return;
        isMuted = !isMuted;
        if (isMuted) volumeControl.setValue(volumeControl.getMinimum());
        else setVolumeByPercentage(sliderValue);
    }

    public boolean isMuted() { return isMuted; }

    public void generateComplexMaze() {
        if (isRunning) return;
        activePaths.clear();
        currentVisited.clear();
        resetAllStats();

        maze = new int[ROWS][COLS];
        for (int r = 0; r < ROWS; r++) Arrays.fill(maze[r], TYPE_WALL);

        maze[1][1] = TYPE_GRASS;
        ArrayList<int[]> walls = new ArrayList<>();
        addWalls(1, 1, walls);
        Random rand = new Random();

        while (!walls.isEmpty()) {
            int idx = rand.nextInt(walls.size());
            int[] wall = walls.remove(idx);
            int nr = wall[0] + wall[2], nc = wall[1] + wall[3];
            if (isValid(nr, nc) && maze[nr][nc] == TYPE_WALL) {
                maze[wall[0]][wall[1]] = TYPE_GRASS;
                maze[nr][nc] = TYPE_GRASS;
                addWalls(nr, nc, walls);
            }
        }

        for (int r = 1; r < ROWS - 1; r++) {
            for (int c = 1; c < COLS - 1; c++) {
                if (maze[r][c] == TYPE_WALL) {
                    boolean v = (maze[r-1][c] != TYPE_WALL && maze[r+1][c] != TYPE_WALL);
                    boolean h = (maze[r][c-1] != TYPE_WALL && maze[r][c+1] != TYPE_WALL);
                    if ((v || h) && rand.nextDouble() < 0.15) maze[r][c] = TYPE_GRASS;
                }
            }
        }

        for(int r=0; r<ROWS; r++){
            for(int c=0; c<COLS; c++){
                if(maze[r][c] != TYPE_WALL) {
                    double chance = rand.nextDouble();
                    if (chance < 0.5) maze[r][c] = TYPE_GRASS;
                    else if (chance < 0.8) maze[r][c] = TYPE_MUD;
                    else maze[r][c] = TYPE_WATER;
                }
            }
        }

        maze[startPos.x][startPos.y] = TYPE_GRASS;
        maze[exitPos.x][exitPos.y] = TYPE_GRASS;

        updateStatus("Map Generated. Ready.");
        repaint();
    }

    private void addWalls(int r, int c, ArrayList<int[]> walls) {
        int[][] dirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int[] d : dirs) {
            int nr = r + d[0], nc = c + d[1];
            if (isValid(nr, nc) && maze[nr][nc] == TYPE_WALL) walls.add(new int[]{nr, nc, d[0], d[1]});
        }
    }

    private int getCellCost(int r, int c) {
        if (maze[r][c] == TYPE_GRASS) return 1;
        if (maze[r][c] == TYPE_MUD) return 5;
        if (maze[r][c] == TYPE_WATER) return 10;
        return 9999;
    }

    private void prepareRun(String algoName) {
        if (isRunning) return;
        isRunning = true;
        currentVisited.clear();
        activePaths.remove(algoName);
        updateStatus("Running " + algoName + "...");
        repaint();
    }

    public void solveBFS() {
        if (isRunning) return; prepareRun("BFS");
        new Thread(() -> {
            Queue<Point> q = new LinkedList<>(); Map<Point, Point> p = new HashMap<>();
            q.add(startPos); currentVisited.add(startPos); boolean found = false;
            while (!q.isEmpty()) {
                Point cur = q.poll(); currentHead = cur; repaint(); sleep(DELAY_SCAN);
                if (cur.equals(exitPos)) { found = true; break; }
                int[][] d = {{-1,0},{1,0},{0,-1},{0,1}};
                for (int[] dir : d) { Point n = new Point(cur.x+dir[0], cur.y+dir[1]);
                    if (isValidStep(n) && !currentVisited.contains(n)) { currentVisited.add(n); p.put(n, cur); q.add(n); }
                }
            }
            finishRun("BFS", p, found);
        }).start();
    }

    public void solveDFS() {
        if (isRunning) return; prepareRun("DFS");
        new Thread(() -> {
            Stack<Point> s = new Stack<>(); Map<Point, Point> p = new HashMap<>();
            s.push(startPos); currentVisited.add(startPos); boolean found = false;
            while (!s.isEmpty()) {
                Point cur = s.pop(); currentHead = cur; repaint(); sleep(DELAY_SCAN);
                if (cur.equals(exitPos)) { found = true; break; }
                List<Point> nb = new ArrayList<>(); int[][] d = {{-1,0},{1,0},{0,-1},{0,1}};
                for (int[] dir : d) { Point n = new Point(cur.x+dir[0], cur.y+dir[1]);
                    if (isValidStep(n) && !currentVisited.contains(n)) nb.add(n);
                }
                Collections.shuffle(nb);
                for (Point n : nb) { currentVisited.add(n); p.put(n, cur); s.push(n); }
            }
            finishRun("DFS", p, found);
        }).start();
    }

    public void solveDijkstra() {
        if (isRunning) return; prepareRun("Dijkstra");
        new Thread(() -> {
            PriorityQueue<Node> pq = new PriorityQueue<>(); int[][] dist = new int[ROWS][COLS];
            for(int[] r : dist) Arrays.fill(r, Integer.MAX_VALUE); Map<Point, Point> pa = new HashMap<>();
            dist[startPos.x][startPos.y] = 0; pq.add(new Node(startPos, 0)); boolean found = false;
            while (!pq.isEmpty()) {
                Node cur = pq.poll(); Point cp = cur.p; if (cur.val > dist[cp.x][cp.y]) continue;
                currentHead = cp; currentVisited.add(cp); repaint(); sleep(DELAY_SCAN);
                if (cp.equals(exitPos)) { found = true; break; }
                int[][] d = {{-1,0},{1,0},{0,-1},{0,1}};
                for (int[] dir : d) { Point np = new Point(cp.x+dir[0], cp.y+dir[1]);
                    if (isValidStep(np)) { int nc = dist[cp.x][cp.y] + getCellCost(np.x, np.y);
                        if (nc < dist[np.x][np.y]) { dist[np.x][np.y] = nc; pq.add(new Node(np, nc)); pa.put(np, cp); }
                    }
                }
            }
            finishRun("Dijkstra", pa, found);
        }).start();
    }

    public void solveAStar() {
        if (isRunning) return; prepareRun("A*");
        new Thread(() -> {
            PriorityQueue<Node> pq = new PriorityQueue<>(); int[][] gs = new int[ROWS][COLS];
            for(int[] r : gs) Arrays.fill(r, Integer.MAX_VALUE); Map<Point, Point> pa = new HashMap<>();
            gs[startPos.x][startPos.y] = 0; pq.add(new Node(startPos, 0)); boolean found = false;
            while (!pq.isEmpty()) {
                Node cur = pq.poll(); Point cp = cur.p; if (cur.val > gs[cp.x][cp.y]) continue;
                currentHead = cp; currentVisited.add(cp); repaint(); sleep(DELAY_SCAN);
                if (cp.equals(exitPos)) { found = true; break; }
                int[][] d = {{-1,0},{1,0},{0,-1},{0,1}};
                for (int[] dir : d) { Point np = new Point(cp.x+dir[0], cp.y+dir[1]);
                    if (isValidStep(np)) { int tg = gs[cp.x][cp.y] + getCellCost(np.x, np.y);
                        if (tg < gs[np.x][np.y]) { gs[np.x][np.y] = tg; int f = tg + (Math.abs(np.x-exitPos.x)+Math.abs(np.y-exitPos.y));
                            Node nn = new Node(np, tg); nn.priority = f; pq.add(nn); pa.put(np, cp); }
                    }
                }
            }
            finishRun("A*", pa, found);
        }).start();
    }

    private void finishRun(String algoName, Map<Point, Point> parents, boolean found) {
        currentHead = null;
        if (!found) { updateStatus(algoName + " Failed!"); isRunning = false; repaint(); return; }

        List<Point> path = new ArrayList<>(); Point curr = exitPos; int cost = 0;
        while (curr != null) { path.add(curr); cost += getCellCost(curr.x, curr.y); curr = parents.get(curr); }
        Collections.reverse(path);

        activePaths.put(algoName, new ArrayList<>());
        cost -= getCellCost(startPos.x, startPos.y);
        int steps = path.size() - 1;

        updateStatus(algoName + " Finished.");
        updateAlgoStats(algoName, steps, cost);

        List<Point> animList = activePaths.get(algoName);
        for (Point p : path) { animList.add(p); repaint(); sleep(DELAY_PATH); }
        isRunning = false;
    }

    private void updateAlgoStats(String algo, int steps, int cost) {
        String text = String.format("Steps: %d | Cost: %d", steps, cost);
        SwingUtilities.invokeLater(() -> {
            switch (algo) {
                case "BFS": if(statBFS != null) statBFS.setText("BFS: " + text); break;
                case "DFS": if(statDFS != null) statDFS.setText("DFS: " + text); break;
                case "Dijkstra": if(statDijk != null) statDijk.setText("Dijk: " + text); break;
                case "A*": if(statAStar != null) statAStar.setText("A*: " + text); break;
            }
        });
    }

    private void resetAllStats() {
        if(statBFS != null) statBFS.setText("BFS: -");
        if(statDFS != null) statDFS.setText("DFS: -");
        if(statDijk != null) statDijk.setText("Dijk: -");
        if(statAStar != null) statAStar.setText("A*: -");
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                drawCell(g2, r, c, c * CELL_SIZE, r * CELL_SIZE);
            }
        }

        activePaths.forEach((algo, pathList) -> {
            if (pathList.isEmpty()) return;
            Color color = Color.WHITE; int ox = 0, oy = 0;
            switch (algo) {
                case "BFS": color = COLOR_BFS; ox = -6; oy = -6; break;
                case "DFS": color = COLOR_DFS; ox = 6; oy = -6; break;
                case "Dijkstra": color = COLOR_DIJKSTRA; ox = -6; oy = 6; break;
                case "A*": color = COLOR_ASTAR; ox = 6; oy = 6; break;
            }
            g2.setColor(color);
            g2.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            if (pathList.size() > 1) {
                int[] xPoints = new int[pathList.size()];
                int[] yPoints = new int[pathList.size()];
                for (int i=0; i<pathList.size(); i++) {
                    Point p = pathList.get(i);
                    xPoints[i] = p.y * CELL_SIZE + CELL_SIZE/2 + ox;
                    yPoints[i] = p.x * CELL_SIZE + CELL_SIZE/2 + oy;
                }
                g2.drawPolyline(xPoints, yPoints, pathList.size());
            }
            for(Point p : pathList) {
                g2.fillOval(p.y * CELL_SIZE + CELL_SIZE/2 + ox - 3, p.x * CELL_SIZE + CELL_SIZE/2 + oy - 3, 6, 6);
            }
        });

        if (currentHead != null) {
            g2.setColor(new Color(255, 100, 0, 200));
            int hx = currentHead.y * CELL_SIZE, hy = currentHead.x * CELL_SIZE;
            g2.fillOval(hx + 4, hy + 4, CELL_SIZE - 8, CELL_SIZE - 8);
            g2.setColor(Color.WHITE); g2.setStroke(new BasicStroke(2));
            g2.drawOval(hx + 4, hy + 4, CELL_SIZE - 8, CELL_SIZE - 8);
        }
        drawMarker(g2, startPos, Color.WHITE, "S");
        drawMarker(g2, exitPos, new Color(255, 50, 50), "E");
    }

    private void drawCell(Graphics2D g2, int r, int c, int x, int y) {
        int type = maze[r][c];
        if (type == TYPE_WALL) {
            g2.setColor(COL_WALL_BASE); g2.fillRect(x, y, CELL_SIZE, CELL_SIZE);
            g2.setColor(COL_WALL_TOP); g2.fillRect(x + 2, y + 2, CELL_SIZE - 4, CELL_SIZE - 4);
        } else {
            if (type == TYPE_GRASS) g2.setColor(new Color(34, 139, 34));
            else if (type == TYPE_MUD) g2.setColor(new Color(101, 67, 33));
            else if (type == TYPE_WATER) g2.setColor(new Color(0, 105, 148));
            g2.fillRect(x, y, CELL_SIZE, CELL_SIZE);

            if (type == TYPE_GRASS) {
                g2.setColor(new Color(50, 205, 50, 100)); g2.fillRect(x+5, y+5, 4, 4); g2.fillRect(x+20, y+20, 3, 3);
            } else if (type == TYPE_WATER) {
                g2.setColor(new Color(135, 206, 250, 80)); g2.drawLine(x+5, y+10, x+15, y+10);
            } else if (type == TYPE_MUD) {
                g2.setColor(new Color(60, 40, 10, 80)); g2.fillOval(x+8, y+8, 6, 6);
            }
            if (currentVisited.contains(new Point(r, c))) {
                g2.setColor(new Color(255, 255, 255, 40)); g2.fillRect(x, y, CELL_SIZE, CELL_SIZE);
            }
        }
    }

    private void drawMarker(Graphics2D g2, Point p, Color c, String text) {
        int x = p.y * CELL_SIZE, y = p.x * CELL_SIZE;
        g2.setColor(new Color(0,0,0,100)); g2.fillOval(x+6, y+8, CELL_SIZE-10, CELL_SIZE-10);
        g2.setColor(c); g2.fillOval(x+4, y+4, CELL_SIZE-8, CELL_SIZE-8);
        g2.setColor(Color.BLACK); g2.setStroke(new BasicStroke(2)); g2.drawOval(x+4, y+4, CELL_SIZE-8, CELL_SIZE-8);
        g2.setFont(new Font("Arial", Font.BOLD, 14)); g2.drawString(text, x+11, y+21);
    }

    private void updateStatus(String text) { if (lblStatus != null) lblStatus.setText(text); }

    private void sleep(int millis) { try { Thread.sleep(millis); } catch (Exception e) {} }
    private boolean isValid(int r, int c) { return r > 0 && r < ROWS - 1 && c > 0 && c < COLS - 1; }
    private boolean isValidStep(Point p) { return p.x >= 0 && p.x < ROWS && p.y >= 0 && p.y < COLS && maze[p.x][p.y] != TYPE_WALL; }

    class Node implements Comparable<Node> {
        Point p; int val; int priority;
        public Node(Point p, int val) { this.p = p; this.val = val; this.priority = val; }
        public int compareTo(Node o) { return Integer.compare(this.priority, o.priority); }
    }

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception e) {}

        JFrame frame = new JFrame("Maze Game - Path Finder Algorithm");
        MazeV2 gamePanel = new MazeV2();

        frame.setLayout(new BorderLayout());
        frame.add(gamePanel, BorderLayout.CENTER);

        JPanel sidebar = new JPanel();
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setPreferredSize(new Dimension(250, 0));
        sidebar.setBackground(new Color(40, 40, 45));
        sidebar.setBorder(new EmptyBorder(15, 15, 15, 15));

        JLabel title = new JLabel("MAZE COMMAND");
        title.setForeground(Color.CYAN);
        title.setFont(new Font("Segoe UI", Font.BOLD, 20));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        sidebar.add(title);
        sidebar.add(Box.createVerticalStrut(20));

        addSectionTitle(sidebar, "AUDIO CONTROL");
        JButton btnMute = createModernButton("Mute Sound", new Color(100, 100, 100));
        JSlider volSlider = new JSlider(0, 100, 75);
        volSlider.setBackground(new Color(40, 40, 45));
        btnMute.addActionListener(e -> {
            gamePanel.toggleMute();
            btnMute.setText(gamePanel.isMuted() ? "Unmute" : "Mute Sound");
        });
        volSlider.addChangeListener(e -> gamePanel.setVolumeByPercentage(volSlider.getValue()));
        sidebar.add(btnMute); sidebar.add(Box.createVerticalStrut(5)); sidebar.add(volSlider);
        sidebar.add(Box.createVerticalStrut(15));

        addSectionTitle(sidebar, "MAP GENERATOR");
        JButton btnGen = createModernButton("Generate New Map", new Color(46, 204, 113));
        JButton btnClear = createModernButton("Clear Lines", new Color(231, 76, 60));
        btnGen.addActionListener(e -> gamePanel.generateComplexMaze());
        btnClear.addActionListener(e -> {
            gamePanel.activePaths.clear();
            gamePanel.currentVisited.clear();
            gamePanel.resetAllStats();
            gamePanel.repaint();
        });
        sidebar.add(btnGen); sidebar.add(Box.createVerticalStrut(5)); sidebar.add(btnClear);
        sidebar.add(Box.createVerticalStrut(15));

        addSectionTitle(sidebar, "ALGORITHMS");
        JButton btnBFS = createModernButton("BFS", new Color(0, 200, 200));
        JButton btnDFS = createModernButton("DFS", new Color(200, 150, 0));
        JButton btnDijk = createModernButton("Dijkstra", new Color(200, 200, 200));
        JButton btnA = createModernButton("A*", new Color(255, 50, 80));

        btnBFS.addActionListener(e -> gamePanel.solveBFS());
        btnDFS.addActionListener(e -> gamePanel.solveDFS());
        btnDijk.addActionListener(e -> gamePanel.solveDijkstra());
        btnA.addActionListener(e -> gamePanel.solveAStar());

        sidebar.add(btnBFS); sidebar.add(Box.createVerticalStrut(5));
        sidebar.add(btnDFS); sidebar.add(Box.createVerticalStrut(5));
        sidebar.add(btnDijk); sidebar.add(Box.createVerticalStrut(5));
        sidebar.add(btnA); sidebar.add(Box.createVerticalStrut(20));

        addSectionTitle(sidebar, "STATISTICS");
        statBFS = createStatLabel("BFS: -", COLOR_BFS);
        statDFS = createStatLabel("DFS: -", COLOR_DFS);
        statDijk = createStatLabel("Dijk: -", COLOR_DIJKSTRA);
        statAStar = createStatLabel("A*: -", COLOR_ASTAR);

        sidebar.add(statBFS);
        sidebar.add(statDFS);
        sidebar.add(statDijk);
        sidebar.add(statAStar);
        sidebar.add(Box.createVerticalStrut(20));

        lblStatus = new JLabel("Ready");
        lblStatus.setForeground(Color.LIGHT_GRAY);
        lblStatus.setAlignmentX(Component.CENTER_ALIGNMENT);
        sidebar.add(lblStatus);

        frame.add(sidebar, BorderLayout.EAST);
        frame.pack();
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static void addSectionTitle(JPanel panel, String text) {
        JLabel label = new JLabel(text);
        label.setForeground(new Color(150, 150, 160));
        label.setFont(new Font("Segoe UI", Font.BOLD, 10));
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(label);
        panel.add(Box.createVerticalStrut(5));
    }

    private static JLabel createStatLabel(String text, Color color) {
        JLabel lbl = new JLabel(text);
        lbl.setForeground(color);
        lbl.setFont(new Font("Consolas", Font.BOLD, 12));
        lbl.setAlignmentX(Component.CENTER_ALIGNMENT);
        lbl.setBorder(new EmptyBorder(0, 0, 5, 0));
        return lbl;
    }

    private static JButton createModernButton(String text, Color baseColor) {
        JButton btn = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (getModel().isPressed()) g2.setColor(baseColor.darker());
                else if (getModel().isRollover()) g2.setColor(baseColor.brighter());
                else g2.setColor(baseColor);
                g2.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 10, 10));
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Segoe UI", Font.BOLD, 12));
                FontMetrics fm = g2.getFontMetrics();
                int x = (getWidth() - fm.stringWidth(getText())) / 2;
                int y = (getHeight() + fm.getAscent()) / 2 - 2;
                g2.drawString(getText(), x, y);
                g2.dispose();
            }
        };
        btn.setPreferredSize(new Dimension(220, 35));
        btn.setMaximumSize(new Dimension(220, 35));
        btn.setContentAreaFilled(false);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.setAlignmentX(Component.CENTER_ALIGNMENT);
        return btn;
    }
}

