package com.sparrowwallet.sparrow;

import com.google.common.eventbus.Subscribe;
import com.google.common.net.HostAndPort;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.OsType;
import com.sparrowwallet.drongo.SecureString;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.bip47.PaymentCode;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.crypto.EncryptionType;
import com.sparrowwallet.drongo.crypto.InvalidPasswordException;
import com.sparrowwallet.drongo.crypto.Key;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.wallet.*;
import com.sparrowwallet.sparrow.control.DialogImage;
import com.sparrowwallet.sparrow.control.WalletPasswordDialog;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import com.sparrowwallet.sparrow.net.Auth47;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.protocol.BlockHeader;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.SigHash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionSignature;
import com.sparrowwallet.drongo.psbt.PSBT;
import com.sparrowwallet.drongo.psbt.PSBTInput;
import com.sparrowwallet.drongo.uri.BitcoinURI;
import com.sparrowwallet.sparrow.control.TrayManager;
import com.sparrowwallet.sparrow.event.*;
import com.sparrowwallet.sparrow.io.*;
import com.sparrowwallet.sparrow.net.*;
import io.reactivex.rxjavafx.schedulers.JavaFxScheduler;
import io.reactivex.subjects.PublishSubject;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.concurrent.ScheduledService;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.concurrent.Worker;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.Dialog;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.text.Font;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.controlsfx.glyphfont.Glyph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.desktop.OpenFilesHandler;
import java.awt.desktop.OpenURIHandler;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.net.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.sparrowwallet.sparrow.AppController.CONNECTION_FAILED_PREFIX;
import static com.sparrowwallet.sparrow.control.DownloadVerifierDialog.*;

public class AppServices {
    private static final Logger log = LoggerFactory.getLogger(AppServices.class);

    private static final int SERVER_PING_PERIOD_SECS = 60;
    private static final int PUBLIC_SERVER_RETRY_PERIOD_SECS = 3;
    private static final int PRIVATE_SERVER_RETRY_PERIOD_SECS = 15;
    public static final int ENUMERATE_HW_PERIOD_SECS = 30;
    private static final int RATES_PERIOD_SECS = 5 * 60;
    private static final int CONNECTION_DELAY_SECS = 2;
    private static final int RATES_DELAY_SECS_DEFAULT = 2;
    private static final int RATES_DELAY_SECS_WINDOWS = 5;
    private static final ExchangeSource DEFAULT_EXCHANGE_SOURCE = ExchangeSource.COINGECKO;
    private static final Currency DEFAULT_FIAT_CURRENCY = Currency.getInstance("USD");
    private static final String TOR_DEFAULT_PROXY_CIRCUIT_ID = "default";

    public static final List<Integer> TARGET_BLOCKS_RANGE = List.of(1, 2, 3, 4, 5, 10, 25, 50);
    private static final List<Double> LONG_FEE_RATES_RANGE = List.of(1d, 2d, 4d, 8d, 16d, 32d, 64d, 128d, 256d, 512d, 1024d, 2048d, 4096d, 8192d);
    public static final double FALLBACK_FEE_RATE = 20000d / 1000;
    public static final double TESTNET_FALLBACK_FEE_RATE = 1000d / 1000;

    private static AppServices INSTANCE;

    private final InteractionServices interactionServices;

    private static HttpClientService httpClientService;

    private final Application application;

    private final Map<Window, List<WalletTabData>> walletWindows = new LinkedHashMap<>();

    private TrayManager trayManager;

    private final PublishSubject<NewBlockEvent> newBlockSubject = PublishSubject.create();

    private static Image windowIcon;

    private static final BooleanProperty onlineProperty = new SimpleBooleanProperty(false);

    private ExchangeSource.RatesService ratesService;

    private ElectrumServer.ConnectionService connectionService;

    private ElectrumServer.FeeRatesService feeRatesService;

    private Hwi.ScheduledEnumerateService deviceEnumerateService;

    private TorService torService;

    private ScheduledService<Void> preventSleepService;

    private static volatile ChainTip announcedTip;

    //Written from the Cormorant connection task and read on the FX thread when a PSBT is built,
    //so this is not confined to a single thread
    private static volatile Integer nodeHardforkHeight;
    private static final AtomicReference<String> lastReportedActivationHeightMismatch = new AtomicReference<>();

    private static final Map<Integer, BlockSummary> blockSummaries = new ConcurrentHashMap<>();

    private static Map<Integer, Double> targetBlockFeeRates;

    private static Double nextBlockMedianFeeRate;

    private static final TreeMap<Date, Set<MempoolRateSize>> mempoolHistogram = new TreeMap<>();

    private static Double minimumRelayFeeRate;

    private static Double serverMinimumRelayFeeRate;

    private static CurrencyRate fiatCurrencyExchangeRate;

    private static List<Device> devices;

    private static final List<File> argFiles = new ArrayList<>();

    private static final List<URI> argUris = new ArrayList<>();

    private static final Map<Sha256Hash, BitcoinURI> payjoinURIs = new HashMap<>();

    private final ChangeListener<Boolean> onlineServicesListener = new ChangeListener<>() {
        @Override
        public void changed(ObservableValue<? extends Boolean> observable, Boolean oldValue, Boolean online) {
            if(online) {
                if(Config.get().requiresInternalTor() && !isTorRunning()) {
                    if(torService.getState() == Worker.State.SCHEDULED) {
                        torService.cancel();
                        torService.reset();
                    }

                    if(torService.getState() != Worker.State.RUNNING) {
                        torService.start();
                    }
                } else {
                    restartServices();
                }
            } else {
                connectionService.cancel();
                ratesService.cancel();

                if(httpClientService != null) {
                    HttpClientService.ShutdownService shutdownService = new HttpClientService.ShutdownService(httpClientService);
                    shutdownService.start();
                }
            }
        }
    };

    private static final OpenURIHandler openURIHandler = event -> {
        openURI(event.getURI());
    };

    private static final OpenFilesHandler openFilesHandler = event -> {
        openFiles(event.getFiles(), null);
    };

    private AppServices(Application application, InteractionServices interactionServices) {
        this.application = application;
        this.interactionServices = interactionServices;

        newBlockSubject.buffer(4, TimeUnit.SECONDS)
                .filter(newBlockEvents -> !newBlockEvents.isEmpty())
                .observeOn(JavaFxScheduler.platform())
                .subscribe(this::fetchBlockSummaries, exception -> log.error("Error fetching block summaries", exception));

        EventManager.get().register(this);
    }

    public void start() {
        Config config = Config.get();
        connectionService = createConnectionService();
        registerHeaderSyncService();
        feeRatesService = createFeeRatesService();
        ratesService = createRatesService(config.getExchangeSource(), config.getFiatCurrency());
        torService = createTorService();
        preventSleepService = createPreventSleepService();

        onlineProperty.addListener(onlineServicesListener);
        minimumRelayFeeRate = getConfiguredMinimumRelayFeeRate(config);

        if(config.getMode() == Mode.ONLINE) {
            if(config.requiresInternalTor()) {
                torService.start();
            } else {
                restartServices();
            }
        } else {
            EventManager.get().post(new DisconnectionEvent());
        }

        addURIHandlers();
    }

    private void restartServices() {
        Config config = Config.get();
        if(config.hasServer()) {
            restartService(connectionService);
        }

        if(config.isFetchRates()) {
            restartService(ratesService);
        }

        if(config.isPreventSleep()) {
            restartService(preventSleepService);
        }
    }

    private void restartService(ScheduledService<?> service) {
        if(service.isRunning()) {
            service.cancel();
        }

        if(service.getState() == Worker.State.CANCELLED || service.getState() == Worker.State.FAILED) {
            service.reset();
        }

        if(!service.isRunning()) {
            service.start();
        }
    }

    public void stop() {
        if(connectionService != null) {
            connectionService.cancel();
        }

        if(ratesService != null) {
            ratesService.cancel();
        }

        if(httpClientService != null) {
            HttpClientService.ShutdownService shutdownService = new HttpClientService.ShutdownService(httpClientService);
            shutdownService.start();
        }

        if(Tor.getDefault() != null) {
            Tor.getDefault().close();
        }
    }

    private ElectrumServer.ConnectionService createConnectionService() {
        ElectrumServer.ConnectionService connectionService = new ElectrumServer.ConnectionService();
        //Delay startup on first connection to Bitcoin Core to allow any unencrypted wallets to open first
        connectionService.setDelay(Config.get().getServerType() == ServerType.BITCOIN_CORE ? Duration.seconds(CONNECTION_DELAY_SECS) : Duration.ZERO);
        connectionService.setPeriod(Duration.seconds(SERVER_PING_PERIOD_SECS));
        connectionService.setRestartOnFailure(true);
        EventManager.get().register(connectionService);

        connectionService.setOnRunning(workerStateEvent -> {
            connectionService.setDelay(Duration.ZERO);
            if(!ElectrumServer.isConnected()) {
                EventManager.get().post(new ConnectionStartEvent(Config.get().getServerDisplayName()));
            }
        });
        connectionService.setOnSucceeded(successEvent -> {
            connectionService.setPeriod(Duration.seconds(SERVER_PING_PERIOD_SECS));
            connectionService.setRestartOnFailure(true);

            onlineProperty.removeListener(onlineServicesListener);
            onlineProperty.setValue(true);
            onlineProperty.addListener(onlineServicesListener);

            FeeRatesUpdatedEvent event = connectionService.getValue();
            if(event != null) {
                EventManager.get().post(event);
            }
        });
        connectionService.setOnFailed(failEvent -> {
            //Close connection here to create a new transport next time we try
            connectionService.closeConnection();

            if(failEvent.getSource().getException() instanceof ServerConfigException) {
                connectionService.setRestartOnFailure(false);
            }

            if(failEvent.getSource().getException() instanceof TlsServerException tlsServerException && failEvent.getSource().getException().getCause() != null) {
                connectionService.setRestartOnFailure(false);
                if(tlsServerException.getCause().getMessage().contains("PKIX path building failed")) {
                    File crtFile = Config.get().getElectrumServerCert();
                    if(crtFile != null && Config.get().getServerType() == ServerType.ELECTRUM_SERVER) {
                        AppServices.showErrorDialog("SSL Handshake Failed", "The configured server certificate at " + crtFile.getAbsolutePath() + " did not match the certificate provided by the server at " + tlsServerException.getServer().getHost() + "." +
                                "\n\nThis may be simply due to a certificate renewal, or it may indicate a man-in-the-middle attack." +
                                "\n\nChange the configured server certificate if you would like to proceed.");
                    } else {
                        crtFile = Storage.getCertificateFile(tlsServerException.getServer().getHost());
                        if(crtFile == null) {
                            crtFile = Storage.getCaCertificateFile(tlsServerException.getServer().getHost());
                        }
                        if(crtFile != null) {
                            Optional<ButtonType> optButton = AppServices.showErrorDialog("SSL Handshake Failed", "The certificate provided by the server at " + tlsServerException.getServer().getHost() + " appears to have changed." +
                                    "\n\nThis may be simply due to a certificate renewal, or it may indicate a man-in-the-middle attack." +
                                    "\n\nDo you still want to proceed?", ButtonType.NO, ButtonType.YES);
                            if(optButton.isPresent() && optButton.get() == ButtonType.YES) {
                                if(crtFile.delete()) {
                                    Platform.runLater(() -> restartService(connectionService));
                                    return;
                                } else {
                                    AppServices.showErrorDialog("Could not delete certificate", "The certificate file at " + crtFile.getAbsolutePath() + " could not be deleted.\n\nPlease delete this file manually.");
                                }
                            }
                        }
                    }
                } else if(tlsServerException.getCause().getCause() instanceof UnknownCertificateExpiredException expiredException) {
                    Optional<ButtonType> optButton = AppServices.showErrorDialog("SSL Handshake Failed", "The certificate provided by the server at " + tlsServerException.getServer().getHost() + " has expired. "
                            + tlsServerException.getMessage() + "." +
                            "\n\nDo you still want to proceed?", ButtonType.NO, ButtonType.YES);
                    if(optButton.isPresent() && optButton.get() == ButtonType.YES) {
                        Storage.saveCertificate(tlsServerException.getServer().getHost(), expiredException.getCertificate());
                        Platform.runLater(() -> restartService(connectionService));
                        return;
                    }
                }
            }

            if(failEvent.getSource().getException() instanceof ProxyServerException && Config.get().isUseProxy() && Config.get().isAutoSwitchProxy() && Config.get().requiresTor()) {
                Config.get().setUseProxy(false);
                Platform.runLater(() -> restartService(torService));
                return;
            }

            onlineProperty.removeListener(onlineServicesListener);
            onlineProperty.setValue(false);
            onlineProperty.addListener(onlineServicesListener);

            log.debug("Connection failed", failEvent.getSource().getException());
            if(Config.get().getServerType() == ServerType.PUBLIC_ELECTRUM_SERVER) {
                boolean changed = changePublicServer();
                connectionService.setPeriod(changed ? Duration.seconds(PUBLIC_SERVER_RETRY_PERIOD_SECS) : Duration.seconds(PRIVATE_SERVER_RETRY_PERIOD_SECS));
                EventManager.get().post(new ConnectionFailedEvent(failEvent.getSource().getException()));
                if(!changed) {
                    Platform.runLater(() -> EventManager.get().post(new StatusEvent(CONNECTION_FAILED_PREFIX + "No public servers available that can serve the open wallets, retrying later...")));
                }
            } else {
                connectionService.setPeriod(Duration.seconds(PRIVATE_SERVER_RETRY_PERIOD_SECS));
                EventManager.get().post(new ConnectionFailedEvent(failEvent.getSource().getException()));
            }
        });

        return connectionService;
    }

    private ElectrumServer.FeeRatesService createFeeRatesService() {
        ElectrumServer.FeeRatesService feeRatesService = new ElectrumServer.FeeRatesService();
        feeRatesService.setOnSucceeded(workerStateEvent -> {
            EventManager.get().post(feeRatesService.getValue());
        });

        return feeRatesService;
    }

    /**
     * The header sync service is driven entirely by the events it subscribes to - started by an announced tip, cancelled on disconnection - so nothing
     * here holds it or restarts it. Registering it with the event bus is what keeps it reachable for the life of the session.
     */
    private void registerHeaderSyncService() {
        ElectrumServer.HeaderSyncService headerSyncService = new ElectrumServer.HeaderSyncService();
        headerSyncService.setPeriod(Duration.seconds(ElectrumServer.HeaderSyncService.RETRY_PERIOD_SECS));
        headerSyncService.setRestartOnFailure(true);
        EventManager.get().register(headerSyncService);

        //The service is started by the tip it is told about, so a successful run has nothing left to do until the next one
        headerSyncService.setOnSucceeded(successEvent -> {
            headerSyncService.cancel();
        });
        headerSyncService.setOnFailed(failEvent -> {
            log.warn("Failed to sync block headers, retrying in " + ElectrumServer.HeaderSyncService.RETRY_PERIOD_SECS + "s", failEvent.getSource().getException());
        });
    }

    private ExchangeSource.RatesService createRatesService(ExchangeSource exchangeSource, Currency currency) {
        ExchangeSource.RatesService ratesService = new ExchangeSource.RatesService(
                exchangeSource == null ? DEFAULT_EXCHANGE_SOURCE : exchangeSource,
                currency == null ? DEFAULT_FIAT_CURRENCY : currency);
        //Delay startup on first run, Windows requires a longer delay
        ratesService.setDelay(OsType.getCurrent() == OsType.WINDOWS ? Duration.seconds(RATES_DELAY_SECS_WINDOWS) : Duration.seconds(RATES_DELAY_SECS_DEFAULT));
        ratesService.setPeriod(Duration.seconds(RATES_PERIOD_SECS));
        ratesService.setRestartOnFailure(true);

        ratesService.setOnSucceeded(successEvent -> {
            EventManager.get().post(ratesService.getValue());
        });

        return ratesService;
    }

    private Hwi.ScheduledEnumerateService createDeviceEnumerateService() {
        Hwi.ScheduledEnumerateService enumerateService = new Hwi.ScheduledEnumerateService(null);
        enumerateService.setPeriod(Duration.seconds(Config.get().getEnumerateHwPeriod()));
        enumerateService.setOnSucceeded(workerStateEvent -> {
            List<Device> devices = enumerateService.getValue();

            //Null devices are returned if the app is currently prompting for a pin (the enumerate would clear the pin screen) or another device operation is in progress
            if(devices != null) {
                //If another instance of HWI is currently accessing the usb interface, HWI returns empty device models. Ignore this run if that happens
                List<Device> validDevices = devices.stream().filter(device -> device.getModel() != null).collect(Collectors.toList());
                if(validDevices.size() == devices.size()) {
                    Platform.runLater(() -> EventManager.get().post(new UsbDeviceEvent(devices)));
                }
            }
        });

        return enumerateService;
    }

    private TorService createTorService() {
        TorService torService = new TorService();
        torService.setPeriod(Duration.hours(1000));
        torService.setRestartOnFailure(true);

        torService.setOnRunning(workerStateEvent -> {
            EventManager.get().post(new TorBootStatusEvent());
        });
        torService.setOnSucceeded(workerStateEvent -> {
            Tor.setDefault(torService.getValue());
            torService.cancel();
            restartServices();
            EventManager.get().post(new TorReadyStatusEvent());
        });
        torService.setOnFailed(workerStateEvent -> {
            EventManager.get().post(new TorFailedStatusEvent(workerStateEvent.getSource().getException()));
        });

        return torService;
    }

    private ScheduledService<Void> createPreventSleepService() {
        ScheduledService<Void> preventSleepService = new ScheduledService<Void>() {
            @Override
            protected Task<Void> createTask() {
                return new Task<>() {
                    protected Void call() {
                        try {
                            Robot robot = new Robot();
                            robot.keyRelease(KeyEvent.VK_F16);
                        } catch(Exception e) {
                            log.debug("Error preventing sleep", e);
                        }

                        return null;
                    }
                };
            }
        };

        preventSleepService.setPeriod(Duration.minutes(1));
        return preventSleepService;
    }

    public void setPreventSleep(boolean preventSleep) {
        if(preventSleepService != null) {
            if(preventSleep) {
                restartService(preventSleepService);
            } else {
                preventSleepService.cancel();
            }
        }
    }

    private void fetchFeeRates() {
        if(feeRatesService != null && !feeRatesService.isRunning() && Config.get().getMode() != Mode.OFFLINE) {
            feeRatesService = createFeeRatesService();
            feeRatesService.start();
        }
    }

    private void fetchBlockSummaries(List<NewBlockEvent> newBlockEvents) {
        if(isConnected()) {
            ElectrumServer.BlockSummaryService blockSummaryService = new ElectrumServer.BlockSummaryService(newBlockEvents);
            blockSummaryService.setOnSucceeded(_ -> {
                EventManager.get().post(blockSummaryService.getValue());
            });
            blockSummaryService.setOnFailed(failedState -> {
                log.error("Error fetching block summaries", failedState.getSource().getException());
            });
            blockSummaryService.start();
        }
    }

    public static boolean isTorRunning() {
        return Tor.getDefault() != null;
    }

    public static boolean isUsingProxy() {
        return isTorRunning() || Config.get().isUseProxy();
    }

    public static Proxy getProxy() {
        return getProxy(TOR_DEFAULT_PROXY_CIRCUIT_ID);
    }

    public static Proxy getProxy(String proxyCircuitId) {
        Config config = Config.get();
        Proxy proxy = null;
        if(config.isUseProxy() && config.getProxyServer() != null) {
            HostAndPort proxyHostAndPort = HostAndPort.fromString(config.getProxyServer());
            InetSocketAddress proxyAddress = new InetSocketAddress(proxyHostAndPort.getHost(), proxyHostAndPort.getPortOrDefault(ProxyTcpOverTlsTransport.DEFAULT_PROXY_PORT));
            proxy = new Proxy(Proxy.Type.SOCKS, proxyAddress);
        } else if(AppServices.isTorRunning()) {
            proxy = Tor.getDefault().getProxy();
        }

        //Setting new proxy authentication credentials will force a new Tor circuit to be created
        if(proxy != null) {
            Authenticator.setDefault(new Authenticator() {
                public PasswordAuthentication getPasswordAuthentication() {
                    return (new PasswordAuthentication("user", proxyCircuitId.toCharArray()));
                }
            });
        }

        return proxy;
    }

    public static void initialize(Application application) {
        INSTANCE = new AppServices(application, new DefaultInteractionServices());
    }

    public static void initialize(Application application, InteractionServices interactionServices) {
        INSTANCE = new AppServices(application, interactionServices);
    }

    public static AppServices get() {
        return INSTANCE;
    }

    public static InteractionServices getInteractionServices() {
        return get().interactionServices;
    }

    public static HttpClientService getHttpClientService() {
        HostAndPort torProxy = getTorProxy();
        if(httpClientService == null) {
            httpClientService = new HttpClientService(torProxy);
        } else {
            if(!Objects.equals(httpClientService.getTorProxy(), torProxy)) {
                httpClientService.setTorProxy(getTorProxy());
            }
        }

        return httpClientService;
    }

    public static HostAndPort getTorProxy() {
        return AppServices.isTorRunning() ?
                Tor.getDefault().getProxyHostAndPort() :
                (Config.get().getProxyServer() == null || Config.get().getProxyServer().isEmpty() || !Config.get().isUseProxy() ? null : HostAndPort.fromString(Config.get().getProxyServer()));
    }

    public static AppController newAppWindow(Stage stage) {
        try {
            FXMLLoader appLoader = new FXMLLoader(AppServices.class.getResource("app.fxml"));
            Parent root = appLoader.load();
            AppController appController = appLoader.getController();

            Scene scene = new Scene(root);
            scene.getStylesheets().add(AppServices.class.getResource("app.css").toExternalForm());

            stage.setTitle(SparrowWallet.APP_NAME);
            stage.setMinWidth(650);
            stage.setMinHeight(708);
            stage.setScene(scene);
            stage.getIcons().add(getWindowIcon());

            appController.initializeView();
            stage.show();
            return appController;
        } catch(IOException e) {
            log.error("Could not load app FXML", e);
            throw new IllegalStateException(e);
        }
    }

    public static void runAfterDelay(long delay, Runnable runnable) {
        if(delay <= 0) {
            if(Platform.isFxApplicationThread()) {
                runnable.run();
            } else {
                Platform.runLater(runnable);
            }
        } else {
            ScheduledService<Void> delayService = new ScheduledService<>() {
                @Override
                protected Task<Void> createTask() {
                    return new Task<>() {
                        @Override
                        protected Void call() {
                            return null;
                        }
                    };
                }
            };
            delayService.setOnSucceeded(_ -> {
                delayService.cancel();
                runnable.run();
            });
            delayService.setDelay(Duration.millis(delay));
            delayService.start();
        }
    }

    private static Image getWindowIcon() {
        if(windowIcon == null) {
            windowIcon = new Image(SparrowWallet.class.getResourceAsStream("/image/sparrow-icon.png"));
        }

        return windowIcon;
    }

    public static boolean isReducedWindowHeight() {
        Window activeWindow = getActiveWindow();
        return (activeWindow != null && activeWindow.getHeight() < getReducedWindowHeight());
    }

    public static boolean isReducedWindowHeight(Node node) {
        return (node.getScene() != null && node.getScene().getWindow().getHeight() < getReducedWindowHeight());
    }

    private static double getReducedWindowHeight() {
        return OsType.getCurrent() != OsType.MACOS ? 802d : 768d;  //Check for menu bar of ~34px
    }

    public Application getApplication() {
        return application;
    }

    public void minimizeStage(Stage stage) {
        if(trayManager == null) {
            trayManager = new TrayManager();
        }

        trayManager.addStage(stage);
        stage.hide();
    }

    public static void onEscapePressed(Scene scene, Runnable runnable) {
        scene.setOnKeyPressed(event -> {
            if(event.getCode() == KeyCode.ESCAPE) {
                runnable.run();
            }
        });
    }

    public Map<Wallet, Storage> getOpenWallets() {
        Map<Wallet, Storage> openWallets = new LinkedHashMap<>();
        for(List<WalletTabData> walletTabDataList : walletWindows.values()) {
            for(WalletTabData walletTabData : walletTabDataList) {
                openWallets.put(walletTabData.getWallet(), walletTabData.getStorage());
            }
        }

        return openWallets;
    }

    public Wallet getWallet(String walletId) {
        return getOpenWallets().entrySet().stream().filter(entry -> entry.getValue().getWalletId(entry.getKey()).equals(walletId)).map(Map.Entry::getKey).findFirst().orElse(null);
    }

    public WalletTransaction getCreatedTransaction(Set<BlockTransactionHashIndex> utxos) {
        for(List<WalletTabData> walletTabDataList : walletWindows.values()) {
            for(WalletTabData walletTabData : walletTabDataList) {
                if(walletTabData.getWalletForm().getCreatedWalletTransaction() != null && utxos.equals(walletTabData.getWalletForm().getCreatedWalletTransaction().getSelectedUtxos().keySet())) {
                    return walletTabData.getWalletForm().getCreatedWalletTransaction();
                }
            }
        }

        return null;
    }

    public Window getWindowForWallet(String walletId) {
        Optional<Window> optWindow = walletWindows.entrySet().stream().filter(entry -> entry.getValue().stream().anyMatch(walletTabData -> walletTabData.getWalletForm().getWalletId().equals(walletId))).map(Map.Entry::getKey).findFirst();
        return optWindow.orElse(null);
    }

    public Window getWindowForPSBT(PSBT psbt) {
        Optional<Window> optWindow = walletWindows.entrySet().stream().filter(entry -> entry.getValue().stream().anyMatch(walletTabData -> walletTabData.getWallet().canSign(psbt))).map(Map.Entry::getKey).findFirst();
        return optWindow.orElse(null);
    }

    public double getWalletWindowMaxX() {
        return walletWindows.keySet().stream().mapToDouble(Window::getX).max().orElse(0d);
    }

    public static boolean isConnecting() {
        return get().connectionService != null && get().connectionService.isConnecting();
    }

    public static boolean isConnected() {
        return onlineProperty.get() && get().connectionService != null && get().connectionService.isConnected();
    }

    public static BooleanProperty onlineProperty() {
        return onlineProperty;
    }

    public static Integer getCurrentBlockHeight() {
        ChainTip tip = announcedTip;
        return tip == null ? null : tip.height();
    }

    public static BlockHeader getLatestBlockHeader() {
        ChainTip tip = announcedTip;
        return tip == null ? null : tip.header();
    }

    /**
     * The chain tip as the connected server last announced it, whose height and header are written together. A reader needing both must take them from
     * one of these, since the two accessors above read the tip separately and can straddle a new block, pairing a new height with the previous header.
     */
    public static ChainTip getAnnouncedTip() {
        return announcedTip;
    }

    public static void setAnnouncedTip(ChainTip announcedTip) {
        AppServices.announcedTip = announcedTip;
    }

    /**
     * Whether transactions this wallet creates should opt in to the unified signature hash.
     *
     * The fork carries both the BLAKE2b proof of work and the opt-in signature hash, and both take
     * effect at the same block, so a chain tip carrying a v2 header is one where opting in is valid.
     * Reading it from the chain rather than a configured height keeps this working on any network,
     * including a regtest chain that activates at an arbitrary height, and leaves nothing to hold in
     * sync with the node.
     *
     * This is one block later than the node's own answer, which asks whether the deployment is active
     * for the block being built rather than for the tip. A transaction created in that one block is
     * signed the legacy way: still valid and still relayed, it simply carries no replay protection.
     * Erring in that direction is the safe one, since a signature that opts in before the rules apply
     * would not verify at all.
     */
    public static boolean isUnifiedSigHashActive() {
        //Read once: ChainTip carries the height and the header together so the decision cannot take the
        //height of one block with the header of another, which two separate reads would allow.
        ChainTip tip = announcedTip;
        return isUnifiedSigHashActive(Network.get(), tip == null ? null : tip.height(), tip == null ? null : tip.header());
    }

    /**
     * The activation height per network, or null where the fork is not scheduled.
     *
     * A height is the part of this decision a server cannot influence, which is why it comes first. On
     * mainnet the fork has no schedule, so nothing a server says can make a wallet opt in; without that
     * floor a hostile or intercepted server could serve a forged v2 header today and every transaction
     * the wallet produced would be rejected by the network as an undefined hash type.
     *
     * Regtest chooses its own height through -testactivationheight, so there is nothing to hardcode and
     * the chain is the only available answer there.
     */
    static Integer getUnifiedSigHashActivationHeight(Network network) {
        //Held on the network alongside the checkpoints, so the header chain and this decision cannot hold different
        //ideas of when the fork activates. The schedule has moved more than once before a final release, and each
        //move replaced the chain that followed the old one, so it is not trusted on its own: isUnifiedSigHashActive
        //cross-checks it against the connected node and declines rather than follow either side of a disagreement.
        return network.getBlake2bHeight();
    }

    /**
     * Records the hardfork height the connected node reports, or null where it reports none.
     *
     * Kept separate from the compiled-in schedule rather than replacing it. A height that ships with the
     * wallet is the one thing in this decision a server cannot move, which is what stops a hostile server
     * driving a mainnet wallet into producing signatures the network rejects. What the node says is used
     * to notice that the shipped value has gone stale, not to override it.
     */
    public static void setNodeHardforkHeight(Integer height) {
        nodeHardforkHeight = height;
    }

    static Integer getNodeHardforkHeight() {
        return nodeHardforkHeight;
    }

    /**
     * Forgets what the last node said. The value describes the connection it came from, so leaving it in
     * place after a disconnect would let a height from one node keep deciding for another, including for
     * an Electrum server that reports nothing at all.
     */
    public static void clearNodeHardforkHeight() {
        nodeHardforkHeight = null;
        lastReportedActivationHeightMismatch.set(null);
        //The indicator describes a disagreement with a particular node, so it goes when that node does
        EventManager.get().post(UnifiedSigHashScheduleEvent.resolved());
    }

    static boolean isUnifiedSigHashActive(Network network, Integer blockHeight, BlockHeader blockHeader) {
        return chainDecision(network, blockHeight, blockHeader).isOptedIn();
    }

    /**
     * As isUnifiedSigHashActive, keeping hold of the reason instead of reducing it to a boolean.
     *
     * The boolean form delegates here rather than the two carrying a copy of the same checks each, since
     * a reason that drifts from the decision it explains is worse than no reason at all.
     */
    static UnifiedSigHashDecision chainDecision(Network network, Integer blockHeight, BlockHeader blockHeader) {
        //Nothing has been heard from a chain, so nothing about one can be reported. Offline, and before the first
        //tip of a session arrives
        if(blockHeader == null) {
            return UnifiedSigHashDecision.CHAIN_UNSEEN;
        }

        //A v2 header means the proof of work change is live, and both rule sets activate at the one block
        if(!blockHeader.isHeaderV2()) {
            return UnifiedSigHashDecision.CHAIN_NOT_ACTIVATED;
        }

        if(network == Network.REGTEST) {
            return UnifiedSigHashDecision.OPTED_IN;
        }

        Integer activationHeight = getUnifiedSigHashActivationHeight(network);
        //nodeHardforkHeight is read once here and passed by value. The callee null checks it and then
        //dereferences it, which is only safe because it cannot be cleared between those two steps.
        //Inlining this call so the field is read twice would reintroduce that race.
        return heightDecision(activationHeight, nodeHardforkHeight, blockHeight);
    }

    /**
     * The height comparison, with the shipped schedule and the node's schedule reconciled.
     *
     * Where the node reports a height and it differs from the one compiled in, one of the two is wrong
     * and there is no way to tell which, so this refuses to opt in. A signature that does not opt in is
     * always valid, while one made under the wrong schedule either fails to verify or forgoes the
     * protection it claims, so declining is the only safe answer to a disagreement.
     */
    static boolean isUnifiedSigHashActive(Integer walletActivationHeight, Integer nodeActivationHeight, Integer blockHeight) {
        return heightDecision(walletActivationHeight, nodeActivationHeight, blockHeight).isOptedIn();
    }

    /**
     * As above, keeping the reason. The warnings stay here rather than moving to the caller, because they
     * are reported once per distinct disagreement and a caller asking only to display a reason must not
     * re-announce one that has already been reported.
     */
    static UnifiedSigHashDecision heightDecision(Integer walletActivationHeight, Integer nodeActivationHeight, Integer blockHeight) {
        if(blockHeight == null) {
            return UnifiedSigHashDecision.CHAIN_HEIGHT_UNKNOWN;
        }

        //A node that has scheduled the fork while this build has not is the case that matters on a network
        //where the flagday is set after this build ships. Declining is right, since a height a node offers
        //is not one this wallet can adopt without letting a compromised node choose the schedule, but it
        //has to be said out loud: signing the legacy way past the flagday forgoes replay protection, and
        //without this the operator gets no signal at all that an update is due.
        if(walletActivationHeight == null) {
            if(nodeActivationHeight != null) {
                warnActivationHeightUnknown(nodeActivationHeight);
            }
            return UnifiedSigHashDecision.BUILD_HAS_NO_SCHEDULE;
        }

        if(nodeActivationHeight != null && !nodeActivationHeight.equals(walletActivationHeight)) {
            warnActivationHeightMismatch(walletActivationHeight, nodeActivationHeight);
            return UnifiedSigHashDecision.SCHEDULE_MISMATCH;
        }

        if(blockHeight < walletActivationHeight) {
            return UnifiedSigHashDecision.BEFORE_ACTIVATION_HEIGHT;
        }

        //A node that reports no height cannot corroborate the shipped one. Opting in regardless is right, since
        //declining because a server cannot answer would forgo the protection on every Electrum connection, but the
        //cross check that would catch a stale build has not run and saying so is the difference this records.
        if(nodeActivationHeight == null) {
            noteScheduleUncorroborated(walletActivationHeight);
            return UnifiedSigHashDecision.OPTED_IN_UNCORROBORATED;
        }

        return UnifiedSigHashDecision.OPTED_IN;
    }

    /**
     * Logs a schedule disagreement once per distinct pair of heights rather than once per transaction.
     * A stale build disagrees on every send, and the operator only needs telling once per connection.
     * clearNodeHardforkHeight resets this so a reconnect reports again.
     *
     * getAndSet rather than a read followed by a write: two sends racing here would otherwise both see
     * the old value and both log.
     */
    private static void warnActivationHeightMismatch(int walletActivationHeight, int nodeActivationHeight) {
        if(isNewActivationHeightReport(walletActivationHeight + "/" + nodeActivationHeight)) {
            log.warn("Not opting in to the unified signature hash: this build expects activation at height "
                    + walletActivationHeight + " but the connected node reports " + nodeActivationHeight);
            EventManager.get().post(UnifiedSigHashScheduleEvent.scheduleMismatch(walletActivationHeight, nodeActivationHeight));
        }
    }

    /**
     * Notes an opt-in taken without a node to corroborate it, once per connection rather than once per send.
     *
     * Logged rather than posted as an event: the status bar indicator is for a disagreement needing attention,
     * and an Electrum server reporting no height is the normal case rather than a fault.
     */
    private static void noteScheduleUncorroborated(int walletActivationHeight) {
        if(isNewActivationHeightReport("uncorroborated/" + walletActivationHeight)) {
            log.info("Opting in to the unified signature hash on the height compiled into this build, "
                    + walletActivationHeight + ": the connected node reports no activation height, so the shipped "
                    + "schedule could not be cross checked against it.");
        }
    }

    /**
     * As above, for the case where the node has a schedule and this build has none for the network.
     */
    private static void warnActivationHeightUnknown(int nodeActivationHeight) {
        if(isNewActivationHeightReport("unknown/" + nodeActivationHeight)) {
            log.warn("Not opting in to the unified signature hash: the connected node schedules activation at height "
                    + nodeActivationHeight + " but this build has no height for " + Network.get()
                    + ". Transactions will be signed without replay protection until it is updated.");
            EventManager.get().post(UnifiedSigHashScheduleEvent.scheduleUnknown(nodeActivationHeight, Network.get().toDisplayString()));
        }
    }

    /**
     * Whether this disagreement has not already been reported, recording it either way.
     *
     * getAndSet rather than a read followed by a write: two sends racing here would otherwise both see
     * the old value and both report.
     */
    static boolean isNewActivationHeightReport(String report) {
        return !report.equals(lastReportedActivationHeightMismatch.getAndSet(report));
    }

    /**
     * The disagreement last reported, or null if none has been since the connection was established.
     */
    static String getLastActivationHeightReport() {
        return lastReportedActivationHeightMismatch.get();
    }

    /**
     * Whether every key that will sign is one this wallet holds.
     *
     * An external signer that has not implemented the opt-in either refuses the hash type outright or
     * signs the legacy message while the PSBT declares the new one, and the resulting signature does not
     * verify. Opting in is optional by design, so a wallet backed by a device simply keeps signing the
     * way it does today until the device catches up.
     */
    static boolean canSignUnified(Wallet wallet) {
        return keystoreDecision(wallet).isOptedIn();
    }

    /**
     * As canSignUnified, keeping the reason. A software seed signs from a key the wallet holds, so its support is
     * not in question; a device is taken at its owner's word, since nothing it sends says which firmware it runs.
     * SW_WATCH is neither: it produces no signature at all, so the wallet is in no position to opt in whatever it
     * has been marked as.
     *
     * A PSBT carries one hash type for every signer, so opting in needs enough of them marked to meet the threshold.
     */
    static UnifiedSigHashDecision keystoreDecision(Wallet wallet) {
        if(wallet == null || wallet.getKeystores().isEmpty()) {
            return UnifiedSigHashDecision.NO_SIGNING_KEYS;
        }

        long capable = wallet.getKeystores().stream().filter(AppServices::canKeystoreSignUnified).count();

        long unmarked = wallet.getKeystores().size() - capable;

        //A threshold that cannot be read is taken as one, the weakest quorum a wallet could have. Guaranteed means no
        //quorum of unmarked signers exists, so assuming a larger threshold than the wallet really has would claim that
        //guarantee where a smaller quorum could still be formed. Erring low means only an entirely marked wallet
        //qualifies, which is the answer that cannot be wrong.
        Integer threshold = readThreshold(wallet);
        int required = threshold == null ? 1 : threshold;

        //One signer that can opt in is enough to opt in at all, because the hash type is opted into per signature and
        //a transaction carrying one opted-in signature cannot be replayed whatever the rest carry. Signers that
        //cannot are handed the base type rather than locked out.
        if(capable > 0) {
            //Guaranteed only where the signers that cannot opt in could not meet the threshold between them. Otherwise
            //they could form a quorum on their own, and that transaction would carry no opted-in signature at all.
            return unmarked < required
                    ? UnifiedSigHashDecision.OPTED_IN : UnifiedSigHashDecision.OPTED_IN_IF_MARKED_SIGNS;
        }

        //A keystore the user can speak for has a remedy they can act on; one that neither signs here nor has a
        //signer to declare for does not, and reporting the markable reason for it points at a control the
        //keystore tab does not show. Every source today is one or the other, so the second branch is unreachable
        //and testEverySourceEitherSignsHereOrCanBeMarked pins that. It is kept rather than removed because a
        //source added later would otherwise fall into the markable reason and name a control it has no access to.
        return wallet.getKeystores().stream()
                .anyMatch(keystore -> !canKeystoreSignUnified(keystore) && !canBeMarked(keystore))
                ? UnifiedSigHashDecision.NO_DEVICE_TO_MARK : UnifiedSigHashDecision.EXTERNAL_SIGNER;
    }

    /**
     * The signers that can produce the opt-in, as a readable list, or null where naming them adds nothing.
     *
     * The caveat on a partial quorum says the transaction has to be signed by the marked signers without saying
     * which, leaving the reader to go and look. Null where every signer qualifies, since there is no subset to name.
     */
    public static String markedSignerNames(Wallet wallet) {
        if(wallet == null || wallet.getKeystores().isEmpty()) {
            return null;
        }

        List<String> names = wallet.getKeystores().stream()
                .filter(AppServices::canKeystoreSignUnified)
                .map(Keystore::getLabel)
                .filter(label -> label != null && !label.isBlank())
                .toList();

        return names.isEmpty() || names.size() == wallet.getKeystores().size() ? null : String.join(", ", names);
    }

    /**
     * How many of the signatures already on this PSBT opt in, against how many there are.
     *
     * Two different properties hang off this, with different thresholds, and reporting one number for both hides the
     * difference. Replay protection belongs to the whole transaction and takes one opted-in signature anywhere in it.
     * Committing to every spent amount, which is what closes CVE-2020-14199, belongs to each signature and takes that
     * signature opting in. A mixed witness has the first in full and the second only for the signers that opted in.
     *
     * Read off the signatures rather than the declared hash type, because a transaction assembled from per-device
     * PSBTs carries signatures the declaration does not describe.
     */
    public static int[] signatureOptInCounts(PSBT psbt) {
        if(psbt == null) {
            return new int[] {0, 0};
        }

        int optedIn = 0;
        int total = 0;
        for(PSBTInput psbtInput : psbt.getPsbtInputs()) {
            for(TransactionSignature signature : psbtInput.getSignatures()) {
                total++;
                if((signature.sighashFlags & SigHash.UNIFIED_FLAG) != 0) {
                    optedIn++;
                }
            }
        }

        return new int[] {optedIn, total};
    }

    /**
     * How many signatures in this transaction can be lifted out of it and spent on the pre-fork chain.
     *
     * One opted-in signature makes the whole transaction invalid under the pre-fork rules, but the legacy signatures
     * inside it stay individually valid there. A legacy ALL or SINGLE signature commits to every input, so it is
     * useless in any other transaction. A legacy ANYONECANPAY one commits only to its own input and to the outputs,
     * so it can be copied into a transaction that drops the opted-in inputs and spent against a node that never
     * adopted the fork. The transaction is protected; that input is not, and saying only "protected" would hide it.
     */
    public static int liftableSignatureCount(PSBT psbt) {
        if(psbt == null) {
            return 0;
        }

        int liftable = 0;
        for(PSBTInput psbtInput : psbt.getPsbtInputs()) {
            for(TransactionSignature signature : psbtInput.getSignatures()) {
                if((signature.sighashFlags & SigHash.UNIFIED_FLAG) == 0
                        && (signature.sighashFlags & SigHash.ANYONECANPAY.value) != 0) {
                    liftable++;
                }
            }
        }

        return liftable;
    }

    /**
     * The PSBT to hand this device: the one given, or a copy asking only for what the device can produce.
     *
     * The hash type is opted into per signature, so a transaction carrying one opted-in signature cannot be replayed
     * whatever the rest carry. A PSBT declares one hash type an input, though, so a device that has not been marked
     * cannot be handed the opted-in one. Giving it a copy asking for the base type lets it sign alongside the others
     * rather than being locked out, and the signatures merge: combine keeps every partial signature, an opted-in type
     * scores as the type it is built on so the severity guard does not fire, and each signature is verified against
     * the type it carries.
     */
    public static PSBT psbtForDevice(Wallet wallet, PSBT psbt, String fingerprint) {
        if(!deviceCannotSignDeclaredSigHash(wallet, psbt, fingerprint)) {
            return psbt;
        }

        PSBT devicePsbt = psbt.copy();
        for(PSBTInput psbtInput : devicePsbt.getPsbtInputs()) {
            SigHash sigHash = psbtInput.getSigHash();
            if(sigHash != null && sigHash.isUnified()) {
                psbtInput.setSigHash(sigHash.withoutUnified());
            }
        }

        return devicePsbt;
    }

    /**
     * Whether handing this PSBT to the device behind the given fingerprint would ask it for a hash type it has not
     * been marked as producing.
     *
     * Reachable only since the wallet began opting in on a quorum: before that it never declared the opt-in while an
     * unmarked keystore was present, so no device could be handed a transaction it could not sign. Now a 2-of-3 with
     * two marked signers declares it, and reaching for the third gets whatever that firmware says on refusal, which
     * knows nothing about replay protection. Answering here lets the caller say something useful instead.
     *
     * Null fingerprint, absent PSBT or a keystore this device does not match all read as no objection: this exists to
     * explain a refusal that is going to happen, not to add one.
     */
    public static boolean deviceCannotSignDeclaredSigHash(Wallet wallet, PSBT psbt, String fingerprint) {
        if(wallet == null || psbt == null || fingerprint == null) {
            return false;
        }

        boolean declaresUnified = psbt.getPsbtInputs().stream()
                .anyMatch(psbtInput -> psbtInput.getSigHash() != null && psbtInput.getSigHash().isUnified());
        if(!declaresUnified) {
            return false;
        }

        return wallet.getKeystores().stream()
                .filter(keystore -> keystore.getKeyDerivation() != null
                        && fingerprint.equalsIgnoreCase(keystore.getKeyDerivation().getMasterFingerprint()))
                .anyMatch(keystore -> keystore.getSource().isHardware() && !keystore.isUnifiedSigHashSupported());
    }

    /**
     * How many signatures this wallet's policy needs, or every keystore where that cannot be determined.
     *
     * getNumSignaturesRequired throws on a policy it cannot parse and the policy itself may be absent on a wallet
     * still being built, and this is called on the send path where throwing would take the screen with it. Falling
     * back to the whole keystore set keeps the stricter answer: a threshold that cannot be read is never grounds
     * for opting in on fewer signers than the wallet might need.
     */
    public static int requiredSignatures(Wallet wallet) {
        Integer threshold = readThreshold(wallet);
        return threshold == null ? wallet.getKeystores().size() : threshold;
    }

    /**
     * The threshold this wallet's policy declares, or null where it cannot be read.
     *
     * getNumSignaturesRequired throws on a policy it cannot parse and the policy itself may be absent on a wallet
     * still being built, and this is reached from the send path where throwing would take the screen with it.
     */
    public static Integer readThreshold(Wallet wallet) {
        try {
            Policy policy = wallet.getDefaultPolicy();
            return policy == null ? null : policy.getNumSignaturesRequired();
        } catch(RuntimeException e) {
            log.debug("Could not read the signature threshold", e);
            return null;
        }
    }

    /**
     * Whether this keystore signs from a key the wallet holds, rather than handing a PSBT to something else.
     *
     * A payment code keystore is one of these: checkKeystore refuses one without a BIP47 extended private key,
     * and getKey returns a private key for it, so it signs in process exactly as a software seed does. It is
     * grouped with SW_SEED on the sign button upstream for the same reason.
     */
    static boolean signsInProcess(Keystore keystore) {
        KeystoreSource source = keystore.getSource();
        return source == KeystoreSource.SW_SEED || source == KeystoreSource.SW_PAYMENT_CODE;
    }

    /**
     * Whether the user can state what the signer behind this keystore does.
     *
     * The wallet is not deciding what it signs here, it is deciding which hash type to declare in a PSBT it
     * hands to something else. A watch only keystore is in exactly the position an airgapped one is: the
     * wallet cannot verify the claim either way, and the owner is the only one who knows. Refusing the claim
     * for one and taking it for the other made the remedy "rebuild this wallet to change one boolean".
     */
    public static boolean canBeMarked(Keystore keystore) {
        KeystoreSource source = keystore.getSource();
        return source.isHardware() || source == KeystoreSource.SW_WATCH;
    }

    private static boolean canKeystoreSignUnified(Keystore keystore) {
        return signsInProcess(keystore) || (canBeMarked(keystore) && keystore.isUnifiedSigHashSupported());
    }

    /**
     * The decision for a wallet about to send, with the reason where it declined.
     *
     * The chain is asked before the keystores, matching the order createPSBT applied when this was a pair
     * of booleans joined by &&: a chain that has not activated is the reason to report even where the
     * wallet also holds a device, since the device is no obstacle until the rules are live.
     */
    public static UnifiedSigHashDecision getUnifiedSigHashDecision(Wallet wallet) {
        ChainTip tip = announcedTip;
        return combinedDecision(chainDecision(Network.get(), tip == null ? null : tip.height(), tip == null ? null : tip.header()), wallet);
    }

    /**
     * Takes the chain's answer first and only asks the keystores if it opted in.
     *
     * Separate from the call above so it can be reached without the chain state, which lives in static
     * fields that only the block events write. Left untested, this ordering is where a chain reason and a
     * keystore reason could quietly swap places without any test noticing.
     */
    static UnifiedSigHashDecision combinedDecision(UnifiedSigHashDecision chainDecision, Wallet wallet) {
        if(!chainDecision.isOptedIn()) {
            return chainDecision;
        }

        UnifiedSigHashDecision keystores = keystoreDecision(wallet);
        if(!keystores.isOptedIn()) {
            return keystores;
        }

        //Both opted in, and either may qualify it. The chain's caveat is about whether the schedule this signed
        //under is the right one, which decides whether the protection holds at all. The keystores' is about who
        //can sign what was built. The first is worth more, so it wins where both apply, and returning the chain's
        //answer is also what stops an uncorroborated opt-in being reported as a confirmed one.
        return chainDecision == UnifiedSigHashDecision.OPTED_IN ? keystores : chainDecision;
    }

    /**
     * Creates the PSBT for a transaction being sent, opting in to the unified signature hash where the
     * chain has it and this wallet holds the keys. Every wallet send path goes through here so the
     * decision is made in one place; the private key sweep builds its own PSBT from a key that is not in
     * a wallet, and keeps signing the way it does today.
     *
     * The wallet does not offer a control for forcing this on. Every input to the decision that it can
     * establish, it establishes more reliably than a person: whether the chain has reached the height,
     * whether the connected node agrees with the schedule this build ships, and whether every key that
     * will sign belongs to a signer that implements the opt-in. Opting in before the rules apply is
     * refused by the network, checked against a node both ways. On a chain that never schedules the
     * fork the mempool refuses it outright, "Signature opts in to the hardfork, which is not active
     * here". On one that schedules it but has not reached the height the mempool takes it, since relay
     * is keyed to the fork being scheduled rather than active, and no block can carry it until
     * activation. The same spend without the opt-in is accepted and mined in both cases.
     *
     * That argument does not extend to forcing it off, and there is currently no way to do so. The
     * sighash control in the transaction view offers only opted-in types once the PSBT has opted in, so
     * changing what the signature covers keeps the opt-in rather than dropping it. Turning it off is a
     * real want: the hash type travels in the PSBT, so a co-signer, a payjoin receiver or any other tool
     * that does not know the byte will refuse a transaction this wallet considered safe, and a legacy
     * signature is always valid.
     *
     * It is deliberately not a bare toggle. Off means the signature verifies under both rule sets, so the
     * transaction can be replayed on the chain that did not fork, which is the protection being given up
     * and has to be said rather than implied.
     *
     * Nor is the wallet always better informed. Where this build ships no height for the network the
     * decision declines however far past activation the chain is, which is a stale table rather than a
     * judgement, and warnActivationHeightUnknown says so to the user.
     */
    public static PSBT createPSBT(WalletTransaction walletTransaction) {
        return createPSBT(walletTransaction, getUnifiedSigHashDecision(walletTransaction.getWallet()).isOptedIn());
    }

    static PSBT createPSBT(WalletTransaction walletTransaction, boolean active) {
        return applyUnifiedSigHash(walletTransaction.createPSBT(), active);
    }

    static PSBT applyUnifiedSigHash(PSBT psbt, boolean active) {
        if(active) {
            for(PSBTInput psbtInput : psbt.getPsbtInputs()) {
                SigHash sigHash = psbtInput.getSigHash();
                psbtInput.setSigHash(sigHash == null ? SigHash.UNIFIED_ALL : sigHash.withUnified());
            }
        }

        return psbt;
    }

    public static Map<Integer, BlockSummary> getBlockSummaries() {
        return blockSummaries;
    }

    public static Double getDefaultFeeRate() {
        int defaultTarget = TARGET_BLOCKS_RANGE.get((TARGET_BLOCKS_RANGE.size() / 2) - 1);
        return getTargetBlockFeeRates() == null ? getFallbackFeeRate() : getTargetBlockFeeRates().get(defaultTarget);
    }

    public static Double getMinimumFeeRate() {
        Optional<Double> optMinFeeRate = getTargetBlockFeeRates().values().stream().min(Double::compareTo);
        Double minRate = optMinFeeRate.orElse(getFallbackFeeRate());
        return Math.max(minRate, Transaction.DUST_RELAY_TX_FEE);
    }

    public static List<Double> getLongFeeRatesRange() {
        if(minimumRelayFeeRate == null || minimumRelayFeeRate >= Transaction.DEFAULT_MIN_RELAY_FEE) {
            return LONG_FEE_RATES_RANGE;
        } else {
            List<Double> longFeeRatesRange = new ArrayList<>();
            longFeeRatesRange.add(minimumRelayFeeRate);
            longFeeRatesRange.addAll(LONG_FEE_RATES_RANGE);
            return longFeeRatesRange;
        }
    }

    public static List<Double> getFeeRatesRange() {
        if(minimumRelayFeeRate == null || minimumRelayFeeRate >= Transaction.DEFAULT_MIN_RELAY_FEE) {
            return LONG_FEE_RATES_RANGE.subList(0, LONG_FEE_RATES_RANGE.size() - 3);
        } else {
            List<Double> longFeeRatesRange = getLongFeeRatesRange();
            return longFeeRatesRange.subList(0, longFeeRatesRange.size() - 4);
        }
    }

    public static Double getNextBlockMedianFeeRate() {
        return nextBlockMedianFeeRate == null ? getDefaultFeeRate() : nextBlockMedianFeeRate;
    }

    public static double getFallbackFeeRate() {
        return Network.get() == Network.MAINNET ? FALLBACK_FEE_RATE : TESTNET_FALLBACK_FEE_RATE;
    }

    public static Map<Integer, Double> getTargetBlockFeeRates() {
        return targetBlockFeeRates;
    }

    public static TreeMap<Date, Set<MempoolRateSize>> getMempoolHistogram() {
        return mempoolHistogram;
    }

    private void addMempoolRateSizes(Set<MempoolRateSize> rateSizes) {
        if(rateSizes.isEmpty()) {
            return;
        }

        LocalDateTime dateMinute = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        if(mempoolHistogram.isEmpty()) {
            mempoolHistogram.put(Date.from(dateMinute.minusMinutes(1).atZone(ZoneId.systemDefault()).toInstant()), rateSizes);
        }

        mempoolHistogram.put(Date.from(dateMinute.atZone(ZoneId.systemDefault()).toInstant()), rateSizes);

        Date yesterday = Date.from(LocalDateTime.now().minusDays(1).atZone(ZoneId.systemDefault()).toInstant());
        mempoolHistogram.keySet().removeIf(date -> date.before(yesterday));

        ZonedDateTime twoHoursAgo = LocalDateTime.now().minusHours(2).atZone(ZoneId.systemDefault());
        mempoolHistogram.keySet().removeIf(date -> {
            ZonedDateTime dateTime = date.toInstant().atZone(ZoneId.systemDefault());
            return dateTime.isBefore(twoHoursAgo) && (dateTime.getMinute() % 10 != 0);
        });
    }

    public static Double getConfiguredMinimumRelayFeeRate(Config config) {
        return config.getMinRelayFeeRate() >= 0d && config.getMinRelayFeeRate() < Transaction.DEFAULT_MIN_RELAY_FEE ? config.getMinRelayFeeRate() : null;
    }

    public static Double getMinimumRelayFeeRate() {
        return minimumRelayFeeRate == null ? Transaction.DEFAULT_MIN_RELAY_FEE : minimumRelayFeeRate;
    }

    public static Double getServerMinimumRelayFeeRate() {
        return serverMinimumRelayFeeRate;
    }

    public static CurrencyRate getFiatCurrencyExchangeRate() {
        return fiatCurrencyExchangeRate;
    }

    public static List<Device> getDevices() {
        return devices == null ? new ArrayList<>() : devices;
    }

    public static BitcoinURI getPayjoinURI(PSBT psbt) {
        return psbt == null ? null : payjoinURIs.get(psbt.getTransaction().calculateTxId(false));
    }

    public static void addPayjoinURI(PSBT psbt, BitcoinURI bitcoinURI) {
        if(bitcoinURI.getPayjoinUrl() == null || bitcoinURI.getAddress() == null) {
            throw new IllegalArgumentException("Not a valid payjoin URI");
        }
        payjoinURIs.put(psbt.getTransaction().calculateTxId(false), bitcoinURI);
    }

    public static void clearPayjoinURI(PSBT psbt) {
        if(psbt != null) {
            payjoinURIs.remove(psbt.getTransaction().calculateTxId(false));
        }
    }

    public static void clearTransactionHistoryCache(Wallet wallet) {
        ElectrumServer.clearRetrievedScriptHashes(wallet);

        if(wallet.getPolicyType() == PolicyType.SINGLE_SP && wallet.isValid()) {
            ElectrumServer.releaseSilentPaymentSubscription(wallet.getSilentPaymentScanAddress());
        }

        for(Wallet childWallet : wallet.getChildWallets()) {
            if(childWallet.isNested()) {
                AppServices.clearTransactionHistoryCache(childWallet);
            }
        }
    }

    public static boolean isWalletFile(File file) {
        return Storage.isWalletFile(file);
    }

    public boolean changePublicServer() {
        List<PolicyType> policyTypes = getOpenWallets().keySet().stream().map(Wallet::getPolicyType).filter(Objects::nonNull).collect(Collectors.toList());
        return changePublicServer(policyTypes.isEmpty() ? List.of(PolicyType.SINGLE_HD) : policyTypes);
    }

    private boolean changePublicServer(List<PolicyType> policyTypes) {
        Config config = Config.get();
        List<Server> otherServers = PublicElectrumServer.getServers().stream().filter(pes -> pes.supportsAllPolicyTypes(policyTypes))
                .map(PublicElectrumServer::getServer).filter(server -> !server.equals(config.getPublicElectrumServer())).collect(Collectors.toList());
        if(!otherServers.isEmpty()) {
            config.setPublicElectrumServer(otherServers.get(ThreadLocalRandom.current().nextInt(otherServers.size())));
            return true;
        }
        return false;
    }

    public static Optional<ButtonType> showWarningDialog(String title, String content, ButtonType... buttons) {
        return showAlertDialog(title, content, Alert.AlertType.WARNING, buttons);
    }

    public static Optional<ButtonType> showErrorDialog(String title, String content, ButtonType... buttons) {
        return showAlertDialog(title, content == null ? "See log file (Help menu)" : content, Alert.AlertType.ERROR, buttons);
    }

    public static Optional<ButtonType> showSuccessDialog(String title, String content, ButtonType... buttons) {
        Glyph successGlyph = new Glyph(FontAwesome5.FONT_NAME, FontAwesome5.Glyph.CHECK_CIRCLE);
        successGlyph.getStyleClass().add("success");
        successGlyph.setFontSize(50);

        return showAlertDialog(title, content, Alert.AlertType.INFORMATION, successGlyph, buttons);
    }

    public static Optional<ButtonType> showAlertDialog(String title, String content, Alert.AlertType alertType, ButtonType... buttons) {
        return showAlertDialog(title, content, alertType, null, buttons);
    }

    public static Optional<ButtonType> showAlertDialog(String title, String content, Alert.AlertType alertType, Node graphic, ButtonType... buttons) {
        return getInteractionServices().showAlert(title, content, alertType, graphic, buttons);
    }

    public static void setStageIcon(Window window) {
        Stage stage = (Stage)window;
        stage.getIcons().add(getWindowIcon());

        if(stage.getScene() != null) {
            if(Config.get().getTheme() == Theme.DARK) {
                stage.getScene().getStylesheets().add(AppServices.class.getResource("darktheme.css").toExternalForm());
            }
            if(Config.get().isChunkAddresses()) {
                stage.getScene().getRoot().getStyleClass().add("chunk-addresses");
            }
        }
    }

    public static Window getActiveWindow() {
        return Stage.getWindows().stream().filter(Window::isFocused).findFirst().orElse(get().walletWindows.keySet().iterator().hasNext() ? get().walletWindows.keySet().iterator().next() : (Stage.getWindows().iterator().hasNext() ? Stage.getWindows().iterator().next() : null));
    }

    public static void moveToActiveWindowScreen(Dialog<?> dialog) {
        Window activeWindow = getActiveWindow();
        if(activeWindow != null) {
            moveToWindowScreen(activeWindow, dialog);
        }
    }

    public static void moveToActiveWindowScreen(Window newWindow, double newWindowWidth, double newWindowHeight) {
        Window activeWindow = getActiveWindow();
        if(activeWindow != null) {
            moveToWindowScreen(activeWindow, newWindow, newWindowWidth, newWindowHeight);
        }
    }

    public static void moveToWindowScreen(Window currentWindow, Dialog<?> dialog) {
        Window newWindow = dialog.getDialogPane().getScene().getWindow();
        DialogPane dialogPane = dialog.getDialogPane();
        double dialogWidth = dialogPane.getPrefWidth() > 0.0 ? dialogPane.getPrefWidth() : (dialogPane.getWidth() > 0.0 ? dialogPane.getWidth() : 360);
        double dialogHeight = dialogPane.getPrefHeight() > 0.0 ? dialogPane.getPrefHeight() : (dialogPane.getHeight() > 0.0 ? dialogPane.getHeight() : 200);
        moveToWindowScreen(currentWindow, newWindow, dialogWidth, dialogHeight);
    }

    public static void moveToWindowScreen(Window currentWindow, Window newWindow, double newWindowWidth, double newWindowHeight) {
        Screen currentScreen = Screen.getScreens().stream().filter(screen -> screen.getVisualBounds().contains(currentWindow.getX(), currentWindow.getY())).findFirst().orElse(null);
        if(currentScreen != null
                && ((!Double.isNaN(newWindow.getX()) && !Double.isNaN(newWindow.getY())) || !Screen.getPrimary().getVisualBounds().contains(currentWindow.getX(), currentWindow.getY()))
                && !currentScreen.getVisualBounds().contains(newWindow.getX(), newWindow.getY())) {
            double x = currentWindow.getX() + (currentWindow.getWidth() / 2) - (newWindowWidth / 2);
            double y = currentWindow.getY() + (currentWindow.getHeight() / 2.2) - (newWindowHeight / 2);
            newWindow.setX(x);
            newWindow.setY(y);
        }
    }

    public static void openBlockExplorer(String txid) {
        if(Config.get().isBlockExplorerDisabled()) {
            return;
        }

        Server blockExplorer = Config.get().getBlockExplorer() == null ? BlockExplorer.getDefault().getServer() : Config.get().getBlockExplorer();
        String url = blockExplorer.getUrl();
        if(url.contains("{0}")) {
            url = url.replace("{0}", txid);
        } else {
            if(Network.get() != Network.MAINNET) {
                url += "/" + Network.get().getName();
            }
            url += "/tx/" + txid;
        }
        AppServices.get().getApplication().getHostServices().showDocument(url);
    }

    static void parseFileUriArguments(List<String> fileUriArguments) {
        for(String fileUri : fileUriArguments) {
            try {
                File file = new File(fileUri.replace("~", System.getProperty("user.home")));
                if(file.exists()) {
                    argFiles.add(file);
                    continue;
                }
                URI uri = new URI(fileUri);
                argUris.add(uri);
            } catch(URISyntaxException e) {
                log.warn("Could not parse " + fileUri + " as a valid file or URI");
            } catch(Exception e) {
                //ignore
            }
        }
    }

    public static void openFileUriArgumentsAfterWalletLoading(Window window) {
        if(!argFiles.isEmpty() || !argUris.isEmpty()) {
            Service<Void> service = new Service<>() {
                @Override
                protected Task<Void> createTask() {
                    return new Task<>() {
                        @Override
                        protected Void call() {
                            Platform.runLater(() -> openFileUriArguments(window));
                            return null;
                        }
                    };
                }
            };
            service.setExecutor(Storage.LoadWalletService.getSingleThreadedExecutor());
            service.start();
        }
    }

    public static void openFileUriArguments(Window window) {
        openFiles(argFiles, window);
        argFiles.clear();

        for(URI argUri : argUris) {
            openURI(argUri);
        }
        argUris.clear();
    }

    private static void openFiles(List<File> files, Window window) {
        final List<File> openFiles = new ArrayList<>(files);
        Platform.runLater(() -> {
            Window openWindow = window;
            if(openWindow == null) {
                openWindow = getActiveWindow();
            }

            if(openWindow instanceof Stage) {
                ((Stage)openWindow).setIconified(false);
                ((Stage)openWindow).setAlwaysOnTop(true);
                ((Stage)openWindow).setAlwaysOnTop(false);
            }

            for(File file : openFiles) {
                if(isWalletFile(file)) {
                    EventManager.get().post(new RequestWalletOpenEvent(openWindow, file));
                } else if(isVerifyDownloadFile(file)) {
                    EventManager.get().post(new RequestVerifyDownloadEvent(openWindow, file));
                } else {
                    EventManager.get().post(new RequestTransactionOpenEvent(openWindow, file));
                }
            }
        });
    }

    private static void openURI(URI uri) {
        Platform.runLater(() -> {
            if("bitcoin".equals(uri.getScheme())) {
                openBitcoinUri(uri);
            } else if(("auth47").equals(uri.getScheme())) {
                openAuth47Uri(uri);
            } else if(("lightning").equals(uri.getScheme())) {
                openLnurlAuthUri(uri);
            }
        });
    }

    public static void addURIHandlers() {
        try {
            if(Desktop.isDesktopSupported()) {
                if(Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_FILE)) {
                    Desktop.getDesktop().setOpenFileHandler(openFilesHandler);
                }
                if(Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_URI)) {
                    Desktop.getDesktop().setOpenURIHandler(openURIHandler);
                }
            }
        } catch(Exception e) {
            log.error("Could not add URI handler", e);
        }
    }

    private static void openBitcoinUri(URI uri) {
        try {
            BitcoinURI bitcoinURI = new BitcoinURI(uri.toString());
            List<PolicyType> policyTypes = Arrays.asList(PolicyType.values());
            List<ScriptType> scriptTypes = Arrays.asList(ScriptType.ADDRESSABLE_TYPES);
            Wallet wallet = selectWallet(policyTypes, scriptTypes, true, false, "pay from", false);

            if(wallet != null) {
                final Wallet sendingWallet = wallet;
                EventManager.get().post(new SendActionEvent(sendingWallet, new ArrayList<>(sendingWallet.getSpendableUtxos().keySet()), true));
                Platform.runLater(() -> EventManager.get().post(new SendPaymentsEvent(sendingWallet, List.of(bitcoinURI.toPayment()), bitcoinURI)));
            }
        } catch(Exception e) {
            showErrorDialog("Not a valid bitcoin URI", e.getMessage());
        }
    }

    private static void openAuth47Uri(URI uri) {
        try {
            Auth47 auth47 = new Auth47(uri);
            List<ScriptType> scriptTypes = PaymentCode.SEGWIT_SCRIPT_TYPES;
            Wallet wallet = selectWallet(List.of(PolicyType.SINGLE_HD), scriptTypes, false, true, auth47.getLoginMessage(), true);

            if(wallet != null) {
                try {
                    auth47.sendResponse(wallet);
                    EventManager.get().post(new StatusEvent("Successfully authenticated to " + auth47.getCallback().getHost()));
                } catch(Exception e) {
                    log.error("Error authenticating auth47 URI", e);
                    showErrorDialog("Error authenticating", "Failed to authenticate.\n\n" + e.getMessage());
                }
            }
        } catch(Exception e) {
            log.error("Not a valid auth47 URI", e);
            showErrorDialog("Not a valid auth47 URI", e.getMessage());
        }
    }

    private static void openLnurlAuthUri(URI uri) {
        try {
            LnurlAuth lnurlAuth = new LnurlAuth(uri);
            List<ScriptType> scriptTypes = ScriptType.getAddressableScriptTypes(PolicyType.SINGLE_HD);
            Wallet wallet = selectWallet(List.of(PolicyType.SINGLE_HD), scriptTypes, true, true, lnurlAuth.getLoginMessage(), true);

            if(wallet != null) {
                if(wallet.isEncrypted()) {
                    Storage storage = AppServices.get().getOpenWallets().get(wallet);
                    Wallet copy = wallet.copy();
                    WalletPasswordDialog dlg = new WalletPasswordDialog(copy.getMasterName(), WalletPasswordDialog.PasswordRequirement.LOAD);
                    dlg.initOwner(getActiveWindow());
                    Optional<SecureString> password = dlg.showAndWait();
                    if(password.isPresent()) {
                        Storage.KeyDerivationService keyDerivationService = new Storage.KeyDerivationService(storage, password.get(), true);
                        keyDerivationService.setOnSucceeded(workerStateEvent -> {
                            EventManager.get().post(new StorageEvent(storage.getWalletId(wallet), TimedEvent.Action.END, "Done"));
                            ECKey encryptionFullKey = keyDerivationService.getValue();
                            Key key = new Key(encryptionFullKey.getPrivKeyBytes(), storage.getKeyDeriver().getSalt(), EncryptionType.Deriver.ARGON2);
                            copy.decrypt(key);
                            try {
                                lnurlAuth.sendResponse(copy);
                                EventManager.get().post(new StatusEvent("Successfully authenticated to " + lnurlAuth.getDomain()));
                            } catch(Exception e) {
                                showErrorDialog("Error authenticating", "Failed to authenticate.\n\n" + e.getMessage());
                            } finally {
                                key.clear();
                                password.get().clear();
                            }
                        });
                        keyDerivationService.setOnFailed(workerStateEvent -> {
                            EventManager.get().post(new StorageEvent(storage.getWalletId(wallet), TimedEvent.Action.END, "Failed"));
                            if(keyDerivationService.getException() instanceof InvalidPasswordException) {
                                Optional<ButtonType> optResponse = showErrorDialog("Invalid Password", "The wallet password was invalid. Try again?", ButtonType.CANCEL, ButtonType.OK);
                                if(optResponse.isPresent() && optResponse.get().equals(ButtonType.OK)) {
                                    Platform.runLater(() -> openLnurlAuthUri(uri));
                                }
                            } else {
                                log.error("Error deriving wallet key", keyDerivationService.getException());
                            }
                        });
                        EventManager.get().post(new StorageEvent(storage.getWalletId(wallet), TimedEvent.Action.START, "Decrypting wallet..."));
                        keyDerivationService.start();
                    }
                } else {
                    try {
                        lnurlAuth.sendResponse(wallet);
                        EventManager.get().post(new StatusEvent("Successfully authenticated to " + lnurlAuth.getDomain()));
                    } catch(LnurlAuth.LnurlAuthException e) {
                        showErrorDialog("Error authenticating", "Failed to authenticate.\n\n" + e.getMessage());
                    } catch(Exception e) {
                        log.error("Failed to authenticate using LNURL-auth", e);
                        showErrorDialog("Error authenticating", "Failed to authenticate.\n\n" + e.getMessage());
                    }
                }
            }
        } catch(Exception e) {
            log.error("Not a valid LNURL-auth URI", e);
            showErrorDialog("Not a valid LNURL-auth URI", e.getMessage());
        }
    }

    private static Wallet selectWallet(List<PolicyType> policyTypes, List<ScriptType> scriptTypes, boolean taprootAllowed, boolean privateKeysRequired, String actionDescription, boolean alwaysAsk) {
        Wallet wallet = null;
        List<Wallet> wallets = get().getOpenWallets().keySet().stream().filter(w -> w.isValid() && policyTypes.contains(w.getPolicyType()) && scriptTypes.contains(w.getScriptType())
                && (!privateKeysRequired || w.getKeystores().stream().allMatch(Keystore::hasPrivateKey))).collect(Collectors.toList());
        if(wallets.isEmpty()) {
            boolean taprootOpen = get().getOpenWallets().keySet().stream().anyMatch(w -> w.getScriptType() == ScriptType.P2TR);
            showErrorDialog("No wallet available", "Open a" + (taprootOpen && !taprootAllowed ? " non-Taproot" : "") + (privateKeysRequired ? " software" : "") + " wallet to " + actionDescription + ".");
        } else if(wallets.size() == 1 && !alwaysAsk) {
            wallet = wallets.iterator().next();
        } else {
            ChoiceDialog<Wallet> walletChoiceDialog = new ChoiceDialog<>(wallets.iterator().next(), wallets);
            walletChoiceDialog.initOwner(getActiveWindow());
            walletChoiceDialog.setTitle("Choose Wallet");
            walletChoiceDialog.setHeaderText("Choose a wallet to " + actionDescription);
            walletChoiceDialog.getDialogPane().setGraphic(new DialogImage(DialogImage.Type.SPARROW));
            setStageIcon(walletChoiceDialog.getDialogPane().getScene().getWindow());
            moveToActiveWindowScreen(walletChoiceDialog);
            Optional<Wallet> optWallet = walletChoiceDialog.showAndWait();
            if(optWallet.isPresent()) {
                wallet = optWallet.get();
            }
        }

        return wallet;
    }

    public static boolean disallowAnyInvalidDerivationPaths(Wallet wallet) {
        Optional<ScriptType> optInvalidScriptType = wallet.getKeystores().stream()
                .filter(keystore -> keystore.getKeyDerivation() != null)
                .map(keystore -> wallet.getOtherScriptTypeMatchingDerivation(keystore.getKeyDerivation().getDerivationPath()))
                .filter(Optional::isPresent).map(Optional::get).findFirst();
        if(optInvalidScriptType.isPresent()) {
            ScriptType invalidScriptType = optInvalidScriptType.get();
            boolean includePolicyType = !wallet.getScriptType().getAllowedPolicyTypes().getFirst().equals(invalidScriptType.getAllowedPolicyTypes().getFirst());
            Optional<ButtonType> optType = AppServices.showWarningDialog("Invalid derivation path", "This wallet is using the derivation path for " +
                    invalidScriptType.getDescription(includePolicyType) + ", instead of the derivation path for its defined script type of " + wallet.getScriptType().getDescription(includePolicyType) +
                    ". \n\nDisable derivation path validation to import this wallet?", ButtonType.NO, ButtonType.YES);
            if(optType.isPresent()) {
                if(optType.get() == ButtonType.YES) {
                    Config.get().setValidateDerivationPaths(false);
                    System.setProperty(Wallet.ALLOW_DERIVATIONS_MATCHING_OTHER_SCRIPT_TYPES_PROPERTY, Boolean.toString(true));
                    System.setProperty(Wallet.ALLOW_DERIVATIONS_MATCHING_OTHER_NETWORKS_PROPERTY, Boolean.toString(true));
                } else {
                    return true;
                }
            }
        }

        return false;
    }

    public static final List<Network> WHIRLPOOL_NETWORKS = List.of(Network.MAINNET, Network.TESTNET);

    public static boolean isWhirlpoolCompatible(Wallet wallet) {
        return WHIRLPOOL_NETWORKS.contains(Network.get())
                && wallet.getPolicyType() == PolicyType.SINGLE_HD
                && wallet.getScriptType() != ScriptType.P2TR    //Taproot not yet supported
                && wallet.getKeystores().size() == 1
                && wallet.getKeystores().get(0).hasSeed()
                && wallet.getKeystores().get(0).getSeed().getType() == DeterministicSeed.Type.BIP39
                && wallet.getStandardAccountType() != null
                && StandardAccount.isMixableAccount(wallet.getStandardAccountType());
    }

    public static boolean isWhirlpoolPostmixCompatible(Wallet wallet) {
        return WHIRLPOOL_NETWORKS.contains(Network.get())
                && wallet.getPolicyType() == PolicyType.SINGLE_HD
                && wallet.getScriptType() != ScriptType.P2TR    //Taproot not yet supported
                && wallet.getKeystores().size() == 1
                && wallet.getKeystores().getFirst().getWalletModel() != WalletModel.BITBOX_02; //BitBox02 does not support high account numbers
    }

    public static List<Wallet> addWhirlpoolWallets(Wallet decryptedWallet, String walletId, Storage storage) {
        List<Wallet> childWallets = new ArrayList<>();
        for(StandardAccount whirlpoolAccount : StandardAccount.WHIRLPOOL_ACCOUNTS) {
            if(decryptedWallet.getChildWallet(whirlpoolAccount) == null) {
                Wallet childWallet = decryptedWallet.addChildWallet(whirlpoolAccount);
                childWallets.add(childWallet);
                EventManager.get().post(new ChildWalletsAddedEvent(storage, decryptedWallet, childWallet));
            }
        }

        return childWallets;
    }

    public static Font getMonospaceFont() {
        return Font.font("Fragment Mono Regular", 13);
    }

    public static boolean isOnWayland() {
        if(OsType.getCurrent() != OsType.UNIX) {
            return false;
        }

        String waylandDisplay = System.getenv("WAYLAND_DISPLAY");
        return waylandDisplay != null && !waylandDisplay.isEmpty();
    }

    @Subscribe
    public void newConnection(ConnectionEvent event) {
        setAnnouncedTip(new ChainTip(event.getBlockHeight(), event.getBlockHeader()));
        System.setProperty(Network.BLOCK_HEIGHT_PROPERTY, Integer.toString(event.getBlockHeight()));
        if(getConfiguredMinimumRelayFeeRate(Config.get()) == null) {
            minimumRelayFeeRate = event.getMinimumRelayFeeRate() == null ? Transaction.DEFAULT_MIN_RELAY_FEE : event.getMinimumRelayFeeRate();
        }
        serverMinimumRelayFeeRate = event.getMinimumRelayFeeRate();
        Config.get().addRecentServer();

        FeeRatesSource feeRatesSource = Config.get().getFeeRatesSource();
        feeRatesSource = (feeRatesSource == null ? FeeRatesSource.getDefault() : feeRatesSource);
        if(feeRatesSource.supportsNetwork(Network.get()) && feeRatesSource.isExternal()) {
            fetchFeeRates();
        }

        if(!blockSummaries.containsKey(getCurrentBlockHeight())) {
            fetchBlockSummaries(Collections.emptyList());
        }
    }

    @Subscribe
    public void usbDevicesFound(UsbDeviceEvent event) {
        devices = Collections.unmodifiableList(event.getDevices());
    }

    @Subscribe
    public void newBlock(NewBlockEvent event) {
        setAnnouncedTip(new ChainTip(event.getHeight(), event.getBlockHeader()));
        System.setProperty(Network.BLOCK_HEIGHT_PROPERTY, Integer.toString(event.getHeight()));
        String status = "Updating to new block height " + event.getHeight();
        EventManager.get().post(new StatusEvent(status));
        newBlockSubject.onNext(event);
    }

    @Subscribe
    public void blockSummary(BlockSummaryEvent event) {
        blockSummaries.putAll(event.getBlockSummaryMap());
        Integer currentBlockHeight = getCurrentBlockHeight();
        if(currentBlockHeight != null) {
            blockSummaries.keySet().removeIf(height -> currentBlockHeight - height > 5);
        }
        nextBlockMedianFeeRate = event.getNextBlockMedianFeeRate();
    }

    @Subscribe
    public void feesUpdated(FeeRatesUpdatedEvent event) {
        targetBlockFeeRates = event.getTargetBlockFeeRates();
        nextBlockMedianFeeRate = event.getNextBlockMedianFeeRate();
    }

    @Subscribe
    public void mempoolRateSizes(MempoolRateSizesUpdatedEvent event) {
        if(event.getMempoolRateSizes() != null) {
            addMempoolRateSizes(event.getMempoolRateSizes());
        }
    }

    @Subscribe
    public void feeRateSourceChanged(FeeRatesSourceChangedEvent event) {
        //Perform once-off fee rates retrieval to immediately change displayed rates
        fetchFeeRates();
        fetchBlockSummaries(Collections.emptyList());
    }

    @Subscribe
    public void fiatCurrencySelected(FiatCurrencySelectedEvent event) {
        if(ratesService != null) {
            ratesService.cancel();

            if(Config.get().getMode() != Mode.OFFLINE && event.getExchangeSource() != ExchangeSource.NONE) {
                ratesService = createRatesService(event.getExchangeSource(), event.getCurrency());
                ratesService.start();
            }
        }
    }

    @Subscribe
    public void exchangeRatesUpdated(ExchangeRatesUpdatedEvent event) {
        fiatCurrencyExchangeRate = event.getCurrencyRate();
    }

    @Subscribe
    public void openWallets(OpenWalletsEvent event) {
        if(event.getWalletTabDataList().isEmpty()) {
            List<WalletTabData> closedTabData = walletWindows.remove(event.getWindow());
            if(closedTabData != null && !closedTabData.isEmpty()) {
                EventManager.get().post(new WalletTabsClosedEvent(closedTabData));
            }
        } else {
            walletWindows.put(event.getWindow(), event.getWalletTabDataList());
        }

        List<WalletTabData> allWallets = walletWindows.values().stream().flatMap(Collection::stream).collect(Collectors.toList());

        Platform.runLater(() -> {
            if(!Window.getWindows().isEmpty()) {
                List<File> walletFiles = allWallets.stream().filter(walletTabData -> walletTabData.getWallet().getMasterWallet() == null).map(walletTabData -> walletTabData.getStorage().getWalletFile()).filter(File::exists).collect(Collectors.toList());
                Config.get().setRecentWalletFiles(Config.get().isLoadRecentWallets() ? walletFiles : Collections.emptyList());
            }
        });

        boolean usbWallet = false;
        for(WalletTabData walletTabData : allWallets) {
            Wallet wallet = walletTabData.getWallet();
            Storage storage = walletTabData.getStorage();

            if(Interface.get() == Interface.DESKTOP && (!storage.getWalletFile().exists() || wallet.containsSource(KeystoreSource.HW_USB) || CardApi.isReaderAvailable())) {
                usbWallet = true;

                if(deviceEnumerateService == null) {
                    deviceEnumerateService = createDeviceEnumerateService();
                }

                if(deviceEnumerateService.getState() == Worker.State.CANCELLED) {
                    deviceEnumerateService.reset();
                }

                if(!deviceEnumerateService.isRunning()) {
                    deviceEnumerateService.start();
                }

                break;
            }
        }

        if(!usbWallet && deviceEnumerateService != null && deviceEnumerateService.isRunning()) {
            deviceEnumerateService.cancel();
            EventManager.get().post(new UsbDeviceEvent(Collections.emptyList()));
        }
    }

    @Subscribe
    public void requestConnect(RequestConnectEvent event) {
        if(Config.get().hasServer()) {
            onlineProperty.set(true);
        }
    }

    @Subscribe
    public void requestDisconnect(RequestDisconnectEvent event) {
        onlineProperty.set(false);
        //Ensure services don't try to reconnect later
        Platform.runLater(() -> {
            connectionService.cancel();
            ratesService.cancel();
        });
    }

    @Subscribe
    public void walletAddressesChanged(WalletAddressesChangedEvent event) {
        restartBwt(event.getWallet());
    }

    @Subscribe
    public void walletOpening(WalletOpeningEvent event) {
        if(Config.get().getServerType() == ServerType.BITCOIN_CORE) {
            Platform.runLater(() -> restartBwt(event.getWallet()));
        }
    }

    @Subscribe
    public void childWalletsAdded(ChildWalletsAddedEvent event) {
        if(event.getChildWallets().stream().anyMatch(Wallet::isNested)) {
            restartBwt(event.getWallet());
        }
    }

    @Subscribe
    public void walletHistoryChanged(WalletHistoryChangedEvent event) {
        if(Config.get().getServerType() == ServerType.BITCOIN_CORE && event.getNestedHistoryChangedNodes().stream().anyMatch(node -> node.getTransactionOutputs().isEmpty())) {
            Platform.runLater(() -> restartBwt(event.getWallet()));
        }
    }

    private void restartBwt(Wallet wallet) {
        if(Config.get().getServerType() == ServerType.BITCOIN_CORE && connectionService != null && connectionService.isConnectionRunning() && wallet.isValid()) {
            connectionService.cancel();
        }
    }

    @Subscribe
    public void bwtShutdown(BwtShutdownEvent event) {
        if(onlineProperty().get() && !connectionService.isRunning()) {
            connectionService.reset();
            connectionService.start();
        }
    }

    @Subscribe
    public void walletHistoryFailed(WalletHistoryFailedEvent event) {
        if(Config.get().getServerType() == ServerType.PUBLIC_ELECTRUM_SERVER && isConnected()) {
            String currentName = Config.get().getServerDisplayName();
            onlineProperty.set(false);
            boolean changed = changePublicServer();
            if(changed) {
                log.warn("Failed to fetch wallet history from " + currentName + ", reconnecting to another server...");
            } else {
                log.warn("Failed to fetch wallet history from " + currentName + ", retrying later");
                connectionService.setDelay(Duration.seconds(PRIVATE_SERVER_RETRY_PERIOD_SECS));
                EventManager.get().post(new StatusEvent("Wallet load failed: No other public servers available that can serve the open wallets, retrying later..."));
            }
            onlineProperty.set(true);
        }
    }

    @Subscribe
    public void transactionProofsFailed(TransactionProofsFailedEvent event) {
        showProofsDialog(event, "Transaction Verification Failed", describeProofs(event.getReferences())
                + (event.getReferences().size() == 1 ? " but the proof of inclusion it supplied does not match that block." : " but the proofs of inclusion it supplied do not match those blocks.")
                + " This means the server is either faulty or dishonest, and what it reported may not have been confirmed at all.");
    }

    @Subscribe
    public void transactionProofsRefused(TransactionProofsRefusedEvent event) {
        showProofsDialog(event, "Transaction Verification Refused", describeProofs(event.getReferences())
                + (event.getReferences().size() == 1 ? " which then declined to prove it at that height." : " which then declined to prove them at those heights.")
                + " A server contradicting itself in this way may be faulty or overloaded, and what it reported cannot be taken as confirmed.");
    }

    private void showProofsDialog(TransactionProofsEvent event, String title, String content) {
        Platform.runLater(() -> {
            ButtonType refreshButton = new ButtonType("Refresh Wallet", ButtonBar.ButtonData.OK_DONE);
            Optional<ButtonType> optType = showErrorDialog(title, content + (event.getReferences().size() == 1 ? " It is" : " They are")
                    + " shown as unconfirmed until verified.\n\nConsider switching servers, and refreshing the wallet afterwards.",
                    ButtonType.CANCEL, refreshButton);
            if(optType.isPresent() && optType.get() == refreshButton) {
                EventManager.get().post(new RequestWalletRefreshEvent(event.getWallet()));
            }
        });
    }

    private static String describeProofs(Set<BlockTransactionHash> references) {
        BlockTransactionHash first = references.iterator().next();
        String firstId = first.getHashAsString().substring(0, 8) + "..";
        if(references.size() == 1) {
            return "Transaction " + firstId + " was reported as confirmed in block " + first.getHeight() + " by the connected server,";
        }

        return references.size() + " transactions, the first being " + firstId + " in block " + first.getHeight()
                + ", were reported as confirmed by the connected server,";
    }

    @Subscribe
    public void silentPaymentsUnsubscribe(SilentPaymentsUnsubscribeEvent event) {
        if(isConnected()) {
            ElectrumServer.SilentPaymentsUnsubscribeService unsubscribeService = new ElectrumServer.SilentPaymentsUnsubscribeService(event.getScanAddress());
            unsubscribeService.setOnFailed(workerStateEvent -> {
                log.warn("Failed to unsubscribe silent payments for " + event.getScanAddress().getAddress(), workerStateEvent.getSource().getException());
            });
            unsubscribeService.start();
        }
    }
}
