package com.almende.eve.agent;

import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import com.almende.eve.config.Config;
import com.almende.eve.context.Context;
import com.almende.eve.context.ContextFactory;
import com.almende.eve.json.JSONRPC;
import com.almende.eve.json.JSONRequest;
import com.almende.eve.json.JSONResponse;
import com.almende.eve.scheduler.Scheduler;
import com.almende.eve.service.AsyncCallback;
import com.almende.eve.service.Service;
import com.almende.eve.service.http.HttpService;
import com.almende.util.ClassUtil;

/**
 * The AgentFactory is a factory to instantiate and invoke Eve Agents within the 
 * configured context. The AgentFactory can invoke local as well as remote 
 * agents.
 * 
 * An AgentFactory must be instantiated with a valid Eve configuration file.
 * This configuration is needed to load the configured agent classes and 
 * instantiate a context for each agent.
 * 
 * Example usage:
 *     // generic constructor
 *     Config config = new Config("eve.yaml");
 *     AgentFactory factory = new AgentFactory(config);
 *     
 *     // construct in servlet
 *     InputStream is = getServletContext().getResourceAsStream("/WEB-INF/eve.yaml");
 *     Config config = new Config(is);
 *     AgentFactory factory = new AgentFactory(config);
 *     
 *     // create or get a shared instance of the AgentFactory
 *     AgentFactory factory = AgentFactory.createInstance(namespace, config);
 *     AgentFactory factory = AgentFactory.getInstance(namespace);
 *     
 *     // invoke a local agent by its id
 *     response = factory.invoke(agentId, request); 
 *
 *     // invoke a local or remote agent by its url
 *     response = factory.send(senderId, receiverUrl, request);
 *     
 *     // create a new agent
 *     Agent agent = factory.createAgent(agentClass, agentId);
 *     String desc = agent.getDescription(); // use the agent
 *     agent.destroy(); // neatly shutdown the agents context
 *     
 *     // instantiate an existing agent
 *     Agent agent = factory.getAgent(agentId);
 *     String desc = agent.getDescription(); // use the agent
 *     agent.destroy(); // neatly shutdown the agents context
 * 
 * @author jos
 */
public class AgentFactory {
	public AgentFactory () {
		addService(new HttpService(this));
	}
	
	/**
	 * Construct an AgentFactory and initialize the configuration
	 * @param config
	 * @throws Exception
	 */
	public AgentFactory(Config config) throws Exception {
		setConfig(config);

		addService(new HttpService(this));
	}
	
	/**
	 * Get a shared AgentFactory instance with the default namespace "default"
	 * @return factory     Returns the factory instance, or null when not 
	 *                     existing 
	 */
	public static AgentFactory getInstance() {
		return getInstance(null);
	}

	/**
	 * Get a shared AgentFactory instance with a specific namespace
	 * @param namespace    If null, "default" namespace will be loaded.
	 * @return factory     Returns the factory instance, or null when not 
	 *                     existing 
	 */
	public static AgentFactory getInstance(String namespace) {
		if (namespace == null) {
			namespace = "default";
		}
		return factories.get(namespace);
	}
	
	/**
	 * Create a shared AgentFactory instance with the default namespace "default"
	 * @param config
	 * @return factory
	 */
	public static synchronized AgentFactory createInstance(Config config) 
			throws Exception{
		return createInstance(null, config);
	}

	/**
	 * Create a shared AgentFactory instance with a specific namespace
	 * @param namespace    If null, "default" namespace will be loaded.
	 * @param config
	 * @return factory
	 * @throws Exception 
	 */
	public static synchronized AgentFactory createInstance(String namespace, 
			Config config) throws Exception {
		if (namespace == null) {
			namespace = "default";
		}
		
		if (factories.containsKey(namespace)) {
			throw new Exception("AgentFactory with namespace '" + namespace +
					"' already exists");
		}
		
		AgentFactory factory = new AgentFactory(config);
		factories.put(namespace, factory);
		
		return factory;
	}

	/**
	 * Get an agent by its id. Returns null if the agent does not exist
	 * 
	 * Before deleting the agent, the method agent.destroy() must be executed
	 * to neatly shutdown the instantiated context.
	 * 
	 * @param agentId
	 * @return agent
	 * @throws Exception
	 */
	public Agent getAgent(String agentId) throws Exception {
		// load the context
		Context context = null; 
		context = getContextFactory().get(agentId);
		if (context == null) {
			// agent does not exist
			return null;
		}
		context.init();
		
		// read the agents class name from context
		Class<?> agentClass = context.getAgentClass();
		if (agentClass == null) {
			throw new Exception("Cannot instantiate agent. " +
					"Class information missing in the agents context " +
					"(agentId='" + agentId + "')");
		}
		
		// instantiate the agent
		Agent agent = (Agent) agentClass.getConstructor().newInstance();
		agent.setAgentFactory(this);
		agent.setContext(context);
		agent.init();
		
		return agent;
	}
	
	/**
	 * Create an agent.
	 * 
	 * Before deleting the agent, the method agent.destroy() must be executed
	 * to neatly shutdown the instantiated context.
	 * 
	 * @param agentClass  full class path
	 * @param agentId
	 * @return
	 * @throws Exception
	 */
	public Agent createAgent(String agentClass, String agentId) throws Exception {
		return (Agent) createAgent(Class.forName(agentClass), agentId);
	}
	
	/**
	 * Create an agent.
	 * 
	 * Before deleting the agent, the method agent.destroy() must be executed
	 * to neatly shutdown the instantiated context.
	 * 
	 * @param agentClass
	 * @param agentId
	 * @return
	 * @throws Exception
	 */
	public Agent createAgent(Class<?> agentClass, String agentId) throws Exception {
		if (!ClassUtil.hasSuperClass(agentClass, Agent.class)) {
			throw new Exception(
					"Class " + agentClass + " does not extend class " + Agent.class);
		}
		
		// instantiate the agent
		Agent agent = (Agent) agentClass.getConstructor().newInstance();
		
		// instantiate the context
		Context context = getContextFactory().create(agentId);
		context.setAgentClass(agentClass);
		agent.setAgentFactory(this);
		agent.setContext(context);
		context.init();
		agent.init();
		
		return agent;
	}
	
	/**
	 * Delete an agent
	 * @param agentId
	 * @throws Exception 
	 */
	public void deleteAgent(String agentId) throws Exception {
		getContextFactory().delete(agentId);
	}
	
	/**
	 * Test if an agent exists
	 * @param agentId
	 * @return true if the agent exists
	 * @throws Exception 
	 */
	public boolean hasAgent(String agentId) throws Exception {
		return getContextFactory().exists(agentId);
	}

	/**
	 * Invoke a local agent
	 * @param agentId
	 * @param request
	 * @return
	 * @throws Exception
	 */
	// TOOD: cleanup this method?
	public JSONResponse invoke(String agentId, JSONRequest request) throws Exception {
		Agent agent = getAgent(agentId);
		if (agent != null) {
			JSONResponse response = JSONRPC.invoke(agent, request);
			agent.destroy();
			return response;
		}
		else {
			throw new Exception("Agent with id '" + agentId + "' not found");
		}
	}

	/**
	 * Invoke a local or remote agent. 
	 * In case of an local agent, the agent is invoked immediately.
	 * In case of an remote agent, an HTTP Request is sent to the concerning
	 * agent.
	 * @param sender
	 * @param receiverUrl
	 * @param request
	 * @return
	 * @throws Exception
	 */
	public JSONResponse send(Agent sender, String receiverUrl, JSONRequest request) 
			throws Exception {
		String agentId = getAgentId(receiverUrl);
		if (agentId != null) {
			return invoke(agentId, request);
		}
		else {
			Service service = null;
			String protocol = null;
			int separator = receiverUrl.indexOf(":");
			if (separator != -1) {
				protocol = receiverUrl.substring(0, separator);
				service = getService(protocol);
			}
			if (service != null) {
				return service.send(sender.getId(), receiverUrl, request);
			}
			else {
				throw new ProtocolException(
					"No service configured for protocol '" + protocol + "'.");
			}			
		}
	}
	
	/**
	 * Asynchronously invoke a request on an agent.
	 * @param sender
	 * @param receiverUrl
	 * @param request
	 * @param callback
	 * @throws Exception 
	 */
	public void sendAsync(final Agent sender, final String receiverUrl, 
			final JSONRequest request, 
			final AsyncCallback<JSONResponse> callback) throws Exception {
		final String agentId = getAgentId(receiverUrl);
		if (agentId != null) {
			new Thread(new Runnable () {
				@Override
				public void run() {
					JSONResponse response;
					try {
						response = invoke(agentId, request);
						callback.onSuccess(response);
					} catch (Exception e) {
						callback.onFailure(e);
					}
				}
			}).start();
		}
		else {
			Service service = null;
			String protocol = null;
			int separator = receiverUrl.indexOf(":");
			if (separator != -1) {
				protocol = receiverUrl.substring(0, separator);
				service = getService(protocol);
			}
			if (service != null) {
				service.sendAsync(sender.getId(), receiverUrl, request, callback);
			}
			else {
				throw new ProtocolException(
					"No service configured for protocol '" + protocol + "'.");
			}
		}
	}

	/**
	 * Get the agentId from given agentUrl. The url can be any protocol.
	 * If the url matches any of the registered services, an agentId is
	 * returned.
	 * This means that the url represents a local agent. It is possible
	 * that no agent with this id exists.
	 * @param agentUrl
	 * @return agentId
	 */
	private String getAgentId(String agentUrl) {
		for (Service service : services) {
			String agentId = service.getAgentId(agentUrl);
			if (agentId != null) {
				return agentId;
			}
		}		
		return null;
	}
	
	/**
	 * Retrieve the current environment, using the configured Context.
	 * Available values: "Production", "Development"
	 * @return environment
	 */
	public String getEnvironment() {
		return (contextFactory != null) ? contextFactory.getEnvironment() : null;
	}

	/**
	 * Set configuration file
	 * @param config   A loaded configuration file
	 * @throws Exception 
	 */
	private void setConfig(Config config) {
		this.config = config;

		initContextFactory(config);
		initServices(config);
		initScheduler(config);
	}

	/**
	 * Get the loaded config file
	 * @return config   A configuration file
	 */
	public Config getConfig() {
		return config;
	}
	
	/**
	 * Initialize the context factory. The class is read from the provided 
	 * configuration file.
	 * @param config
	 * @throws Exception
	 */
	private void initContextFactory(Config config) {
		// get the class name from the config file
		// first read from the environment specific configuration,
		// if not found read from the global configuration
		String className = config.get("context", "class");
		if (className == null) {
			throw new IllegalArgumentException(
				"Config parameter 'context.class' missing in Eve configuration.");
		}
		
		// Recognize known classes by their short name,
		// and replace the short name for the full class path
		for (String name : CONTEXT_FACTORIES.keySet()) {
			if (className.toLowerCase().equals(name.toLowerCase())) {
				className = CONTEXT_FACTORIES.get(name);
				break;
			}
		}
		
		try {
			// get the class
			Class<?> contextClass = Class.forName(className);
			if (!ClassUtil.hasSuperClass(contextClass, ContextFactory.class)) {
				throw new IllegalArgumentException(
						"Context factory class " + contextClass.getName() + 
						" must extend " + Context.class.getName());
			}
	
			// instantiate the context factory
			Map<String, Object> params = config.get("context");
			ContextFactory contextFactory = (ContextFactory) contextClass
					.getConstructor(AgentFactory.class, Map.class )
					.newInstance(this, params);

			setContextFactory(contextFactory);
			logger.info("Initialized context factory: " + contextFactory.toString());
		}
		catch (Exception e) {
			e.printStackTrace();
		}		
	}
	
	/**
	 * Set a context factory. The context factory is used to get/create/delete
	 * an agents context.
	 * @param contextFactory
	 */
	public void setContextFactory(ContextFactory contextFactory) {
		this.contextFactory = contextFactory;
	}

	/**
	 * Get the configured context factory.
	 * @return contextFactory
	 */
	public ContextFactory  getContextFactory() throws Exception {
		if (contextFactory == null) {
			throw new Exception("No context factory initialized.");
		}
		return contextFactory;
	}

	/**
	 * Initialize the scheduler. The class is read from the provided 
	 * configuration file.
	 * @param config
	 * @throws Exception
	 */
	private void initScheduler(Config config) {
		// get the class name from the config file
		// first read from the environment specific configuration,
		// if not found read from the global configuration
		String className = config.get("environment", getEnvironment(), "scheduler", "class");
		if (className == null) {
			className = config.get("scheduler", "class");
		}
		if (className == null) {
			throw new IllegalArgumentException(
				"Config parameter 'scheduler.class' missing in Eve configuration.");
		}
		
		// Recognize known classes by their short name,
		// and replace the short name for the full class path
		for (String name : SCHEDULERS.keySet()) {
			if (className.toLowerCase().equals(name.toLowerCase())) {
				className = SCHEDULERS.get(name);
				break;
			}
		}
		
		try {
			// get the class
			schedulerClass = Class.forName(className);
			if (!ClassUtil.hasSuperClass(schedulerClass, Scheduler.class)) {
				throw new IllegalArgumentException(
						"Scheduler class " + schedulerClass.getName() + 
						" must extend " + Scheduler.class.getName());
			}
			logger.info("Initialized scheduler: " + schedulerClass.getName());
		}
		catch (Exception e) {
			e.printStackTrace();
		}		
	}

	/**
	 * Initialize services for incoming and outgoing messages
	 * (for example http and xmpp services).
	 * @param config
	 */
	private void initServices(Config config) {
		if (config == null) {
			Exception e = new Exception("Configuration uninitialized");
			e.printStackTrace();
			return;
		}
		
		// create a list to hold both global and environment specific services
		List<Map<String, Object>> allServiceParams = 
				new ArrayList<Map<String, Object>>();
		
		// read global service params
		List<Map<String, Object>> globalServiceParams = 
				config.get("services");
		if (globalServiceParams != null) {
			allServiceParams.addAll(globalServiceParams);
		}

		// read service params for the current environment
		List<Map<String, Object>> environmentServiceParams = 
				config.get("environment", getEnvironment(), "services");
		if (environmentServiceParams != null) {
			allServiceParams.addAll(environmentServiceParams);
		}
		
		int index = 0;
		for (Map<String, Object> serviceParams : allServiceParams) {
			String className = (String) serviceParams.get("class");
			try {
				if (className != null) {
					// Recognize known classes by their short name,
					// and replace the short name for the full class path
					for (String name : SERVICES.keySet()) {
						if (className.toLowerCase().equals(name.toLowerCase())) {
							className = SERVICES.get(name);
							break;
						}
					}
					
					Class<?> serviceClass = Class.forName(className);
					Service service = (Service) serviceClass
							.getConstructor(AgentFactory.class)
							.newInstance(this);
					service.init(serviceParams);
					addService(service);
				}
				else {
					logger.warning("Cannot load service at index " + index + 
							": no class defined.");
				}
			}
			catch (Exception e) {
				logger.warning("Cannot load service at index " + index + 
						": " + e.getMessage());
			}
			index++;
		}
	}

	/**
	 * Add a new communication service
	 * @param service
	 */
	public void addService(Service service) {
		services.add(service);
		logger.info("Registered service: " + service.toString());
	}

	/**
	 * Remove a registered a communication service
	 * @param service
	 */
	public void removeService(Service service) {
		services.remove(service);
		logger.info("Unregistered service " + service.toString());
	}

	/**
	 * Get all registered communication services
	 * @return services
	 */
	public List<Service> getServices() {
		return services;
	}
	
	/**
	 * Get all registered communication services which can handle given protocol
	 * @param protocol   A protocol, for example "http" or "xmpp"
	 * @return services
	 */
	public List<Service> getServices(String protocol) {
		List<Service> filteredServices = new ArrayList<Service> ();
		
		for (Service service : services) {
			List<String> protocols = service.getProtocols();
			if (protocols.contains(protocol)) {
				filteredServices.add(service);
			}
		}
		
		return filteredServices;
	}
	
	/**
	 * Get the first registered service which supports given protocol. 
	 * Returns null when none of the registered services can handle
	 * the protocol.
	 * @param protocol   A protocol, for example "http" or "xmpp"
	 * @return service
	 */
	public Service getService(String protocol) {
		List<Service> services = getServices(protocol);
		if (services.size() > 0) {
			return services.get(0);
		}
		return null;
	}

	/**
	 * create a scheduler for an agent
	 * @param agentId
	 * @return scheduler
	 */
	public Scheduler createScheduler(String agentId) {
		// instantiate the scheduler
		Scheduler scheduler = null;
		try {
			scheduler = (Scheduler) schedulerClass
					.getConstructor(AgentFactory.class, String.class )
					.newInstance(this, agentId);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return scheduler;
	}
	
	// Note: the CopyOnWriteArrayList is inefficient but thread safe. 
	private List<Service> services = new CopyOnWriteArrayList<Service>();
	private ContextFactory contextFactory = null;
	private Class<?> schedulerClass = null;
	private Config config = null;

	private static Map<String, AgentFactory> factories = 
			new ConcurrentHashMap<String, AgentFactory>();  // namespace:factory

	private final static Map<String, String> CONTEXT_FACTORIES = new HashMap<String, String>();
	static {
        CONTEXT_FACTORIES.put("FileContextFactory", "com.almende.eve.context.FileContextFactory");
        CONTEXT_FACTORIES.put("MemoryContextFactory", "com.almende.eve.context.MemoryContextFactory");
        CONTEXT_FACTORIES.put("DatastoreContextFactory", "com.almende.eve.context.google.DatastoreContextFactory");
    }

	private final static Map<String, String> SCHEDULERS = new HashMap<String, String>();
	static {
		SCHEDULERS.put("RunnableScheduler",  "com.almende.eve.scheduler.RunnableScheduler");
		SCHEDULERS.put("AppEngineScheduler", "com.almende.eve.scheduler.google.AppEngineScheduler");
    }
	
	private final static Map<String, String> SERVICES = new HashMap<String, String>();
	static {
		SERVICES.put("XmppService", "com.almende.eve.service.xmpp.XmppService");
		SERVICES.put("HttpService", "com.almende.eve.service.http.HttpService");
    }
	
	private Logger logger = Logger.getLogger(this.getClass().getSimpleName());
}
