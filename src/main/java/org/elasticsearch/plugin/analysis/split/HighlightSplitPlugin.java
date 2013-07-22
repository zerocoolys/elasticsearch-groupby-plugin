/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Author: Dong, Aihua
 * Created at: 2013/02/27
 */
package org.elasticsearch.plugin.analysis.split;

import org.apache.lucene.search.highlight.NeloHighlighter;
import org.elasticsearch.action.ActionModule;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.index.analysis.action.HighlightAction;
import org.elasticsearch.index.analysis.action.TransportHighlightAction;
import org.elasticsearch.index.analysis.rest.TokenizerRestAction;
import org.elasticsearch.plugins.AbstractPlugin;
import org.elasticsearch.rest.RestModule;
import org.elasticsearch.search.highlight.HighlightModule;

public class HighlightSplitPlugin extends AbstractPlugin {

	@Override
	public String name() {
		return "highlight-split";
	}

	@Override
	public String description() {
		return "";
	}

	@Override
	public void processModule(Module module) {
		if (module instanceof RestModule) {
			RestModule restModule = (RestModule) module;
			restModule.addRestAction(TokenizerRestAction.class);
		}else if(module instanceof ActionModule){
			ActionModule actionModule = (ActionModule) module;
			actionModule.registerAction(HighlightAction.INSTANCE, TransportHighlightAction.class);
		}else if(module instanceof HighlightModule){
			HighlightModule highlightModule = (HighlightModule)module;
			highlightModule.registerHighlighter(NeloHighlighter.class);
		}
	}

}
