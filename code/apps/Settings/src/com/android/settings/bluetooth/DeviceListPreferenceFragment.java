/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.settings.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.util.Log;

import com.android.settings.RestrictedSettingsFragment;
import com.android.settingslib.bluetooth.BluetoothCallback;
import com.android.settingslib.bluetooth.BluetoothDeviceFilter;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.LocalBluetoothAdapter;
import com.android.settingslib.bluetooth.LocalBluetoothManager;

import java.util.Collection;
import java.util.WeakHashMap;

/**
 * Parent class for settings fragments that contain a list of Bluetooth
 * devices.
 *
 * @see BluetoothSettings
 * @see DevicePickerFragment
 */
public abstract class DeviceListPreferenceFragment extends
        RestrictedSettingsFragment implements BluetoothCallback {

    private static final String TAG = "DeviceListPreferenceFragment";

    private static final String KEY_BT_DEVICE_LIST = "bt_device_list";
    private static final String KEY_BT_SCAN = "bt_scan";

    private BluetoothDeviceFilter.Filter mFilter;

    BluetoothDevice mSelectedDevice;

    LocalBluetoothAdapter mLocalAdapter;
    LocalBluetoothManager mLocalManager;

    private PreferenceGroup mDeviceListGroup;

    final WeakHashMap<CachedBluetoothDevice, BluetoothDevicePreference> mDevicePreferenceMap =
            new WeakHashMap<CachedBluetoothDevice, BluetoothDevicePreference>();

    DeviceListPreferenceFragment(String restrictedKey) {
        super(restrictedKey);
        mFilter = BluetoothDeviceFilter.ALL_FILTER;
    }

    final void setFilter(BluetoothDeviceFilter.Filter filter) {
        mFilter = filter;
    }

    final void setFilter(int filterType) {
        mFilter = BluetoothDeviceFilter.getFilter(filterType);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mLocalManager = Utils.getLocalBtManager(getActivity());
        if (mLocalManager == null) {
            Log.e(TAG, "Bluetooth is not supported on this device");
            return;
        }
        mLocalAdapter = mLocalManager.getBluetoothAdapter();

        addPreferencesForActivity();

        mDeviceListGroup = (PreferenceCategory) findPreference(KEY_BT_DEVICE_LIST);
    }

    void setDeviceListGroup(PreferenceGroup preferenceGroup) {
        mDeviceListGroup = preferenceGroup;
    }

    /** Add preferences from the subclass. */
    abstract void addPreferencesForActivity();

    @Override
    public void onResume() {
        super.onResume();
        if (mLocalManager == null || isUiRestricted()) return;

        mLocalManager.setForegroundActivity(getActivity());
        mLocalManager.getEventManager().registerCallback(this);

        updateProgressUi(mLocalAdapter.isDiscovering());
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mLocalManager == null || isUiRestricted()) {
            return;
        }

        /* SPRD: modified for that When cancel bluetooth pairing,
         * the "No nearby Bluetooth devices were found.
         * " will show a very short period of time.
         */
        // removeAllDevices();
        mLocalManager.setForegroundActivity(null);
        mLocalManager.getEventManager().unregisterCallback(this);
    }

    void removeAllDevices() {
        mLocalAdapter.stopScanning();
        mDevicePreferenceMap.clear();
        mDeviceListGroup.removeAll();
    }

    void addCachedDevices() {
        Collection<CachedBluetoothDevice> cachedDevices =
                mLocalManager.getCachedDeviceManager().getCachedDevicesCopy();
        for (CachedBluetoothDevice cachedDevice : cachedDevices) {
            onDeviceAdded(cachedDevice);
        }
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen,
            Preference preference) {
        if (KEY_BT_SCAN.equals(preference.getKey())) {
            mLocalAdapter.startScanning(true);
            return true;
        }

        if (preference instanceof BluetoothDevicePreference) {
            BluetoothDevicePreference btPreference = (BluetoothDevicePreference) preference;
            CachedBluetoothDevice device = btPreference.getCachedDevice();
            mSelectedDevice = device.getDevice();
            onDevicePreferenceClick(btPreference);
            return true;
        }

        return super.onPreferenceTreeClick(preferenceScreen, preference);
    }

    void onDevicePreferenceClick(BluetoothDevicePreference btPreference) {
        btPreference.onClicked();
    }

    public void onDeviceAdded(CachedBluetoothDevice cachedDevice) {
        if (mDevicePreferenceMap.get(cachedDevice) != null) {
            return;
        }

        // Prevent updates while the list shows one of the state messages
        if (mLocalAdapter.getBluetoothState() != BluetoothAdapter.STATE_ON) return;

        if (mFilter.matches(cachedDevice.getDevice())) {
            createDevicePreference(cachedDevice);
        }
    }

    void createDevicePreference(CachedBluetoothDevice cachedDevice) {
        if (mDeviceListGroup == null) {
            Log.w(TAG, "Trying to create a device preference before the list group/category "
                    + "exists!");
            return;
        }

        BluetoothDevicePreference preference = new BluetoothDevicePreference(
                getActivity(), cachedDevice);

        initDevicePreference(preference);
        mDeviceListGroup.addPreference(preference);
        mDevicePreferenceMap.put(cachedDevice, preference);
    }

    /**
     * Overridden in {@link BluetoothSettings} to add a listener.
     * @param preference the newly added preference
     */
    void initDevicePreference(BluetoothDevicePreference preference) {
        // Does nothing by default
    }

    public void onDeviceDeleted(CachedBluetoothDevice cachedDevice) {
        BluetoothDevicePreference preference = mDevicePreferenceMap.remove(cachedDevice);
        if (preference != null) {
            mDeviceListGroup.removePreference(preference);
        }
    }

    public void onScanningStateChanged(boolean started) {
        /*
         * SPRD: Add a judge that remove out of range devices when started is
         * false. if manual cancel by connect or pair operations, we will not
         * do removeOutOfRangeDevices() @{
         */
        if (started == false && mLocalAdapter.isManualCancel() == false) {
            removeOutOfRangeDevices();
        }
        /* @} */
        updateProgressUi(started);
    }

    public void updateProgressUi(boolean start) {
        if (mDeviceListGroup instanceof BluetoothProgressCategory) {
            ((BluetoothProgressCategory) mDeviceListGroup).setProgress(start);
        }
    }

    public void onBluetoothStateChanged(int bluetoothState) {
        if (bluetoothState == BluetoothAdapter.STATE_OFF) {
            updateProgressUi(false);
        }
    }

    public void onConnectionStateChanged(CachedBluetoothDevice cachedDevice, int state) { }

    /**
     * SPRD: Add the function that remove cachedDevice from cachedDevices when
     * BondState is BOND_NONE and cachedDevice is unvisible. @{
     */
    private void removeOutOfRangeDevices() {
        Collection<CachedBluetoothDevice> cachedDevices =
                mLocalManager.getCachedDeviceManager().getCachedDevicesCopy();
        for (CachedBluetoothDevice cachedDevice : cachedDevices) {
            if (cachedDevice.getBondState() == BluetoothDevice.BOND_NONE &&
                    cachedDevice.isVisible() == false) {
                BluetoothDevicePreference preference = mDevicePreferenceMap.get(cachedDevice);
                if (preference != null) {
                    mDeviceListGroup.removePreference(preference);
                }
                mDevicePreferenceMap.remove(cachedDevice);
            }
        }
    }
    /** @} */
}
