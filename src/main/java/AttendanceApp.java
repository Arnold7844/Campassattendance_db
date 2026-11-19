import com.mongodb.client.*;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.types.ObjectId;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.*;
import java.util.List;

public class AttendanceApp {
    // Change this if you rename the database (e.g. "Campusattendance_db")
    static final String DB_NAME = "Campusattendance";
    static MongoDatabase db;

    public static void main(String[] args) {
        MongoClient client = MongoClients.create("mongodb://localhost:27017");
        db = client.getDatabase(DB_NAME);

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Student Attendance System - " + DB_NAME);
            JTabbedPane tabs = new JTabbedPane();
            // Collection names match Compass exactly (capital first letter).
            // Columns are detected from your existing documents; the names
            // below are only used if a collection is empty.
            tabs.add("Students",   new CrudPanel("Students",   "studentNo", "name", "email"));
            tabs.add("Courses",    new CrudPanel("Courses",    "courseCode", "title", "credits"));
            tabs.add("Lecturers",  new CrudPanel("Lecturers",  "staffNo", "name", "email"));
            tabs.add("Attendance", new CrudPanel("Attendance", "studentNo", "courseCode", "date", "status"));
            frame.add(tabs);
            frame.setSize(950, 520);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}

/** One reusable CRUD screen for any collection. */
class CrudPanel extends JPanel {
    private final MongoCollection<Document> col;
    private final String[] fields;
    private final Map<String, Class<?>> types = new HashMap<>();
    private final JTextField[] inputs;
    private final DefaultTableModel model;
    private final JTable table;
    private final List<Document> rows = new ArrayList<>();

    CrudPanel(String collection, String... defaultFields) {
        this.col = AttendanceApp.db.getCollection(collection);

        // Detect field names (and value types) from the existing documents
        List<String> found = new ArrayList<>();
        try {
            for (Document d : col.find().limit(20)) {
                for (Map.Entry<String, Object> en : d.entrySet()) {
                    if (en.getKey().equals("_id")) continue;
                    if (!found.contains(en.getKey())) found.add(en.getKey());
                    if (en.getValue() != null) types.putIfAbsent(en.getKey(), en.getValue().getClass());
                }
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null, "Cannot reach MongoDB: " + ex.getMessage());
        }
        this.fields = (found.isEmpty() ? Arrays.asList(defaultFields) : found).toArray(new String[0]);

        setLayout(new BorderLayout(8, 8));

        // Form
        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        inputs = new JTextField[fields.length];
        for (int i = 0; i < fields.length; i++) {
            form.add(new JLabel(fields[i]));
            inputs[i] = new JTextField();
            form.add(inputs[i]);
        }
        add(form, BorderLayout.NORTH);

        // Table
        model = new DefaultTableModel(fields, 0) {
            public boolean isCellEditable(int r, int c) { return false; }
        };
        table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> {
            int r = table.getSelectedRow();
            if (r >= 0) for (int i = 0; i < fields.length; i++)
                inputs[i].setText(String.valueOf(model.getValueAt(r, i)));
        });
        add(new JScrollPane(table), BorderLayout.CENTER);

        // Buttons
        JPanel buttons = new JPanel();
        JButton add = new JButton("Add"), upd = new JButton("Update"),
                del = new JButton("Delete"), ref = new JButton("Refresh");
        buttons.add(add); buttons.add(upd); buttons.add(del); buttons.add(ref);
        add(buttons, BorderLayout.SOUTH);

        add.addActionListener(e -> safe(() -> {                       // CREATE
            Document d = new Document();
            for (int i = 0; i < fields.length; i++)
                d.append(fields[i], convert(fields[i], inputs[i].getText().trim()));
            col.insertOne(d);
            clearForm();
            load();
        }));

        upd.addActionListener(e -> safe(() -> {                       // UPDATE
            Document orig = selectedRow();
            if (orig == null) return;
            Document set = new Document();
            for (int i = 0; i < fields.length; i++) {
                String txt = inputs[i].getText().trim();
                Object old = orig.get(fields[i]);
                String oldTxt = old == null ? "" : String.valueOf(old);
                if (!txt.equals(oldTxt)) set.append(fields[i], convert(fields[i], txt));
            }
            if (set.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Nothing changed.");
                return;
            }
            col.updateOne(Filters.eq("_id", orig.getObjectId("_id")), new Document("$set", set));
            load();
        }));

        del.addActionListener(e -> safe(() -> {                       // DELETE
            Document orig = selectedRow();
            if (orig == null) return;
            int ok = JOptionPane.showConfirmDialog(this, "Delete this record?", "Confirm",
                    JOptionPane.YES_NO_OPTION);
            if (ok != JOptionPane.YES_OPTION) return;
            col.deleteOne(Filters.eq("_id", orig.getObjectId("_id")));
            clearForm();
            load();
        }));

        ref.addActionListener(e -> safe(this::load));
        safe(this::load);
    }

    // READ
    private void load() {
        model.setRowCount(0);
        rows.clear();
        for (Document d : col.find()) {
            rows.add(d);
            Object[] row = new Object[fields.length];
            for (int i = 0; i < fields.length; i++) {
                Object v = d.get(fields[i]);
                row[i] = v == null ? "" : String.valueOf(v);
            }
            model.addRow(row);
        }
    }

    // Keep numbers/booleans as numbers/booleans if that is how they are stored
    private Object convert(String field, String text) {
        Class<?> c = types.get(field);
        try {
            if (c == Integer.class) return Integer.parseInt(text);
            if (c == Long.class) return Long.parseLong(text);
            if (c == Double.class) return Double.parseDouble(text);
            if (c == Boolean.class) return Boolean.parseBoolean(text);
        } catch (NumberFormatException ignored) { }
        return text;
    }

    private Document selectedRow() {
        int r = table.getSelectedRow();
        if (r < 0) { JOptionPane.showMessageDialog(this, "Select a row first."); return null; }
        return rows.get(r);
    }

    private void clearForm() { for (JTextField t : inputs) t.setText(""); }

    private void safe(Runnable action) {
        try { action.run(); }
        catch (Exception ex) { JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage()); }
    }
}
