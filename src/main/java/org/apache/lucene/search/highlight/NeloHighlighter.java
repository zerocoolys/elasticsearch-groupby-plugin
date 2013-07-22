package org.apache.lucene.search.highlight;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CachingTokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.search.Query;
import org.elasticsearch.common.collect.ImmutableList;
import org.elasticsearch.common.collect.ImmutableSet;
import org.elasticsearch.common.io.FastStringReader;
import org.elasticsearch.common.lucene.search.XFilteredQuery;
import org.elasticsearch.common.lucene.search.function.FiltersFunctionScoreQuery;
import org.elasticsearch.common.lucene.search.function.FunctionScoreQuery;
import org.elasticsearch.common.text.StringText;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.SizeValue;
import org.elasticsearch.index.fieldvisitor.CustomFieldsVisitor;
import org.elasticsearch.index.mapper.FieldMapper;
import org.elasticsearch.search.fetch.FetchPhaseExecutionException;
import org.elasticsearch.search.fetch.FetchSubPhase;
import org.elasticsearch.search.highlight.HighlightField;
import org.elasticsearch.search.highlight.Highlighter;
import org.elasticsearch.search.highlight.HighlighterContext;
import org.elasticsearch.search.highlight.SearchContextHighlight;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.search.lookup.SearchLookup;

public class NeloHighlighter implements Highlighter {

	private final String[] names = new String[] { "nelo-highlight" };

	private final String PRE_I = "<i>";
	private final String POST_I = "</i>";

	@Override
	public String[] names() {
		return names;
	}

	@Override
	public HighlightField highlight(HighlighterContext highlighterContext) {
		SearchContextHighlight.Field field = highlighterContext.field;
		String preTag = (field.preTags() == null || field.preTags().length == 0) ? "<em>" : field.preTags()[0];
		String postTag = (field.postTags() == null || field.postTags().length == 0) ? "</em>" : field.postTags()[0];

		Map<String, Object> options = field.options();
		long sizeValue = Long.MAX_VALUE;
		if (options != null && options.size() > 0) {
			if (options.containsKey("string_size")) {
				String size = options.get("string_size").toString();
				if (size.endsWith("b") || size.endsWith("B")) {
					sizeValue = ByteSizeValue.parseBytesSizeValue(size).bytes();
				} else {
					sizeValue = SizeValue.parseSizeValue(size).singles();
				}
			}
		}

		SearchContext context = highlighterContext.context;
		FetchSubPhase.HitContext hitContext = highlighterContext.hitContext;
		FieldMapper<?> mapper = highlighterContext.mapper;

		String sourceOrPartial = null;
		if (context.hasPartialFields()) {
			sourceOrPartial = "partial";
		} else {
			sourceOrPartial = "_source";
		}

		int min = Integer.MAX_VALUE, max = -1;

		List<Object> textsToHighlight;
		if (mapper.fieldType().stored()) {
			try {
				CustomFieldsVisitor fieldVisitor = new CustomFieldsVisitor(ImmutableSet.of(mapper.names().indexName()),
						false);
				hitContext.reader().document(hitContext.docId(), fieldVisitor);
				textsToHighlight = fieldVisitor.fields().get(mapper.names().indexName());
				if (textsToHighlight == null) {
					textsToHighlight = ImmutableList.of();
				}
			} catch (Exception e) {
				throw new FetchPhaseExecutionException(context, "Failed to highlight field ["
						+ highlighterContext.fieldName + "]", e);
			}
		} else {
			SearchLookup lookup = context.lookup();
			lookup.setNextReader(hitContext.readerContext());
			lookup.setNextDocId(hitContext.docId());
			textsToHighlight = lookup.source().extractRawValues(mapper.names().sourcePath());
		}
		assert textsToHighlight != null;

		Analyzer analyzer = context.mapperService().documentMapper(hitContext.hit().type()).mappers().indexAnalyzer();
		ArrayList<String> newTerms = new ArrayList<String>();
		try {
			for (Object textToHighlight : textsToHighlight) {
				newTerms.addAll(terms(highlighterContext, analyzer, textToHighlight.toString()));
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		// the terms length min and max
		String[] a = newTerms.toArray(new String[] {});
		Arrays.sort(a);
		ListIterator<String> li = newTerms.listIterator();
		for (int j = 0; j < a.length; j++) {
			li.next();
			li.set((String) a[j]);
			min = Math.min(min, a[j].length());
			max = Math.max(max, a[j].length());
		}

		int numberOfFragments = field.numberOfFragments() == 0 ? 1 : field.numberOfFragments();
		ArrayList<String> fragsList = new ArrayList<String>();

		try {

			boolean needAnalyzed = mapper.fieldType().tokenized();
//			System.out.println(mapper.names().fullName());
			if (needAnalyzed) {
				for (Object textToHighlight : textsToHighlight) {

					String text = textToHighlight.toString();
					int firstAmp = text.indexOf("&");
					if (firstAmp != -1) {
						text = doUnescape(text,firstAmp);
					}

					StringBuilder sb = new StringBuilder();

					TokenStream ts = analyzer.tokenStream(mapper.names().indexName(), new FastStringReader(text));
					CachingTokenFilter tokenStream = new CachingTokenFilter(ts);

					OffsetAttribute offsetAttr = tokenStream.getAttribute(OffsetAttribute.class);

					int offset = 0;
					boolean stop = false;
					ts.reset();

					while (tokenStream.incrementToken() && !stop) {
						while (offset < offsetAttr.startOffset()) {
							append(sb, text, offset);
							if (offset > sizeValue) {
								fragsList.add(sb.toString());
							}
							offset++;
						}
						String original = text.substring(offset, offsetAttr.endOffset());
						int length = original.length();
						boolean match = length >= min ? length <= max : false;
						if (match && Collections.binarySearch(newTerms, original.toLowerCase()) >= 0) {
							sb.append(preTag);
							while (offset < offsetAttr.endOffset()) {
								append(sb, text, offset);
								if (offset > sizeValue) {
									if (offset + 1 == offsetAttr.endOffset()) {
										sb.append(postTag);
										fragsList.add(sb.toString());
									} else {
										String str = sb.toString();
										int idx = str.lastIndexOf(preTag);
										str = str.substring(0, idx) + str.substring(idx + preTag.length());
										fragsList.add(str);
									}
									stop = true;
									break;
								}
								offset++;
							}
							sb.append(postTag);
						} else {

							sb.append(PRE_I);
							int pLength = PRE_I.length();
							while (offset < offsetAttr.endOffset()) {
								append(sb, text, offset);
								if (offset > sizeValue) {
									if (offset < offsetAttr.endOffset() - 1) {
										StringBuilder newSb = new StringBuilder();
										String currentStr = sb.toString();
										newSb.append(currentStr.substring(0, currentStr.lastIndexOf(PRE_I))).append(
												currentStr.substring(currentStr.lastIndexOf(PRE_I) + pLength));
										fragsList.add(newSb.toString());
									} else {
										fragsList.add(sb.append(POST_I).toString());
									}
									break;
								}
								offset++;
							}
							sb.append(POST_I);
						}
						offset = offsetAttr.endOffset();

					}
					tokenStream.close();
					fragsList.add(sb.toString());
				}
			} else {

				for (Object textToHighlight : textsToHighlight) {
					String text = textToHighlight.toString();

					int length = text.length();
					boolean match = length >= min ? length <= max : false;
					if (match && Collections.binarySearch(newTerms, text) >= 0) {
						fragsList.add(preTag + text + postTag);
					} else {
						fragsList.add(PRE_I + text + PRE_I);
					}
				}
			}
		} catch (Exception e) {
			throw new FetchPhaseExecutionException(context, "Failed to highlight field ["
					+ highlighterContext.fieldName + "]", e);
		} finally {

		}

		String[] fragments = null;
		// number_of_fragments is set to 0 but we have a multivalued field
		if (field.numberOfFragments() == 0 && textsToHighlight.size() > 1 && fragsList.size() > 0) {
			fragments = new String[fragsList.size()];
			for (int i = 0; i < fragsList.size(); i++) {
				fragments[i] = fragsList.get(i).toString();
			}
		} else {
			// refine numberOfFragments if needed
			numberOfFragments = fragsList.size() < numberOfFragments ? fragsList.size() : numberOfFragments;
			fragments = new String[numberOfFragments];
			for (int i = 0; i < fragments.length; i++) {
				fragments[i] = fragsList.get(i).toString();
			}
		}

		if (fragments != null && fragments.length > 0) {
			return new HighlightField(highlighterContext.fieldName, StringText.convertFromStringArray(fragments));
		}

		return null;
	}

	private ArrayList<String> terms(HighlighterContext context, Analyzer analyzer, String text) throws IOException {
		SearchContext searchContext = context.context;
		FieldMapper<?> mapper = context.mapper;

		TokenStream tokenStream = analyzer.tokenStream(mapper.names().indexName(), new FastStringReader(text));
		tokenStream.reset();

		WeightedSpanTermExtractor qse = new NeloWeightedSpanTermExtractor();
		qse.setExpandMultiTermQuery(true);
		qse.setMaxDocCharsToAnalyze(Integer.MAX_VALUE);
		Map<String, WeightedSpanTerm> maps = qse.getWeightedSpanTerms(searchContext.parsedQuery().query(), tokenStream,
				mapper.names().name());

		ArrayList<String> terms = new ArrayList<String>();
		terms.addAll(maps.keySet());

		return terms;
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

	private String doUnescape(String str, int firstAmp) {
		StringWriter writer = null;
		try {
			writer = new StringWriter();

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
		} catch (Exception e) {

		} finally {
			if (writer != null)
				try {
					writer.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
		}

		if (writer == null) {
			return str;
		}
		return writer.toString();
	}
	
	// wighted term exctractor
	private static class NeloWeightedSpanTermExtractor extends WeightedSpanTermExtractor {

        public NeloWeightedSpanTermExtractor() {
            super();
        }

        public NeloWeightedSpanTermExtractor(String defaultField) {
            super(defaultField);
        }

        @Override
        protected void extractUnknownQuery(Query query,
                                           Map<String, WeightedSpanTerm> terms) throws IOException {
            if (query instanceof FunctionScoreQuery) {
                query = ((FunctionScoreQuery) query).getSubQuery();
                extract(query, terms);
            } else if (query instanceof FiltersFunctionScoreQuery) {
                query = ((FiltersFunctionScoreQuery) query).getSubQuery();
                extract(query, terms);
            } else if (query instanceof XFilteredQuery) {
                query = ((XFilteredQuery) query).getQuery();
                extract(query, terms);
            }
        }

    }
}
