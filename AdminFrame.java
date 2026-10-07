import java.awt.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Vector;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** Admin console: create elections, posts, candidates and view turnout. */
class AdminFrame extends JFrame {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final JComboBox<Item> electionBox = new JComboBox<>();
    private final JComboBox<Item> postBox = new JComboBox<>();
    private final JTable electionTable = new JTable();
    private final JTable candidateTable = new JTable();
    private final JTable turnoutTable = new JTable();

    AdminFrame(String name) {
        super("Election Admin - " + name);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Elections", electionsTab());
        tabs.addTab("Posts & Candidates", candidatesTab());
        tabs.addTab("Turnout", turnoutTab());
        add(tabs);
        refreshAll();
        setSize(760, 440); setLocationRelativeTo(null);
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        setDefaultCloseOperation(EXIT_ON_CLOSE); setVisible(true);
    }

    private JPanel electionsTab() {
        JTextField title = new JTextField(16), start = new JTextField(11), end = new JTextField(11);
        JButton create = new JButton("Create Election");
        create.addActionListener(e -> {
            try {
                Timestamp s = Timestamp.valueOf(LocalDateTime.parse(start.getText().trim(), FMT));
                Timestamp en = Timestamp.valueOf(LocalDateTime.parse(end.getText().trim(), FMT));
                if (update("INSERT INTO election(title, start_time, end_time) VALUES (?, ?, ?)",
                        title.getText().trim(), s, en)) {
                    title.setText(""); refreshAll();
                }
            } catch (DateTimeParseException ex) {
                JOptionPane.showMessageDialog(this, "Use the format yyyy-MM-dd HH:mm, e.g. 2026-11-05 09:00");
            }
        });
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Title:")); top.add(title);
        top.add(new JLabel("Start (yyyy-MM-dd HH:mm):")); top.add(start);
        top.add(new JLabel("End:")); top.add(end);
        top.add(create);
        JPanel p = new JPanel(new BorderLayout());
        p.add(top, BorderLayout.NORTH);
        p.add(new JScrollPane(electionTable), BorderLayout.CENTER);
        return p;
    }

    private JPanel candidatesTab() {
        JTextField postTitle = new JTextField(14), studentId = new JTextField(8), manifesto = new JTextField(22);
        JButton addPost = new JButton("Add Post"), addCand = new JButton("Add Candidate");

        electionBox.addActionListener(e -> loadPosts());
        postBox.addActionListener(e -> loadCandidates());

        addPost.addActionListener(e -> {
            Item el = (Item) electionBox.getSelectedItem();
            if (el == null) return;
            if (update("INSERT INTO election_post(election_id, post_title) VALUES (?, ?)",
                    el.id(), postTitle.getText().trim())) {
                postTitle.setText(""); loadPosts();
            }
        });
        addCand.addActionListener(e -> {
            Item po = (Item) postBox.getSelectedItem();
            if (po == null) return;
            if (update("INSERT INTO candidate(student_id, post_id, manifesto) VALUES (?, ?, ?)",
                    studentId.getText().trim(), po.id(), manifesto.getText().trim())) {
                studentId.setText(""); manifesto.setText(""); loadCandidates();
            }
        });

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row1.add(new JLabel("Election:")); row1.add(electionBox);
        row1.add(new JLabel("New post:")); row1.add(postTitle); row1.add(addPost);
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row2.add(new JLabel("Post:")); row2.add(postBox);
        row2.add(new JLabel("Student ID:")); row2.add(studentId);
        row2.add(new JLabel("Manifesto:")); row2.add(manifesto); row2.add(addCand);
        JPanel top = new JPanel(new GridLayout(2, 1));
        top.add(row1); top.add(row2);

        JPanel p = new JPanel(new BorderLayout());
        p.add(top, BorderLayout.NORTH);
        p.add(new JScrollPane(candidateTable), BorderLayout.CENTER);
        return p;
    }

    private JPanel turnoutTab() {
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> fill(turnoutTable, "SELECT * FROM v_turnout ORDER BY 1, 2"));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(refresh);
        top.add(new JLabel("Turnout counts voters only; it never shows who voted for whom."));
        JPanel p = new JPanel(new BorderLayout());
        p.add(top, BorderLayout.NORTH);
        p.add(new JScrollPane(turnoutTable), BorderLayout.CENTER);
        return p;
    }

    private void refreshAll() {
        fill(electionTable, "SELECT election_id, title, start_time, end_time FROM election ORDER BY election_id");
        fill(turnoutTable, "SELECT * FROM v_turnout ORDER BY 1, 2");
        electionBox.removeAllItems();
        try (Connection c = EVotingApp.conn(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT election_id, title FROM election ORDER BY election_id")) {
            while (rs.next()) electionBox.addItem(new Item(rs.getInt(1), rs.getString(2)));
        } catch (SQLException ex) { EVotingApp.error(this, ex); }
    }

    private void loadPosts() {
        postBox.removeAllItems();
        Item el = (Item) electionBox.getSelectedItem();
        if (el == null) return;
        try (Connection c = EVotingApp.conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT post_id, post_title FROM election_post WHERE election_id = ? ORDER BY post_id")) {
            ps.setInt(1, el.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) postBox.addItem(new Item(rs.getInt(1), rs.getString(2)));
            }
        } catch (SQLException ex) { EVotingApp.error(this, ex); }
    }

    private void loadCandidates() {
        Item po = (Item) postBox.getSelectedItem();
        if (po == null) { candidateTable.setModel(new DefaultTableModel()); return; }
        fill(candidateTable,
             "SELECT c.candidate_id, s.student_id, s.full_name, c.manifesto "
           + "FROM candidate c JOIN student s ON s.student_id = c.student_id WHERE c.post_id = ?", po.id());
    }

    private boolean update(String sql, Object... params) {
        try (Connection c = EVotingApp.conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            ps.executeUpdate();
            return true;
        } catch (SQLException ex) { EVotingApp.error(this, ex); return false; }
    }

    private void fill(JTable table, String sql, Object... params) {
        try (Connection c = EVotingApp.conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                Vector<String> cols = new Vector<>();
                for (int i = 1; i <= md.getColumnCount(); i++) cols.add(md.getColumnLabel(i));
                Vector<Vector<Object>> rows = new Vector<>();
                while (rs.next()) {
                    Vector<Object> r = new Vector<>();
                    for (int i = 1; i <= cols.size(); i++) r.add(rs.getObject(i));
                    rows.add(r);
                }
                table.setModel(new DefaultTableModel(rows, cols) {
                    @Override public boolean isCellEditable(int r, int c) { return false; }
                });
            }
        } catch (SQLException ex) { EVotingApp.error(this, ex); }
    }
}
