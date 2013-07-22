package org.elasticsearch.index.analysis.action;

import java.util.HashMap;
import java.util.Map;

import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.search.SearchHit;

public class HighlightResponse extends ActionResponse {

	Map<String, SearchHit> searchMap;

	private long tookInMillis = -1;

	public HighlightResponse(long tookInMillis) {
		this.tookInMillis = tookInMillis;
	}

	public long tookInMillis() {
		return tookInMillis;
	}

	public Map<String, SearchHit> getSearchMap() {
		if (searchMap == null)
			return null;
		return new HashMap<String, SearchHit>(searchMap);
	}

	public void add(String id, SearchHit searchHit) {
		if (searchMap == null) {
			searchMap = new HashMap<String, SearchHit>();
		}
		searchMap.put(id, searchHit);
	}

}
