package org.concord.sensor.server;

import java.awt.EventQueue;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.concord.sensor.ExperimentConfig;
import org.concord.sensor.ExperimentRequest;
import org.concord.sensor.SensorConfig;
import org.concord.sensor.SensorRequest;
import org.concord.sensor.device.SensorDevice;
import org.concord.sensor.device.impl.DeviceConfigImpl;
import org.concord.sensor.device.impl.DeviceID;
import org.concord.sensor.device.impl.JavaDeviceFactory;
import org.concord.sensor.device.impl.SensorConfigImpl;
import org.concord.sensor.impl.ExperimentRequestImpl;
import org.concord.sensor.impl.Range;
import org.concord.sensor.impl.SensorRequestImpl;
import org.concord.sensor.server.data.DataSink;

import com.continuent.tungsten.fsm.core.Action;
import com.continuent.tungsten.fsm.core.Entity;
import com.continuent.tungsten.fsm.core.Event;
import com.continuent.tungsten.fsm.core.EventTypeGuard;
import com.continuent.tungsten.fsm.core.FiniteStateException;
import com.continuent.tungsten.fsm.core.PositiveGuard;
import com.continuent.tungsten.fsm.core.State;
import com.continuent.tungsten.fsm.core.StateMachine;
import com.continuent.tungsten.fsm.core.StateTransitionMap;
import com.continuent.tungsten.fsm.core.StateType;
import com.continuent.tungsten.fsm.core.Transition;
import com.continuent.tungsten.fsm.core.TransitionFailureException;
import com.continuent.tungsten.fsm.core.TransitionRollbackException;

public class SensorStateManager {
	private StateMachine stateMachine;
	private final HashMap<String, Action> actions = new HashMap<String, Action>();

	private final static Logger logger = LogManager.getLogger(SensorStateManager.class.getName());
	
	private static final int MAX_READ_ERRORS = 7;

	private JavaDeviceFactory deviceFactory;
	private SensorDevice device;
	private ScheduledExecutorService executor;
	private ScheduledFuture<?> collectionTask;
	private String executorThreadName;

	private ExperimentConfig reportedConfig = null;
	private long reportedConfigLoadedAt = 0;
	
	private DataSink datasink;

	public SensorStateManager(DataSink datasink) throws FiniteStateException {
		this.datasink = datasink;
		StateTransitionMap map = generateStateTransitionMap();
		Entity sensorEntity = new Entity(){};
		stateMachine = new StateMachine(map, sensorEntity);
		try {
			// Trigger the transition to DISCONNECTED:NORMAL
			stateMachine.applyEvent(new Event(null));
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	public void start() {
		try {
			stateMachine.applyEvent(new StartEvent());
		} catch (FiniteStateException | InterruptedException e) {
			e.printStackTrace();
		}
	}

	public void stop() {
		try {
			stateMachine.applyEvent(new StopEvent());
		} catch (FiniteStateException | InterruptedException e) {
			e.printStackTrace();
		}
	}

	public void connect() {
		try {
			stateMachine.applyEvent(new ConnectEvent());
		} catch (FiniteStateException | InterruptedException e) {
			e.printStackTrace();
		}
	}

	public void disconnect() {
		try {
			stateMachine.applyEvent(new DisconnectEvent());
		} catch (FiniteStateException | InterruptedException e) {
			e.printStackTrace();
		}
	}
	
	public void terminate() {
		try {
			stateMachine.applyEvent(new TerminateEvent());
		} catch (FiniteStateException | InterruptedException e) {
			e.printStackTrace();
		}
	}
	
	public State currentState() {
		return stateMachine.getState();
	}
	
	private StateTransitionMap generateStateTransitionMap() throws FiniteStateException {
		initializeActions();
		StateTransitionMap map = new StateTransitionMap();
		
		// First, set up the various states
		State initializing = new State("INITIALIZING", StateType.START);
		
		State disconnected = new State("DISCONNECTED", StateType.ACTIVE);
		State disconnectedNormal = new State("NORMAL", StateType.ACTIVE, disconnected, actions.get("transitionToConnect"), null);
		State disconnectedError  = new State("ERROR",  StateType.ACTIVE, disconnected, actions.get("reinitialize"), null);
		
		State connected = new State("CONNECTED", StateType.ACTIVE, actions.get("connect"), actions.get("disconnect"));
		State connectedPolling = new State("POLLING", StateType.ACTIVE, connected, actions.get("startPolling"), actions.get("stopPolling"));
		State connectedCollecting = new State("COLLECTING", StateType.ACTIVE, connected, actions.get("startCollecting"), actions.get("stopCollecting"));
		
		State finished = new State("FINISHED", StateType.END, actions.get("terminate"), null);
		
		map.addState(initializing);
		map.addState(disconnected);
		map.addState(disconnectedNormal);
		map.addState(disconnectedError);
		map.addState(connected);
		map.addState(connectedPolling);
		map.addState(connectedCollecting);
		map.addState(finished);
		
		// Thebn, define the transitions between states
		EventTypeGuard startGuard = new EventTypeGuard(StartEvent.class);
		EventTypeGuard stopGuard = new EventTypeGuard(StopEvent.class);
		EventTypeGuard connectGuard = new EventTypeGuard(ConnectEvent.class);
		EventTypeGuard disconnectGuard = new EventTypeGuard(DisconnectEvent.class);
		EventTypeGuard terminateGuard = new EventTypeGuard(TerminateEvent.class);
		
		Transition init2disco = new Transition("INITIALIZING-TO-DISCONNECTED", new PositiveGuard(), initializing, actions.get("initialize"), disconnectedNormal);
		Transition disco2connected = new Transition("DISCONNECTED-TO-CONNECTED", connectGuard, disconnectedNormal, null, connectedPolling);
		Transition error2connected = new Transition("ERROR-TO-CONNECTED", connectGuard, disconnectedError, null, connectedPolling);
		Transition polling2collecting = new Transition("POLLING-TO-COLLECTING", startGuard, connectedPolling, null, connectedCollecting);
		Transition collecting2polling = new Transition("COLLECTING-TO-POLLING", stopGuard, connectedCollecting, null, connectedPolling);

		Transition collecting2disconnected = new Transition("COLLECTING-TO-DISCONNECTED", disconnectGuard, connectedCollecting, null, disconnectedNormal);
		Transition polling2disconnected = new Transition("POLLING-TO-DISCONNECTED", disconnectGuard, connectedPolling, null, disconnectedNormal);
		
		Transition connected2error = new Transition("CONNECTED-TO-ERROR", new PositiveGuard(), connected, null, disconnectedError);
		Transition disconnected2error = new Transition("DISCONNECTED-TO-ERROR", new PositiveGuard(), disconnected, null, disconnectedError);
		
		Transition connected2finished = new Transition("CONNECTED-TO-FINISHED", terminateGuard, connected, null, finished);
		Transition disconnected2finished = new Transition("DISCONNECTED-TO-FINISHED", terminateGuard, disconnected, null, finished);
		
		map.addTransition(init2disco);
		map.addTransition(disco2connected);
		map.addTransition(error2connected);
		map.addTransition(polling2collecting);
		map.addTransition(collecting2polling);
		map.addTransition(collecting2disconnected);
		map.addTransition(polling2disconnected);
		map.addTransition(connected2finished);
		map.addTransition(disconnected2finished);
		
		map.addTransition(connected2error);
		map.addTransition(disconnected2error);
		
		map.setErrorState(disconnectedError);
		
		map.build();
		
		return map;
	}

	private void initializeActions() {
		// Define the actions that happen on state transitions
		actions.put("initialize", new Action() {
			@Override
			public void doAction(Event message, Entity entity, Transition transition, int actionType) throws TransitionRollbackException, TransitionFailureException, InterruptedException {
				// Then, set up all the pre-requisite classes, thread processors, etc.
				deviceFactory = new JavaDeviceFactory();
				executor = Executors.newSingleThreadScheduledExecutor();
				ScheduledFuture<?> task = executor.schedule(new Runnable() {
					public void run() {
						executorThreadName = Thread.currentThread().getName();
					}
				}, 0, TimeUnit.MILLISECONDS);
				try {
					task.get();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
		
		actions.put("transitionToConnect", new Action() {
			@Override
			public void doAction(Event message, Entity entity, Transition transition, int actionType) throws TransitionRollbackException, TransitionFailureException, InterruptedException {
				EventQueue.invokeLater(new Runnable() {
					@Override
					public void run() {
						SensorStateManager.this.connect();
					}
				});
			}
		});
		
		actions.put("reinitialize", new Action() {
			@Override
			public void doAction(Event message, Entity entity, Transition transition, int actionType) throws TransitionRollbackException, TransitionFailureException, InterruptedException {
				// make sure we've destroyed any prior sessions
				actions.get("terminate").doAction(message, entity, transition, actionType);
				actions.get("initialize").doAction(message, entity, transition, actionType);
				actions.get("transitionToConnect").doAction(message, entity, transition, actionType);
			}
		});

		actions.put("connect", new Action() {
			@Override
			public void doAction(Event message, Entity entity, Transition transition, int actionType) throws TransitionRollbackException, TransitionFailureException, InterruptedException {
				// TODO Scan to see which devices are connected, and then connect with that device type
				Runnable r = new Runnable() {
					public void run() {
						logger.debug("Creating device: " + Thread.currentThread().getName());
						device = deviceFactory.createDevice(new DeviceConfigImpl(DeviceID.VERNIER_GO_LINK_JNA, null));
						
						// Check if we're attached
						logger.debug("Checking attached: " + Thread.currentThread().getName());
						boolean deviceIsAttached = device.isAttached();
						if (!deviceIsAttached) {
							// try re-opening the device
							try {
								device.close();
								device.open(null);
								deviceIsAttached = device.isAttached();
							} catch (Exception e) {
								deviceIsAttached = false;
							}
							// we're still not attached. Error.
							if (!deviceIsAttached) {
								throw new RuntimeException("Device not attached!");
							}
						}
					}
				};

				if (!executeAndWait(r)) {
					// device wasn't attached. Error.
					throw new TransitionFailureException("Device not attached!", message, entity, transition, actionType, null);
				}
			}
		});

		actions.put("disconnect", new Action() {
			@Override
			public void doAction(Event message, Entity entity, Transition transition, int actionType) throws TransitionRollbackException, TransitionFailureException, InterruptedException {
				// make sure we've destroyed any prior sessions
				if (device != null) {
					Runnable r = new Runnable() {
						public void run() {
							deviceFactory.destroyDevice(device);
							device = null;
						}
					};
					executeAndWait(r);
				}
			}
		});

		actions.put("startPolling", new Action() {
			@Override
			public void doAction(Event message, Entity entity, Transition transition, int actionType) throws TransitionRollbackException, TransitionFailureException, InterruptedException {
				datasink.startNewCollection(getDeviceConfig());
				
				Runnable r2 = new Runnable() {
					@Override
					public void run() {
						try {
							readSingleValue();
						} catch (Exception e) {
							logger.error("Failed to read data from the device!", e);
						}
					}
					
				};
				collectionTask = executor.scheduleAtFixedRate(r2, 1, 1, TimeUnit.SECONDS);
			}
		});

		actions.put("stopPolling", new Action() {
			@Override
			public void doAction(Event message, Entity entity, Transition transition, int actionType) throws TransitionRollbackException, TransitionFailureException, InterruptedException {
				collectionTask.cancel(false);
			}
		});

		actions.put("startCollecting", new Action() {
			@Override
			public void doAction(Event message, Entity entity, Transition transition, int actionType) throws TransitionRollbackException, TransitionFailureException, InterruptedException {
				ExperimentConfig config = getDeviceConfig();
				ExperimentRequest request;
				try {
					request = generateExperimentRequest(config);
				} catch (Exception e1) {
					throw new TransitionFailureException(e1.getMessage(), message, entity, transition, actionType, null);
				}
				try {
					device.configure(request);
				} catch (RuntimeException e) {
					// force re-getting the currently attached sensors and try it again
					Thread.sleep(1000);
					config = getDeviceConfig(true);
					try {
						request = generateExperimentRequest(config);
					} catch (Exception e1) {
						throw new TransitionFailureException(e1.getMessage(), message, entity, transition, actionType, null);
					}
					device.configure(request);
				}

				final float[] data = new float[1024];
				final Runnable r = new Runnable() {
					public void run() {
						try {
							int numSamples = device.read(data, 0, numSensors, null);
							if (numSamples > 0) {
								float[] dataCopy = new float[numSamples * numSensors];
								System.arraycopy(data, 0, dataCopy, 0, numSamples * numSensors);
								datasink.appendCollectedData(numSamples, dataCopy);

								numErrors = 0;
							} else {
								// some devices (ex: GoIO) report -1 samples to indicate an error, or
								// will just report 0 samples continuously after being unplugged
								numErrors++;
							}
						} catch (Exception e) {
							numErrors++;
							logger.fatal("Error reading data from device!", e);
						}
						if (numErrors >= MAX_READ_ERRORS) {
							numErrors = 0;
							logger.fatal("Too many collection errors! Stopping device.");
							try {
								stateMachine.applyEvent(new StopEvent());
							} catch (FiniteStateException | InterruptedException e) {
								e.printStackTrace();
							}
						}
					}
				};
				numErrors = 0;
				long interval = (long) Math.floor(config.getDataReadPeriod() * 1000);
				if (interval <= 0) {
					interval = 100;
				}
				final long adjustedInterval = interval;

				Runnable start = new Runnable() {
					public void run() {
						boolean deviceIsRunning = device.start();
						if(deviceIsRunning) {
							System.out.println("started device");
							collectionTask = executor.scheduleAtFixedRate(r, 10, adjustedInterval, TimeUnit.MILLISECONDS);
						} else {
							// we should send a notification here that something went wrong
							System.err.println("error starting the device");
							try {
								stateMachine.applyEvent(new StopEvent());
							} catch (FiniteStateException | InterruptedException e) {
								e.printStackTrace();
							}
						}

					}
				};
				execute(start, 0);
			}
		});

		actions.put("stopCollecting", new Action() {
			@Override
			public void doAction(Event message, Entity entity, Transition transition, int actionType) throws TransitionRollbackException, TransitionFailureException, InterruptedException {
				if (collectionTask != null && !collectionTask.isDone()) {
					collectionTask.cancel(false);
				}
				collectionTask = null;
				Runnable r = new Runnable() {
					public void run() {
						logger.debug("Stopping device: " + Thread.currentThread().getName());
						device.stop(true);
					}
				};

				if (!executeAndWait(r)) {
					// try closing and re-opening the device
					throw new TransitionFailureException("Stopping had errors! Closing and re-opening.", message, entity, transition, actionType, null);
				}
			}
		});

		actions.put("terminate", new Action() {
			@Override
			public void doAction(Event message, Entity entity, Transition transition, int actionType) throws TransitionRollbackException, TransitionFailureException, InterruptedException {
				Runnable r = new Runnable() {
					public void run() {
						if (device != null) {
							deviceFactory.destroyDevice(device);
							device = null;
						}
					}
				};
				executeAndWait(r);
				executor.shutdownNow();
				try {
					executor.awaitTermination(5, TimeUnit.SECONDS);
					System.err.println("Shutdown completed. All tasks terminated: " + executor.isTerminated());
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				executor = null;
				executorThreadName = null;
				deviceFactory = null;
			}
		});
	}

	private ExperimentRequest generateExperimentRequest(ExperimentConfig config) throws Exception {
		if (config == null) { throw new Exception("Couldn't fetch config from device! Restarting..."); }

		SensorRequest[] sensors = getSensorsFromCurrentConfig(config);
		numSensors = sensors.length;
		if (sensors == null || numSensors < 1) { throw new Exception("No sensors attached! Restarting...");  }
		
		ExperimentRequestImpl request = new ExperimentRequestImpl();

		request.setPeriod(config.getPeriod());
		request.setNumberOfSamples(-1);

		request.setSensorRequests(sensors);
		return request;
	}

	private ExperimentConfig getDeviceConfig() {
		return getDeviceConfig(false);
	}

	private ExperimentConfig getDeviceConfig(boolean force) {
		if (device != null && (force || reportedConfig == null || (System.currentTimeMillis() - reportedConfigLoadedAt) > 1000)) {
			Runnable r = new Runnable() {
				public void run() {
					logger.debug("Getting device config: " + Thread.currentThread().getName());
					// Check what is attached, this isn't necessary if you know what you want
					// to be attached. But sometimes you want the user to see what is attached
					reportedConfig = device.getCurrentConfig();
					reportedConfigLoadedAt = System.currentTimeMillis();
				}
			};

			executeAndWait(r);

			logger.debug("DONE getting device config: " + Thread.currentThread().getName());
		}
		return reportedConfig;
	}

	private int numErrors = 0;
	private int numSensors = 0;
	private void readSingleValue() throws Exception {
		// There's probably a more efficient way of doing this.
		// GoIO devices, for instance, support one-shot data collection.
		// Perhaps other devices do as well?

		final ExperimentConfig config = getDeviceConfig();
		ExperimentRequest request = generateExperimentRequest(config);
		device.configure(request);

		Runnable start = new Runnable() {
			public void run() {
				logger.debug("starting device");
				device.start();
			}
		};
		execute(start, 0);

		final float[] buffer = new float[1024];
		final float[] data = new float[numSensors];
		Runnable r = new Runnable() {
			public void run() {
				int numCollected = 0;
				while (numErrors < MAX_READ_ERRORS && numCollected < 1) {
					logger.debug("Trying to read data from the device...");
					try {
						final int numSamples = device.read(buffer, 0, numSensors, null);
						if (numSamples > 0) {
							// read just the first value
							synchronized (data) {
								System.arraycopy(buffer, 0, data, 0, numSensors);
							}
							logger.debug("Successfully captured data!");
							numCollected++;
							numErrors = 0;
						} else {
							// some devices (ex: GoIO) report -1 samples to indicate an error, or
							// will just report 0 samples continuously after being unplugged
							numErrors++;
							Thread.sleep((int)(1000 * config.getDataReadPeriod()));
						}
					} catch (Exception e) {
						numErrors++;
						logger.fatal("Error reading data from device!", e);
					}
				}
				if (numErrors >= MAX_READ_ERRORS) {
					logger.fatal("Too many collection errors while getting single value! Stopping device.");
				}
			}
		};
		try {
			numErrors = 0;
			executeAndWait(r, 10);
			synchronized (data) {
				// A no-op, basically, just to ensure this synchronized block
				// doesn't get optimized away completely.
				numSensors = 0;
			}
			logger.debug("Returning data: " + Arrays.toString(data));
			datasink.setLastPolledData(config, data);
			return;
		} finally {
			r = new Runnable() {
				public void run() {
					logger.debug("Stopping device: " + Thread.currentThread().getName());
					device.stop(true);
				}
			};
	
			if (!executeAndWait(r)) {
				// try closing and re-opening the device
				logger.fatal("Stopping had errors! Closing and re-opening.");
			}
		}
	}
	
	private SensorRequest[] getSensorsFromCurrentConfig(ExperimentConfig deviceConfig) {
		if (deviceConfig == null || deviceConfig.getSensorConfigs() == null) {
			return null;
		}
		SensorConfig[] configs = deviceConfig.getSensorConfigs();
		SensorRequest[] reqs = new SensorRequest[configs.length];
		for (int i = 0; i < configs.length; i++) {
			SensorConfigImpl config = (SensorConfigImpl) configs[i];
			SensorRequestImpl sensorReq = new SensorRequestImpl();
			Range r = config.getValueRange();
			if (r == null) { r = new Range(-10000f, 10000f); }
			configureSensorRequest(sensorReq, 1, r.minimum, r.maximum, config.getPort(), config.getStepSize(), config.getType());
			reqs[i] = sensorReq;
		}
		return reqs;
	}

	private void configureSensorRequest(SensorRequestImpl sensor, int precision, float min, float max, int port, float step, int type) {
		sensor.setDisplayPrecision(precision);
		sensor.setRequiredMin(min);
		sensor.setRequiredMax(max);
		sensor.setPort(port);
		sensor.setStepSize(step);
		sensor.setType(type);
	}
	
	private ScheduledFuture<?> execute(Runnable r, int delay) {
		if (Thread.currentThread().getName().equals(executorThreadName)) {
			r.run();
			return null;
		} else {
			return executor.schedule(r, delay, TimeUnit.MILLISECONDS);
		}
	}

	private boolean executeAndWait(final Runnable r) {
		return executeAndWait(r, 0);
	}

	private boolean executeAndWait(final Runnable r, int delayMs) {
		try {
			ScheduledFuture<?> task = execute(r, delayMs);
			if (task != null) {
				task.get();
			}
			return true;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return false;
	}

	private class StopEvent extends Event {
		public StopEvent() {
			super(null);
		}
	}
	private class StartEvent extends Event {
		public StartEvent() {
			super(null);
		}
	}
	private class ConnectEvent extends Event {
		public ConnectEvent() {
			super(null);
		}
	}
	private class DisconnectEvent extends Event {
		public DisconnectEvent() {
			super(null);
		}
	}
	private class TerminateEvent extends Event {
		public TerminateEvent() {
			super(null);
		}
	}
}