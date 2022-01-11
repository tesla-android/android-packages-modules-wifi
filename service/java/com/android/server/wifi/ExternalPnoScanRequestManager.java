/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.wifi;

import static android.net.wifi.WifiManager.PnoScanResultsCallback.REGISTER_PNO_CALLBACK_ALREADY_REGISTERED;
import static android.net.wifi.WifiManager.PnoScanResultsCallback.REGISTER_PNO_CALLBACK_RESOURCE_BUSY;
import static android.net.wifi.WifiManager.PnoScanResultsCallback.REMOVE_PNO_CALLBACK_RESULTS_DELIVERED;
import static android.net.wifi.WifiManager.PnoScanResultsCallback.REMOVE_PNO_CALLBACK_UNREGISTERED;

import android.net.wifi.IPnoScanResultsCallback;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiSsid;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Manages PNO scan requests from apps.
 * This class is not thread safe and is expected to be run on a single thread.
 */
public class ExternalPnoScanRequestManager implements IBinder.DeathRecipient {
    private static final String TAG = "ExternalPnoScanRequestManager";
    private ExternalPnoScanRequest mCurrentRequest;
    private final Handler mHandler;

    /**
     * Creates a ExternalPnoScanRequestManager.
     * @param handler to run binder death callback.
     */
    public ExternalPnoScanRequestManager(Handler handler) {
        mHandler = handler;
    }

    /**
     * Returns a copy of the current SSIDs being requested for PNO scan.
     */
    public Set<String> getExternalPnoScanSsids() {
        return mCurrentRequest == null ? Collections.EMPTY_SET
                : new ArraySet<>(mCurrentRequest.mSsidStrings);
    }

    /**
     * Sets the request. This will fail if there's already a request set.
     */
    public boolean setRequest(int uid, IBinder binder, IPnoScanResultsCallback callback,
            List<WifiSsid> ssids) {
        if (mCurrentRequest != null) {
            // Already has existing request. Can't set a new one.
            try {
                callback.onRegisterFailed(uid == mCurrentRequest.mUid
                        ? REGISTER_PNO_CALLBACK_ALREADY_REGISTERED
                        : REGISTER_PNO_CALLBACK_RESOURCE_BUSY);
            } catch (RemoteException e) {
                Log.e(TAG, e.getMessage());
            }
            return false;
        }
        ExternalPnoScanRequest request = new ExternalPnoScanRequestManager.ExternalPnoScanRequest(
                uid, binder, callback, ssids);
        try {
            request.mBinder.linkToDeath(this, 0);
        } catch (RemoteException e) {
            Log.e(TAG, "mBinder.linkToDeath failed: " + e.getMessage());
            return false;
        }
        try {
            request.mCallback.onRegisterSuccess();
        } catch (RemoteException e) {
            Log.e(TAG, e.getMessage());
            return false;
        }
        mCurrentRequest = request;
        return true;
    }

    private void removeCurrentRequest() {
        if (mCurrentRequest != null) {
            try {
                mCurrentRequest.mBinder.unlinkToDeath(this, 0);
            } catch (NoSuchElementException e) {
                Log.e(TAG, e.getMessage());
            }
        }
        mCurrentRequest = null;
    }

    /**
     * Removes the requests. Will fail if the remover's uid and packageName does not match with the
     * creator's uid and packageName.
     */
    public boolean removeRequest(int uid) {
        if (mCurrentRequest == null || uid != mCurrentRequest.mUid) {
            return false;
        }

        try {
            mCurrentRequest.mCallback.onRemoved(REMOVE_PNO_CALLBACK_UNREGISTERED);
        } catch (RemoteException e) {
            Log.e(TAG, e.getMessage());
        }
        removeCurrentRequest();
        return true;
    }

    /**
     * Triggered when PNO networks are found. Any results matching the external request will be
     * sent to the callback.
     * @param results
     */
    public void onPnoNetworkFound(ScanResult[] results) {
        if (mCurrentRequest == null) {
            return;
        }
        List<ScanResult> requestedResults = new ArrayList<>();
        for (ScanResult result : results) {
            if (mCurrentRequest.mSsidStrings.contains(result.getWifiSsid().toString())) {
                requestedResults.add(result);
            }
        }
        if (requestedResults.isEmpty()) {
            return;
        }

        // requested PNO SSIDs found. Send results and then remove request.
        try {
            mCurrentRequest.mCallback.onScanResultsAvailable(requestedResults);
            mCurrentRequest.mCallback.onRemoved(REMOVE_PNO_CALLBACK_RESULTS_DELIVERED);
        } catch (RemoteException e) {
            Log.e(TAG, e.getMessage());
        }
        removeCurrentRequest();
    }

    /**
     * Tracks a request for PNO scan made by an app.
     */
    public static class ExternalPnoScanRequest {
        private int mUid;
        private Set<String> mSsidStrings;
        private IPnoScanResultsCallback mCallback;
        private IBinder mBinder;

        /**
         * @param uid identifies the caller
         * @param binder obtained from the caller
         * @param callback used to send results back to the caller
         * @param ssids requested SSIDs for PNO scan
         */
        public ExternalPnoScanRequest(int uid, IBinder binder, IPnoScanResultsCallback callback,
                List<WifiSsid> ssids) {
            mUid = uid;
            mBinder = binder;
            mCallback = callback;
            mSsidStrings = new ArraySet<>();
            for (WifiSsid wifiSsid : ssids) {
                mSsidStrings.add(wifiSsid.toString());
            }
        }
    }

    /**
     * Binder has died. Perform cleanup.
     */
    @Override
    public void binderDied() {
        mHandler.post(() -> removeCurrentRequest());
    }
}
