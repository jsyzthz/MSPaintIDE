package com.uddernetworks.mspaint.code.lsp;

import com.uddernetworks.mspaint.code.ImageClass;
import com.uddernetworks.mspaint.code.lsp.doc.DefaultDocumentManager;
import com.uddernetworks.mspaint.code.lsp.doc.Document;
import com.uddernetworks.mspaint.code.lsp.doc.DocumentManager;
import com.uddernetworks.mspaint.main.StartupLogic;
import com.uddernetworks.mspaint.project.ProjectManager;
import com.uddernetworks.mspaint.watcher.FileWatchManager;
import com.uddernetworks.mspaint.watcher.FileWatcher;
import com.uddernetworks.mspaint.watcher.WatchType;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.LanguageServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import static com.uddernetworks.mspaint.code.lsp.LSStatus.*;

public class LanguageServerWrapper {

    private static Logger LOGGER = LoggerFactory.getLogger(LanguageServerWrapper.class);

    private LSStatus status = LSStatus.STOPPED;
    private File rootPath;

    private RequestManager requestManager;
    private LanguageServer languageServer;
    private Future<Void> launcherFuture;
    private LSPClient client;

    private DocumentManager documentManager;
    private BiConsumer<LanguageServerWrapper, File> workspaceInit;
    private CompletableFuture<Void> startingFuture;

    private ObservableList<WorkspaceFolder> workspaces = FXCollections.observableArrayList();

    private StartupLogic startupLogic;
    private FileWatchManager fileWatchManager;
    private LSP lsp;
    private String serverPath;
    private List<String> lspArgs;
    private BiFunction<LanguageServerWrapper, List<String>, List<String>> argumentPreprocessor = (x, y) -> y;

    public LanguageServerWrapper(StartupLogic startupLogic, LSP lsp, List<String> lspArgs) {
        this(startupLogic, lsp, null, lspArgs);
    }

    public LanguageServerWrapper(StartupLogic startupLogic, LSP lsp, String serverPath, List<String> lspArgs) {
        this(startupLogic, lsp, serverPath, lspArgs, null);
    }

    public LanguageServerWrapper(StartupLogic startupLogic, LSP lsp, String serverPath, List<String> lspArgs, BiConsumer<LanguageServerWrapper, File> workspaceInit) {
        this.documentManager = new DefaultDocumentManager(this, startupLogic);
        this.startupLogic = startupLogic;
        this.fileWatchManager = startupLogic.getFileWatchManager();
        this.lsp = lsp;
        this.serverPath = serverPath;
        this.lspArgs = lspArgs;
        this.workspaceInit = workspaceInit;

        if (!lsp.usesWorkspaces()) return;
        this.workspaces.addListener((ListChangeListener<WorkspaceFolder>) change -> {
            var added = new ArrayList<WorkspaceFolder>();
            var removed = new ArrayList<WorkspaceFolder>();

            while (change.next()) {
                added.addAll(change.getAddedSubList());
                removed.addAll(change.getRemoved());
            }

            LOGGER.info("Adding: {}  Removing: {}", added, removed);

            this.languageServer.getWorkspaceService().didChangeWorkspaceFolders(
                    new DidChangeWorkspaceFoldersParams(
                            new WorkspaceFoldersChangeEvent(
                                    added,
                                    removed)));
        });
    }

    // rootPath will be null if launching LSP in headless mode
    private CompletableFuture<Void> start(File rootPath) {
        setStatus(STARTING);
        this.client = new LSPClient(this.startupLogic);

        this.rootPath = rootPath;

        try {
            var processedArgs = this.argumentPreprocessor.apply(this, new ArrayList<>(this.lspArgs));

            var streamConnectionProvider = new BetterProvider(
                    processedArgs,
                    serverPath); // new File(TEMP_ROOT).getParent()
            streamConnectionProvider.start();

            Launcher<LanguageServer> launcher =
                    Launcher.createLauncher(client, LanguageServer.class, streamConnectionProvider.getInputStream(), streamConnectionProvider.getOutputStream());

            languageServer = launcher.getRemoteProxy();
            client.connect(languageServer);
            launcherFuture = launcher.startListening();

            return (startingFuture = languageServer.initialize(getInitParams()).thenApply(res -> {
                LOGGER.info("Started {}", res);

                requestManager = new DefaultRequestManager(this, languageServer, client, res.getCapabilities());
                setStatus(STARTED);
                requestManager.initialized(new InitializedParams());
                setStatus(INITIALIZED);
                return res;
            }).thenRun(() -> LOGGER.info("Done starting LSP!")));

        } catch (Exception e) {
            LOGGER.error("Can't launch language server for project", e);
        }

        return CompletableFuture.runAsync(() -> {});
    }

    /*
     * Realistically, this only accepts ONE open workspace, since the IDE only allows one open window at once.
     */
    public void openWorkspace(File file, File inputFile) {
        // TODO: Throw if `file` is not in `rootPath`?
        verifyStatus(file.getParentFile()).thenRun(() -> {

            if (this.workspaceInit != null) {
                LOGGER.info("Running language-specific workspace init code...");
                this.workspaceInit.accept(this, file);
            }

            LOGGER.info("Adding workspace {}", file.getAbsolutePath());
            this.workspaces.add(getWorkspace(file));

            // Opening all paths because the Java LSP server listens to files itself
            var diagnosticManager = this.startupLogic.getDiagnosticManager();
            diagnosticManager.pauseDiagnostics();
            try {
                Files.walk(inputFile.toPath(), FileVisitOption.FOLLOW_LINKS)
                        .map(Path::toFile)
                        .filter(File::isFile)
                        .filter(walking -> walking.getName().endsWith(".png"))
                        .forEach(path -> {
                            try {
                                LOGGER.info("File {}", path.getName());
                                var document = this.documentManager.getDocument(path);
                                document.open();
                                highlightFile(document);
                            } catch (Exception e) {
                                LOGGER.error("Error", e);
                            }
                        });
            } catch (IOException e) {
                e.printStackTrace();
            }
            diagnosticManager.resumeDiagnostics();

            // This is NOT done in the Document class, because stuff may get messed up when deleting and mainly creating
            // new files.
            LOGGER.info("Watching {}", inputFile.getAbsolutePath());
            this.fileWatchManager.watchFile(inputFile).addListener((type, changedFile) -> {
                changedFile = changedFile.getAbsoluteFile();
                LOGGER.info("Changed: {}", changedFile.getAbsolutePath());
                if (!changedFile.getName().endsWith("png")) return;

                if (lsp.usesWorkspaces()) {
                    // TODO: Not sure if this should happen before or after the following switch?
                    this.languageServer.getWorkspaceService().didChangeWatchedFiles(new DidChangeWatchedFilesParams(Arrays.asList(
                            new FileEvent(changedFile.toURI().toString(), type.toFCT())
                    )));
                }

                Document document = null;
                switch (type) {
                    case CREATE:
                        LOGGER.info("Create document event {}", changedFile.getAbsolutePath());
                        (document = this.documentManager.getDocument(changedFile)).open();
                        break;
                    case MODIFY:
                        LOGGER.info("Modify document event {}", changedFile.getAbsolutePath());
                        document = this.documentManager.getDocument(changedFile);
                        if (!document.isOpened()) document.open();

                        document.notifyOfTextChange();
                        break;
                    case DELETE:
                        LOGGER.info("Delete document event {}", changedFile.getAbsolutePath());
                        this.documentManager.getDocument(changedFile, false).ifPresent(this.documentManager::deleteDocument);
                        break;
                }

                if (document != null) {
                    var imageClass = document.getImageClass();
                    if (type == WatchType.CREATE && imageClass.getScannedImage().isPresent()) highlightFile(document);
                }
            });
        });
    }

    private void highlightFile(Document document) {
        try {
            this.startupLogic.getCurrentLanguage().highlightAll(Collections.singletonList(document.getImageClass()));
        } catch (IOException e) {
            LOGGER.error("There was an error trying to highlight the created/modified file " + document.getFile().getName(), e);
        }
    }

    public void closeWorkspace(File file) {
        verifyStatus(file.getParentFile()).thenRun(() -> {
            LOGGER.info("Closing workspace {}", file.getAbsolutePath());
            this.workspaces.removeIf(workspace -> workspace.getUri().equals(file.toURI().toString()));
            this.fileWatchManager.getWatcher(file).ifPresent(FileWatcher::stopWatching);
        });
    }

    /*
     * As many open files are allowed, as they are not separate IDE windows abd are not part of any workspace.
     */
    public void openFile(ImageClass imageClass) {
        verifyStatus(null).thenRun(() -> {
            LOGGER.info("Opening file {}", imageClass.getInputImage().getAbsolutePath());
            this.documentManager.openFile(imageClass);
        });
    }

    public CompletableFuture<Void> verifyStatus(File rootPath) {
        if (getStatus() == STARTED || getStatus() == STARTING) {
            LOGGER.info("LSP server is starting up, waiting for it to finish...");
            return this.startingFuture;
        } else if (getStatus() == STOPPED) {
            LOGGER.info("LSP is stopped, waiting for it to start up...");
            return start(rootPath);
        } // INITIALIZED
        return CompletableFuture.runAsync(() -> {});
    }

    private WorkspaceFolder getWorkspace(File file) {
        return getWorkspace(file.getAbsolutePath());
    }

    private WorkspaceFolder getWorkspace(String file) {
        var workspace = new WorkspaceFolder();
        workspace.setUri(getURI(file));
        workspace.setName(ProjectManager.getPPFProject().getName());
        return workspace;
    }

    private WorkspaceFolder getWorkspace(String file, String name) {
        var workspace = new WorkspaceFolder();
        workspace.setUri(getURI(file));
        workspace.setName(name);
        return workspace;
    }

    private String getURI(String file) {
        return new File(file).toURI().toString();
    }

    // Init params from this method modified from LSP4IntelliJ ( Copyright (c) 2018-2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved )
    private InitializeParams getInitParams() {
        InitializeParams initParams = new InitializeParams();
//        initParams.setClientName("MS Paint IDE");
//        initParams.setWorkspaceFolders(List.of(new WorkspaceFolder(new File("E:\\MS Paint IDE Demos\\MS Paint IDE Demo\\clone\\shit\\other").toURI().toString())));
//        initParams.setWorkspaceFolders(Arrays.asList(new WorkspaceFolder(new File(TEMP_ROOT).toURI().toString())));
//        initParams.setRootUri(new File(TEMP_ROOT).getParentFile().toURI().toString());
        if (this.rootPath != null) {
            initParams.setRootUri(rootPath.toURI().toString());
            LOGGER.info("Root is to {}", rootPath.toURI().toString());
        }
        WorkspaceClientCapabilities workspaceClientCapabilities = new WorkspaceClientCapabilities();
        workspaceClientCapabilities.setApplyEdit(true);
        workspaceClientCapabilities.setDidChangeWatchedFiles(new DidChangeWatchedFilesCapabilities());
        workspaceClientCapabilities.setExecuteCommand(new ExecuteCommandCapabilities());
        workspaceClientCapabilities.setWorkspaceEdit(new WorkspaceEditCapabilities());
//        workspaceClientCapabilities.setSymbol(new SymbolCapabilities());
        workspaceClientCapabilities.setWorkspaceFolders(true);
        workspaceClientCapabilities.setConfiguration(true);

        TextDocumentClientCapabilities textDocumentClientCapabilities = new TextDocumentClientCapabilities();
        textDocumentClientCapabilities.setCodeAction(new CodeActionCapabilities());
        textDocumentClientCapabilities.setCompletion(new CompletionCapabilities(new CompletionItemCapabilities(false)));
        textDocumentClientCapabilities.setDefinition(new DefinitionCapabilities());
//        textDocumentClientCapabilities.setDocumentHighlight(new DocumentHighlightCapabilities());
        textDocumentClientCapabilities.setFormatting(new FormattingCapabilities());
//        textDocumentClientCapabilities.setHover(new HoverCapabilities());
//        textDocumentClientCapabilities.setOnTypeFormatting(new OnTypeFormattingCapabilities());
//        textDocumentClientCapabilities.setRangeFormatting(new RangeFormattingCapabilities());
        textDocumentClientCapabilities.setReferences(new ReferencesCapabilities());
        textDocumentClientCapabilities.setRename(new RenameCapabilities());
        textDocumentClientCapabilities.setSemanticHighlightingCapabilities(new SemanticHighlightingCapabilities(false));
        textDocumentClientCapabilities.setSignatureHelp(new SignatureHelpCapabilities());
        textDocumentClientCapabilities.setSynchronization(new SynchronizationCapabilities(true, true, true));
        initParams.setCapabilities(
                new ClientCapabilities(workspaceClientCapabilities, textDocumentClientCapabilities, null));
//        initParams.setInitializationOptions(null);
//        initParams.setInitializationOptions(
//                serverDefinition.getInitializationOptions(URI.create(initParams.getRootUri())));

        return initParams;
    }

    public LanguageServerWrapper argumentPreprocessor(BiFunction<LanguageServerWrapper, List<String>, List<String>> argumentPreprocessor) {
        this.argumentPreprocessor = argumentPreprocessor;
        return this;
    }

    public static File getLSPDirectory() {
//        return new File(StartupLogic.getJarParent().orElse(new File("")), "lsp");
        return new File("C:\\Program Files (x86)\\MS Paint IDE\\lsp"); // TODO: Uncomment before release
    }

    public Optional<File> getRootPath() {
        return Optional.ofNullable(rootPath);
    }

    public LSStatus getStatus() {
        return status;
    }

    public void setStatus(LSStatus status) {
        this.status = status;
    }

    public RequestManager getRequestManager() {
        return requestManager;
    }

    public DocumentManager getDocumentManager() {
        return documentManager;
    }

    public LanguageServer getLanguageServer() {
        return languageServer;
    }

    public LSPClient getClient() {
        return client;
    }

    public LSP getLSP() {
        return lsp;
    }

    public String getServerPath() {
        return serverPath;
    }
}