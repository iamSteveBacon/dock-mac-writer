package de.yourorg.dockmqtt;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.io.File;
import java.io.FileOutputStream;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    // ---- CONFIG (anpassen wenn nötig) ----
    private static final String BROKER_HOST = "192.168.130.11";
    private static final int BROKER_PORT = 1883;
    private static final String TOPIC_VIN = "DB/vehicle/VIN";
    private static final String TOPIC_VEHICLE_ID = "DB/vehicle/UniqueId";
    private static final int WAIT_SECONDS = 10;
    // --------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Alles in einem Background-Thread (Android erlaubt kein Netzwerk im UI Thread)
        new Thread(() -> {
            Result r = new Result();
            r.dockMac = resolveDockMac();

            try {
                fetchFromMqtt(r);
            } catch (Exception e) {
                r.status = "MQTT_ERROR";
                r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }

            // Ergebnisse schreiben (scoped-storage-safe)
            writeToFile("dock_mac.txt", safe(r.dockMac) + "\n");
            writeToFile("vin.txt", safe(r.vin) + "\n");
            writeToFile("vehicle_id.txt", safe(r.vehicleId) + "\n");

            String json = "{"
                    + "\"dock_mac\":\"" + esc(safe(r.dockMac)) + "\","
                    + "\"vin\":\"" + esc(safe(r.vin)) + "\","
                    + "\"vehicle_id\":\"" + esc(safe(r.vehicleId)) + "\","
                    + "\"status\":\"" + esc(safe(r.status)) + "\","
                    + "\"error\":\"" + esc(safe(r.error)) + "\""
                    + "}";
            writeToFile("vehicle_info.json", json + "\n");

            // App schließen
            runOnUiThread(this::finish);
        }).start();
    }

    private void fetchFromMqtt(Result r) throws MqttException, InterruptedException {
        String brokerUrl = "tcp://" + BROKER_HOST + ":" + BROKER_PORT;
        String clientId = "dockmqtt-" + UUID.randomUUID();

        CountDownLatch latch = new CountDownLatch(1);

        MqttClient client = new MqttClient(brokerUrl, clientId, null);

        client.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                // Ignore - we'll timeout anyway
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                String payload = new String(message.getPayload(), StandardCharsets.UTF_8).trim();
                if (TOPIC_VIN.equals(topic)) {
                    r.vin = payload;
                } else if (TOPIC_VEHICLE_ID.equals(topic)) {
                    r.vehicleId = payload;
                }

                // Wenn wir VehicleId haben (oder beides), sind wir fertig
                if (r.vehicleId != null && !r.vehicleId.isEmpty()) {
                    latch.countDown();
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // Not used (subscriber)
            }
        });

        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        opts.setAutomaticReconnect(false);
        // No auth (falls ihr User/Pass habt, sag Bescheid, dann bauen wir es ein)

        client.connect(opts);

        // Subscribe zuerst - Broker sendet retained messages sofort (wenn retain=1 gesetzt ist, wie in eurem Router-Script)
        client.subscribe(TOPIC_VIN, 0);
        client.subscribe(TOPIC_VEHICLE_ID, 0);

        boolean ok = latch.await(WAIT_SECONDS, TimeUnit.SECONDS);

        if (ok && r.vehicleId != null && !r.vehicleId.isEmpty()) {
            r.status = "OK";
        } else {
            r.status = "TIMEOUT";
        }

        try {
            client.disconnect();
        } catch (Exception ignored) {}
        try {
            client.close();
        } catch (Exception ignored) {}
    }

    private String resolveDockMac() {
        List<String> preferred = List.of("eth0", "usb0", "rndis0");

        try {
            List<NetworkInterface> all = Collections.list(NetworkInterface.getNetworkInterfaces());

            for (String name : preferred) {
                for (NetworkInterface nif : all) {
                    if (nif != null && name.equalsIgnoreCase(nif.getName())) {
                        String mac = macOf(nif);
                        if (mac != null) return mac;
                    }
                }
            }

            for (NetworkInterface nif : all) {
                String mac = macOf(nif);
                if (mac != null) return mac;
            }

            return "NOT_FOUND";
        } catch (Exception e) {
            return "ERROR";
        }
    }

    private String macOf(NetworkInterface nif) {
        try {
            if (nif == null) return null;
            byte[] macBytes = nif.getHardwareAddress();
            if (macBytes == null || macBytes.length == 0) return null;

            StringBuilder sb = new StringBuilder();
            for (byte b : macBytes) sb.append(String.format("%02X:", b));
            sb.deleteCharAt(sb.length() - 1);
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void writeToFile(String filename, String content) {
        try {
            File dir = getExternalFilesDir(null); // /storage/emulated/0/Android/data/<pkg>/files/
            if (dir == null) return;
            File out = new File(dir, filename);
            try (FileOutputStream fos = new FileOutputStream(out, false)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
        } catch (Exception ignored) {
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String esc(String s) {
        // minimal JSON escaping
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static class Result {
        String dockMac;
        String vin;
        String vehicleId;
        String status = "INIT";
        String error = "";
    }
}
