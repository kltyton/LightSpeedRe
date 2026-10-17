package io.github.kltyton.temple.ui;

import io.github.kltyton.temple.catalog.VersionCatalog;
import io.github.kltyton.temple.workspace.TargetPlan;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

public final class TargetSelectionPanel extends JPanel {
    private enum Language {
        CHINESE("简体中文", Locale.CHINESE), ENGLISH("English", Locale.ENGLISH);
        final String name;
        final Locale locale;
        Language(String name, Locale locale) { this.name = name; this.locale = locale; }
        @Override public String toString() { return name; }
    }

    private final JComboBox<Language> language = new JComboBox<>(Language.values());
    private final JComboBox<String> minecraft = new JComboBox<>(), loader = new JComboBox<>(),
            version = new JComboBox<>(), api = new JComboBox<>();
    private final JCheckBox preview = new JCheckBox();
    private final JLabel status = new JLabel(), toolchain = new JLabel();
    private final Map<String, JLabel> labels = new LinkedHashMap<>();
    private final DefaultTableModel model = new DefaultTableModel(0, 5) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final List<Map<String, String>> selected = new ArrayList<>();
    private final Consumer<List<Map<String, String>>> changed;
    private final JButton add = new JButton(), refresh = new JButton(), remove = new JButton();
    private final JPanel targets = new JPanel(new BorderLayout(0, 8));
    private final JPanel catalogControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private UiText text;
    private VersionCatalog catalog;
    private int javaVersion;
    private boolean ready;
    private String statusKey = "loading";
    private int statusCount;
    private int gameRequest;

    public TargetSelectionPanel(Function<String, Object> parser, List<Map<String, String>> initial,
                                Consumer<List<Map<String, String>>> changed) {
        super(new BorderLayout(0, 12));
        this.changed = changed;
        language.setSelectedItem("zh".equals(Locale.getDefault().getLanguage()) ? Language.CHINESE : Language.ENGLISH);
        text = new UiText(((Language) language.getSelectedItem()).locale);
        JPanel selectors = new JPanel(new GridLayout(0, 2, 12, 8));
        for (Object[] pair : new Object[][]{
                {"language", language}, {"minecraft", minecraft}, {"loader", loader},
                {"loaderVersion", version}, {"fabricApi", api}, {"toolchain", toolchain}}) {
            JLabel label = new JLabel();
            labels.put(pair[0].toString(), label);
            selectors.add(label);
            selectors.add((Component) pair[1]);
        }
        catalogControls.add(refresh);
        catalogControls.add(preview);
        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.add(selectors, BorderLayout.CENTER);
        JPanel catalogStatus = new JPanel(new BorderLayout(0, 8));
        catalogStatus.add(catalogControls, BorderLayout.NORTH);
        catalogStatus.add(status, BorderLayout.SOUTH);
        top.add(catalogStatus, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        JTable table = new JTable(model);
        table.setRowHeight(Math.max(26, table.getFont().getSize() + 12));
        table.setFillsViewportHeight(true);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(660, 190));
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        targets.add(scroll, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.add(add);
        buttons.add(remove);
        targets.add(buttons, BorderLayout.SOUTH);
        add(targets, BorderLayout.CENTER);
        translate();
        initial.forEach(row -> { selected.add(new LinkedHashMap<>(row)); append(row); });
        for (JComboBox<String> box : List.of(minecraft, loader, version, api)) box.setEditable(false);
        version.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                if (catalog != null && "fabric".equals(loader.getSelectedItem())
                        && Objects.equals(value, catalog.recommendedFabricLoader()))
                    setText(value + " (" + text.get("recommended") + ")");
                return this;
            }
        });
        language.addActionListener(event -> {
            UiText old = text;
            text = new UiText(((Language) language.getSelectedItem()).locale);
            translate();
            updateChoiceLabels();
            version.repaint();
            firePropertyChange("language", old, text);
        });
        minecraft.addActionListener(event -> loadGame());
        loader.addActionListener(event -> updateLoader());
        version.addActionListener(event -> updateReady());
        api.addActionListener(event -> updateReady());
        preview.addActionListener(event -> updateLoader());
        add.addActionListener(event -> addSelection());
        remove.addActionListener(event -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                selected.remove(row);
                model.removeRow(row);
                changed.accept(selections());
                setStatus("selected", selected.size());
            }
        });
        refresh.addActionListener(event -> loadCatalog(parser));
        changed.accept(selections());
        loadCatalog(parser);
    }

    public UiText messages() { return text; }

    public JPanel targetsPanel() { return targets; }

    public void configurationRows(BiConsumer<JLabel, JComponent> row) {
        row.accept(labels.get("language"), language);
        row.accept(labels.get("minecraft"), minecraft);
        JPanel loaderChoices = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 0.35;
        loaderChoices.add(loader, constraints);
        constraints.weightx = 0;
        constraints.insets = new Insets(0, 12, 0, 8);
        loaderChoices.add(labels.get("loaderVersion"), constraints);
        constraints.weightx = 0.65;
        constraints.insets = new Insets(0, 0, 0, 0);
        loaderChoices.add(version, constraints);
        row.accept(labels.get("loader"), loaderChoices);
        row.accept(labels.get("fabricApi"), api);
        row.accept(labels.get("toolchain"), toolchain);
        row.accept(null, catalogControls);
        row.accept(null, status);
    }

    public List<Map<String, String>> selections() {
        return selected.stream().map(LinkedHashMap::new).map(value -> (Map<String, String>) value).toList();
    }

    private void translate() {
        labels.forEach((key, label) -> label.setText(text.get(key)));
        add.setText(text.get("add"));
        refresh.setText(text.get("refresh"));
        remove.setText(text.get("remove"));
        preview.setText(text.get("preview"));
        model.setColumnIdentifiers(new String[]{text.get("minecraft"), text.get("loader"),
                text.get("loaderVersion"), text.get("fabricApi"), "Java"});
        showStatus();
    }

    private void setStatus(String key, int count) {
        statusKey = key;
        statusCount = count;
        showStatus();
    }

    private void showStatus() {
        status.setText(("releases".equals(statusKey) || "selected".equals(statusKey)
                ? statusCount + " " : "") + text.get(statusKey));
    }

    private void append(Map<String, String> row) {
        model.addRow(new Object[]{row.get("minecraft_version"), row.get("loader"), row.get("loader_version"),
                row.getOrDefault("fabric_api_version", ""), row.get("java_version")});
    }

    private void loadCatalog(Function<String, Object> parser) {
        ready = false;
        gameRequest++;
        javaVersion = 0;
        add.setEnabled(false);
        refresh.setEnabled(false);
        setStatus("loading", 0);
        String previousGame = (String) minecraft.getSelectedItem();
        minecraft.setEnabled(false);
        loader.setEnabled(false);
        new SwingWorker<VersionCatalog, Void>() {
            @Override protected VersionCatalog doInBackground() throws Exception {
                VersionCatalog data = new VersionCatalog(parser);
                data.load();
                return data;
            }
            @Override protected void done() {
                refresh.setEnabled(true);
                try {
                    catalog = get();
                    minecraft.setModel(new DefaultComboBoxModel<>(catalog.minecraftVersions().toArray(String[]::new)));
                    if (catalog.minecraftVersions().contains(previousGame)) minecraft.setSelectedItem(previousGame);
                    minecraft.setEnabled(true);
                    loader.setEnabled(true);
                    setStatus("releases", catalog.minecraftVersions().size());
                    loadGame();
                } catch (Exception error) {
                    ready = false;
                    add.setEnabled(false);
                    setStatus("downloadFailed", 0);
                    showError(error);
                }
            }
        }.execute();
    }

    private void loadGame() {
        if (catalog == null || !minecraft.isEnabled() || minecraft.getSelectedItem() == null) return;
        VersionCatalog data = catalog;
        String mc = minecraft.getSelectedItem().toString();
        int request = ++gameRequest;
        ready = false;
        javaVersion = 0;
        add.setEnabled(false);
        toolchain.setText(text.get("loadingVersions"));
        loader.setModel(new DefaultComboBoxModel<>(data.loaders(mc).toArray(String[]::new)));
        updateLoader();
        new SwingWorker<Integer, Void>() {
            @Override protected Integer doInBackground() {
                if (data.loaders(mc).contains("fabric")) data.loadCompatibleVersions(mc);
                return data.javaVersion(mc);
            }
            @Override protected void done() {
                if (request != gameRequest || data != catalog || !mc.equals(minecraft.getSelectedItem())) return;
                try {
                    javaVersion = get();
                    ready = true;
                    updateLoader();
                    setStatus("releases", data.minecraftVersions().size());
                } catch (Exception error) {
                    ready = false;
                    setStatus("downloadFailed", 0);
                    showError(error);
                }
                updateReady();
            }
        }.execute();
    }

    private void updateLoader() {
        if (catalog == null || minecraft.getSelectedItem() == null || loader.getSelectedItem() == null) return;
        String mc = minecraft.getSelectedItem().toString(), platform = loader.getSelectedItem().toString();
        String oldVersion = (String) version.getSelectedItem(), oldApi = (String) api.getSelectedItem();
        List<String> versions = catalog.loaderVersions(platform, mc).stream()
                .filter(value -> preview.isSelected() || !catalog.preview(value)).toList();
        List<String> apiVersions = "fabric".equals(platform) ? catalog.apiVersions(mc).stream()
                .filter(value -> preview.isSelected() || !catalog.preview(value)).toList() : List.of();
        version.setModel(new DefaultComboBoxModel<>(versions.toArray(String[]::new)));
        api.setModel(new DefaultComboBoxModel<>(apiVersions.toArray(String[]::new)));
        if (versions.contains(oldVersion)) version.setSelectedItem(oldVersion);
        if (apiVersions.contains(oldApi)) api.setSelectedItem(oldApi);
        api.setEnabled("fabric".equals(platform));
        updateChoiceLabels();
        updateReady();
        if (ready) {
            toolchain.setText("Java " + javaVersion + " · Gradle " + wrapperVersion(platform, mc));
            toolchain.setToolTipText(null);
        }
    }

    private String wrapperVersion(String platform, String mc) {
        if ("fabric".equals(platform) && (javaVersion >= 25 || VersionCatalog.compare(mc, "1.21.4") > 0)) return "9.7.1";
        return javaVersion >= 25 ? "9.5.1" : "8.14.4";
    }

    private void updateChoiceLabels() {
        labels.get("loaderVersion").setText(text.get("loaderVersion") + " (" + version.getItemCount() + ")");
        labels.get("fabricApi").setText(text.get("fabricApi") + " (" + api.getItemCount() + ")");
    }

    private void showError(Exception error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        toolchain.setText(text.get("errorDetail"));
        toolchain.setToolTipText(cause.toString());
    }

    private void updateReady() {
        add.setEnabled(ready && javaVersion > 0 && minecraft.getSelectedItem() != null
                && loader.getSelectedItem() != null && version.getSelectedItem() != null
                && (!"fabric".equals(loader.getSelectedItem()) || api.getSelectedItem() != null));
    }

    private void addSelection() {
        if (!add.isEnabled()) { setStatus("notReady", 0); return; }
        String mc = minecraft.getSelectedItem().toString(), platform = loader.getSelectedItem().toString();
        if (selected.stream().anyMatch(row -> mc.equals(row.get("minecraft_version")) && platform.equals(row.get("loader")))) {
            setStatus("duplicate", 0);
            return;
        }
        Map<String, String> row = new LinkedHashMap<>();
        row.put("minecraft_version", mc);
        row.put("loader", platform);
        row.put("loader_version", version.getSelectedItem().toString());
        row.put("java_version", Integer.toString(javaVersion));
        row.put("gradle_java_version", Integer.toString(javaVersion >= 25 ? 25 : 21));
        row.put("blueprint", TargetPlan.blueprint(platform, mc, javaVersion));
        row.put("wrapper_version", wrapperVersion(platform, mc));
        if ("fabric".equals(platform) && VersionCatalog.compare(mc, "1.21.4") > 0) {
            row.put("loom_version", "1.18.2");
        }
        if ("fabric".equals(platform)) row.put("fabric_api_version", api.getSelectedItem().toString());
        if ("forge".equals(platform) && VersionCatalog.compare(mc, "1.20.1") > 0) row.put("forgegradle_version", "6.0.54");
        selected.add(row);
        append(row);
        changed.accept(selections());
        setStatus("selected", selected.size());
    }
}
