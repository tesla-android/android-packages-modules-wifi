/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.net.wifi;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.os.Parcel;
import android.telephony.SubscriptionManager;

import androidx.test.filters.SmallTest;

import com.android.modules.utils.build.SdkLevel;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link android.net.wifi.WifiInfo}.
 */
@SmallTest
public class WifiInfoTest {
    private static final long TEST_TX_SUCCESS = 1;
    private static final long TEST_TX_RETRIES = 2;
    private static final long TEST_TX_BAD = 3;
    private static final long TEST_RX_SUCCESS = 4;
    private static final String TEST_PACKAGE_NAME = "com.test.example";
    private static final String TEST_FQDN = "test.com";
    private static final String TEST_PROVIDER_NAME = "test";
    private static final int TEST_WIFI_STANDARD = ScanResult.WIFI_STANDARD_11AC;
    private static final int TEST_MAX_SUPPORTED_TX_LINK_SPEED_MBPS = 866;
    private static final int TEST_MAX_SUPPORTED_RX_LINK_SPEED_MBPS = 1200;
    private static final String TEST_SSID = "Test123";
    private static final String TEST_BSSID = "12:12:12:12:12:12";
    private static final int TEST_RSSI = -60;
    private static final int TEST_NETWORK_ID = 5;
    private static final int TEST_NETWORK_ID2 = 6;
    private static final int TEST_SUB_ID = 1;

    /**
     *  Verify parcel write/read with WifiInfo.
     */
    @Test
    public void testWifiInfoParcelWriteReadWithLocationSensitiveInfo() throws Exception {
        WifiInfo writeWifiInfo = new WifiInfo();
        writeWifiInfo.txSuccess = TEST_TX_SUCCESS;
        writeWifiInfo.txRetries = TEST_TX_RETRIES;
        writeWifiInfo.txBad = TEST_TX_BAD;
        writeWifiInfo.rxSuccess = TEST_RX_SUCCESS;
        writeWifiInfo.setSSID(WifiSsid.createFromAsciiEncoded(TEST_SSID));
        writeWifiInfo.setBSSID(TEST_BSSID);
        writeWifiInfo.setNetworkId(TEST_NETWORK_ID);
        writeWifiInfo.setTrusted(true);
        writeWifiInfo.setOemPaid(true);
        writeWifiInfo.setOemPrivate(true);
        writeWifiInfo.setCarrierMerged(true);
        writeWifiInfo.setOsuAp(true);
        writeWifiInfo.setFQDN(TEST_FQDN);
        writeWifiInfo.setProviderFriendlyName(TEST_PROVIDER_NAME);
        writeWifiInfo.setRequestingPackageName(TEST_PACKAGE_NAME);
        writeWifiInfo.setWifiStandard(TEST_WIFI_STANDARD);
        writeWifiInfo.setMaxSupportedTxLinkSpeedMbps(TEST_MAX_SUPPORTED_TX_LINK_SPEED_MBPS);
        writeWifiInfo.setMaxSupportedRxLinkSpeedMbps(TEST_MAX_SUPPORTED_RX_LINK_SPEED_MBPS);
        writeWifiInfo.setSubscriptionId(TEST_SUB_ID);
        List<ScanResult.InformationElement> informationElements = generateIes();
        writeWifiInfo.setInformationElements(informationElements);
        writeWifiInfo.setIsPrimary(true);

        // Make a copy which allows parcelling of location sensitive data.
        WifiInfo writeWifiInfoWithLocationSensitiveInfo = writeWifiInfo.makeCopyInternal(true);

        Parcel parcel = Parcel.obtain();
        writeWifiInfoWithLocationSensitiveInfo.writeToParcel(parcel, 0);
        // Rewind the pointer to the head of the parcel.
        parcel.setDataPosition(0);
        WifiInfo readWifiInfo = WifiInfo.CREATOR.createFromParcel(parcel);

        assertNotNull(readWifiInfo);
        assertEquals(TEST_TX_SUCCESS, readWifiInfo.txSuccess);
        assertEquals(TEST_TX_RETRIES, readWifiInfo.txRetries);
        assertEquals(TEST_TX_BAD, readWifiInfo.txBad);
        assertEquals(TEST_RX_SUCCESS, readWifiInfo.rxSuccess);
        assertEquals("\"" + TEST_SSID + "\"", readWifiInfo.getSSID());
        assertEquals(TEST_BSSID, readWifiInfo.getBSSID());
        assertEquals(TEST_NETWORK_ID, readWifiInfo.getNetworkId());
        assertTrue(readWifiInfo.isTrusted());
        assertTrue(readWifiInfo.isOsuAp());
        assertTrue(readWifiInfo.isPasspointAp());
        assertEquals(TEST_PACKAGE_NAME, readWifiInfo.getRequestingPackageName());
        assertEquals(TEST_FQDN, readWifiInfo.getPasspointFqdn());
        assertEquals(TEST_PROVIDER_NAME, readWifiInfo.getPasspointProviderFriendlyName());
        assertEquals(TEST_WIFI_STANDARD, readWifiInfo.getWifiStandard());
        assertEquals(TEST_MAX_SUPPORTED_TX_LINK_SPEED_MBPS,
                readWifiInfo.getMaxSupportedTxLinkSpeedMbps());
        assertEquals(TEST_MAX_SUPPORTED_RX_LINK_SPEED_MBPS,
                readWifiInfo.getMaxSupportedRxLinkSpeedMbps());
        if (SdkLevel.isAtLeastS()) {
            assertTrue(readWifiInfo.isOemPaid());
            assertTrue(readWifiInfo.isOemPrivate());
            assertTrue(readWifiInfo.isCarrierMerged());
            assertEquals(TEST_SUB_ID, readWifiInfo.getSubscriptionId());
            assertEquals(2, readWifiInfo.getInformationElements().size());
            assertEquals(informationElements.get(0).id,
                    readWifiInfo.getInformationElements().get(0).id);
            assertEquals(informationElements.get(0).idExt,
                    readWifiInfo.getInformationElements().get(0).idExt);
            assertArrayEquals(informationElements.get(0).bytes,
                    readWifiInfo.getInformationElements().get(0).bytes);
            assertEquals(informationElements.get(1).id,
                    readWifiInfo.getInformationElements().get(1).id);
            assertEquals(informationElements.get(1).idExt,
                    readWifiInfo.getInformationElements().get(1).idExt);
            assertArrayEquals(informationElements.get(1).bytes,
                    readWifiInfo.getInformationElements().get(1).bytes);
            assertTrue(readWifiInfo.isPrimary());
        }
    }

    /**
     *  Verify parcel write/read with WifiInfo.
     */
    @Test
    public void testWifiInfoParcelWriteReadWithoutLocationSensitiveInfo() throws Exception {
        WifiInfo writeWifiInfo = new WifiInfo();
        writeWifiInfo.txSuccess = TEST_TX_SUCCESS;
        writeWifiInfo.txRetries = TEST_TX_RETRIES;
        writeWifiInfo.txBad = TEST_TX_BAD;
        writeWifiInfo.rxSuccess = TEST_RX_SUCCESS;
        writeWifiInfo.setSSID(WifiSsid.createFromAsciiEncoded(TEST_SSID));
        writeWifiInfo.setBSSID(TEST_BSSID);
        writeWifiInfo.setNetworkId(TEST_NETWORK_ID);
        writeWifiInfo.setTrusted(true);
        writeWifiInfo.setOemPaid(true);
        writeWifiInfo.setOemPrivate(true);
        writeWifiInfo.setCarrierMerged(true);
        writeWifiInfo.setOsuAp(true);
        writeWifiInfo.setFQDN(TEST_FQDN);
        writeWifiInfo.setProviderFriendlyName(TEST_PROVIDER_NAME);
        writeWifiInfo.setRequestingPackageName(TEST_PACKAGE_NAME);
        writeWifiInfo.setWifiStandard(TEST_WIFI_STANDARD);
        writeWifiInfo.setMaxSupportedTxLinkSpeedMbps(TEST_MAX_SUPPORTED_TX_LINK_SPEED_MBPS);
        writeWifiInfo.setMaxSupportedRxLinkSpeedMbps(TEST_MAX_SUPPORTED_RX_LINK_SPEED_MBPS);
        writeWifiInfo.setSubscriptionId(TEST_SUB_ID);
        writeWifiInfo.setInformationElements(generateIes());
        writeWifiInfo.setIsPrimary(true);

        // Make a copy which allows parcelling of location sensitive data.
        WifiInfo writeWifiInfoWithoutLocationSensitiveInfo = writeWifiInfo.makeCopyInternal(false);

        Parcel parcel = Parcel.obtain();
        writeWifiInfoWithoutLocationSensitiveInfo.writeToParcel(parcel, 0);
        // Rewind the pointer to the head of the parcel.
        parcel.setDataPosition(0);
        WifiInfo readWifiInfo = WifiInfo.CREATOR.createFromParcel(parcel);

        assertNotNull(readWifiInfo);
        assertEquals(TEST_TX_SUCCESS, readWifiInfo.txSuccess);
        assertEquals(TEST_TX_RETRIES, readWifiInfo.txRetries);
        assertEquals(TEST_TX_BAD, readWifiInfo.txBad);
        assertEquals(TEST_RX_SUCCESS, readWifiInfo.rxSuccess);
        assertEquals(WifiManager.UNKNOWN_SSID, readWifiInfo.getSSID());
        assertEquals(WifiInfo.DEFAULT_MAC_ADDRESS, readWifiInfo.getBSSID());
        assertEquals(WifiConfiguration.INVALID_NETWORK_ID, readWifiInfo.getNetworkId());
        assertTrue(readWifiInfo.isTrusted());
        assertTrue(readWifiInfo.isOsuAp());
        assertFalse(readWifiInfo.isPasspointAp()); // fqdn & friendly name is masked.
        assertEquals(TEST_PACKAGE_NAME, readWifiInfo.getRequestingPackageName());
        assertNull(readWifiInfo.getPasspointFqdn());
        assertNull(readWifiInfo.getPasspointProviderFriendlyName());
        assertEquals(TEST_WIFI_STANDARD, readWifiInfo.getWifiStandard());
        assertEquals(TEST_MAX_SUPPORTED_TX_LINK_SPEED_MBPS,
                readWifiInfo.getMaxSupportedTxLinkSpeedMbps());
        assertEquals(TEST_MAX_SUPPORTED_RX_LINK_SPEED_MBPS,
                readWifiInfo.getMaxSupportedRxLinkSpeedMbps());
        if (SdkLevel.isAtLeastS()) {
            assertTrue(readWifiInfo.isOemPaid());
            assertTrue(readWifiInfo.isOemPrivate());
            assertTrue(readWifiInfo.isCarrierMerged());
            assertEquals(TEST_SUB_ID, readWifiInfo.getSubscriptionId());
            assertNull(readWifiInfo.getInformationElements());
            assertTrue(readWifiInfo.isPrimary());
        }
    }

    /**
     *  Verify parcel write/read with WifiInfo.
     */
    @Test
    public void testWifiInfoParcelWriteReadWithNullInfoElements() throws Exception {
        assumeTrue(SdkLevel.isAtLeastS());

        WifiInfo writeWifiInfo = new WifiInfo();
        writeWifiInfo.setInformationElements(null);

        // Make a copy which allows parcelling of location sensitive data.
        WifiInfo writeWifiInfoWithoutLocationSensitiveInfo = writeWifiInfo.makeCopyInternal(true);

        Parcel parcel = Parcel.obtain();
        writeWifiInfoWithoutLocationSensitiveInfo.writeToParcel(parcel, 0);
        // Rewind the pointer to the head of the parcel.
        parcel.setDataPosition(0);
        WifiInfo readWifiInfo = WifiInfo.CREATOR.createFromParcel(parcel);
        assertNull(readWifiInfo.getInformationElements());
    }

    /**
     *  Verify parcel write/read with WifiInfo.
     */
    @Test
    public void testWifiInfoParcelWriteReadWithEmptyInfoElements() throws Exception {
        assumeTrue(SdkLevel.isAtLeastS());

        WifiInfo writeWifiInfo = new WifiInfo();
        writeWifiInfo.setInformationElements(new ArrayList<>());

        // Make a copy which allows parcelling of location sensitive data.
        WifiInfo writeWifiInfoWithoutLocationSensitiveInfo = writeWifiInfo.makeCopyInternal(true);

        Parcel parcel = Parcel.obtain();
        writeWifiInfoWithoutLocationSensitiveInfo.writeToParcel(parcel, 0);
        // Rewind the pointer to the head of the parcel.
        parcel.setDataPosition(0);
        WifiInfo readWifiInfo = WifiInfo.CREATOR.createFromParcel(parcel);
        assertTrue(readWifiInfo.getInformationElements().isEmpty());
    }

    @Test
    public void testWifiInfoCopyConstructor() throws Exception {
        WifiInfo writeWifiInfo = new WifiInfo();
        writeWifiInfo.txSuccess = TEST_TX_SUCCESS;
        writeWifiInfo.txRetries = TEST_TX_RETRIES;
        writeWifiInfo.txBad = TEST_TX_BAD;
        writeWifiInfo.rxSuccess = TEST_RX_SUCCESS;
        writeWifiInfo.setTrusted(true);
        writeWifiInfo.setOemPaid(true);
        writeWifiInfo.setOemPrivate(true);
        writeWifiInfo.setCarrierMerged(true);
        writeWifiInfo.setOsuAp(true);
        writeWifiInfo.setFQDN(TEST_FQDN);
        writeWifiInfo.setProviderFriendlyName(TEST_PROVIDER_NAME);
        writeWifiInfo.setRequestingPackageName(TEST_PACKAGE_NAME);
        writeWifiInfo.setWifiStandard(TEST_WIFI_STANDARD);
        writeWifiInfo.setMaxSupportedTxLinkSpeedMbps(TEST_MAX_SUPPORTED_TX_LINK_SPEED_MBPS);
        writeWifiInfo.setMaxSupportedRxLinkSpeedMbps(TEST_MAX_SUPPORTED_RX_LINK_SPEED_MBPS);
        writeWifiInfo.setSubscriptionId(TEST_SUB_ID);
        writeWifiInfo.setIsPrimary(true);

        WifiInfo readWifiInfo = new WifiInfo(writeWifiInfo);

        assertEquals(TEST_TX_SUCCESS, readWifiInfo.txSuccess);
        assertEquals(TEST_TX_RETRIES, readWifiInfo.txRetries);
        assertEquals(TEST_TX_BAD, readWifiInfo.txBad);
        assertEquals(TEST_RX_SUCCESS, readWifiInfo.rxSuccess);
        assertTrue(readWifiInfo.isTrusted());
        assertTrue(readWifiInfo.isOsuAp());
        assertTrue(readWifiInfo.isPasspointAp());
        assertEquals(TEST_PACKAGE_NAME, readWifiInfo.getRequestingPackageName());
        assertEquals(TEST_FQDN, readWifiInfo.getPasspointFqdn());
        assertEquals(TEST_PROVIDER_NAME, readWifiInfo.getPasspointProviderFriendlyName());
        assertEquals(TEST_WIFI_STANDARD, readWifiInfo.getWifiStandard());
        assertEquals(TEST_MAX_SUPPORTED_TX_LINK_SPEED_MBPS,
                readWifiInfo.getMaxSupportedTxLinkSpeedMbps());
        assertEquals(TEST_MAX_SUPPORTED_RX_LINK_SPEED_MBPS,
                readWifiInfo.getMaxSupportedRxLinkSpeedMbps());
        if (SdkLevel.isAtLeastS()) {
            assertTrue(readWifiInfo.isOemPaid());
            assertTrue(readWifiInfo.isOemPrivate());
            assertTrue(readWifiInfo.isCarrierMerged());
            assertEquals(TEST_SUB_ID, readWifiInfo.getSubscriptionId());
            assertTrue(readWifiInfo.isPrimary());
        }
    }

    /**
     *  Verify values after reset()
     */
    @Test
    public void testWifiInfoResetValue() throws Exception {
        WifiInfo wifiInfo = new WifiInfo();
        wifiInfo.reset();
        assertEquals(WifiInfo.LINK_SPEED_UNKNOWN, wifiInfo.getMaxSupportedTxLinkSpeedMbps());
        assertEquals(WifiInfo.LINK_SPEED_UNKNOWN, wifiInfo.getMaxSupportedRxLinkSpeedMbps());
        assertEquals(WifiInfo.LINK_SPEED_UNKNOWN, wifiInfo.getTxLinkSpeedMbps());
        assertEquals(WifiInfo.LINK_SPEED_UNKNOWN, wifiInfo.getRxLinkSpeedMbps());
        assertEquals(WifiInfo.INVALID_RSSI, wifiInfo.getRssi());
        assertEquals(WifiManager.UNKNOWN_SSID, wifiInfo.getSSID());
        assertEquals(null, wifiInfo.getBSSID());
        assertEquals(-1, wifiInfo.getNetworkId());
        if (SdkLevel.isAtLeastS()) {
            assertFalse(wifiInfo.isOemPaid());
            assertFalse(wifiInfo.isOemPrivate());
            assertFalse(wifiInfo.isCarrierMerged());
            assertEquals(SubscriptionManager.INVALID_SUBSCRIPTION_ID, wifiInfo.getSubscriptionId());
            assertFalse(wifiInfo.isPrimary());
        }
    }

    /**
     * Test that the WifiInfo Builder returns the same values that was set, and that
     * calling build multiple times returns different instances.
     */
    @Test
    public void testWifiInfoBuilder() throws Exception {
        WifiInfo.Builder builder = new WifiInfo.Builder()
                .setSsid(TEST_SSID.getBytes(StandardCharsets.UTF_8))
                .setBssid(TEST_BSSID)
                .setRssi(TEST_RSSI)
                .setNetworkId(TEST_NETWORK_ID);

        WifiInfo info1 = builder.build();

        assertEquals("\"" + TEST_SSID + "\"", info1.getSSID());
        assertEquals(TEST_BSSID, info1.getBSSID());
        assertEquals(TEST_RSSI, info1.getRssi());
        assertEquals(TEST_NETWORK_ID, info1.getNetworkId());

        WifiInfo info2 = builder
                .setNetworkId(TEST_NETWORK_ID2)
                .build();

        // different instances
        assertNotSame(info1, info2);

        // assert that info1 didn't change
        assertEquals("\"" + TEST_SSID + "\"", info1.getSSID());
        assertEquals(TEST_BSSID, info1.getBSSID());
        assertEquals(TEST_RSSI, info1.getRssi());
        assertEquals(TEST_NETWORK_ID, info1.getNetworkId());

        // assert that info2 changed
        assertEquals("\"" + TEST_SSID + "\"", info2.getSSID());
        assertEquals(TEST_BSSID, info2.getBSSID());
        assertEquals(TEST_RSSI, info2.getRssi());
        assertEquals(TEST_NETWORK_ID2, info2.getNetworkId());
    }

    @Test
    public void testWifiInfoEquals() throws Exception {
        WifiInfo.Builder builder = new WifiInfo.Builder()
                .setSsid(TEST_SSID.getBytes(StandardCharsets.UTF_8))
                .setBssid(TEST_BSSID)
                .setRssi(TEST_RSSI)
                .setNetworkId(TEST_NETWORK_ID);

        WifiInfo info1 = builder.build();
        WifiInfo info2 = builder.build();
        if (SdkLevel.isAtLeastS()) {
            assertEquals(info1, info2);
        } else {
            // On R devices, reference equality.
            assertNotEquals(info1, info2);
        }

        info1.setSubscriptionId(TEST_SUB_ID);
        assertNotEquals(info1, info2);

        info2.setSubscriptionId(TEST_SUB_ID);
        if (SdkLevel.isAtLeastS()) {
            assertEquals(info1, info2);
        } else {
            // On R devices, reference equality.
            assertNotEquals(info1, info2);
        }

        info1.setSSID(WifiSsid.createFromHex(null));
        assertNotEquals(info1, info2);

        info2.setSSID(WifiSsid.createFromHex(null));
        if (SdkLevel.isAtLeastS()) {
            assertEquals(info1, info2);
        } else {
            // On R devices, reference equality.
            assertNotEquals(info1, info2);
        }
    }

    @Test
    public void testWifiInfoEqualsWithInfoElements() throws Exception {
        WifiInfo.Builder builder = new WifiInfo.Builder()
                .setSsid(TEST_SSID.getBytes(StandardCharsets.UTF_8))
                .setBssid(TEST_BSSID)
                .setRssi(TEST_RSSI)
                .setNetworkId(TEST_NETWORK_ID);

        WifiInfo info1 = builder.build();
        WifiInfo info2 = builder.build();
        if (SdkLevel.isAtLeastS()) {
            assertEquals(info1, info2);
        } else {
            // On R devices, reference equality.
            assertNotEquals(info1, info2);
        }

        info1.setInformationElements(generateIes());
        info2.setInformationElements(generateIes());

        if (SdkLevel.isAtLeastS()) {
            assertEquals(info1, info2);
        } else {
            // On R devices, reference equality.
            assertNotEquals(info1, info2);
        }
    }

    @Test
    public void testWifiInfoHashcode() throws Exception {
        WifiInfo.Builder builder = new WifiInfo.Builder()
                .setSsid(TEST_SSID.getBytes(StandardCharsets.UTF_8))
                .setBssid(TEST_BSSID)
                .setRssi(TEST_RSSI)
                .setNetworkId(TEST_NETWORK_ID);

        WifiInfo info1 = builder.build();
        WifiInfo info2 = builder.build();
        if (SdkLevel.isAtLeastS()) {
            assertEquals(info1.hashCode(), info2.hashCode());
        } else {
            // On R devices, system generated hashcode.
            assertNotEquals(info1.hashCode(), info2.hashCode());
        }

        info1.setSubscriptionId(TEST_SUB_ID);
        assertNotEquals(info1.hashCode(), info2.hashCode());

        info2.setSubscriptionId(TEST_SUB_ID);
        if (SdkLevel.isAtLeastS()) {
            assertEquals(info1.hashCode(), info2.hashCode());
        } else {
            // On R devices, system generated hashcode.
            assertNotEquals(info1.hashCode(), info2.hashCode());
        }

        info1.setSSID(WifiSsid.createFromHex(null));
        assertNotEquals(info1.hashCode(), info2.hashCode());

        info2.setSSID(WifiSsid.createFromHex(null));
        if (SdkLevel.isAtLeastS()) {
            assertEquals(info1.hashCode(), info2.hashCode());
        } else {
            // On R devices, system generated hashcode.
            assertNotEquals(info1.hashCode(), info2.hashCode());
        }
    }

    private static List<ScanResult.InformationElement> generateIes() {
        List<ScanResult.InformationElement> informationElements = new ArrayList<>();
        ScanResult.InformationElement informationElement = new ScanResult.InformationElement();
        informationElement.id = ScanResult.InformationElement.EID_HT_OPERATION;
        informationElement.idExt = 0;
        informationElement.bytes = new byte[]{0x11, 0x22, 0x33};
        informationElements.add(informationElement);

        informationElement = new ScanResult.InformationElement();
        informationElement.id = ScanResult.InformationElement.EID_EXTENSION_PRESENT;
        informationElement.idExt = ScanResult.InformationElement.EID_EXT_HE_OPERATION;
        informationElement.bytes = new byte[]{0x44, 0x55, 0x66};
        informationElements.add(informationElement);

        return informationElements;
    }
}
