package net.runelite.client.plugins.microbot.scriptbuilder;

import net.runelite.client.plugins.microbot.scriptbuilder.model.BlockDefinition;
import net.runelite.client.plugins.microbot.scriptbuilder.model.BlockParam;
import net.runelite.client.plugins.microbot.scriptbuilder.model.BlockInstance;
import net.runelite.client.plugins.microbot.scriptbuilder.registry.BlockRegistry;
import net.runelite.client.plugins.microbot.scriptbuilder.runner.ScriptRunner;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ScriptBuilderPanel extends JPanel {

    private final BlockRegistry registry = new BlockRegistry();
    private final DefaultListModel<BlockInstance> scriptModel = new DefaultListModel<>();
    private final JList<BlockInstance> scriptList = new JList<>(scriptModel);
    private final List<JList<BlockDefinition>> availableLists = new ArrayList<>();
    private final JPanel availableContainer = new JPanel();
    private final JTextField searchField = new JTextField();
    private final List<Section> sections = new ArrayList<>();
    private final JTextArea logArea = new JTextArea();
    private final ScriptRunner runner = new ScriptRunner(registry);
    private static boolean DISPATCHER_REGISTERED = false;
    private static long QS_LAST_SHIFT_TIME = 0L;
    private static int QS_SHIFT_TAPS = 0;
    private static boolean QS_OPEN = false;
    private static boolean QS_PENDING = false;
    private static long QS_SUPPRESS_UNTIL = 0L;
    private JPopupMenu openPopup;
    private int pairHighlightIndex = -1;

    private final java.util.Deque<java.util.List<BlockInstance>> history = new java.util.ArrayDeque<>();
    private final java.util.Deque<java.util.List<BlockInstance>> redoStack = new java.util.ArrayDeque<>();
    private boolean applyingUndo = false;
    private boolean applyingRedo = false;

    public ScriptBuilderPanel(Object ignoredConfigManager) {
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setBorder(new EmptyBorder(8, 8, 8, 8));

        availableContainer.setLayout(new BoxLayout(availableContainer, BoxLayout.Y_AXIS));
        availableContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        registry.groups().forEach((group, defs) -> addSection(group, defs));
        addLogicSection();

        JMenuBar categoryBar = new JMenuBar();
        categoryBar.setBorderPainted(false);
        for (Section s : sections) {
            JButton btn = new JButton(s.title);
            btn.setFocusPainted(false);
            btn.setBorder(BorderFactory.createEmptyBorder(4,8,4,8));

            JPopupMenu popup = buildCategoryPopup(s);
            btn.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseEntered(java.awt.event.MouseEvent e) {
                    if (openPopup != null && openPopup.isVisible()) openPopup.setVisible(false);
                    openPopup = popup;
                    popup.show(btn, 0, btn.getHeight());
                }
            });
            categoryBar.add(btn);
        }
        add(categoryBar, BorderLayout.NORTH);

        scriptList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel c = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                BlockInstance bi = (BlockInstance) value;
                int indent = computeIndent(index, bi);
                StringBuilder pad = new StringBuilder();
                for (int i = 0; i < indent; i++) pad.append("  ");
                if (bi.getKind() == null || bi.getKind() == BlockInstance.Kind.ACTION) {
                    BlockDefinition d = registry.get(bi.getDefinitionId());
                    if (d == null) {
                        c.setText(pad + String.valueOf(bi.getDefinitionId()));
                    } else if ("SLEEPS.SLEEPUNTIL".equals(d.getId())) {
                        BlockDefinition cd = registry.get(bi.getConditionDefinitionId());
                        String timeout = bi.getArgs().isEmpty() ? "" : bi.getArgs().get(0);
                        String condLabel = cd != null && cd.getMethod() != null
                                ? (cd.getGroup() + ": " + cd.getMethod().getName() + formatArgs(cd, bi.getConditionArgs()))
                                : "<unset condition>";
                        String label = "Sleeps: sleepUntil(" + (timeout == null || timeout.isEmpty() ? "?" : timeout) + ") " + condLabel;
                        c.setText(pad + label);
                    } else if (d.getMethod() == null) {
                        c.setText(pad + String.valueOf(bi.getDefinitionId()));
                    } else {
                        String label = d.getGroup() + ": " + d.getMethod().getName() + formatArgs(d, bi.getArgs());
                        c.setText(pad + label);
                    }
                } else if (bi.getKind() == BlockInstance.Kind.COMMENT) {
                    String text = bi.getComment() == null ? "" : bi.getComment();
                    c.setText(pad + "// " + text);
                    c.setForeground(new Color(150, 150, 150));
                } else if (bi.getKind() == BlockInstance.Kind.IF) {
                    BlockDefinition cd = registry.get(bi.getConditionDefinitionId());
                    if (cd != null && cd.getMethod() != null) {
                        String label = (bi.isNegateCondition() ? "! " : "") + cd.getGroup() + ": " + cd.getMethod().getName() + formatArgs(cd, bi.getConditionArgs());
                        c.setText(pad + "IF " + label);
                    } else {
                        c.setText(pad + "IF " + (bi.isNegateCondition() ? "! " : "") + "<unset condition>");
                    }
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                } else if (bi.getKind() == BlockInstance.Kind.ELSE) {
                    if (pad.length() >= 2) pad.setLength(pad.length()-2);
                    c.setText(pad + "ELSE");
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                } else if (bi.getKind() == BlockInstance.Kind.ENDIF) {
                    if (pad.length() >= 2) pad.setLength(pad.length()-2);
                    c.setText(pad + "END IF");
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                }
                if (!isSelected && index == pairHighlightIndex) {
                    c.setOpaque(true);
                    c.setBackground(new Color(65, 90, 130));
                }
                return c;
            }
        });

        // selection mode per section list is set within addSection
        scriptList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JButton removeBtn = new JButton("Remove");
        removeBtn.addActionListener(e -> {
            int idx = scriptList.getSelectedIndex();
            if (idx < 0) return;
            int res = JOptionPane.showConfirmDialog(this, "Remove selected line?", "Confirm", JOptionPane.YES_NO_OPTION);
            if (res != JOptionPane.YES_OPTION) return;
            pushSnapshot();
            scriptModel.remove(idx);
        });

        JButton upBtn = new JButton("Up");
        upBtn.addActionListener(e -> moveSelected(-1));
        JButton downBtn = new JButton("Down");
        downBtn.addActionListener(e -> moveSelected(1));

        JButton runBtn = new JButton("Execute");
        runBtn.addActionListener(e -> runScript());
        JButton stopBtn = new JButton("Stop");
        stopBtn.addActionListener(e -> runner.stop());

        JButton saveBtn = new JButton("Save");
        saveBtn.addActionListener(e -> saveScript());
        JButton loadBtn = new JButton("Load");
        loadBtn.addActionListener(e -> loadScript());

        JPanel center = new JPanel(new BorderLayout());
        JLabel scriptLbl = new JLabel("Script");
        scriptLbl.setHorizontalAlignment(SwingConstants.CENTER);
        scriptLbl.setFont(scriptLbl.getFont().deriveFont(Font.BOLD));
        center.add(scriptLbl, BorderLayout.NORTH);
        center.add(new JScrollPane(scriptList), BorderLayout.CENTER);
        JPanel centerBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        centerBtns.add(upBtn);
        centerBtns.add(downBtn);
        centerBtns.add(removeBtn);
        center.add(centerBtns, BorderLayout.SOUTH);

        JPanel right = new JPanel(new BorderLayout());
        JLabel execLbl = new JLabel("Execution");
        execLbl.setHorizontalAlignment(SwingConstants.CENTER);
        execLbl.setFont(execLbl.getFont().deriveFont(Font.BOLD));
        right.add(execLbl, BorderLayout.NORTH);
        logArea.setEditable(false);
        JScrollPane logScroll = new JScrollPane(logArea);
        right.add(logScroll, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
        JCheckBox verboseCb = new JCheckBox("Verbose");
        verboseCb.addActionListener(e -> runner.setVerbose(verboseCb.isSelected()));
        actions.add(runBtn);
        actions.add(stopBtn);
        actions.add(saveBtn);
        actions.add(loadBtn);
        actions.add(verboseCb);
        right.add(actions, BorderLayout.SOUTH);

        JPanel centerWrap = wrap(center);
        JPanel rightWrap = wrap(right);
        JSplitPane inner = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, centerWrap, rightWrap);
        add(inner, BorderLayout.CENTER);

        scriptList.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z, java.awt.event.InputEvent.CTRL_DOWN_MASK),
                "undo");
        scriptList.getActionMap().put("undo", new UndoAction(this));

        scriptList.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Y, java.awt.event.InputEvent.CTRL_DOWN_MASK),
                "redo");
        scriptList.getActionMap().put("redo", new RedoAction(this));

        centerWrap.setPreferredSize(new Dimension(441, centerWrap.getPreferredSize().height));
        rightWrap.setPreferredSize(new Dimension(319, rightWrap.getPreferredSize().height));

        SwingUtilities.invokeLater(() -> {
            inner.setDividerLocation(441);
        });

        scriptList.addListSelectionListener(e -> updatePairHighlight());
        scriptList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() >= 2) {
                    int idx = scriptList.locationToIndex(e.getPoint());
                    if (idx >= 0) openParamEditor(scriptModel.get(idx));
                }
            }
        });

        scriptList.addMouseListener(new java.awt.event.MouseAdapter() {
            private void maybeShowPopup(java.awt.event.MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int idx = scriptList.locationToIndex(e.getPoint());
                if (idx < 0) return;
                scriptList.setSelectedIndex(idx);
                JPopupMenu menu = new JPopupMenu();
                JMenuItem addComment = new JMenuItem("Add comment above");
                addComment.addActionListener(a -> {
                    int sel = scriptList.getSelectedIndex();
                    if (sel < 0) return;
                    String text = JOptionPane.showInputDialog(ScriptBuilderPanel.this, "Comment text");
                    if (text == null) return;
                    pushSnapshot();
                    BlockInstance comment = new BlockInstance();
                    comment.setKind(BlockInstance.Kind.COMMENT);
                    comment.setComment(text);
                    scriptModel.add(sel, comment);
                    scriptList.setSelectedIndex(sel);
                });
                JMenuItem duplicate = new JMenuItem("Duplicate");
                duplicate.addActionListener(a -> duplicateSelected());
                JMenuItem remove = new JMenuItem("Remove");
                remove.addActionListener(a -> {
                    int sel = scriptList.getSelectedIndex();
                    if (sel < 0) return;
                    int res = JOptionPane.showConfirmDialog(ScriptBuilderPanel.this, "Remove selected line?", "Confirm Remove", JOptionPane.YES_NO_OPTION);
                    if (res != JOptionPane.YES_OPTION) return;
                    pushSnapshot();
                    scriptModel.remove(sel);
                });
                menu.add(addComment);
                menu.add(duplicate);
                menu.add(remove);
                menu.show(scriptList, e.getX(), e.getY());
            }
            @Override public void mousePressed(java.awt.event.MouseEvent e) { maybeShowPopup(e); }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) { maybeShowPopup(e); }
        });

        scriptList.setDragEnabled(true);
        scriptList.setDropMode(DropMode.INSERT);
        scriptList.setTransferHandler(new TransferHandler() {
            protected Transferable createTransferable(JComponent c) {
                return new DataHandlerWrapper(scriptList.getSelectedValue());
            }
            public int getSourceActions(JComponent c) { return MOVE; }
            public boolean canImport(TransferSupport s) { return s.isDataFlavorSupported(DataFlavor.stringFlavor); }
            public boolean importData(TransferSupport s) {
                try {
                    pushSnapshot();
                    int idx = scriptList.getDropLocation().getIndex();
                    int from = scriptList.getSelectedIndex();
                    if (from == -1) return false;
                    int start = from, end = from;
                    BlockInstance b = scriptModel.get(from);
                    if (b.getKind() == BlockInstance.Kind.IF) {
                        int e = findMatchingEndIf(from);
                        if (e > from) { start = from; end = e; }
                    } else if (b.getKind() == BlockInstance.Kind.ENDIF) {
                        int sIdx = findMatchingIf(from);
                        if (sIdx >= 0) { start = sIdx; end = from; }
                    }
                    int len = end - start + 1;
                    java.util.List<BlockInstance> segment = new java.util.ArrayList<>();
                    for (int i = start; i <= end; i++) segment.add(scriptModel.get(i));
                    for (int i = end; i >= start; i--) scriptModel.remove(i);
                    if (idx > end) idx -= len; else if (idx > start) idx = start;
                    for (int i = 0; i < segment.size(); i++) scriptModel.add(idx + i, segment.get(i));
                    scriptList.setSelectedIndex(idx);
                    return true;
                } catch (Exception ex) { return false; }
            }
        });

        if (!DISPATCHER_REGISTERED) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
                if (e.getID() != java.awt.event.KeyEvent.KEY_RELEASED) return false;
                if (e.getKeyCode() != java.awt.event.KeyEvent.VK_SHIFT) return false;
                long now0 = System.currentTimeMillis();
                if (QS_OPEN || QS_PENDING) return false;
                long now = System.currentTimeMillis();
                if (now < QS_SUPPRESS_UNTIL) return false;
                if (now - QS_LAST_SHIFT_TIME < 400) {
                    QS_SHIFT_TAPS++;
                } else {
                    QS_SHIFT_TAPS = 1;
                }
                QS_LAST_SHIFT_TIME = now;
                if (QS_SHIFT_TAPS >= 2) {
                    QS_SHIFT_TAPS = 0;
                    QS_PENDING = true;
                    Component fo = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                    ScriptBuilderPanel panel = (ScriptBuilderPanel) javax.swing.SwingUtilities.getAncestorOfClass(ScriptBuilderPanel.class, fo);
                    SwingUtilities.invokeLater(() -> {
                        if (panel != null) panel.openQuickSearchDialog();
                    });
                }
                return false;
            });
            DISPATCHER_REGISTERED = true;
        }

        loadBackupOrDefault();
    }

    private JPanel wrap(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        p.setBorder(new EmptyBorder(4,4,4,4));
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    private void loadDefaultScript() {
        try {
            scriptModel.clear();
            BlockInstance if1 = new BlockInstance();
            if1.setKind(BlockInstance.Kind.IF);
            if1.setConditionDefinitionId("Rs2Bank.isOpen()");
            if1.setNegateCondition(true);
            scriptModel.addElement(if1);

            BlockInstance act1 = new BlockInstance();
            act1.setKind(BlockInstance.Kind.ACTION);
            act1.setDefinitionId("Rs2Bank.walkToBankAndUseBank()");
            scriptModel.addElement(act1);

            BlockInstance sleep = new BlockInstance();
            sleep.setKind(BlockInstance.Kind.ACTION);
            sleep.setDefinitionId("SLEEPS.SLEEPUNTIL");
            sleep.getArgs().add("3000");
            sleep.setConditionDefinitionId("Rs2Bank.isOpen()");
            scriptModel.addElement(sleep);

            BlockInstance if2 = new BlockInstance();
            if2.setKind(BlockInstance.Kind.IF);
            if2.setConditionDefinitionId("Rs2Bank.isOpen()");
            scriptModel.addElement(if2);

            BlockInstance withdraw = new BlockInstance();
            withdraw.setKind(BlockInstance.Kind.ACTION);
            withdraw.setDefinitionId("Rs2Bank.withdrawX(String,int)");
            withdraw.getArgs().add("Diamond");
            withdraw.getArgs().add("2");
            scriptModel.addElement(withdraw);

            BlockInstance endif1 = new BlockInstance();
            endif1.setKind(BlockInstance.Kind.ENDIF);
            scriptModel.addElement(endif1);

            BlockInstance endif2 = new BlockInstance();
            endif2.setKind(BlockInstance.Kind.ENDIF);
            scriptModel.addElement(endif2);

            if (!scriptModel.isEmpty()) scriptList.setSelectedIndex(0);
        } catch (Exception ignored) {}
    }

    public List<BlockInstance> getCurrentScript() {
        return snapshotCurrent();
    }

    public void saveBackupSilent() {
        try {
            File f = new File(ScriptRunner.scriptsDir(), "backup.json");
            List<BlockInstance> list = new ArrayList<>();
            for (int i = 0; i < scriptModel.size(); i++) list.add(scriptModel.get(i));
            ScriptRunner.saveToFile(f, list);
        } catch (Exception ignored) {}
    }

    private void loadBackupOrDefault() {
        try {
            File f = new File(ScriptRunner.scriptsDir(), "backup.json");
            if (f.exists()) {
                List<BlockInstance> list = ScriptRunner.loadFromFile(f);
                scriptModel.clear();
                for (BlockInstance bi : list) scriptModel.addElement(bi);
                if (!scriptModel.isEmpty()) scriptList.setSelectedIndex(0);
            } else {
                loadDefaultScript();
            }
        } catch (Exception e) {
            loadDefaultScript();
        }
    }

    private void openQuickSearchDialog() {
        if (QS_OPEN) return;
        QS_PENDING = false;
        QS_OPEN = true;
        JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(this), "Search Blocks", Dialog.ModalityType.APPLICATION_MODAL);
        JPanel panel = new JPanel(new BorderLayout(6,6));
        panel.setBorder(new EmptyBorder(8,8,8,8));
        JTextField query = new JTextField();
        DefaultListModel<BlockDefinition> model = new DefaultListModel<>();
        java.util.List<BlockDefinition> all = registry.all();
        all.forEach(model::addElement);
        JList<BlockDefinition> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, value, index, isSelected, cellHasFocus);
                BlockDefinition d = (BlockDefinition) value;
                c.setText(d.getDisplayName());
                return c;
            }
        });
        JScrollPane sp = new JScrollPane(list);
        panel.add(query, BorderLayout.NORTH);
        panel.add(sp, BorderLayout.CENTER);
        Runnable filter = () -> {
            String q = query.getText().trim().toLowerCase();
            model.clear();
            for (BlockDefinition d : all) {
                String dn = d.getDisplayName() != null ? d.getDisplayName().toLowerCase() : "";
                String gn = d.getGroup() != null ? d.getGroup().toLowerCase() : "";
                String mn = d.getMethod() != null ? d.getMethod().getName().toLowerCase() : "";
                if (q.isEmpty() || dn.contains(q) || gn.contains(q) || mn.contains(q)) model.addElement(d);
            }
            if (!model.isEmpty()) list.setSelectedIndex(0);
        };
        query.getDocument().addDocumentListener(new SimpleDocumentListener(filter));
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() >= 2) {
                    BlockDefinition d = list.getSelectedValue();
                    if (d != null) {
                        addBlockInstance(d);
                        QS_OPEN = false;
                        QS_PENDING = false;
                        QS_SUPPRESS_UNTIL = System.currentTimeMillis() + 600;
                        QS_LAST_SHIFT_TIME = 0L;
                        QS_SHIFT_TAPS = 0;
                        dlg.dispose();
                    }
                }
            }
        });
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0), "pick");
        list.getActionMap().put("pick", new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) {
            BlockDefinition d = list.getSelectedValue();
            if (d != null) {
                addBlockInstance(d);
                QS_OPEN = false;
                QS_PENDING = false;
                QS_SUPPRESS_UNTIL = System.currentTimeMillis() + 600;
                QS_LAST_SHIFT_TIME = 0L;
                QS_SHIFT_TAPS = 0;
                dlg.dispose();
            }
        }});
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "close");
        list.getActionMap().put("close", new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) {
            QS_OPEN = false;
            QS_PENDING = false;
            QS_SUPPRESS_UNTIL = System.currentTimeMillis() + 300;
            QS_LAST_SHIFT_TIME = 0L;
            QS_SHIFT_TAPS = 0;
            dlg.dispose();
        }});
        dlg.setContentPane(panel);
        dlg.setSize(new Dimension(520, 420));
        dlg.setLocationRelativeTo(this);
        dlg.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                QS_OPEN = false;
                QS_PENDING = false;
                QS_SUPPRESS_UNTIL = System.currentTimeMillis() + 300;
                QS_LAST_SHIFT_TIME = 0L;
                QS_SHIFT_TAPS = 0;
            }
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                QS_OPEN = false;
                QS_PENDING = false;
                QS_SUPPRESS_UNTIL = System.currentTimeMillis() + 300;
                QS_LAST_SHIFT_TIME = 0L;
                QS_SHIFT_TAPS = 0;
            }
        });
        SwingUtilities.invokeLater(query::requestFocusInWindow);
        dlg.setVisible(true);
    }

    private JPopupMenu buildCategoryPopup(Section s) {
        JPopupMenu popup = new JPopupMenu();
        DefaultListModel<BlockDefinition> model = new DefaultListModel<>();
        for (BlockDefinition d : s.allDefs) model.addElement(d);
        JList<BlockDefinition> list = new JList<>(model);
        list.setVisibleRowCount(-1);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, value, index, isSelected, cellHasFocus);
                BlockDefinition d = (BlockDefinition) value;
                c.setText(d.getDisplayName());
                return c;
            }
        });
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() >= 1) {
                    BlockDefinition d = list.getSelectedValue();
                    if (d != null) addBlockInstance(d);
                    popup.setVisible(false);
                }
            }
        });
        JScrollPane sp = new JScrollPane(list);
        int rowH = Math.max(list.getFixedCellHeight() != -1 ? list.getFixedCellHeight() : 22, 20);
        int maxH = Math.min(320, Math.max(120, s.allDefs.size() * rowH + 4));
        sp.setPreferredSize(new Dimension(260, maxH));
        popup.add(sp);
        popup.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {}
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) { if (openPopup == popup) openPopup = null; }
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) { if (openPopup == popup) openPopup = null; }
        });
        return popup;
    }

    

    private void addSection(String title, List<BlockDefinition> defs) {
        JPanel section = new JPanel(new BorderLayout());
        section.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        JToggleButton header = new JToggleButton(title);
        header.setSelected(true);
        header.setFocusPainted(false);
        header.setHorizontalAlignment(SwingConstants.LEFT);
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        section.add(header, BorderLayout.NORTH);
        DefaultListModel<BlockDefinition> model = new DefaultListModel<>();
        defs.forEach(model::addElement);
        JList<BlockDefinition> list = new JList<>(model);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, value, index, isSelected, cellHasFocus);
                BlockDefinition d = (BlockDefinition) value;
                c.setText(d.getDisplayName());
                return c;
            }
        });
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() >= 2) {
                    BlockDefinition d = list.getSelectedValue();
                    if (d != null) addBlockInstance(d);
                }
            }
        });
        availableLists.add(list);
        JScrollPane scroller = new JScrollPane(list);
        section.add(scroller, BorderLayout.CENTER);
        header.addActionListener(e -> {
            scroller.setVisible(header.isSelected());
            section.revalidate();
        });
        availableContainer.add(section);
        sections.add(new Section(title, defs, model, list, scroller, header, section));
    }

    private void addLogicSection() {
        List<BlockDefinition> logicDefs = new ArrayList<>();
        logicDefs.add(BlockDefinition.builder().id("LOGIC.IF").displayName("If ... End If").group("Logic").build());
        logicDefs.add(BlockDefinition.builder().id("LOGIC.ELSE").displayName("Else").group("Logic").build());
        addSection("Logic", logicDefs);
    }

    private void addBlockInstance(BlockDefinition d) {
        pushSnapshot();
        int insertAt = scriptList.getSelectedIndex() >= 0 ? scriptList.getSelectedIndex() + 1 : scriptModel.size();
        if (d.getId().startsWith("LOGIC.")) {
            BlockInstance.Kind k;
            switch (d.getId()) {
                case "LOGIC.IF": k = BlockInstance.Kind.IF; break;
                case "LOGIC.ELSE": k = BlockInstance.Kind.ELSE; break;
                case "LOGIC.ENDIF": k = BlockInstance.Kind.ENDIF; break;
                default: k = BlockInstance.Kind.ACTION;
            }
            BlockInstance inst = new BlockInstance();
            inst.setKind(k);
            if (k == BlockInstance.Kind.IF) {
                scriptModel.add(insertAt, inst);
                BlockInstance end = new BlockInstance();
                end.setKind(BlockInstance.Kind.ENDIF);
                scriptModel.add(insertAt + 1, end);
                scriptList.setSelectedIndex(insertAt);
                return;
            } else {
                scriptModel.add(insertAt, inst);
            }
        } else {
            BlockInstance inst = new BlockInstance();
            inst.setKind(BlockInstance.Kind.ACTION);
            inst.setDefinitionId(d.getId());
            for (int i = 0; i < d.getParams().size(); i++) inst.getArgs().add("");
            scriptModel.add(insertAt, inst);
        }
        scriptList.setSelectedIndex(insertAt);
    }

    private void addSelectedFromAnySection() {
        for (JList<BlockDefinition> l : availableLists) {
            BlockDefinition d = l.getSelectedValue();
            if (d != null) { addBlockInstance(d); return; }
        }
    }

    private int findMatchingEndIf(int ifIdx) {
        int depth = 1;
        for (int i = ifIdx + 1; i < scriptModel.size(); i++) {
            BlockInstance k = scriptModel.get(i);
            if (k.getKind() == BlockInstance.Kind.IF) depth++;
            else if (k.getKind() == BlockInstance.Kind.ENDIF) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

private int findMatchingIf(int endIfIdx) {
        int depth = 1;
        for (int i = endIfIdx - 1; i >= 0; i--) {
            BlockInstance k = scriptModel.get(i);
            if (k.getKind() == BlockInstance.Kind.ENDIF) depth++;
            else if (k.getKind() == BlockInstance.Kind.IF) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private void removeRange(int start, int end) {
        for (int i = end; i >= start; i--) scriptModel.remove(i);
    }

    private void moveRange(int start, int end, int insertAt) {
        java.util.List<BlockInstance> segment = new java.util.ArrayList<>();
        for (int i = start; i <= end; i++) segment.add(scriptModel.get(i));
        for (int i = end; i >= start; i--) scriptModel.remove(i);
        if (insertAt > start) insertAt -= (end - start + 1);
        for (int i = 0; i < segment.size(); i++) scriptModel.add(insertAt + i, segment.get(i));
    }

    private void wrapActionInIf(BlockInstance inst, boolean negated) {
        pushSnapshot();
        int idx = -1;
        for (int i = 0; i < scriptModel.size(); i++) {
            if (scriptModel.get(i) == inst) { idx = i; break; }
        }
        if (idx < 0) return;
        BlockInstance ifInst = new BlockInstance();
        ifInst.setKind(BlockInstance.Kind.IF);
        ifInst.setNegateCondition(negated);
        BlockDefinition actionDef = registry.get(inst.getDefinitionId());
        BlockDefinition condDef = guessConditionForAction(actionDef);
        String condId = condDef != null ? condDef.getId() : null;
        ifInst.setConditionDefinitionId(condId);
        int pcount = condDef != null ? condDef.getParams().size() : 0;
        for (int i = 0; i < pcount; i++) {
            String v = i < inst.getArgs().size() ? inst.getArgs().get(i) : "";
            ifInst.getConditionArgs().add(v);
        }
        scriptModel.remove(idx);
        scriptModel.add(idx, ifInst);
        BlockInstance end = new BlockInstance();
        end.setKind(BlockInstance.Kind.ENDIF);
        scriptModel.add(idx + 1, end);
        scriptList.setSelectedIndex(idx);
        openParamEditor(ifInst);
    }

    private BlockDefinition guessConditionForAction(BlockDefinition actionDef) {
        if (actionDef == null || actionDef.getMethod() == null) return null;
        String group = actionDef.getGroup();
        String name = actionDef.getMethod().getName();
        java.util.List<BlockDefinition> conds = registry.conditions();
        BlockDefinition best = null;
        for (BlockDefinition d : conds) {
            if (group != null && !group.equals(d.getGroup())) continue;
            if (d.getMethod() == null) continue;
            String cn = d.getMethod().getName();
            if (cn.equalsIgnoreCase(name)) return d;
        }
        if (name.startsWith("find") && name.length() > 4) {
            String tail = name.substring(4);
            String target = "has" + tail;
            for (BlockDefinition d : conds) {
                if (group != null && !group.equals(d.getGroup())) continue;
                if (d.getMethod() == null) continue;
                String cn = d.getMethod().getName();
                if (cn.equalsIgnoreCase(target)) return d;
            }
        }
        for (BlockDefinition d : conds) {
            if (group != null && !group.equals(d.getGroup())) continue;
            if (d.getMethod() == null) continue;
            String cn = d.getMethod().getName();
            if (cn.contains("has") && (name.contains("item") == cn.contains("item"))) {
                best = d; break;
            }
        }
        return best;
    }

    private void moveSelected(int delta) {
        pushSnapshot();
        int idx = scriptList.getSelectedIndex();
        if (idx < 0) return;
        int newIdx = Math.max(0, Math.min(scriptModel.size() - 1, idx + delta));
        if (newIdx == idx) return;
        BlockInstance val = scriptModel.remove(idx);
        scriptModel.add(newIdx, val);
        scriptList.setSelectedIndex(newIdx);
        updatePairHighlight();
    }

    private void openParamEditor(BlockInstance inst) {
        if (inst.getKind() == BlockInstance.Kind.COMMENT) {
            pushSnapshot();
            String cur = inst.getComment() == null ? "" : inst.getComment();
            String text = JOptionPane.showInputDialog(this, "Comment text", cur);
            if (text != null) inst.setComment(text);
            scriptList.repaint();
        } else if (inst.getKind() == null || inst.getKind() == BlockInstance.Kind.ACTION) {
            pushSnapshot();
            BlockDefinition def = registry.get(inst.getDefinitionId());
            if (def == null) return;
            JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(this), def.getDisplayName(), Dialog.ModalityType.APPLICATION_MODAL);
            JPanel content = new JPanel(new GridBagLayout());
            content.setBorder(new EmptyBorder(8,8,8,8));
            GridBagConstraints gc = new GridBagConstraints();
            gc.insets = new Insets(4,4,4,4);
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.gridy = 0;
            JCheckBox wrapIf = new JCheckBox("If");
            JCheckBox negIf = new JCheckBox("Negated If");
            negIf.setEnabled(false);
            wrapIf.addActionListener(e -> negIf.setEnabled(wrapIf.isSelected()));
            gc.gridx = 0; gc.gridwidth = 1; content.add(wrapIf, gc);
            gc.gridx = 1; gc.gridwidth = 1; content.add(negIf, gc); gc.gridy++;

            if ("SLEEPS.SLEEPUNTIL".equals(def.getId())) {
                List<BlockDefinition> conds = registry.conditions();
                JLabel condLbl = new JLabel("Condition");
                gc.gridx = 0; gc.gridwidth = 2; content.add(condLbl, gc); gc.gridy++;
                JComboBox<BlockDefinition> condBox = new JComboBox<>(conds.toArray(new BlockDefinition[0]));
                condBox.setRenderer(new DefaultListCellRenderer() {
                    @Override public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                        JLabel c = (JLabel) super.getListCellRendererComponent(l, value, index, isSelected, cellHasFocus);
                        if (value instanceof BlockDefinition) c.setText(((BlockDefinition) value).getDisplayName());
                        return c;
                    }
                });
                BlockDefinition selected = null;
                for (BlockDefinition d : conds) if (d.getId().equals(inst.getConditionDefinitionId())) { selected = d; break; }
                if (selected != null) condBox.setSelectedItem(selected);
                gc.gridx = 0; gc.gridwidth = 2; content.add(condBox, gc); gc.gridy++;

                JPanel paramsPanel = new JPanel(new GridBagLayout());
                gc.gridx = 0; gc.gridwidth = 2; content.add(paramsPanel, gc); gc.gridy++;

                JCheckBox negate = new JCheckBox("Negate condition");
                negate.setSelected(inst.isNegateCondition());
                negate.addActionListener(e -> inst.setNegateCondition(negate.isSelected()));
                gc.gridx = 0; gc.gridwidth = 2; content.add(negate, gc); gc.gridy++;

                Runnable rebuild = () -> {
                    paramsPanel.removeAll();
                    BlockDefinition cdef = (BlockDefinition) condBox.getSelectedItem();
                    if (cdef != null) {
                        inst.setConditionDefinitionId(cdef.getId());
                        while (inst.getConditionArgs().size() < cdef.getParams().size()) inst.getConditionArgs().add("");
                        while (inst.getConditionArgs().size() > cdef.getParams().size()) inst.getConditionArgs().remove(inst.getConditionArgs().size()-1);
                        GridBagConstraints pgc = new GridBagConstraints();
                        pgc.insets = new Insets(4,4,4,4);
                        pgc.fill = GridBagConstraints.HORIZONTAL;
                        pgc.gridy = 0;
                        for (int i = 0; i < cdef.getParams().size(); i++) {
                            JLabel l = new JLabel(labelForParam(cdef, i, true));
                            JComponent field = paramField(cdef, cdef.getParams().get(i).getType(), inst, i, true);
                            pgc.gridx = 0; pgc.weightx = 0; paramsPanel.add(l, pgc);
                            pgc.gridx = 1; pgc.weightx = 1; paramsPanel.add(field, pgc);
                            pgc.gridy++;
                        }
                    }
                    paramsPanel.revalidate();
                    paramsPanel.repaint();
                };
                condBox.addActionListener(e -> rebuild.run());
                rebuild.run();
            }
            gc.gridwidth = 1;
            for (int i = 0; i < def.getParams().size(); i++) {
                JLabel l = new JLabel(labelForParam(def, i, false));
                JComponent field = paramField(def, def.getParams().get(i).getType(), inst, i, false);
                gc.gridx = 0; gc.weightx = 0; content.add(l, gc);
                gc.gridx = 1; gc.weightx = 1; content.add(field, gc);
                gc.gridy++;
            }
            JButton close = new JButton("Save");
            close.addActionListener(e -> {
                if (wrapIf.isSelected()) wrapActionInIf(inst, negIf.isSelected());
                dlg.dispose();
            });
            gc.gridx = 0; gc.gridwidth = 2; content.add(close, gc);
            dlg.setContentPane(content);
            dlg.pack();
            dlg.setLocationRelativeTo(this);
            dlg.setVisible(true);
            scriptList.repaint();
        } else if (inst.getKind() == BlockInstance.Kind.IF) {
            pushSnapshot();
            List<BlockDefinition> conds = registry.conditions();
            JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(this), "If Condition", Dialog.ModalityType.APPLICATION_MODAL);
            JPanel content = new JPanel(new GridBagLayout());
            content.setBorder(new EmptyBorder(8,8,8,8));
            GridBagConstraints gc = new GridBagConstraints();
            gc.insets = new Insets(4,4,4,4);
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.gridy = 0;

            JComboBox<BlockDefinition> condBox = new JComboBox<>(conds.toArray(new BlockDefinition[0]));
            condBox.setRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    JLabel c = (JLabel) super.getListCellRendererComponent(l, value, index, isSelected, cellHasFocus);
                    if (value instanceof BlockDefinition) c.setText(((BlockDefinition) value).getDisplayName());
                    return c;
                }
            });

            BlockDefinition selected = null;
            for (BlockDefinition d : conds) if (d.getId().equals(inst.getConditionDefinitionId())) { selected = d; break; }
            if (selected != null) condBox.setSelectedItem(selected);

            gc.gridx = 0; gc.gridwidth = 2; content.add(new JLabel("Condition"), gc); gc.gridy++;
            gc.gridx = 0; gc.gridwidth = 2; content.add(condBox, gc); gc.gridy++;

            JPanel paramsPanel = new JPanel(new GridBagLayout());
            gc.gridx = 0; gc.gridwidth = 2; content.add(paramsPanel, gc); gc.gridy++;

            JCheckBox negate = new JCheckBox("Negate condition");
            negate.setSelected(inst.isNegateCondition());
            negate.addActionListener(e -> inst.setNegateCondition(negate.isSelected()));
            gc.gridx = 0; gc.gridwidth = 2; content.add(negate, gc); gc.gridy++;

            Runnable rebuild = () -> {
                paramsPanel.removeAll();
                BlockDefinition def = (BlockDefinition) condBox.getSelectedItem();
                if (def != null) {
                    inst.setConditionDefinitionId(def.getId());
                    // resize args list to correct size
                    while (inst.getConditionArgs().size() < def.getParams().size()) inst.getConditionArgs().add("");
                    while (inst.getConditionArgs().size() > def.getParams().size()) inst.getConditionArgs().remove(inst.getConditionArgs().size()-1);
                    GridBagConstraints pgc = new GridBagConstraints();
                    pgc.insets = new Insets(4,4,4,4);
                    pgc.fill = GridBagConstraints.HORIZONTAL;
                    pgc.gridy = 0;
                    for (int i = 0; i < def.getParams().size(); i++) {
                        JLabel l = new JLabel(labelForParam(def, i, true));
                        JComponent field = paramField(def, def.getParams().get(i).getType(), inst, i, true);
                        pgc.gridx = 0; pgc.weightx = 0; paramsPanel.add(l, pgc);
                        pgc.gridx = 1; pgc.weightx = 1; paramsPanel.add(field, pgc);
                        pgc.gridy++;
                    }
                }
                paramsPanel.revalidate();
                paramsPanel.repaint();
            };

            condBox.addActionListener(e -> rebuild.run());
            rebuild.run();

            JButton save = new JButton("Save");
            save.addActionListener(e -> dlg.dispose());
            gc.gridx = 0; gc.gridwidth = 2; content.add(save, gc);

            dlg.setContentPane(content);
            dlg.pack();
            dlg.setLocationRelativeTo(this);
            dlg.setVisible(true);
            scriptList.repaint();
        }
    }

    private void updatePairHighlight() {
        int idx = scriptList.getSelectedIndex();
        int match = -1;
        if (idx >= 0 && idx < scriptModel.size()) {
            BlockInstance b = scriptModel.get(idx);
            if (b.getKind() == BlockInstance.Kind.IF) {
                match = findMatchingEndIf(idx);
            } else if (b.getKind() == BlockInstance.Kind.ENDIF) {
                match = findMatchingIf(idx);
            }
        }
        pairHighlightIndex = match;
        scriptList.repaint();
    }

    private JComponent paramField(BlockDefinition def, Class<?> type, BlockInstance inst, int index, boolean isCondition) {
        if (type == boolean.class || type == Boolean.class) {
            JCheckBox cb = new JCheckBox();
            cb.setSelected(Boolean.parseBoolean(isCondition ? safeCondArg(inst, index) : safeArg(inst, index)));
            cb.addActionListener(a -> {
                if (isCondition) inst.getConditionArgs().set(index, Boolean.toString(cb.isSelected()));
                else inst.getArgs().set(index, Boolean.toString(cb.isSelected()));
            });
            return cb;
        }
        if (type.isEnum()) {
            Object[] values = type.getEnumConstants();
            JComboBox<Object> box = new JComboBox<>(values);
            String cur = isCondition ? safeCondArg(inst, index) : safeArg(inst, index);
            if (cur != null && !cur.isEmpty()) {
                for (int i = 0; i < values.length; i++) if (values[i].toString().equals(cur)) box.setSelectedIndex(i);
            }
            box.addActionListener(a -> {
                if (isCondition) inst.getConditionArgs().set(index, box.getSelectedItem().toString());
                else inst.getArgs().set(index, box.getSelectedItem().toString());
            });
            return box;
        }
        // String
        String label = labelForParam(def, index, isCondition);
        if ("action".equalsIgnoreCase(label)) {
            String[] actions = new String[]{"Use","Wear","Equip","Eat","Drink","Wield","Drop","Cast","Bury","Light","Examine"};
            JComboBox<String> box = new JComboBox<>(actions);
            box.setEditable(true);
            String cur = isCondition ? safeCondArg(inst, index) : safeArg(inst, index);
            if (cur != null && !cur.isEmpty()) box.setSelectedItem(cur);
            box.addActionListener(a -> {
                String val = String.valueOf(box.getSelectedItem());
                if (isCondition) inst.getConditionArgs().set(index, val);
                else inst.getArgs().set(index, val);
            });
            return box;
        }
        JTextField tf = new JTextField(isCondition ? safeCondArg(inst, index) : safeArg(inst, index));
        tf.setToolTipText(label);
        tf.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
            if (isCondition) inst.getConditionArgs().set(index, tf.getText());
            else inst.getArgs().set(index, tf.getText());
        }));
        return tf;
    }

    private String safeArg(BlockInstance inst, int idx) {
        return idx < inst.getArgs().size() ? inst.getArgs().get(idx) : "";
    }

    private String safeCondArg(BlockInstance inst, int idx) {
        return idx < inst.getConditionArgs().size() ? inst.getConditionArgs().get(idx) : "";
    }

    private int computeIndent(int index, BlockInstance current) {
        int n = scriptModel.size();
        int[] preDepth = new int[n];
        int[] endToIf = new int[n];
        java.util.Arrays.fill(endToIf, -1);
        java.util.Deque<Integer> stack = new java.util.ArrayDeque<>();
        int depth = 0;
        for (int i = 0; i < n; i++) {
            preDepth[i] = Math.max(depth, 0);
            BlockInstance b = scriptModel.get(i);
            if (b.getKind() == BlockInstance.Kind.IF) {
                stack.push(i);
                depth++;
            } else if (b.getKind() == BlockInstance.Kind.ENDIF) {
                if (!stack.isEmpty()) {
                    int ifIdx = stack.pop();
                    endToIf[i] = ifIdx;
                }
                depth = Math.max(depth - 1, 0);
            }
        }

        BlockInstance.Kind kind = current != null ? current.getKind() : null;
        if (kind == BlockInstance.Kind.ENDIF) {
            int ifIdx = endToIf[index];
            if (ifIdx >= 0) return preDepth[ifIdx];
            return Math.max(preDepth[index] - 1, 0);
        } else if (kind == BlockInstance.Kind.ELSE) {
            return Math.max(preDepth[index] - 1, 0);
        } else {
            return preDepth[index];
        }
    }

    private String formatArgs(BlockDefinition def, List<String> values) {
        StringBuilder sb = new StringBuilder();
        sb.append('(');
        for (int i = 0; i < def.getParams().size(); i++) {
            Class<?> t = def.getParams().get(i).getType();
            String v = i < values.size() ? values.get(i) : null;
            if (i > 0) sb.append(", ");
            if (v == null || v.isEmpty()) {
                sb.append("?");
            } else if (t == String.class) {
                sb.append('"').append(v).append('"');
            } else if (t.isEnum()) {
                sb.append(t.getSimpleName()).append('.').append(v);
            } else {
                sb.append(v);
            }
        }
        sb.append(')');
        return sb.toString();
    }

    private String labelForParam(BlockDefinition def, int paramIndex, boolean isCondition) {
        BlockParam bp = def.getParams().get(paramIndex);
        String name = bp.getName();
        if (name != null && !name.startsWith("arg")) return name;
        String base = bp.getType().getSimpleName();
        if (def.getMethod() != null) {
            String m = def.getMethod().getName();
            Class<?> t = bp.getType();
            if (t == String.class) {
                if (m.toLowerCase().contains("item")) return "name";
            }
            if ((t == boolean.class || t == Boolean.class)) {
                if (m.equals("hasItem") || m.toLowerCase().contains("hasitem")) return "exact";
            }
        }
        return base;
    }

    private void runScript() {
        List<BlockInstance> list = new ArrayList<>();
        for (int i = 0; i < scriptModel.size(); i++) list.add(scriptModel.get(i));
        runner.load(list);
        runner.setLogSink(s -> SwingUtilities.invokeLater(() -> {
            if (logArea.getText().length() > 100000) logArea.setText("");
            logArea.append(s + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        }));
        runner.start();
    }

    private void filterAvailable(String q) {
        String query = q == null ? "" : q.trim().toLowerCase();
        for (Section s : sections) {
            s.model.clear();
            for (BlockDefinition d : s.allDefs) {
                String name = d.getMethod() != null ? d.getMethod().getName().toLowerCase() : d.getDisplayName().toLowerCase();
                String group = d.getGroup() == null ? "" : d.getGroup().toLowerCase();
                if (query.isEmpty() || name.contains(query) || group.contains(query)) {
                    s.model.addElement(d);
                }
            }
            s.list.clearSelection();
        }
        availableContainer.revalidate();
        availableContainer.repaint();
    }

    private void saveScript() {
        String name = JOptionPane.showInputDialog(this, "Script name");
        if (name == null || name.trim().isEmpty()) return;
        File f = new File(ScriptRunner.scriptsDir(), name + ".json");
        List<BlockInstance> list = new ArrayList<>();
        for (int i = 0; i < scriptModel.size(); i++) list.add(scriptModel.get(i));
        try {
            ScriptRunner.saveToFile(f, list);
            JOptionPane.showMessageDialog(this, "Saved: " + f.getAbsolutePath());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage());
        }
    }

    private void loadScript() {
        pushSnapshot();
        JFileChooser fc = new JFileChooser(ScriptRunner.scriptsDir());
        int res = fc.showOpenDialog(this);
        if (res == JFileChooser.APPROVE_OPTION) {
            try {
                List<BlockInstance> list = ScriptRunner.loadFromFile(fc.getSelectedFile());
                scriptModel.clear();
                for (BlockInstance bi : list) scriptModel.addElement(bi);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Load failed: " + ex.getMessage());
            }
        }
    }

    private static class SimpleDocumentListener implements javax.swing.event.DocumentListener {
        private final Runnable cb;
        SimpleDocumentListener(Runnable cb) { this.cb = cb; }
        public void insertUpdate(javax.swing.event.DocumentEvent e) { cb.run(); }
        public void removeUpdate(javax.swing.event.DocumentEvent e) { cb.run(); }
        public void changedUpdate(javax.swing.event.DocumentEvent e) { cb.run(); }
    }

    private static class DataHandlerWrapper implements Transferable {
        private final Object value;
        DataHandlerWrapper(Object v) { this.value = v; }
        public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.stringFlavor}; }
        public boolean isDataFlavorSupported(DataFlavor flavor) { return flavor.equals(DataFlavor.stringFlavor); }
        public Object getTransferData(DataFlavor flavor) { return value.toString(); }
    }

    private static class Section {
        final String title;
        final List<BlockDefinition> allDefs;
        final DefaultListModel<BlockDefinition> model;
        final JList<BlockDefinition> list;
        final JScrollPane scroller;
        final JToggleButton header;
        final JPanel panel;
        Section(String title, List<BlockDefinition> allDefs, DefaultListModel<BlockDefinition> model,
                JList<BlockDefinition> list, JScrollPane scroller, JToggleButton header, JPanel panel) {
            this.title = title;
            this.allDefs = allDefs;
            this.model = model;
            this.list = list;
            this.scroller = scroller;
            this.header = header;
            this.panel = panel;
        }
    }

    private static final class UndoAction extends AbstractAction {
        private final ScriptBuilderPanel panel;
        UndoAction(ScriptBuilderPanel p) { this.panel = p; }
        @Override public void actionPerformed(java.awt.event.ActionEvent e) { panel.undo(); }
    }

    private static final class RedoAction extends AbstractAction {
        private final ScriptBuilderPanel panel;
        RedoAction(ScriptBuilderPanel p) { this.panel = p; }
        @Override public void actionPerformed(java.awt.event.ActionEvent e) { panel.redo(); }
    }

    private void pushSnapshot() {
        if (applyingUndo || applyingRedo) return;
        history.push(snapshotCurrent());
        redoStack.clear();
    }

    private List<BlockInstance> snapshotCurrent() {
        List<BlockInstance> snap = new ArrayList<>();
        for (int i = 0; i < scriptModel.size(); i++) snap.add(deepCopy(scriptModel.get(i)));
        return snap;
    }

    private void restoreSnapshot(List<BlockInstance> snap) {
        scriptModel.clear();
        for (BlockInstance bi : snap) scriptModel.addElement(deepCopy(bi));
        if (!scriptModel.isEmpty()) scriptList.setSelectedIndex(Math.min(scriptList.getSelectedIndex(), scriptModel.size()-1));
    }

    private void undo() {
        if (history.isEmpty()) return;
        applyingUndo = true;
        try {
            redoStack.push(snapshotCurrent());
            List<BlockInstance> snap = history.pop();
            restoreSnapshot(snap);
        } finally {
            applyingUndo = false;
        }
    }

    private void redo() {
        if (redoStack.isEmpty()) return;
        applyingRedo = true;
        try {
            history.push(snapshotCurrent());
            List<BlockInstance> snap = redoStack.pop();
            restoreSnapshot(snap);
        } finally {
            applyingRedo = false;
        }
    }

    private static BlockInstance deepCopy(BlockInstance x) {
        BlockInstance y = new BlockInstance();
        y.setKind(x.getKind());
        y.setDefinitionId(x.getDefinitionId());
        y.setArgs(new ArrayList<>(x.getArgs()));
        y.setConditionDefinitionId(x.getConditionDefinitionId());
        y.setConditionArgs(new ArrayList<>(x.getConditionArgs()));
        y.setNegateCondition(x.isNegateCondition());
        y.setComment(x.getComment());
        return y;
    }
    private void duplicateSelected() {
        pushSnapshot();
        int idx = scriptList.getSelectedIndex();
        if (idx < 0) return;
        int start = idx, end = idx;
        BlockInstance b = scriptModel.get(idx);
        if (b.getKind() == BlockInstance.Kind.IF) {
            int e = findMatchingEndIf(idx);
            if (e > idx) end = e;
        } else if (b.getKind() == BlockInstance.Kind.ENDIF) {
            int s = findMatchingIf(idx);
            if (s >= 0) start = s;
        }
        int insertAt = idx + 1;
        for (int i = start; i <= end; i++) {
            scriptModel.add(insertAt + (i - start), deepCopy(scriptModel.get(i)));
        }
        scriptList.setSelectedIndex(insertAt);
    }
}
