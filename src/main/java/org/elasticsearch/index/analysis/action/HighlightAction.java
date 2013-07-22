package org.elasticsearch.index.analysis.action;

import org.elasticsearch.action.Action;
import org.elasticsearch.client.Client;

public class HighlightAction extends
		Action<HighlightRequest, HighlightResponse, HighlightRequestBuilder> {

    public static final HighlightAction INSTANCE = new HighlightAction();

	public final static String NAME = "highlight";

	protected HighlightAction() {
		super(NAME);
	}

	@Override
	public HighlightRequestBuilder newRequestBuilder(Client client) {
		return new HighlightRequestBuilder(client);
	}

	@Override
	public HighlightResponse newResponse() {
		return new HighlightResponse(0);
	}

}
