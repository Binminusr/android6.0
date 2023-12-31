/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.settings.applications;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.text.format.Formatter;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.app.IProcessStats;
import com.android.internal.app.ProcessMap;
import com.android.internal.app.ProcessStats;
import com.android.internal.app.ProcessStats.ProcessDataCollection;
import com.android.internal.app.ProcessStats.TotalMemoryUseCollection;
import com.android.internal.util.MemInfoReader;
import com.android.settings.R;
import com.android.settings.Utils;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import com.sprd.android.config.OptConfig;//Kalyy

public class ProcStatsData {

    private static final String TAG = "ProcStatsManager";

    private static final boolean DEBUG = ProcessStatsUi.DEBUG;

    private static ProcessStats sStatsXfer;

    private PackageManager mPm;
    private Context mContext;
    private long memTotalTime;

    private IProcessStats mProcessStats;
    private ProcessStats mStats;

    private boolean mUseUss;
    private long mDuration;

    private int[] mMemStates;

    private int[] mStates;

    private MemInfo mMemInfo;

    private ArrayList<ProcStatsPackageEntry> pkgEntries;

    public ProcStatsData(Context context, boolean useXfer) {
        mContext = context;
        mPm = context.getPackageManager();
        mProcessStats = IProcessStats.Stub.asInterface(
                ServiceManager.getService(ProcessStats.SERVICE_NAME));
        mMemStates = ProcessStats.ALL_MEM_ADJ;
        mStates = ProcessStats.BACKGROUND_PROC_STATES;
        if (useXfer) {
            mStats = sStatsXfer;
        }
    }

    public void setTotalTime(int totalTime) {
        memTotalTime = totalTime;
    }

    public void xferStats() {
        sStatsXfer = mStats;
    }

    public void setMemStates(int[] memStates) {
        mMemStates = memStates;
        refreshStats(false);
    }

    public void setStats(int[] stats) {
        this.mStates = stats;
        refreshStats(false);
    }

    public int getMemState() {
        int factor = mStats.mMemFactor;
        if (factor == ProcessStats.ADJ_NOTHING) {
            return ProcessStats.ADJ_MEM_FACTOR_NORMAL;
        }
        if (factor >= ProcessStats.ADJ_SCREEN_ON) {
            factor -= ProcessStats.ADJ_SCREEN_ON;
        }
        return factor;
    }

    public MemInfo getMemInfo() {
        return mMemInfo;
    }

    public long getElapsedTime() {
        return mStats.mTimePeriodEndRealtime - mStats.mTimePeriodStartRealtime;
    }

    public void setDuration(long duration) {
        if (duration != mDuration) {
            mDuration = duration;
            refreshStats(true);
        }
    }

    public long getDuration() {
        return mDuration;
    }

    public List<ProcStatsPackageEntry> getEntries() {
        return pkgEntries;
    }

    public void refreshStats(boolean forceLoad) {
        if (mStats == null || forceLoad) {
            load();
        }

        pkgEntries = new ArrayList<>();

        long now = SystemClock.uptimeMillis();

        memTotalTime = ProcessStats.dumpSingleTime(null, null, mStats.mMemFactorDurations,
                mStats.mMemFactor, mStats.mStartTime, now);

        ProcessStats.TotalMemoryUseCollection totalMem = new ProcessStats.TotalMemoryUseCollection(
                ProcessStats.ALL_SCREEN_ADJ, mMemStates);
        mStats.computeTotalMemoryUse(totalMem, now);

        mMemInfo = new MemInfo(mContext, totalMem, memTotalTime);

        ProcessDataCollection bgTotals = new ProcessDataCollection(
                ProcessStats.ALL_SCREEN_ADJ, mMemStates, mStates);
        ProcessDataCollection runTotals = new ProcessDataCollection(
                ProcessStats.ALL_SCREEN_ADJ, mMemStates, ProcessStats.NON_CACHED_PROC_STATES);

        createPkgMap(getProcs(bgTotals, runTotals), bgTotals, runTotals);

        ProcStatsPackageEntry osPkg = createOsEntry(bgTotals, runTotals, totalMem,
                mMemInfo.baseCacheRam);
        pkgEntries.add(osPkg);
    }

    private void createPkgMap(ArrayList<ProcStatsEntry> procEntries, ProcessDataCollection bgTotals,
            ProcessDataCollection runTotals) {
        // Combine processes into packages.
        ArrayMap<String, ProcStatsPackageEntry> pkgMap = new ArrayMap<>();
        for (int i = procEntries.size() - 1; i >= 0; i--) {
            ProcStatsEntry proc = procEntries.get(i);
            proc.evaluateTargetPackage(mPm, mStats, bgTotals, runTotals, sEntryCompare, mUseUss);
            ProcStatsPackageEntry pkg = pkgMap.get(proc.mBestTargetPackage);
            if (pkg == null) {
                pkg = new ProcStatsPackageEntry(proc.mBestTargetPackage, memTotalTime);
                pkgMap.put(proc.mBestTargetPackage, pkg);
                pkgEntries.add(pkg);
            }
            pkg.addEntry(proc);
        }
    }

    private ProcStatsPackageEntry createOsEntry(ProcessDataCollection bgTotals,
            ProcessDataCollection runTotals, TotalMemoryUseCollection totalMem, long baseCacheRam) {
        // Add in fake entry representing the OS itself.
        ProcStatsPackageEntry osPkg = new ProcStatsPackageEntry("os", memTotalTime);
        ProcStatsEntry osEntry;
        if (totalMem.sysMemNativeWeight > 0) {
            osEntry = new ProcStatsEntry(Utils.OS_PKG, 0,
                    mContext.getString(R.string.process_stats_os_native), memTotalTime,
                    (long) (totalMem.sysMemNativeWeight / memTotalTime));
            osEntry.evaluateTargetPackage(mPm, mStats, bgTotals, runTotals, sEntryCompare, mUseUss);
            osPkg.addEntry(osEntry);
        }
        if (totalMem.sysMemKernelWeight > 0) {
            osEntry = new ProcStatsEntry(Utils.OS_PKG, 0,
                    mContext.getString(R.string.process_stats_os_kernel), memTotalTime,
                    (long) (totalMem.sysMemKernelWeight / memTotalTime));
            osEntry.evaluateTargetPackage(mPm, mStats, bgTotals, runTotals, sEntryCompare, mUseUss);
            osPkg.addEntry(osEntry);
        }
        if (totalMem.sysMemZRamWeight > 0) {
            osEntry = new ProcStatsEntry(Utils.OS_PKG, 0,
                    mContext.getString(R.string.process_stats_os_zram), memTotalTime,
                    (long) (totalMem.sysMemZRamWeight / memTotalTime));
            osEntry.evaluateTargetPackage(mPm, mStats, bgTotals, runTotals, sEntryCompare, mUseUss);
            osPkg.addEntry(osEntry);
        }
        if (baseCacheRam > 0) {
            osEntry = new ProcStatsEntry(Utils.OS_PKG, 0,
                    mContext.getString(R.string.process_stats_os_cache), memTotalTime,
                    baseCacheRam / 1024);
            osEntry.evaluateTargetPackage(mPm, mStats, bgTotals, runTotals, sEntryCompare, mUseUss);
            osPkg.addEntry(osEntry);
        }
        return osPkg;
    }

    private ArrayList<ProcStatsEntry> getProcs(ProcessDataCollection bgTotals,
            ProcessDataCollection runTotals) {
        final ArrayList<ProcStatsEntry> procEntries = new ArrayList<>();
        if (DEBUG) Log.d(TAG, "-------------------- PULLING PROCESSES");

        final ProcessMap<ProcStatsEntry> entriesMap = new ProcessMap<ProcStatsEntry>();
        for (int ipkg = 0, N = mStats.mPackages.getMap().size(); ipkg < N; ipkg++) {
            final SparseArray<SparseArray<ProcessStats.PackageState>> pkgUids = mStats.mPackages
                    .getMap().valueAt(ipkg);
            for (int iu = 0; iu < pkgUids.size(); iu++) {
                final SparseArray<ProcessStats.PackageState> vpkgs = pkgUids.valueAt(iu);
                for (int iv = 0; iv < vpkgs.size(); iv++) {
                    final ProcessStats.PackageState st = vpkgs.valueAt(iv);
                    for (int iproc = 0; iproc < st.mProcesses.size(); iproc++) {
                        final ProcessStats.ProcessState pkgProc = st.mProcesses.valueAt(iproc);
                        final ProcessStats.ProcessState proc = mStats.mProcesses.get(pkgProc.mName,
                                pkgProc.mUid);
                        if (proc == null) {
                            Log.w(TAG, "No process found for pkg " + st.mPackageName
                                    + "/" + st.mUid + " proc name " + pkgProc.mName);
                            continue;
                        }
                        ProcStatsEntry ent = entriesMap.get(proc.mName, proc.mUid);
                        if (ent == null) {
                            ent = new ProcStatsEntry(proc, st.mPackageName, bgTotals, runTotals,
                                    mUseUss);
                            if (ent.mRunWeight > 0) {
                                if (DEBUG) Log.d(TAG, "Adding proc " + proc.mName + "/"
                                            + proc.mUid + ": time="
                                            + ProcessStatsUi.makeDuration(ent.mRunDuration) + " ("
                                            + ((((double) ent.mRunDuration) / memTotalTime) * 100)
                                            + "%)"
                                            + " pss=" + ent.mAvgRunMem);
                                entriesMap.put(proc.mName, proc.mUid, ent);
                                procEntries.add(ent);
                            }
                        } else {
                            ent.addPackage(st.mPackageName);
                        }
                    }
                }
            }
        }

        if (DEBUG) Log.d(TAG, "-------------------- MAPPING SERVICES");

        // Add in service info.
        for (int ip = 0, N = mStats.mPackages.getMap().size(); ip < N; ip++) {
            SparseArray<SparseArray<ProcessStats.PackageState>> uids = mStats.mPackages.getMap()
                    .valueAt(ip);
            for (int iu = 0; iu < uids.size(); iu++) {
                SparseArray<ProcessStats.PackageState> vpkgs = uids.valueAt(iu);
                for (int iv = 0; iv < vpkgs.size(); iv++) {
                    ProcessStats.PackageState ps = vpkgs.valueAt(iv);
                    for (int is = 0, NS = ps.mServices.size(); is < NS; is++) {
                        ProcessStats.ServiceState ss = ps.mServices.valueAt(is);
                        if (ss.mProcessName != null) {
                            ProcStatsEntry ent = entriesMap.get(ss.mProcessName,
                                    uids.keyAt(iu));
                            if (ent != null) {
                                if (DEBUG) Log.d(TAG, "Adding service " + ps.mPackageName
                                            + "/" + ss.mName + "/" + uids.keyAt(iu) + " to proc "
                                            + ss.mProcessName);
                                ent.addService(ss);
                            } else {
                                Log.w(TAG, "No process " + ss.mProcessName + "/" + uids.keyAt(iu)
                                        + " for service " + ss.mName);
                            }
                        }
                    }
                }
            }
        }

        return procEntries;
    }

    private void load() {
        try {
            ParcelFileDescriptor pfd = mProcessStats.getStatsOverTime(mDuration);
            mStats = new ProcessStats(false);
            InputStream is = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
            mStats.read(is);
            try {
                is.close();
            } catch (IOException e) {
            }
            if (mStats.mReadError != null) {
                Log.w(TAG, "Failure reading process stats: " + mStats.mReadError);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException:", e);
        }
    }

    public static class MemInfo {
        double realUsedRam;
        double realFreeRam;
        double realTotalRam;
        long baseCacheRam;

        double[] mMemStateWeights = new double[ProcessStats.STATE_COUNT];
        double freeWeight;
        double usedWeight;
        double weightToRam;
        double totalRam;
        double totalScale;
        long memTotalTime;

        private MemInfo(Context context, ProcessStats.TotalMemoryUseCollection totalMem,
                long memTotalTime) {
            this.memTotalTime = memTotalTime;
            calculateWeightInfo(context, totalMem, memTotalTime);

            double usedRam = (usedWeight * 1024) / memTotalTime;
            double freeRam = (freeWeight * 1024) / memTotalTime;
            totalRam = usedRam + freeRam;
            totalScale = realTotalRam / totalRam;
            weightToRam = totalScale / memTotalTime * 1024;

            realUsedRam = usedRam * totalScale;
            realFreeRam = freeRam * totalScale;
            if (DEBUG) {
                Log.i(TAG, "Scaled Used RAM: " + Formatter.formatShortFileSize(context,
                        (long) realUsedRam));
                Log.i(TAG, "Scaled Free RAM: " + Formatter.formatShortFileSize(context,
                        (long) realFreeRam));
            }
            if (DEBUG) {
                Log.i(TAG, "Adj Scaled Used RAM: " + Formatter.formatShortFileSize(context,
                        (long) realUsedRam));
                Log.i(TAG, "Adj Scaled Free RAM: " + Formatter.formatShortFileSize(context,
                        (long) realFreeRam));
            }

            ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
            ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(
                    memInfo);
            if (memInfo.hiddenAppThreshold >= realFreeRam) {
                realUsedRam = freeRam;
                realFreeRam = 0;
                baseCacheRam = (long) realFreeRam;
            } else {
                realUsedRam += memInfo.hiddenAppThreshold;
                realFreeRam -= memInfo.hiddenAppThreshold;
                baseCacheRam = memInfo.hiddenAppThreshold;
            }
        }

        private void calculateWeightInfo(Context context, TotalMemoryUseCollection totalMem,
                long memTotalTime) {
            MemInfoReader memReader = new MemInfoReader();
            memReader.readMemInfo();
            //Kalyy
            if(OptConfig.SUN_RAM_INFO_2G){
                realTotalRam = 2L*1024*1024*1024;
            }else if(OptConfig.SUN_RAM_INFO_1G){
                realTotalRam = 1L*1024*1024*1024;
            }else if(OptConfig.SUN_RAM_INFO_3G){//ljb add
                realTotalRam = 3L*1024*1024*1024;
            //qiuyaobo,20170223,begin    
            }else if(OptConfig.SUN_RAM_INFO_512M){
                realTotalRam = 512*1024*1024;
            //qiuyaobo,20170223,end                    
            }else{
                realTotalRam = memReader.getTotalSize();
            }
            freeWeight = totalMem.sysMemFreeWeight + totalMem.sysMemCachedWeight;
            usedWeight = totalMem.sysMemKernelWeight + totalMem.sysMemNativeWeight
                    + totalMem.sysMemZRamWeight;
            for (int i = 0; i < ProcessStats.STATE_COUNT; i++) {
                if (i == ProcessStats.STATE_SERVICE_RESTARTING) {
                    // These don't really run.
                    mMemStateWeights[i] = 0;
                } else {
                    mMemStateWeights[i] = totalMem.processStateWeight[i];
                    if (i >= ProcessStats.STATE_HOME) {
                        freeWeight += totalMem.processStateWeight[i];
                    } else {
                        usedWeight += totalMem.processStateWeight[i];
                    }
                }
            }
            if (DEBUG) {
                Log.i(TAG, "Used RAM: " + Formatter.formatShortFileSize(context,
                        (long) ((usedWeight * 1024) / memTotalTime)));
                Log.i(TAG, "Free RAM: " + Formatter.formatShortFileSize(context,
                        (long) ((freeWeight * 1024) / memTotalTime)));
                Log.i(TAG, "Total RAM: " + Formatter.formatShortFileSize(context,
                        (long) (((freeWeight + usedWeight) * 1024) / memTotalTime)));
            }
        }
    }

    final static Comparator<ProcStatsEntry> sEntryCompare = new Comparator<ProcStatsEntry>() {
        @Override
        public int compare(ProcStatsEntry lhs, ProcStatsEntry rhs) {
            if (lhs.mRunWeight < rhs.mRunWeight) {
                return 1;
            } else if (lhs.mRunWeight > rhs.mRunWeight) {
                return -1;
            } else if (lhs.mRunDuration < rhs.mRunDuration) {
                return 1;
            } else if (lhs.mRunDuration > rhs.mRunDuration) {
                return -1;
            }
            return 0;
        }
    };
}
