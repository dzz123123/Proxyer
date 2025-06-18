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
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class TCPProxyManagerGUI extends JFrame {

    private static final String CONFIG_FILE = "config.json";
    private final Map<String, List<ProxyConfig>> environments = new HashMap<>();
    private final Map<ProxyConfig, ManagedProxy> activeProxies = new HashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private JLabel titleLabel;
    private JTable proxyTable;
    private DefaultTableModel tableModel;
    private JButton startButton, stopButton, startAllButton, stopAllButton;
    private String currentEnvironment;

    public TCPProxyManagerGUI() {
        super("TCP 代理管理器");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 600);

        loadConfig();
        initComponents();
        layoutComponents();
        setLocationRelativeTo(null);
    }

    private void loadConfig() {
        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            // 使用 fastjson2 解析 JSON
            JSONObject config = JSON.parseObject(reader);

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
            JOptionPane.showMessageDialog(this, "配置文件加载失败: " + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
            environments.put("默认环境", Collections.emptyList());
        }
    }

    private void initComponents() {
        // 标题标签
        titleLabel = new JLabel("选择环境", JLabel.CENTER);
        titleLabel.setFont(new Font("微软雅黑", Font.BOLD, 24));

        // 菜单栏
        JMenuBar menuBar = new JMenuBar();
        JMenu environmentMenu = new JMenu("选择环境");

        for (String env : environments.keySet()) {
            JMenuItem envItem = new JMenuItem(env);
            envItem.addActionListener(e -> switchEnvironment(env));
            environmentMenu.add(envItem);
        }
        menuBar.add(environmentMenu);
        setJMenuBar(menuBar);

        // 表格模型
        String[] columnNames = {"描述", "远程地址", "远程端口", "本地端口", "状态"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public Class<?> getColumnClass(int column) {
                return column == 4 ? ProxyState.class : String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        // 表格
        proxyTable = new JTable(tableModel);
        proxyTable.getColumnModel().getColumn(4).setCellRenderer(new StatusRenderer());
        proxyTable.setRowHeight(40);
        proxyTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        proxyTable.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        proxyTable.getTableHeader().setFont(new Font("微软雅黑", Font.BOLD, 14));

        // 按钮
        startButton = new JButton("启动代理");
        startButton.addActionListener(this::startSelectedProxies);

        stopButton = new JButton("停止代理");
        stopButton.addActionListener(this::stopSelectedProxies);

        startAllButton = new JButton("全部启动");
        startAllButton.addActionListener(e -> startAllProxies());

        stopAllButton = new JButton("全部停止");
        stopAllButton.addActionListener(e -> stopAllProxies());

        // 设置按钮样式
        Font buttonFont = new Font("微软雅黑", Font.PLAIN, 16);
        startButton.setFont(buttonFont);
        stopButton.setFont(buttonFont);
        startAllButton.setFont(buttonFont);
        stopAllButton.setFont(buttonFont);
    }

    private void layoutComponents() {
        JPanel contentPane = new JPanel(new BorderLayout(10, 10));
        contentPane.setBorder(new EmptyBorder(10, 10, 10, 10));

        // 标题面板
        JPanel titlePanel = new JPanel(new BorderLayout());
        titlePanel.add(titleLabel, BorderLayout.CENTER);
        titlePanel.setBorder(BorderFactory.createEmptyBorder(10, 0, 15, 0));

        // 表格面板
        JScrollPane tableScrollPane = new JScrollPane(proxyTable);
        tableScrollPane.setBorder(BorderFactory.createTitledBorder("代理状态"));

        // 按钮面板
        JPanel buttonPanel = new JPanel(new GridLayout(1, 4, 10, 0));
        buttonPanel.add(startButton);
        buttonPanel.add(stopButton);
        buttonPanel.add(startAllButton);
        buttonPanel.add(stopAllButton);

        // 添加到主面板
        contentPane.add(titlePanel, BorderLayout.NORTH);
        contentPane.add(tableScrollPane, BorderLayout.CENTER);
        contentPane.add(buttonPanel, BorderLayout.SOUTH);

        setContentPane(contentPane);
    }

    private void switchEnvironment(String environment) {
        // 停止当前环境的所有代理
        stopAllProxies();
        activeProxies.clear();

        // 更新当前环境
        currentEnvironment = environment;
        titleLabel.setText(environment);

        // 更新表格
        tableModel.setRowCount(0);

        List<ProxyConfig> configs = environments.get(environment);
        for (ProxyConfig config : configs) {
            ManagedProxy proxy = new ManagedProxy(config);
            activeProxies.put(config, proxy);

            tableModel.addRow(new Object[]{
                    config.getDescription(),
                    config.getRemoteHost(),
                    config.getRemotePort(),
                    config.getLocalPort(),
                    proxy.getState()
            });
        }
    }

    private void startSelectedProxies(ActionEvent e) {
        int[] selectedRows = proxyTable.getSelectedRows();
        for (int rowIndex : selectedRows) {
            ProxyConfig config = getProxyConfigFromRow(rowIndex);
            if (config != null) {
                startProxy(config, rowIndex);
            }
        }
    }

    private void stopSelectedProxies(ActionEvent e) {
        int[] selectedRows = proxyTable.getSelectedRows();
        for (int rowIndex : selectedRows) {
            ProxyConfig config = getProxyConfigFromRow(rowIndex);
            if (config != null) {
                stopProxy(config, rowIndex);
            }
        }
    }

    private void startAllProxies() {
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            ProxyConfig config = getProxyConfigFromRow(i);
            if (config != null) {
                startProxy(config, i);
            }
        }
    }

    private void stopAllProxies() {
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            ProxyConfig config = getProxyConfigFromRow(i);
            if (config != null) {
                stopProxy(config, i);
            }
        }
    }

    private ProxyConfig getProxyConfigFromRow(int rowIndex) {
        if (rowIndex >= 0 && rowIndex < tableModel.getRowCount()) {
            String desc = (String) tableModel.getValueAt(rowIndex, 0);
            String remoteHost = (String) tableModel.getValueAt(rowIndex, 1);
            int remotePort = (Integer) tableModel.getValueAt(rowIndex, 2);
            int localPort = (Integer) tableModel.getValueAt(rowIndex, 3);

            for (ProxyConfig config : activeProxies.keySet()) {
                if (config.getDescription().equals(desc) &&
                        config.getRemoteHost().equals(remoteHost) &&
                        config.getRemotePort() == remotePort &&
                        config.getLocalPort() == localPort) {
                    return config;
                }
            }
        }
        return null;
    }

    private void startProxy(ProxyConfig config, int rowIndex) {
        ManagedProxy proxy = activeProxies.get(config);
        if (proxy != null && proxy.getState() != ProxyState.RUNNING) {
            proxy.clear();
            executor.execute(() -> {
                try {
                    proxy.start();
                    SwingUtilities.invokeLater(() -> {
                        tableModel.setValueAt(proxy.getState(), rowIndex, 4);
                    });
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> {
                        tableModel.setValueAt(proxy.getState(), rowIndex, 4);
                        JOptionPane.showMessageDialog(
                                TCPProxyManagerGUI.this,
                                "启动代理失败: " + e.getMessage(),
                                "错误",
                                JOptionPane.ERROR_MESSAGE
                        );
                    });
                }
            });
        }
    }

    private void stopProxy(ProxyConfig config, int rowIndex) {
        ManagedProxy proxy = activeProxies.get(config);
        if (proxy != null) {
            proxy.stop();
            SwingUtilities.invokeLater(() -> {
                tableModel.setValueAt(proxy.getState(), rowIndex, 4);
            });
        }
    }

    public static void main(String[] args) {
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

        public int getLocalPort() {
            return localPort;
        }

        public String getRemoteHost() {
            return remoteHost;
        }

        public int getRemotePort() {
            return remotePort;
        }

        public String getDescription() {
            return description;
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

        public String getText() {
            return text;
        }

        public Color getColor() {
            return color;
        }
    }

    // 带有状态管理的代理类
    static class ManagedProxy {
        private final ProxyConfig config;
        private SimpleTCPProxy proxy;
        private ProxyState state;

        public ManagedProxy(ProxyConfig config) {
            this.config = config;
            this.proxy = new SimpleTCPProxy(
                    config.getLocalPort(),
                    config.getRemoteHost(),
                    config.getRemotePort()
            );
            this.state = ProxyState.INIT;
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
            if (proxy == null) return ProxyState.INIT;
            return state;
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

// 增强的代理类（已根据要求修改）
class SimpleTCPProxy {
    private final int localPort;
    private final String remoteHost;
    private final int remotePort;
    private volatile boolean criticalError = false;
    private Exception criticalException = null;
    private volatile ServerSocket serverSocket;

    private volatile ProxyState state = ProxyState.INIT;

    // 连接测试超时时间（毫秒）
    private static final int CONNECTION_TEST_TIMEOUT = 5000;


    public SimpleTCPProxy(int localPort, String remoteHost, int remotePort) {
        this.localPort = localPort;
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
    }

    public void start() throws IOException {
        state = ProxyState.INIT;  // 初始为黄色状态
        criticalError = false;
        criticalException = null;

        try {
            serverSocket = new ServerSocket(localPort);
            System.out.println("Proxy listening on port " + localPort + " ...");

            testRemoteConnection();  // 测试远程连接

            state = ProxyState.RUNNING;  // 测试通过后设置为绿色状态

            while (!criticalError) {
                Socket clientSocket;
                try {
                    clientSocket = serverSocket.accept();
                    System.out.println("Client connected from: " + clientSocket.getRemoteSocketAddress());
                } catch (SocketException e) {
                    if (criticalError) {
                        throw new CriticalIOException("Proxy stopped due to critical error: " +
                                criticalException.getMessage(), criticalException);
                    }
                    throw e;
                }

                new Thread(() -> {
                    try {
                        handleClientConnection(clientSocket);
                    } catch (IOException e) {
                        System.err.println("Client connection error: " + e.getMessage());
                    }
                }).start();
            }
        } catch (IOException e) {
            state = ProxyState.STOPPED;  // 出错时设置为红色状态
            throw e;
        } finally {
            if (serverSocket != null && !serverSocket.isClosed()) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    System.err.println("Error closing server socket: " + e.getMessage());
                }
                serverSocket = null;
            }
        }
    }

    private void testRemoteConnection() throws IOException {
        try (Socket testSocket = new Socket()) {
            testSocket.connect(new InetSocketAddress(remoteHost, remotePort), 5000);
            System.out.println("Remote server test passed: " + remoteHost + ":" + remotePort);
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
        handleCriticalError(new Exception("代理已手动停止"));
        state = ProxyState.STOPPED;
    }

    public void clear() {
        // 恢复所有临时字段到默认状态
        criticalError = false;
        criticalException = null;
        serverSocket = null;
        state = ProxyState.INIT;
    }

    public ProxyState getState() {
        return state;
    }

    private void handleClientConnection(Socket clientSocket) throws IOException {
        try {
            Socket remoteSocket = connectToRemote();
            System.out.println("Connected to remote server: " + remoteHost + ":" + remotePort);

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
            System.out.println("Data transfer exception: " + e.getMessage());
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
                System.err.println("Error closing socket: " + e.getMessage());
            }
        }
    }

    private static class CriticalIOException extends IOException {
        public CriticalIOException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

// 代理状态枚举
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

    public String getText() {
        return text;
    }

    public Color getColor() {
        return color;
    }
}