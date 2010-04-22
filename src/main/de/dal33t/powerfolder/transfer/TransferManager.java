/*
 * Copyright 2004 - 2008 Christian Sprajc. All rights reserved.
 *
 * This file is part of PowerFolder.
 *
 * PowerFolder is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation.
 *
 * PowerFolder is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PowerFolder. If not, see <http://www.gnu.org/licenses/>.
 *
 * $Id$
 */
package de.dal33t.powerfolder.transfer;

import java.io.*;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import de.dal33t.powerfolder.ConfigurationEntry;
import de.dal33t.powerfolder.Constants;
import de.dal33t.powerfolder.Controller;
import de.dal33t.powerfolder.Member;
import de.dal33t.powerfolder.PFComponent;
import de.dal33t.powerfolder.disk.Folder;
import de.dal33t.powerfolder.disk.FolderRepository;
import de.dal33t.powerfolder.event.ListenerSupportFactory;
import de.dal33t.powerfolder.event.TransferManagerEvent;
import de.dal33t.powerfolder.event.TransferManagerListener;
import de.dal33t.powerfolder.light.FileInfo;
import de.dal33t.powerfolder.light.FolderInfo;
import de.dal33t.powerfolder.message.AbortUpload;
import de.dal33t.powerfolder.message.DownloadQueued;
import de.dal33t.powerfolder.message.FileChunk;
import de.dal33t.powerfolder.message.RequestDownload;
import de.dal33t.powerfolder.message.TransferStatus;
import de.dal33t.powerfolder.net.ConnectionHandler;
import de.dal33t.powerfolder.transfer.swarm.FileRecordProvider;
import de.dal33t.powerfolder.transfer.swarm.VolatileFileRecordProvider;
import de.dal33t.powerfolder.util.Format;
import de.dal33t.powerfolder.util.NamedThreadFactory;
import de.dal33t.powerfolder.util.Reject;
import de.dal33t.powerfolder.util.StringUtils;
import de.dal33t.powerfolder.util.TransferCounter;
import de.dal33t.powerfolder.util.Validate;
import de.dal33t.powerfolder.util.WrapperExecutorService;
import de.dal33t.powerfolder.util.compare.MemberComparator;
import de.dal33t.powerfolder.util.compare.ReverseComparator;
import de.dal33t.powerfolder.util.delta.FilePartsRecord;

/**
 * Transfer manager for downloading/uploading files
 * 
 * @author <a href="mailto:totmacher@powerfolder.com">Christian Sprajc </a>
 * @version $Revision: 1.92 $
 */
public class TransferManager extends PFComponent {

    /**
     * The maximum size of a chunk transferred at once of older, prev 3.1.7/4.0
     * versions.
     */
    public static final int OLD_MAX_CHUNK_SIZE = 32 * 1024;
    public static final int OLD_MAX_REQUESTS_QUEUED = 20;
    public static final long PARTIAL_TRANSFER_DELAY = 10000; // Ten seconds
    public static final long ONE_DAY = 24 * 3600 * 1000; // One day in ms

    private static final DecimalFormat CPS_FORMAT = new DecimalFormat(
        "#,###,###,###.##");

    private volatile boolean started;

    private Thread myThread;
    /** Uploads that are waiting to start */
    private final List<Upload> queuedUploads;
    /** currently uploading */
    private final List<Upload> activeUploads;
    /** The list of completed download */
    private final List<Upload> completedUploads;
    /** currenly downloading */
    private final ConcurrentMap<FileInfo, DownloadManager> dlManagers;
    /**
     * The # of active and queued downloads of this node. Cached value. Only
     * used for performance optimization
     */
    private final ConcurrentMap<Member, Integer> downloadsCount;
    /** A set of pending files, which should be downloaded */
    private final List<Download> pendingDownloads;
    /** The list of completed download */
    private final List<DownloadManager> completedDownloads;

    /** The trigger, where transfermanager waits on */
    private final Object waitTrigger = new Object();
    private boolean transferCheckTriggered;
    /**
     * To lock the transfer checker. Lock this to make sure no transfer checks
     * are executed untill the lock is released.
     */
    // private ReentrantLock downloadsLock = new ReentrantLock();
    /**
     * To lock the transfer checker. Lock this to make sure no transfer checks
     * are executed untill the lock is released.
     */
    private final Lock uploadsLock = new ReentrantLock();
    private final Object downloadRequestLock = new Object();

    private FileRecordProvider fileRecordProvider;

    /** Threadpool for Upload Threads */
    private ExecutorService threadPool;

    /** The maximum concurrent uploads */
    private final int allowedUploads;

    /** the counter for uploads (effecitve) */
    private final TransferCounter uploadCounter;
    /** the counter for downloads (effecitve) */
    private final TransferCounter downloadCounter;

    /** the counter for up traffic (real) */
    private final TransferCounter totalUploadTrafficCounter;
    /** the counter for download traffic (real) */
    private final TransferCounter totalDownloadTrafficCounter;

    /** Provides bandwidth for the transfers */
    private final BandwidthProvider bandwidthProvider;

    /** Input limiter, currently shared between all LAN connections */
    private final BandwidthLimiter sharedLANInputHandler;
    /** Input limiter, currently shared between all WAN connections */
    private final BandwidthLimiter sharedWANInputHandler;
    /** Output limiter, currently shared between all LAN connections */
    private final BandwidthLimiter sharedLANOutputHandler;
    /** Output limiter, currently shared between all WAN connections */
    private final BandwidthLimiter sharedWANOutputHandler;

    private final TransferManagerListener listenerSupport;

    private DownloadManagerFactory downloadManagerFactory = MultiSourceDownloadManager.factory;

    public TransferManager(Controller controller) {
        super(controller);
        this.started = false;
        this.queuedUploads = new CopyOnWriteArrayList<Upload>();
        this.activeUploads = new CopyOnWriteArrayList<Upload>();
        this.completedUploads = new CopyOnWriteArrayList<Upload>();
        this.dlManagers = new ConcurrentHashMap<FileInfo, DownloadManager>();
        this.pendingDownloads = new CopyOnWriteArrayList<Download>();
        this.completedDownloads = new CopyOnWriteArrayList<DownloadManager>();
        this.downloadsCount = new ConcurrentHashMap<Member, Integer>();
        this.uploadCounter = new TransferCounter();
        this.downloadCounter = new TransferCounter();
        totalUploadTrafficCounter = new TransferCounter();
        totalDownloadTrafficCounter = new TransferCounter();

        // Create listener support
        this.listenerSupport = ListenerSupportFactory
            .createListenerSupport(TransferManagerListener.class);

        // maximum concurrent uploads
        allowedUploads = ConfigurationEntry.UPLOADS_MAX_CONCURRENT.getValueInt(
            getController()).intValue();
        if (allowedUploads <= 0) {
            throw new NumberFormatException("Illegal value for max uploads: "
                + allowedUploads);
        }

        bandwidthProvider = new BandwidthProvider(getController()
            .getThreadPool());

        sharedWANOutputHandler = new BandwidthLimiter();
        sharedWANInputHandler = new BandwidthLimiter();

        checkConfigCPS(ConfigurationEntry.UPLOADLIMIT_WAN, 0);
        checkConfigCPS(ConfigurationEntry.DOWNLOADLIMIT_WAN, 0);
        checkConfigCPS(ConfigurationEntry.UPLOADLIMIT_LAN, 0);
        checkConfigCPS(ConfigurationEntry.DOWNLOADLIMIT_LAN, 0);

        // bandwidthProvider.setLimitBPS(sharedWANOutputHandler, maxCps);
        // set ul limit
        setAllowedUploadCPSForWAN(getConfigCPS(ConfigurationEntry.UPLOADLIMIT_WAN));
        setAllowedDownloadCPSForWAN(getConfigCPS(ConfigurationEntry.DOWNLOADLIMIT_WAN));

        sharedLANOutputHandler = new BandwidthLimiter();
        sharedLANInputHandler = new BandwidthLimiter();

        // bandwidthProvider.setLimitBPS(sharedLANOutputHandler, maxCps);
        // set ul limit
        setAllowedUploadCPSForLAN(getConfigCPS(ConfigurationEntry.UPLOADLIMIT_LAN));
        setAllowedDownloadCPSForLAN(getConfigCPS(ConfigurationEntry.DOWNLOADLIMIT_LAN));

        if (getMaxFileChunkSize() > OLD_MAX_CHUNK_SIZE) {
            logWarning("Max filechunk size set to "
                + Format.formatBytes(getMaxFileChunkSize())
                + ". Can only communicate with clients"
                + " running version 3.1.7 or higher.");
        }
        if (getMaxRequestsQueued() > OLD_MAX_REQUESTS_QUEUED) {
            logWarning("Max request queue size set to "
                + getMaxRequestsQueued()
                + ". Can only communicate with clients"
                + " running version 3.1.7 or higher.");
        }
    }

    /**
     * Checks if the configration entry exists, and if not sets it to a given
     * value.
     * 
     * @param entry
     * @param _cps
     */
    private void checkConfigCPS(ConfigurationEntry entry, long _cps) {
        String cps = entry.getValue(getController());
        if (cps == null) {
            entry.setValue(getController(), Long.toString(_cps / 1024));
        }
    }

    private long getConfigCPS(ConfigurationEntry entry) {
        String cps = entry.getValue(getController());
        long maxCps = 0;
        if (cps != null) {
            try {
                maxCps = (long) Double.parseDouble(cps) * 1024;
                if (maxCps < 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                logWarning("Illegal value for KByte." + entry + " '" + cps
                    + '\'');
            }
        }
        return maxCps;
    }

    // General ****************************************************************

    /**
     * Starts the transfermanager thread
     */
    public void start() {
        if (!ConfigurationEntry.TRANSFER_MANAGER_ENABLED
            .getValueBoolean(getController()))
        {
            logWarning("Not starting TransferManager. disabled by config");
            return;
        }
        fileRecordProvider = new VolatileFileRecordProvider(getController());

        bandwidthProvider.start();

        threadPool = new WrapperExecutorService(Executors
            .newCachedThreadPool(new NamedThreadFactory("TMThread-")));

        myThread = new Thread(new TransferChecker(), "Transfer manager");
        myThread.start();

        // Load all pending downloads
        loadDownloads();

        // Do an initial clean.
        cleanupOldTransfers();

        getController().scheduleAndRepeat(new PartialTransferStatsUpdater(),
            PARTIAL_TRANSFER_DELAY, PARTIAL_TRANSFER_DELAY);

        getController().scheduleAndRepeat(new TransferCleaner(), ONE_DAY,
            ONE_DAY);

        started = true;
        logFine("Started");
    }

    /**
     * This method cleans up old uploads and downloads. Only cleans up if the
     * UPLOADS_AUTO_CLEANUP / DOWNLOAD_AUTO_CLEANUP is true and the transfer is
     * older than AUTO_CLEANUP_FREQUENCY in days.
     */
    private void cleanupOldTransfers() {
        if (ConfigurationEntry.UPLOADS_AUTO_CLEANUP
            .getValueBoolean(getController()))
        {
            Integer uploadCleanupFrequency = ConfigurationEntry.UPLOAD_AUTO_CLEANUP_FREQUENCY
                .getValueInt(getController());
            for (Upload completedUpload : completedUploads) {
                long numberOfDays = calcDays(completedUpload.getCompletedDate());
                if (numberOfDays >= uploadCleanupFrequency) {
                    logInfo("Auto-cleaning up upload '"
                        + completedUpload.getFile().getRelativeName()
                        + "' (days=" + numberOfDays + ')');
                    clearCompletedUpload(completedUpload);
                }
            }
        }
        if (ConfigurationEntry.DOWNLOADS_AUTO_CLEANUP
            .getValueBoolean(getController()))
        {
            Integer downloadCleanupFrequency = ConfigurationEntry.DOWNLOAD_AUTO_CLEANUP_FREQUENCY
                .getValueInt(getController());
            for (DownloadManager completedDownload : completedDownloads) {
                long numberOfDays = calcDays(completedDownload
                    .getCompletedDate());
                if (numberOfDays >= downloadCleanupFrequency) {
                    logInfo("Auto-cleaning up download '"
                        + completedDownload.getFileInfo().getRelativeName()
                        + "' (days=" + numberOfDays + ')');
                    clearCompletedDownload(completedDownload);
                }
            }
        }
    }

    /**
     * Returns the number of days between two dates.
     * 
     * @param completedDate
     * @return
     */
    private static long calcDays(Date completedDate) {
        if (completedDate == null) {
            return -1;
        }
        Date now = new Date();
        long diff = now.getTime() - completedDate.getTime();
        return diff / ONE_DAY;
    }

    /**
     * Shuts down the transfer manager
     */
    public void shutdown() {
        // Remove listners, not bothering them by boring shutdown events
        // ListenerSupportFactory.removeAllListeners(listenerSupport);

        // shutdown on thread
        if (myThread != null) {
            myThread.interrupt();
        }

        if (threadPool != null) {
            threadPool.shutdownNow();
        }

        // shutdown active uploads
        for (Upload upload : activeUploads) {
            upload.abort();
            upload.shutdown();
        }

        bandwidthProvider.shutdown();

        if (started) {
            storeDownloads();
        }

        // abort / shutdown active downloads
        // done after storeDownloads(), so they are restored!
        for (DownloadManager man : dlManagers.values()) {
            synchronized (man) {
                man.setBroken("shutdown");
            }
        }

        if (fileRecordProvider != null) {
            fileRecordProvider.shutdown();
            fileRecordProvider = null;
        }

        started = false;
        logFine("Stopped");
    }

    /**
     * for debug
     * 
     * @param suspended
     */
    public void setSuspendFireEvents(boolean suspended) {
        ListenerSupportFactory.setSuspended(listenerSupport, suspended);
        logFine("setSuspendFireEvents: " + suspended);
    }

    /**
     * Triggers the workingn checker thread.
     */
    public void triggerTransfersCheck() {
        synchronized (waitTrigger) {
            transferCheckTriggered = true;
            waitTrigger.notifyAll();
        }
    }

    public BandwidthProvider getBandwidthProvider() {
        return bandwidthProvider;
    }

    public BandwidthLimiter getOutputLimiter(ConnectionHandler handler) {
        if (handler.isOnLAN()) {
            return sharedLANOutputHandler;
        }
        return sharedWANOutputHandler;
    }

    public BandwidthLimiter getInputLimiter(ConnectionHandler handler) {
        if (handler.isOnLAN()) {
            return sharedLANInputHandler;
        }
        return sharedWANInputHandler;
    }

    /**
     * Returns the transferstatus by calculating it new
     * 
     * @return the current status
     */
    public TransferStatus getStatus() {
        TransferStatus transferStatus = new TransferStatus();

        // Upload status
        transferStatus.activeUploads = activeUploads.size();
        transferStatus.queuedUploads = queuedUploads.size();

        transferStatus.maxUploads = getAllowedUploads();
        transferStatus.maxUploadCPS = getAllowedUploadCPSForWAN();
        transferStatus.currentUploadCPS = (long) uploadCounter
            .calculateCurrentCPS();
        transferStatus.uploadedBytesTotal = uploadCounter.getBytesTransferred();

        // Download status
        for (DownloadManager man : dlManagers.values()) {
            for (Download download : man.getSources()) {
                if (download.isStarted()) {
                    transferStatus.activeDownloads++;
                } else {
                    transferStatus.queuedDownloads++;
                }
            }
        }

        transferStatus.maxDownloads = Integer.MAX_VALUE;
        transferStatus.maxDownloadCPS = Double.MAX_VALUE;
        transferStatus.currentDownloadCPS = (long) downloadCounter
            .calculateCurrentCPS();
        transferStatus.downloadedBytesTotal = downloadCounter
            .getBytesTransferred();

        return transferStatus;
    }

    // General Transfer callback methods **************************************

    /**
     * Returns the MultiSourceDownload, that's managing the given info.
     * 
     * @param info
     * @return
     */
    private DownloadManager getDownloadManagerFor(FileInfo info) {
        Validate.notNull(info);
        DownloadManager man = dlManagers.get(info);
        if (man != null
            && man.getFileInfo().isVersionDateAndSizeIdentical(info))
        {
            return man;
        }
        return null;
    }

    /**
     * Sets an transfer as started
     * 
     * @param transfer
     *            the transfer
     */
    void setStarted(Transfer transfer) {
        if (transfer instanceof Upload) {
            uploadsLock.lock();
            try {
                queuedUploads.remove(transfer);
                activeUploads.add((Upload) transfer);
            } finally {
                uploadsLock.unlock();
            }

            // Fire event
            fireUploadStarted(new TransferManagerEvent(this, (Upload) transfer));
        } else if (transfer instanceof Download) {
            // Fire event
            fireDownloadStarted(new TransferManagerEvent(this,
                (Download) transfer));
        }

        logFine("Transfer started: " + transfer);
    }

    /**
     * Callback to inform, that a download has been enqued at the remote ide
     * 
     * @param download
     *            the download request
     * @param member
     */
    public void downloadQueued(Download download, Member member) {
        Reject.noNullElements(download, member);
        fireDownloadQueued(new TransferManagerEvent(this, download));
    }

    /**
     * Sets a transfer as broken, removes from queues
     * 
     * @param upload
     *            the upload
     * @param transferProblem
     *            the problem that broke the transfer
     */
    void uploadBroken(Upload upload, TransferProblem transferProblem) {
        uploadBroken(upload, transferProblem, null);
    }

    void downloadBroken(Download download, TransferProblem problem,
        String problemInfo)
    {
        logWarning("Download broken: " + download + ' '
            + (problem == null ? "" : problem) + ": " + problemInfo);

        download.setTransferProblem(problem);
        download.setProblemInformation(problemInfo);

        removeDownload(download);

        // Fire event
        fireDownloadBroken(new TransferManagerEvent(this, download, problem,
            problemInfo));
    }

    /**
     * Sets a transfer as broken, removes from queues
     * 
     * @param upload
     *            the upload
     * @param transferProblem
     *            the problem that broke the transfer
     * @param problemInformation
     *            specific information about the problem
     */
    void uploadBroken(Upload upload, TransferProblem transferProblem,
        String problemInformation)
    {
        // Ensure shutdown
        upload.shutdown();
        boolean transferFound = false;
        logWarning("Upload broken: " + upload + ' '
            + (transferProblem == null ? "" : transferProblem) + ": "
            + problemInformation);
        uploadsLock.lock();
        try {
            transferFound = queuedUploads.remove(upload);
            transferFound = transferFound || activeUploads.remove(upload);
        } finally {
            uploadsLock.unlock();
        }

        // Tell remote peer if possible
        if (upload.getPartner().isCompletelyConnected()) {
            logWarning("Sending abort upload of " + upload);
            upload.getPartner().sendMessagesAsynchron(
                new AbortUpload(upload.getFile()));
        }

        // Fire event
        if (transferFound) {
            fireUploadBroken(new TransferManagerEvent(this, upload));
        }

        // Now trigger, to check uploads/downloads to start
        triggerTransfersCheck();
    }

    /**
     * Breaks all transfers on that folder, usually on folder remove
     * 
     * @param foInfo
     */
    public void breakTransfers(final FolderInfo foInfo) {
        Reject.ifNull(foInfo, "Folderinfo is null");
        // Search for uls to break
        if (!queuedUploads.isEmpty()) {
            for (Upload upload : queuedUploads) {
                if (foInfo.equals(upload.getFile().getFolderInfo())) {
                    uploadBroken(upload, TransferProblem.FOLDER_REMOVED,
                        foInfo.name);
                }
            }
        }

        if (!activeUploads.isEmpty()) {
            for (Upload upload : activeUploads) {
                if (foInfo.equals(upload.getFile().getFolderInfo())) {
                    uploadBroken(upload, TransferProblem.FOLDER_REMOVED,
                        foInfo.name);
                }
            }
        }

        for (DownloadManager man : dlManagers.values()) {
            for (Download download : man.getSources()) {
                if (foInfo.equals(download.getFile().getFolderInfo())) {
                    download.setBroken(TransferProblem.FOLDER_REMOVED,
                        foInfo.name);
                }
            }
        }
    }

    /**
     * Breaks all transfers from and to that node. Usually done on disconnect
     * 
     * @param node
     */
    public void breakTransfers(final Member node) {
        // Search for uls to break
        if (!queuedUploads.isEmpty()) {
            for (Upload upload : queuedUploads) {
                if (node.equals(upload.getPartner())) {
                    uploadBroken(upload, TransferProblem.NODE_DISCONNECTED,
                        node.getNick());
                }
            }
        }

        if (!activeUploads.isEmpty()) {
            for (Upload upload : activeUploads) {
                if (node.equals(upload.getPartner())) {
                    uploadBroken(upload, TransferProblem.NODE_DISCONNECTED,
                        node.getNick());
                }
            }
        }

        for (DownloadManager man : dlManagers.values()) {
            for (Download download : man.getSources()) {
                if (node.equals(download.getPartner())) {
                    download.setBroken(TransferProblem.NODE_DISCONNECTED, node
                        .getNick());
                }
            }
        }
    }

    /**
     * Breaks all transfers on the file. Usually when the file is going to be
     * changed. TODO: Transfers have been ABORTED instead of BROKEN before.
     * 
     * @param fInfo
     */
    public void breakTransfers(final FileInfo fInfo) {
        Reject.ifNull(fInfo, "FileInfo is null");
        // Search for uls to break
        if (!queuedUploads.isEmpty()) {
            for (Upload upload : queuedUploads) {
                if (fInfo.equals(upload.getFile())) {
                    uploadBroken(upload, TransferProblem.FILE_CHANGED, fInfo
                        .getRelativeName());
                }
            }
        }

        if (!activeUploads.isEmpty()) {
            for (Upload upload : activeUploads) {
                if (fInfo.equals(upload.getFile())) {
                    upload.abort();
                    uploadBroken(upload, TransferProblem.FILE_CHANGED, fInfo
                        .getRelativeName());
                }
            }
        }

        for (DownloadManager man : dlManagers.values()) {
            for (Download download : man.getSources()) {
                if (fInfo.equals(download.getFile())) {
                    download.setBroken(TransferProblem.FILE_CHANGED, fInfo
                        .getRelativeName());
                }
            }
        }
    }

    void setCompleted(DownloadManager dlManager) {
        assert dlManager.isDone();

        FileInfo fInfo = dlManager.getFileInfo();
        // Inform other folder member of added file
        Folder folder = fInfo.getFolder(getController().getFolderRepository());
        if (folder != null) {
            // scan in new downloaded file
            // TODO React on failed scan?
            // TODO PREVENT further uploads of this file unless it's "there"
            // Search for active uploads of the file and break them
            boolean abortedUL;
            do {
                abortedUL = abortUploadsOf(fInfo);
                if (abortedUL) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                    }
                }
            } while (abortedUL);

            assert getActiveUploads(fInfo).isEmpty();

            if (folder.scanDownloadFile(fInfo, dlManager.getTempFile())) {
                if (StringUtils.isNotBlank(folder.getDownloadScript())) {
                    executeDownloadScript(fInfo, folder);
                }
            } else {
                logSevere("Scanning of completed file failed: "
                    + fInfo.toDetailString() + " at " + dlManager.getTempFile());
                dlManager.setBroken("Scanning of completed file failed: "
                    + fInfo.toDetailString());
                return;
            }
        }
        completedDownloads.add(dlManager);
        for (Download d : dlManager.getSources()) {
            d.setCompleted();
        }
        removeDownloadManager(dlManager);

        // Auto cleanup of Downloads

        if (ConfigurationEntry.DOWNLOADS_AUTO_CLEANUP
            .getValueBoolean(getController())
            && ConfigurationEntry.DOWNLOAD_AUTO_CLEANUP_FREQUENCY
                .getValueInt(getController()) == 0)
        {
            if (isFiner()) {
                logFiner("Auto-cleaned " + dlManager.getSources());
            }
            clearCompletedDownload(dlManager);
        }

        handleMetaFolderDownload(fInfo);
    }

    /**
     * Handle any metaFolder downloads. Files downloaded to a metaFolder
     * affect the parent folder.
     *
     * @param fileInfo
     */
    private void handleMetaFolderDownload(FileInfo fileInfo) {
        if (getController().getFolderRepository().isMetaFolder(
                fileInfo.getFolderInfo())) {
            if (fileInfo.getFilenameOnly().equals(
                    Folder.META_FOLDER_SYNC_PATTERNS_FILE_NAME)) {
                handleMetaFolderSyncPatterns(fileInfo);
            }
        }
    }

    /**
     * Updated sync patterns have been downloaded to the metaFolder.
     * Update the sync patterns in the parent folder.
     *
     * @param fileInfo
     */
    private void handleMetaFolderSyncPatterns(FileInfo fileInfo) {
        Folder parentFolder = getController().getFolderRepository()
                .getParentFolder(fileInfo.getFolderInfo());
        if (parentFolder == null) {
            logWarning("Could not find parent folder for " + fileInfo);
            return;
        }
        if (!parentFolder.isSyncPatterns()) {
            logFine("Parent folder is not syncing patterns: " +
                    parentFolder.getName());
            return;
        }
        Folder metaFolder = getController().getFolderRepository()
                .getMetaFolderForParent(parentFolder.getInfo());
        if (metaFolder == null) {
            logWarning("Could not find metaFolder for " +
                    parentFolder.getInfo());
            return;
        }
        File syncPatternsFile = metaFolder.getDiskFile(fileInfo);
        logFine("Reading syncPatterns " + syncPatternsFile);
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(syncPatternsFile));
            parentFolder.getDiskItemFilter().removeAllPatterns();
            String line;
            while((line = br.readLine()) != null) {
                parentFolder.getDiskItemFilter().addPattern(line);
            }
        } catch (Exception e) {
            logSevere(e);
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }

    /**
     * #1538
     * <p>
     * http://www.powerfolder.com/wiki/Script_execution
     * 
     * @param fInfo
     * @param folder
     */
    private void executeDownloadScript(FileInfo fInfo, Folder folder) {
        Reject
            .ifBlank(folder.getDownloadScript(), "Download script is not set");
        File dlFile = fInfo.getDiskFile(getController().getFolderRepository());
        String command = folder.getDownloadScript();
        command = command.replace("$file", dlFile.getAbsolutePath());
        command = command.replace("$path", dlFile.getParent());
        command = command.replace("$folderpath", folder.getLocalBase()
            .getAbsolutePath());
        try {
            logInfo("Executing command: " + command);
            Runtime.getRuntime().exec(command);
        } catch (IOException e) {
            logSevere("Unable to execute script after download. '"
                + folder.getDownloadScript() + "' file: " + dlFile + ". " + e,
                e);
        }
    }

    private boolean abortUploadsOf(FileInfo fInfo) {
        boolean abortedUL = false;
        uploadsLock.lock();
        try {
            for (Upload u : activeUploads) {
                if (u.getFile().equals(fInfo)) {
                    abortUpload(fInfo, u.getPartner());
                    abortedUL = true;
                }
            }
            List<Upload> remove = new LinkedList<Upload>();
            for (Upload u : queuedUploads) {
                if (u.getFile().equals(fInfo)) {
                    abortedUL = true;
                    remove.add(u);
                }
            }
            queuedUploads.removeAll(remove);
        } finally {
            uploadsLock.unlock();
        }
        return abortedUL;
    }

    /**
     * Returns all uploads of the given file.
     * 
     * @param info
     * @return
     */
    private List<Upload> getActiveUploads(FileInfo info) {
        List<Upload> ups = new ArrayList<Upload>();
        for (Upload u : activeUploads) {
            if (u.getFile().equals(info)) {
                ups.add(u);
            }
        }
        return ups;
    }

    /**
     * Coounts the number of active uploads for a folder.
     * 
     * @param folder
     * @return
     */
    public int countActiveUploads(Folder folder) {
        int count = 0;
        for (Upload activeUpload : activeUploads) {
            if (activeUpload.getFile().getFolderInfo().equals(folder.getInfo()))
            {
                count++;
            }
        }
        return count;
    }

    /**
     * Callback method to indicate that a transfer is completed
     * 
     * @param transfer
     */
    void setCompleted(Transfer transfer) {
        boolean transferFound = false;

        FileInfo fileInfo = transfer.getFile();
        if (transfer instanceof Download) {
            // Fire event
            fireDownloadCompleted(new TransferManagerEvent(this,
                (Download) transfer));

            downloadsCount.remove(transfer.getPartner());
            int nDlFromNode = countActiveAndQueuedDownloads(transfer
                .getPartner());
            boolean requestMoreFiles = nDlFromNode == 0;
            if (!requestMoreFiles) {
                // Hmm maybe end of transfer queue is near (25% or less filled),
                // request if yes!
                if (transfer.getPartner().isOnLAN()) {
                    requestMoreFiles = nDlFromNode <= Constants.MAX_DLS_FROM_LAN_MEMBER / 4;
                } else {
                    requestMoreFiles = nDlFromNode <= Constants.MAX_DLS_FROM_INET_MEMBER / 4;
                }
            }

            if (requestMoreFiles) {
                // Trigger filerequestor
                getController().getFolderRepository().getFileRequestor()
                    .triggerFileRequesting(fileInfo.getFolderInfo());
            } else {
                logFiner("Not triggering file requestor. " + nDlFromNode
                    + " more dls from " + transfer.getPartner());
            }

        } else if (transfer instanceof Upload) {
            transfer.setCompleted();

            uploadsLock.lock();
            try {
                transferFound = queuedUploads.remove(transfer);
                transferFound = activeUploads.remove(transfer) || transferFound;
                completedUploads.add((Upload) transfer);
            } finally {
                uploadsLock.unlock();
            }

            if (transferFound) {
                // Fire event
                fireUploadCompleted(new TransferManagerEvent(this,
                    (Upload) transfer));
            }

            // Auto cleanup of uploads
            if (ConfigurationEntry.UPLOADS_AUTO_CLEANUP
                .getValueBoolean(getController())
                && ConfigurationEntry.UPLOAD_AUTO_CLEANUP_FREQUENCY
                    .getValueInt(getController()) == 0)
            {
                if (isFiner()) {
                    logFiner("Auto-cleaned " + transfer);
                }
                clearCompletedUpload((Upload) transfer);
            }
        }

        // Now trigger, to start next transfer
        triggerTransfersCheck();

        if (isFiner()) {
            logFiner("Transfer completed: " + transfer);
        }
    }

    // Upload management ******************************************************

    /**
     * This method is called after any change associated with bandwidth. I.e.:
     * upload limits, silent mode
     */
    public void updateSpeedLimits() {
        int throttle = 100;

        if (getController().isSilentMode()) {
            try {
                throttle = Integer
                    .parseInt(ConfigurationEntry.UPLOADLIMIT_SILENTMODE_THROTTLE
                        .getValue(getController()));
                if (throttle < 10) {
                    throttle = 10;
                } else if (throttle > 100) {
                    throttle = 100;
                }
            } catch (NumberFormatException nfe) {
                throttle = 100;
                // logFine(nfe);
            }
        }

        // Any setting that is "unlimited" will stay unlimited!
        bandwidthProvider.setLimitBPS(sharedLANOutputHandler,
            getAllowedUploadCPSForLAN() * throttle / 100);
        bandwidthProvider.setLimitBPS(sharedWANOutputHandler,
            getAllowedUploadCPSForWAN() * throttle / 100);
        bandwidthProvider.setLimitBPS(sharedLANInputHandler,
            getAllowedDownloadCPSForLAN() * throttle / 100);
        bandwidthProvider.setLimitBPS(sharedWANInputHandler,
            getAllowedDownloadCPSForWAN() * throttle / 100);
    }

    /**
     * Sets the maximum upload bandwidth usage in CPS
     * 
     * @param allowedCPS
     */
    public void setAllowedUploadCPSForWAN(long allowedCPS) {
        if (allowedCPS != 0 && allowedCPS < 3 * 1024
            && !getController().isVerbose())
        {
            logWarning("Setting upload limit to a minimum of 3 KB/s");
            allowedCPS = 3 * 1024;
        }

        // Store in config
        ConfigurationEntry.UPLOADLIMIT_WAN.setValue(getController(), String
            .valueOf(allowedCPS / 1024));

        updateSpeedLimits();

        logInfo("Upload limit: " + allowedUploads
            + " allowed, at maximum rate of "
            + (getAllowedUploadCPSForWAN() / 1024) + " KByte/s");
    }

    /**
     * @return the allowed upload rate (internet) in CPS
     */
    public long getAllowedUploadCPSForWAN() {
        return Integer.parseInt(ConfigurationEntry.UPLOADLIMIT_WAN
            .getValue(getController())) * 1024;
    }

    /**
     * Sets the maximum download bandwidth usage in CPS
     * 
     * @param allowedCPS
     */
    public void setAllowedDownloadCPSForWAN(long allowedCPS) {
        // if (allowedCPS != 0 && allowedCPS < 3 * 1024
        // && !getController().isVerbose())
        // {
        // logWarning("Setting download limit to a minimum of 3 KB/s");
        // allowedCPS = 3 * 1024;
        // }
        //
        // Store in config
        ConfigurationEntry.DOWNLOADLIMIT_WAN.setValue(getController(), String
            .valueOf(allowedCPS / 1024));

        updateSpeedLimits();

        logInfo("Download limit: " + allowedUploads
            + " allowed, at maximum rate of "
            + (getAllowedDownloadCPSForWAN() / 1024) + " KByte/s");
    }

    /**
     * @return the allowed download rate (internet) in CPS
     */
    public long getAllowedDownloadCPSForWAN() {
        return ConfigurationEntry.DOWNLOADLIMIT_WAN
            .getValueInt(getController()) * 1024;
    }

    /**
     * Sets the maximum upload bandwidth usage in CPS for LAN
     * 
     * @param allowedCPS
     */
    public void setAllowedUploadCPSForLAN(long allowedCPS) {
        if (allowedCPS != 0 && allowedCPS < 3 * 1024
            && !getController().isVerbose())
        {
            logWarning("Setting upload limit to a minimum of 3 KB/s");
            allowedCPS = 3 * 1024;
        }
        // Store in config
        ConfigurationEntry.UPLOADLIMIT_LAN.setValue(getController(), String
            .valueOf(allowedCPS / 1024));

        updateSpeedLimits();

        logInfo("LAN Upload limit: " + allowedUploads
            + " allowed, at maximum rate of "
            + (getAllowedUploadCPSForLAN() / 1024) + " KByte/s");
    }

    /**
     * @return the allowed upload rate (LAN) in CPS
     */
    public long getAllowedUploadCPSForLAN() {
        return ConfigurationEntry.UPLOADLIMIT_LAN.getValueInt(getController()) * 1024;

    }

    /**
     * Sets the maximum upload bandwidth usage in CPS for LAN
     * 
     * @param allowedCPS
     */
    public void setAllowedDownloadCPSForLAN(long allowedCPS) {
        // if (allowedCPS != 0 && allowedCPS < 3 * 1024
        // && !getController().isVerbose())
        // {
        // logWarning("Setting upload limit to a minimum of 3 KB/s");
        // allowedCPS = 3 * 1024;
        // }
        // Store in config
        ConfigurationEntry.DOWNLOADLIMIT_LAN.setValue(getController(), String
            .valueOf(allowedCPS / 1024));

        updateSpeedLimits();

        logInfo("LAN Download limit: " + allowedUploads
            + " allowed, at maximum rate of "
            + (getAllowedDownloadCPSForLAN() / 1024) + " KByte/s");
    }

    /**
     * @return the allowed download rate (LAN) in CPS
     */
    public long getAllowedDownloadCPSForLAN() {
        return Integer.parseInt(ConfigurationEntry.DOWNLOADLIMIT_LAN
            .getValue(getController())) * 1024;
    }

    /**
     *@see ConfigurationEntry#TRANSFERS_MAX_FILE_CHUNK_SIZE
     * @return the maximum size of a {@link FileChunk}
     */
    int getMaxFileChunkSize() {
        return ConfigurationEntry.TRANSFERS_MAX_FILE_CHUNK_SIZE
            .getValueInt(getController());
    }

    /**
     * @see ConfigurationEntry#TRANSFERS_MAX_REQUESTS_QUEUED
     * @return
     */
    int getMaxRequestsQueued() {
        return ConfigurationEntry.TRANSFERS_MAX_REQUESTS_QUEUED
            .getValueInt(getController());
    }

    /**
     * @return true if the manager has free upload slots
     */
    private boolean hasFreeUploadSlots() {
        Set<Member> uploadsTo = new HashSet<Member>();
        for (Upload upload : activeUploads) {
            uploadsTo.add(upload.getPartner());
        }
        return uploadsTo.size() < allowedUploads;
    }

    /**
     * @return the maximum number of allowed uploads
     */
    public int getAllowedUploads() {
        return allowedUploads;
    }

    /**
     * @return the counter for upload speed
     */
    public TransferCounter getUploadCounter() {
        return uploadCounter;
    }

    /**
     * @return the counter for total upload traffic (real)
     */
    public TransferCounter getTotalUploadTrafficCounter() {
        return totalUploadTrafficCounter;
    }

    /**
     * Queues a upload into the upload queue. Breaks all former upload requests
     * for the file from that member. Will not be queued if file not exists or
     * is deleted in the meantime or if the connection with the requestor is
     * lost.
     * 
     * @param from
     * @param dl
     * @return the enqued upload, or null if not queued.
     */
    public Upload queueUpload(Member from, RequestDownload dl) {
        logFine("Received download request from " + from + ": " + dl);
        if (dl == null || dl.file == null) {
            throw new NullPointerException("Downloadrequest/File is null");
        }
        // Never upload db files !!
        if (Folder.DB_FILENAME.equalsIgnoreCase(dl.file.getRelativeName())
            || Folder.DB_BACKUP_FILENAME.equalsIgnoreCase(dl.file
                .getRelativeName()))
        {
            logSevere(from.getNick()
                + " has illegally requested to download a folder database file");
            return null;
        }
        Folder folder = dl.file
            .getFolder(getController().getFolderRepository());
        if (folder == null) {
            logSevere("Received illegal download request from "
                + from.getNick() + ". Not longer on folder "
                + dl.file.getFolderInfo());
            return null;
        }
        if (!folder.hasReadPermission(from)) {
            logWarning("No Read permission: " + from + " on " + folder);
            return null;
        }

        if (dlManagers.containsKey(dl.file)) {
            logWarning("Not queuing upload, active download of the file is in progress.");
            return null;
        }

        FolderRepository repo = getController().getFolderRepository();
        File diskFile = dl.file.getDiskFile(repo);
        boolean fileInSyncWithDisk = diskFile != null
            && dl.file.inSyncWithDisk(diskFile);
        if (!fileInSyncWithDisk) {
            logWarning("File not in sync with disk: '"
                + dl.file.toDetailString() + "', should be modified at "
                + diskFile.lastModified());
            // This should free up an otherwise waiting for download partner
            if (folder.scanAllowedNow()) {
                folder.scanChangedFile(dl.file);
            }
            // folder.recommendScanOnNextMaintenance();
            return null;
        }

        FileInfo localFile = dl.file.getLocalFileInfo(repo);
        boolean fileInSyncWithDb = localFile != null
            && localFile.isVersionDateAndSizeIdentical(dl.file);
        if (!fileInSyncWithDb) {
            logWarning("File not in sync with db: '" + dl.file.toDetailString()
                + "', but I have "
                + ((localFile != null) ? localFile.toDetailString() : ""));
            return null;
        }

        Upload upload = new Upload(this, from, dl);
        if (upload.isBroken()) { // connection lost
            // Check if this download is broken
            return null;
        }

        Upload oldUpload = null;
        // Check if we have a old upload to break
        uploadsLock.lock();
        try {
            int oldUploadIndex = activeUploads.indexOf(upload);
            if (oldUploadIndex >= 0) {
                oldUpload = activeUploads.get(oldUploadIndex);
                activeUploads.remove(oldUploadIndex);
            }
            oldUploadIndex = queuedUploads.indexOf(upload);
            if (oldUploadIndex >= 0) {
                if (oldUpload != null) {
                    // Should never happen
                    throw new IllegalStateException(
                        "Found illegal upload. is in list of queued AND active uploads: "
                            + oldUpload);
                }
                oldUpload = queuedUploads.get(oldUploadIndex);
                queuedUploads.remove(oldUploadIndex);
            }

            logFine("Upload enqueud: " + dl.file.toDetailString()
                + ", startOffset: " + dl.startOffset + ", to: " + from);
            queuedUploads.add(upload);
        } finally {
            uploadsLock.unlock();
        }

        // Fire as requested
        fireUploadRequested(new TransferManagerEvent(this, upload));

        if (oldUpload != null) {
            logWarning("Received already known download request for " + dl.file
                + " from " + from.getNick() + ", overwriting old request");
            // Stop former upload request
            oldUpload.abort();
            oldUpload.shutdown();
            uploadBroken(oldUpload, TransferProblem.OLD_UPLOAD, from.getNick());
        }

        // Trigger working thread on upload enqueued
        triggerTransfersCheck();

        // Wait to let the transfers check grab the new download
        try {
            Thread.sleep(5);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // If upload is not started, tell peer
        if (!upload.isStarted()) {
            from.sendMessageAsynchron(new DownloadQueued(upload.getFile()),
                null);
        } else if (isFiner()) {
            logFiner("Optimization. Did not send DownloadQueued message for "
                + upload.getFile() + " to " + upload.getPartner());
        }
        return upload;
    }

    /**
     * Aborts a upload
     * 
     * @param fInfo
     *            the file to upload
     * @param to
     *            the member where is file is going to
     */
    public void abortUpload(FileInfo fInfo, Member to) {
        if (fInfo == null) {
            throw new NullPointerException(
                "Unable to abort upload, file is null");
        }
        if (to == null) {
            throw new NullPointerException(
                "Unable to abort upload, to-member is null");
        }

        Upload abortedUpload = null;

        for (Upload upload : queuedUploads) {
            if (upload.getFile().isVersionDateAndSizeIdentical(fInfo)
                && to.equals(upload.getPartner()))
            {
                // Remove upload from queue
                uploadsLock.lock();
                try {
                    queuedUploads.remove(upload);
                } finally {
                    uploadsLock.unlock();
                }
                upload.abort();
                abortedUpload = upload;
            }
        }

        for (Upload upload : activeUploads) {
            if (upload.getFile().isVersionDateAndSizeIdentical(fInfo)
                && to.equals(upload.getPartner()))
            {
                // Remove upload from queue
                uploadsLock.lock();
                try {
                    activeUploads.remove(upload);
                } finally {
                    uploadsLock.unlock();
                }
                upload.abort();
                abortedUpload = upload;
            }
        }

        if (abortedUpload != null) {
            fireUploadAborted(new TransferManagerEvent(this, abortedUpload));

            // Trigger check
            triggerTransfersCheck();
        } else {
            logWarning("Failed to abort upload: " + fInfo + " to " + to);
        }
    }

    /**
     * Returns the {@link FileRecordProvider} managing the
     * {@link FilePartsRecord}s for the various uploads/downloads.
     * 
     * @return
     */
    public FileRecordProvider getFileRecordManager() {
        return fileRecordProvider;
    }

    /**
     * Perfoms a upload in the tranfsermanagers threadpool.
     * 
     * @param uploadPerformer
     */
    void perfomUpload(Runnable uploadPerformer) {
        threadPool.submit(uploadPerformer);
    }

    /**
     * Generic stuff to do
     * 
     * @param worker
     */
    void doWork(Runnable worker) {
        threadPool.submit(worker);
    }

    /**
     * @return the number of currently activly transferring uploads.
     */
    public int countActiveUploads() {
        return activeUploads.size();
    }

    /**
     * @return the currently active uploads
     */
    public Collection<Upload> getActiveUploads() {
        return Collections.unmodifiableCollection(activeUploads);
    }

    /**
     * @return an unmodifiable collection reffering to the internal completed
     *         upload list. May change after returned.
     */
    public List<Upload> getCompletedUploadsCollection() {
        return Collections.unmodifiableList(completedUploads);
    }

    /**
     * @return the number of queued uploads.
     */
    public int countQueuedUploads() {
        return queuedUploads.size();
    }

    /**
     * @return the total number of queued / active uploads
     */
    public int countLiveUploads() {
        return activeUploads.size() + queuedUploads.size();
    }

    /**
     * @return the total number of uploads
     */
    public int countAllUploads() {
        return activeUploads.size() + queuedUploads.size()
            + completedUploads.size();
    }

    /**
     * @param folder
     *            the folder.
     * @return the number of uploads on the folder
     */
    public int countUploadsOn(Folder folder) {
        int nUploads = 0;
        for (Upload upload : activeUploads) {
            if (upload.getFile().getFolderInfo().equals(folder.getInfo())) {
                nUploads++;
            }
        }
        for (Upload upload : queuedUploads) {
            if (upload.getFile().getFolderInfo().equals(folder.getInfo())) {
                nUploads++;
            }
        }
        return nUploads;
    }

    /**
     * Answers all queued uploads
     * 
     * @return Array of all queued upload
     */
    public Collection<Upload> getQueuedUploads() {
        return Collections.unmodifiableCollection(queuedUploads);

    }

    /**
     * Answers, if we are uploading to this member
     * 
     * @param member
     *            the receiver
     * @return true if we are uploading to that member
     */
    public boolean uploadingTo(Member member) {
        return uploadingToSize(member) >= 0;
    }

    /**
     * @param member
     *            the receiver
     * @return the total size of bytes of the files currently upload to that
     *         member. Or -1 if not uploading to that member.
     */
    public long uploadingToSize(Member member) {
        long size = 0;
        boolean uploading = false;
        for (Upload upload : activeUploads) {
            if (member.equals(upload.getPartner())) {
                size += upload.getFile().getSize();
                uploading = true;
            }
        }
        if (!uploading) {
            return -1;
        }
        return size;
    }

    // Download management ***************************************************

    /**
     * Be sure to hold downloadsLock when calling this method!
     */
    private void removeDownload(final Download download) {
        final DownloadManager man = download.getDownloadManager();
        if (man == null) {
            return;
        }
        synchronized (man) {
            downloadsCount.remove(download.getPartner());
            if (man.hasSource(download)) {
                try {
                    man.removeSource(download);
                } catch (Exception e) {
                    logSevere("Unable to remove download: " + download, e);
                }
                if (!man.hasSources()) {
                    if (!man.isDone()) {
                        logFine("No further sources in that manager, removing it!");
                        man.setBroken("Out of sources for download");
                    } else {
                        logFine("No further sources in that manager, Not removing it because it's already done");
                    }
                }
            }
        }
    }

    /**
     * @return the download counter
     */
    public TransferCounter getDownloadCounter() {
        return downloadCounter;
    }

    /**
     * @return the download traffic counter (real)
     */
    public TransferCounter getTotalDownloadTrafficCounter() {
        return totalDownloadTrafficCounter;
    }

    /**
     * Addds a file for download if source is not known. Download will be
     * started when a source is found.
     * 
     * @param download
     * @return true if succeeded
     */
    public boolean enquePendingDownload(Download download) {
        Validate.notNull(download);

        FileInfo fInfo = download.getFile();
        if (download.isRequestedAutomatic()) {
            logFiner("Not adding pending download, is a auto-dl: " + fInfo);
            return false;
        }

        if (!getController().getFolderRepository().hasJoinedFolder(
            fInfo.getFolderInfo()))
        {
            logWarning("Not adding pending download, not on folder: " + fInfo);
            return false;
        }

        Folder folder = fInfo.getFolder(getController().getFolderRepository());
        FileInfo localFile = folder != null ? folder.getFile(fInfo) : null;
        if (localFile != null && fInfo.isVersionDateAndSizeIdentical(localFile))
        {
            logWarning("Not adding pending download, already have: " + fInfo);
            return false;
        }

        boolean contained = true;

        synchronized (pendingDownloads) {
            if (!pendingDownloads.contains(download)
                && !dlManagers.containsKey(download.getFile()))
            {
                contained = false;
                pendingDownloads.add(0, download);
            }
        }

        if (!contained) {
            logFine("Pending download added for: " + fInfo);
            firePendingDownloadEnqueud(new TransferManagerEvent(this, download));
        }
        return true;
    }

    public DownloadManagerFactory getDownloadManagerFactory() {
        return downloadManagerFactory;
    }

    public void setDownloadManagerFactory(
        DownloadManagerFactory downloadManagerFactory)
    {
        this.downloadManagerFactory = downloadManagerFactory;
    }

    /**
     * Requests to download the newest version of the file. Returns null if
     * download wasn't enqued at a node. Download is then pending
     * 
     * @param fInfo
     * @return the member where the dl was requested at, or null if no source
     *         available (DL was NOT started)
     */
    public DownloadManager downloadNewestVersion(FileInfo fInfo) {
        return downloadNewestVersion(fInfo, false);
    }

    /**
     * Requests to download the newest version of the file. Returns null if
     * download wasn't enqued at a node. Download is then pending or blacklisted
     * (for autodownload)
     * 
     * @param fInfo
     * @param automatic
     *            if this download was requested automatically
     * @return the member where the dl was requested at, or null if no source
     *         available (DL was NOT started) and null if blacklisted
     */
    public DownloadManager downloadNewestVersion(FileInfo fInfo,
        boolean automatic)
    {
        synchronized (downloadRequestLock) {
            Folder folder = fInfo.getFolder(getController()
                .getFolderRepository());
            if (folder == null) {
                // on shutdown folder maybe null here
                return null;
            }
            if (!started) {
                return null;
            }
            // FIXME Does not actually CHECK the base dir, but takes result of
            // last
            // scan. Possible problem on Mac/Linux: Unmounted path might exist.
            if (folder.isDeviceDisconnected()) {
                return null;
            }

            // return null if in blacklist on automatic download
            if (folder.getDiskItemFilter().isExcluded(fInfo)) {
                return null;
            }

            if (!fInfo.isValid()) {
                return null;
            }

            // Now walk through all sources and get the best one
            // Member bestSource = null;
            FileInfo newestVersionFile = fInfo.getNewestVersion(getController()
                .getFolderRepository());
            FileInfo localFile = folder.getFile(fInfo);
            FileInfo fileToDl = newestVersionFile != null
                ? newestVersionFile
                : fInfo;

            // Check if we have the file already downloaded in the meantime.
            // Or we have this file actual on disk but not in own db yet.
            if (localFile != null && !fileToDl.isNewerThan(localFile)) {
                if (isFiner()) {
                    logFiner("NOT requesting download, already has latest file in own db: "
                        + fInfo.toDetailString());
                }
                return null;
            } else if (fileToDl.inSyncWithDisk(fInfo
                .getDiskFile(getController().getFolderRepository())))
            {
                if (isFiner()) {
                    logFiner("NOT requesting download, file seems already to exists on disk: "
                        + fInfo.toDetailString());
                }
                // Disabled: Causes bottleneck on many transfers
                // DB seems to be out of sync. Recommend scan
                // Folder f = fInfo.getFolder(getController()
                // .getFolderRepository());
                // if (f != null) {
                // f.recommendScanOnNextMaintenance();
                // }
                return null;
            }

            List<Member> sources = getSourcesWithFreeUploadCapacity(fInfo);
            // ap<>
            // Map<Member, Integer> downloadCountList =
            // countNodesActiveAndQueuedDownloads();

            Collection<Member> bestSources = null;
            for (Member source : sources) {
                FileInfo remoteFile = source.getFile(fInfo);
                if (remoteFile == null) {
                    continue;
                }
                // Skip "wrong" sources
                if (!fileToDl.isVersionDateAndSizeIdentical(remoteFile)) {
                    continue;
                }
                if (bestSources == null) {
                    bestSources = new LinkedList<Member>();
                }
                bestSources.add(source);
            }

            if (bestSources != null) {
                for (Member bestSource : bestSources) {
                    Download download;
                    download = new Download(this, fileToDl, automatic);
                    if (isFiner()) {
                        logFiner("Best source for " + fInfo + " is "
                            + bestSource);
                    }
                    if (localFile != null
                        && localFile.getModifiedDate().after(
                            fileToDl.getModifiedDate())
                        && !localFile.isDeleted())
                    {
                        logWarning("Requesting older file requested: "
                            + fileToDl.toDetailString() + ", local: "
                            + localFile.toDetailString() + ", localIsNewer: "
                            + localFile.isNewerThan(fileToDl));
                    }
                    if (fileToDl.isNewerAvailable(getController()
                        .getFolderRepository()))
                    {
                        logSevere("Downloading old version while newer is available: "
                            + localFile);
                    }
                    requestDownload(download, bestSource);
                }
            }

            if (bestSources == null && !automatic) {
                // Okay enque as pending download if was manually requested
                enquePendingDownload(new Download(this, fileToDl, automatic));
                return null;
            }
            return getActiveDownload(fInfo);
        }
    }

    /**
     * Requests the download from that member
     * 
     * @param download
     * @param from
     */
    private void requestDownload(final Download download, final Member from) {
        final FileInfo fInfo = download.getFile();
        boolean dlWasRequested = false;
        // Lock/Disable transfer checker
        DownloadManager man;
        synchronized (dlManagers) {
            Download dl = getActiveDownload(from, fInfo);
            if (dl != null) {
                if (isFiner()) {
                    logFiner("Already downloading " + fInfo.toDetailString()
                        + " from " + from);
                }
                return;
            }
            if (fInfo.isVersionDateAndSizeIdentical(fInfo.getFolder(
                getController().getFolderRepository()).getFile(fInfo)))
            {
                // Skip exact same version etc.
                if (isFiner()) {
                    logFiner("Not requesting download, already have latest file version: "
                        + fInfo.toDetailString());
                }
                return;
            }

            man = dlManagers.get(fInfo);

            if (man == null
                || !fInfo.isVersionDateAndSizeIdentical(man.getFileInfo()))
            {
                if (man != null) {
                    if (!man.isDone()) {
                        logWarning("Got active download of different file version, aborting.");
                        man.abortAndCleanup();
                    }
                    return;
                }

                try {
                    man = downloadManagerFactory
                        .createDownloadManager(getController(), fInfo, download
                            .isRequestedAutomatic());
                    man.init(false);
                } catch (IOException e) {
                    // Something gone badly wrong
                    logSevere("IOException", e);
                    return;
                }
                if (dlManagers.put(fInfo, man) != null) {
                    throw new AssertionError("Found old manager!");
                }
            }
        }

        if (abortUploadsOf(fInfo)) {
            logFine("Aborted uploads of file to be downloaded: "
                + fInfo.toDetailString());
        }

        synchronized (man) {
            if (fInfo.isVersionDateAndSizeIdentical(fInfo.getFolder(
                getController().getFolderRepository()).getFile(fInfo)))
            {
                // Skip exact same version etc.
                logWarning("Aborting download, already have latest file version: "
                    + fInfo.toDetailString());
                man.abort();
                return;
            }
            if (man.getSourceFor(from) == null && !man.isDone()
                && man.canAddSource(from))
            {
                if (isFine()) {
                    logFine("Requesting " + fInfo.toDetailString() + " from "
                        + from);
                }

                if (fInfo.isNewerThan(man.getFileInfo())) {
                    logSevere("Requested newer download: " + download
                        + " than " + man);
                }
                pendingDownloads.remove(download);
                downloadsCount.remove(from);
                download.setPartner(from);
                download.setDownloadManager(man);
                dlWasRequested = man.addSource(download);
            }
        }

        if (dlWasRequested) {
            if (isFiner()) {
                logFiner("File really was requested!");
            }
            // Fire event
            fireDownloadRequested(new TransferManagerEvent(this, download));
        }
    }

    // TODO Does all this "sources" management really belong to the
    // TransferManager?

    /**
     * Finds the sources for the file. Returns only sources which are connected
     * The members are sorted in order of best source.
     * <p>
     * WARNING: Versions of files are ingnored
     * 
     * @param fInfo
     * @return the list of members, where the file is available
     */
    public List<Member> getSourcesFor(FileInfo fInfo) {
        return getSourcesFor0(fInfo, false);
    }

    /**
     * Returns only sources which are connected and have "exactly" the given
     * FileInfo version.
     * 
     * @param fInfo
     * @return
     */
    public Collection<Member> getSourcesForVersion(FileInfo fInfo) {
        Reject.notNull(fInfo, "fInfo");

        Folder folder = fInfo.getFolder(getController().getFolderRepository());
        if (folder == null) {
            throw new NullPointerException("Folder not joined of file: "
                + fInfo);
        }
        List<Member> sources = null;
        for (Member node : folder.getMembersAsCollection()) {
            FileInfo rInfo = node.getFile(fInfo);
            if (node.isCompletelyConnected() && !node.isMySelf()
                && fInfo.isVersionDateAndSizeIdentical(rInfo))
            {
                // node is connected and has file
                if (sources == null) {
                    sources = new ArrayList<Member>();
                }
                sources.add(node);
            }
        }
        if (sources == null) {
            sources = Collections.emptyList();
        }
        return sources;
    }

    private List<Member> getSourcesWithFreeUploadCapacity(FileInfo fInfo) {
        return getSourcesFor0(fInfo, true);
    }

    /**
     * Finds the sources for the file. Returns only sources which are connected
     * The members are sorted in order of best source.
     * <p>
     * WARNING: Versions of files are ignored
     * 
     * @param fInfo
     * @param withUploadCapacityOnly
     *            if only those sources should be considered, that have free
     *            upload capacity.
     * @return the list of members, where the file is available
     */
    private List<Member> getSourcesFor0(FileInfo fInfo,
        boolean withUploadCapacityOnly)
    {
        Reject.ifNull(fInfo, "File is null");
        Folder folder = fInfo.getFolder(getController().getFolderRepository());
        if (folder == null) {
            throw new NullPointerException("Folder not joined of file: "
                + fInfo);
        }

        // List<Member> nodes = getController().getNodeManager()
        // .getNodeWithFileListFrom(fInfo.getFolderInfo());
        List<Member> sources = null;
        // List<Member> sources = new ArrayList<Member>(nodes.size());
        for (Member node : folder.getMembersAsCollection()) {
            if (node.isCompletelyConnected() && !node.isMySelf()
                && node.hasFile(fInfo))
            {
                if (withUploadCapacityOnly && !hasUploadCapacity(node)) {
                    continue;
                }

                // node is connected and has file
                if (sources == null) {
                    sources = new ArrayList<Member>();
                }
                sources.add(node);
            }
        }
        if (sources == null) {
            return Collections.emptyList();
        }
        // Sort by the best upload availibility
        Collections.shuffle(sources);
        Collections.sort(sources, new ReverseComparator(
            MemberComparator.BY_UPLOAD_AVAILIBILITY));

        return sources;
    }

    /**
     * Aborts all automatically enqueued download of a folder.
     * 
     * @param folder
     *            the folder to break downloads on
     */
    public void abortAllAutodownloads(final Folder folder) {
        int aborted = 0;
        for (DownloadManager dl : getActiveDownloads()) {
            boolean fromFolder = folder.getInfo().equals(
                dl.getFileInfo().getFolderInfo());
            if (fromFolder && dl.isRequestedAutomatic()) {
                // Abort
                dl.abort();
                aborted++;
            }
        }
        logFine("Aborted " + aborted + " downloads on " + folder);
    }

    void downloadManagerAborted(DownloadManager manager) {
        logWarning("Aborted download: " + manager);
        removeDownloadManager(manager);
    }

    void downloadManagerBroken(DownloadManager manager,
        TransferProblem problem, String message)
    {
        removeDownloadManager(manager);
        if (!manager.isRequestedAutomatic()) {
            enquePendingDownload(new Download(this, manager.getFileInfo(),
                manager.isRequestedAutomatic()));
        }
    }

    /**
     * @param manager
     */
    private void removeDownloadManager(DownloadManager manager) {
        assert manager.isDone() : "Manager to remove is NOT done!";
        dlManagers.remove(manager.getFileInfo(), manager);
    }

    /**
     * Aborts an download. Gets removed compeletly.
     */
    void downloadAborted(Download download) {
        pendingDownloads.remove(download);
        removeDownload(download);
        // Fire event
        fireDownloadAborted(new TransferManagerEvent(this, download));
    }

    /**
     * abort a download, only if the downloading partner is the same
     * 
     * @param fileInfo
     * @param from
     */
    public void abortDownload(FileInfo fileInfo, Member from) {
        Reject.ifNull(fileInfo, "FileInfo is null");
        Reject.ifNull(from, "From is null");
        Download download = getActiveDownload(from, fileInfo);
        if (download != null) {
            assert download.getPartner().equals(from);
            if (isFiner()) {
                logFiner("downloading changed file, aborting it! "
                    + fileInfo.toDetailString() + " " + from);
            }
            download.abort(false);
        } else {
            for (Download pendingDL : pendingDownloads) {
                if (pendingDL.getFile().equals(fileInfo)
                    && pendingDL.getPartner() != null
                    && pendingDL.getPartner().equals(from))
                {
                    if (isFiner()) {
                        logFiner("Aborting pending download! "
                            + fileInfo.toDetailString() + " " + from);
                    }
                    pendingDL.abort(false);
                }
            }
        }
    }

    /**
     * Clears a completed downloads
     */
    public void clearCompletedDownload(DownloadManager dlMan) {
        if (completedDownloads.remove(dlMan)) {
            for (Download download : dlMan.getSources()) {
                fireCompletedDownloadRemoved(new TransferManagerEvent(this,
                    download));
            }
        }
    }

    /**
     * Clears a completed uploads
     */
    public void clearCompletedUpload(Upload upload) {
        if (completedUploads.remove(upload)) {
            fireCompletedUploadRemoved(new TransferManagerEvent(this, upload));
        }
    }

    /**
     * Invoked by Download, if a new chunk was received
     * 
     * @param d
     * @param chunk
     */
    public void chunkAdded(Download d, FileChunk chunk) {
        Reject.noNullElements(d, chunk);
        downloadCounter.chunkTransferred(chunk);
    }

    /**
     * @param fInfo
     * @return true if that file gets downloaded
     */
    public boolean isDownloadingActive(FileInfo fInfo) {
        return dlManagers.containsKey(fInfo);
    }

    /**
     * @param fInfo
     * @return true if the file is enqued as pending download
     */
    public boolean isDownloadingPending(FileInfo fInfo) {
        Reject.ifNull(fInfo, "File is null");
        for (Download d : pendingDownloads) {
            if (d.getFile().equals(fInfo)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param fInfo
     * @return true if that file is uploading, or queued
     */
    public boolean isUploading(FileInfo fInfo) {
        for (Upload upload : activeUploads) {
            if (upload.getFile() == fInfo) {
                return true;
            }
        }
        for (Upload upload : queuedUploads) {
            if (upload.getFile() == fInfo) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the upload for the given file to the given member
     * 
     * @param to
     *            the member which the upload transfers to
     * @param fInfo
     *            the file which is transfered
     * @return the Upload or null if there is none
     */
    public Upload getUpload(Member to, FileInfo fInfo) {
        for (Upload u : activeUploads) {
            if (u.getFile().isVersionDateAndSizeIdentical(fInfo)
                && u.getPartner().equals(to))
            {
                return u;
            }
        }
        for (Upload u : queuedUploads) {
            if (u.getFile().isVersionDateAndSizeIdentical(fInfo)
                && u.getPartner().equals(to))
            {
                return u;
            }
        }
        return null;
    }

    /**
     * @param from
     * @param fInfo
     * @return The download of the file that has been completed from this
     *         member.
     */
    public Download getCompletedDownload(Member from, FileInfo fInfo) {
        DownloadManager man = getCompletedDownload(fInfo);
        if (man == null) {
            return null;
        }
        Download d = man.getSourceFor(from);
        if (d == null) {
            return null;
        }
        assert d.getPartner().equals(from);
        return d;
    }

    /**
     * @param fInfo
     * @return the download manager containing the completed file, otherwise
     *         null
     */
    public DownloadManager getCompletedDownload(FileInfo fInfo) {
        for (DownloadManager dlManager : completedDownloads) {
            if (dlManager.getFileInfo().isVersionDateAndSizeIdentical(fInfo)) {
                return dlManager;
            }
        }
        return null;
    }

    public Download getActiveDownload(Member from, FileInfo fInfo) {
        DownloadManager man = getDownloadManagerFor(fInfo);
        if (man == null) {
            return null;
        }
        Download d = man.getSourceFor(from);
        if (d == null) {
            return null;
        }
        assert d.getPartner().equals(from);
        return d;
    }

    /**
     * @param fInfo
     * @return the active download for a file
     */
    public DownloadManager getActiveDownload(FileInfo fInfo) {
        return getDownloadManagerFor(fInfo);
    }

    /**
     * @param fileInfo
     * @return the pending download for the file
     */
    public Download getPendingDownload(FileInfo fileInfo) {
        for (Download pendingDl : pendingDownloads) {
            if (fileInfo.equals(pendingDl.getFile())) {
                return pendingDl;
            }
        }
        return null;
    }

    /**
     * @param folder
     *            the folder
     * @return the number of downloads (active & queued) from on a folder.
     */
    public int countNumberOfDownloads(Folder folder) {
        Reject.ifNull(folder, "Folder is null");
        int n = 0;

        for (DownloadManager man : dlManagers.values()) {
            for (Download download : man.getSources()) {
                if (download.getFile().getFolderInfo().equals(folder.getInfo()))
                {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * @param from
     * @return the number of downloads (active & queued) from a member
     */
    public int getNumberOfDownloadsFrom(Member from) {
        if (from == null) {
            throw new NullPointerException("From is null");
        }
        int n = 0;
        for (DownloadManager man : dlManagers.values()) {
            if (man.getSourceFor(from) != null) {
                n++;
            }
        }
        return n;
    }

    /**
     * @return the internal unodifiable collection of pending downloads.
     */
    public Collection<Download> getPendingDownloads() {
        return Collections.unmodifiableCollection(pendingDownloads);
    }

    /**
     * @return unodifiable instance to the internal active downloads collection.
     */
    public Collection<DownloadManager> getActiveDownloads() {
        return Collections.unmodifiableCollection(dlManagers.values());
    }

    /**
     * @return the number of completed downloads
     */
    public int countCompletedDownloads() {
        return completedDownloads.size();
    }

    public int countCompletedDownloads(Folder folder) {
        int count = 0;
        for (DownloadManager completedDownload : completedDownloads) {
            Folder f = completedDownload.getFileInfo().getFolder(
                getController().getFolderRepository());
            if (f != null && f.getInfo().equals(folder.getInfo())) {
                count++;
            }
        }
        return count;
    }

    /**
     * Counts the number of active downloads for a folder.
     * 
     * @param folder
     * @return
     */
    public int countActiveDownloads(Folder folder) {
        int count = 0;
        for (DownloadManager activeDownload : dlManagers.values()) {
            Folder f = activeDownload.getFileInfo().getFolder(
                getController().getFolderRepository());
            if (f != null && f.getInfo().equals(folder.getInfo())) {
                count++;
            }
        }
        return count;
    }

    /**
     * @return an unmodifiable collection reffering to the internal completed
     *         downloads list. May change after returned.
     */
    public List<DownloadManager> getCompletedDownloadsCollection() {
        return Collections.unmodifiableList(completedDownloads);
    }

    /**
     * @param fInfo
     * @return true if the file was recently downloaded (in the list of
     *         completed downloads);
     */
    public boolean isCompletedDownload(FileInfo fInfo) {
        for (DownloadManager dm : completedDownloads) {
            if (dm.getFileInfo().isVersionDateAndSizeIdentical(fInfo)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the number of all downloads
     */
    public int countActiveDownloads() {
        return dlManagers.size();
    }

    /**
     * @return the number of total downloads (queued, active, pending and
     *         completed)
     */
    public int countTotalDownloads() {
        return countActiveDownloads() + pendingDownloads.size()
            + completedDownloads.size();
    }

    /**
     * @param node
     * @return true if the remote node has free upload capacity.
     */
    public boolean hasUploadCapacity(Member node) {
        int nDownloadFrom = countActiveAndQueuedDownloads(node);
        int maxAllowedDls = node.isOnLAN()
            ? Constants.MAX_DLS_FROM_LAN_MEMBER
            : Constants.MAX_DLS_FROM_INET_MEMBER;
        return nDownloadFrom < maxAllowedDls;
    }

    /**
     * Counts the number of downloads from this node.
     * 
     * @param node
     *            the node to check
     * @return Number of active or enqued downloads to that node
     */
    private int countActiveAndQueuedDownloads(Member node) {
        Integer cached = downloadsCount.get(node);
        if (cached != null) {
            // cacheHit++;
            // if (cacheHit % 1000 == 0) {
            // logWarning(
            // "countActiveAndQueuedDownloads cache hit count: "
            // + cacheHit);
            // }
            return cached;
        }
        int nDownloadsFrom = 0;
        for (DownloadManager man : dlManagers.values()) {
            for (Download download : man.getSources()) {
                if (download.getPartner().equals(node)
                    && !download.isCompleted() && !download.isBroken())
                {
                    nDownloadsFrom++;
                }
            }
        }
        downloadsCount.put(node, nDownloadsFrom);
        return nDownloadsFrom;
    }

    /**
     * Answers if we have active downloads from that member (not used)
     * 
     * @param from
     * @return
     */
    /*
     * private boolean hasActiveDownloadsFrom(Member from) { synchronized
     * (downloads) { for (Iterator it = downloads.values().iterator();
     * it.hasNext();) { Download download = (Download) it.next(); if
     * (download.isStarted() && download.getFrom().equals(from)) { return true;
     * } } } return false; }
     */

    /**
     * Loads all pending downloads and enqueus them for re-download
     */
    private void loadDownloads() {
        File transferFile = new File(Controller.getMiscFilesLocation(),
            getController().getConfigName() + ".transfers");
        if (!transferFile.exists()) {
            logFine("No downloads to restore, "
                + transferFile.getAbsolutePath() + " does not exists");
            return;
        }
        try {
            FileInputStream fIn = new FileInputStream(transferFile);
            ObjectInputStream oIn = new ObjectInputStream(fIn);
            List<?> storedDownloads = (List<?>) oIn.readObject();
            oIn.close();
            // Reverse to restore in right order
            Collections.reverse(storedDownloads);

            if (storedDownloads.size() > 10000) {
                logWarning("Got many completed downloads ("
                    + storedDownloads.size() + "). Cleanup is recommended");
            }
            // #1705: Speed up of start
            Map<FileInfo, List<DownloadManager>> tempMap = new HashMap<FileInfo, List<DownloadManager>>();
            for (Iterator<?> it = storedDownloads.iterator(); it.hasNext();) {
                Download download = (Download) it.next();

                // Initalize after deserialisation
                download.init(TransferManager.this);
                if (download.isCompleted()) {
                    DownloadManager man = null;
                    List<DownloadManager> dlms = tempMap
                        .get(download.getFile());
                    if (dlms == null) {
                        dlms = new ArrayList<DownloadManager>();
                        tempMap.put(download.getFile(), dlms);
                    }

                    for (DownloadManager dlm : dlms) {
                        if (dlm.getFileInfo().isVersionDateAndSizeIdentical(
                            download.getFile()))
                        {
                            man = dlm;
                            break;
                        }
                    }

                    if (man == null) {
                        man = downloadManagerFactory.createDownloadManager(
                            getController(), download.getFile(), download
                                .isRequestedAutomatic());
                        man.init(true);
                        completedDownloads.add(man);
                        // For faster loading
                        dlms.add(man);
                    }
                    // FIXME The UI shouldn't access Downloads directly anyway,
                    // there should be a representation suitable for that.
                    if (download.getPartner() != null) {
                        download.setDownloadManager(man);
                        if (man.canAddSource(download.getPartner())) {
                            man.addSource(download);
                        }
                    }
                } else if (download.isPending()) {
                    enquePendingDownload(download);
                }
            }

            logFine("Loaded " + storedDownloads.size() + " downloads");
        } catch (IOException e) {
            logSevere("Unable to load pending downloads", e);
            if (!transferFile.delete()) {
                logSevere("Unable to delete transfer file!");
            }
        } catch (ClassNotFoundException e) {
            logSevere("Unable to load pending downloads", e);
            if (!transferFile.delete()) {
                logSevere("Unable to delete pending downloads file!");
            }
        } catch (ClassCastException e) {
            logSevere("Unable to load pending downloads", e);
            if (!transferFile.delete()) {
                logSevere("Unable to delete pending downloads file!");
            }
        }
    }

    /**
     * Stores all pending downloads to disk
     */
    private void storeDownloads() {
        // Store pending downloads
        try {
            // Collect all download infos
            List<Download> storedDownloads = new ArrayList<Download>(
                pendingDownloads);
            int nPending = countActiveDownloads();
            int nCompleted = completedDownloads.size();
            for (DownloadManager man : dlManagers.values()) {
                storedDownloads.add(new Download(this, man.getFileInfo(), man
                    .isRequestedAutomatic()));
            }

            for (DownloadManager man : completedDownloads) {
                storedDownloads.addAll(man.getSources());
            }

            logFiner("Storing " + storedDownloads.size() + " downloads ("
                + nPending + " pending, " + nCompleted + " completed)");
            File transferFile = new File(Controller.getMiscFilesLocation(),
                getController().getConfigName() + ".transfers");
            // for testing we should support getConfigName() with subdirs
            if (!transferFile.getParentFile().exists()
                && !new File(transferFile.getParent()).mkdirs())
            {
                logSevere("Failed to mkdir misc directory!");
            }
            OutputStream fOut = new BufferedOutputStream(new FileOutputStream(
                transferFile));
            ObjectOutputStream oOut = new ObjectOutputStream(fOut);
            oOut.writeObject(storedDownloads);
            oOut.close();
        } catch (IOException e) {
            logSevere("Unable to store pending downloads", e);
        }
    }

    /**
     * Removes completed download for a fileInfo.
     * 
     * @param fileInfo
     */
    public void clearCompletedDownload(FileInfo fileInfo) {
        for (DownloadManager completedDownload : completedDownloads) {
            if (completedDownload.getFileInfo().equals(fileInfo)) {
                if (completedDownloads.remove(completedDownload)) {
                    for (Download download : completedDownload.getSources()) {
                        fireCompletedDownloadRemoved(new TransferManagerEvent(
                            this, download));
                    }
                }
            }
        }
    }

    // Worker code ************************************************************

    /**
     * The core maintenance thread of transfermanager.
     */
    private class TransferChecker implements Runnable {
        public void run() {
            long waitTime = getController().getWaitTime() * 2;
            int count = 0;

            while (!Thread.currentThread().isInterrupted()) {
                // Check uploads/downloads every 10 seconds
                if (isFiner()) {
                    logFiner("Checking uploads/downloads");
                }

                // Check queued uploads
                checkQueuedUploads();

                // Check pending downloads
                checkPendingDownloads();

                // Checking downloads
                checkDownloads();

                // log upload / donwloads
                if (count % 2 == 0) {
                    logFine("Transfers: "
                        + countActiveDownloads()
                        + " download(s), "
                        + Format.formatDecimal(getDownloadCounter()
                            .calculateCurrentKBS())
                        + " KByte/s, "
                        + activeUploads.size()
                        + " active upload(s), "
                        + queuedUploads.size()
                        + " in queue, "
                        + Format.formatDecimal(getUploadCounter()
                            .calculateCurrentKBS()) + " KByte/s");
                }

                count++;

                // wait a bit to next work
                try {
                    // Wait another 10ms to avoid spamming via trigger
                    Thread.sleep(10);

                    synchronized (waitTrigger) {
                        if (!transferCheckTriggered) {
                            waitTrigger.wait(waitTime);
                        }
                        transferCheckTriggered = false;
                    }

                } catch (InterruptedException e) {
                    // Break
                    break;
                }
            }
        }
    }

    /**
     * Checks the queued or active downloads and breaks them if nesseary. Also
     * searches for additional sources of download.
     */
    private void checkDownloads() {
        if (isFiner()) {
            logFiner("Checking " + countActiveDownloads() + " download(s)");
        }

        for (DownloadManager man : dlManagers.values()) {
            try {
                if (!man.isDone()) {
                    downloadNewestVersion(man.getFileInfo(), man
                        .isRequestedAutomatic());
                }
                downloadNewestVersion(man.getFileInfo(), man
                    .isRequestedAutomatic());
                for (Download download : man.getSources()) {
                    if (!download.isCompleted() && download.isBroken()) {
                        download.setBroken(TransferProblem.BROKEN_DOWNLOAD,
                            "isBroken()");
                    }
                }
            } catch (Exception e) {
                logSevere("Exception while cheking downloads. " + e, e);
            }
        }
    }

    /**
     * Checks the queued uploads and start / breaks them if nessesary.
     */
    private void checkQueuedUploads() {
        int uploadsStarted = 0;
        int uploadsBroken = 0;

        if (isFiner()) {
            logFiner("Checking " + queuedUploads.size() + " queued uploads");
        }

        for (Upload upload : queuedUploads) {
            try {
                if (upload.isBroken()) {
                    // Broken
                    uploadBroken(upload, TransferProblem.BROKEN_UPLOAD);
                    uploadsBroken++;
                } else if (hasFreeUploadSlots()
                    || upload.getPartner().isOnLAN())
                {
                    boolean alreadyUploadingTo;
                    // The total size planned+current uploading to that node.
                    long totalPlannedSizeUploadingTo = uploadingToSize(upload
                        .getPartner());
                    if (totalPlannedSizeUploadingTo == -1) {
                        alreadyUploadingTo = false;
                        totalPlannedSizeUploadingTo = 0;
                    } else {
                        alreadyUploadingTo = true;
                    }
                    totalPlannedSizeUploadingTo += upload.getFile().getSize();
                    long maxSizeUpload = upload.getPartner().isOnLAN()
                        ? Constants.START_UPLOADS_TILL_PLANNED_SIZE_LAN
                        : Constants.START_UPLOADS_TILL_PLANNED_SIZE_INET;
                    if (!alreadyUploadingTo
                        || totalPlannedSizeUploadingTo <= maxSizeUpload)
                    {
                        // if (!alreadyUploadingTo) {
                        if (alreadyUploadingTo && isFiner()) {
                            logFiner("Starting another upload to "
                                + upload.getPartner().getNick()
                                + ". Total size to upload to: "
                                + Format
                                    .formatBytesShort(totalPlannedSizeUploadingTo));

                        }
                        // start the upload if we have free slots
                        // and not uploading to member currently
                        // or user is on local network

                        // TODO should check if this file is not sended (or is
                        // being send) to other user in the last minute or so to
                        // allow for disitributtion of that file by user that
                        // just received that file from us

                        // Enqueue upload to friends and lan members first

                        logFiner("Starting upload: " + upload);
                        upload.start();
                        uploadsStarted++;
                    }
                }
            } catch (Exception e) {
                logSevere("Exception while cheking queued uploads. " + e, e);
            }
        }

        if (isFiner()) {
            logFiner("Started " + uploadsStarted + " upload(s), "
                + uploadsBroken + " broken upload(s)");
        }
    }

    /**
     * Checks the pendings download. Restores them if a source is found
     */
    private void checkPendingDownloads() {
        if (isFiner()) {
            logFiner("Checking " + pendingDownloads.size()
                + " pending downloads");
        }

        // Checking pending downloads
        for (Download dl : pendingDownloads) {
            try {
                FileInfo fInfo = dl.getFile();
                boolean notDownloading = getDownloadManagerFor(fInfo) == null;
                if (notDownloading
                    && getController().getFolderRepository().hasJoinedFolder(
                        fInfo.getFolderInfo()))
                {
                    // MultiSourceDownload source = downloadNewestVersion(fInfo,
                    // download
                    // .isRequestedAutomatic());
                    DownloadManager source = downloadNewestVersion(fInfo, dl
                        .isRequestedAutomatic());
                    if (source != null) {
                        logFine("Pending download restored: " + fInfo
                            + " from " + source);
                        synchronized (pendingDownloads) {
                            pendingDownloads.remove(dl);
                        }
                    }
                } else if (dl.getDownloadManager() != null
                    && !dl.getDownloadManager().isDone())
                {
                    // Not joined folder, break pending dl
                    logWarning("Pending download removed: " + fInfo);
                    synchronized (pendingDownloads) {
                        pendingDownloads.remove(dl);
                    }
                }
            } catch (Exception e) {
                logSevere("Exception while cheking pending downloads. " + e, e);
            }
        }
    }

    // Helper code ************************************************************

    /**
     * logs a up- or download with speed and time
     * 
     * @param download
     * @param took
     * @param fInfo
     * @param member
     */
    public void logTransfer(boolean download, long took, FileInfo fInfo,
        Member member)
    {

        String memberInfo = "";
        if (member != null) {
            memberInfo = ((download) ? " from " : " to ") + '\''
                + member.getNick() + '\'';
        }

        String cpsStr = "-";

        // printout rate only if dl last longer than 0,5 s
        if (took > 1000) {
            double cps = fInfo.getSize();
            cps /= 1024;
            cps /= took;
            cps *= 1000;
            synchronized (CPS_FORMAT) {
                cpsStr = CPS_FORMAT.format(cps);
            }
        }

        logInfo((download ? "Download" : "Upload") + " completed: "
            + Format.formatDecimal(fInfo.getSize()) + " bytes in "
            + (took / 1000) + "s (" + cpsStr + " KByte/s): " + fInfo
            + memberInfo);
    }

    // Event/Listening code ***************************************************

    public void addListener(TransferManagerListener listener) {
        ListenerSupportFactory.addListener(listenerSupport, listener);
    }

    public void removeListener(TransferManagerListener listener) {
        ListenerSupportFactory.removeListener(listenerSupport, listener);
    }

    private void fireUploadStarted(TransferManagerEvent event) {
        listenerSupport.uploadStarted(event);
    }

    private void fireUploadAborted(TransferManagerEvent event) {
        listenerSupport.uploadAborted(event);
    }

    private void fireUploadBroken(TransferManagerEvent event) {
        listenerSupport.uploadBroken(event);
    }

    private void fireUploadCompleted(TransferManagerEvent event) {
        listenerSupport.uploadCompleted(event);
    }

    private void fireUploadRequested(TransferManagerEvent event) {
        listenerSupport.uploadRequested(event);
    }

    private void fireDownloadAborted(TransferManagerEvent event) {
        listenerSupport.downloadAborted(event);
    }

    private void fireDownloadBroken(TransferManagerEvent event) {
        listenerSupport.downloadBroken(event);
    }

    private void fireDownloadCompleted(TransferManagerEvent event) {
        listenerSupport.downloadCompleted(event);
    }

    private void fireDownloadQueued(TransferManagerEvent event) {
        listenerSupport.downloadQueued(event);
    }

    private void fireDownloadRequested(TransferManagerEvent event) {
        listenerSupport.downloadRequested(event);
    }

    private void fireDownloadStarted(TransferManagerEvent event) {
        listenerSupport.downloadStarted(event);
    }

    private void fireCompletedDownloadRemoved(TransferManagerEvent event) {
        listenerSupport.completedDownloadRemoved(event);
    }

    private void fireCompletedUploadRemoved(TransferManagerEvent event) {
        listenerSupport.completedUploadRemoved(event);
    }

    private void firePendingDownloadEnqueud(TransferManagerEvent event) {
        listenerSupport.pendingDownloadEnqueud(event);
    }

    /**
     * This class regularly checks to see if any downloads or uploads are
     * active, and updates folder statistics with partial down/upload byte
     * count.
     */
    private class PartialTransferStatsUpdater extends TimerTask {
        @Override
        public void run() {
            FolderRepository folderRepository = getController()
                .getFolderRepository();
            for (FileInfo fileInfo : dlManagers.keySet()) {
                DownloadManager downloadManager = dlManagers.get(fileInfo);
                if (downloadManager != null) {
                    Folder folder = folderRepository.getFolder(fileInfo
                        .getFolderInfo());
                    folder.getStatistic().putPartialSyncStat(fileInfo,
                        getController().getMySelf(),
                        downloadManager.getCounter().getBytesTransferred());
                }
            }
            for (Upload upload : activeUploads) {
                Folder folder = upload.getFile().getFolder(folderRepository);
                folder.getStatistic().putPartialSyncStat(upload.getFile(),
                    upload.getPartner(),
                    upload.getCounter().getBytesTransferred());
            }
        }
    }

    private class TransferCleaner extends TimerTask {
        @Override
        public void run() {
            cleanupOldTransfers();
        }
    }
}
