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
import android.content.Context;
import android.hardware.wifi.hostapd.ApInfo;
import android.hardware.wifi.hostapd.BandMask;
import android.hardware.wifi.hostapd.Bandwidth;
import android.hardware.wifi.hostapd.ChannelParams;
import android.hardware.wifi.hostapd.ClientInfo;
import android.hardware.wifi.hostapd.DebugLevel;
import android.hardware.wifi.hostapd.EncryptionType;
import android.hardware.wifi.hostapd.FrequencyRange;
import android.hardware.wifi.hostapd.Generation;
import android.hardware.wifi.hostapd.HwModeParams;
import android.hardware.wifi.hostapd.IHostapd;
import android.hardware.wifi.hostapd.IHostapdCallback;
import android.hardware.wifi.hostapd.Ieee80211ReasonCode;
import android.hardware.wifi.hostapd.IfaceParams;
import android.hardware.wifi.hostapd.NetworkParams;
import android.net.MacAddress;
import android.net.wifi.ScanResult;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.SoftApConfiguration.BandType;
import android.net.wifi.SoftApInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.build.SdkLevel;
import com.android.server.wifi.WifiNative.HostapdDeathEventHandler;
import com.android.server.wifi.util.ApConfigUtil;
import com.android.server.wifi.util.NativeUtil;
import com.android.wifi.resources.R;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.concurrent.ThreadSafe;

/**
 * To maintain thread-safety, the locking protocol is that every non-static method (regardless of
 * access level) acquires mLock.
 */
@ThreadSafe
/** The implementation of IHostapdHal which based on Stable AIDL interface */
public class HostapdHalAidlImp implements IHostapdHal {
    private static final String TAG = "HostapdHalAidlImp";
    private static final String HAL_INSTANCE_NAME = IHostapd.DESCRIPTOR + "/default";

    private final Object mLock = new Object();
    private boolean mVerboseLoggingEnabled = false;
    private boolean mVerboseHalLoggingEnabled = false;
    private final Context mContext;
    private final Handler mEventHandler;

    // Hostapd HAL interface objects
    private IHostapd mIHostapd;
    private HashMap<String, Runnable> mSoftApFailureListeners = new HashMap<>();
    private WifiNative.SoftApHalCallback mSoftApEventCallback;
    private Set<String> mActiveInstances = new HashSet<>();
    private HostapdDeathEventHandler mDeathEventHandler;
    private boolean mServiceDeclared = false;
    private HostapdDeathRecipient mIHostapdDeathRecipient;

    /**
     * Default death recipient. Called any time the service dies.
     */
    private class HostapdDeathRecipient implements DeathRecipient {
        @Override
        public void binderDied() {
            mEventHandler.post(() -> {
                synchronized (mLock) {
                    Log.w(TAG, "IHostapd/IHostapd died");
                    hostapdServiceDiedHandler();
                }
            });
        }
    }

    /**
     * Terminate death recipient. Linked to service death on call to terminate()
     */
    private class TerminateDeathRecipient implements DeathRecipient {
        @Override
        public void binderDied() {
            mEventHandler.post(() -> {
                synchronized (mLock) {
                    Log.w(TAG, "IHostapd/IHostapd was killed by terminate()");
                    // nothing more to be done here
                }
            });
        }
    }

    public HostapdHalAidlImp(@NonNull Context context, @NonNull Handler handler) {
        mContext = context;
        mEventHandler = handler;
        mIHostapdDeathRecipient = new HostapdDeathRecipient();
        Log.d(TAG, "init HostapdHalAidlImp");
    }

    /**
     * Enable/Disable verbose logging.
     *
     * @param enable true to enable, false to disable.
     */
    @Override
    public void enableVerboseLogging(boolean verboseEnabled, boolean halVerboseEnabled) {
        synchronized (mLock) {
            mVerboseLoggingEnabled = verboseEnabled;
            mVerboseHalLoggingEnabled = halVerboseEnabled;
            setDebugParams();
        }
    }

    /**
     * Returns whether or not the hostapd supports getting the AP info from the callback.
     */
    @Override
    public boolean isApInfoCallbackSupported() {
        // Supported in the AIDL implementation
        return true;
    }

    /**
     * Checks whether the IHostapd service is declared, and therefore should be available.
     * @return true if the IHostapd service is declared
     */
    @Override
    public boolean initialize() {
        synchronized (mLock) {
            if (mVerboseLoggingEnabled) {
                Log.i(TAG, "Checking if IHostapd service is declared.");
            }
            mServiceDeclared = serviceDeclared();
            return mServiceDeclared;
        }
    }

    /**
     * Register for callbacks with the hostapd service. On service-side event,
     * the hostapd service will trigger our IHostapdCallback implementation, which
     * in turn calls the proper SoftApHalCallback registered with us by WifiNative.
     */
    private boolean registerCallback(IHostapdCallback callback) {
        synchronized (mLock) {
            String methodStr = "registerCallback";
            try {
                mIHostapd.registerCallback(callback);
                return true;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
            } catch (ServiceSpecificException e) {
                handleServiceSpecificException(e, methodStr);
            }
            return false;
        }
    }

    /**
     * Register the provided callback handler for SoftAp events.
     * <p>
     * Note that only one callback can be registered at a time - any registration overrides previous
     * registrations.
     *
     * @param ifaceName Name of the interface.
     * @param listener Callback listener for AP events.
     * @return true on success, false on failure.
     */
    @Override
    public boolean registerApCallback(@NonNull String ifaceName,
            @NonNull WifiNative.SoftApHalCallback callback) {
        // TODO(b/195980798) : Create a hashmap to associate the listener with the ifaceName
        synchronized (mLock) {
            if (callback == null) {
                Log.e(TAG, "registerApCallback called with a null callback");
                return false;
            }
            mSoftApEventCallback = callback;
            Log.i(TAG, "registerApCallback Successful in " + ifaceName);
            return true;
        }
    }

    /**
     * Add and start a new access point.
     *
     * @param ifaceName Name of the interface.
     * @param config Configuration to use for the AP.
     * @param isMetered Indicates if the network is metered or not.
     * @param onFailureListener A runnable to be triggered on failure.
     * @return true on success, false otherwise.
     */
    @Override
    public boolean addAccessPoint(@NonNull String ifaceName, @NonNull SoftApConfiguration config,
            boolean isMetered, Runnable onFailureListener) {
        synchronized (mLock) {
            final String methodStr = "addAccessPoint";
            Log.d(TAG, methodStr + ": " + ifaceName);
            if (!checkHostapdAndLogFailure(methodStr)) {
                return false;
            }
            try {
                IfaceParams ifaceParams = prepareIfaceParams(ifaceName, config);
                NetworkParams nwParams = prepareNetworkParams(isMetered, config);
                if (ifaceParams == null || nwParams == null) {
                    Log.e(TAG, "addAccessPoint parameters could not be prepared.");
                    return false;
                }
                mIHostapd.addAccessPoint(ifaceParams, nwParams);
                mSoftApFailureListeners.put(ifaceName, onFailureListener);
                return true;
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Unrecognized apBand: " + config.getBand());
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
            } catch (ServiceSpecificException e) {
                handleServiceSpecificException(e, methodStr);
            }
            return false;
        }
    }

    /**
     * Remove a previously started access point.
     *
     * @param ifaceName Name of the interface.
     * @return true on success, false otherwise.
     */
    @Override
    public boolean removeAccessPoint(@NonNull String ifaceName) {
        synchronized (mLock) {
            final String methodStr = "removeAccessPoint";
            if (!checkHostapdAndLogFailure(methodStr)) {
                return false;
            }
            try {
                mSoftApFailureListeners.remove(ifaceName);
                mSoftApEventCallback = null;
                mIHostapd.removeAccessPoint(ifaceName);
                return true;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
            } catch (ServiceSpecificException e) {
                handleServiceSpecificException(e, methodStr);
            }
            return false;
        }
    }

    /**
     * Remove a previously connected client.
     *
     * @param ifaceName Name of the interface.
     * @param client Mac Address of the client.
     * @param reasonCode One of disconnect reason code which defined in {@link WifiManager}.
     * @return true on success, false otherwise.
     */
    @Override
    public boolean forceClientDisconnect(@NonNull String ifaceName,
            @NonNull MacAddress client, int reasonCode) {
        synchronized (mLock) {
            final String methodStr = "forceClientDisconnect";
            try {
                if (!checkHostapdAndLogFailure(methodStr)) {
                    return false;
                }
                byte[] clientMacByteArray = client.toByteArray();
                int disconnectReason;
                switch (reasonCode) {
                    case WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_BLOCKED_BY_USER:
                        disconnectReason = Ieee80211ReasonCode.WLAN_REASON_PREV_AUTH_NOT_VALID;
                        break;
                    case WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_NO_MORE_STAS:
                        disconnectReason = Ieee80211ReasonCode.WLAN_REASON_DISASSOC_AP_BUSY;
                        break;
                    case WifiManager.SAP_CLIENT_DISCONNECT_REASON_CODE_UNSPECIFIED:
                        disconnectReason = Ieee80211ReasonCode.WLAN_REASON_UNSPECIFIED;
                        break;
                    default:
                        throw new IllegalArgumentException(
                                "Unknown disconnect reason code:" + reasonCode);
                }
                mIHostapd.forceClientDisconnect(ifaceName, clientMacByteArray, disconnectReason);
                return true;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
            } catch (ServiceSpecificException e) {
                handleServiceSpecificException(e, methodStr);
            }
            return false;
        }
    }

    /**
     * Registers a death notification for hostapd.
     * @return Returns true on success.
     */
    @Override
    public boolean registerDeathHandler(@NonNull HostapdDeathEventHandler handler) {
        synchronized (mLock) {
            if (mDeathEventHandler != null) {
                Log.e(TAG, "Death handler already present");
            }
            mDeathEventHandler = handler;
            return true;
        }
    }

    /**
     * Deregisters a death notification for hostapd.
     * @return Returns true on success.
     */
    @Override
    public boolean deregisterDeathHandler() {
        synchronized (mLock) {
            if (mDeathEventHandler == null) {
                Log.e(TAG, "No Death handler present");
                return false;
            }
            mDeathEventHandler = null;
            return true;
        }
    }

    /**
     * Handle hostapd death.
     */
    private void hostapdServiceDiedHandler() {
        synchronized (mLock) {
            mIHostapd = null;
            if (mDeathEventHandler != null) {
                mDeathEventHandler.onDeath();
            }
        }
    }

    private class HostapdCallback extends IHostapdCallback.Stub {
        @Override
        public void onFailure(String ifaceName, String instanceName) {
            Log.w(TAG, "Failure on iface " + ifaceName + ", instance: " + instanceName);
            Runnable onFailureListener = mSoftApFailureListeners.get(ifaceName);
            if (onFailureListener != null) {
                mActiveInstances.remove(instanceName);
                if (mActiveInstances.size() == 0) {
                    onFailureListener.run();
                } else if (mSoftApEventCallback != null) {
                    mSoftApEventCallback.onInstanceFailure(instanceName);
                }
            }
        }

        @Override
        public void onApInstanceInfoChanged(ApInfo info) {
            Log.v(TAG, "onApInstanceInfoChanged on " + info.ifaceName + " / "
                    + info.apIfaceInstance);
            try {
                if (mSoftApEventCallback != null) {
                    mSoftApEventCallback.onInfoChanged(info.apIfaceInstance, info.freqMhz,
                            mapHalBandwidthToSoftApInfo(info.bandwidth),
                            mapHalGenerationToWifiStandard(info.generation),
                            MacAddress.fromBytes(info.apIfaceInstanceMacAddress));
                }
                mActiveInstances.add(info.apIfaceInstance);
            } catch (IllegalArgumentException iae) {
                Log.e(TAG, " Invalid apIfaceInstanceMacAddress, " + iae);
            }
        }

        @Override
        public void onConnectedClientsChanged(ClientInfo info) {
            try {
                Log.d(TAG, "onConnectedClientsChanged on " + info.ifaceName
                        + " / " + info.apIfaceInstance
                        + " and Mac is " + MacAddress.fromBytes(info.clientAddress).toString()
                        + " isConnected: " + info.isConnected);
                if (mSoftApEventCallback != null) {
                    mSoftApEventCallback.onConnectedClientsChanged(info.apIfaceInstance,
                            MacAddress.fromBytes(info.clientAddress), info.isConnected);
                }
            } catch (IllegalArgumentException iae) {
                Log.e(TAG, " Invalid clientAddress, " + iae);
            }
        }
    }

    /**
     * Signals whether Initialization started and found the declared service
     */
    @Override
    public boolean isInitializationStarted() {
        synchronized (mLock) {
            return mServiceDeclared;
        }
    }

    /**
     * Signals whether Initialization completed successfully.
     */
    @Override
    public boolean isInitializationComplete() {
        synchronized (mLock) {
            return mIHostapd != null;
        }
    }

    /**
     * Indicates whether the AIDL service is declared
     */
    public static boolean serviceDeclared() {
        // Service Manager API ServiceManager#isDeclared supported after T.
        if (!SdkLevel.isAtLeastT()) {
            return false;
        }
        return ServiceManager.isDeclared(HAL_INSTANCE_NAME);
    }

    /**
     * Wrapper functions created to be mockable in unit tests
     */
    @VisibleForTesting
    protected IBinder getServiceBinderMockable() {
        synchronized (mLock) {
            if (mIHostapd == null) return null;
            return mIHostapd.asBinder();
        }
    }

    @VisibleForTesting
    protected IHostapd getHostapdMockable() {
        synchronized (mLock) {
            return IHostapd.Stub.asInterface(
                    ServiceManager.waitForDeclaredService(HAL_INSTANCE_NAME));
        }
    }

    /**
     * Start hostapd daemon
     *
     * @return true when succeed, otherwise false.
     */
    @Override
    public boolean startDaemon() {
        synchronized (mLock) {
            final String methodStr = "startDaemon";
            mIHostapd = getHostapdMockable();
            if (mIHostapd == null) {
                Log.e(TAG, "Service hostapd wasn't found.");
                return false;
            }
            Log.i(TAG, "Obtained IHostApd binder.");

            try {
                IBinder serviceBinder = getServiceBinderMockable();
                if (serviceBinder == null) return false;
                serviceBinder.linkToDeath(mIHostapdDeathRecipient, /* flags= */ 0);
                setDebugParams();
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }

            if (!registerCallback(new HostapdCallback())) {
                Log.e(TAG, "Failed to register callback, stopping hostapd AIDL startup");
                mIHostapd = null;
                return false;
            }
            return true;
        }
    }

    /**
     * Terminate the hostapd daemon & register a DeathListener to confirm death
     */
    @Override
    public void terminate() {
        synchronized (mLock) {
            // Register a new death listener to confirm that terminate() killed hostapd
            final String methodStr = "terminate";
            if (!checkHostapdAndLogFailure(methodStr)) {
                return;
            }
            try {
                IBinder serviceBinder = getServiceBinderMockable();
                if (serviceBinder == null) return;
                serviceBinder.linkToDeath(new TerminateDeathRecipient(), /* flags= */ 0);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to register death recipient", e);
                handleRemoteException(e, methodStr);
                return;
            }

            try {
                mIHostapd.terminate();
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
            }
        }
    }

    private void handleRemoteException(RemoteException e, String methodStr) {
        synchronized (mLock) {
            hostapdServiceDiedHandler();
            Log.e(TAG, "IHostapd." + methodStr + " failed with exception", e);
        }
    }

    /**
     * Set the debug log level for hostapd.
     *
     * @return true if request is sent successfully, false otherwise.
     */
    private boolean setDebugParams() {
        synchronized (mLock) {
            final String methodStr = "setDebugParams";
            if (!checkHostapdAndLogFailure(methodStr)) {
                return false;
            }
            try {
                mIHostapd.setDebugParams(mVerboseHalLoggingEnabled
                        ? DebugLevel.DEBUG : DebugLevel.INFO);
                return true;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
            } catch (ServiceSpecificException e) {
                handleServiceSpecificException(e, methodStr);
            }
            return false;
        }
    }

    private static int getEncryptionType(SoftApConfiguration localConfig) {
        int encryptionType;
        switch (localConfig.getSecurityType()) {
            case SoftApConfiguration.SECURITY_TYPE_OPEN:
                encryptionType = EncryptionType.NONE;
                break;
            case SoftApConfiguration.SECURITY_TYPE_WPA2_PSK:
                encryptionType = EncryptionType.WPA2;
                break;
            case SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION:
                encryptionType = EncryptionType.WPA3_SAE_TRANSITION;
                break;
            case SoftApConfiguration.SECURITY_TYPE_WPA3_SAE:
                encryptionType = EncryptionType.WPA3_SAE;
                break;
            default:
                // We really shouldn't default to None, but this was how NetworkManagementService
                // used to do this.
                encryptionType = EncryptionType.NONE;
                break;
        }
        return encryptionType;
    }

    private static int getHalBandMask(int apBand) throws IllegalArgumentException {
        int bandMask = 0;

        if (!ApConfigUtil.isBandValid(apBand)) {
            throw new IllegalArgumentException();
        }

        if (ApConfigUtil.containsBand(apBand, SoftApConfiguration.BAND_2GHZ)) {
            bandMask |= BandMask.BAND_2_GHZ;
        }
        if (ApConfigUtil.containsBand(apBand, SoftApConfiguration.BAND_5GHZ)) {
            bandMask |= BandMask.BAND_5_GHZ;
        }
        if (ApConfigUtil.containsBand(apBand, SoftApConfiguration.BAND_6GHZ)) {
            bandMask |= BandMask.BAND_6_GHZ;
        }
        if (ApConfigUtil.containsBand(apBand, SoftApConfiguration.BAND_60GHZ)) {
            bandMask |= BandMask.BAND_60_GHZ;
        }

        return bandMask;
    }

   /**
     * Prepare the acsChannelFreqRangesMhz in ChannelParams.
     */
    private void prepareAcsChannelFreqRangesMhz(ChannelParams channelParams,
            @BandType int band) {
        List<FrequencyRange> ranges = new ArrayList<>();
        if ((band & SoftApConfiguration.BAND_2GHZ) != 0) {
            ranges.addAll(toAcsFreqRanges(SoftApConfiguration.BAND_2GHZ));
        }
        if ((band & SoftApConfiguration.BAND_5GHZ) != 0) {
            ranges.addAll(toAcsFreqRanges(SoftApConfiguration.BAND_5GHZ));
        }
        if ((band & SoftApConfiguration.BAND_6GHZ) != 0) {
            ranges.addAll(toAcsFreqRanges(SoftApConfiguration.BAND_6GHZ));
        }
        channelParams.acsChannelFreqRangesMhz = ranges.toArray(
                new FrequencyRange[ranges.size()]);
    }

    /**
     * Convert channel list string like '1-6,11' to list of FreqRange
     */
    private List<FrequencyRange> toAcsFreqRanges(@BandType int band) {
        List<FrequencyRange> FrequencyRanges = new ArrayList<>();

        if (!ApConfigUtil.isBandValid(band) || ApConfigUtil.isMultiband(band)) {
            Log.e(TAG, "Invalid band : " + band);
            return FrequencyRanges;
        }

        String channelListStr;
        switch (band) {
            case SoftApConfiguration.BAND_2GHZ:
                channelListStr = mContext.getResources().getString(
                        R.string.config_wifiSoftap2gChannelList);
                if (TextUtils.isEmpty(channelListStr)) {
                    channelListStr = ScanResult.BAND_24_GHZ_FIRST_CH_NUM + "-"
                            + ScanResult.BAND_24_GHZ_LAST_CH_NUM;
                }
                break;
            case SoftApConfiguration.BAND_5GHZ:
                channelListStr = mContext.getResources().getString(
                        R.string.config_wifiSoftap5gChannelList);
                if (TextUtils.isEmpty(channelListStr)) {
                    channelListStr = ScanResult.BAND_5_GHZ_FIRST_CH_NUM + "-"
                            + ScanResult.BAND_5_GHZ_LAST_CH_NUM;
                }
                break;
            case SoftApConfiguration.BAND_6GHZ:
                channelListStr = mContext.getResources().getString(
                        R.string.config_wifiSoftap6gChannelList);
                if (TextUtils.isEmpty(channelListStr)) {
                    channelListStr = ScanResult.BAND_6_GHZ_FIRST_CH_NUM + "-"
                            + ScanResult.BAND_6_GHZ_LAST_CH_NUM;
                }
                break;
            default:
                return FrequencyRanges;
        }

        for (String channelRange : channelListStr.split(",")) {
            FrequencyRange freqRange = new FrequencyRange();
            try {
                if (channelRange.contains("-")) {
                    String[] channels  = channelRange.split("-");
                    if (channels.length != 2) {
                        Log.e(TAG, "Unrecognized channel range, length is " + channels.length);
                        continue;
                    }
                    int start = Integer.parseInt(channels[0].trim());
                    int end = Integer.parseInt(channels[1].trim());
                    if (start > end) {
                        Log.e(TAG, "Invalid channel range, from " + start + " to " + end);
                        continue;
                    }
                    freqRange.startMhz =
                            ApConfigUtil.convertChannelToFrequency(start, band);
                    freqRange.endMhz = ApConfigUtil.convertChannelToFrequency(end, band);
                } else if (!TextUtils.isEmpty(channelRange)) {
                    int channel = Integer.parseInt(channelRange.trim());
                    freqRange.startMhz =
                            ApConfigUtil.convertChannelToFrequency(channel, band);
                    freqRange.endMhz = freqRange.startMhz;
                }
            } catch (NumberFormatException e) {
                // Ignore malformed value
                Log.e(TAG, "Malformed channel value detected: " + e);
                continue;
            }
            FrequencyRanges.add(freqRange);
        }
        return FrequencyRanges;
    }

    /**
     * Map hal bandwidth to SoftApInfo.
     *
     * @param bandwidth The channel bandwidth of the AP which is defined in the HAL.
     * @return The channel bandwidth in the SoftApinfo.
     */
    @VisibleForTesting
    public int mapHalBandwidthToSoftApInfo(int bandwidth) {
        switch (bandwidth) {
            case Bandwidth.BANDWIDTH_20_NOHT:
                return SoftApInfo.CHANNEL_WIDTH_20MHZ_NOHT;
            case Bandwidth.BANDWIDTH_20:
                return SoftApInfo.CHANNEL_WIDTH_20MHZ;
            case Bandwidth.BANDWIDTH_40:
                return SoftApInfo.CHANNEL_WIDTH_40MHZ;
            case Bandwidth.BANDWIDTH_80:
                return SoftApInfo.CHANNEL_WIDTH_80MHZ;
            case Bandwidth.BANDWIDTH_80P80:
                return SoftApInfo.CHANNEL_WIDTH_80MHZ_PLUS_MHZ;
            case Bandwidth.BANDWIDTH_160:
                return SoftApInfo.CHANNEL_WIDTH_160MHZ;
            case Bandwidth.BANDWIDTH_2160:
                return SoftApInfo.CHANNEL_WIDTH_2160MHZ;
            case Bandwidth.BANDWIDTH_4320:
                return SoftApInfo.CHANNEL_WIDTH_4320MHZ;
            case Bandwidth.BANDWIDTH_6480:
                return SoftApInfo.CHANNEL_WIDTH_6480MHZ;
            case Bandwidth.BANDWIDTH_8640:
                return SoftApInfo.CHANNEL_WIDTH_8640MHZ;
            default:
                return SoftApInfo.CHANNEL_WIDTH_INVALID;
        }
    }

    /**
     * Map hal generation to wifi standard.
     *
     * @param generation The operation mode of the AP which is defined in HAL.
     * @return The wifi standard in the ScanResult.
     */
    @VisibleForTesting
    public int mapHalGenerationToWifiStandard(int generation) {
        switch (generation) {
            case Generation.WIFI_STANDARD_LEGACY:
                return ScanResult.WIFI_STANDARD_LEGACY;
            case Generation.WIFI_STANDARD_11N:
                return ScanResult.WIFI_STANDARD_11N;
            case Generation.WIFI_STANDARD_11AC:
                return ScanResult.WIFI_STANDARD_11AC;
            case Generation.WIFI_STANDARD_11AX:
                return ScanResult.WIFI_STANDARD_11AX;
            case Generation.WIFI_STANDARD_11AD:
                return ScanResult.WIFI_STANDARD_11AD;
            default:
                return ScanResult.WIFI_STANDARD_UNKNOWN;
        }
    }

    private NetworkParams prepareNetworkParams(boolean isMetered,
            SoftApConfiguration config) {
        NetworkParams nwParams = new NetworkParams();
        ArrayList<Byte> ssid = NativeUtil.byteArrayToArrayList(config.getWifiSsid().getBytes());
        nwParams.ssid = new byte[ssid.size()];
        for (int i = 0; i < ssid.size(); i++) {
            nwParams.ssid[i] = ssid.get(i);
        }

        final List<ScanResult.InformationElement> elements = config.getVendorElementsInternal();
        int totalLen = 0;
        for (ScanResult.InformationElement e : elements) {
            totalLen += 2 + e.bytes.length; // 1 byte ID + 1 byte payload len + payload
        }
        nwParams.vendorElements = new byte[totalLen];
        int i = 0;
        for (ScanResult.InformationElement e : elements) {
            nwParams.vendorElements[i++] = (byte) e.id;
            nwParams.vendorElements[i++] = (byte) e.bytes.length;
            for (int j = 0; j < e.bytes.length; j++) {
                nwParams.vendorElements[i++] = e.bytes[j];
            }
        }

        nwParams.isMetered = isMetered;
        nwParams.isHidden = config.isHiddenSsid();
        nwParams.encryptionType = getEncryptionType(config);
        nwParams.passphrase = (config.getPassphrase() != null)
                    ? config.getPassphrase() : "";

        if (nwParams.ssid == null || nwParams.passphrase == null) {
            return null;
        }
        return nwParams;
    }

    private IfaceParams prepareIfaceParams(String ifaceName, SoftApConfiguration config)
            throws IllegalArgumentException {
        IfaceParams ifaceParams = new IfaceParams();
        ifaceParams.name = ifaceName;
        ifaceParams.hwModeParams = prepareHwModeParams(config);
        ifaceParams.channelParams = prepareChannelParamsList(config);
        if (ifaceParams.name == null || ifaceParams.hwModeParams == null
                || ifaceParams.channelParams == null) {
            return null;
        }
        return ifaceParams;
    }

    private HwModeParams prepareHwModeParams(SoftApConfiguration config) {
        HwModeParams hwModeParams = new HwModeParams();
        hwModeParams.enable80211N = true;
        hwModeParams.enable80211AC = mContext.getResources().getBoolean(
                R.bool.config_wifi_softap_ieee80211ac_supported);
        hwModeParams.enable80211AX = mContext.getResources().getBoolean(
                R.bool.config_wifiSoftapIeee80211axSupported);
        //Update 80211ax support with the configuration.
        hwModeParams.enable80211AX &= config.isIeee80211axEnabledInternal();
        hwModeParams.enable6GhzBand = ApConfigUtil.isBandSupported(
                SoftApConfiguration.BAND_6GHZ, mContext);
        hwModeParams.enableHeSingleUserBeamformer = mContext.getResources().getBoolean(
                R.bool.config_wifiSoftapHeSuBeamformerSupported);
        hwModeParams.enableHeSingleUserBeamformee = mContext.getResources().getBoolean(
                R.bool.config_wifiSoftapHeSuBeamformeeSupported);
        hwModeParams.enableHeMultiUserBeamformer = mContext.getResources().getBoolean(
                R.bool.config_wifiSoftapHeMuBeamformerSupported);
        hwModeParams.enableHeTargetWakeTime = mContext.getResources().getBoolean(
                R.bool.config_wifiSoftapHeTwtSupported);
        return hwModeParams;
    }

    private ChannelParams[] prepareChannelParamsList(SoftApConfiguration config)
            throws IllegalArgumentException {
        int nChannels = 1;
        if (SdkLevel.isAtLeastS()) {
            nChannels = config.getChannels().size();
        }
        ChannelParams[] channelParamsList = new ChannelParams[nChannels];
        for (int i = 0; i < nChannels; i++) {
            int band = config.getBand();
            int channel = config.getChannel();
            if (SdkLevel.isAtLeastS()) {
                band = config.getChannels().keyAt(i);
                channel = config.getChannels().valueAt(i);
            }
            channelParamsList[i] = new ChannelParams();
            channelParamsList[i].channel = channel;
            channelParamsList[i].enableAcs = ApConfigUtil.isAcsSupported(mContext)
                    && channel == 0;
            channelParamsList[i].bandMask = getHalBandMask(band);
            channelParamsList[i].acsChannelFreqRangesMhz = new FrequencyRange[0];
            if (channelParamsList[i].enableAcs) {
                channelParamsList[i].acsShouldExcludeDfs = !mContext.getResources()
                        .getBoolean(R.bool.config_wifiSoftapAcsIncludeDfs);
                if (ApConfigUtil.isSendFreqRangesNeeded(band, mContext)) {
                    prepareAcsChannelFreqRangesMhz(channelParamsList[i], band);
                }
            }
            if (channelParamsList[i].acsChannelFreqRangesMhz == null) {
                return null;
            }
        }
        return channelParamsList;
    }

    /**
     * Returns false if Hostapd is null, and logs failure to call methodStr
     */
    private boolean checkHostapdAndLogFailure(String methodStr) {
        synchronized (mLock) {
            if (mIHostapd == null) {
                Log.e(TAG, "Can't call " + methodStr + ", IHostapd is null");
                return false;
            }
            return true;
        }
    }

    /**
     * Logs failure for a service specific exception. Error codes are defined in HostapdStatusCode
     */
    private void handleServiceSpecificException(
            ServiceSpecificException exception, String methodStr) {
        synchronized (mLock) {
            Log.e(TAG, "IHostapd." + methodStr + " failed: " + exception.toString());
        }
    }

    /**
     * Dump information about the AIDL implementation.
     *
     * TODO (b/202302891) Log version information once we freeze the AIDL interface
     */
    public void dump(PrintWriter pw) {
        pw.println("AIDL interface version: 1 (initial)");
    }
}
