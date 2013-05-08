/**
 * @file Agent.java
 * 
 * @brief
 *        Agent is the abstract base class for all Eve agents.
 *        It provides basic functionality such as id, url, getting methods,
 *        subscribing to events, etc.
 * 
 * @license
 *          Licensed under the Apache License, Version 2.0 (the "License"); you
 *          may not
 *          use this file except in compliance with the License. You may obtain
 *          a copy
 *          of the License at
 * 
 *          http://www.apache.org/licenses/LICENSE-2.0
 * 
 *          Unless required by applicable law or agreed to in writing, software
 *          distributed under the License is distributed on an "AS IS" BASIS,
 *          WITHOUT
 *          WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See
 *          the
 *          License for the specific language governing permissions and
 *          limitations under
 *          the License.
 * 
 *          Copyright © 2010-2012 Almende B.V.
 * 
 * @author Jos de Jong, <jos@almende.org>
 * @date 2012-12-12
 */

package com.almende.eve.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;

import com.almende.eve.agent.annotation.Access;
import com.almende.eve.agent.annotation.AccessType;
import com.almende.eve.agent.annotation.EventTriggered;
import com.almende.eve.agent.annotation.Name;
import com.almende.eve.agent.annotation.Required;
import com.almende.eve.agent.annotation.Sender;
import com.almende.eve.agent.proxy.AsyncProxy;
import com.almende.eve.event.Callback;
import com.almende.eve.event.EventsFactory;
import com.almende.eve.monitor.ResultMonitor;
import com.almende.eve.monitor.ResultMonitorFactory;
import com.almende.eve.rpc.jsonrpc.JSONRPC;
import com.almende.eve.rpc.jsonrpc.JSONRPCException;
import com.almende.eve.rpc.jsonrpc.JSONRequest;
import com.almende.eve.rpc.jsonrpc.JSONResponse;
import com.almende.eve.rpc.jsonrpc.jackson.JOM;
import com.almende.eve.scheduler.Scheduler;
import com.almende.eve.state.State;
import com.almende.eve.transport.AsyncCallback;
import com.almende.eve.transport.TransportService;
import com.almende.util.AnnotationUtil;
import com.almende.util.AnnotationUtil.AnnotatedClass;
import com.almende.util.AnnotationUtil.AnnotatedMethod;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

abstract public class Agent implements AgentInterface {
	protected AgentFactory			agentFactory	= null;
	protected State					state			= null;
	protected Scheduler				scheduler		= null;
	protected ResultMonitorFactory	monitorFactory	= null;
	protected EventsFactory			eventsFactory	= null;
	
	public abstract String getDescription();
	
	public abstract String getVersion();
	
	public Agent() {
	}
	
	public void constr(AgentFactory factory, State state) {
		if (this.state == null) {
			this.agentFactory = factory;
			this.state = state;
			this.scheduler = factory.getScheduler(this);
			this.monitorFactory = new ResultMonitorFactory(this);
			this.eventsFactory = new EventsFactory(this);
		}
	}
	
	@Access(AccessType.UNAVAILABLE)
	public boolean onAccess(String senderId, String functionTag) {
		return true;
	}
	
	@Access(AccessType.UNAVAILABLE)
	public boolean onAccess(String senderId) {
		return onAccess(senderId, null);
	}
	
	/**
	 * This method is called once in the life time of an agent, at the moment
	 * the agent is being created by the AgentFactory.
	 * It can be overridden and used to perform some action when the agent
	 * is create, in that case super.create() should be called in
	 * the overridden create().
	 */
	@Access(AccessType.UNAVAILABLE)
	public void create() {
	}
	
	/**
	 * This method is called once in the life time of an agent, at the moment
	 * the agent is being deleted by the AgentFactory.
	 * It can be overridden and used to perform some action when the agent
	 * is deleted, in that case super.delete() should be called in
	 * the overridden delete().
	 */
	@Access(AccessType.UNAVAILABLE)
	public void delete() {
		// TODO: unsubscribe from all subscriptions
		
		// cancel all scheduled tasks.
		Scheduler scheduler = getScheduler();
		if (scheduler != null) {
			for (String taskId : scheduler.getTasks()) {
				scheduler.cancelTask(taskId);
			}
		}
		// remove all keys from the state
		// Note: the state itself will be deleted by the AgentFactory
		state.clear();
		
		// save the agents class again in the state
		state.put(State.KEY_AGENT_TYPE, getClass().getName());
		state = null; // forget local reference, as it can keep the State alive
						// even if the agentFactory removes the file.
	}
	
	/**
	 * This method is called when the containing AgentFactory is started.
	 * It can be overridden and used to perform some action (like alerting
	 * owners about the reboot),
	 * in that case super.boot() should be called in
	 * the overridden boot().
	 * 
	 * @throws Exception
	 */
	@Access(AccessType.UNAVAILABLE)
	public void boot() throws Exception {
		// init scheduler tasks
		getScheduler();
		// if applicable reconnect existing connections.
		List<TransportService> services = agentFactory.getTransportServices();
		if (services != null) {
			for (TransportService service : services) {
				service.reconnect(getId());
			}
		}
	}
	
	/**
	 * This method is called directly after the agent and its state is
	 * initiated.
	 * It can be overridden and used to perform some action when the agent
	 * is initialized, in that case super.init() should be called in
	 * the overridden init().
	 */
	@Access(AccessType.UNAVAILABLE)
	public void init() {
	}
	
	/**
	 * This method can is called when the agent is uninitialized, and is
	 * needed finalize the state of the agent.
	 * It can be overridden and used to perform some action when the agent
	 * is uninitialized, in that case super.destroy() should be called in
	 * the overridden destroy().
	 */
	@Access(AccessType.UNAVAILABLE)
	public void destroy() {
		getState().destroy();
	}
	
	@Override
	@Access(AccessType.UNAVAILABLE)
	protected void finalize() {
		// ensure the state is cleanup when the agent's method destroy is not
		// called.
		getState().destroy();
	}
	
	/**
	 * Get the agents state. The state contains methods get, put, etc. to
	 * write properties into a persistent state.
	 * 
	 * @param state
	 */
	@Access(AccessType.UNAVAILABLE)
	final public State getState() {
		return state;
	}
	
	/**
	 * Get the scheduler to schedule tasks for the agent to be executed later
	 * on.
	 * 
	 */
	@Access(AccessType.UNAVAILABLE)
	final public Scheduler getScheduler() {
		return scheduler;
	}
	
	/**
	 * Get the agent factory. The agent factory can create/delete agents.
	 * 
	 * @return
	 */
	@Access(AccessType.UNAVAILABLE)
	final public AgentFactory getAgentFactory() {
		return agentFactory;
	}
	
	/**
	 * Get the resultMonitorFactory, which can be used to register push/poll RPC
	 * result monitors.
	 */
	@Access(AccessType.UNAVAILABLE)
	final public ResultMonitorFactory getResultMonitorFactory() {
		return monitorFactory;
	}
	
	/**
	 * Get the eventsFactory, which can be used to subscribe and trigger events.
	 */
	@Access(AccessType.UNAVAILABLE)
	final public EventsFactory getEventsFactory() {
		return eventsFactory;
	}
	
	public void doPoll(@Name("monitorId") String monitorId) throws Exception {
		ResultMonitor monitor = ResultMonitor
				.getMonitorById(getId(), monitorId);
		if (monitor != null) {
			Object result = send(monitor.url, monitor.method, monitor.params,
					Object.class);
			if (monitor.callbackMethod != null) {
				ObjectNode params = JOM.createObjectNode();
				params.put("result",
						JOM.getInstance().writeValueAsString(result));
				send("local://" + getId(), monitor.callbackMethod, params);
			}
			if (monitor.hasCache()) {
				monitor.getCache().store(result);
			}
		}
	}
	
	private JsonNode	lastRes	= null;
	
	public void doPush(@Name("params") ObjectNode pushParams) throws Exception {
		String method = pushParams.get("method").textValue();
		ObjectNode params = (ObjectNode) pushParams.get("params");
		JSONResponse res = JSONRPC
				.invoke(this, new JSONRequest(method, params));
		
		JsonNode result = res.getResult();
		if (pushParams.has("onChange")
				&& pushParams.get("onChange").asBoolean()) {
			if (lastRes != null && lastRes.equals(result)) {
				return;
			}
			lastRes = result;
		}
		
		ObjectNode parms = JOM.createObjectNode();
		parms.put("result", result);
		parms.put("monitorId", pushParams.get("monitorId").textValue());
		
		send(pushParams.get("url").textValue(), "callbackPush", parms);
		// If callback reports "old", unregisterPush();
	}
	
	public void callbackPush(@Name("result") Object result,
			@Name("monitorId") String monitorId) {
		try {
			ResultMonitor monitor = ResultMonitor.getMonitorById(getId(),
					monitorId);
			if (monitor != null) {
				if (monitor.callbackMethod != null) {
					ObjectNode params = JOM.createObjectNode();
					params.put("result",
							JOM.getInstance().writeValueAsString(result));
					JSONRPC.invoke(this, new JSONRequest(
							monitor.callbackMethod, params));
				}
				if (monitor.hasCache()) {
					monitor.getCache().store(result);
				}
			}
		} catch (Exception e) {
			System.err.println("Couldn't run local callbackMethod for push!"
					+ monitorId);
			e.printStackTrace();
		}
	}
	
	public List<String> registerPush(@Name("params") ObjectNode pushParams,
			@Sender String senderUrl) {
		List<String> result = new ArrayList<String>();
		pushParams.put("url", senderUrl);
		ObjectNode parms = JOM.createObjectNode();
		parms.put("params", pushParams);
		
		if (pushParams.has("interval")) {
			int interval = pushParams.get("interval").intValue();
			JSONRequest request = new JSONRequest("doPush", parms);
			result.add(getScheduler()
					.createTask(request, interval, true, false));
		}
		if (pushParams.has("onEvent") && pushParams.get("onEvent").asBoolean()) {
			String event = "change"; // default
			if (pushParams.has("event")) {
				event = pushParams.get("event").textValue(); // Event param
																// overrules
			} else {
				AnnotatedClass ac = null;
				try {
					ac = AnnotationUtil.get(getClass());
					for (AnnotatedMethod method : ac.getMethods(pushParams.get(
							"method").textValue())) {
						EventTriggered annotation = method
								.getAnnotation(EventTriggered.class);
						if (annotation != null) {
							event = annotation.value(); // If no Event param,
														// get it from
														// annotation, else
														// default.
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			try {
				result.add(eventsFactory.subscribe(getFirstUrl(), event, "doPush",
						parms));
			} catch (Exception e) {
				System.err.println("Failed to register push Event");
				e.printStackTrace();
			}
		}
		return result;
	}
	
	public void unregisterPush(@Name("pushId") String id) {
		// Just assume that id is either a taskId or an Event subscription Id.
		// Both allow unknown ids, Postel's law rules!
		getScheduler().cancelTask(id);
		try {
			eventsFactory.unsubscribe(getFirstUrl(), id);
		} catch (Exception e) {
			System.err.println("Failed to unsubscribe push:" + e);
		}
	}
	

	
	/**
	 * Let an other agent subscribe to one of this agents events
	 * When the event is triggered, a callback will be send to the provided
	 * callbackUrl.
	 * 
	 * @param event
	 * @param callbackUrl
	 * @param callbackMethod
	 * @return subscriptionId
	 */
	final public String onSubscribe(@Name("event") String event,
			@Name("callbackUrl") String callbackUrl,
			@Name("callbackMethod") String callbackMethod,
			@Required(false) @Name("callbackParams") ObjectNode params) {
		return eventsFactory.onSubscribe(event, callbackUrl, callbackMethod, params);
	}
	
	/**
	 * Let an other agent unsubscribe from one of this agents events
	 * - If subscriptionId is provided, the subscription with this id will be
	 * deleted
	 * - If the parameter callbackUrl and optionally event and/or
	 * callbackMethod,
	 * all subscriptions with matching parameters will be deleted.
	 * (if only callbackUrl is provided, all subscriptions from this agent
	 * will be deleted).
	 * 
	 * @param subscriptionId
	 * @param event
	 * @param callbackUrl
	 * @param callbackMethod
	 */
	final public void onUnsubscribe(
			@Required(false) @Name("subscriptionId") String subscriptionId,
			@Required(false) @Name("event") String event,
			@Required(false) @Name("callbackUrl") String callbackUrl,
			@Required(false) @Name("callbackMethod") String callbackMethod) {
		@SuppressWarnings("unchecked")
		HashMap<String, List<Callback>> allSubscriptions = (HashMap<String, List<Callback>>) state
				.get("subscriptions");
		if (allSubscriptions == null) {
			return;
		}
		
		for (Entry<String, List<Callback>> entry : allSubscriptions.entrySet()) {
			String subscriptionEvent = entry.getKey();
			List<Callback> subscriptions = entry.getValue();
			if (subscriptions != null) {
				int i = 0;
				while (i < subscriptions.size()) {
					Callback subscription = subscriptions.get(i);
					boolean matched = false;
					if (subscriptionId != null
							&& subscriptionId.equals(subscription.id)) {
						// callback with given subscriptionId is found
						matched = true;
					} else if (callbackUrl != null
							&& callbackUrl.equals(subscription.url)) {
						if ((callbackMethod == null || callbackMethod
								.equals(subscription.method))
								&& (event == null || event
										.equals(subscriptionEvent))) {
							// callback with matching properties is found
							matched = true;
						}
					}
					
					if (matched) {
						subscriptions.remove(i);
					} else {
						i++;
					}
				}
			}
			// TODO: cleanup event list when empty
		}
		
		// store state again
		state.put("subscriptions", allSubscriptions);
	}
	
	/**
	 * Asynchronously trigger an event.
	 * the onTrigger method is called from a scheduled task, initiated in the
	 * method trigger
	 * 
	 * @param url
	 * @param method
	 * @param params
	 * @throws Exception
	 */
	final public void onTrigger(@Name("url") String url,
			@Name("method") String method, @Name("params") ObjectNode params)
			throws Exception {
		// TODO: send the trigger as a JSON-RPC 2.0 Notification
		// TODO: catch exceptions and log them here?
		send(url, method, params);
	}
	

	
	/**
	 * Get the first url of the agents urls. Returns local://<agentId> if the
	 * agent does not
	 * have any urls.
	 * 
	 * @return firstUrl
	 */
	public String getFirstUrl() {
		List<String> urls = getUrls();
		if (urls.size() > 0) {
			return urls.get(0);
		}
		return "local://" + getId();
	}
	
	/**
	 * Get all available methods of this agent
	 * 
	 * @return array
	 */
	public List<Object> getMethods() {
		return getAgentFactory().getMethods(this);
	}
	
	/**
	 * Send a request to an agent in JSON-RPC format
	 * 
	 * @param url
	 *            The url of the agent
	 * @param method
	 *            The name of the method
	 * @param params
	 *            A Object containing the parameter values of the method.
	 *            This can be an ObjectNode, Map, or POJO.
	 * @param type
	 *            The return type of the method
	 * @return
	 * @throws Exception
	 */
	@Access(AccessType.UNAVAILABLE)
	final public <T> T send(String url, String method, Object params,
			Class<T> type) throws Exception {
		// TODO: implement support for adding custom http headers (for
		// authorization for example)
		
		ObjectNode jsonParams;
		if (params instanceof ObjectNode) {
			jsonParams = (ObjectNode) params;
		} else {
			jsonParams = JOM.getInstance().convertValue(params,
					ObjectNode.class);
		}
		
		// invoke the other agent via the agentFactory, allowing the factory
		// to route the request internally or externally
		String id = UUID.randomUUID().toString();
		JSONRequest request = new JSONRequest(id, method, jsonParams);
		JSONResponse response = getAgentFactory().send(this, url, request);
		JSONRPCException err = response.getError();
		if (err != null) {
			throw err;
		}
		if (type != null && type != void.class) {
			return response.getResult(type);
		}
		
		return null;
	}
	
	/**
	 * Send a request to an agent in JSON-RPC format
	 * 
	 * @param url
	 *            The url of the agent
	 * @param method
	 *            The name of the method
	 * @return
	 * @throws Exception
	 */
	@Access(AccessType.UNAVAILABLE)
	final public <T> T send(String url, String method, Class<T> type)
			throws Exception {
		return send(url, method, null, type);
	}
	
	/**
	 * Send a request to an agent in JSON-RPC format
	 * 
	 * @param url
	 *            The url of the agent
	 * @param method
	 *            The name of the method
	 * @param params
	 *            A Object containing the parameter values of the method.
	 *            This can be an ObjectNode, Map, or POJO.
	 * @return
	 * @throws Exception
	 */
	@Access(AccessType.UNAVAILABLE)
	final public void send(String url, String method, Object params)
			throws Exception {
		send(url, method, params, void.class);
	}
	
	/**
	 * Create a proxy to an other agent. Invoked methods will be send to the
	 * actual agent via the AgentFactory.
	 * 
	 * @param url
	 * @param agentInterface
	 *            A Java Interface, extending AgentInterface
	 * @return agentProxy
	 */
	@Access(AccessType.UNAVAILABLE)
	final public <T> T createAgentProxy(String url, Class<T> agentInterface) {
		return getAgentFactory().createAgentProxy(this, url, agentInterface);
	}
	
	/**
	 * Create a proxy to an other agent. Invoked methods will be send to the
	 * actual agent via the AgentFactory.
	 * 
	 * @param url
	 * @param agentInterface
	 *            A Java Interface, extending AgentInterface
	 * @return agentProxy
	 */
	@Access(AccessType.UNAVAILABLE)
	final public <T> AsyncProxy<T> createAsyncAgentProxy(String url,
			Class<T> agentInterface) {
		return getAgentFactory().createAsyncAgentProxy(this, url,
				agentInterface);
	}
	
	/**
	 * Send a request to an agent in JSON-RPC format
	 * 
	 * @param url
	 *            The url of the agent
	 * @param method
	 *            The name of the method
	 * @return
	 * @throws Exception
	 */
	@Access(AccessType.UNAVAILABLE)
	final public void send(String url, String method) throws Exception {
		send(url, method, null, void.class);
	}
	
	/**
	 * Send an asynchronous JSON-RPC request to an agent
	 * sendAsync is not supported on Google App Engine
	 * 
	 * @param url
	 *            The url of the agent to be called
	 * @param method
	 *            The name of the method
	 * @param params
	 *            A JSONObject containing the parameter
	 *            values of the method
	 * @param callback
	 *            An AsyncCallback of which the onSuccess or
	 *            onFailure method will be executed on callback.
	 * @param type
	 *            The type of result coming from the callback.
	 * @throws Exception
	 * @throws JSONException
	 */
	@Access(AccessType.UNAVAILABLE)
	final public <T> void sendAsync(String url, String method,
			ObjectNode params, final AsyncCallback<T> callback,
			final Class<T> type) throws Exception {
		String id = UUID.randomUUID().toString();
		JSONRequest request = new JSONRequest(id, method, params);
		sendAsync(url, request, callback, type);
	}
	
	/**
	 * Send an asynchronous JSON-RPC request to an agent
	 * sendAsync is not supported on Google App Engine
	 * 
	 * @param url
	 *            The url of the agent to be called
	 * @param request
	 *            JSON-RPC request containing method and params
	 * @param callback
	 *            An AsyncCallback of which the onSuccess or
	 *            onFailure method will be executed on callback.
	 * @param type
	 *            The type of result coming from the callback.
	 * @throws Exception
	 * @throws JSONException
	 */
	@Access(AccessType.UNAVAILABLE)
	final public <T> void sendAsync(final String url,
			final JSONRequest request, final AsyncCallback<T> callback,
			final Class<T> type) throws Exception {
		
		// Create a callback to retrieve a JSONResponse and extract the result
		// or error from this.
		final AsyncCallback<JSONResponse> responseCallback = new AsyncCallback<JSONResponse>() {
			@Override
			public void onSuccess(JSONResponse response) {
				Exception err;
				try {
					err = response.getError();
				} catch (JSONRPCException e) {
					err = e;
				}
				if (err != null) {
					callback.onFailure(err);
				}
				if (type != null && type != void.class) {
					callback.onSuccess(response.getResult(type));
				} else {
					callback.onSuccess(null);
				}
			}
			
			@Override
			public void onFailure(Exception exception) {
				callback.onFailure(exception);
			}
		};
		
		getAgentFactory().sendAsync(this, url, request, responseCallback);
	}
	
	/**
	 * Get the urls of this agent, for example "http://mysite.com/agents/key".
	 * An agent can have multiple urls for different configured communication
	 * services, such as HTTP and XMPP.
	 * 
	 * @return urls
	 */
	public List<String> getUrls() {
		List<String> urls = new ArrayList<String>();
		if (agentFactory != null) {
			String agentId = getId();
			for (TransportService service : agentFactory.getTransportServices()) {
				String url = service.getAgentUrl(agentId);
				if (url != null) {
					urls.add(url);
				}
			}
		} else {
			System.err.println("AgentFactory not initialized?!?");
		}
		return urls;
	}
	
	/**
	 * Get the Id of this agent
	 * 
	 * @return
	 */
	public String getId() {
		return state.getAgentId();
	}
	
	/**
	 * Retrieve the type name of this agent, its class
	 * 
	 * @return classname
	 */
	public String getType() {
		return getClass().getSimpleName();
	}
	
	/**
	 * Retrieve a JSON Array with the agents scheduled tasks
	 */
	@Override
	public String getTasks() {
		return this.getScheduler().toString();
	}
	
	@Override
	public String toString() {
		Map<String, Object> data = new HashMap<String, Object>();
		data.put("class", this.getClass().getName());
		data.put("id", getId());
		return data.toString();
	}
}
