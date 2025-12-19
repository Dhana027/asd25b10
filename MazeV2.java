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
