package com.almende.test.agents;

import org.joda.time.DateTime;

import com.almende.eve.agent.Agent;
import com.almende.eve.agent.annotation.Name;
import com.almende.eve.entity.Cache;
import com.almende.eve.entity.Poll;
import com.almende.eve.entity.Push;
import com.almende.eve.rpc.jsonrpc.jackson.JOM;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class TestMemoQueryAgent extends Agent {
	
	public int getData() {
		return DateTime.now().getSecondOfDay();
	}
	
	public void prepare() {
		String repeatID = initRepeat("local://bob", "getData",
				JOM.createObjectNode(), null, new Poll(1000), new Cache());
		
		if (repeatID != null) getState().put("pollKey", repeatID);
		
		repeatID = initRepeat("local://bob", "getData", JOM.createObjectNode(),
				null, new Push(1000, false), new Cache());
		if (repeatID != null) getState().put("pushKey", repeatID);

		repeatID = initRepeat("local://bob", "getData",
				JOM.createObjectNode(), "returnRes", new Poll(800));
		
		if (repeatID != null) getState().put("LazyPollKey", repeatID);

	}
	
	public void returnRes(@Name("result") int result){
		System.err.println("Received callback result:"+result);
	}
	
	public Integer get_result() {
		try {
			ObjectNode params = JOM.createObjectNode();
			params.put("maxAge", 3000);
			return getRepeat((String) getState().get("pollKey"), params,
					Integer.class);
		} catch (Exception e) {
			
		}
		return null;
	}
	
	public void tear_down() {
		cancelRepeat((String) getState().get("pushKey"));
	}
	
	@Override
	public String getDescription() {
		return "test agent to work on MemoQuery development";
	}
	
	@Override
	public String getVersion() {
		return "1.0";
	}
	
}
