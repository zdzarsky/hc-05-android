package arkadiusz.szczepanek.bluetoothclient;

import android.app.Activity;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final String TAG = "Bluetooth Client";
    private final String devName = "HC-05";
    ImageView btIcon;
    NotificationCompat.Builder mBuilder =
            new NotificationCompat.Builder(this)
                    .setSmallIcon(R.drawable.ic_notify)
                    .setContentTitle("ALARM")
                    .setContentText("Ktoś otworzył twoje drzwi")
                    .setVibrate(new long[]{100, 100, 100, 100});

    private final float LSB_TO_DPS = (float) (8.75 / 1000.0);


    BluetoothAdapter mBluetoothAdapter;
    BluetoothSocket mmSocket;
    BluetoothDevice mmDevice;
    OutputStream mmOutputStream;
    InputStream mmInputStream;
    Thread workerThread;
    byte[] readBuffer;
    int readBufferPosition;
    int counter;
    volatile boolean stopWorker;

    int gyroX, gyroY, gyroZ;
    int accX, accY, accZ;
    int index;

    byte[] packetBytes = new byte[18];
    byte[] copyForSecondThread = new byte[18];

    int prevRead;
    private DataInputStream mmDataInputStream;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate: started");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button openButton = (Button) findViewById(R.id.open);
        Button closeButton = (Button) findViewById(R.id.close);
        btIcon = findViewById(R.id.imageView);
        //Open Button
        openButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    findBT();
                    openBT();
                } catch (IOException ex) {
                }
            }
        });



        //Close button
        closeButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    closeBT();
                } catch (IOException ex) {
                }
            }
        });
    }

    boolean isSensorMoved(byte[] readings, int startPosition, int commSize) {
        double dx = 175;
        if (readings.length == 0) return false;
            startPosition += 2;
            startPosition %= commSize;

            index = readings[(startPosition) % commSize];
            startPosition++;

            gyroX = (readings[(startPosition % commSize)] << 8) | readings[(startPosition + 1) % commSize];
            startPosition += 2;

            gyroY = (readings[(startPosition % commSize)] << 8) | readings[(startPosition + 1) % commSize];
            startPosition += 2;

            gyroZ = (readings[(startPosition % commSize)] << 8) | readings[(startPosition + 1) % commSize];
            startPosition += 2;

            accX = (readings[(startPosition % commSize)] << 8) | readings[(startPosition + 1) % commSize];
            startPosition += 2;

            accY = (readings[(startPosition % commSize)] << 8) | readings[(startPosition + 1) % commSize];
            startPosition += 2;

            accZ = (readings[(startPosition % commSize)] << 8) | readings[(startPosition + 1) % commSize];

            float prevVec;
            float[] angularVelocities = new float[]{(float)gyroX, (float)gyroY, (float)gyroZ};
            float vec = 0;
            for(int i = 0; i < 3; i++){
                angularVelocities[i] *= LSB_TO_DPS;
                angularVelocities[i] *= angularVelocities[i];
                vec += angularVelocities[i];
            }

            return vec > dx;

    }

    void findBT() {
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            Toast.makeText(MainActivity.this, "Nie znaleziono urządzenia HC-05", Toast.LENGTH_SHORT).show();
            btIcon.setImageResource(R.drawable.ic_disconnected);
        }

        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableBluetooth = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBluetooth, 0);
        }

        Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
        if (pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                if (device.getName().equals(devName)) {
                    mmDevice = device;
                    break;
                }
            }
        }

        Toast.makeText(MainActivity.this, "Połączono z urządzeniem HC-05", Toast.LENGTH_SHORT).show();
    }

    void openBT() throws IOException {
        UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); //Standard SerialPortService ID
        mmSocket = mmDevice.createRfcommSocketToServiceRecord(uuid);
        mmSocket.connect();
        mmOutputStream = mmSocket.getOutputStream();
        mmInputStream = mmSocket.getInputStream();
        mmDataInputStream = new DataInputStream(mmInputStream);
        beginListenForData();

        Toast.makeText(MainActivity.this, "Otwarto socket Bluetooth", Toast.LENGTH_SHORT).show();
        btIcon.setImageResource(R.drawable.ic_connected);
    }

    void beginListenForData() {
        Log.d(TAG, "beginListenForData: started");
        final Handler handler = new Handler();

        stopWorker = false;
        readBufferPosition = 0;
        final int commSize = 18;
        readBuffer = new byte[1024];
        workerThread = new Thread(new Runnable() {
            public void run() {
                while (!Thread.currentThread().isInterrupted() && !stopWorker) {
                    try {

                        byte pre_last = mmDataInputStream.readByte();
                        packetBytes = new byte[18];
                        while (true) {
                            byte last = mmDataInputStream.readByte();
                            if (pre_last == 'w' && last == 'a') {
                                break;
                            }
                            pre_last = last;
                        }

                        mmDataInputStream.readFully(packetBytes);
                        //Log.d(TAG, "PacketBytes:" + new String(packetBytes));
                        System.arraycopy(packetBytes, 0, copyForSecondThread, 0, 18);
                        readBufferPosition = 0;
                        handler.post(new Runnable() {

                            public void run() {
                                if (isSensorMoved(copyForSecondThread, 0, commSize)) {
                                    createAlert();
                                }

                            }
                        });


                    } catch (IOException ex) {
                        stopWorker = true;
                    }
                }
            }
        });

        workerThread.start();
    }

    private void createAlert() {
        Log.d(TAG, "createAlert: DEVICE HAS BEEN MOVED !");
        // Sets an ID for the notification
        int mNotificationId = 001;
        // Gets an instance of the NotificationManager service
        NotificationManager mNotifyMgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        // Builds the notification and issues it.
        mNotifyMgr.notify(mNotificationId, mBuilder.build());
    }



    void closeBT() throws IOException {
        stopWorker = true;
        mmOutputStream.close();
        mmInputStream.close();
        mmSocket.close();
        Toast.makeText(MainActivity.this, "Odłączono urządzenie HC-05", Toast.LENGTH_SHORT).show();
        btIcon.setImageResource(R.drawable.ic_disconnected);
    }
}