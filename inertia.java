import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

public class Inertia extends JFrame {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> showSolverStrategyDialog(null));
    }

    // =========================================================================
    //  Strategy-selection dialog
    // =========================================================================
    private static void showSolverStrategyDialog(Inertia existingFrame) {
        String[] strategies = {
            "BFS (Breadth-First Search)",
            "Greedy Algorithm",
            "Divide & Conquer",
            "Dynamic Programming",
            "Backtracking"
        };
        String choice = (String) JOptionPane.showInputDialog(
            existingFrame, "Choose solving strategy:", "Solver Strategy",
            JOptionPane.QUESTION_MESSAGE, null, strategies, strategies[0]);
        if (choice == null) return;
        int strategy = 1;
        if      (choice.contains("BFS"))          strategy = 1;
        else if (choice.contains("Greedy"))       strategy = 2;
        else if (choice.contains("Divide"))       strategy = 3;
        else if (choice.contains("Dynamic"))      strategy = 4;
        else if (choice.contains("Backtracking")) strategy = 5;
        if (existingFrame != null) existingFrame.changeStrategy(strategy);
        else { Inertia f = new Inertia(strategy); f.setVisible(true); }
    }

    // =========================================================================
    //  Model
    // =========================================================================
    enum Cell  { EMPTY, WALL, MINE, GEM, STOP, BLOCK }
    enum Turn  { WAITING, SOLVING }

    static final class Vec {
        final int r, c;
        Vec(int r, int c) { this.r = r; this.c = c; }
        Vec add(Vec o) { return new Vec(r + o.r, c + o.c); }
        @Override public boolean equals(Object o) {
            return (o instanceof Vec) && ((Vec)o).r == r && ((Vec)o).c == c;
        }
        @Override public int hashCode() { return Objects.hash(r, c); }
        @Override public String toString() { return "(" + r + "," + c + ")"; }
    }

    static final Vec[] DIRS = {
        new Vec(-1, 0), new Vec(1, 0), new Vec(0,-1), new Vec(0, 1),
        new Vec(-1,-1), new Vec(-1,1), new Vec(1,-1), new Vec(1, 1)
    };

    static final class Grid {
        final int rows, cols;
        final Cell[][] cells;
        Grid(Cell[][] cells) {
            this.rows = cells.length; this.cols = cells[0].length; this.cells = cells;
        }
        boolean inBounds(Vec v) { return v.r>=0 && v.r<rows && v.c>=0 && v.c<cols; }
        Cell    get(Vec v)      { return cells[v.r][v.c]; }
        boolean isWall(Vec v)   { Cell c = get(v); return c==Cell.WALL || c==Cell.BLOCK; }
    }

    static final class GameState {
        final Grid  grid;
        Vec         ball;
        boolean[][] gemPresent;
        int         totalGems, gemsCollected, deaths;

        GameState(Grid grid, Vec start, boolean[][] gemPresent, int totalGems) {
            this.grid = grid; this.ball = start;
            this.gemPresent = deepCopy(gemPresent);
            this.totalGems = totalGems; this.gemsCollected = 0; this.deaths = 0;
        }
        boolean allGemsCollected() { return gemsCollected >= totalGems; }

        static boolean[][] deepCopy(boolean[][] src) {
            boolean[][] out = new boolean[src.length][src[0].length];
            for (int i = 0; i < src.length; i++)
                System.arraycopy(src[i], 0, out[i], 0, src[i].length);
            return out;
        }
    }

    // =========================================================================
    //  Fields
    // =========================================================================
    final BoardPanel          board;
    final JLabel              status;
    GameState                 state;
    Level                     currentLevel;

    volatile boolean          showExplosion   = false;
    Vec                       explosionCenter = null;
    javax.swing.Timer         explosionTimer  = null;
    javax.swing.Timer         solverTimer     = null;
    boolean                   gameOver        = false;
    private Turn              turn            = Turn.WAITING;
    private int               solverStrategy;
    private int               movesThisSolve  = 0;
    private final List<SolveResult> solveResults = new ArrayList<>();
    private JTextArea         statsArea;

    // =========================================================================
    //  Constructor
    // =========================================================================
    public Inertia(int solverStrategy) {
        super("Inertia — Computer Solver  [" + strategyName(solverStrategy) + "]");
        this.solverStrategy = solverStrategy;
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        this.currentLevel = Level.generateRandomLevel();
        this.state        = currentLevel.toGameState();
        this.board        = new BoardPanel(state);
        this.status       = new JLabel(" ");
        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        updateStatus();

        add(board,             BorderLayout.CENTER);
        add(toolbar(),         BorderLayout.NORTH);
        add(buildStatsPanel(), BorderLayout.EAST);
        add(status,            BorderLayout.SOUTH);

        setSize(1020, 680);
        setLocationRelativeTo(null);
        SwingUtilities.invokeLater(this::startSolverWithStrategy);
    }

    private void changeStrategy(int s) {
        stopTimers();
        this.solverStrategy = s;
        setTitle("Inertia — Computer Solver  [" + strategyName(s) + "]");
        state = currentLevel.toGameState();
        gameOver = false; showExplosion = false; explosionCenter = null; movesThisSolve = 0;
        board.setState(state); updateStatus(); board.repaint();
        SwingUtilities.invokeLater(this::startSolverWithStrategy);
    }

    // =========================================================================
    //  Stats panel
    // =========================================================================
    private JPanel buildStatsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(235, 0));
        panel.setBorder(BorderFactory.createTitledBorder("Strategy Comparison"));
        statsArea = new JTextArea();
        statsArea.setEditable(false);
        statsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        statsArea.setBackground(new Color(240, 244, 255));
        statsArea.setMargin(new Insets(6, 6, 6, 6));
        panel.add(new JScrollPane(statsArea), BorderLayout.CENTER);
        JButton clr = new JButton("Clear Stats");
        clr.addActionListener(e -> { solveResults.clear(); refreshStats(); });
        panel.add(clr, BorderLayout.SOUTH);
        refreshStats();
        return panel;
    }

    private void refreshStats() {
        if (statsArea == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-22s %5s%n", "Strategy", "Moves"));
        sb.append("─".repeat(30)).append("\n");
        for (SolveResult r : solveResults)
            sb.append(String.format("%-22s %5s  %s%n",
                r.strategyName,
                r.solved ? String.valueOf(r.moves) : "-",
                r.solved ? "✓" : "✗"));
        if (solveResults.isEmpty()) sb.append("(no results yet)\n");
        statsArea.setText(sb.toString());
    }

    static class SolveResult {
        String strategyName; int moves; boolean solved;
        SolveResult(String n, int m, boolean s) {
            strategyName = n; moves = m; solved = s;
        }
    }

    // =========================================================================
    //  Toolbar
    // =========================================================================
    private JToolBar toolbar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);

        JButton newGame = new JButton("New Game");
        newGame.addActionListener(e -> {
            stopTimers();
            status.setText("Generating…");
            new SwingWorker<Level,Void>() {
                @Override protected Level doInBackground() { return Level.generateRandomLevel(); }
                @Override protected void done() {
                    try {
                        currentLevel = get();
                        solveResults.clear(); refreshStats();
                        resetToLevel(currentLevel);
                        startSolverWithStrategy();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        JOptionPane.showMessageDialog(Inertia.this, "Failed to generate level");
                    }
                }
            }.execute();
        });

        JButton restartSame = new JButton("Restart Same Game");
        restartSame.addActionListener(e -> {
            stopTimers(); resetToLevel(currentLevel); startSolverWithStrategy();
        });

        JButton changeStrat = new JButton("Change Strategy");
        changeStrat.addActionListener(e -> showSolverStrategyDialog(this));

        JButton compareAll = new JButton("Compare All Strategies");
        compareAll.addActionListener(e -> runAllStrategiesComparison());

        tb.add(newGame); tb.add(restartSame); tb.add(changeStrat);
        tb.addSeparator(); tb.add(compareAll);
        return tb;
    }

    private void resetToLevel(Level level) {
        state = level.toGameState();
        gameOver = false; showExplosion = false; explosionCenter = null;
        movesThisSolve = 0; turn = Turn.WAITING;
        board.setState(state); updateStatus(); board.repaint();
    }

    private void runAllStrategiesComparison() {
        stopTimers(); solveResults.clear(); refreshStats();
        status.setText("Running all 5 strategies…");
        new SwingWorker<List<SolveResult>,Void>() {
            @Override protected List<SolveResult> doInBackground() {
                List<SolveResult> res = new ArrayList<>();
                for (int s = 1; s <= 5; s++) {
                    GameState tmp = currentLevel.toGameState();
                    List<Integer> plan = solveWithStrategy(s, tmp);
                    int mv = (plan != null) ? plan.size() : 0;
                    res.add(new SolveResult(strategyName(s), mv,
                                            plan != null && !plan.isEmpty()));
                }
                return res;
            }
            @Override protected void done() {
                try {
                    solveResults.addAll(get()); refreshStats();
                    status.setText("Comparison complete.");
                    resetToLevel(currentLevel); startSolverWithStrategy();
                } catch (Exception ex) { ex.printStackTrace(); }
            }
        }.execute();
    }

    private void stopTimers() {
        if (explosionTimer != null && explosionTimer.isRunning()) explosionTimer.stop();
        if (solverTimer    != null && solverTimer.isRunning())    solverTimer.stop();
        solverTimer = null;
    }

    // =========================================================================
    //  Solver dispatcher
    // =========================================================================
    private List<Integer> solveWithStrategy(int strategy, GameState s) {
        switch (strategy) {
            case 1:  return bfsSolve(s);
            case 2:  return greedySolve(s);
            case 3:  return divideAndConquerSolve(s);
            case 4:  return dynamicProgrammingSolve(s);
            case 5:  return backtrackingSolve(s);
            default: return bfsSolve(s);
        }
    }

    private void startSolverWithStrategy() {
        turn = Turn.SOLVING; movesThisSolve = 0;
        updateStatus();
        new SwingWorker<List<Integer>,Void>() {
            @Override protected List<Integer> doInBackground() {
                return solveWithStrategy(solverStrategy, currentLevel.toGameState());
            }
            @Override protected void done() {
                try {
                    List<Integer> plan = get();
                    if (plan == null || plan.isEmpty()) {
                        JOptionPane.showMessageDialog(Inertia.this,
                            "No solution found. Try New Game.", "Solver",
                            JOptionPane.WARNING_MESSAGE);
                        turn = Turn.WAITING; updateStatus(); return;
                    }
                    solveResults.add(new SolveResult(strategyName(solverStrategy),
                                                     plan.size(), true));
                    refreshStats(); animateSolution(plan);
                } catch (Exception ex) {
                    ex.printStackTrace(); turn = Turn.WAITING; updateStatus();
                }
            }
        }.execute();
    }

    // =========================================================================
    //  Animation
    // =========================================================================
    private void animateSolution(List<Integer> plan) {
        final Iterator<Integer> it = plan.iterator();
        if (solverTimer != null && solverTimer.isRunning()) solverTimer.stop();
        solverTimer = new javax.swing.Timer(280, ev -> {
            if (gameOver) { solverTimer.stop(); turn=Turn.WAITING; updateStatus(); return; }
            if (!it.hasNext()) {
                solverTimer.stop();
                if (state.allGemsCollected()) {
                    gameOver = true; updateStatus(); board.repaint();
                    JOptionPane.showMessageDialog(Inertia.this,
                        "Solved! All gems in " + movesThisSolve + " moves.",
                        "Done", JOptionPane.INFORMATION_MESSAGE);
                } else { turn = Turn.WAITING; updateStatus(); }
                return;
            }
            boolean died = slideFrom(state, state.ball, DIRS[it.next()], true, false);
            movesThisSolve++; updateStatus(); board.repaint();
            if (died) { solverTimer.stop(); turn=Turn.WAITING; updateStatus(); return; }
            if (state.allGemsCollected()) {
                solverTimer.stop(); gameOver = true; updateStatus();
                JOptionPane.showMessageDialog(Inertia.this,
                    "Solved! All gems in " + movesThisSolve + " moves.",
                    "Done", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        solverTimer.setRepeats(true); solverTimer.start();
    }

    // =========================================================================
    //  Slide helpers
    // =========================================================================
    private static boolean slideFrom(GameState gs, Vec start, Vec dir,
                                     boolean collect, boolean allowDeath) {
        Vec cur = new Vec(start.r, start.c);
        boolean died = false;
        while (true) {
            Vec nxt = cur.add(dir);
            if (!gs.grid.inBounds(nxt) || gs.grid.isWall(nxt)) break;
            cur = nxt;
            if (collect && gs.gemPresent[cur.r][cur.c]) {
                gs.gemPresent[cur.r][cur.c] = false; gs.gemsCollected++;
            }
            Cell cell = gs.grid.get(cur);
            if (cell == Cell.MINE) {
                gs.ball = new Vec(cur.r,cur.c); if (allowDeath) died=true; break;
            }
            if (cell == Cell.STOP) break;
        }
        gs.ball = new Vec(cur.r, cur.c);
        return died;
    }

    static final class SlideResult {
        final int r, c, mask; final boolean died, moved;
        SlideResult(int r,int c,int mask,boolean died,boolean moved) {
            this.r=r; this.c=c; this.mask=mask; this.died=died; this.moved=moved;
        }
    }

    private static SlideResult slideForSolver(int r, int c, Vec dir, Grid grid,
                                              int[][] gemIdx, int mask) {
        int cr=r, cc=c, cm=mask; boolean moved=false;
        while (true) {
            int nr=cr+dir.r, nc=cc+dir.c;
            if (nr<0||nr>=grid.rows||nc<0||nc>=grid.cols) break;
            Vec nv = new Vec(nr,nc);
            if (grid.isWall(nv)) break;
            moved=true; cr=nr; cc=nc;
            Cell cell = grid.get(nv);
            if (cell==Cell.MINE) return new SlideResult(cr,cc,cm,true,true);
            int gi=gemIdx[cr][cc]; if(gi!=-1) cm|=(1<<gi);
            if (cell==Cell.STOP) break;
        }
        return new SlideResult(cr,cc,cm,false,moved);
    }

    private static int manhattan(Vec a, Vec b) {
        return Math.abs(a.r-b.r)+Math.abs(a.c-b.c);
    }

    private static Vec findNearestGem(Vec from, GameState gs) {
        int best=Integer.MAX_VALUE; Vec v=null;
        for (int r=0;r<gs.grid.rows;r++)
            for (int c=0;c<gs.grid.cols;c++)
                if (gs.gemPresent[r][c]) {
                    int d=manhattan(from,new Vec(r,c));
                    if (d<best) { best=d; v=new Vec(r,c); }
                }
        return v;
    }

    private static GameState cloneState(GameState o) {
        boolean[][] g=new boolean[o.grid.rows][o.grid.cols];
        for (int r=0;r<o.grid.rows;r++)
            System.arraycopy(o.gemPresent[r],0,g[r],0,o.grid.cols);
        GameState ns=new GameState(o.grid,new Vec(o.ball.r,o.ball.c),g,o.totalGems);
        ns.gemsCollected=o.gemsCollected; return ns;
    }

    private static int[][] buildGemIndex(GameState gs, int max) {
        int[][] idx=new int[gs.grid.rows][gs.grid.cols];
        for (int[] row:idx) Arrays.fill(row,-1);
        int k=0;
        for (int r=0;r<gs.grid.rows;r++)
            for (int c=0;c<gs.grid.cols;c++)
                if (gs.gemPresent[r][c] && k<max) idx[r][c]=k++;
        return idx;
    }

    // =========================================================================
    //  Strategy 1 – BFS
    // =========================================================================
    private List<Integer> bfsSolve(GameState s) {
        int rows=s.grid.rows, cols=s.grid.cols;
        int[][] gemIdx = buildGemIndex(s, 20);
        int gc=0;
        for (int r=0;r<rows;r++) for (int c=0;c<cols;c++) if (gemIdx[r][c]!=-1) gc++;
        if (gc==0) return Collections.emptyList();
        int full=(1<<gc)-1;

        try {
            boolean[][][] vis = new boolean[rows][cols][1<<gc];
            int[][][] pR=new int[rows][cols][1<<gc], pC=new int[rows][cols][1<<gc],
                      pM=new int[rows][cols][1<<gc], pD=new int[rows][cols][1<<gc];
            for (int r=0;r<rows;r++) for (int c=0;c<cols;c++) Arrays.fill(pD[r][c],-2);

            int sr=s.ball.r, sc=s.ball.c;
            int sm=0; if(gemIdx[sr][sc]!=-1) sm|=1<<gemIdx[sr][sc];
            vis[sr][sc][sm]=true; pD[sr][sc][sm]=-1;
            ArrayDeque<int[]> q=new ArrayDeque<>(); q.add(new int[]{sr,sc,sm});

            while (!q.isEmpty()) {
                int[] cur=q.poll(); int cr=cur[0],cc=cur[1],cm=cur[2];
                if (cm==full) return recoverBFS(pR,pC,pM,pD,cr,cc,cm);
                for (int di=0;di<DIRS.length;di++) {
                    SlideResult sr2=slideForSolver(cr,cc,DIRS[di],s.grid,gemIdx,cm);
                    if (!sr2.moved||sr2.died) continue;
                    if (!vis[sr2.r][sr2.c][sr2.mask]) {
                        vis[sr2.r][sr2.c][sr2.mask]=true;
                        pR[sr2.r][sr2.c][sr2.mask]=cr; pC[sr2.r][sr2.c][sr2.mask]=cc;
                        pM[sr2.r][sr2.c][sr2.mask]=cm; pD[sr2.r][sr2.c][sr2.mask]=di;
                        q.add(new int[]{sr2.r,sr2.c,sr2.mask});
                    }
                }
            }
            return null;
        } catch (OutOfMemoryError oom) {
            return bfsSolveHash(s, gemIdx, full);
        }
    }

    private List<Integer> bfsSolveHash(GameState s, int[][] gemIdx, int full) {
        Map<Long,Long>    prev    = new HashMap<>();
        Map<Long,Integer> prevDir = new HashMap<>();
        ArrayDeque<Long>  q       = new ArrayDeque<>();
        int sr=s.ball.r, sc=s.ball.c, cols=s.grid.cols;
        int sm=0; if(gemIdx[sr][sc]!=-1) sm|=1<<gemIdx[sr][sc];
        long startK = eKey(sr,sc,sm);
        prev.put(startK,null); prevDir.put(startK,-1); q.add(startK);
        while (!q.isEmpty()) {
            long k=q.poll();
            int cr=(int)(k>>20)&0x3FF, cc=(int)(k>>10)&0x3FF, cm=(int)(k&0x3FF);
            if (cm==full) {
                List<Integer> path=new ArrayList<>();
                long p=k;
                while (prevDir.get(p)!=-1) { path.add(prevDir.get(p)); p=prev.get(p); }
                Collections.reverse(path); return path;
            }
            for (int di=0;di<DIRS.length;di++) {
                SlideResult sr2=slideForSolver(cr,cc,DIRS[di],s.grid,gemIdx,cm);
                if (!sr2.moved||sr2.died) continue;
                long nk=eKey(sr2.r,sr2.c,sr2.mask);
                if (!prev.containsKey(nk)) { prev.put(nk,k); prevDir.put(nk,di); q.add(nk); }
            }
        }
        return null;
    }

    private static long eKey(int r, int c, int mask) {
        return ((long)r<<20)|((long)c<<10)|(mask&0x3FF);
    }

    private List<Integer> recoverBFS(int[][][]pR,int[][][]pC,int[][][]pM,int[][][]pD,
                                     int r,int c,int m) {
        List<Integer> d=new ArrayList<>();
        while (true) {
            int v=pD[r][c][m]; if(v==-1) break;
            d.add(v); int pr=pR[r][c][m],pc=pC[r][c][m],pm=pM[r][c][m];
            r=pr; c=pc; m=pm;
        }
        Collections.reverse(d); return d;
    }

    // =========================================================================
    //  Strategy 2 – Greedy
    // =========================================================================
    private List<Integer> greedySolve(GameState s) {
        GameState gs = cloneState(s);
        List<Integer> plan = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (int iter=0; iter<600 && !gs.allGemsCollected(); iter++) {
            String key = stateKey(gs);
            if (seen.contains(key)) {
                List<Integer> tail = bfsSolve(gs);
                if (tail!=null) { plan.addAll(tail); return plan; }
                return bfsSolve(s);
            }
            seen.add(key);
            Vec target = findNearestGem(gs.ball, gs);
            if (target==null) break;
            int bestDir=-1, bestGems=-1, bestDist=Integer.MAX_VALUE;
            for (int di=0;di<DIRS.length;di++) {
                SlideResult sr=slideForSolver(gs.ball.r,gs.ball.c,DIRS[di],
                                              gs.grid,buildGemIndex(gs,64),0);
                if (!sr.moved||sr.died) continue;
                int gems=countGemsOnPath(gs,gs.ball,DIRS[di]);
                int dist=manhattan(new Vec(sr.r,sr.c),target);
                if (gems>bestGems||(gems==bestGems&&dist<bestDist)) {
                    bestGems=gems; bestDist=dist; bestDir=di;
                }
            }
            if (bestDir==-1) {
                List<Integer> tail=bfsSolve(gs);
                if (tail!=null) { plan.addAll(tail); return plan; }
                return bfsSolve(s);
            }
            plan.add(bestDir);
            slideFrom(gs, gs.ball, DIRS[bestDir], true, false);
        }
        if (gs.allGemsCollected()) return plan;
        return bfsSolve(s);
    }

    private int countGemsOnPath(GameState gs, Vec start, Vec dir) {
        Vec cur=new Vec(start.r,start.c); int count=0;
        while (true) {
            Vec nxt=cur.add(dir);
            if (!gs.grid.inBounds(nxt)||gs.grid.isWall(nxt)) break;
            cur=nxt;
            if (gs.gemPresent[cur.r][cur.c]) count++;
            Cell cell=gs.grid.get(cur);
            if (cell==Cell.MINE||cell==Cell.STOP) break;
        }
        return count;
    }

    private String stateKey(GameState gs) {
        StringBuilder sb=new StringBuilder();
        sb.append(gs.ball.r).append(',').append(gs.ball.c).append('|');
        for (int r=0;r<gs.grid.rows;r++)
            for (int c=0;c<gs.grid.cols;c++)
                if (gs.gemPresent[r][c]) sb.append(r).append(',').append(c).append(';');
        return sb.toString();
    }

    // =========================================================================
    //  Strategy 3 – Divide & Conquer
    // =========================================================================
    private List<Integer> divideAndConquerSolve(GameState s) {
        int rows = s.grid.rows, cols = s.grid.cols;

        List<Integer> hFences = new ArrayList<>();
        for (int r=1; r<rows-1; r++) {
            boolean fence=true;
            for (int c=1; c<cols-1; c++) {
                Cell cell=s.grid.cells[r][c];
                if (cell!=Cell.WALL && cell!=Cell.BLOCK && cell!=Cell.STOP) {
                    fence=false; break;
                }
            }
            if (fence) hFences.add(r);
        }

        List<Integer> vFences = new ArrayList<>();
        for (int c=1; c<cols-1; c++) {
            boolean fence=true;
            for (int r=1; r<rows-1; r++) {
                Cell cell=s.grid.cells[r][c];
                if (cell!=Cell.WALL && cell!=Cell.BLOCK && cell!=Cell.STOP) {
                    fence=false; break;
                }
            }
            if (fence) vFences.add(c);
        }

        if (hFences.size() < 2) {
            return bfsSolve(s);
        }

        int hf1 = hFences.get(0);
        int hf2 = hFences.get(1);

        int midR1 = hf1+1, midR2 = hf2-1;
        int vFenceInMid = -1;
        if (!vFences.isEmpty()) vFenceInMid = vFences.get(0);

        List<int[]> regions = new ArrayList<>();
        regions.add(new int[]{1,      hf1-1, 1, cols-2});
        if (vFenceInMid > 0 && vFenceInMid < cols-1) {
            regions.add(new int[]{midR1, midR2, 1,            vFenceInMid-1});
            regions.add(new int[]{midR1, midR2, vFenceInMid+1, cols-2});
        } else {
            regions.add(new int[]{midR1, midR2, 1, cols-2});
        }
        regions.add(new int[]{hf2+1, rows-2, 1, cols-2});

        GameState cur = cloneState(s);
        List<Integer> fullPlan = new ArrayList<>();

        for (int[] reg : regions) {
            int rMin=reg[0], rMax=reg[1], cMin=reg[2], cMax=reg[3];
            if (rMin > rMax || cMin > cMax) continue;

            int subGems = 0;
            for (int r=rMin; r<=rMax; r++)
                for (int c=cMin; c<=cMax; c++)
                    if (cur.gemPresent[r][c]) subGems++;
            if (subGems == 0) continue;

            boolean[][] savedGems = GameState.deepCopy(cur.gemPresent);
            int savedCollected    = cur.gemsCollected;
            int savedTotal        = cur.totalGems;

            for (int r=0; r<cur.grid.rows; r++)
                for (int c=0; c<cur.grid.cols; c++)
                    if (cur.gemPresent[r][c] && !(r>=rMin&&r<=rMax&&c>=cMin&&c<=cMax))
                        cur.gemPresent[r][c] = false;

            cur.totalGems     = subGems;
            cur.gemsCollected = 0;

            List<Integer> subPlan = bfsSolve(cur);

            cur.gemPresent    = savedGems;
            cur.gemsCollected = savedCollected;
            cur.totalGems     = savedTotal;

            if (subPlan == null) return bfsSolve(s);

            fullPlan.addAll(subPlan);
            for (int di : subPlan)
                slideFrom(cur, cur.ball, DIRS[di], true, false);
        }

        return fullPlan;
    }

    // =========================================================================
    //  Strategy 4 – Dynamic Programming
    // =========================================================================
    private List<Integer> dynamicProgrammingSolve(GameState s) {
        List<Vec> gems = new ArrayList<>();
        for (int r=0;r<s.grid.rows;r++)
            for (int c=0;c<s.grid.cols;c++)
                if (s.gemPresent[r][c]) gems.add(new Vec(r,c));
        int n=gems.size();
        if (n==0) return Collections.emptyList();
        if (n>14) return bfsSolve(s);

        Vec[] nodes=new Vec[n+1]; nodes[0]=s.ball;
        for (int i=0;i<n;i++) nodes[i+1]=gems.get(i);

        int[][] dist=new int[n+1][n+1];
        for (int i=0;i<=n;i++) { Arrays.fill(dist[i],Integer.MAX_VALUE/2); dist[i][i]=0; }
        for (int i=0;i<=n;i++)
            for (int j=i+1;j<=n;j++) {
                int d=slideDistance(s.grid,nodes[i],nodes[j]);
                dist[i][j]=dist[j][i]=d;
            }

        int full=(1<<n)-1;
        int[][] dp=new int[1<<n][n], par=new int[1<<n][n];
        for (int[] row:dp) Arrays.fill(row,Integer.MAX_VALUE/2);
        for (int[] row:par) Arrays.fill(row,-1);
        for (int i=0;i<n;i++) dp[1<<i][i]=dist[0][i+1];

        for (int mask=1;mask<=full;mask++) {
            for (int last=0;last<n;last++) {
                if ((mask&(1<<last))==0||dp[mask][last]>=Integer.MAX_VALUE/2) continue;
                for (int next=0;next<n;next++) {
                    if ((mask&(1<<next))!=0) continue;
                    int nm=mask|(1<<next), nd=dp[mask][last]+dist[last+1][next+1];
                    if (nd<dp[nm][next]) { dp[nm][next]=nd; par[nm][next]=last; }
                }
            }
        }

        int bestLast=-1, bestCost=Integer.MAX_VALUE;
        for (int i=0;i<n;i++) if (dp[full][i]<bestCost) { bestCost=dp[full][i]; bestLast=i; }
        if (bestLast==-1) return bfsSolve(s);

        List<Integer> order=new ArrayList<>();
        int mask=full, cur=bestLast;
        while (cur!=-1) { order.add(cur); int p=par[mask][cur]; mask^=(1<<cur); cur=p; }
        Collections.reverse(order);

        GameState gs=cloneState(s);
        List<Integer> fullPlan=new ArrayList<>();
        for (int idx:order) {
            if (!gs.gemPresent[gems.get(idx).r][gems.get(idx).c]) continue;
            List<Integer> seg=bfsSolve(gs);
            if (seg==null) return bfsSolve(s);
            fullPlan.addAll(seg);
            for (int di:seg) slideFrom(gs,gs.ball,DIRS[di],true,false);
            if (gs.allGemsCollected()) break;
        }
        return gs.allGemsCollected() ? fullPlan : bfsSolve(s);
    }

    private int slideDistance(Grid grid, Vec from, Vec to) {
        if (from.equals(to)) return 0;
        boolean[][] vis=new boolean[grid.rows][grid.cols];
        ArrayDeque<int[]> q=new ArrayDeque<>();
        vis[from.r][from.c]=true; q.add(new int[]{from.r,from.c,0});
        while (!q.isEmpty()) {
            int[] cur=q.poll();
            for (Vec d:DIRS) {
                int cr=cur[0], cc=cur[1];
                while (true) {
                    int nr=cr+d.r, nc=cc+d.c;
                    if (nr<0||nr>=grid.rows||nc<0||nc>=grid.cols) break;
                    Vec nv=new Vec(nr,nc);
                    if (grid.isWall(nv)) break;
                    cr=nr; cc=nc;
                    if (cr==to.r&&cc==to.c) return cur[2]+1;
                    if (!vis[cr][cc]) { vis[cr][cc]=true; q.add(new int[]{cr,cc,cur[2]+1}); }
                    Cell cell=grid.get(new Vec(cr,cc));
                    if (cell==Cell.STOP||cell==Cell.MINE) break;
                }
            }
        }
        return Integer.MAX_VALUE/2;
    }

    // =========================================================================
    //  Strategy 5 – Backtracking
    // =========================================================================
    private List<Integer> backtrackingSolve(GameState s) {
        List<Integer> bfsRef=bfsSolve(s);
        if (bfsRef==null) return null;
        List<Integer> best=new ArrayList<>(bfsRef);
        int[] bestLen={bfsRef.size()};

        Deque<BTFrame> stack=new ArrayDeque<>();
        stack.push(new BTFrame(cloneState(s), new ArrayList<>(), new HashSet<>()));
        int iters=0; final int MAX=250_000;

        while (!stack.isEmpty() && iters++<MAX) {
            BTFrame frame=stack.peek();
            GameState gs=frame.gs;
            if (gs.allGemsCollected()) {
                if (frame.path.size()<bestLen[0]) {
                    best=new ArrayList<>(frame.path); bestLen[0]=best.size();
                }
                stack.pop(); continue;
            }
            if (frame.path.size()>=bestLen[0]-1) { stack.pop(); continue; }
            String key=stateKey(gs);
            if (frame.visited.contains(key)) { stack.pop(); continue; }
            frame.visited.add(key);

            boolean pushed=false;
            while (frame.nextDir<DIRS.length) {
                int di=frame.nextDir++;
                GameState next=cloneState(gs);
                boolean died=slideFrom(next,next.ball,DIRS[di],true,false);
                if (died||next.ball.equals(gs.ball)) continue;
                List<Integer> np=new ArrayList<>(frame.path); np.add(di);
                stack.push(new BTFrame(next,np,new HashSet<>(frame.visited)));
                pushed=true; break;
            }
            if (!pushed) stack.pop();
        }
        return best;
    }

    static class BTFrame {
        GameState gs; List<Integer> path; Set<String> visited; int nextDir=0;
        BTFrame(GameState gs,List<Integer> path,Set<String> visited) {
            this.gs=gs; this.path=path; this.visited=visited;
        }
    }

    // =========================================================================
    //  Status
    // =========================================================================
    private void updateStatus() {
        if (gameOver) {
            if (state.allGemsCollected())
                status.setText(String.format("Gems: %d/%d  |  Moves: %d  |  ✅ COMPLETED  [%s]",
                    state.gemsCollected,state.totalGems,movesThisSolve,strategyName(solverStrategy)));
            else
                status.setText("❌ GAME OVER");
        } else {
            String t=(turn==Turn.SOLVING)?"Solving…":"Ready";
            status.setText(String.format("Gems: %d/%d  |  Moves: %d  |  %s  [%s]",
                state.gemsCollected,state.totalGems,movesThisSolve,t,strategyName(solverStrategy)));
        }
    }

    static String strategyName(int s) {
        switch(s) {
            case 1: return "BFS";
            case 2: return "Greedy";
            case 3: return "Divide & Conquer";
            case 4: return "Dynamic Programming";
            case 5: return "Backtracking";
            default: return "Unknown";
        }
    }

    // =========================================================================
    //  Board rendering
    // =========================================================================
    static final class BoardPanel extends JPanel {
        GameState state;
        final int cellSize = 48;
        final int pad      = 16;
        int originX=16, originY=16;

        BoardPanel(GameState s) { this.state=s; setBackground(Color.WHITE); }
        void setState(GameState s) { this.state=s; }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2=(Graphics2D)g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);

            int rows=state.grid.rows, cols=state.grid.cols;
            originX=Math.max(pad,(getWidth() -cols*cellSize)/2);
            originY=Math.max(pad,(getHeight()-rows*cellSize)/2);

            for (int r=0;r<rows;r++) {
                for (int c=0;c<cols;c++) {
                    int x=originX+c*cellSize, y=originY+r*cellSize;
                    // light floor tile
                    g2.setColor(new Color(245,245,245)); g2.fillRect(x,y,cellSize,cellSize);
                    g2.setColor(new Color(220,220,220)); g2.drawRect(x,y,cellSize,cellSize);

                    Cell cell=state.grid.cells[r][c];
                    switch(cell) {
                        case WALL:  drawWall(g2,x,y);  break;
                        case BLOCK: drawBlock(g2,x,y); break;
                        case MINE:  drawMine(g2,x,y);  break;
                        case STOP:  drawStop(g2,x,y);  break;
                        default: break;
                    }
                    if (state.gemPresent[r][c]) drawGem(g2,x,y);
                }
            }

            Inertia outer=(Inertia)SwingUtilities.getWindowAncestor(this);
            if (outer!=null && outer.showExplosion && outer.explosionCenter!=null) {
                Vec e=outer.explosionCenter;
                drawExplosion(g2,originX+e.c*cellSize,originY+e.r*cellSize);
            }
            drawBall(g2,state.ball);
        }

        private void drawWall(Graphics2D g2, int x, int y) {
            g2.setColor(new Color(80,80,80));
            g2.fillRect(x,y,cellSize,cellSize);
        }

        private void drawBlock(Graphics2D g2, int x, int y) {
            int s=cellSize, b=Math.max(3,s/10);
            g2.setColor(new Color(200,200,200)); g2.fillRect(x,y,s,s);
            g2.setColor(new Color(220,220,220)); g2.fillRect(x+4,y+4,s-8,s-8);
            g2.setColor(Color.WHITE); g2.fillRect(x,y,s,b); g2.fillRect(x,y,b,s);
            g2.setColor(new Color(180,180,180)); g2.fillRect(x+s-b,y,b,s); g2.fillRect(x,y+s-b,s,b);
        }

        private void drawMine(Graphics2D g2, int x, int y) {
            int s=cellSize, cx=x+s/2, cy=y+s/2, r=s/2-8;
            // body
            g2.setColor(Color.BLACK); g2.fillOval(cx-r,cy-r,r*2,r*2);
            GradientPaint gp=new GradientPaint(cx-r,cy-r,new Color(90,90,90),
                                               cx+r,cy+r,new Color(20,20,20));
            Paint oldp=g2.getPaint();
            g2.setPaint(gp); g2.fillOval(cx-r/2,cy-r/2,r,r);
            g2.setPaint(oldp);
            g2.setColor(new Color(70,70,70)); g2.setStroke(new BasicStroke(2f));
            g2.drawOval(cx-r,cy-r,r*2,r*2);
            // fuse
            int fx1=cx+r-2, fy1=cy-r+6, fx2=fx1+12, fy2=fy1-10;
            g2.setStroke(new BasicStroke(3f)); g2.setColor(new Color(120,80,30));
            g2.drawLine(fx1,fy1,fx2,fy2);
            // flame
            Polygon flame=new Polygon();
            flame.addPoint(fx2+3,fy2); flame.addPoint(fx2+9,fy2-5); flame.addPoint(fx2+3,fy2-10);
            g2.setColor(new Color(255,160,20)); g2.fillPolygon(flame);
            g2.setColor(new Color(200,90,10)); g2.setStroke(new BasicStroke(1f)); g2.drawPolygon(flame);
        }

        private void drawStop(Graphics2D g2, int x, int y) {
            int s=cellSize, cx=x+s/2, cy=y+s/2, r=s/2-6;
            g2.setColor(new Color(160,160,160));
            g2.setStroke(new BasicStroke(3f));
            g2.drawOval(cx-r,cy-r,r*2,r*2);
            g2.fillOval(cx-3,cy-3,7,7);
        }

        private void drawGem(Graphics2D g2, int x, int y) {
            int s=cellSize;
            Polygon p=new Polygon();
            p.addPoint(x+s/2, y+6);
            p.addPoint(x+s-6, y+s/2);
            p.addPoint(x+s/2, y+s-6);
            p.addPoint(x+6,   y+s/2);
            g2.setColor(new Color(60,140,230)); g2.fillPolygon(p);
            g2.setColor(new Color(25,90,170)); g2.setStroke(new BasicStroke(2f)); g2.drawPolygon(p);
        }

        private void drawBall(Graphics2D g2, Vec pos) {
            int px=originX+pos.c*cellSize+cellSize/2;
            int py=originY+pos.r*cellSize+cellSize/2;
            int rad=cellSize/2-6;
            // shadow
            g2.setColor(new Color(240,240,240));
            g2.fillOval(px-rad-4,py-rad-4,(rad+4)*2,(rad+4)*2);
            // fill
            g2.setColor(new Color(30,180,90)); g2.fillOval(px-rad,py-rad,rad*2,rad*2);
            // border
            g2.setColor(new Color(10,120,60)); g2.setStroke(new BasicStroke(3f));
            g2.drawOval(px-rad,py-rad,rad*2,rad*2);
        }

        private void drawExplosion(Graphics2D g2, int cx0, int cy0) {
            int cx=cx0+cellSize/2, cy=cy0+cellSize/2, R=cellSize;
            Composite old=g2.getComposite();
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,0.85f));
            g2.setColor(new Color(220,30,30)); g2.fillOval(cx-R/2,cy-R/2,R,R);
            g2.setColor(new Color(255,155,0)); g2.setStroke(new BasicStroke(3f));
            for (int a=0;a<360;a+=30) {
                double rad=Math.toRadians(a);
                g2.drawLine(cx,cy,cx+(int)(Math.cos(rad)*R),cy+(int)(Math.sin(rad)*R));
            }
            g2.setComposite(old);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(state.grid.cols*cellSize+pad*2,
                                 state.grid.rows*cellSize+pad*2);
        }
    }

    // =========================================================================
    //  Level
    // =========================================================================
    static final class Level {
        final String[] rows;
        Level(String[] rows) { this.rows = rows; }

        GameState toGameState() {
            int R=rows.length, C=rows[0].length();
            Cell[][] cells=new Cell[R][C];
            boolean[][] gem=new boolean[R][C];
            Vec start=null; int gems=0;
            for (int r=0;r<R;r++) {
                for (int c=0;c<C;c++) {
                    char ch=rows[r].charAt(c);
                    Cell cell;
                    switch(ch) {
                        case '#': cell=Cell.WALL;  break;
                        case 'B': cell=Cell.BLOCK; break;
                        case '*': cell=Cell.MINE;  break;
                        case 'O': cell=Cell.STOP;  break;
                        case 'G': cell=Cell.EMPTY; gem[r][c]=true; gems++; break;
                        case 'S': cell=Cell.STOP;  start=new Vec(r,c); break;
                        default:  cell=Cell.EMPTY; break;
                    }
                    cells[r][c]=cell;
                }
            }
            if (start==null) throw new IllegalStateException("No 'S' in level");
            return new GameState(new Grid(cells), start, gem, gems);
        }

        static final int ROWS=10, COLS=12;
        static final int HF1=3, HF2=7, VF=6;

        static Level generateRandomLevel() {
            Random rand=new Random();
            for (int attempt=0; attempt<80; attempt++) {
                char[][] g=tryBuild(rand);
                if (g==null) continue;
                String[] map=new String[ROWS];
                for (int r=0;r<ROWS;r++) map[r]=new String(g[r]);
                Level lv=new Level(map);
                if (bfsVerify(lv.toGameState())) return lv;
            }
            return fallbackLevel();
        }

        private static char[][] tryBuild(Random rand) {
            char[][] g=new char[ROWS][COLS];
            for (char[] row:g) Arrays.fill(row,' ');

            for (int r=0;r<ROWS;r++) { g[r][0]='#'; g[r][COLS-1]='#'; }
            for (int c=0;c<COLS;c++) { g[0][c]='#'; g[ROWS-1][c]='#'; }

            for (int c=1;c<COLS-1;c++) g[HF1][c]='B';
            int gate1 = 1 + rand.nextInt(COLS-2);
            g[HF1][gate1]='O';

            for (int c=1;c<COLS-1;c++) g[HF2][c]='B';
            int gate2 = 1 + rand.nextInt(COLS-2);
            g[HF2][gate2]='O';

            for (int r=HF1+1;r<HF2;r++) g[r][VF]='B';
            int vGateRow = HF1+1 + rand.nextInt(HF2-HF1-1);
            g[vGateRow][VF]='O';

            int[][] topCells    = regionCells(1,  2, 1, 10,  g, ' ');
            int[][] midLCells   = regionCells(4,  6, 1,  5,  g, ' ');
            int[][] midRCells   = regionCells(4,  6, 7, 10,  g, ' ');
            int[][] botCells    = regionCells(8,  8, 1, 10,  g, ' ');

            if (topCells.length<8||midLCells.length<5||midRCells.length<4||botCells.length<4)
                return null;

            placeRandom(g, topCells,  3+rand.nextInt(2), 'O', rand);
            placeRandom(g, midLCells, 2+rand.nextInt(2), 'O', rand);
            placeRandom(g, midRCells, 2+rand.nextInt(2), 'O', rand);
            placeRandom(g, botCells,  2+rand.nextInt(2), 'O', rand);

            int[][] topEmpty = regionCells(1, 2, 1, 10, g, ' ');
            if (topEmpty.length==0) return null;
            int[] sc=topEmpty[rand.nextInt(topEmpty.length)];
            g[sc[0]][sc[1]]='S';

            int gemsTarget = 12 + rand.nextInt(5);
            int[] regGems = distributeAcross(gemsTarget, 4, rand);

            if (!placeGemsScattered(g, 1,  2, 1, 10, regGems[0], rand)) return null;
            if (!placeGemsScattered(g, 4,  6, 1,  5, regGems[1], rand)) return null;
            if (!placeGemsScattered(g, 4,  6, 7, 10, regGems[2], rand)) return null;
            if (!placeGemsScattered(g, 8,  8, 1, 10, regGems[3], rand)) return null;

            int minesTarget = 8 + rand.nextInt(5);
            int[] regMines = distributeAcross(minesTarget, 4, rand);

            if (!placeMinesScattered(g, 1, 2,  1, 10, regMines[0], rand)) return null;
            if (!placeMinesScattered(g, 4, 6,  1,  5, regMines[1], rand)) return null;
            if (!placeMinesScattered(g, 4, 6,  7, 10, regMines[2], rand)) return null;
            if (!placeMinesScattered(g, 8, 8,  1, 10, regMines[3], rand)) return null;

            int blockTarget = 6 + rand.nextInt(5);
            int[][] allEmpty = regionCells(1, ROWS-2, 1, COLS-2, g, ' ');
            List<int[]> blockCands = new ArrayList<>();
            for (int[] cell:allEmpty)
                if (cell[0]!=HF1 && cell[0]!=HF2)
                    blockCands.add(cell);
            Collections.shuffle(blockCands, rand);
            int placed=0;
            Set<Integer> usedBCols=new HashSet<>();
            for (int[] cell:blockCands) {
                if (placed>=blockTarget) break;
                if (!usedBCols.contains(cell[1])) {
                    g[cell[0]][cell[1]]='B'; usedBCols.add(cell[1]); placed++;
                }
            }

            g[sc[0]][sc[1]]='S';

            return g;
        }

        private static int[][] regionCells(int rMin,int rMax,int cMin,int cMax,
                                           char[][] g, char ch) {
            List<int[]> list=new ArrayList<>();
            for (int r=rMin;r<=rMax;r++)
                for (int c=cMin;c<=cMax;c++)
                    if (g[r][c]==ch) list.add(new int[]{r,c});
            return list.toArray(new int[0][]);
        }

        private static void placeRandom(char[][] g, int[][] cands, int count,
                                        char ch, Random rand) {
            List<int[]> list=new ArrayList<>(Arrays.asList(cands));
            Collections.shuffle(list, rand);
            int placed=0;
            for (int[] cell:list) {
                if (placed>=count) break;
                if (g[cell[0]][cell[1]]==' ') { g[cell[0]][cell[1]]=ch; placed++; }
            }
        }

        private static int[] distributeAcross(int total, int n, Random rand) {
            int[] arr=new int[n];
            Arrays.fill(arr,1);
            int remaining=total-n;
            for (int i=0;i<remaining;i++) arr[rand.nextInt(n)]++;
            return arr;
        }

        private static boolean placeGemsScattered(char[][] g,
                                                   int rMin,int rMax,int cMin,int cMax,
                                                   int count, Random rand) {
            int[][] cands=regionCells(rMin,rMax,cMin,cMax,g,' ');
            if (cands.length<count) return false;
            List<int[]> list=new ArrayList<>(Arrays.asList(cands));
            Collections.shuffle(list, rand);
            int placed=0;
            Set<Integer> usedR=new HashSet<>(), usedC=new HashSet<>();
            for (int[] cell:list) {
                if (placed>=count) break;
                if (!usedR.contains(cell[0]) || !usedC.contains(cell[1])) {
                    g[cell[0]][cell[1]]='G'; usedR.add(cell[0]); usedC.add(cell[1]); placed++;
                }
            }
            for (int[] cell:list) {
                if (placed>=count) break;
                if (g[cell[0]][cell[1]]==' ') { g[cell[0]][cell[1]]='G'; placed++; }
            }
            return placed>=count;
        }

        private static boolean placeMinesScattered(char[][] g,
                                                   int rMin,int rMax,int cMin,int cMax,
                                                   int count, Random rand) {
            int[][] cands=regionCells(rMin,rMax,cMin,cMax,g,' ');
            if (cands.length<count) return false;
            List<int[]> list=new ArrayList<>(Arrays.asList(cands));
            Collections.shuffle(list, rand);
            int placed=0;
            Set<Integer> usedC=new HashSet<>();
            for (int[] cell:list) {
                if (placed>=count) break;
                if (!usedC.contains(cell[1])) {
                    g[cell[0]][cell[1]]='*'; usedC.add(cell[1]); placed++;
                }
            }
            for (int[] cell:list) {
                if (placed>=count) break;
                if (g[cell[0]][cell[1]]==' ') { g[cell[0]][cell[1]]='*'; placed++; }
            }
            return true;
        }

        static boolean bfsVerify(GameState s) {
            int rows=s.grid.rows, cols=s.grid.cols;
            int[][] gemIdx=new int[rows][cols];
            for (int[] row:gemIdx) Arrays.fill(row,-1);
            int gc=0;
            for (int r=0;r<rows;r++)
                for (int c=0;c<cols;c++)
                    if (s.gemPresent[r][c]&&gc<20) gemIdx[r][c]=gc++;
            if (gc==0) return true;
            int full=(1<<gc)-1;

            Set<Long> vis=new HashSet<>();
            ArrayDeque<long[]> q=new ArrayDeque<>();
            int sr=s.ball.r, sc=s.ball.c;
            int sm=0; if(gemIdx[sr][sc]!=-1) sm|=1<<gemIdx[sr][sc];
            long startE=enc(sr,sc,sm);
            vis.add(startE); q.add(new long[]{sr,sc,sm});

            while (!q.isEmpty()) {
                long[] cur=q.poll();
                int cr=(int)cur[0], cc=(int)cur[1], cm=(int)cur[2];
                if (cm==full) return true;
                for (Vec d:DIRS) {
                    int pr=cr, pc=cc, pm=cm; boolean moved=false, died=false;
                    while (true) {
                        int nr=pr+d.r, nc=pc+d.c;
                        if (nr<0||nr>=rows||nc<0||nc>=cols) break;
                        Vec nv=new Vec(nr,nc);
                        if (s.grid.isWall(nv)) break;
                        moved=true; pr=nr; pc=nc;
                        Cell cell=s.grid.get(new Vec(pr,pc));
                        if (cell==Cell.MINE){died=true;break;}
                        int gi=gemIdx[pr][pc]; if(gi!=-1) pm|=(1<<gi);
                        if (cell==Cell.STOP) break;
                    }
                    if (!moved||died) continue;
                    long ne=enc(pr,pc,pm);
                    if (!vis.contains(ne)) { vis.add(ne); q.add(new long[]{pr,pc,pm}); }
                }
            }
            return false;
        }

        private static long enc(int r, int c, int mask) {
            return ((long)r<<27)|((long)c<<20)|(mask&0xFFFFF);
        }

        private static Level fallbackLevel() {
            String[] map = {
                "############",
                "#S G  O  G #",
                "#O  G  * G #",
                "#BBBOBBBBBBB",
                "#G O B O G #",
                "#  G B  *  #",
                "#* O O G   #",
                "#BBBBBOBBBB#",
                "#G  O  G * #",
                "############"
            };
            return new Level(map);
        }
    }
}
