package org.elasticsearch.index.analysis.action;

import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.search.SearchHits;

public class HighlightRequest extends SearchRequest {

	private SearchHits searchHits;
	private String size;
	private final long startTime = System.currentTimeMillis();
	
	public SearchHits searchHits() {
		return searchHits;
	}

	public void setSearchHits(SearchHits searchHits) {
		this.searchHits = searchHits;
	}

	public void setSize(String size) {
		this.size = size;
	}

	public String size(){
		return size;
	}
	
	public long startTime(){
		return startTime;
	}
}
