package org.example;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TCPProxyManagerGUI extends JFrame {

    private static final String VERSION = "v1.1";  // 版本常量
    private static final String CONFIG_FILE = "config.json";
    private final Map<String, List<ProxyConfig>> environments = new HashMap<>();
    private final Map<ProxyConfig, ManagedProxy> activeProxies = new HashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private JLabel titleLabel;
    private JTable proxyTable;
    private DefaultTableModel tableModel;
    private JButton startButton, stopButton, startAllButton, stopAllButton, addMappingButton, deleteMappingButton, renameEnvironmentButton;
    private JTextArea logArea;
    private JScrollPane logScrollPane;
    private String currentEnvironment;
    private javax.swing.Timer statusUpdateTimer;

    public TCPProxyManagerGUI() {
        super("TCP 代理管理器 " + VERSION);  // 修改窗口标题
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 700);

        // 设置字符编码
        System.setProperty("file.encoding", "UTF-8");

        loadConfig();
        initComponents();
        layoutComponents();
        setLocationRelativeTo(null);
        startStatusUpdateTimer();

        logMessage("程序启动成功");
    }

    private void startStatusUpdateTimer() {
        statusUpdateTimer = new javax.swing.Timer(1000, e -> updateAllProxyStates());
        statusUpdateTimer.start();
    }

    private void updateAllProxyStates() {
        SwingUtilities.invokeLater(() -> {
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                ProxyConfig config = getProxyConfigFromRow(i);
                if (config != null) {
                    ManagedProxy proxy = activeProxies.get(config);
                    if (proxy != null) {
                        ProxyState currentState = (ProxyState) tableModel.getValueAt(i, 5);
                        ProxyState newState = proxy.getState();
                        if (currentState != newState) {
                            tableModel.setValueAt(newState, i, 5);
                        }
                    }
                }
            }
        });
    }

    private void loadConfig() {
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(CONFIG_FILE), StandardCharsets.UTF_8)) {

            // 读取所有内容到字符串
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[8192];
            int len;
            while ((len = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, len);
            }

            JSONObject config = JSON.parseObject(sb.toString());

            for (Map.Entry<String, Object> entry : config.entrySet()) {
                String env = entry.getKey();
                JSONArray proxies = (JSONArray) entry.getValue();
                List<ProxyConfig> proxyList = new ArrayList<>();

                for (int i = 0; i < proxies.size(); i++) {
                    JSONObject proxyJson = proxies.getJSONObject(i);
                    ProxyConfig configObj = new ProxyConfig(
                            proxyJson.getIntValue("localPort"),
                            proxyJson.getString("remoteHost"),
                            proxyJson.getIntValue("remotePort"),
                            proxyJson.containsKey("desc") ? proxyJson.getString("desc") : "未命名代理"
                    );
                    proxyList.add(configObj);
                }

                environments.put(env, proxyList);
            }
        } catch (Exception e) {
            environments.put("默认环境", Collections.emptyList());
            logError("配置文件加载失败: " + e.getMessage());
        }
    }

    private void saveConfig() {
        try {
            JSONObject config = new JSONObject();
            for (Map.Entry<String, List<ProxyConfig>> entry : environments.entrySet()) {
                JSONArray proxies = new JSONArray();
                for (ProxyConfig proxyConfig : entry.getValue()) {
                    JSONObject proxyJson = new JSONObject();
                    proxyJson.put("localPort", proxyConfig.getLocalPort());
                    proxyJson.put("remoteHost", proxyConfig.getRemoteHost());
                    proxyJson.put("remotePort", proxyConfig.getRemotePort());
                    proxyJson.put("desc", proxyConfig.getDescription());
                    proxies.add(proxyJson);
                }
                config.put(entry.getKey(), proxies);
            }

            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(CONFIG_FILE), StandardCharsets.UTF_8)) {
                writer.write(config.toJSONString());
            }
            logMessage("配置文件保存成功");
        } catch (Exception e) {
            logError("配置文件保存失败: " + e.getMessage());
        }
    }

    private void saveCurrentEnvironment() {
        if (currentEnvironment == null) {
            logError("请先选择一个环境");
            return;
        }

        try {
            // 从表格中读取当前数据更新到environments
            List<ProxyConfig> updatedConfigs = new ArrayList<>();
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                String desc = (String) tableModel.getValueAt(i, 1);
                String remoteHost = (String) tableModel.getValueAt(i, 2);
                Object remotePortObj = tableModel.getValueAt(i, 3);
                Object localPortObj = tableModel.getValueAt(i, 4);

                // 处理可能的空值和类型转换
                int remotePort = 0;
                int localPort = 0;

                if (remotePortObj instanceof Integer) {
                    remotePort = (Integer) remotePortObj;
                } else if (remotePortObj instanceof String) {
                    try {
                        remotePort = Integer.parseInt((String) remotePortObj);
                    } catch (NumberFormatException e) {
                        logError("第" + (i + 1) + "行远程端口格式错误: " + remotePortObj);
                        continue;
                    }
                }

                if (localPortObj instanceof Integer) {
                    localPort = (Integer) localPortObj;
                } else if (localPortObj instanceof String) {
                    try {
                        localPort = Integer.parseInt((String) localPortObj);
                    } catch (NumberFormatException e) {
                        logError("第" + (i + 1) + "行本地端口格式错误: " + localPortObj);
                        continue;
                    }
                }

                if (desc != null && !desc.trim().isEmpty() &&
                        remoteHost != null && !remoteHost.trim().isEmpty() &&
                        remotePort > 0 && localPort > 0) {
                    updatedConfigs.add(new ProxyConfig(localPort, remoteHost.trim(), remotePort, desc.trim()));
                }
            }

            environments.put(currentEnvironment, updatedConfigs);
            saveConfig();
            logMessage("环境 '" + currentEnvironment + "' 配置已保存");

            // 重新加载当前环境以刷新显示
            switchEnvironment(currentEnvironment);
        } catch (Exception e) {
            logError("保存环境配置失败: " + e.getMessage());
        }
    }

    private void saveCurrentEnvironmentToMemory() {
        if (currentEnvironment == null) {
            return;
        }

        List<ProxyConfig> updatedConfigs = new ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            String desc = (String) tableModel.getValueAt(i, 1);
            String remoteHost = (String) tableModel.getValueAt(i, 2);
            Object remotePortObj = tableModel.getValueAt(i, 3);
            Object localPortObj = tableModel.getValueAt(i, 4);

            // 处理可能的空值和类型转换
            int remotePort = 0;
            int localPort = 0;

            if (remotePortObj instanceof Integer) {
                remotePort = (Integer) remotePortObj;
            } else if (remotePortObj instanceof String) {
                try {
                    remotePort = Integer.parseInt((String) remotePortObj);
                } catch (NumberFormatException e) {
                    continue;
                }
            }

            if (localPortObj instanceof Integer) {
                localPort = (Integer) localPortObj;
            } else if (localPortObj instanceof String) {
                try {
                    localPort = Integer.parseInt((String) localPortObj);
                } catch (NumberFormatException e) {
                    continue;
                }
            }

            if (desc != null && !desc.trim().isEmpty() &&
                    remoteHost != null && !remoteHost.trim().isEmpty() &&
                    remotePort > 0 && localPort > 0) {
                updatedConfigs.add(new ProxyConfig(localPort, remoteHost.trim(), remotePort, desc.trim()));
            }
        }

        environments.put(currentEnvironment, updatedConfigs);
    }

    private void initComponents() {
        titleLabel = new JLabel("选择环境", JLabel.CENTER);
        titleLabel.setFont(new Font("微软雅黑", Font.BOLD, 24));

        JMenuBar menuBar = new JMenuBar();

        // 选择环境菜单
        JMenu environmentMenu = new JMenu("选择环境");
        updateEnvironmentMenu(environmentMenu);
        menuBar.add(environmentMenu);

        // 新增环境菜单
        JMenu addEnvironmentMenu = new JMenu("新增环境");
        JMenuItem addEnvItem = new JMenuItem("新增环境");
        addEnvItem.addActionListener(this::addEnvironment);
        addEnvironmentMenu.add(addEnvItem);
        menuBar.add(addEnvironmentMenu);

        // 删除环境菜单
        JMenu deleteEnvironmentMenu = new JMenu("删除环境");
        JMenuItem deleteEnvItem = new JMenuItem("删除当前环境");
        deleteEnvItem.addActionListener(this::deleteEnvironment);
        deleteEnvironmentMenu.add(deleteEnvItem);
        menuBar.add(deleteEnvironmentMenu);

        // 保存菜单
        JMenu saveMenu = new JMenu("保存");
        JMenuItem saveItem = new JMenuItem("保存当前环境");
        saveItem.addActionListener(e -> saveCurrentEnvironment());
        saveMenu.add(saveItem);
        menuBar.add(saveMenu);

        // 关于菜单
        JMenu aboutMenu = new JMenu("关于");
        JMenuItem aboutItem = new JMenuItem("关于");
        aboutItem.addActionListener(this::showAbout);
        aboutMenu.add(aboutItem);
        menuBar.add(aboutMenu);

        setJMenuBar(menuBar);

        String[] columnNames = {"选择", "描述", "远程地址", "远程端口", "本地端口", "状态"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public Class<?> getColumnClass(int column) {
                if (column == 0) return Boolean.class;
                if (column == 5) return ProxyState.class;
                return String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                if (column == 0) return true; // 选择列总是可编辑

                // 对于非运行状态的行，允许编辑描述、远程地址、远程端口、本地端口
                if (column >= 1 && column <= 4) {
                    ProxyState state = (ProxyState) getValueAt(row, 5);
                    return state != ProxyState.RUNNING;
                }

                return false;
            }
        };

        proxyTable = new JTable(tableModel);
        proxyTable.getColumnModel().getColumn(0).setPreferredWidth(50);
        proxyTable.getColumnModel().getColumn(0).setMaxWidth(80);
        proxyTable.getColumnModel().getColumn(5).setCellRenderer(new StatusRenderer());

        proxyTable.setRowHeight(40);
        proxyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        proxyTable.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        proxyTable.getTableHeader().setFont(new Font("微软雅黑", Font.BOLD, 14));

        JCheckBox headerCheckBox = new JCheckBox();
        headerCheckBox.setHorizontalAlignment(JCheckBox.CENTER);
        headerCheckBox.addActionListener(e -> {
            boolean selected = headerCheckBox.isSelected();
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                tableModel.setValueAt(selected, i, 0);
            }
        });

        proxyTable.getColumnModel().getColumn(0).setHeaderRenderer((table, value, isSelected, hasFocus, row, column) -> headerCheckBox);

        // 初始化日志区域，解决中文乱码问题
        logArea = new JTextArea();
        logArea.setEditable(false);
        // 使用支持中文的字体
        logArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        logArea.setBackground(new Color(245, 245, 245));
        logScrollPane = new JScrollPane(logArea);
        logScrollPane.setPreferredSize(new Dimension(0, 150));
        logScrollPane.setBorder(BorderFactory.createTitledBorder("日志输出"));

        startButton = new JButton("启动代理");
        startButton.addActionListener(this::startSelectedProxies);

        stopButton = new JButton("停止代理");
        stopButton.addActionListener(this::stopSelectedProxies);

        startAllButton = new JButton("全部启动");
        startAllButton.addActionListener(e -> startAllProxies());

        stopAllButton = new JButton("全部停止");
        stopAllButton.addActionListener(e -> stopAllProxies());

        addMappingButton = new JButton("添加映射");
        addMappingButton.addActionListener(this::addMapping);

        // 添加新按钮
        deleteMappingButton = new JButton("删除映射");
        deleteMappingButton.addActionListener(this::deleteMapping);

        renameEnvironmentButton = new JButton("环境重命名");
        renameEnvironmentButton.addActionListener(this::renameEnvironment);

        Font buttonFont = new Font("微软雅黑", Font.PLAIN, 16);
        startButton.setFont(buttonFont);
        stopButton.setFont(buttonFont);
        startAllButton.setFont(buttonFont);
        stopAllButton.setFont(buttonFont);
        addMappingButton.setFont(buttonFont);
        deleteMappingButton.setFont(buttonFont);
        renameEnvironmentButton.setFont(buttonFont);
    }

    private void addMapping(ActionEvent e) {
        if (currentEnvironment == null) {
            logError("请先选择一个环境");
            return;
        }

        // 添加新行到表格
        tableModel.addRow(new Object[]{
                Boolean.FALSE,
                "未命名",
                "",
                "",
                "",
                ProxyState.INIT
        });

        logMessage("已添加新的映射条目，请编辑后保存");
    }

    private void deleteMapping(ActionEvent e) {
        if (currentEnvironment == null) {
            logError("请先选择一个环境");
            return;
        }

        List<Integer> selectedRows = getCheckedRows();
        if (selectedRows.isEmpty()) {
            logError("请先选择要删除的映射");
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
                "确定要删除选中的 " + selectedRows.size() + " 个映射吗？",
                "删除映射",
                JOptionPane.YES_NO_OPTION);

        if (confirm == JOptionPane.YES_OPTION) {
            // 先停止选中的代理
            for (int rowIndex : selectedRows) {
                ProxyConfig config = getProxyConfigFromRow(rowIndex);
                if (config != null) {
                    ManagedProxy proxy = activeProxies.get(config);
                    if (proxy != null) {
                        proxy.stop();
                        activeProxies.remove(config);
                    }
                }
            }

            // 从后往前删除，避免索引变化影响
            Collections.sort(selectedRows, Collections.reverseOrder());
            for (int rowIndex : selectedRows) {
                tableModel.removeRow(rowIndex);
            }

            logMessage("已删除 " + selectedRows.size() + " 个映射，请保存环境配置");
        }
    }

    private void renameEnvironment(ActionEvent e) {
        if (currentEnvironment == null) {
            logError("请先选择一个环境");
            return;
        }

        String newName = (String) JOptionPane.showInputDialog(
                this,
                "请输入新的环境名称:",
                "环境重命名",
                JOptionPane.PLAIN_MESSAGE,
                null,
                null,
                currentEnvironment
        );

        if (newName != null && !newName.trim().isEmpty()) {
            newName = newName.trim();

            if (newName.equals(currentEnvironment)) {
                logMessage("环境名称未改变");
                return;
            }

            if (environments.containsKey(newName)) {
                logError("环境名称 '" + newName + "' 已存在，请选择其他名称");
                return;
            }

            String oldName = currentEnvironment;

            // 先保存当前环境的配置到内存
            saveCurrentEnvironmentToMemory();

            // 更新environments映射
            List<ProxyConfig> configs = environments.get(oldName);
            environments.remove(oldName);
            environments.put(newName, configs);

            // 立即保存到配置文件
            saveConfig();

            // 重新加载配置
            environments.clear();
            loadConfig();

            // 更新菜单
            JMenuBar menuBar = getJMenuBar();
            if (menuBar != null) {
                JMenu environmentMenu = menuBar.getMenu(0);
                updateEnvironmentMenu(environmentMenu);
            }

            // 切换到新命名的环境
            switchEnvironment(newName);

            logMessage("环境已从 '" + oldName + "' 重命名为 '" + newName + "'");
        }
    }

    private void showAbout(ActionEvent e) {
        String aboutMessage = "author: dzz\ncreated at 25-6\nversion: " + VERSION;
        JOptionPane.showMessageDialog(this, aboutMessage, "关于", JOptionPane.INFORMATION_MESSAGE);
    }

    private void updateEnvironmentMenu(JMenu environmentMenu) {
        environmentMenu.removeAll();
        for (String env : environments.keySet()) {
            JMenuItem envItem = new JMenuItem(env);
            envItem.addActionListener(e -> switchEnvironment(env));
            environmentMenu.add(envItem);
        }
    }

    private void addEnvironment(ActionEvent e) {
        String newEnvName = JOptionPane.showInputDialog(this, "请输入新环境名称:", "新增环境", JOptionPane.PLAIN_MESSAGE);
        if (newEnvName != null && !newEnvName.trim().isEmpty()) {
            newEnvName = newEnvName.trim();
            if (environments.containsKey(newEnvName)) {
                logError("环境 '" + newEnvName + "' 已存在");
                return;
            }

            environments.put(newEnvName, new ArrayList<>());
            saveConfig();

            // 更新菜单
            JMenuBar menuBar = getJMenuBar();
            if (menuBar != null) {
                JMenu environmentMenu = menuBar.getMenu(0);
                updateEnvironmentMenu(environmentMenu);
            }

            logMessage("成功添加新环境: " + newEnvName);
        }
    }

    private void deleteEnvironment(ActionEvent e) {
        if (currentEnvironment == null) {
            logError("请先选择一个环境");
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
                "确定要删除环境 '" + currentEnvironment + "' 吗？",
                "删除环境",
                JOptionPane.YES_NO_OPTION);

        if (confirm == JOptionPane.YES_OPTION) {
            String deletedEnv = currentEnvironment;

            // 先停止当前环境的所有代理
            stopAllProxies();

            // 删除环境
            environments.remove(currentEnvironment);
            saveConfig();

            // 更新菜单
            JMenuBar menuBar = getJMenuBar();
            if (menuBar != null) {
                JMenu environmentMenu = menuBar.getMenu(0);
                updateEnvironmentMenu(environmentMenu);
            }

            // 清空当前显示
            currentEnvironment = null;
            titleLabel.setText("选择环境");
            tableModel.setRowCount(0);
            activeProxies.clear();

            logMessage("成功删除环境: " + deletedEnv);
        }
    }

    private void layoutComponents() {
        JPanel contentPane = new JPanel(new BorderLayout(10, 10));
        contentPane.setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel titlePanel = new JPanel(new BorderLayout());
        titlePanel.add(titleLabel, BorderLayout.CENTER);
        titlePanel.setBorder(BorderFactory.createEmptyBorder(10, 0, 15, 0));

        JScrollPane tableScrollPane = new JScrollPane(proxyTable);
        tableScrollPane.setBorder(BorderFactory.createTitledBorder("代理状态"));

        // 修改按钮面板，增加到7个按钮
        JPanel buttonPanel = new JPanel(new GridLayout(1, 7, 10, 0));
        buttonPanel.add(startButton);
        buttonPanel.add(stopButton);
        buttonPanel.add(startAllButton);
        buttonPanel.add(stopAllButton);
        buttonPanel.add(addMappingButton);
        buttonPanel.add(deleteMappingButton);
        buttonPanel.add(renameEnvironmentButton);

        // 中间面板包含表格和日志
        JPanel centerPanel = new JPanel(new BorderLayout(0, 10));
        centerPanel.add(tableScrollPane, BorderLayout.CENTER);
        centerPanel.add(logScrollPane, BorderLayout.SOUTH);

        contentPane.add(titlePanel, BorderLayout.NORTH);
        contentPane.add(centerPanel, BorderLayout.CENTER);
        contentPane.add(buttonPanel, BorderLayout.SOUTH);

        setContentPane(contentPane);
    }

    private void logMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            logArea.append(String.format("[%s] %s%n", timestamp, message));
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void logError(String message) {
        SwingUtilities.invokeLater(() -> {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            logArea.append(String.format("[%s] [错误] %s%n", timestamp, message));
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void switchEnvironment(String environment) {
        // 静默停止所有代理，不显示弹窗
        stopAllProxiesQuietly();
        activeProxies.clear();

        currentEnvironment = environment;
        titleLabel.setText(environment);

        tableModel.setRowCount(0);

        List<ProxyConfig> configs = environments.get(environment);
        for (ProxyConfig config : configs) {
            ManagedProxy proxy = new ManagedProxy(config);
            activeProxies.put(config, proxy);

            tableModel.addRow(new Object[]{
                    Boolean.FALSE,
                    config.getDescription(),
                    config.getRemoteHost(),
                    config.getRemotePort(),
                    config.getLocalPort(),
                    proxy.getState()
            });
        }

        logMessage("已切换到环境: " + environment);
    }

    private void stopAllProxiesQuietly() {
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            ProxyConfig config = getProxyConfigFromRow(i);
            if (config != null) {
                ManagedProxy proxy = activeProxies.get(config);
                if (proxy != null) {
                    proxy.stop();
                }
            }
        }
    }

    private void startSelectedProxies(ActionEvent e) {
        List<Integer> selectedRows = getCheckedRows();
        if (selectedRows.isEmpty()) {
            logError("请先选择要启动的代理");
            return;
        }

        logMessage("开始启动 " + selectedRows.size() + " 个代理...");
        for (int rowIndex : selectedRows) {
            ProxyConfig config = getProxyConfigFromRow(rowIndex);
            if (config != null) {
                startProxy(config, rowIndex);
            }
        }
    }

    private void stopSelectedProxies(ActionEvent e) {
        List<Integer> selectedRows = getCheckedRows();
        if (selectedRows.isEmpty()) {
            logError("请先选择要停止的代理");
            return;
        }

        logMessage("开始停止 " + selectedRows.size() + " 个代理...");
        for (int rowIndex : selectedRows) {
            ProxyConfig config = getProxyConfigFromRow(rowIndex);
            if (config != null) {
                stopProxy(config, rowIndex);
            }
        }
    }

    private List<Integer> getCheckedRows() {
        List<Integer> checkedRows = new ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            Boolean checked = (Boolean) tableModel.getValueAt(i, 0);
            if (checked != null && checked) {
                checkedRows.add(i);
            }
        }
        return checkedRows;
    }

    private void startAllProxies() {
        logMessage("开始启动所有代理...");
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            ProxyConfig config = getProxyConfigFromRow(i);
            if (config != null) {
                startProxy(config, i);
            }
        }
    }

    private void stopAllProxies() {
        logMessage("开始停止所有代理...");
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            ProxyConfig config = getProxyConfigFromRow(i);
            if (config != null) {
                stopProxy(config, i);
            }
        }
    }

    private ProxyConfig getProxyConfigFromRow(int rowIndex) {
        if (rowIndex >= 0 && rowIndex < tableModel.getRowCount()) {
            String desc = (String) tableModel.getValueAt(rowIndex, 1);
            String remoteHost = (String) tableModel.getValueAt(rowIndex, 2);
            Object remotePortObj = tableModel.getValueAt(rowIndex, 3);
            Object localPortObj = tableModel.getValueAt(rowIndex, 4);

            // 处理可能的空值和字符串
            if (desc == null || remoteHost == null || remotePortObj == null || localPortObj == null) {
                return null;
            }

            try {
                int remotePort = remotePortObj instanceof Integer ? (Integer) remotePortObj :
                        Integer.parseInt(remotePortObj.toString());
                int localPort = localPortObj instanceof Integer ? (Integer) localPortObj :
                        Integer.parseInt(localPortObj.toString());

                return new ProxyConfig(localPort, remoteHost, remotePort, desc);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private void startProxy(ProxyConfig config, int rowIndex) {
        if (config == null) {
            logError("第" + (rowIndex + 1) + "行配置信息不完整，无法启动");
            return;
        }

        ManagedProxy proxy = activeProxies.get(config);
        if (proxy == null) {
            proxy = new ManagedProxy(config);
            activeProxies.put(config, proxy);
        }

        if (proxy.getState() != ProxyState.RUNNING) {
            proxy.clear();
            final ManagedProxy finalProxy = proxy;  // 创建final变量供lambda使用
            executor.execute(() -> {
                try {
                    logMessage("正在启动代理: " + config.getDescription() + " (本地端口:" + config.getLocalPort() + ")");
                    finalProxy.start();
                } catch (Exception e) {
                    if (finalProxy.isManualStop()) {
                        logMessage("代理已停止: " + config.getDescription());
                    } else {
                        logError("启动代理失败 [" + config.getDescription() + "]: " + e.getMessage());
                    }
                }
            });
        }
    }


    private void stopProxy(ProxyConfig config, int rowIndex) {
        if (config == null) {
            return;
        }

        ManagedProxy proxy = activeProxies.get(config);
        if (proxy != null) {
            proxy.stop();
            logMessage("代理已停止: " + config.getDescription());
        }
    }

    @Override
    public void dispose() {
        if (statusUpdateTimer != null) {
            statusUpdateTimer.stop();
        }
        stopAllProxiesQuietly();
        executor.shutdown();
        super.dispose();
    }

    public static void main(String[] args) {
        // 设置系统编码为UTF-8
        System.setProperty("file.encoding", "UTF-8");

        SwingUtilities.invokeLater(() -> {
            TCPProxyManagerGUI gui = new TCPProxyManagerGUI();
            gui.setVisible(true);
        });
    }

    // 配置类
    static class ProxyConfig {
        private final int localPort;
        private final String remoteHost;
        private final int remotePort;
        private final String description;

        public ProxyConfig(int localPort, String remoteHost, int remotePort, String description) {
            this.localPort = localPort;
            this.remoteHost = remoteHost;
            this.remotePort = remotePort;
            this.description = description;
        }

        public int getLocalPort() { return localPort; }
        public String getRemoteHost() { return remoteHost; }
        public int getRemotePort() { return remotePort; }
        public String getDescription() { return description; }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            ProxyConfig that = (ProxyConfig) obj;
            return localPort == that.localPort &&
                    remotePort == that.remotePort &&
                    Objects.equals(remoteHost, that.remoteHost) &&
                    Objects.equals(description, that.description);
        }

        @Override
        public int hashCode() {
            return Objects.hash(localPort, remoteHost, remotePort, description);
        }
    }

    // 带有状态管理的代理类
    static class ManagedProxy {
        private final ProxyConfig config;
        private SimpleTCPProxy proxy;

        public ManagedProxy(ProxyConfig config) {
            this.config = config;
            this.proxy = new SimpleTCPProxy(
                    config.getLocalPort(),
                    config.getRemoteHost(),
                    config.getRemotePort()
            );
        }

        public void start() throws IOException {
            this.proxy.start();
        }

        public void stop() {
            this.proxy.stop();
        }

        public void clear() {
            this.proxy.clear();
        }

        public ProxyState getState() {
            return proxy != null ? proxy.getState() : ProxyState.INIT;
        }

        public boolean isManualStop() {
            return proxy != null && proxy.isManualStop();
        }
    }

    // 状态渲染器
    static class StatusRenderer implements TableCellRenderer {
        private final JLabel label = new JLabel();

        public StatusRenderer() {
            label.setOpaque(true);
            label.setHorizontalAlignment(JLabel.CENTER);
            label.setFont(new Font("微软雅黑", Font.BOLD, 16));
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value,
                boolean isSelected, boolean hasFocus,
                int row, int column
        ) {
            if (value instanceof ProxyState) {
                ProxyState state = (ProxyState) value;
                label.setText(state.getText());
                label.setBackground(state.getColor());
                label.setForeground(Color.BLACK);
            }
            return label;
        }
    }
}

// 状态枚举
enum ProxyState {
    INIT("未启动", Color.YELLOW),
    RUNNING("运行中", Color.GREEN),
    STOPPED("已停止", Color.RED);

    private final String text;
    private final Color color;

    ProxyState(String text, Color color) {
        this.text = text;
        this.color = color;
    }

    public String getText() { return text; }
    public Color getColor() { return color; }
}

// SimpleTCPProxy类保持不变
class SimpleTCPProxy {
    private final int localPort;
    private final String remoteHost;
    private final int remotePort;
    private volatile boolean criticalError = false;
    private volatile boolean manualStop = false;
    private Exception criticalException = null;
    private volatile ServerSocket serverSocket;
    private volatile ProxyState state = ProxyState.INIT;

    private static final int CONNECTION_TEST_TIMEOUT = 5000;

    public SimpleTCPProxy(int localPort, String remoteHost, int remotePort) {
        this.localPort = localPort;
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
    }

    public void start() throws IOException {
        state = ProxyState.INIT;
        criticalError = false;
        criticalException = null;
        manualStop = false;

        try {
            serverSocket = new ServerSocket(localPort);

            testRemoteConnection();
            state = ProxyState.RUNNING;

            while (!criticalError) {
                Socket clientSocket;
                try {
                    clientSocket = serverSocket.accept();
                } catch (SocketException e) {
                    if (criticalError) {
                        if (manualStop) {
                            throw new ManualStopException("代理已手动停止");
                        } else {
                            throw new CriticalIOException("Proxy stopped due to critical error: " +
                                    criticalException.getMessage(), criticalException);
                        }
                    }
                    throw e;
                }

                new Thread(() -> {
                    try {
                        handleClientConnection(clientSocket);
                    } catch (IOException e) {
                        // 静默处理连接错误
                    }
                }).start();
            }
        } catch (IOException e) {
            state = ProxyState.STOPPED;
            throw e;
        } finally {
            if (serverSocket != null && !serverSocket.isClosed()) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    // 忽略关闭异常
                }
                serverSocket = null;
            }
        }
    }

    private void testRemoteConnection() throws IOException {
        try (Socket testSocket = new Socket()) {
            testSocket.connect(new InetSocketAddress(remoteHost, remotePort), 5000);
        } catch (SocketTimeoutException e) {
            handleCriticalError(new ConnectException("Connection to " + remoteHost + ":" + remotePort +
                    " timed out after 5000ms"));
            throw new CriticalIOException("Initial connection test failed", e);
        } catch (UnknownHostException | ConnectException e) {
            handleCriticalError(e);
            throw new CriticalIOException("Initial connection test failed", e);
        }
    }

    private Socket connectToRemote() throws IOException {
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(remoteHost, remotePort), CONNECTION_TEST_TIMEOUT);
            return socket;
        } catch (UnknownHostException | ConnectException | SocketTimeoutException e) {
            handleCriticalError(e);
            throw e;
        }
    }

    private void handleCriticalError(Exception e) {
        synchronized (this) {
            if (!criticalError) {
                criticalError = true;
                criticalException = e;
                if (serverSocket != null && !serverSocket.isClosed()) {
                    try {
                        serverSocket.close();
                    } catch (IOException ex) {
                        // 忽略关闭异常
                    }
                }
            }
        }
    }

    public void stop() {
        manualStop = true;
        handleCriticalError(new Exception("代理已手动停止"));
        state = ProxyState.STOPPED;
    }

    public void clear() {
        criticalError = false;
        criticalException = null;
        serverSocket = null;
        manualStop = false;
        state = ProxyState.INIT;
    }

    public ProxyState getState() {
        return state;
    }

    public boolean isManualStop() {
        return manualStop;
    }

    private void handleClientConnection(Socket clientSocket) throws IOException {
        try {
            Socket remoteSocket = connectToRemote();

            Thread clientToRemote = new Thread(() -> transferData(clientSocket, remoteSocket));
            Thread remoteToClient = new Thread(() -> transferData(remoteSocket, clientSocket));

            clientToRemote.start();
            remoteToClient.start();

            clientToRemote.join();
            remoteToClient.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            safeCloseSocket(clientSocket);
        }
    }

    private void transferData(Socket source, Socket destination) {
        try (InputStream sourceInput = source.getInputStream();
             OutputStream destinationOutput = destination.getOutputStream()) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = sourceInput.read(buffer)) != -1) {
                destinationOutput.write(buffer, 0, bytesRead);
                destinationOutput.flush();
            }
            destination.shutdownOutput();
        } catch (IOException e) {
            // 静默处理传输异常
        } finally {
            safeCloseSocket(source);
            safeCloseSocket(destination);
        }
    }

    private static void safeCloseSocket(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
                // 忽略关闭异常
            }
        }
    }

    // 自定义异常类
    private static class ManualStopException extends IOException {
        public ManualStopException(String message) {
            super(message);
        }
    }

    private static class CriticalIOException extends IOException {
        public CriticalIOException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
