package org.apache.solr.handler.component;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.SetBasedFieldSelector;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermEnum;
import org.apache.lucene.index.TermVectorMapper;
import org.apache.lucene.index.TermVectorOffsetInfo;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.params.TermVectorParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.core.SolrCore;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.search.DocList;
import org.apache.solr.search.DocListAndSet;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.util.RefCounted;
import org.apache.solr.util.plugin.SolrCoreAware;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
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
 * Return term vectors for the documents in a query result set.
 * <p/>
 * Info available:
 * term, frequency, position, offset, IDF.
 * <p/>
 * <b>Note</b> Returning IDF can be expensive.
 */
public class TermVectorComponent extends SearchComponent implements SolrCoreAware {
  private transient static Logger log = Logger.getLogger(TermVectorComponent.class.getName());

  public static final String COMPONENT_NAME = "tv";

  protected NamedList initParams;
  public static final String TERM_VECTORS = "termVectors";


  public void process(ResponseBuilder rb) throws IOException {
    SolrParams params = rb.req.getParams();
    if (!params.getBool(COMPONENT_NAME, false)) {
      return;
    }

    NamedList termVectors = new NamedList();
    rb.rsp.add(TERM_VECTORS, termVectors);
    //figure out what options we have, and try to get the appropriate vector
    boolean termFreq = params.getBool(TermVectorParams.TF, false);
    boolean positions = params.getBool(TermVectorParams.POSITIONS, false);
    boolean offsets = params.getBool(TermVectorParams.OFFSETS, false);
    boolean idf = params.getBool(TermVectorParams.IDF, false);
    boolean tfIdf = params.getBool(TermVectorParams.TF_IDF, false);
    //boolean cacheIdf = params.getBool(TermVectorParams.IDF, false);

    boolean all = params.getBool(TermVectorParams.ALL, false);
    if (all == true){
      termFreq = true;
      positions = true;
      offsets = true;
      idf = true;
      tfIdf = true;
    }

    String[] fields = params.getParams(TermVectorParams.FIELDS);
    if (fields == null) {
      fields = params.getParams(CommonParams.FL);
    }
    DocListAndSet listAndSet = rb.getResults();
    List<Integer> docIds = getInts(params.getParams(TermVectorParams.DOC_IDS));
    Iterator<Integer> iter;
    if (docIds != null && docIds.isEmpty() == false) {
      iter = docIds.iterator();
    } else {
      DocList list = listAndSet.docList;
      iter = list.iterator();
    }
    SolrCore core = rb.req.getCore();
    RefCounted<SolrIndexSearcher> searcher = core.getSearcher();
    try {
      IndexReader reader = searcher.get().getReader();
      TVMapper mapper = new TVMapper(fields, reader, termFreq, positions, offsets, idf, tfIdf);
      IndexSchema schema = core.getSchema();
      String uniqFieldName = schema.getUniqueKeyField().getName();
      SetBasedFieldSelector fieldSelector = new SetBasedFieldSelector(Collections.singleton(uniqFieldName), Collections.emptySet());
      while (iter.hasNext()) {
        Integer docId = iter.next();
        NamedList docNL = new NamedList();
        termVectors.add("doc-" + docId, docNL);
        mapper.docNL = docNL;
        Document document = reader.document(docId, fieldSelector);
        String uniqId = document.get(uniqFieldName);
        docNL.add("uniqueKey", uniqId);
        reader.getTermFreqVector(docId, mapper);
      }
      termVectors.add("uniqueKeyFieldName", uniqFieldName);
    } finally {
      searcher.decref();
    }
  }

  private List<Integer> getInts(String[] vals) {
    List<Integer> result = null;
    if (vals != null && vals.length > 0) {
      result = new ArrayList<Integer>(vals.length);
      for (int i = 0; i < vals.length; i++) {
        try {
          result.add(new Integer(vals[i]));
        } catch (NumberFormatException e) {
          throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, e.getMessage(), e);
        }
      }
    }
    return result;
  }

  @Override
  public int distributedProcess(ResponseBuilder rb) throws IOException {
    int result = ResponseBuilder.STAGE_DONE;
    if (rb.stage == ResponseBuilder.STAGE_GET_FIELDS) {
      //Go ask each shard for it's vectors
      // for each shard, collect the documents for that shard.
      HashMap<String, Collection<ShardDoc>> shardMap = new HashMap<String, Collection<ShardDoc>>();
      for (ShardDoc sdoc : rb.resultIds.values()) {
        Collection<ShardDoc> shardDocs = shardMap.get(sdoc.shard);
        if (shardDocs == null) {
          shardDocs = new ArrayList<ShardDoc>();
          shardMap.put(sdoc.shard, shardDocs);
        }
        shardDocs.add(sdoc);
      }
      // Now create a request for each shard to retrieve the stored fields
      for (Collection<ShardDoc> shardDocs : shardMap.values()) {
        ShardRequest sreq = new ShardRequest();
        sreq.purpose = ShardRequest.PURPOSE_GET_FIELDS;

        sreq.shards = new String[]{shardDocs.iterator().next().shard};

        sreq.params = new ModifiableSolrParams();

        // add original params
        sreq.params.add(rb.req.getParams());
        sreq.params.remove(CommonParams.Q);//remove the query
        ArrayList<String> ids = new ArrayList<String>(shardDocs.size());
        for (ShardDoc shardDoc : shardDocs) {
          ids.add(shardDoc.id.toString());
        }
        sreq.params.add(TermVectorParams.DOC_IDS, StrUtils.join(ids, ','));

        rb.addRequest(this, sreq);
      }
      result = ResponseBuilder.STAGE_DONE;
    }
    return result;
  }

  private class TVMapper extends TermVectorMapper {
    private NamedList docNL;
    private IndexReader reader;
    private Set<String> fields;
    private boolean termFreq, positions, offsets, idf, tfIdf;
    //internal vars not passed in by construction
    private boolean map, useOffsets, usePositions;
    //private Map<String, Integer> idfCache;
    private NamedList fieldNL;
    private Term currentTerm;

    public TVMapper(String[] fields, IndexReader reader, boolean termFreq, boolean positions, boolean offsets, boolean idf, boolean tfIdf) {

      this.reader = reader;
      this.fields = fields != null ? new HashSet<String>(Arrays.asList(fields)) : Collections.<String>emptySet();
      this.termFreq = termFreq;
      this.positions = positions;
      this.offsets = offsets;
      this.idf = idf;
      this.tfIdf = tfIdf;

    }

    public void map(String term, int frequency, TermVectorOffsetInfo[] offsets, int[] positions) {
      if (map == true && fieldNL != null) {
        NamedList termInfo = new NamedList();
        fieldNL.add(term, termInfo);
        if (termFreq == true) {
          termInfo.add("freq", frequency);
        }
        if (useOffsets == true) {
          NamedList theOffsets = new NamedList();
          termInfo.add("offsets", theOffsets);
          for (int i = 0; i < offsets.length; i++) {
            TermVectorOffsetInfo offset = offsets[i];
            theOffsets.add("start", offset.getStartOffset());
            theOffsets.add("end", offset.getEndOffset());
          }
        }
        if (usePositions == true) {
          NamedList positionsNL = new NamedList();
          for (int i = 0; i < positions.length; i++) {
            positionsNL.add("position", positions[i]);            
          }
          termInfo.add("positions", positionsNL);
        }
        if (idf == true) {
          termInfo.add("idf", getIdf(term));
        }
        if (tfIdf == true){
          double tfIdfVal = ((double) frequency) / getIdf(term);
          termInfo.add("tf-idf", tfIdfVal);
        }
      }
    }

    private int getIdf(String term) {
      int result = 1;
      currentTerm = currentTerm.createTerm(term);
      try {
        TermEnum termEnum = reader.terms(currentTerm);
        if (termEnum != null && termEnum.term().equals(currentTerm)) {
          result = termEnum.docFreq();
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      return result;
    }

    public void setExpectations(String field, int numTerms, boolean storeOffsets, boolean storePositions) {

      if (idf == true && reader != null) {
        this.currentTerm = new Term(field);
      }
      useOffsets = storeOffsets && offsets;
      usePositions = storePositions && positions;
      if (fields.isEmpty() || fields.contains(field)) {
        map = true;
        fieldNL = new NamedList();
        docNL.add(field, fieldNL);
      } else {
        map = false;
        fieldNL = null;
      }
    }
  }

  public void prepare(ResponseBuilder rb) throws IOException {

  }

  //////////////////////// NamedListInitializedPlugin methods //////////////////////
  @Override
  public void init(NamedList args) {
    super.init(args);
    this.initParams = args;
  }

  public void inform(SolrCore core) {

  }

  public String getVersion() {
    return "$Revision$";
  }

  public String getSourceId() {
    return "$Id:$";
  }

  public String getSource() {
    return "$Revision:$";
  }

  public String getDescription() {
    return "A Component for working with Term Vectors";
  }
}