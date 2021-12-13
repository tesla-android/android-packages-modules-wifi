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

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.wifi.WifiContext;
import android.net.wifi.WifiManager;
import android.os.UserHandle;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Class to manage launching dialogs via WifiDialog and returning the user reply.
 */
public class WifiDialogManager {
    private static final String TAG = "WifiDialogManager";
    public static final String WIFI_DIALOG_ACTIVITY_CLASSNAME =
            "com.android.wifi.dialog.WifiDialogActivity";

    private boolean mVerboseLoggingEnabled;

    private int mNextDialogId = 0;
    private @NonNull SparseArray<P2pInvitationReceivedDialogCallback>
            mP2pInvitationReceivedDialogCallbacks = new SparseArray<>();

    @NonNull WifiContext mContext;
    @NonNull PackageManager mPackageManager;

    public WifiDialogManager(WifiContext context) {
        mContext = context;
        mPackageManager = context.getPackageManager();
    }

    /**
     * Enables verbose logging.
     */
    public void enableVerboseLogging(boolean enabled) {
        mVerboseLoggingEnabled = enabled;
    }

    private int getNextDialogId() {
        if (mNextDialogId < 0) {
            mNextDialogId = 0;
        }
        return mNextDialogId++;
    }

    /**
     * Callback for receiving P2P Invitation Received dialog responses.
     */
    public interface P2pInvitationReceivedDialogCallback {
        /**
         * Invitation was accepted.
         * @param optionalPin Optional PIN if a PIN was requested, or {@code null} otherwise.
         */
        void onAccepted(@Nullable String optionalPin);

        /**
         * Invitation was declined or cancelled.
         */
        void onDeclined();
    }

    /**
     * Launches a P2P Invitation Received dialog.
     * @param deviceName Name of the device sending the invitation.
     * @param isPinRequested True if a PIN was requested and a PIN input UI should be shown.
     * @param displayPin Display PIN, or {@code null} if no PIN should be displayed
     * @param callback Callback to receive the dialog response. Runs on the main Wi-Fi thread.
     * @return id of the launched dialog, or {@code -1} if the dialog could not be created.
     */
    public int launchP2pInvitationReceivedDialog(
            String deviceName,
            boolean isPinRequested,
            @Nullable String displayPin,
            @NonNull P2pInvitationReceivedDialogCallback callback) {
        if (callback == null) {
            Log.e(TAG, "Cannot launch a P2P Invitation Received dialog with null callback!");
            return -1;
        }
        int dialogId = getNextDialogId();
        Intent intent = new Intent();
        String wifiDialogApkPkgName = mContext.getWifiDialogApkPkgName();
        if (wifiDialogApkPkgName == null) {
            Log.e(TAG, "Tried to launch P2P Invitation Received dialog but could not find a"
                    + " WifiDialog apk package name!");
            return -1;
        }
        intent.setClassName(wifiDialogApkPkgName, WIFI_DIALOG_ACTIVITY_CLASSNAME);
        intent.putExtra(WifiManager.EXTRA_DIALOG_TYPE,
                WifiManager.DIALOG_TYPE_P2P_INVITATION_RECEIVED);
        intent.putExtra(WifiManager.EXTRA_DIALOG_ID, dialogId);
        intent.putExtra(WifiManager.EXTRA_P2P_DEVICE_NAME, deviceName);
        intent.putExtra(WifiManager.EXTRA_P2P_PIN_REQUESTED, isPinRequested);
        intent.putExtra(WifiManager.EXTRA_P2P_DISPLAY_PIN, displayPin);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mP2pInvitationReceivedDialogCallbacks.put(dialogId, callback);
        mContext.startActivityAsUser(intent, UserHandle.CURRENT);
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Launching P2P Invitation Received dialog."
                    + " id=" + dialogId
                    + " deviceName=" + deviceName
                    + " isPinRequested=" + isPinRequested
                    + " displayPin=" + displayPin
                    + " callback=" + callback);
        }
        return dialogId;
    }
}
