package org.elasticsearch.index.analysis.action;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.TransportAction;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.FastStringReader;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.text.StringText;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.SizeValue;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.analysis.IndicesAnalysisService;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHitField;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.highlight.HighlightField;
import org.elasticsearch.threadpool.ThreadPool;

public class TransportHighlightAction extends TransportAction<HighlightRequest, HighlightResponse> {

	private final IndicesAnalysisService indicesAnalysisService;

	private ClusterService clusterService;

	private final HashSet<String> fieldSet = new HashSet<String>();

	private final String PRE_I = "<i>";
	private final String POST_I = "</i>";

	private final String[] SYSTEMFIELDS = new String[] { "body", "projectName", "projectVersion", "logType",
			"logSource", "host" };

	private final String[] CURRENT_SYS_FIELDS;

	private enum TYPE {
		NOT_ANALYZED, ANALYZED, NOCHANGED
	}

	@Inject
	protected TransportHighlightAction(Settings settings, ThreadPool threadPool, IndicesService indicesService,
			IndicesAnalysisService indicesAnalysisService, ClusterService clusterService) {
		super(settings, threadPool);
		this.indicesAnalysisService = indicesAnalysisService;
		this.clusterService = clusterService;

		String[] settingFields = settings.getAsArray("highlight.fields.exclude");
		if (settingFields == null || settingFields.length == 0) {
			CURRENT_SYS_FIELDS = SYSTEMFIELDS;
		} else {
			CURRENT_SYS_FIELDS = settingFields;
		}

		for (int i = 0; i < CURRENT_SYS_FIELDS.length; i++) {
			fieldSet.add(CURRENT_SYS_FIELDS[i]);
		}
	}

	@Override
	protected void doExecute(HighlightRequest request, ActionListener<HighlightResponse> listener) {

		SearchHits searchHits = request.searchHits();
		
		String size = request.size();
		Map<String, Analyzer> analyzerMap = new HashMap<String, Analyzer>(4);
		Map<Object, StringBuilder> builderCache = new HashMap<Object, StringBuilder>(16);
		long realSize = -1;
		if (size.endsWith("b") || size.endsWith("B")) {
			realSize = ByteSizeValue.parseBytesSizeValue(size).bytes();
		} else {
			realSize = SizeValue.parseSizeValue(size).singles();
		}

		try {
			for (SearchHit searchHit : searchHits) {
				Map<String, HighlightField> fieldMap = searchHit.getHighlightFields();
				String index = searchHit.index();
				String type = searchHit.type();
				ClusterState state = clusterService.state();
				Map<String, Object> mappingMap = state.getMetaData().getIndices().get(index).mapping(type)
						.sourceAsMap();

				Map<String, Object> fieldValue = null;
				if (searchHit.source() != null) {
					// we must not using this field.
					fieldValue = searchHit.sourceAsMap();
				} else if (searchHit.fields() != null) {
					SearchHitField searchHitField = searchHit.getFields().get("partial");
					if (searchHitField != null) {
						if (searchHitField.getValue() != null) {
							fieldValue = searchHitField.getValue();
						}
					}
				}

				List<String> highLightField = new ArrayList<String>();
				for (Entry<String, HighlightField> entry : fieldMap.entrySet()) {
					HighlightField field = entry.getValue();
					highLightField.add(field.getName());
					int i = 0;
					for (; i < field.fragments().length; i++) {

						String text = field.fragments()[i].string();

						String originalText = fieldValue.get(field.name()).toString();

						HashSet<String> highlightList = null;

						if (text.indexOf('&') != -1) {
							StringWriter writer = null;
							try {
								writer = new StringWriter();
								doUnescape(writer, text, text.indexOf('&'));
								logger.debug("writer info : " + writer.toString());
								text = writer.toString();
								writer.close();
							} catch (Exception se) {
							} finally {
								if (writer != null)
									writer.close();
							}
						}
						if (text.replaceAll("<em>", "").replace("</em>", "").length() < originalText.length()) {
							// more tags
							highlightList = drill(convert(text, originalText));
						} else {
							highlightList = drill(text);
						}
						String finalText = null;
						String key = index + type + field.getName() + originalText.hashCode();
						if (builderCache.containsKey(key)) {
							finalText = builderCache.get(key).toString();
						} else {
							finalText = convertString(builderCache, key, analyzerMap, mappingMap, field.name(),
									originalText, null, highlightList, realSize);
						}
						field.fragments()[i] = new StringText(finalText);
					}
				}

				for (String key : fieldValue.keySet()) {
					if (highLightField.contains(key)) {
						if (!(key.equals("logSource") || key.equals("logType") || key.equals("Platform")))
							fieldValue.put(key, "");
						continue;
					}
					Object value = fieldValue.get(key);
					String builderKey = index + type + key + value.hashCode();
					String finalText = null;
					if (builderCache.containsKey(builderKey)) {
						finalText = builderCache.get(builderKey).toString();
					} else {
						finalText = convertString(builderCache, key, analyzerMap, mappingMap, key, value.toString(),
								null, null, realSize);
					}
					fieldValue.put(key, finalText);
				}

			}
		} catch (Exception e) {
			logger.error("plugin", e);
		}

		listener.onResponse(new HighlightResponse(System.currentTimeMillis() - request.startTime()));
	}

	private String convertString(Map<Object, StringBuilder> builderCache, String key,
			Map<String, Analyzer> analyzerMap, Map<String, Object> fieldMappers, String fieldName, String originalText,
			String highlightText, HashSet<String> highlightList, long sizeValue) throws IOException {

		boolean size = fieldSet.contains(fieldName);

		Object fieldMapper = fieldMappers.get("properties");

		Map<String, Object> properties = (Map<String, Object>) ((Map<String, Object>) fieldMapper).get(fieldName);

		TYPE returnType = null;
		if (properties == null) {
			// fields not in mapping (empty value fields)
			returnType = TYPE.NOCHANGED;
		} else {
			returnType = isAnalyzed(properties);
		}
		StringBuilder sb = new StringBuilder();

		switch (returnType) {
		case NOT_ANALYZED:
			if (highlightList != null && highlightList.contains(originalText)) {
				return "<em>" + originalText + "</em>";
			} else {
				return PRE_I + originalText + POST_I;
			}
		case NOCHANGED:
			return originalText;
		case ANALYZED:
			String analyzeName = (properties.containsKey("analyze")) ? properties.get("analyze").toString() : "";

			Analyzer analyzer = null;
			if (analyzer == null) {
				if (analyzeName.equals("")) {
					analyzer = indicesAnalysisService.analyzer("default_analyzer");
				} else {
					analyzer = indicesAnalysisService.analyzer(analyzeName);
				}

				if (analyzer == null) {
					analyzer = indicesAnalysisService.analyzer("standard");
				}

				if (analyzer == null) {
					throw new ElasticSearchException("no analyzer found for field " + fieldName);
				}

			}
			TokenStream ts = null;
			try {
				ts = analyzer.tokenStream(fieldName, new FastStringReader(originalText));
				OffsetAttribute offsetAttr = ts.getAttribute(OffsetAttribute.class);
				ts.reset();

				if (highlightList == null) {
					highlightList = new HashSet<String>(0);
				}
				int offset = 0;
				boolean stop = false;
				while (ts.incrementToken() && !stop) {

					while (offset < offsetAttr.startOffset()) {
						append(sb, originalText, offset);
						if (size && offset > sizeValue) {
							return sb.toString();
						}

						offset++;
					}
					String original = originalText.substring(offset, offsetAttr.endOffset());
					if (highlightList.contains(original)) {
						sb.append("<em>");
						while (offset < offsetAttr.endOffset()) {
							append(sb, originalText, offset);
							if (size && offset > sizeValue) {
								sb.append("</em>");
								return sb.toString();
							}
							offset++;
						}
						sb.append("</em>");
					} else {

						sb.append(PRE_I);
						int length = PRE_I.length();
						while (offset < offsetAttr.endOffset()) {
							append(sb, originalText, offset);
							if (size && offset > sizeValue) {
								if (offset < offsetAttr.endOffset() - 1) {
									StringBuilder newSb = new StringBuilder();
									String currentStr = sb.toString();
									newSb.append(currentStr.substring(0, currentStr.lastIndexOf(PRE_I))).append(
											currentStr.substring(currentStr.lastIndexOf(PRE_I) + length));
									return newSb.toString();
								} else {
									return sb.append(POST_I).toString();
								}
							}
							offset++;
						}
						sb.append(POST_I);
					}
					offset = offsetAttr.endOffset();
				}
				while (offset < originalText.length()) {
					append(sb, originalText, offset);
					if (size && offset > sizeValue) {
						return sb.toString();
					}
					offset++;
				}
			} catch (Exception e) {
				logger.error("plugin", e);
			} finally {
				if (ts != null)
					ts.close();
			}
		}

		builderCache.put(key, sb);
		return sb.toString();
	}

	public static String convert(String text, String originalText) {
		StringBuilder sb = new StringBuilder();

		int i = 0, j = 0;

		while (i < originalText.length()) {
			char o = originalText.charAt(i);
			char t = text.charAt(j);

			if (o == t) {
				if (o == '<')
					sb.append("&lt;");
				else if (o == '>')
					sb.append("&gt;");
				else
					sb.append(o);
				i++;
			} else {
				sb.append(t);
			}
			j++;
		}
		if (j < text.length()) {
			sb.append(text.substring(j));
		}
		return sb.toString();
	}

	public HashSet<String> drill(String text) {
		FastStringReader reader = new FastStringReader(text);
		int idx = -1;
		final HashSet<String> highlighText = new HashSet<String>();
		char c = 0;

		boolean highlight = false;
		boolean startTag = false;
		int s = -1;
		try {
			while ((c = (char) reader.read()) != 65535) {
				idx++;
				if (c == '<') {
					if (highlight) {
						highlighText.add(reader.subSequence(s + 1, idx).toString());
						highlight = false;
					} else {
						startTag = true;
					}
					continue;
				}

				if (c == '/') {
					if (startTag) {
						startTag = false;
					}
				}

				if (startTag && c == '>') {
					startTag = false;
					highlight = true;
					s = idx;
					continue;
				}

				if (startTag) {
					if (c != 'e' && c != 'm') {
						startTag = false;
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			reader.close();
		}

		return highlighText;
	}

	private TYPE isAnalyzed(Map<String, Object> fieldProperties) {

		if (fieldProperties.containsKey("index") && fieldProperties.get("index").equals("not_analyzed")) {
			return TYPE.NOT_ANALYZED;
		}
		Object type = fieldProperties.get("type");

		if (type.equals("string")) {
			return TYPE.ANALYZED;
		} else {
			return TYPE.NOCHANGED;
		}

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

	private void doUnescape(Writer writer, String str, int firstAmp) throws IOException {
		writer.write(str, 0, firstAmp);
		int len = str.length();
		for (int i = firstAmp; i < len; i++) {
			char c = str.charAt(i);
			if (c == '&') {
				int nextIdx = i + 1;
				int semiColonIdx = str.indexOf(';', nextIdx);
				if (semiColonIdx == -1) {
					writer.write(c);
					continue;
				}
				int amphersandIdx = str.indexOf('&', i + 1);
				if (amphersandIdx != -1 && amphersandIdx < semiColonIdx) {
					writer.write(c);
					continue;
				}
				String entityContent = str.substring(nextIdx, semiColonIdx);
				int entityValue = -1;
				int entityContentLen = entityContent.length();
				if (entityContentLen > 0) {
					if (entityContent.charAt(0) == '#') {
						if (entityContentLen > 1) {
							char isHexChar = entityContent.charAt(1);
							try {
								switch (isHexChar) {
								case 'X':
								case 'x': {
									entityValue = Integer.parseInt(entityContent.substring(2), 16);
									break;
								}
								default: {
									entityValue = Integer.parseInt(entityContent.substring(1), 10);
								}
								}
								if (entityValue > 0xFFFF) {
									entityValue = -1;
								}
							} catch (NumberFormatException e) {
								entityValue = -1;
							}
						}
					}
				}

				if (entityValue == -1) {
					writer.write('&');
					writer.write(entityContent);
					writer.write(';');
				} else {
					writer.write(entityValue);
				}
				i = semiColonIdx;
			} else {
				writer.write(c);
			}
		}
	}
}
