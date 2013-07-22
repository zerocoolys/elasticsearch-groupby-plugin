package org.elasticsearch.index.analysis.rest;

import static org.elasticsearch.common.unit.TimeValue.parseTimeValue;
import static org.elasticsearch.rest.RestRequest.Method.GET;
import static org.elasticsearch.rest.RestRequest.Method.POST;
import static org.elasticsearch.rest.RestStatus.BAD_REQUEST;
import static org.elasticsearch.rest.action.support.RestXContentBuilder.restContentBuilder;
import static org.elasticsearch.search.suggest.SuggestBuilder.termSuggestion;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.elasticsearch.ElasticSearchIllegalArgumentException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.state.ClusterStateRequestBuilder;
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponse;
import org.elasticsearch.action.search.SearchOperationThreading;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.IgnoreIndices;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.analysis.action.HighlightAction;
import org.elasticsearch.index.analysis.action.HighlightRequestBuilder;
import org.elasticsearch.index.analysis.action.HighlightResponse;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.QueryStringQueryBuilder;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.XContentRestResponse;
import org.elasticsearch.rest.XContentThrowableRestResponse;
import org.elasticsearch.rest.action.support.RestActions;
import org.elasticsearch.search.Scroll;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortOrder;

public class TokenizerRestAction extends BaseRestHandler {

	private final int MAX_FIELD_LENGTH = 50;

	@Inject
	public TokenizerRestAction(Settings settings, Client client, RestController controller) {
		super(settings, client);
		controller.registerHandler(GET, "/_highlight", this);
		controller.registerHandler(POST, "/_highlight", this);
		controller.registerHandler(GET, "/{index}/_highlight", this);
		controller.registerHandler(POST, "/{index}/_highlight", this);
		controller.registerHandler(GET, "/{index}/{type}/_highlight", this);
		controller.registerHandler(POST, "/{index}/{type}/_highlight", this);
	}

	@Override
	public void handleRequest(final RestRequest request, final RestChannel channel) {

		SearchRequest searchRequest;
		try {
			searchRequest = parseSearchRequest(request);
			searchRequest.listenerThreaded(false);
			SearchOperationThreading operationThreading = SearchOperationThreading.fromString(
					request.param("operation_threading"), null);
			if (operationThreading != null) {
				if (operationThreading == SearchOperationThreading.NO_THREADS) {
					operationThreading = SearchOperationThreading.SINGLE_THREAD;
				}
				searchRequest.operationThreading(operationThreading);
			}
		} catch (Exception e) {
			if (logger.isDebugEnabled()) {
				logger.debug("failed to parse search request parameters", e);
			}
			try {
				XContentBuilder builder = restContentBuilder(request);
				channel.sendResponse(new XContentRestResponse(request, BAD_REQUEST, builder.startObject()
						.field("error", e.getMessage()).endObject()));
			} catch (IOException e1) {
				logger.error("Failed to send failure response", e1);
			}
			return;
		}

		final String size = request.param("limit", "999mb");
		HashSet<String> fields = new HashSet<String>();
		Map<String, Boolean> validateMap = new HashMap<String, Boolean>();
		{
			String[] index = searchRequest.indices();
			String[] types = searchRequest.types();

			ClusterStateRequestBuilder builder = client.admin().cluster().prepareState().setFilterIndices(index)
					.setFilterNodes(true).setLocal(true).setFilterRoutingTable(true).setFilterNodes(true);
			ClusterStateResponse response = builder.execute().actionGet();
			MetaData metaData = response.getState().metaData();

			Map<String, IndexMetaData> indexMetaMap = metaData.getIndices();
			boolean all = types.length == 0;
			for (String indexName : index) {
				IndexMetaData indexMetaData = indexMetaMap.get(indexName);
				try {
					if (!all) {
						for (String typeName : types) {
							MappingMetaData mappingMetaData = indexMetaData.getMappings().get(typeName);
							if (mappingMetaData == null) {
								continue;
							} else {
								Map<String, Object> sourceMap = mappingMetaData.sourceAsMap();
								removeUnsearchFields(sourceMap, fields, validateMap);
							}
						}
					} else {
						for (MappingMetaData mappingAllData : indexMetaData.getMappings().values()) {
							Map<String, Object> sourceMap = mappingAllData.sourceAsMap();
							removeUnsearchFields(sourceMap, fields, validateMap);
						}
					}
				} catch (IOException e1) {
					e1.printStackTrace();
				}
			}

			BytesReference bytes = searchRequest.source();
			try {
				XContentBuilder xBuilder = XContentFactory.jsonBuilder();
				XContentParser parser = XContentFactory.xContent(bytes).createParser(bytes);
				XContentParser.Token token = parser.nextToken();

				if (token == XContentParser.Token.START_OBJECT) {
					String fieldName = "";
					xBuilder.startObject();
					while ((token = parser.nextToken()) != null) {
						switch (token) {
						case FIELD_NAME:
							fieldName = parser.text();
							xBuilder.field(fieldName);
							break;
						case VALUE_STRING:
							xBuilder.value(parser.text());
							break;
						case VALUE_NUMBER:
							xBuilder.value(parser.longValue());
							break;
						case VALUE_BOOLEAN:
							xBuilder.value(parser.booleanValue());
							break;
						case START_ARRAY:
							xBuilder.startArray();
							break;
						case VALUE_EMBEDDED_OBJECT:
						case START_OBJECT:
							xBuilder.startObject();
							if (fieldName.equals("query_string")) {
								xBuilder.array("fields", fields.toArray(new String[] {}));
							} else if (fieldName.equals("highlight")) {
								xBuilder.startObject("fields");
								for (String hlField : fields) {
									xBuilder.startObject(hlField);
									xBuilder.endObject();
								}
								xBuilder.endObject();
							}

							break;
						case END_ARRAY:
							xBuilder.endArray();
							break;
						case END_OBJECT:
							xBuilder.endObject();
							break;
						case VALUE_NULL:
							xBuilder.nullValue();
							break;
						default:
							break;
						}
					}
				}
				logger.debug(xBuilder.string());
				searchRequest.source(xBuilder.string().getBytes(Charset.forName("utf-8")));
			} catch (IOException e1) {
				e1.printStackTrace();
			}

		}

		client.search(searchRequest, new ActionListener<SearchResponse>() {
			@Override
			public void onResponse(SearchResponse response) {
				try {
					XContentBuilder builder = restContentBuilder(request);
					builder.startObject();
					response.toXContent(builder, request);
					builder.endObject();
					channel.sendResponse(new XContentRestResponse(request, response.status(), builder));
				} catch (Exception e) {
					if (logger.isDebugEnabled()) {
						logger.debug("failed to execute search (building response)", e);
					}
					onFailure(e);
				}
			}

			@Override
			public void onFailure(Throwable e) {
				try {
					channel.sendResponse(new XContentThrowableRestResponse(request, e));
				} catch (IOException e1) {
					logger.error("Failed to send failure response", e1);
				}
			}
		});
	}

	public void append(StringBuilder sb, String text, int offset) {
		char cc = text.charAt(offset);
		if (cc == '<')
			sb.append("&lt;");
		else if (cc == '>')
			sb.append("&gt;");
		else
			sb.append(text.charAt(offset));
	}

	private void removeUnsearchFields(Map<String, Object> sourceMap, Set<String> fields,
			Map<String, Boolean> validateMap) {
		if (sourceMap.get("properties") != null) {
			Map<String, Object> propMap = (Map<String, Object>) sourceMap.get("properties");
			Set<String> keys = propMap.keySet();

			for (String key : keys) {
				if (key.length() > MAX_FIELD_LENGTH) {
					continue;
				}

				if (!validate(key, validateMap)) {
					continue;
				}
				Map<String, Object> fieldProp = (Map<String, Object>) propMap.get(key);
				Object type = fieldProp.get("type");
				if (type != null && type.equals("string")) {
					fields.add(key);
				}
			}
		}
	}

	private static boolean validate(String key, Map<String, Boolean> validateMap) {
		Boolean ret = validateMap.get(key);
		if (ret != null)
			return ret;

		char[] chars = key.toCharArray();
		for (char c : chars) {
			if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
				continue;
			} else {
				validateMap.put(key, false);
				return false;
			}

		}
		validateMap.put(key, true);
		return true;
	}

	private SearchRequest parseSearchRequest(RestRequest request) {
		String[] indices = RestActions.splitIndices(request.param("index"));
		SearchRequest searchRequest = new SearchRequest(indices);
		// get the content, and put it in the body
		if (request.hasContent()) {
			searchRequest.source(request.content(), request.contentUnsafe());
		} else {
			String source = request.param("source");
			if (source != null) {
				searchRequest.source(source);
			}
		}
		// add extra source based on the request parameters
		searchRequest.extraSource(parseSearchSource(request));

		searchRequest.searchType(request.param("search_type"));

		String scroll = request.param("scroll");
		if (scroll != null) {
			searchRequest.scroll(new Scroll(parseTimeValue(scroll, null)));
		}

		searchRequest.types(RestActions.splitTypes(request.param("type")));
		searchRequest.routing(request.param("routing"));
		searchRequest.preference(request.param("preference"));
		if (request.hasParam("ignore_indices")) {
			searchRequest.ignoreIndices(IgnoreIndices.fromString(request.param("ignore_indices")));
		}

		return searchRequest;
	}

	private SearchSourceBuilder parseSearchSource(RestRequest request) {
		SearchSourceBuilder searchSourceBuilder = null;
		String queryString = request.param("q");
		if (queryString != null) {
			QueryStringQueryBuilder queryBuilder = QueryBuilders.queryString(queryString);
			queryBuilder.defaultField(request.param("df"));
			queryBuilder.analyzer(request.param("analyzer"));
			queryBuilder.analyzeWildcard(request.paramAsBoolean("analyze_wildcard", false));
			queryBuilder.lowercaseExpandedTerms(request.paramAsBoolean("lowercase_expanded_terms", true));
			queryBuilder.lenient(request.paramAsBooleanOptional("lenient", null));
			String defaultOperator = request.param("default_operator");
			if (defaultOperator != null) {
				if ("OR".equals(defaultOperator)) {
					queryBuilder.defaultOperator(QueryStringQueryBuilder.Operator.OR);
				} else if ("AND".equals(defaultOperator)) {
					queryBuilder.defaultOperator(QueryStringQueryBuilder.Operator.AND);
				} else {
					throw new ElasticSearchIllegalArgumentException("Unsupported defaultOperator [" + defaultOperator
							+ "], can either be [OR] or [AND]");
				}
			}
			if (searchSourceBuilder == null) {
				searchSourceBuilder = new SearchSourceBuilder();
			}
			searchSourceBuilder.query(queryBuilder);
		}

		int from = request.paramAsInt("from", -1);
		if (from != -1) {
			if (searchSourceBuilder == null) {
				searchSourceBuilder = new SearchSourceBuilder();
			}
			searchSourceBuilder.from(from);
		}
		int size = request.paramAsInt("size", -1);
		if (size != -1) {
			if (searchSourceBuilder == null) {
				searchSourceBuilder = new SearchSourceBuilder();
			}
			searchSourceBuilder.size(size);
		}

		if (request.hasParam("explain")) {
			if (searchSourceBuilder == null) {
				searchSourceBuilder = new SearchSourceBuilder();
			}
			searchSourceBuilder.explain(request.paramAsBooleanOptional("explain", null));
		}
		if (request.hasParam("version")) {
			if (searchSourceBuilder == null) {
				searchSourceBuilder = new SearchSourceBuilder();
			}
			searchSourceBuilder.version(request.paramAsBooleanOptional("version", null));
		}
		if (request.hasParam("timeout")) {
			if (searchSourceBuilder == null) {
				searchSourceBuilder = new SearchSourceBuilder();
			}
			searchSourceBuilder.timeout(request.paramAsTime("timeout", null));
		}

		String sField = request.param("fields");
		if (sField != null) {
			if (searchSourceBuilder == null) {
				searchSourceBuilder = new SearchSourceBuilder();
			}
			if (!Strings.hasText(sField)) {
				searchSourceBuilder.noFields();
			} else {
				String[] sFields = Strings.splitStringByCommaToArray(sField);
				if (sFields != null) {
					for (String field : sFields) {
						searchSourceBuilder.field(field);
					}
				}
			}
		}

		String sSorts = request.param("sort");
		if (sSorts != null) {
			if (searchSourceBuilder == null) {
				searchSourceBuilder = new SearchSourceBuilder();
			}
			String[] sorts = Strings.splitStringByCommaToArray(sSorts);
			for (String sort : sorts) {
				int delimiter = sort.lastIndexOf(":");
				if (delimiter != -1) {
					String sortField = sort.substring(0, delimiter);
					String reverse = sort.substring(delimiter + 1);
					if ("asc".equals(reverse)) {
						searchSourceBuilder.sort(sortField, SortOrder.ASC);
					} else if ("desc".equals(reverse)) {
						searchSourceBuilder.sort(sortField, SortOrder.DESC);
					}
				} else {
					searchSourceBuilder.sort(sort);
				}
			}
		}

		String sIndicesBoost = request.param("indices_boost");
		if (sIndicesBoost != null) {
			if (searchSourceBuilder == null) {
				searchSourceBuilder = new SearchSourceBuilder();
			}
			String[] indicesBoost = Strings.splitStringByCommaToArray(sIndicesBoost);
			for (String indexBoost : indicesBoost) {
				int divisor = indexBoost.indexOf(',');
				if (divisor == -1) {
					throw new ElasticSearchIllegalArgumentException("Illegal index boost [" + indexBoost + "], no ','");
				}
				String indexName = indexBoost.substring(0, divisor);
				String sBoost = indexBoost.substring(divisor + 1);
				try {
					searchSourceBuilder.indexBoost(indexName, Float.parseFloat(sBoost));
				} catch (NumberFormatException e) {
					throw new ElasticSearchIllegalArgumentException("Illegal index boost [" + indexBoost
							+ "], boost not a float number");
				}
			}
		}

		String sStats = request.param("stats");
		if (sStats != null) {
			if (searchSourceBuilder == null) {
				searchSourceBuilder = new SearchSourceBuilder();
			}
			searchSourceBuilder.stats(Strings.splitStringByCommaToArray(sStats));
		}

		String suggestField = request.param("suggest_field");
		if (suggestField != null) {
			String suggestText = request.param("suggest_text", queryString);
			int suggestSize = request.paramAsInt("suggest_size", 5);
			if (searchSourceBuilder == null) {
				searchSourceBuilder = new SearchSourceBuilder();
			}
			String suggestMode = request.param("suggest_mode");
			searchSourceBuilder.suggest().addSuggestion(
					termSuggestion(suggestField).field(suggestField).text(suggestText).size(suggestSize)
							.suggestMode(suggestMode));
		}

		return searchSourceBuilder;
	}

	public static void main(String[] args) {
		String name = "xx_xxx-dsadsad1231dsadsa123dfhj-";
		String fname = "???!#21321dsafds8345@#%FDGDFS";
		Map<String, Boolean> testMap = new HashMap<String, Boolean>();
		System.out.println(validate(name, testMap));
		System.out.println(validate(name, testMap));
		System.out.println(validate(fname, testMap));
		System.out.println(validate(fname, testMap));
		System.out.println(testMap);
	}

}
