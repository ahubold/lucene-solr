package org.apache.solr.cloud;

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

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.CommonsHttpSolrServer;
import org.apache.solr.client.solrj.impl.StreamingUpdateSolrServer;
import org.apache.solr.common.SolrInputDocument;
import org.apache.zookeeper.KeeperException;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;

@Ignore
public class ChaosMonkeyNothingIsSafeTest extends FullSolrCloudTest {
  
  @BeforeClass
  public static void beforeSuperClass() throws Exception {
    
  }
  
  @AfterClass
  public static void afterSuperClass() throws Exception {
    
  }
  
  @Before
  @Override
  public void setUp() throws Exception {
    super.setUp();
    // we expect this time of exception as shards go up and down...
    ignoreException("shard update error ");
    ignoreException("Connection refused");
    ignoreException("interrupted waiting for shard update response");
    ignoreException("org\\.mortbay\\.jetty\\.EofException");
    ignoreException("java\\.lang\\.InterruptedException");
    ignoreException("java\\.nio\\.channels\\.ClosedByInterruptException");
    
    
    // sometimes we cannot get the same port
    ignoreException("java\\.net\\.BindException: Address already in use");
    
    System.setProperty("numShards", Integer.toString(sliceCount));
  }
  
  @Override
  @After
  public void tearDown() throws Exception {
    super.tearDown();
    resetExceptionIgnores();
  }
  
  public ChaosMonkeyNothingIsSafeTest() {
    super();
    shardCount = atLeast(9);
    sliceCount = atLeast(3);
  }
  
  @Override
  public void doTest() throws Exception {
    
    handle.clear();
    handle.put("QTime", SKIPVAL);
    handle.put("timestamp", SKIPVAL);
    
    // we cannot do delete by query
    // as it's not supported for recovery
    //del("*:*");
    
    List<StopableIndexingThread> threads = new ArrayList<StopableIndexingThread>();
    int threadCount = atLeast(1);
    int i = 0;
    for (i = 0; i < threadCount; i++) {
      StopableIndexingThread indexThread = new StopableIndexingThread(i * 50000, true);
      threads.add(indexThread);
      indexThread.start();
    }
    
    FullThrottleStopableIndexingThread ftIndexThread = new FullThrottleStopableIndexingThread(
        ((CommonsHttpSolrServer) clients.get(0)).getBaseURL(), i * 50000, true);
    threads.add(ftIndexThread);
    ftIndexThread.start();
    
    chaosMonkey.startTheMonkey(true);
    
    Thread.sleep(atLeast(15000));
    
    chaosMonkey.stopTheMonkey();
    
    for (StopableIndexingThread indexThread : threads) {
      indexThread.safeStop();
    }
    
    // wait for stop...
    for (StopableIndexingThread indexThread : threads) {
      indexThread.join();
    }
    
    
    // fails will happen...
//    for (StopableIndexingThread indexThread : threads) {
//      assertEquals(0, indexThread.getFails());
//    }
    
    // try and wait for any replications and what not to finish...
    
    // wait until there are no recoveries...
    waitForThingsToLevelOut();

    checkShardConsistency(false, true);
    
    // ensure we have added more than 0 docs
    long cloudClientDocs = cloudClient.query(new SolrQuery("*:*")).getResults().getNumFound();
    assertTrue(cloudClientDocs > 0);
    
    if (VERBOSE) System.out.println("control docs:" + controlClient.query(new SolrQuery("*:*")).getResults().getNumFound() + "\n\n");
  }

  private void waitForThingsToLevelOut() throws KeeperException,
      InterruptedException, Exception, IOException, URISyntaxException {
    int cnt = 0;
    boolean retry = false;
    do {
      waitForRecoveriesToFinish(VERBOSE);
      
      commit();
      
      updateMappingsFromZk(jettys, clients);
      
      Set<String> theShards = shardToClient.keySet();
      String failMessage = null;
      for (String shard : theShards) {
        failMessage = checkShardConsistency(shard, false);
      }
      
      if (failMessage != null) {
        retry  = true;
      }
      cnt++;
      if (cnt > 10) break;
      Thread.sleep(4000);
    } while (retry);
  }
  
  // skip the randoms - they can deadlock...
  protected void indexr(Object... fields) throws Exception {
    SolrInputDocument doc = getDoc(fields);
    indexDoc(doc);
  }

  private SolrInputDocument getDoc(Object... fields) {
    SolrInputDocument doc = new SolrInputDocument();
    addFields(doc, fields);
    addFields(doc, "rnd_b", true);
    return doc;
  }

  class FullThrottleStopableIndexingThread extends StopableIndexingThread {
    private volatile boolean stop = false;

    private StreamingUpdateSolrServer suss;  
    
    public FullThrottleStopableIndexingThread(String serverUrl, int startI, boolean doDeletes) throws MalformedURLException {
      super(startI, doDeletes);
      setDaemon(true);
      suss = new StreamingUpdateSolrServer(serverUrl, 10, 3);
    }
    
    @Override
    public void run() {
      int i = startI;
      int numDeletes = 0;
      int numAdds = 0;

      while (true && !stop) {
        ++i;
        
        if (doDeletes && random.nextBoolean() && deletes.size() > 0) {
          Integer delete = deletes.remove(0);
          try {
            numDeletes++;
            suss.deleteById(Integer.toString(delete));
          } catch (Exception e) {
            System.err.println("REQUEST FAILED:");
            e.printStackTrace();
            fails.incrementAndGet();
          }
        }
        
        try {
          numAdds++;
          SolrInputDocument doc = getDoc(id, i, i1, 50, tlong, 50, t1,
              "to come to the aid of their country.");
          suss.add(doc);
        } catch (Exception e) {
          System.err.println("REQUEST FAILED:");
          e.printStackTrace();
          fails.incrementAndGet();
        }
        
        if (doDeletes && random.nextBoolean()) {
          deletes.add(i);
        }
        
      }
      
      System.err.println("added docs:" + numAdds + " with " + fails + " fails" + " deletes:" + numDeletes);
    }
    
    public void safeStop() {
      stop = true;
      suss.blockUntilFinished();
    }

    public int getFails() {
      return fails.get();
    }
    
  };
  
}