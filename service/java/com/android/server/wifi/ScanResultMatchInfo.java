/*
 * Copyright (C) 2017 The Android Open Source Project
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
import android.annotation.Nullable;
import android.net.wifi.ScanResult;
import android.net.wifi.SecurityParams;
import android.net.wifi.WifiConfiguration;

import com.android.server.wifi.util.ScanResultUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Class to store the info needed to match a scan result to the provided network configuration.
 */
public class ScanResultMatchInfo {
    /**
     * SSID of the network.
     */
    public String networkSsid;
    /**
     * Security params list.
     */
    public List<SecurityParams> securityParamsList = new ArrayList<>();

    /**
     * True if created from a scan result
     */
    private boolean mFromScanResult = false;

    /**
     * Get the ScanResultMatchInfo for the given WifiConfiguration
     */
    public static ScanResultMatchInfo fromWifiConfiguration(WifiConfiguration config) {
        ScanResultMatchInfo info = new ScanResultMatchInfo();
        info.networkSsid = config.SSID;
        info.securityParamsList = config.getSecurityParamsList();
        return info;
    }

    private static List<SecurityParams> generateSecurityParamsListFromScanResult(
            ScanResult scanResult) {
        List<SecurityParams> list = new ArrayList<>();

        // Open network & its upgradable types
        if (ScanResultUtil.isScanResultForOweTransitionNetwork(scanResult)) {
            list.add(SecurityParams.createSecurityParamsBySecurityType(
                    WifiConfiguration.SECURITY_TYPE_OPEN));
            list.add(SecurityParams.createSecurityParamsBySecurityType(
                    WifiConfiguration.SECURITY_TYPE_OWE));
            return list;
        } else if (ScanResultUtil.isScanResultForOweNetwork(scanResult)) {
            list.add(SecurityParams.createSecurityParamsBySecurityType(
                    WifiConfiguration.SECURITY_TYPE_OWE));
            return list;
        } else if (ScanResultUtil.isScanResultForOpenNetwork(scanResult)) {
            list.add(SecurityParams.createSecurityParamsBySecurityType(
                    WifiConfiguration.SECURITY_TYPE_OPEN));
            return list;
        }

        // WEP network which has no upgradable type
        if (ScanResultUtil.isScanResultForWepNetwork(scanResult)) {
            list.add(SecurityParams.createSecurityParamsBySecurityType(
                    WifiConfiguration.SECURITY_TYPE_WEP));
            return list;
        }

        // WAPI PSK network which has no upgradable type
        if (ScanResultUtil.isScanResultForWapiPskNetwork(scanResult)) {
            list.add(SecurityParams.createSecurityParamsBySecurityType(
                    WifiConfiguration.SECURITY_TYPE_WAPI_PSK));
            return list;
        }

        // WAPI CERT network which has no upgradable type
        if (ScanResultUtil.isScanResultForWapiCertNetwork(scanResult)) {
            list.add(SecurityParams.createSecurityParamsBySecurityType(
                    WifiConfiguration.SECURITY_TYPE_WAPI_CERT));
            return list;
        }

        // WPA2 personal network & its upgradable types
        if (ScanResultUtil.isScanResultForPskNetwork(scanResult)
                && ScanResultUtil.isScanResultForSaeNetwork(scanResult)) {
            list.add(SecurityParams.createSecurityParamsBySecurityType(
                    WifiConfiguration.SECURITY_TYPE_PSK));
            list.add(SecurityParams.createSecurityParamsBySecurityType(
                    WifiConfiguration.SECURITY_TYPE_SAE));
            return list;
        } else if (ScanResultUtil.isScanResultForPskNetwork(scanResult)) {
            list.add(SecurityParams.createSecurityParamsBySecurityType(
                    WifiConfiguration.SECURITY_TYPE_PSK));
            return list;
        } else if (ScanResultUtil.isScanResultForSaeNetwork(scanResult)) {
            list.add(SecurityParams.createSecurityParamsBySecurityType(
                    WifiConfiguration.SECURITY_TYPE_SAE));
            return list;
        }

        // WPA3 Enterprise 192-bit mode
        if (ScanResultUtil.isScanResultForEapSuiteBNetwork(scanResult)) {
            list.add(SecurityParams.createSecurityParamsBySecurityType(
                    WifiConfiguration.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT));
            return list;
        }

        // WPA2/WPA3 enterprise network & its upgradable types
        if (ScanResultUtil.isScanResultForWpa3EnterpriseTransitionNetwork(scanResult)) {
            list.add(SecurityParams.createSecurityParamsBySecurityType(
                    WifiConfiguration.SECURITY_TYPE_EAP));
            list.add(SecurityParams.createSecurityParamsBySecurityType(
                    WifiConfiguration.SECURITY_TYPE_EAP_WPA3_ENTERPRISE));
            return list;
        } else if (ScanResultUtil.isScanResultForWpa3EnterpriseOnlyNetwork(scanResult)) {
            list.add(SecurityParams.createSecurityParamsBySecurityType(
                    WifiConfiguration.SECURITY_TYPE_EAP_WPA3_ENTERPRISE));
            return list;
        } else if (ScanResultUtil.isScanResultForEapNetwork(scanResult)) {
            list.add(SecurityParams.createSecurityParamsBySecurityType(
                    WifiConfiguration.SECURITY_TYPE_EAP));
            return list;
        }
        throw new IllegalArgumentException("Invalid ScanResult: " + scanResult);
    }

    /**
     * Get the ScanResultMatchInfo for the given ScanResult
     */
    public static ScanResultMatchInfo fromScanResult(ScanResult scanResult) {
        ScanResultMatchInfo info = new ScanResultMatchInfo();
        // Scan result ssid's are not quoted, hence add quotes.
        // TODO: This matching algo works only if the scan result contains a string SSID.
        // However, according to our public documentation ths {@link WifiConfiguration#SSID} can
        // either have a hex string or quoted ASCII string SSID.
        info.networkSsid = ScanResultUtil.createQuotedSSID(scanResult.SSID);
        info.securityParamsList = generateSecurityParamsListFromScanResult(scanResult);
        info.mFromScanResult = true;
        return info;
    }

    /**
     * The matching algorithm is that the type with a bigger index in the allowed
     * params list has the higher priority. We try to match each type from the end of
     * the allowed params list against the params in the scan result params list.
     *
     * There are three cases which will skip the match:
     * 1. the security type is different.
     * 2. the params is disabled, ex. disabled by Transition Disable Indication.
     * 3. The params is added by the auto-upgrade mechanism, but the corresponding
     *    feature is not enabled.
     */
    private static @Nullable SecurityParams findBestMatchingSecurityParams(
            List<SecurityParams> allowedParamsList,
            List<SecurityParams> scanResultParamsList) {
        if (null == allowedParamsList) return null;
        if (null == scanResultParamsList) return null;
        for (int i = allowedParamsList.size() - 1; i >= 0; i--) {
            SecurityParams allowedParams = allowedParamsList.get(i);

            if (!WifiConfigurationUtil.isSecurityParamsValid(allowedParams)) continue;

            for (SecurityParams scanResultParams: scanResultParamsList) {
                if (!allowedParams.isSecurityType(scanResultParams.getSecurityType())) {
                    continue;
                }
                return allowedParams;
            }
        }
        return null;
    }

    /**
     * Get the best-matching security type between ScanResult and WifiConifiguration.
     */
    public static @Nullable SecurityParams getBestMatchingSecurityParams(
            WifiConfiguration config,
            ScanResult scanResult) {
        if (null == config || null == scanResult) return null;

        return findBestMatchingSecurityParams(
                config.getSecurityParamsList(),
                generateSecurityParamsListFromScanResult(scanResult));
    }

    public @Nullable SecurityParams getDefaultSecurityParams() {
        return securityParamsList.isEmpty() ? null : securityParamsList.get(0);
    }

    public @Nullable SecurityParams getFirstAvailableSecurityParams() {
        return securityParamsList.stream()
                .filter(WifiConfigurationUtil::isSecurityParamsValid)
                .findFirst()
                .orElse(null);
    }

    /**
     * Checks for equality of network type.
     */
    public boolean networkTypeEquals(@NonNull ScanResultMatchInfo other) {
        if (null == securityParamsList || null == other.securityParamsList) return false;

        // If both are from the same sources, do normal comparison.
        if (mFromScanResult == other.mFromScanResult) {
            return securityParamsList.equals(other.securityParamsList);
        }

        final List<SecurityParams> allowedParamsList = mFromScanResult
                ? other.securityParamsList : securityParamsList;
        final List<SecurityParams> scanResultParamsList = mFromScanResult
                ? securityParamsList : other.securityParamsList;

        return null != findBestMatchingSecurityParams(
                allowedParamsList,
                scanResultParamsList);
    }

    @Override
    public boolean equals(Object otherObj) {
        if (this == otherObj) {
            return true;
        } else if (!(otherObj instanceof ScanResultMatchInfo)) {
            return false;
        }
        ScanResultMatchInfo other = (ScanResultMatchInfo) otherObj;
        if (mFromScanResult == other.mFromScanResult) {
            return Objects.equals(networkSsid, other.networkSsid)
                    && securityParamsList.equals(other.securityParamsList);
        }
        return null != matchForNetworkSelection(other);
    }

    /**
     * Match two ScanResultMatchInfo objects while considering configuration in overlays
     *
     * @param other Other object to compare against
     * @return return best matching security params, null if no matching one.
     */
    public SecurityParams matchForNetworkSelection(ScanResultMatchInfo other) {
        if (!Objects.equals(networkSsid, other.networkSsid)) return null;
        if (null == securityParamsList) return null;
        if (null == other.securityParamsList) return null;

        final List<SecurityParams> allowedParamsList = mFromScanResult
                ? other.securityParamsList : securityParamsList;
        final List<SecurityParams> scanResultParamsList = mFromScanResult
                ? securityParamsList : other.securityParamsList;

        return findBestMatchingSecurityParams(
                allowedParamsList,
                scanResultParamsList);
    }

    @Override
    public int hashCode() {
        return Objects.hash(networkSsid);
    }

    @Override
    public String toString() {
        StringBuffer sbuf = new StringBuffer();
        sbuf.append("ScanResultMatchInfo: SSID: ").append(networkSsid);
        sbuf.append(", from scan result: ").append(mFromScanResult);
        sbuf.append(", SecurityParams List:");
        securityParamsList.stream()
                .forEach(params -> sbuf.append(params.toString()));
        return sbuf.toString();
    }
}
