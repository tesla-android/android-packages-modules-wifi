/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.NonNull;
import android.hardware.wifi.supplicant.GsmRand;
import android.hardware.wifi.supplicant.ISupplicantStaNetworkCallback;
import android.hardware.wifi.supplicant.NetworkRequestEapSimGsmAuthParams;
import android.hardware.wifi.supplicant.NetworkRequestEapSimUmtsAuthParams;
import android.hardware.wifi.supplicant.TransitionDisableIndication;

import com.android.server.wifi.util.NativeUtil;

class SupplicantStaNetworkCallbackAidlImpl extends ISupplicantStaNetworkCallback.Stub {
    private final SupplicantStaNetworkHalAidlImpl mNetworkHal;
    /**
     * Current configured network's framework network id.
     */
    private final int mFrameworkNetworkId;
    /**
     * Current configured network's ssid.
     */
    private final String mSsid;
    private final String mIfaceName;
    private final WifiMonitor mWifiMonitor;
    private final Object mLock;

    SupplicantStaNetworkCallbackAidlImpl(
            @NonNull SupplicantStaNetworkHalAidlImpl networkHal,
            int frameworkNetworkId, @NonNull String ssid,
            @NonNull String ifaceName, @NonNull Object lock, @NonNull WifiMonitor wifiMonitor) {
        mNetworkHal = networkHal;
        mFrameworkNetworkId = frameworkNetworkId;
        mSsid = ssid;
        mIfaceName = ifaceName;
        mLock = lock;
        mWifiMonitor = wifiMonitor;
    }

    @Override
    public void onNetworkEapSimGsmAuthRequest(NetworkRequestEapSimGsmAuthParams params) {
        synchronized (mLock) {
            mNetworkHal.logCallback("onNetworkEapSimGsmAuthRequest");
            String[] data = new String[params.rands.length];
            int i = 0;
            for (GsmRand rand : params.rands) {
                data[i++] = NativeUtil.hexStringFromByteArray(rand.data);
            }
            mWifiMonitor.broadcastNetworkGsmAuthRequestEvent(
                    mIfaceName, mFrameworkNetworkId, mSsid, data);
        }
    }

    @Override
    public void onNetworkEapSimUmtsAuthRequest(NetworkRequestEapSimUmtsAuthParams params) {
        synchronized (mLock) {
            mNetworkHal.logCallback("onNetworkEapSimUmtsAuthRequest");
            String randHex = NativeUtil.hexStringFromByteArray(params.rand);
            String autnHex = NativeUtil.hexStringFromByteArray(params.autn);
            String[] data = {randHex, autnHex};
            mWifiMonitor.broadcastNetworkUmtsAuthRequestEvent(
                    mIfaceName, mFrameworkNetworkId, mSsid, data);
        }
    }

    @Override
    public void onNetworkEapIdentityRequest() {
        synchronized (mLock) {
            mNetworkHal.logCallback("onNetworkEapIdentityRequest");
            mWifiMonitor.broadcastNetworkIdentityRequestEvent(
                    mIfaceName, mFrameworkNetworkId, mSsid);
        }
    }

    @Override
    public void onTransitionDisable(int indicationBits) {
        synchronized (mLock) {
            mNetworkHal.logCallback("onTransitionDisable");
            int frameworkBits = 0;
            if ((indicationBits & TransitionDisableIndication.USE_WPA3_PERSONAL) != 0) {
                frameworkBits |= WifiMonitor.TDI_USE_WPA3_PERSONAL;
            }
            if ((indicationBits & TransitionDisableIndication.USE_SAE_PK) != 0) {
                frameworkBits |= WifiMonitor.TDI_USE_SAE_PK;
            }
            if ((indicationBits & TransitionDisableIndication.USE_WPA3_ENTERPRISE) != 0) {
                frameworkBits |= WifiMonitor.TDI_USE_WPA3_ENTERPRISE;
            }
            if ((indicationBits & TransitionDisableIndication.USE_ENHANCED_OPEN) != 0) {
                frameworkBits |= WifiMonitor.TDI_USE_ENHANCED_OPEN;
            }
            if (frameworkBits == 0) {
                return;
            }

            mWifiMonitor.broadcastTransitionDisableEvent(
                    mIfaceName, mFrameworkNetworkId, frameworkBits);
        }
    }
}
