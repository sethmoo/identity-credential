/*
 * Copyright 2022 The Android Open Source Project
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

package com.android.identity;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.android.identity.Constants.LoggingFlag;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;

@SuppressWarnings("deprecation")
class GattServer extends BluetoothGattServerCallback {
    private static final String TAG = "GattServer";
    private final byte[] mEncodedEDeviceKeyBytes;
    private final UUID mServiceUuid;
    private final BluetoothManager mBluetoothManager;
    private final Context mContext;
    final Util.Logger mLog;
    UUID mCharacteristicStateUuid;
    UUID mCharacteristicClient2ServerUuid;
    UUID mCharacteristicServer2ClientUuid;
    UUID mCharacteristicIdentUuid;
    // This is what the 16-bit UUID 0x29 0x02 is encoded like.
    UUID mClientCharacteristicConfigUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    Listener mListener;
    boolean mInhibitCallbacks = false;
    BluetoothGattCharacteristic mCharacteristicState;
    BluetoothGattCharacteristic mCharacteristicClient2Server;
    BluetoothGattCharacteristic mCharacteristicServer2Client;
    BluetoothGattCharacteristic mCharacteristicIdent;
    ByteArrayOutputStream mIncomingMessage = new ByteArrayOutputStream();
    Queue<byte[]> mWritingQueue = new ArrayDeque<>();
    boolean writeIsOutstanding = false;
    private BluetoothGattServer mGattServer;
    private BluetoothDevice mCurrentConnection;
    private int mNegotiatedMtu = 0;
    private byte[] mIdentValue;

    GattServer(@NonNull Context context, @LoggingFlag int loggingFlags,
            @NonNull BluetoothManager bluetoothManager,
            @NonNull UUID serviceUuid, @NonNull byte[] encodedEDeviceKeyBytes,
            @NonNull UUID characteristicStateUuid,
            @NonNull UUID characteristicClient2ServerUuid,
            @NonNull UUID characteristicServer2ClientUuid,
            @Nullable UUID characteristicIdentUuid) {
        mContext = context;
        mLog = new Util.Logger(TAG, loggingFlags);
        mBluetoothManager = bluetoothManager;
        mServiceUuid = serviceUuid;
        mEncodedEDeviceKeyBytes = encodedEDeviceKeyBytes;
        mCharacteristicStateUuid = characteristicStateUuid;
        mCharacteristicClient2ServerUuid = characteristicClient2ServerUuid;
        mCharacteristicServer2ClientUuid = characteristicServer2ClientUuid;
        mCharacteristicIdentUuid = characteristicIdentUuid;
    }

    void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    boolean start() {
        byte[] ikm = mEncodedEDeviceKeyBytes;
        byte[] info = new byte[]{'B', 'L', 'E', 'I', 'd', 'e', 'n', 't'};
        byte[] salt = new byte[]{};
        mIdentValue = Util.computeHkdf("HmacSha256", ikm, salt, info, 16);

        try {
            mGattServer = mBluetoothManager.openGattServer(mContext, this);
        } catch (SecurityException e) {
            reportError(e);
            return false;
        }
        if (mGattServer == null) {
            return false;
        }

        BluetoothGattService service = new BluetoothGattService(mServiceUuid,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);

        BluetoothGattCharacteristic c;
        BluetoothGattDescriptor d;

        // State
        c = new BluetoothGattCharacteristic(mCharacteristicStateUuid,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        d = new BluetoothGattDescriptor(mClientCharacteristicConfigUuid,
                BluetoothGattDescriptor.PERMISSION_WRITE);
        d.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        c.addDescriptor(d);
        service.addCharacteristic(c);
        mCharacteristicState = c;

        // Client2Server
        c = new BluetoothGattCharacteristic(mCharacteristicClient2ServerUuid,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        service.addCharacteristic(c);
        mCharacteristicClient2Server = c;

        // Server2Client
        c = new BluetoothGattCharacteristic(mCharacteristicServer2ClientUuid,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        d = new BluetoothGattDescriptor(mClientCharacteristicConfigUuid,
                BluetoothGattDescriptor.PERMISSION_WRITE);
        d.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        c.addDescriptor(d);
        service.addCharacteristic(c);
        mCharacteristicServer2Client = c;

        // Ident
        if (mCharacteristicIdentUuid != null) {
            c = new BluetoothGattCharacteristic(mCharacteristicIdentUuid,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ);
            service.addCharacteristic(c);
            mCharacteristicIdent = c;
        }

        try {
            mGattServer.addService(service);
        } catch (SecurityException e) {
            reportError(e);
            return false;
        }

        return true;
    }

    void stop() {
        mInhibitCallbacks = true;
        if (mGattServer != null) {
            try {
                if (mCurrentConnection != null) {
                    mGattServer.cancelConnection(mCurrentConnection);
                }
                mGattServer.close();
            } catch (SecurityException e) {
                Log.e(TAG, "Caught SecurityException while shutting down: " + e);
            }
        }
    }

    @Override
    public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
        mLog.transport("onConnectionStateChange: " + status + " " + newState);
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            if (mCurrentConnection != null) {
                Log.w(TAG, "Got a connection but we already have one");
                // TODO: possible to disconnect that 2nd device?
                return;
            }
            mCurrentConnection = device;

        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            if (!device.getAddress().equals(mCurrentConnection.getAddress())) {
                Log.w(TAG, "Got a disconnection from a device we haven't seen before");
                return;
            }
            mCurrentConnection = null;
            reportPeerDisconnected();
        }
    }

    @Override
    public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
            BluetoothGattCharacteristic characteristic) {
        mLog.transport("onCharacteristicReadRequest: " + requestId + " " + offset + " "
                    + characteristic.getUuid());
        if (mCharacteristicIdentUuid != null
                && characteristic.getUuid().equals(mCharacteristicIdentUuid)) {
            try {
                mGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        mIdentValue);
            } catch (SecurityException e) {
                reportError(e);
            }
        } else {
            reportError(new Error("Read on unexpected characteristic with UUID "
                    + characteristic.getUuid()));
        }
    }

    @Override
    public void onCharacteristicWriteRequest(BluetoothDevice device,
            int requestId,
            BluetoothGattCharacteristic characteristic,
            boolean preparedWrite,
            boolean responseNeeded,
            int offset,
            byte[] value) {
        UUID charUuid = characteristic.getUuid();
        mLog.transport("onCharacteristicWriteRequest: "
                + characteristic.getUuid() + " " + offset + " " + Util.toHex(value));
        if (charUuid.equals(mCharacteristicStateUuid) && value.length == 1) {
            if (value[0] == 0x01) {
                reportPeerConnected();
            } else if (value[0] == 0x02) {
                reportTransportSpecificSessionTermination();
            } else {
                reportError(new Error("Invalid byte " + value[0] + " for state "
                        + "characteristic"));
            }
        } else if (charUuid.equals(mCharacteristicClient2ServerUuid)) {
            if (value.length < 1) {
                reportError(new Error("Invalid value with length " + value.length));
                return;
            }
            mIncomingMessage.write(value, 1, value.length - 1);
            if (value[0] == 0x00) {
                // Last message.
                byte[] entireMessage = mIncomingMessage.toByteArray();
                mIncomingMessage.reset();
                reportMessageReceived(entireMessage);
            }

            if (responseNeeded) {
                try {
                    mGattServer.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            null);
                } catch (SecurityException e) {
                    reportError(e);
                }
            }
        } else {
            reportError(new Error("Write on unexpected characteristic with UUID "
                    + characteristic.getUuid()));
        }
    }

    @Override
    public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset,
            BluetoothGattDescriptor descriptor) {
        mLog.transport("onDescriptorReadRequest: "
                + descriptor.getCharacteristic().getUuid() + " " + offset);
        /* Do nothing */
    }

    @Override
    public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
            BluetoothGattDescriptor descriptor,
            boolean preparedWrite, boolean responseNeeded,
            int offset, byte[] value) {
        if (mLog.isTransportVerboseEnabled()) {
            mLog.transportVerbose("onDescriptorWriteRequest: "
                    + descriptor.getCharacteristic().getUuid() + " " + offset + " "
                    + Util.toHex(value));
        }
        if (responseNeeded) {
            try {
                mGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        null);
            } catch (SecurityException e) {
                reportError(e);
            }
        }
    }

    @Override
    public void onMtuChanged(BluetoothDevice device, int mtu) {
        mNegotiatedMtu = mtu;
        mLog.transport("Negotiated MTU " + mtu);
    }

    void drainWritingQueue() {
        mLog.transport("drainWritingQueue " + writeIsOutstanding);
        if (writeIsOutstanding) {
            return;
        }
        byte[] chunk = mWritingQueue.poll();
        if (chunk == null) {
            return;
        }

        if (mLog.isTransportVerboseEnabled()) {
            mLog.transportVerbose("writing chunk to " + mCharacteristicServer2Client.getUuid() +
                    " " + Util.toHex(chunk));
        }

        mCharacteristicServer2Client.setValue(chunk);
        try {
            if (!mGattServer.notifyCharacteristicChanged(mCurrentConnection,
                    mCharacteristicServer2Client, false)) {
                reportError(
                        new Error("Error calling notifyCharacteristicsChanged on Server2Client"));
                return;
            }
        } catch (SecurityException e) {
            reportError(e);
            return;
        }
        writeIsOutstanding = true;
    }

    @Override
    public void onNotificationSent(BluetoothDevice device, int status) {
        mLog.transport("onNotificationSent " + status);
        if (status != BluetoothGatt.GATT_SUCCESS) {
            reportError(new Error("Error in onNotificationSent status=" + status));
            return;
        }
        writeIsOutstanding = false;
        drainWritingQueue();
    }

    void sendMessage(@NonNull byte[] data) {
        if (mNegotiatedMtu == 0) {
            Log.w(TAG, "MTU not negotiated, defaulting to 23. Performance will suffer.");
            mNegotiatedMtu = 23;
        }

        if (mLog.isTransportVerboseEnabled()) {
            mLog.transportVerbose("sendMessage " + Util.toHex(data));
        }

        // Three less the MTU but we also need room for the leading 0x00 or 0x01.
        //
        int maxChunkSize = mNegotiatedMtu - 4;

        int offset = 0;
        do {
            boolean moreChunksComing = (offset + maxChunkSize < data.length);
            int size = data.length - offset;
            if (size > maxChunkSize) {
                size = maxChunkSize;
            }

            byte[] chunk = new byte[size + 1];
            chunk[0] = moreChunksComing ? (byte) 0x01 : (byte) 0x00;
            System.arraycopy(data, offset, chunk, 1, size);

            mWritingQueue.add(chunk);

            offset += size;
        } while (offset < data.length);
        drainWritingQueue();
    }

    void reportPeerConnected() {
        if (mListener != null && !mInhibitCallbacks) {
            mListener.onPeerConnected();
        }
    }

    void reportPeerDisconnected() {
        if (mListener != null && !mInhibitCallbacks) {
            mListener.onPeerDisconnected();
        }
    }

    void reportMessageReceived(@NonNull byte[] data) {
        if (mListener != null && !mInhibitCallbacks) {
            mListener.onMessageReceived(data);
        }
    }

    void reportError(@NonNull Throwable error) {
        if (mListener != null && !mInhibitCallbacks) {
            mListener.onError(error);
        }
    }

    void reportTransportSpecificSessionTermination() {
        if (mListener != null && !mInhibitCallbacks) {
            mListener.onTransportSpecificSessionTermination();
        }
    }

    public void sendTransportSpecificTermination() {
        byte[] terminationCode = new byte[]{(byte) 0x02};
        mCharacteristicState.setValue(terminationCode);
        try {
            if (!mGattServer.notifyCharacteristicChanged(mCurrentConnection,
                    mCharacteristicState, false)) {
                reportError(new Error("Error calling notifyCharacteristicsChanged on State"));
            }
        } catch (SecurityException e) {
            reportError(e);
        }
    }

    interface Listener {
        void onPeerConnected();

        void onPeerDisconnected();

        void onMessageReceived(@NonNull byte[] data);

        void onTransportSpecificSessionTermination();

        void onError(@NonNull Throwable error);
    }
}


