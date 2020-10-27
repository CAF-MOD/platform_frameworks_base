/*
 * Copyright (C) 2019 BaikalOS
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


package com.android.server;

import com.android.internal.baikalos.Actions;
import com.android.internal.baikalos.Bluetooth;
import com.android.internal.baikalos.Telephony;
import com.android.internal.baikalos.Torch;
import com.android.internal.baikalos.Sensors;
import com.android.internal.baikalos.Runtime;
import com.android.internal.baikalos.AppProfileManager;
import com.android.internal.baikalos.DevProfileManager;

import com.android.internal.baikalos.AppProfile;
import com.android.internal.baikalos.AppProfileSettings;

import android.app.job.IJobScheduler;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobProtoEnums;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.app.job.JobSnapshot;
import android.app.job.JobWorkItem;

import com.android.internal.baikalos.BaikalSettings;
import com.android.internal.baikalos.BaikalUtils;

import android.util.Slog;

import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;


import android.os.Binder;
import android.os.IBinder;
import android.os.SystemProperties;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.UserHandle;
import android.os.Process;
import android.os.SystemClock;

import android.app.AlarmManager;


import com.android.server.BaikalStaticService;
import com.android.internal.baikalos.BaikalConstants;


public class BaikalStaticService {

    private static final String TAG = "BaikalService";

    private static final boolean DEBUG = false;

    private static boolean mSystemReady = false;

    private static Context mContext;

    static com.android.internal.baikalos.Actions mBaikalActions;
    static com.android.internal.baikalos.Bluetooth mBaikalBluetooth;
    static com.android.internal.baikalos.Telephony mBaikalTelephony;
    static com.android.internal.baikalos.Torch mBaikalTorch;
    static com.android.internal.baikalos.Sensors mBaikalSensors;
    static com.android.internal.baikalos.AppProfileManager mBaikalAppProfileManager;
    static com.android.internal.baikalos.DevProfileManager mBaikalDevProfileManager;

    static com.android.internal.baikalos.BaikalSettings mBaikalSettings;


    BaikalStaticService() {
    }

    static void Initialize(Context context,
	    com.android.internal.baikalos.Actions baikalActions,
	    com.android.internal.baikalos.Bluetooth baikalBluetooth,
	    com.android.internal.baikalos.Telephony baikalTelephony,
	    com.android.internal.baikalos.Torch baikalTorch,
	    com.android.internal.baikalos.Sensors baikalSensors,
	    com.android.internal.baikalos.AppProfileManager baikalAppProfileManager,
	    com.android.internal.baikalos.DevProfileManager baikalDevProfileManager,
	    com.android.internal.baikalos.BaikalSettings baikalSettings) {

        mContext = context;
        if( BaikalConstants.BAIKAL_DEBUG_ACTIVITY ) {
            Slog.i(TAG,"BaikalStatic()");
        }

	    mBaikalActions = baikalActions;
	    mBaikalBluetooth = baikalBluetooth;
	    mBaikalTelephony = baikalTelephony;
	    mBaikalTorch = baikalTorch;
	    mBaikalSensors = baikalSensors;
        mBaikalAppProfileManager = baikalAppProfileManager;
        mBaikalDevProfileManager = baikalDevProfileManager;
	    mBaikalSettings = baikalSettings;
    }

    static void setSystemReady(boolean ready) {
        if( BaikalConstants.BAIKAL_DEBUG_ACTIVITY ) {
            Slog.i(TAG,"setSystemReady(" + ready + ")");
        }
	    mSystemReady = ready;
    }

    private static Object mStaticMembersLock = new Object();

    private static boolean mHideIdleFromGms;
    private static boolean mUnrestrictedNetwork;
	

    public static boolean isEnergySaveMode() {
	    return BaikalSettings.getAggressiveIdleEnabled() ||
		   BaikalSettings.getExtremeIdleEnabled();
    }

    public static boolean isJobBlacklisted(JobInfo job, JobWorkItem work, int uId, String packageName,
            int userId, String tag) {

        if( !BaikalSettings.getStaminaMode() &&
            !BaikalSettings.getExtremeIdleEnabled() &&
            !BaikalSettings.getAggressiveIdleEnabled() ) {
            if( BaikalConstants.BAIKAL_DEBUG_ALARM ) Slog.i(TAG,"isJobBlacklisted: Not in energy saving mode ");
            return false;
        }


        if( BaikalSettings.getTopAppUid() == uId ) return false;

        if( BaikalSettings.getAppBlocked(uId, packageName) ) {
            if( BaikalConstants.BAIKAL_DEBUG_ALARM ) {
                Slog.i(TAG,"isJobBlacklisted: blocked: uid=" + uId + ", pkg=" + packageName + " -> " + job + " : " + work);
            }
            return true;
        }

        if( !allowBackgroundStart(uId, packageName) ) {
            if( BaikalConstants.BAIKAL_DEBUG_ALARM ) {
                Slog.i(TAG,"isJobBlacklisted: restricted: uid=" + uId + ", pkg=" + packageName + " -> " + job + " : " + work);
            }
            return true;
        }
            
        return false;
    }

    //boolean updateSingleJobRestrictionLocked(boolean canRun, JobStatus jobStatus, int activeState) {
    //    final int uid = jobStatus.getSourceUid();
    //    final String packageName = jobStatus.getSourcePackageName();
    //    return canRun;
    //}

    public static boolean updateSingleJobRestrictionLocked(boolean canRun, int uid, String packageName, int activeState, JobInfo jobInfo) {
        if( !BaikalSettings.getStaminaMode() &&
            !BaikalSettings.getExtremeIdleEnabled() &&
            !BaikalSettings.getAggressiveIdleEnabled() ) {
            if( BaikalConstants.BAIKAL_DEBUG_SERVICES ) Slog.i(TAG,"updateSingleJobRestrictionLocked: Not in energy saving mode ");
            return canRun;
        }

        if( BaikalSettings.getTopAppUid() == uid ) return true;

        AppProfile profile = AppProfileSettings.getProfileStatic(packageName);
        if( profile == null ) return canRun;


        if( BaikalUtils.isGmsUid(uid)  ) {
            if( BaikalSettings.getAppBlocked(uid, packageName) ) {
                if( BaikalConstants.BAIKAL_DEBUG_SERVICES ) {
                    Slog.i(TAG,"updateSingleJobRestrictionLocked: GMS blocked: uid=" + uid + ", pkg=" + packageName + ", job=" + jobInfo);
                }
                return false;
            }
            return !jobGmsBlackListed(jobInfo);
        }

        
        if( profile.mBackground < 0 ) {
            if( BaikalConstants.BAIKAL_DEBUG_SERVICES ) {
                Slog.i(TAG,"updateSingleJobRestrictionLocked: whitelisted: uid=" + uid + ", pkg=" + packageName + ", job=" + jobInfo);
            }
            return true;
        }
        if( !getBackgroundMode(profile) ) {
            if( BaikalConstants.BAIKAL_DEBUG_SERVICES ) { 
                Slog.i(TAG,"updateSingleJobRestrictionLocked: restricted : uid=" + uid + ", pkg=" + packageName + ", job=" + jobInfo);
            }
            return false;
        }
        return canRun;
    }


    private static boolean jobGmsBlackListed(JobInfo jobInfo) {
        if( BaikalConstants.BAIKAL_DEBUG_SERVICES ) {
            Slog.i(TAG,"updateSingleJobRestrictionLocked: GMS job service=" + jobInfo.getService().toString());
        }
        return false;
    }

    public static boolean allowBackgroundStart(int uid, String packageName) {

        if( !BaikalSettings.getStaminaMode() &&
            !BaikalSettings.getExtremeIdleEnabled() &&
            !BaikalSettings.getAggressiveIdleEnabled() ) {
            if( BaikalConstants.BAIKAL_DEBUG_ACTIVITY ) Slog.i(TAG,"allowBackgroundStart: Not in energy saving mode ");
            return true;
        }

        if( BaikalSettings.getTopAppUid() == uid ) return true;
        AppProfile profile = AppProfileSettings.getProfileStatic(packageName);
        if( profile == null ) return true;
        if( !getBackgroundMode(profile) ) return false;
        return true;
    }

    private static boolean getBackgroundMode(AppProfile profile) {
        if( Runtime.isIdleMode()  ) {
            if( profile.mBackground > 1 && BaikalSettings.getAggressiveIdleEnabled() ) return false;
            if( profile.mBackground > 0 && BaikalSettings.getExtremeIdleEnabled() ) return false;
        } else {
            if( profile.mBackground > 2 && BaikalSettings.getAggressiveIdleEnabled() ) return false;
            if( profile.mBackground > 1 && BaikalSettings.getExtremeIdleEnabled() ) return false;
        }
        return true;
    }


}