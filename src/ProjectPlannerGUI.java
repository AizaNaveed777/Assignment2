import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit; // ✅ correct import
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Project Planner GUI — Assignment 2 (Fall 2025)
 *
 * Single-file Java Swing app implementing:
 * 1) Form to create a project, add tasks & resources (tabular view + CSV upload)
 * 2) Analyze options: completion time, overlaps, resources/teams, effort breakdown
 * 3) Visualize option: Gantt Chart
 * 4) Integrated, easy-to-navigate UI
 *
 * CSV formats supported
 *  - Tasks: id,name,start,end,dependencies,resources
 *      e.g. 1,Initial research,20250915+0800,20251010+1800,,Ahmed*|Ayesha*|Mariam
 *      - start/end accept: 20251013, 20251013+0800, 202510130800
 *      - dependencies: comma/semicolon/space-separated IDs (e.g., "1,3" or "1 3")
 *      - resources: pipe- or comma-separated names; add * to mark partial allocation (0.5)
 *
 *  - Resources (optional): name,allocationFraction
 *      e.g. Ahmed,1.0  or  Ayesha,0.5
 */
public class ProjectPlannerGUI extends JFrame {
    // ----- Models ------------------------------------------------------------
    static class Task {
        int id;
        String name;
        LocalDateTime start;
        LocalDateTime end;
        List<Integer> dependencies = new ArrayList<>();
        List<ResourceRef> resources = new ArrayList<>();

        long durationHours() { return Duration.between(start, end).toHours(); }
    }

    static class ResourceRef {
        String name;
        double allocation; // 1.0 = full, 0.5 = partial ("*")
        ResourceRef(String name, double allocation) { this.name = name; this.allocation = allocation; }
        public String toString(){ return allocation == 1.0 ? name : name + "*"; }
    }

    static class ResourceCatalog {
        private final Map<String, Double> overrides = new HashMap<>();
        void put(String name, double allocation){ overrides.put(name.trim(), allocation); }
        double resolve(String name, boolean hasAsterisk){
            if (overrides.containsKey(name.trim())) return overrides.get(name.trim());
            return hasAsterisk ? 0.5 : 1.0;
        }
        void clear(){ overrides.clear(); }
    }

    // ----- UI State ---------------------------------------------------------
    private final JTextField projectTitle = new JTextField();
    private final DefaultTableModel tableModel;
    private final JTable taskTable;
    private final JTextArea analysisOutput = new JTextArea(16, 80);
    private final JRadioButton rbCompletion = new JRadioButton("Project completion time and duration", true);
    private final JRadioButton rbOverlaps = new JRadioButton("Overlapping tasks");
    private final JRadioButton rbTeams = new JRadioButton("Resources and teams");
    private final JRadioButton rbEffort = new JRadioButton("Effort breakdown: Resource-wise");
    private final GanttPanel ganttPanel = new GanttPanel();
    private final ResourceCatalog resourceCatalog = new ResourceCatalog();

    public ProjectPlannerGUI(){
        super("Project Planner — Assignment 2");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1100, 700));

        // --- Table
        String[] cols = {"Id", "Task", "Start", "End", "Dependencies", "Resources"};
        tableModel = new DefaultTableModel(cols, 0){
            @Override public boolean isCellEditable(int row, int column) { return true; }
        };
        taskTable = new JTable(tableModel);
        taskTable.setFillsViewportHeight(true);
        taskTable.setRowHeight(24);
        taskTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        tableModel.addTableModelListener(e -> ganttPanel.setTasks(extractTasks()));

        // --- Top bar
        JPanel top = new JPanel(new BorderLayout(8,8));
        top.setBorder(new EmptyBorder(8,8,8,8));
        JPanel leftBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton btnNew = new JButton("New");
        JButton btnSave = new JButton("Save");
        JButton btnClose = new JButton("Close");
        leftBtns.add(btnNew); leftBtns.add(btnSave); leftBtns.add(btnClose);
        top.add(leftBtns, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.add(new JLabel("Project:"));
        projectTitle.setPreferredSize(new Dimension(280, 28));
        right.add(projectTitle);
        top.add(right, BorderLayout.EAST);

        // --- Mid action bar
        JPanel actionBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        JButton btnUploadTasks = new JButton("Upload Tasks");
        JButton btnUploadResources = new JButton("Upload Resources");
        JButton btnAnalyze = new JButton("Analyze");
        JButton btnVisualize = new JButton("Visualize");
        actionBar.add(btnUploadTasks);
        actionBar.add(btnUploadResources);
        actionBar.add(btnAnalyze);
        actionBar.add(btnVisualize);

        // --- Center: Table in scroll
        JScrollPane tableScroll = new JScrollPane(taskTable);

        // --- Tabs
        JTabbedPane tabs = new JTabbedPane();
        // Tab 1: Editor
        JPanel editor = new JPanel(new BorderLayout());
        editor.add(top, BorderLayout.NORTH);
        editor.add(actionBar, BorderLayout.CENTER);
        editor.add(tableScroll, BorderLayout.SOUTH);
        tabs.addTab("Project & Tasks", editor);

        // Tab 2: Analysis
        JPanel analysis = new JPanel(new BorderLayout(8,8));
        analysis.setBorder(new EmptyBorder(8,8,8,8));
        JPanel rbPanel = new JPanel(new GridLayout(0,1));
        ButtonGroup bg = new ButtonGroup();
        for (JRadioButton rb : new JRadioButton[]{rbCompletion, rbOverlaps, rbTeams, rbEffort}){ bg.add(rb); rbPanel.add(rb); }
        analysis.add(rbPanel, BorderLayout.NORTH);
        analysisOutput.setEditable(false);
        analysisOutput.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        analysis.add(new JScrollPane(analysisOutput), BorderLayout.CENTER);
        tabs.addTab("Analyze", analysis);

        // Tab 3: Visualization (Gantt)
        JPanel viz = new JPanel(new BorderLayout());
        viz.add(ganttPanel, BorderLayout.CENTER);
        tabs.addTab("Visualize", viz);

        setLayout(new BorderLayout());
        add(tabs, BorderLayout.CENTER);

        // --- Wiring buttons
        btnNew.addActionListener(e -> doNew());
        btnSave.addActionListener(e -> doSave());
        btnClose.addActionListener(e -> dispose());
        btnUploadTasks.addActionListener(e -> doUploadTasks());
        btnUploadResources.addActionListener(e -> doUploadResources());
        btnAnalyze.addActionListener(e -> doAnalyze());
        btnVisualize.addActionListener(e -> { ganttPanel.setTasks(extractTasks()); tabs.setSelectedComponent(viz); });

        // Seed example
        seedExample();
        ganttPanel.setTasks(extractTasks());
        setLocationRelativeTo(null);
    }

    // ----- Actions ----------------------------------------------------------
    private void doNew(){
        projectTitle.setText("");
        tableModel.setRowCount(0);
        resourceCatalog.clear();
        analysisOutput.setText("");
        ganttPanel.setTasks(extractTasks());
    }

    private void doSave(){
        JFileChooser ch = new JFileChooser();
        ch.setDialogTitle("Save project as CSV");
        ch.setFileFilter(new FileNameExtensionFilter("CSV Files", "csv"));
        if (ch.showSaveDialog(this) == JFileChooser.APPROVE_OPTION){
            File f = ch.getSelectedFile();
            if (!f.getName().toLowerCase().endsWith(".csv")) f = new File(f.getParentFile(), f.getName()+".csv");
            try(PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))){
                pw.println("# Project Title," + escapeCsv(projectTitle.getText()));
                pw.println("id,name,start,end,dependencies,resources");
                for (int r=0; r<tableModel.getRowCount(); r++){
                    String id = String.valueOf(tableModel.getValueAt(r,0));
                    String name = String.valueOf(tableModel.getValueAt(r,1));
                    String start = String.valueOf(tableModel.getValueAt(r,2));
                    String end = String.valueOf(tableModel.getValueAt(r,3));
                    String deps = String.valueOf(tableModel.getValueAt(r,4));
                    String res = String.valueOf(tableModel.getValueAt(r,5));
                    pw.println(String.join(",",
                            escapeCsv(id), escapeCsv(name), escapeCsv(start), escapeCsv(end),
                            escapeCsv(deps), escapeCsv(res)));
                }
            } catch (IOException ex){ showError("Failed to save: " + ex.getMessage()); }
        }
    }

    private void doUploadTasks(){
        JFileChooser ch = new JFileChooser();
        ch.setDialogTitle("Upload Tasks CSV");
        ch.setFileFilter(new FileNameExtensionFilter("CSV Files", "csv"));
        if (ch.showOpenDialog(this) == JFileChooser.APPROVE_OPTION){
            Path file = ch.getSelectedFile().toPath();
            try {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (String line : lines){
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.toLowerCase().startsWith("id,")) continue;
                    List<String> cells = parseCsvLine(trimmed);
                    if (cells.size() < 6) continue; // skip malformed
                    tableModel.addRow(new Object[]{ cells.get(0), cells.get(1), cells.get(2), cells.get(3), cells.get(4), cells.get(5) });
                }
                ganttPanel.setTasks(extractTasks());
            } catch (Exception ex){ showError("Failed to load tasks: " + ex.getMessage()); }
        }
    }

    private void doUploadResources(){
        JFileChooser ch = new JFileChooser();
        ch.setDialogTitle("Upload Resources CSV");
        ch.setFileFilter(new FileNameExtensionFilter("CSV Files", "csv"));
        if (ch.showOpenDialog(this) == JFileChooser.APPROVE_OPTION){
            Path file = ch.getSelectedFile().toPath();
            try {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                int count = 0;
                for (String line : lines){
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                    List<String> cells = parseCsvLine(trimmed);
                    if (cells.size() >= 2){
                        String name = cells.get(0).trim();
                        double alloc = Double.parseDouble(cells.get(1).trim());
                        resourceCatalog.put(name, alloc);
                        count++;
                    }
                }
                JOptionPane.showMessageDialog(this, "Resources loaded/overridden: " + count);
                ganttPanel.setTasks(extractTasks());
            } catch (Exception ex){ showError("Failed to load resources: " + ex.getMessage()); }
        }
    }

    private void doAnalyze(){
        List<Task> tasks = extractTasks();
        if (tasks.isEmpty()){ analysisOutput.setText("No tasks available. Please add or upload tasks first."); return; }
        if (rbCompletion.isSelected()){
            LocalDateTime minStart = tasks.stream().map(t->t.start).min(LocalDateTime::compareTo).get();
            LocalDateTime maxEnd = tasks.stream().map(t->t.end).max(LocalDateTime::compareTo).get();
            long totalHours = Duration.between(minStart, maxEnd).toHours();
            analysisOutput.setText(String.format(
                    "Project completion window\nStart: %s\nEnd:   %s\nDuration: %d hours (%.2f days)",
                    minStart, maxEnd, totalHours, totalHours/24.0));
        } else if (rbOverlaps.isSelected()){
            StringBuilder sb = new StringBuilder("Overlapping tasks (pairs):\n");
            boolean found = false;
            for (int i=0;i<tasks.size();i++){
                for (int j=i+1;j<tasks.size();j++){
                    Task a = tasks.get(i), b = tasks.get(j);
                    if (rangesOverlap(a.start, a.end, b.start, b.end)){
                        found = true;
                        sb.append(String.format("- #%d %s  ↔  #%d %s\n", a.id, a.name, b.id, b.name));
                    }
                }
            }
            if (!found) sb.append("(none)");
            analysisOutput.setText(sb.toString());
        } else if (rbTeams.isSelected()){
            Map<String, List<Task>> byRes = mapResourceToTasks(tasks);
            StringBuilder sb = new StringBuilder("Resources and teams:\n");
            for (var e : byRes.entrySet()){
                sb.append("\n").append(e.getKey()).append(":\n");
                for (Task t : e.getValue()){
                    sb.append(String.format("  - #%d %s  (%s → %s)\n", t.id, t.name, t.start, t.end));
                }
            }
            analysisOutput.setText(sb.toString());
        } else if (rbEffort.isSelected()){
            Map<String, Double> effort = computeEffortByResource(tasks);
            StringBuilder sb = new StringBuilder("Effort breakdown (resource-wise):\n");
            sb.append(String.format("%-18s %s\n", "Resource", "Effort Hours"));
            sb.append("------------------  ------------\n");
            for (var e : effort.entrySet()){
                sb.append(String.format("%-18s  %.2f\n", e.getKey(), e.getValue()));
            }
            analysisOutput.setText(sb.toString());
        }
    }

    // ----- Helpers ----------------------------------------------------------
    private List<Task> extractTasks(){
        List<Task> list = new ArrayList<>();
        for (int r=0; r<tableModel.getRowCount(); r++){
            try{
                Task t = new Task();
                t.id = parseIntSafe(tableModel.getValueAt(r,0));
                t.name = String.valueOf(tableModel.getValueAt(r,1));
                t.start = parseDateTime(String.valueOf(tableModel.getValueAt(r,2)));
                t.end = parseDateTime(String.valueOf(tableModel.getValueAt(r,3)));
                if (!t.end.isAfter(t.start)) throw new IllegalArgumentException("End must be after Start");
                t.dependencies = parseDependencies(String.valueOf(tableModel.getValueAt(r,4)));
                t.resources = parseResources(String.valueOf(tableModel.getValueAt(r,5)));
                list.add(t);
            } catch (Exception ignored){ }
        }
        list.sort(Comparator.comparing((Task x)->x.start).thenComparingInt(x->x.id));
        return list;
    }

    private static boolean rangesOverlap(LocalDateTime a1, LocalDateTime a2, LocalDateTime b1, LocalDateTime b2){
        return a1.isBefore(b2) && b1.isBefore(a2); // strict overlap
    }

    private Map<String, List<Task>> mapResourceToTasks(List<Task> tasks){
        Map<String, List<Task>> map = new TreeMap<>();
        for (Task t : tasks){
            for (ResourceRef rr : t.resources){ map.computeIfAbsent(rr.name, k->new ArrayList<>()).add(t); }
        }
        return map;
    }

    private Map<String, Double> computeEffortByResource(List<Task> tasks){
        Map<String, Double> effort = new TreeMap<>();
        for (Task t : tasks){
            double hours = t.durationHours();
            for (ResourceRef rr : t.resources){ effort.merge(rr.name, hours * rr.allocation, Double::sum); }
        }
        return effort;
    }

    private static int parseIntSafe(Object v){
        try { return Integer.parseInt(String.valueOf(v).trim()); } catch (Exception e){ return 0; }
    }

    private static List<Integer> parseDependencies(String raw){
        if (raw == null) return List.of();
        String s = raw.trim(); if (s.isEmpty()) return List.of();
        String[] parts = s.split("[;,\\s]+");
        List<Integer> ids = new ArrayList<>();
        for (String p : parts){ try { ids.add(Integer.parseInt(p.trim())); } catch (Exception ignored) {} }
        return ids;
    }

    private List<ResourceRef> parseResources(String raw){
        if (raw == null) return List.of();
        String s = raw.trim(); if (s.isEmpty()) return List.of();
        String[] parts = s.split("[|,]+");
        List<ResourceRef> list = new ArrayList<>();
        for (String p : parts){
            String pt = p.trim(); if (pt.isEmpty()) continue;
            boolean hasStar = pt.endsWith("*");
            String name = hasStar ? pt.substring(0, pt.length()-1).trim() : pt;
            double alloc = resourceCatalog.resolve(name, hasStar);
            list.add(new ResourceRef(name, alloc));
        }
        return list;
    }

    private static LocalDateTime parseDateTime(String raw){
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) throw new DateTimeParseException("empty", s, 0);
        String digits = s.replaceAll("[^0-9]", "");
        if (digits.length() == 8){
            return LocalDate.parse(digits, DateTimeFormatter.ofPattern("yyyyMMdd")).atTime(9,0);
        } else if (digits.length() == 12){
            LocalDate d = LocalDate.parse(digits.substring(0,8), DateTimeFormatter.ofPattern("yyyyMMdd"));
            int hh = Integer.parseInt(digits.substring(8,10));
            int mm = Integer.parseInt(digits.substring(10,12));
            return d.atTime(hh, mm);
        } else {
            throw new DateTimeParseException("Unrecognized date format", s, 0);
        }
    }

    private static List<String> parseCsvLine(String line){
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQ = false;
        for (int i=0;i<line.length();i++){
            char c = line.charAt(i);
            if (c=='"'){
                if (inQ && i+1<line.length() && line.charAt(i+1)=='"'){ sb.append('"'); i++; }
                else inQ = !inQ;
            } else if (c==',' && !inQ){ out.add(sb.toString()); sb.setLength(0); }
            else { sb.append(c); }
        }
        out.add(sb.toString());
        return out;
    }

    private static String escapeCsv(String s){
        if (s == null) return "";
        boolean needQuotes = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        String v = s.replace("\"", "\"\"");
        return needQuotes ? '"' + v + '"' : v;
    }

    private void showError(String msg){ JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE); }

    private void seedExample(){
        projectTitle.setText("Sample Program Launch");
        Object[][] rows = {
                {1, "Initial research and analysis", "20250915+0800", "20251010+1800", "", "Ahmed*|Ayesha*|Mariam"},
                {2, "Develop program content and materials", "20251013+0800", "20251031+1159", "1", "Ayesha*|Mariam"},
                {3, "Infrastructure planning", "20251013+0800", "20251017+1800", "1", "Ahmed"},
                {4, "Infrastructure setup and review", "20251020+0800", "20251031+1800", "3", "Ahmed"},
                {5, "Program rollout", "20251103+0900", "20251215+1700", "2,4", "Ahmed*|Mariam"}
        };
        for (Object[] r : rows) tableModel.addRow(r);
        resourceCatalog.put("Ahmed", 1.0);
        resourceCatalog.put("Ayesha", 0.5);
        resourceCatalog.put("Mariam", 1.0);
    }

    // ----- Gantt Panel ------------------------------------------------------
    static class GanttPanel extends JPanel {
        private List<Task> tasks = new ArrayList<>();
        private LocalDate minDate, maxDate;
        private static final int ROW_H = 28;
        private static final int LEFT_PAD = 160;
        private static final int TOP_PAD = 50;
        private static final int DAY_W = 16; // px per day

        GanttPanel(){
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(1000, 600));
            setOpaque(true);
            setToolTipText("Gantt Chart: hover bars for details");
        }

        void setTasks(List<Task> tasks){
            this.tasks = tasks;
            if (tasks.isEmpty()){ minDate = maxDate = null; revalidate(); repaint(); return; }
            minDate = tasks.stream().map(t->t.start.toLocalDate()).min(LocalDate::compareTo).get();
            maxDate = tasks.stream().map(t->t.end.toLocalDate()).max(LocalDate::compareTo).get();
            int days = (int) ChronoUnit.DAYS.between(minDate, maxDate) + 10;
            int height = TOP_PAD + tasks.size()*ROW_H + 80;
            setPreferredSize(new Dimension(LEFT_PAD + days*DAY_W + 200, height));
            revalidate(); repaint();
        }

        @Override protected void paintComponent(Graphics g){
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            if (minDate != null){ drawTimeline(g2); drawRows(g2); }
            else { g2.setColor(Color.DARK_GRAY); g2.drawString("No tasks to visualize.", 20, 30); }
            g2.dispose();
        }

        private void drawTimeline(Graphics2D g2){
            g2.setColor(new Color(245,245,245));
            g2.fillRect(0,0,getWidth(),TOP_PAD);
            g2.setColor(Color.GRAY);
            g2.drawLine(0, TOP_PAD, getWidth(), TOP_PAD);

            LocalDate d = minDate;
            int x = LEFT_PAD;
            g2.setFont(g2.getFont().deriveFont(Font.BOLD));
            while (!d.isAfter(maxDate)){
                g2.setColor(Color.LIGHT_GRAY);
                g2.drawLine(x, TOP_PAD, x, getHeight());
                g2.setColor(Color.DARK_GRAY);
                g2.drawString(d.toString(), x+2, 20);
                x += DAY_W;
                d = d.plusDays(1);
            }
        }

        private void drawRows(Graphics2D g2){
            int y = TOP_PAD + 10;
            for (Task t : tasks){
                g2.setColor(new Color(30,30,30));
                String label = String.format("#%d %s", t.id, t.name);
                g2.drawString(truncate(label, 26), 10, y+14);

                int x1 = LEFT_PAD + (int) ChronoUnit.DAYS.between(minDate, t.start.toLocalDate()) * DAY_W;
                int x2 = LEFT_PAD + (int) ChronoUnit.DAYS.between(minDate, t.end.toLocalDate()) * DAY_W + DAY_W/2;
                int w = Math.max(6, x2 - x1);
                int barY = y;

                g2.setColor(new Color(220,235,255));
                g2.fillRoundRect(x1, barY, w, ROW_H-12, 10,10);
                g2.setColor(new Color(40,120,200));
                g2.drawRoundRect(x1, barY, w, ROW_H-12, 10,10);

                if (!t.dependencies.isEmpty()){
                    g2.setColor(new Color(180,80,80));
                    g2.fillRect(x1-3, barY+5, 3, ROW_H-18);
                }

                int rx = x1 + 6; int ry = barY + (ROW_H-12) - 6;
                g2.setFont(g2.getFont().deriveFont(11f));
                g2.setColor(new Color(20,20,20));
                String res = t.resources.stream().map(ResourceRef::toString).collect(Collectors.joining(", "));
                g2.drawString(res, rx, ry);

                y += ROW_H;
            }
        }

        private static String truncate(String s, int max){ return s.length() <= max ? s : s.substring(0, Math.max(0, max-1)) + "\u2026"; }
    }

    // ----- Main -------------------------------------------------------------
    public static void main(String[] args){ SwingUtilities.invokeLater(() -> new ProjectPlannerGUI().setVisible(true)); }
}
