package org.elasticsearch.index.analysis.action;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequestBuilder;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.internal.InternalClient;
import org.elasticsearch.search.SearchHits;

public class HighlightRequestBuilder extends
		ActionRequestBuilder<HighlightRequest, HighlightResponse, HighlightRequestBuilder> {

	public HighlightRequestBuilder(Client client, HighlightRequest request) {
		super((InternalClient) client, request);
	}

	public HighlightRequestBuilder(Client client) {
		super((InternalClient) client, new HighlightRequest());
	}

	@Override
	protected void doExecute(ActionListener<HighlightResponse> listener) {
		((Client) client).execute(HighlightAction.INSTANCE, request);
	}

	public HighlightRequestBuilder searchHits(SearchHits searchHits) {
		request.setSearchHits(searchHits);
		return this;
	}
	
	public HighlightRequestBuilder size(String size){
		request.setSize(size);
		return this;
	}
}
