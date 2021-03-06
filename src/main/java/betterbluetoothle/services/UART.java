package betterbluetoothle.services;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.content.Context;

import com.google.common.base.Function;
import com.google.common.primitives.Bytes;

import org.jdeferred.DoneCallback;
import org.jdeferred.DonePipe;
import org.jdeferred.ProgressCallback;
import org.jdeferred.Promise;
import org.jdeferred.impl.DeferredObject;

import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.UUID;

import betterbluetoothle.async.AsyncBluetoothGatt;
import betterbluetoothle.async.AsyncBluetoothLeScan;

public class UART {

    //
    // PUBLIC METHODS AND STATE
    //

    // UUIDs for UAT service and associated characteristics.
    public static UUID UART_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    public static UUID TX_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    public static UUID RX_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");

    // Constructor
    public UART(BluetoothDevice device, Context context, boolean autoConnect) {
        gatt = new AsyncBluetoothGatt(device, context, autoConnect);
        received = new ArrayDeque<Byte>();
    }

    // Static method to find the first nearby UART device.
    // When this promise is resolved the first available UART device has been found.
    public static Promise<UART, Void, Void> findFirst(BluetoothAdapter adapter, final Context context, final boolean autoConnect) {
        scanner = new AsyncBluetoothLeScan(adapter);
        found = new DeferredObject<UART, Void, Void>();
        // Scan for devices with the UART service.
        scanner.start(UART_UUID).progress(new ProgressCallback<AsyncBluetoothLeScan.ScanResult>() {
            @Override
            public void onProgress(AsyncBluetoothLeScan.ScanResult progress) {
                // Found a device.
                // Stop the scan.
                scanner.stop();
                scanner = null;
                // Resolve the found promise with the UART.
                found.resolve(new UART(progress.device, context, autoConnect));
            }
        });
        return found.promise();
    }

    // Connect to the device's UART service and setup code to fire connected, available, and
    // disconnected promises.
    public void connect() {
        // Connect to the device.
        gatt.connect().then(new DonePipe<Void, Void, Integer, Void>() {
            @Override
            public Promise<Void, Integer, Void> pipeDone(Void result) {
                // Connected, start service discovery.
                return gatt.discoverServices();
            }
        // Switch to promise for service discovery completion.
        }).then(new DonePipe<Void, BluetoothGattDescriptor, Integer, Void>() {
            @Override
            public Promise<BluetoothGattDescriptor, Integer, Void> pipeDone(Void result) {
                // Service discovery complete, grab reference to TX and RX services.
                rx = gatt.getService(UART_UUID).getCharacteristic(RX_UUID);
                tx = gatt.getService(UART_UUID).getCharacteristic(TX_UUID);
                // Notify that device is connected.
                enumerateCallbacks(new Function<Callback, Void>() {
                    @Override
                    public Void apply(Callback input) {
                        input.connected();
                        return null;
                    }
                });
                // Now setup notifications for RX characteristic changes.
                // First change the client descriptor to enable notifications and write it to the device.
                BluetoothGattDescriptor client = rx.getDescriptor(CLIENT_UUID);
                client.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                return gatt.writeDescriptor(client);
            }
            // Switch to promise for RX client descriptor update.
        }).then(new DonePipe<BluetoothGattDescriptor, Void, Void, BluetoothGattCharacteristic>() {
            @Override
            public Promise<Void, Void, BluetoothGattCharacteristic> pipeDone(BluetoothGattDescriptor result) {
                // Descriptor update complete, now enable notifications on RX characteristic changes.
                return gatt.setCharacteristicNotification(rx, true);
            }
            // Switch to promise for RX characteristic updates (i.e. data received).
        }).progress(new ProgressCallback<BluetoothGattCharacteristic>() {
            @Override
            public void onProgress(BluetoothGattCharacteristic progress) {
                // RX characteristic has changed.
                // Update buffer of received bytes.
                updateReceived(progress);
                // Notify data is available for reading.
                enumerateCallbacks(new Function<Callback, Void>() {
                    @Override
                    public Void apply(Callback input) {
                        input.available();
                        return null;
                    }
                });

            }
        });
        // Catch when the device is disconnected and notify all the registered callbacks.
        gatt.disconnected().done(new DoneCallback<Void>() {
                @Override
                public void onDone(Void result) {
                    // Notify disconnected
                    enumerateCallbacks(new Function<Callback, Void>() {
                        @Override
                        public Void apply(Callback input) {
                            input.disconnected();
                            return null;
                        }
                    });
                }
            }
        );
    }

    // Interface to define the UART event handler functions for connected, disconnected, and data
    // available events.
    public interface Callback {
        public void connected();
        public void disconnected();
        public void available();
    }

    // Register a class to receive callbacks of UART events.
    public synchronized void register(Callback callback) {
        // Add the callback if it isn't already in the list of registered callbacks.
        if (!callbacks.contains(callback)) {
            callbacks.add(callback);
        }
    }

    // Unregister a class to stop receiving callbacks of UART events.
    public synchronized void unregister(Callback callback) {
        // Remove the callback.
        callbacks.remove(callback);
    }

    // Return amount of bytes available in received buffer.
    public synchronized int available() {
        return received.size();
    }

    // Disconnect from the UART.
    public void disconnect() {
        gatt.disconnect();
    }

    // Write bytes to the UART.
    public void write(byte[] data) {
        if (tx == null) {
            return;
        }
        tx.setValue(data);
        gatt.writeCharacteristic(tx);
    }

    // Write a string to the UART.  String will be encoded in UTF-8 before sending to UART.
    public void write(String data) {
        write(data.getBytes(Charset.forName("UTF-8")));
    }

    // Read up to count bytes of data from the UART received data.  Less data than requested might be returned!
    public synchronized byte[] read(int count) {
        int size = count < received.size() ? count : received.size();
        byte[] result = new byte[size];
        for (int i = 0; i < size; ++i) {
            result[i] = received.remove();
        }
        return result;
    }

    // Read all bytes of data from the UART.
    public synchronized byte[] readAll() {
        byte[] result = Bytes.toArray(received);
        received.clear();
        return result;
    }

    // Read bytes as a UTF-8 string up to length bytes long.  Less data than requested might be returned!
    public synchronized String readString(int length) {
        return new String(read(length), Charset.forName("UTF-8"));
    }

    // Read all bytes as a UTF-8 string.
    public synchronized String readAllString() {
        return new String(readAll(), Charset.forName("UTF-8"));
    }

    //
    // PRIVATE/INTERNAL METHODS AND STATE
    //

    // UUID for the BTLE client characteristic which is necessary for notifications.
    private static UUID CLIENT_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static AsyncBluetoothLeScan scanner;
    private static DeferredObject<UART, Void, Void> found;
    private AsyncBluetoothGatt gatt;
    private BluetoothGattCharacteristic rx;
    private BluetoothGattCharacteristic tx;
    // TODO: This deque of bytes is not efficient for storing the buffer of received data.
    private ArrayDeque<Byte> received;
    private ArrayList<Callback> callbacks;

    // Convenience method for enumerating all valid callbacks.
    private synchronized void enumerateCallbacks(Function<Callback, Void> function) {
        for (Callback callback : callbacks) {
            if (callback != null) {
                function.apply(callback);
            }
        }
    }

    // Add data to received buffer.
    private synchronized void updateReceived(BluetoothGattCharacteristic rx) {
        received.addAll(Bytes.asList(rx.getValue()));
    }
}
