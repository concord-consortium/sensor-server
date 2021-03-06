package org.concord.sensor.server.data;

import java.util.ArrayList;
import java.util.Arrays;

import net.minidev.json.JSONObject;

import org.concord.sensor.ExperimentConfig;
import org.concord.sensor.SensorConfig;
import org.concord.sensor.SensorDefaults;
import org.concord.sensor.device.impl.SensorConfigImpl;
import org.concord.sensor.impl.ExperimentConfigImpl;
import org.concord.sensor.impl.Range;

public class DataCollection {
	private static final Object flag = new Object();
	private static int COUNT = 0;
	private final int id;
	private ArrayList<SensorConfig> sensorConfigs;
	private int samplesCollected = 0;
	private long lastCollectedTime = 0;
	private long lastPolledTimestamp = 0;
	private float[] lastPolledData;
	private ArrayList<ArrayList<Float>> sensorsData;
	private float lastSampleTime = 0;
	private float dt;

	public DataCollection(ExperimentConfig config) {
		synchronized (flag) {
			id = 110 + COUNT;
			COUNT += 10; // for now, don't support more than 9 sensors (plus 1 time) per device
		}
		updateSensors(config);
	}
	
	private DataCollection(DataCollection collection) {
		synchronized (flag) {
			id = 110 + COUNT;
			COUNT += 10; // for now, don't support more than 9 sensors (plus 1 time) per device
		}
		dt = collection.dt;
		lastPolledData = collection.lastPolledData.clone();
		lastPolledTimestamp = collection.lastPolledTimestamp;
		sensorConfigs = (ArrayList<SensorConfig>) collection.sensorConfigs.clone();
		sensorsData = new ArrayList<ArrayList<Float>>();
		
		for (int i = 0; i < sensorConfigs.size(); i++) {
			sensorsData.add(new ArrayList<Float>());
		}
	}
	
	public synchronized void appendCollectedData(int numSamples, float[] data) {
		int idx = 0;
		float[] lastCollected = new float[sensorConfigs.size()-1];
		for (int sensor = 0; sensor < sensorConfigs.size(); sensor++) {
			ArrayList<Float> sensorData = sensorsData.get(sensor);
			for (int sample = 0; sample < numSamples; sample++) {
				if (sensor == 0) {
					// dt-based time value
					sensorData.add(lastSampleTime);
					lastSampleTime += dt;
				} else {
					idx = sample*(sensorConfigs.size()-1) + (sensor-1);
					sensorData.add(data[idx]);
				}
			}
			if (sensor > 0) {
				lastCollected[sensor-1] = sensorData.get(sensorData.size()-1);
			}
		}
		samplesCollected += numSamples;
		lastCollectedTime = System.currentTimeMillis();
		setLastPolledData(lastCollected);
	}
	
	public float[][] getCollectedData() {
		synchronized (sensorsData) {
			float[][] data = new float[sensorConfigs.size()][samplesCollected];
			for (int sensor = 0; sensor < sensorConfigs.size(); sensor++) {
				ArrayList<Float> sensorData = sensorsData.get(sensor);
				for (int idx = 0; idx < samplesCollected; idx++) {
					data[sensor][idx] = sensorData.get(idx);
				}
			}
			return data;
		}
	}
	
	public void setLastPolledData(float[] data) {
		if ((data.length+1) != sensorConfigs.size()) {
			// the number of returned values and the number of sensors differ!
			return;
		}

		for (int i = 0; i < data.length; i++) {
			lastPolledData[i+1] = data[i];
		}
		lastPolledTimestamp = System.currentTimeMillis();
	}

	public float[] getLastPolledData() {
		return lastPolledData;
	}

	public int getId() {
		return id;
	}
	
	public JSONObject getColumnInfo() {
		JSONObject allInfo = new JSONObject();
		for (int i = 0; i < sensorConfigs.size(); i++) {
			SensorConfig config = sensorConfigs.get(i);
			int sensorId = id + i;

			String unit = config.getUnit();
			if (unit == null) {
				unit = "";
			}
			
			JSONObject sensorInfo = new JSONObject();
			sensorInfo.put("id", ""+sensorId);
			sensorInfo.put("setID", ""+id);
			sensorInfo.put("position", i);
			sensorInfo.put("name", config.getName());
			sensorInfo.put("units", unit);
			sensorInfo.put("valueCount", samplesCollected);
			sensorInfo.put("valuesTimeStamp", lastCollectedTime);
			sensorInfo.put("liveValue", ""+lastPolledData[i]);
			sensorInfo.put("liveValueTimeStamp", lastPolledTimestamp);
			
			allInfo.put(""+sensorId, sensorInfo);
		}
		return allInfo;
	}

	public int getNumberOfSensors() {
		return sensorConfigs.size();
	}

	// return true if the set of sensors is the same, and no data has been collected
	public boolean isPristine(ExperimentConfig config) {
		if (samplesCollected == 0) {
			if (hasSameSensors(config)) {
				return true;
			}
		}
		return false;
	}
	
	private boolean hasSameSensors(ExperimentConfig config) {
		SensorConfig[] sensors = (config == null ? null : config.getSensorConfigs());
		if (sensors == null) {
			sensors = new SensorConfig[] {};
		}
		if (sensors.length != sensorConfigs.size()-1) {
			return false;
		}
		
		for (int i = 1; i < sensorConfigs.size(); i++) {
			SensorConfig newSensor = sensors[i-1];
			SensorConfig oldSensor = sensorConfigs.get(i);
			
			if (newSensor.getType() != oldSensor.getType()) { return false; }
			if (newSensor.getName() != oldSensor.getName()) { return false; }
		}
		return true;
	}

	public int getNumberOfSamples() {
		return samplesCollected;
	}

	public void updateSensors(ExperimentConfig config) {
		SensorConfig[] configs = (config == null ? null : config.getSensorConfigs());
		if (configs == null) {
			configs = new SensorConfig[] {};
		}

		dt = (config == null ? SensorDefaults.DEFAULT_PERIOD : config.getPeriod());
		if (config instanceof ExperimentConfigImpl) {
			Range r = ((ExperimentConfigImpl) config).getPeriodRange();
			if (r != null && r.minimum > 0) {
				dt = r.minimum;
			}
		}
		if (dt == 0) { dt = SensorDefaults.DEFAULT_PERIOD; }
		if (dt < SensorDefaults.MIN_PERIOD) { dt = SensorDefaults.MIN_PERIOD; }

		sensorConfigs = new ArrayList<SensorConfig>();

		// fake time sensor so we can properly export time column values
		SensorConfigImpl timeConfig = new SensorConfigImpl();
		timeConfig.setUnit("s");
		timeConfig.setName("Time");
		sensorConfigs.add(timeConfig); // Time is always the 0-index sensor
		sensorConfigs.addAll(Arrays.asList(configs));

		sensorsData = new ArrayList<ArrayList<Float>>();
		
		lastPolledData = new float[sensorConfigs.size()];
		for (int i = 0; i < sensorConfigs.size(); i++) {
			sensorsData.add(new ArrayList<Float>());
			lastPolledData[i] = 0;
		}
	}

	public String[] getUnits() {
		if (sensorConfigs.size() > 0) {
			String[] units = new String[sensorConfigs.size()];
			for (int i = 0; i < sensorConfigs.size(); i++) {
				SensorConfig config = sensorConfigs.get(i);
				units[i] = config.getUnit();
			}
			return units;
		} else {
			return new String[] {};
		}
	}
	
	public DataCollection clone() {
		DataCollection clone = new DataCollection(this);
		return clone;
	}
}
