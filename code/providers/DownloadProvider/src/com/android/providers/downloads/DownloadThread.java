/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.downloads;

import static android.provider.Downloads.Impl.STATUS_BAD_REQUEST;
import static android.provider.Downloads.Impl.STATUS_CANCELED;
import static android.provider.Downloads.Impl.STATUS_CANNOT_RESUME;
import static android.provider.Downloads.Impl.STATUS_FILE_ERROR;
import static android.provider.Downloads.Impl.STATUS_HTTP_DATA_ERROR;
import static android.provider.Downloads.Impl.STATUS_SUCCESS;
import static android.provider.Downloads.Impl.STATUS_TOO_MANY_REDIRECTS;
import static android.provider.Downloads.Impl.STATUS_UNHANDLED_HTTP_CODE;
import static android.provider.Downloads.Impl.STATUS_UNKNOWN_ERROR;
import static android.provider.Downloads.Impl.STATUS_WAITING_FOR_NETWORK;
import static android.provider.Downloads.Impl.STATUS_WAITING_TO_RETRY;
import static android.text.format.DateUtils.SECOND_IN_MILLIS;
import static com.android.providers.downloads.Constants.TAG;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_PARTIAL;
import static java.net.HttpURLConnection.HTTP_PRECON_FAILED;
import static java.net.HttpURLConnection.HTTP_SEE_OTHER;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.drm.DrmManagerClient;
import android.drm.DrmOutputStream;
import android.net.ConnectivityManager;
import android.net.INetworkPolicyListener;
import android.net.NetworkInfo;
import android.net.NetworkPolicyManager;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.os.WorkSource;
import android.provider.Downloads;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.util.Pair;

import com.android.providers.downloads.DownloadInfo.NetworkState;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.net.URLConnection;

import com.android.providers.downloadsplugin.DownloadsDRMUtils;
/**
 * Task which executes a given {@link DownloadInfo}: making network requests,
 * persisting data to disk, and updating {@link DownloadProvider}.
 * <p>
 * To know if a download is successful, we need to know either the final content
 * length to expect, or the transfer to be chunked. To resume an interrupted
 * download, we need an ETag.
 * <p>
 * Failed network requests are retried several times before giving up. Local
 * disk errors fail immediately and are not retried.
 */
public class DownloadThread implements Runnable {

    // TODO: bind each download to a specific network interface to avoid state
    // checking races once we have ConnectivityManager API

    // TODO: add support for saving to content://

    private static final int HTTP_REQUESTED_RANGE_NOT_SATISFIABLE = 416;
    private static final int HTTP_TEMP_REDIRECT = 307;

    private static final int DEFAULT_TIMEOUT = (int) (20 * SECOND_IN_MILLIS);

    /**
     *Add for download pause&resume
     *@{
     */
    private static final int TAG_PAUSED_BY_OWNER = 3;
    /*@}*/
    private final Context mContext;
    private final SystemFacade mSystemFacade;
    private final DownloadNotifier mNotifier;

    private final long mId;

    /**
     * Info object that should be treated as read-only. Any potentially mutated
     * fields are tracked in {@link #mInfoDelta}. If a field exists in
     * {@link #mInfoDelta}, it must not be read from {@link #mInfo}.
     */
    /*
     * for downloadprovider_DRM
     * original code
    private final DownloadInfo mInfo;
    private final DownloadInfoDelta mInfoDelta;
     *@{
     */
    public final DownloadInfo mInfo;
    public final DownloadInfoDelta mInfoDelta;
    /*@}*/
    private volatile boolean mPolicyDirty;

    /**
     * Local changes to {@link DownloadInfo}. These are kept local to avoid
     * racing with the thread that updates based on change notifications.
     */
    /*
     * for downloadprovider_DRM
     * original code
    private class DownloadInfoDelta {
     *@{
     */
    public class DownloadInfoDelta {
    /*@}*/
        public String mUri;
        public String mFileName;
        public String mMimeType;
        public int mStatus;
        public int mNumFailed;
        public int mRetryAfter;
        public long mTotalBytes;
        public long mCurrentBytes;
        public String mETag;

        public String mErrorMsg;

        public DownloadInfoDelta(DownloadInfo info) {
            mUri = info.mUri;
            mFileName = info.mFileName;
            mMimeType = info.mMimeType;
            mStatus = info.mStatus;
            mNumFailed = info.mNumFailed;
            mRetryAfter = info.mRetryAfter;
            mTotalBytes = info.mTotalBytes;
            mCurrentBytes = info.mCurrentBytes;
            mETag = info.mETag;
        }

        private ContentValues buildContentValues() {
            final ContentValues values = new ContentValues();

            values.put(Downloads.Impl.COLUMN_URI, mUri);
            values.put(Downloads.Impl._DATA, mFileName);
            values.put(Downloads.Impl.COLUMN_MIME_TYPE, mMimeType);
            values.put(Downloads.Impl.COLUMN_STATUS, mStatus);
            values.put(Downloads.Impl.COLUMN_FAILED_CONNECTIONS, mNumFailed);
            values.put(Constants.RETRY_AFTER_X_REDIRECT_COUNT, mRetryAfter);
            values.put(Downloads.Impl.COLUMN_TOTAL_BYTES, mTotalBytes);
            values.put(Downloads.Impl.COLUMN_CURRENT_BYTES, mCurrentBytes);
            values.put(Constants.ETAG, mETag);

            values.put(Downloads.Impl.COLUMN_LAST_MODIFICATION, mSystemFacade.currentTimeMillis());
            values.put(Downloads.Impl.COLUMN_ERROR_MSG, mErrorMsg);

            return values;
        }

        /**
         * Blindly push update of current delta values to provider.
         */
        public void writeToDatabase() {
            mContext.getContentResolver().update(mInfo.getAllDownloadsUri(), buildContentValues(),
                    null, null);
        }

        /**
         * Push update of current delta values to provider, asserting strongly
         * that we haven't been paused or deleted.
         */
        public void writeToDatabaseOrThrow() throws StopRequestException {
            if (mContext.getContentResolver().update(mInfo.getAllDownloadsUri(),
                    buildContentValues(), Downloads.Impl.COLUMN_DELETED + " == '0'", null) == 0) {
                throw new StopRequestException(STATUS_CANCELED, "Download deleted or missing!");
            }
        }
    }

    /**
     * Flag indicating if we've made forward progress transferring file data
     * from a remote server.
     */
    private boolean mMadeProgress = false;

    /**
     * Details from the last time we pushed a database update.
     */
    private long mLastUpdateBytes = 0;
    private long mLastUpdateTime = 0;

    private int mNetworkType = ConnectivityManager.TYPE_NONE;

    /** Historical bytes/second speed of this download. */
    private long mSpeed;
    /** Time when current sample started. */
    private long mSpeedSampleStart;
    /** Bytes transferred since current sample started. */
    private long mSpeedSampleBytes;

    public DownloadThread(Context context, SystemFacade systemFacade, DownloadNotifier notifier,
            DownloadInfo info) {
        mContext = context;
        mSystemFacade = systemFacade;
        mNotifier = notifier;

        mId = info.mId;
        mInfo = info;
        mInfoDelta = new DownloadInfoDelta(info);
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

        // Skip when download already marked as finished; this download was
        // probably started again while racing with UpdateThread.
        if (DownloadInfo.queryDownloadStatus(mContext.getContentResolver(), mId)
                == Downloads.Impl.STATUS_SUCCESS) {
            logDebug("Already finished; skipping");
            return;
        }

        final NetworkPolicyManager netPolicy = NetworkPolicyManager.from(mContext);
        PowerManager.WakeLock wakeLock = null;
        final PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);

        try {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, Constants.TAG);
            wakeLock.setWorkSource(new WorkSource(mInfo.mUid));
            wakeLock.acquire();

            // while performing download, register for rules updates
            netPolicy.registerListener(mPolicyListener);

            logDebug("Starting");

            // Remember which network this download started on; used to
            // determine if errors were due to network changes.
            final NetworkInfo info = mSystemFacade.getActiveNetworkInfo(mInfo.mUid);
            if (info != null) {
                mNetworkType = info.getType();
            }

            // Network traffic on this thread should be counted against the
            // requesting UID, and is tagged with well-known value.
            TrafficStats.setThreadStatsTag(TrafficStats.TAG_SYSTEM_DOWNLOAD);
            TrafficStats.setThreadStatsUid(mInfo.mUid);

            executeDownload();

            mInfoDelta.mStatus = STATUS_SUCCESS;
            TrafficStats.incrementOperationCount(1);

            // If we just finished a chunked file, record total size
            if (mInfoDelta.mTotalBytes == -1) {
                mInfoDelta.mTotalBytes = mInfoDelta.mCurrentBytes;
            }

        } catch (StopRequestException e) {
            mInfoDelta.mStatus = e.getFinalStatus();
            mInfoDelta.mErrorMsg = e.getMessage();

            logWarning("Stop requested with status "
                    + Downloads.Impl.statusToString(mInfoDelta.mStatus) + ": "
                    + mInfoDelta.mErrorMsg);

            /**
             *Add for download pause&resume
             *@{
             */
            if (mInfo.mControl == Downloads.Impl.CONTROL_PAUSED) {
                if (mInfoDelta.mStatus != Downloads.Impl.STATUS_PAUSED_BY_APP) {
                    logWarning("already paused, change status to STATUS_PAUSED_BY_APP");
                    mInfoDelta.mStatus = Downloads.Impl.STATUS_PAUSED_BY_APP;
                }
            }
            /*@}*/

            // Nobody below our level should request retries, since we handle
            // failure counts at this level.
            if (mInfoDelta.mStatus == STATUS_WAITING_TO_RETRY) {
                throw new IllegalStateException("Execution should always throw final error codes");
            }

            // Some errors should be retryable, unless we fail too many times.
            if (isStatusRetryable(mInfoDelta.mStatus)) {
                if (mMadeProgress) {
                    mInfoDelta.mNumFailed = 1;
                } else {
                    mInfoDelta.mNumFailed += 1;
                }

                if (mInfoDelta.mNumFailed < Constants.MAX_RETRIES) {
                    final NetworkInfo info = mSystemFacade.getActiveNetworkInfo(mInfo.mUid);
                    if (info != null && info.getType() == mNetworkType && info.isConnected()) {
                        // Underlying network is still intact, use normal backoff
                        mInfoDelta.mStatus = STATUS_WAITING_TO_RETRY;
                    } else {
                        // Network changed, retry on any next available
                        mInfoDelta.mStatus = STATUS_WAITING_FOR_NETWORK;
                    }

                    /*
                     * for downloadprovider_DRM
                     * original code
                    if ((mInfoDelta.mETag == null && mMadeProgress)
                            || DownloadDrmHelper.isDrmConvertNeeded(mInfoDelta.mMimeType)) {
                     *@{
                     */
                    if ((mInfoDelta.mETag == null && mMadeProgress)
                            || (DownloadDrmHelper.isDrmConvertNeeded(mInfoDelta.mMimeType) && (!DownloadsDRMUtils.getInstance(mContext).isSupportDRM()))) {
                    /*@}*/
                        // However, if we wrote data and have no ETag to verify
                        // contents against later, we can't actually resume.
                        mInfoDelta.mStatus = STATUS_CANNOT_RESUME;
                    }
                }
            }
            /**
             *Add for download pause&resume
             *@{
             */
            if(mInfoDelta.mStatus == Downloads.Impl.STATUS_PAUSED_BY_APP){
                ContentValues values = new ContentValues();
                values.put(Downloads.Impl.COLUMN_CONTROL, TAG_PAUSED_BY_OWNER);
                mContext.getContentResolver().update(mInfo.getAllDownloadsUri(), values, null, null);
            }
            /*@}*/
        } catch (Throwable t) {
            mInfoDelta.mStatus = STATUS_UNKNOWN_ERROR;
            mInfoDelta.mErrorMsg = t.toString();

            logError("Failed: " + mInfoDelta.mErrorMsg, t);

        } finally {
            logDebug("Finished with status " + Downloads.Impl.statusToString(mInfoDelta.mStatus));
            Log.i(Constants.TAG, "DownloadThread download finished status: " + mInfoDelta.mStatus + ",mInfo.mControl="+ mInfo.mControl);
            mNotifier.notifyDownloadSpeed(mId, 0);
            /**
             *Add for download pause&resume
             *@{
             */
            if (Downloads.Impl.isStatusCompleted(mInfoDelta.mStatus) && (mInfo.mControl == Downloads.Impl.CONTROL_PAUSED)){
                Log.i(Constants.TAG, "finally to Start service for update systemui");
                mContext.startService(new Intent(mContext, DownloadService.class));
            }
            /*@}*/
            finalizeDestination();

            mInfoDelta.writeToDatabase();

            /**
             *Add for oma download
             *Original Android code:
             *    if (Downloads.Impl.isStatusCompleted(mInfoDelta.mStatus)) {
             *@{
             */
            if (Downloads.Impl.isStatusCompleted(mInfoDelta.mStatus)
                  || (mInfoDelta.mStatus == Downloads.Impl.STATUS_INSUFFICIENT_SPACE_ERROR)) {
            /*@}*/
                mInfo.sendIntentIfRequested();
            }

            TrafficStats.clearThreadStatsTag();
            TrafficStats.clearThreadStatsUid();

            /*
             * for downloadprovider_DRM
             *@{
             */
            DownloadsDRMUtils.getInstance(mContext).notifyDownloadCompleted(this, mContext, mInfoDelta, mInfoDelta.mStatus, mInfoDelta.mErrorMsg, mInfoDelta.mNumFailed);
            /*@}*/

            netPolicy.unregisterListener(mPolicyListener);

            if (wakeLock != null) {
                wakeLock.release();
                wakeLock = null;
            }
        }
    }

    /**
     * Fully execute a single download request. Setup and send the request,
     * handle the response, and transfer the data to the destination file.
     */
    private void executeDownload() throws StopRequestException {
        final boolean resuming = mInfoDelta.mCurrentBytes != 0;

        URL url;
        try {
            // TODO: migrate URL sanity checking into client side of API
            url = new URL(mInfoDelta.mUri);
        } catch (MalformedURLException e) {
            throw new StopRequestException(STATUS_BAD_REQUEST, e);
        }

        boolean cleartextTrafficPermitted = mSystemFacade.isCleartextTrafficPermitted(mInfo.mUid);
        int redirectionCount = 0;
        while (redirectionCount++ < Constants.MAX_REDIRECTS) {
            // Enforce the cleartext traffic opt-out for the UID. This cannot be enforced earlier
            // because of HTTP redirects which can change the protocol between HTTP and HTTPS.
            if ((!cleartextTrafficPermitted) && ("http".equalsIgnoreCase(url.getProtocol()))) {
                throw new StopRequestException(STATUS_BAD_REQUEST,
                        "Cleartext traffic not permitted for UID " + mInfo.mUid + ": "
                        + Uri.parse(url.toString()).toSafeString());
            }

            // Open connection and follow any redirects until we have a useful
            // response with body.
            HttpURLConnection conn = null;
            try {
                checkConnectivity();
                conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(DEFAULT_TIMEOUT);
                conn.setReadTimeout(DEFAULT_TIMEOUT);

                addRequestHeaders(conn, resuming);

                final int responseCode = conn.getResponseCode();
                switch (responseCode) {
                    case HTTP_OK:
                        if (resuming) {
                            throw new StopRequestException(
                                    STATUS_CANNOT_RESUME, "Expected partial, but received OK");
                        }
                        parseOkHeaders(conn);
                        transferData(conn);
                        return;

                    case HTTP_PARTIAL:
                        if (!resuming) {
                            throw new StopRequestException(
                                    STATUS_CANNOT_RESUME, "Expected OK, but received partial");
                        }
                        transferData(conn);
                        return;

                    case HTTP_MOVED_PERM:
                    case HTTP_MOVED_TEMP:
                    case HTTP_SEE_OTHER:
                    case HTTP_TEMP_REDIRECT:
                        final String location = conn.getHeaderField("Location");
                        url = new URL(url, location);
                        if (responseCode == HTTP_MOVED_PERM) {
                            // Push updated URL back to database
                            mInfoDelta.mUri = url.toString();
                        }
                        continue;

                    case HTTP_PRECON_FAILED:
                        throw new StopRequestException(
                                STATUS_CANNOT_RESUME, "Precondition failed");

                    case HTTP_REQUESTED_RANGE_NOT_SATISFIABLE:
                        throw new StopRequestException(
                                STATUS_CANNOT_RESUME, "Requested range not satisfiable");

                    case HTTP_UNAVAILABLE:
                        parseUnavailableHeaders(conn);
                        throw new StopRequestException(
                                HTTP_UNAVAILABLE, conn.getResponseMessage());

                    case HTTP_INTERNAL_ERROR:
                        throw new StopRequestException(
                                HTTP_INTERNAL_ERROR, conn.getResponseMessage());

                    default:
                        StopRequestException.throwUnhandledHttpError(
                                responseCode, conn.getResponseMessage());
                }

            } catch (IOException e) {
                if (e instanceof ProtocolException
                        && e.getMessage().startsWith("Unexpected status line")) {
                    throw new StopRequestException(STATUS_UNHANDLED_HTTP_CODE, e);
                } else {
                    // Trouble with low-level sockets
                    throw new StopRequestException(STATUS_HTTP_DATA_ERROR, e);
                }

            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        throw new StopRequestException(STATUS_TOO_MANY_REDIRECTS, "Too many redirects");
    }

    /**
     * Transfer data from the given connection to the destination file.
     */
    private void transferData(HttpURLConnection conn) throws StopRequestException {

        // To detect when we're really finished, we either need a length, closed
        // connection, or chunked encoding.
        final boolean hasLength = mInfoDelta.mTotalBytes != -1;
        final boolean isConnectionClose = "close".equalsIgnoreCase(
                conn.getHeaderField("Connection"));
        final boolean isEncodingChunked = "chunked".equalsIgnoreCase(
                conn.getHeaderField("Transfer-Encoding"));

        final boolean finishKnown = hasLength || isConnectionClose || isEncodingChunked;
        if (!finishKnown) {
            throw new StopRequestException(
                    STATUS_CANNOT_RESUME, "can't know size of download, giving up");
        }

        DrmManagerClient drmClient = null;
        ParcelFileDescriptor outPfd = null;
        FileDescriptor outFd = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            try {
                in = conn.getInputStream();
            } catch (IOException e) {
                throw new StopRequestException(STATUS_HTTP_DATA_ERROR, e);
            }

            try {
                outPfd = mContext.getContentResolver()
                        .openFileDescriptor(mInfo.getAllDownloadsUri(), "rw");
                outFd = outPfd.getFileDescriptor();
                /*
                 * for downloadprovider_DRM
                 * original code
                if (DownloadDrmHelper.isDrmConvertNeeded(mInfoDelta.mMimeType)) {
                 *@{
                 */
                if (DownloadDrmHelper.isDrmConvertNeeded(mInfoDelta.mMimeType) && (!DownloadsDRMUtils.getInstance(mContext).isSupportDRM())) {
                /*@}*/
                    drmClient = new DrmManagerClient(mContext);
                    out = new DrmOutputStream(drmClient, outPfd, mInfoDelta.mMimeType);
                } else {
                    out = new ParcelFileDescriptor.AutoCloseOutputStream(outPfd);
                }

                // Pre-flight disk space requirements, when known
                if (mInfoDelta.mTotalBytes > 0) {
                    final long curSize = Os.fstat(outFd).st_size;
                    final long newBytes = mInfoDelta.mTotalBytes - curSize;

                    StorageUtils.ensureAvailableSpace(mContext, outFd, newBytes);

                    try {
                        // We found enough space, so claim it for ourselves
                        Os.posix_fallocate(outFd, 0, mInfoDelta.mTotalBytes);
                    } catch (ErrnoException e) {
                        if (e.errno == OsConstants.ENOSYS || e.errno == OsConstants.ENOTSUP) {
                            Log.w(TAG, "fallocate() not supported; falling back to ftruncate()");
                            Os.ftruncate(outFd, mInfoDelta.mTotalBytes);
                        } else {
                            throw e;
                        }
                    }
                }

                // Move into place to begin writing
                Os.lseek(outFd, mInfoDelta.mCurrentBytes, OsConstants.SEEK_SET);

            } catch (ErrnoException e) {
                throw new StopRequestException(STATUS_FILE_ERROR, e);
            } catch (IOException e) {
                throw new StopRequestException(STATUS_FILE_ERROR, e);
            }

            // Start streaming data, periodically watch for pause/cancel
            // commands and checking disk space as needed.
            transferData(in, out, outFd);

            try {
                if (out instanceof DrmOutputStream) {
                    ((DrmOutputStream) out).finish();
                }
            } catch (IOException e) {
                throw new StopRequestException(STATUS_FILE_ERROR, e);
            }

        } finally {
            if (drmClient != null) {
                drmClient.release();
            }

            IoUtils.closeQuietly(in);

            try {
                if (out != null) out.flush();
                if (outFd != null) outFd.sync();
            } catch (IOException e) {
            } finally {
                IoUtils.closeQuietly(out);
            }
        }
    }

    /**
     * Transfer as much data as possible from the HTTP response to the
     * destination file.
     */
    private void transferData(InputStream in, OutputStream out, FileDescriptor outFd)
            throws StopRequestException {
        final byte buffer[] = new byte[Constants.BUFFER_SIZE];
        while (true) {
            checkPausedOrCanceled();

            int len = -1;
            try {
                len = in.read(buffer);
            } catch (IOException e) {
                throw new StopRequestException(
                        STATUS_HTTP_DATA_ERROR, "Failed reading response: " + e, e);
            }

            if (len == -1) {
                break;
            }

            try {
                // When streaming, ensure space before each write
                if (mInfoDelta.mTotalBytes == -1) {
                    final long curSize = Os.fstat(outFd).st_size;
                    final long newBytes = (mInfoDelta.mCurrentBytes + len) - curSize;

                    StorageUtils.ensureAvailableSpace(mContext, outFd, newBytes);
                }

                out.write(buffer, 0, len);

                mMadeProgress = true;
                mInfoDelta.mCurrentBytes += len;

                updateProgress(outFd);

            } catch (ErrnoException e) {
                throw new StopRequestException(STATUS_FILE_ERROR, e);
            } catch (IOException e) {
                throw new StopRequestException(STATUS_FILE_ERROR, e);
            }
        }

        // Finished without error; verify length if known
        if (mInfoDelta.mTotalBytes != -1 && mInfoDelta.mCurrentBytes != mInfoDelta.mTotalBytes) {
            throw new StopRequestException(STATUS_HTTP_DATA_ERROR, "Content length mismatch");
        }
    }

    /**
     * Called just before the thread finishes, regardless of status, to take any
     * necessary action on the downloaded file.
     */
    private void finalizeDestination() {
        if (Downloads.Impl.isStatusError(mInfoDelta.mStatus)) {
            // When error, free up any disk space
            try {
                final ParcelFileDescriptor target = mContext.getContentResolver()
                        .openFileDescriptor(mInfo.getAllDownloadsUri(), "rw");
                try {
                    Os.ftruncate(target.getFileDescriptor(), 0);
                } catch (ErrnoException ignored) {
                } finally {
                    IoUtils.closeQuietly(target);
                }
            } catch (FileNotFoundException ignored) {
            }

            // Delete if local file
            if (mInfoDelta.mFileName != null) {
                new File(mInfoDelta.mFileName).delete();
                mInfoDelta.mFileName = null;
            }

        } else if (Downloads.Impl.isStatusSuccess(mInfoDelta.mStatus)) {
            // When success, open access if local file
            if (mInfoDelta.mFileName != null) {
                try {
                    // TODO: remove this once PackageInstaller works with content://
                    Os.chmod(mInfoDelta.mFileName, 0644);
                } catch (ErrnoException ignored) {
                }

                if (mInfo.mDestination != Downloads.Impl.DESTINATION_FILE_URI) {
                    try {
                        // Move into final resting place, if needed
                        final File before = new File(mInfoDelta.mFileName);
                        final File beforeDir = Helpers.getRunningDestinationDirectory(
                                mContext, mInfo.mDestination);
                        final File afterDir = Helpers.getSuccessDestinationDirectory(
                                mContext, mInfo.mDestination);
                        if (!beforeDir.equals(afterDir)
                                && before.getParentFile().equals(beforeDir)) {
                            final File after = new File(afterDir, before.getName());
                            if (before.renameTo(after)) {
                                mInfoDelta.mFileName = after.getAbsolutePath();
                            }
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    /**
     * Check if current connectivity is valid for this request.
     */
    private void checkConnectivity() throws StopRequestException {
        // checking connectivity will apply current policy
        mPolicyDirty = false;

        final NetworkState networkUsable = mInfo.checkCanUseNetwork(mInfoDelta.mTotalBytes);
        if (networkUsable != NetworkState.OK) {
            int status = Downloads.Impl.STATUS_WAITING_FOR_NETWORK;
            if (networkUsable == NetworkState.UNUSABLE_DUE_TO_SIZE) {
                status = Downloads.Impl.STATUS_QUEUED_FOR_WIFI;
                mInfo.notifyPauseDueToSize(true);
            } else if (networkUsable == NetworkState.RECOMMENDED_UNUSABLE_DUE_TO_SIZE) {
                status = Downloads.Impl.STATUS_QUEUED_FOR_WIFI;
                mInfo.notifyPauseDueToSize(false);
            }
            throw new StopRequestException(status, networkUsable.name());
        }
    }

    /**
     * Check if the download has been paused or canceled, stopping the request
     * appropriately if it has been.
     */
    private void checkPausedOrCanceled() throws StopRequestException {
        synchronized (mInfo) {
            if (mInfo.mControl == Downloads.Impl.CONTROL_PAUSED) {
                throw new StopRequestException(
                        Downloads.Impl.STATUS_PAUSED_BY_APP, "download paused by owner");
            }
            if (mInfo.mStatus == Downloads.Impl.STATUS_CANCELED || mInfo.mDeleted) {
                throw new StopRequestException(Downloads.Impl.STATUS_CANCELED, "download canceled");
            }
        }

        // if policy has been changed, trigger connectivity check
        if (mPolicyDirty) {
            checkConnectivity();
        }
    }

    /**
     * Report download progress through the database if necessary.
     */
    private void updateProgress(FileDescriptor outFd) throws IOException, StopRequestException {
        final long now = SystemClock.elapsedRealtime();
        final long currentBytes = mInfoDelta.mCurrentBytes;

        final long sampleDelta = now - mSpeedSampleStart;
        if (sampleDelta > 500) {
            final long sampleSpeed = ((currentBytes - mSpeedSampleBytes) * 1000)
                    / sampleDelta;

            if (mSpeed == 0) {
                mSpeed = sampleSpeed;
            } else {
                mSpeed = ((mSpeed * 3) + sampleSpeed) / 4;
            }

            // Only notify once we have a full sample window
            if (mSpeedSampleStart != 0) {
                mNotifier.notifyDownloadSpeed(mId, mSpeed);
            }

            mSpeedSampleStart = now;
            mSpeedSampleBytes = currentBytes;
        }

        final long bytesDelta = currentBytes - mLastUpdateBytes;
        final long timeDelta = now - mLastUpdateTime;
        if (bytesDelta > Constants.MIN_PROGRESS_STEP && timeDelta > Constants.MIN_PROGRESS_TIME) {
            // fsync() to ensure that current progress has been flushed to disk,
            // so we can always resume based on latest database information.
            outFd.sync();

            mInfoDelta.writeToDatabaseOrThrow();

            mLastUpdateBytes = currentBytes;
            mLastUpdateTime = now;
        }
    }

    /**
     * Process response headers from first server response. This derives its
     * filename, size, and ETag.
     */
    private void parseOkHeaders(HttpURLConnection conn) throws StopRequestException {
        if (mInfoDelta.mFileName == null) {
            final String contentDisposition = conn.getHeaderField("Content-Disposition");
            final String contentLocation = conn.getHeaderField("Content-Location");

            try {
                mInfoDelta.mFileName = Helpers.generateSaveFile(mContext, mInfoDelta.mUri,
                        mInfo.mHint, contentDisposition, contentLocation, mInfoDelta.mMimeType,
                        mInfo.mDestination);
            } catch (IOException e) {
                throw new StopRequestException(
                        Downloads.Impl.STATUS_FILE_ERROR, "Failed to generate filename: " + e);
            }
        }

        if (mInfoDelta.mMimeType == null) {
            mInfoDelta.mMimeType = Intent.normalizeMimeType(conn.getContentType());
        }

        final String transferEncoding = conn.getHeaderField("Transfer-Encoding");
        if (transferEncoding == null) {
            mInfoDelta.mTotalBytes = getHeaderFieldLong(conn, "Content-Length", -1);
        } else {
            mInfoDelta.mTotalBytes = -1;
        }

        mInfoDelta.mETag = conn.getHeaderField("ETag");

        mInfoDelta.writeToDatabaseOrThrow();

        // Check connectivity again now that we know the total size
        checkConnectivity();
    }

    private void parseUnavailableHeaders(HttpURLConnection conn) {
        long retryAfter = conn.getHeaderFieldInt("Retry-After", -1);
        if (retryAfter < 0) {
            retryAfter = 0;
        } else {
            if (retryAfter < Constants.MIN_RETRY_AFTER) {
                retryAfter = Constants.MIN_RETRY_AFTER;
            } else if (retryAfter > Constants.MAX_RETRY_AFTER) {
                retryAfter = Constants.MAX_RETRY_AFTER;
            }
            retryAfter += Helpers.sRandom.nextInt(Constants.MIN_RETRY_AFTER + 1);
        }

        mInfoDelta.mRetryAfter = (int) (retryAfter * SECOND_IN_MILLIS);
    }

    /**
     * Add custom headers for this download to the HTTP request.
     */
    private void addRequestHeaders(HttpURLConnection conn, boolean resuming) {
        for (Pair<String, String> header : mInfo.getHeaders()) {
            conn.addRequestProperty(header.first, header.second);
        }

        Log.i(TAG, "addRequestHeaders User-Agent old =" + conn.getRequestProperty("User-Agent"));
        // Only splice in user agent when not already defined
        if ((conn.getRequestProperty("User-Agent") == null) || (conn.getRequestProperty("User-Agent").length() <= 0)) {
            /*modify for empty user agent string*/
            conn.setRequestProperty("User-Agent", mInfo.getUserAgent());
        }
        Log.i(TAG, "addRequestHeaders User-Agent new =" + conn.getRequestProperty("User-Agent"));

        // Defeat transparent gzip compression, since it doesn't allow us to
        // easily resume partial downloads.
        conn.setRequestProperty("Accept-Encoding", "identity");

        // Defeat connection reuse, since otherwise servers may continue
        // streaming large downloads after cancelled.
        conn.setRequestProperty("Connection", "close");

        if (resuming) {
            if (mInfoDelta.mETag != null) {
                conn.addRequestProperty("If-Match", mInfoDelta.mETag);
            }
            conn.addRequestProperty("Range", "bytes=" + mInfoDelta.mCurrentBytes + "-");
        }
    }

    private void logDebug(String msg) {
        Log.d(TAG, "[" + mId + "] " + msg);
    }

    private void logWarning(String msg) {
        Log.w(TAG, "[" + mId + "] " + msg);
    }

    private void logError(String msg, Throwable t) {
        Log.e(TAG, "[" + mId + "] " + msg, t);
    }

    private INetworkPolicyListener mPolicyListener = new INetworkPolicyListener.Stub() {
        @Override
        public void onUidRulesChanged(int uid, int uidRules) {
            // caller is NPMS, since we only register with them
            if (uid == mInfo.mUid) {
                mPolicyDirty = true;
            }
        }

        @Override
        public void onMeteredIfacesChanged(String[] meteredIfaces) {
            // caller is NPMS, since we only register with them
            mPolicyDirty = true;
        }

        @Override
        public void onRestrictBackgroundChanged(boolean restrictBackground) {
            // caller is NPMS, since we only register with them
            mPolicyDirty = true;
        }
    };

    private static long getHeaderFieldLong(URLConnection conn, String field, long defaultValue) {
        try {
            return Long.parseLong(conn.getHeaderField(field));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Return if given status is eligible to be treated as
     * {@link android.provider.Downloads.Impl#STATUS_WAITING_TO_RETRY}.
     */
    public static boolean isStatusRetryable(int status) {
        switch (status) {
            case STATUS_HTTP_DATA_ERROR:
            case HTTP_UNAVAILABLE:
            case HTTP_INTERNAL_ERROR:
            case STATUS_FILE_ERROR:
                return true;
            default:
                return false;
        }
    }
}
