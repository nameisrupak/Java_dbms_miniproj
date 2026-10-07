import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.sql.*;
import java.util.Vector;

/** Campus E-Voting: Swing UI + JDBC + Oracle. Needs ojdbc11.jar on the classpath. Java 16+. */
public class EVotingApp {
    // Edit these for your Oracle instance.
    static final String URL  = "jdbc:oracle:thin:@//localhost:1521/XEPDB1";
    static final String USER = "evote";
    static final String PASS = "evote_pw";

    static Connection conn() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASS);
    }

    static void error(Component parent, Exception ex) {
        String m = ex.getMessage() == null ? ex.toString() : ex.getMessage();
        // Oracle prefixes custom errors like "ORA-20003: You have already voted..."
        int i = m.indexOf("ORA-200");
        if (i >= 0) m = m.substring(i + 11).split("\n")[0];
        JOptionPane.showMessageDialog(parent, m, "Error", JOptionPane.ERROR_MESSAGE);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(LoginFrame::new);
    }
}

record Item(int id, String label) {
    @Override public String toString() { return label; }
}

class LoginFrame extends JFrame {
    LoginFrame() {
        super("Campus E-Voting - Login");
        JTextField id = new JTextField(15);
        JPasswordField pw = new JPasswordField(15);
        JButton login = new JButton("Login");

        JPanel form = new JPanel(new GridLayout(2, 2, 8, 8));
        form.add(new JLabel("Student ID:")); form.add(id);
        form.add(new JLabel("Password:"));   form.add(pw);
        form.setBorder(BorderFactory.createEmptyBorder(16, 16, 8, 16));

        Runnable doLogin = () -> {
            String sql = "SELECT full_name, is_admin FROM student "
                       + "WHERE student_id = ? AND pwd_hash = STANDARD_HASH(?, 'SHA256')";
            try (Connection c = EVotingApp.conn(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, id.getText().trim());
                ps.setString(2, new String(pw.getPassword()));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        if (rs.getInt(2) == 1) new AdminFrame(rs.getString(1));
                        else new VotingFrame(id.getText().trim(), rs.getString(1));
                        dispose();
                    } else {
                        JOptionPane.showMessageDialog(this, "Invalid ID or password.");
                    }
                }
            } catch (SQLException ex) { EVotingApp.error(this, ex); }
        };
        login.addActionListener(e -> doLogin.run());
        getRootPane().setDefaultButton(login);

        add(form, BorderLayout.CENTER);
        add(login, BorderLayout.SOUTH);
        pack(); setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE); setVisible(true);
    }
}

class VotingFrame extends JFrame {
    private final String studentId;
    private final JComboBox<Item> posts = new JComboBox<>();
    private final DefaultListModel<Item> candModel = new DefaultListModel<>();
    private final JList<Item> candList = new JList<>(candModel);
    private final JButton voteBtn = new JButton("Cast Vote");

    VotingFrame(String studentId, String name) {
        super("Campus E-Voting - " + name);
        this.studentId = studentId;

        candList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JButton results = new JButton("View Results");
        JPanel bottom = new JPanel();
        bottom.add(voteBtn); bottom.add(results);

        add(new JLabel("  Welcome, " + name + ". Choose a post, then a candidate:"), BorderLayout.NORTH);
        JPanel mid = new JPanel(new BorderLayout(6, 6));
        mid.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        mid.add(posts, BorderLayout.NORTH);
        mid.add(new JScrollPane(candList), BorderLayout.CENTER);
        add(mid, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        posts.addActionListener(e -> loadCandidates());
        voteBtn.addActionListener(e -> castVote());
        results.addActionListener(e -> showResults());

        loadPosts();
        setSize(480, 360); setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE); setVisible(true);
    }

    /** Only open posts this student has not yet voted for. */
    private void loadPosts() {
        posts.removeAllItems();
        String sql = "SELECT p.post_id, e.title || ' - ' || p.post_title FROM election_post p "
                   + "JOIN election e ON e.election_id = p.election_id "
                   + "WHERE LOCALTIMESTAMP BETWEEN e.start_time AND e.end_time "
                   + "AND NOT EXISTS (SELECT 1 FROM voter_log l WHERE l.post_id = p.post_id AND l.student_id = ?) "
                   + "ORDER BY p.post_id";
        try (Connection c = EVotingApp.conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) posts.addItem(new Item(rs.getInt(1), rs.getString(2)));
            }
        } catch (SQLException ex) { EVotingApp.error(this, ex); }
        voteBtn.setEnabled(posts.getItemCount() > 0);
        if (posts.getItemCount() == 0) {
            candModel.clear();
            candModel.addElement(new Item(-1, "Nothing left to vote on. Thank you!"));
        }
    }

    private void loadCandidates() {
        candModel.clear();
        Item post = (Item) posts.getSelectedItem();
        if (post == null) return;
        String sql = "SELECT c.candidate_id, s.full_name || ' - ' || NVL(c.manifesto, '') "
                   + "FROM candidate c JOIN student s ON s.student_id = c.student_id WHERE c.post_id = ?";
        try (Connection c = EVotingApp.conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, post.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) candModel.addElement(new Item(rs.getInt(1), rs.getString(2)));
            }
        } catch (SQLException ex) { EVotingApp.error(this, ex); }
    }

    private void castVote() {
        Item post = (Item) posts.getSelectedItem();
        Item cand = candList.getSelectedValue();
        if (post == null || cand == null) {
            JOptionPane.showMessageDialog(this, "Select a post and a candidate."); return;
        }
        int ok = JOptionPane.showConfirmDialog(this,
                "Vote for:\n" + cand + "\n\nThis cannot be changed.", "Confirm", JOptionPane.OK_CANCEL_OPTION);
        if (ok != JOptionPane.OK_OPTION) return;

        try (Connection c = EVotingApp.conn(); CallableStatement cs = c.prepareCall("{call cast_vote(?, ?, ?)}")) {
            cs.setString(1, studentId);
            cs.setInt(2, post.id());
            cs.setInt(3, cand.id());
            cs.execute();
            JOptionPane.showMessageDialog(this, "Your vote has been recorded.");
            loadPosts();
        } catch (SQLException ex) { EVotingApp.error(this, ex); loadPosts(); }
    }

    /** Results are shown only for elections that have ended. */
    private void showResults() {
        String sql = "SELECT election, post_title, candidate, votes FROM v_results "
                   + "WHERE end_time < LOCALTIMESTAMP ORDER BY election_id, post_title, votes DESC";
        try (Connection c = EVotingApp.conn(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            Vector<String> cols = new Vector<>(java.util.List.of("Election", "Post", "Candidate", "Votes"));
            Vector<Vector<Object>> rows = new Vector<>();
            while (rs.next()) {
                Vector<Object> r = new Vector<>();
                for (int i = 1; i <= 4; i++) r.add(rs.getObject(i));
                rows.add(r);
            }
            if (rows.isEmpty()) { JOptionPane.showMessageDialog(this, "No completed elections yet."); return; }
            JTable t = new JTable(new DefaultTableModel(rows, cols) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            });
            JScrollPane sp = new JScrollPane(t);
            sp.setPreferredSize(new Dimension(520, 240));
            JOptionPane.showMessageDialog(this, sp, "Results", JOptionPane.PLAIN_MESSAGE);
        } catch (SQLException ex) { EVotingApp.error(this, ex); }
    }
}
