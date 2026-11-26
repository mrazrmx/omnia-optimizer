package com.omnia.optimizer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registro de cada ciclo delimitado de bateria do dispositivo (sem dados pessoais pré-carregados).
 */
public class CycleData {
    public String id;
    public long timestamp;
    public int batteryLevel;
    public int batteryStartLevel = 0;
    public int dischargePp = 0;
    public double screenOnSeconds;
    public double screenOffSeconds;
    public double dischargeOnMah;
    public double dischargeOffMah;
    public double rateOnMahH;
    public double rateOffMahH;
    public double deepSleepSeconds;
    public double deepSleepPercent;
    public double lightBrightnessPercent;
    public double weakSignalSeconds;
    public double mobileActiveSeconds;
    public double mobileRadioMah;
    public double cpuMah;
    public double videoMah;
    public double cameraMah;
    public double audioMah;
    public double wakelockSeconds;
    public double highRefreshSeconds;
    public double batteryTempC;
    public String topApp = "";
    public double topAppMah = 0;
    public String topAppsSummary = "";
    public String userContext = "";

    public String startClockTime = "";
    public String bootId = "";
    public int systemStarts = 0;
    public boolean hasPartialRecharge = false;
    public String integrityStatus = "";
    public String integrityReason = "";
    public double screenDozeSeconds = 0;
    public double screenDozeDischargeMah = 0;
    public double gnssSeconds = 0;
    public double gnssMah = 0;
    public double mobile5gSeconds = 0;
    public String topWakelocksSummary = "";
    public int asocPercent = -1;
    public double batteryCyclesCount = -1;
    public int chargeCounterMah = -1;
    public String sentinelSeverity = "OK";
    public String sentinelSummary = "";

    public boolean isMixedWindow() {
        return "MISTO".equalsIgnoreCase(integrityStatus) || systemStarts > 0 || hasPartialRecharge;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("timestamp", timestamp);
            json.put("batteryLevel", batteryLevel);
            json.put("batteryStartLevel", batteryStartLevel);
            json.put("dischargePp", dischargePp);
            json.put("screenOnSeconds", screenOnSeconds);
            json.put("screenOffSeconds", screenOffSeconds);
            json.put("dischargeOnMah", dischargeOnMah);
            json.put("dischargeOffMah", dischargeOffMah);
            json.put("rateOnMahH", rateOnMahH);
            json.put("rateOffMahH", rateOffMahH);
            json.put("deepSleepSeconds", deepSleepSeconds);
            json.put("deepSleepPercent", deepSleepPercent);
            json.put("lightBrightnessPercent", lightBrightnessPercent);
            json.put("weakSignalSeconds", weakSignalSeconds);
            json.put("mobileActiveSeconds", mobileActiveSeconds);
            json.put("mobileRadioMah", mobileRadioMah);
            json.put("cpuMah", cpuMah);
            json.put("videoMah", videoMah);
            json.put("cameraMah", cameraMah);
            json.put("audioMah", audioMah);
            json.put("wakelockSeconds", wakelockSeconds);
            json.put("highRefreshSeconds", highRefreshSeconds);
            json.put("batteryTempC", batteryTempC);
            json.put("topApp", topApp);
            json.put("topAppMah", topAppMah);
            json.put("topAppsSummary", topAppsSummary);
            json.put("userContext", userContext);

            json.put("startClockTime", startClockTime);
            json.put("bootId", bootId);
            json.put("systemStarts", systemStarts);
            json.put("hasPartialRecharge", hasPartialRecharge);
            json.put("integrityStatus", integrityStatus);
            json.put("integrityReason", integrityReason);
            json.put("screenDozeSeconds", screenDozeSeconds);
            json.put("screenDozeDischargeMah", screenDozeDischargeMah);
            json.put("gnssSeconds", gnssSeconds);
            json.put("gnssMah", gnssMah);
            json.put("mobile5gSeconds", mobile5gSeconds);
            json.put("topWakelocksSummary", topWakelocksSummary);
            json.put("asocPercent", asocPercent);
            json.put("batteryCyclesCount", batteryCyclesCount);
            json.put("chargeCounterMah", chargeCounterMah);
            json.put("sentinelSeverity", sentinelSeverity);
            json.put("sentinelSummary", sentinelSummary);
        } catch (Exception ignored) {}
        return json;
    }

    public static CycleData fromJson(JSONObject json) {
        CycleData d = new CycleData();
        try {
            d.id = json.optString("id");
            d.timestamp = json.optLong("timestamp");
            d.batteryLevel = json.optInt("batteryLevel");
            d.batteryStartLevel = json.optInt("batteryStartLevel", 0);
            d.dischargePp = json.optInt("dischargePp", 0);
            d.screenOnSeconds = json.optDouble("screenOnSeconds", 0);
            d.screenOffSeconds = json.optDouble("screenOffSeconds", 0);
            d.dischargeOnMah = json.optDouble("dischargeOnMah", 0);
            d.dischargeOffMah = json.optDouble("dischargeOffMah", 0);
            d.rateOnMahH = json.optDouble("rateOnMahH", 0);
            d.rateOffMahH = json.optDouble("rateOffMahH", 0);
            d.deepSleepSeconds = json.optDouble("deepSleepSeconds", 0);
            d.deepSleepPercent = json.optDouble("deepSleepPercent", 0);
            d.lightBrightnessPercent = json.optDouble("lightBrightnessPercent", 0);
            d.weakSignalSeconds = json.optDouble("weakSignalSeconds", 0);
            d.mobileActiveSeconds = json.optDouble("mobileActiveSeconds", 0);
            d.mobileRadioMah = json.optDouble("mobileRadioMah", 0);
            d.cpuMah = json.optDouble("cpuMah", 0);
            d.videoMah = json.optDouble("videoMah", 0);
            d.cameraMah = json.optDouble("cameraMah", 0);
            d.audioMah = json.optDouble("audioMah", 0);
            d.wakelockSeconds = json.optDouble("wakelockSeconds", 0);
            d.highRefreshSeconds = json.optDouble("highRefreshSeconds", 0);
            d.batteryTempC = json.optDouble("batteryTempC", 0);
            d.topApp = json.optString("topApp", "");
            d.topAppMah = json.optDouble("topAppMah", 0);
            d.topAppsSummary = json.optString("topAppsSummary", "");
            d.userContext = json.optString("userContext", "");

            d.startClockTime = json.optString("startClockTime", "");
            d.bootId = json.optString("bootId", "");
            d.systemStarts = json.optInt("systemStarts", 0);
            d.hasPartialRecharge = json.optBoolean("hasPartialRecharge", false);
            d.integrityStatus = json.optString("integrityStatus", "");
            d.integrityReason = json.optString("integrityReason", "");
            d.screenDozeSeconds = json.optDouble("screenDozeSeconds", 0);
            d.screenDozeDischargeMah = json.optDouble("screenDozeDischargeMah", 0);
            d.gnssSeconds = json.optDouble("gnssSeconds", 0);
            d.gnssMah = json.optDouble("gnssMah", 0);
            d.mobile5gSeconds = json.optDouble("mobile5gSeconds", 0);
            d.topWakelocksSummary = json.optString("topWakelocksSummary", "");
            d.asocPercent = json.optInt("asocPercent", -1);
            d.batteryCyclesCount = json.optDouble("batteryCyclesCount", -1);
            d.chargeCounterMah = json.optInt("chargeCounterMah", -1);
            d.sentinelSeverity = json.optString("sentinelSeverity", "OK");
            d.sentinelSummary = json.optString("sentinelSummary", "");
        } catch (Exception ignored) {}
        return d;
    }

    public static File getHistoryFile() {
        File dir = new File(StorageManager.BASE_DIR_PATH);
        dir.mkdirs();
        return new File(dir, "historico_vitalicio.json");
    }

    public static synchronized void saveCycle(CycleData cycle) {
        List<CycleData> incoming = new ArrayList<CycleData>();
        incoming.add(cycle);
        mergeCycles(incoming, true);
    }

    public static synchronized int[] mergeCycles(List<CycleData> incoming, boolean overwriteMatching) {
        List<CycleData> existing = loadAllCycles();
        Map<String, CycleData> map = new LinkedHashMap<String, CycleData>();
        for (CycleData c : existing) {
            if (c.id != null && !c.id.isEmpty()) {
                map.put(c.id, c);
            }
        }

        int added = 0;
        int updated = 0;
        for (CycleData c : incoming) {
            if (c.id == null || c.id.isEmpty()) continue;
            CycleData cur = map.get(c.id);
            if (cur == null) {
                map.put(c.id, c);
                added++;
            } else if (overwriteMatching) {
                if ((c.userContext == null || c.userContext.isEmpty()) && cur.userContext != null && !cur.userContext.isEmpty()) {
                    c.userContext = cur.userContext;
                }
                map.put(c.id, c);
                updated++;
            } else if ((cur.userContext == null || cur.userContext.isEmpty()) && c.userContext != null && !c.userContext.isEmpty()) {
                cur.userContext = c.userContext;
                updated++;
            }
        }

        List<CycleData> merged = new ArrayList<CycleData>(map.values());
        writeCycles(merged);
        return new int[]{added, updated};
    }

    private static void writeCycles(List<CycleData> list) {
        Collections.sort(list, new Comparator<CycleData>() {
            @Override
            public int compare(CycleData o1, CycleData o2) {
                return Long.compare(o2.timestamp, o1.timestamp);
            }
        });

        try {
            JSONArray arr = new JSONArray();
            for (CycleData c : list) {
                arr.put(c.toJson());
            }
            FileWriter fw = new FileWriter(getHistoryFile());
            fw.write(arr.toString(2));
            fw.flush();
            fw.close();
        } catch (Exception ignored) {}
    }

    public static synchronized List<CycleData> loadAllCycles() {
        List<CycleData> list = new ArrayList<CycleData>();
        File file = getHistoryFile();
        if (!file.exists()) return list;

        try {
            BufferedReader br = new BufferedReader(new FileReader(file));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            br.close();
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                list.add(fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {}

        Collections.sort(list, new Comparator<CycleData>() {
            @Override
            public int compare(CycleData o1, CycleData o2) {
                return Long.compare(o2.timestamp, o1.timestamp);
            }
        });
        return list;
    }
}
