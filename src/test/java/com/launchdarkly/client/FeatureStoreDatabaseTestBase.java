package com.launchdarkly.client;

import com.launchdarkly.client.TestUtil.DataBuilder;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.util.Arrays;
import java.util.Map;

import static com.launchdarkly.client.VersionedDataKind.FEATURES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

/**
 * Extends FeatureStoreTestBase with tests for feature stores where multiple store instances can
 * use the same underlying data store (i.e. database implementations in general).
 */
@RunWith(Parameterized.class)
public abstract class FeatureStoreDatabaseTestBase<T extends FeatureStore> extends FeatureStoreTestBase<T> {

  @Parameters(name="cached={0}")
  public static Iterable<Boolean> data() {
    return Arrays.asList(new Boolean[] { false, true });
  }
  
  public FeatureStoreDatabaseTestBase(boolean cached) {
    super(cached);
  }
  
  /**
   * Test subclasses should override this method if the feature store class supports a key prefix option
   * for keeping data sets distinct within the same database.
   */
  protected T makeStoreWithPrefix(String prefix) {
    return null;
  }

  /**
   * Test classes should override this to return false if the feature store class does not have a local
   * caching option (e.g. the in-memory store).
   * @return
   */
  protected boolean isCachingSupported() {
    return true;
  }
  
  /**
   * Test classes should override this to clear all data from the underlying database, if it is
   * possible for data to exist there before the feature store is created (i.e. if
   * isUnderlyingDataSharedByAllInstances() returns true).
   */
  protected void clearAllData() {
  }
  
  /**
   * Test classes should override this (and return true) if it is possible to instrument the feature
   * store to execute the specified Runnable during an upsert operation, for concurrent modification tests.
   */
  protected boolean setUpdateHook(T storeUnderTest, Runnable hook) {
    return false;
  }
  
  @Before
  public void setup() {
    assumeTrue(isCachingSupported() || !cached);
    super.setup();
  }
  
  @After
  public void teardown() throws Exception {
    store.close();
  }
  
  @Test
  public void storeNotInitializedBeforeInit() {
    clearAllData();
    assertFalse(store.initialized());
  }
  
  @Test
  public void storeInitializedAfterInit() {
    store.init(new DataBuilder().build());
    assertTrue(store.initialized());
  }
  
  @Test
  public void oneInstanceCanDetectIfAnotherInstanceHasInitializedTheStore() {
    assumeFalse(cached); // caching would cause the inited state to only be detected after the cache has expired
    
    clearAllData();
    T store2 = makeStore();
    
    assertFalse(store.initialized());
    
    store2.init(new DataBuilder().add(FEATURES, feature1).build());
    
    assertTrue(store.initialized());
  }

  @Test
  public void oneInstanceCanDetectIfAnotherInstanceHasInitializedTheStoreEvenIfEmpty() {
    assumeFalse(cached); // caching would cause the inited state to only be detected after the cache has expired
    
    clearAllData();
    T store2 = makeStore();
    
    assertFalse(store.initialized());
    
    store2.init(new DataBuilder().build());
    
    assertTrue(store.initialized());
  }
  
  // The following two tests verify that the update version checking logic works correctly when
  // another client instance is modifying the same data. They will run only if the test class
  // supports setUpdateHook().
  
  @Test
  public void handlesUpsertRaceConditionAgainstExternalClientWithLowerVersion() throws Exception {
    final T store2 = makeStore();
    
    int startVersion = 1;
    final int store2VersionStart = 2;
    final int store2VersionEnd = 4;
    int store1VersionEnd = 10;
    
    final FeatureFlag flag1 = new FeatureFlagBuilder("foo").version(startVersion).build();
    
    Runnable concurrentModifier = new Runnable() {
      int versionCounter = store2VersionStart;
      public void run() {
        if (versionCounter <= store2VersionEnd) {
          FeatureFlag f = new FeatureFlagBuilder(flag1).version(versionCounter).build();
          store2.upsert(FEATURES, f);
          versionCounter++;
        }
      }
    };
    
    try {
      assumeTrue(setUpdateHook(store, concurrentModifier));
      
      store.init(new DataBuilder().add(FEATURES, flag1).build());
      
      FeatureFlag store1End = new FeatureFlagBuilder(flag1).version(store1VersionEnd).build();
      store.upsert(FEATURES, store1End);
      
      FeatureFlag result = store.get(FEATURES, flag1.getKey());
      assertEquals(store1VersionEnd, result.getVersion());
    } finally {
      store2.close();
    }
  }
  
  @Test
  public void handlesUpsertRaceConditionAgainstExternalClientWithHigherVersion() throws Exception {
    final T store2 = makeStore();
    
    int startVersion = 1;
    final int store2Version = 3;
    int store1VersionEnd = 2;
    
    final FeatureFlag flag1 = new FeatureFlagBuilder("foo").version(startVersion).build();
    
    Runnable concurrentModifier = new Runnable() {
      public void run() {
        FeatureFlag f = new FeatureFlagBuilder(flag1).version(store2Version).build();
        store2.upsert(FEATURES, f);
      }
    };
    
    try {
      assumeTrue(setUpdateHook(store, concurrentModifier));
      
      store.init(new DataBuilder().add(FEATURES, flag1).build());
      
      FeatureFlag store1End = new FeatureFlagBuilder(flag1).version(store1VersionEnd).build();
      store.upsert(FEATURES, store1End);
      
      FeatureFlag result = store.get(FEATURES, flag1.getKey());
      assertEquals(store2Version, result.getVersion());
    } finally {
      store2.close();
    }
  }
  
  @Test
  public void storesWithDifferentPrefixAreIndependent() throws Exception {
    assumeFalse(cached);
    
    T store1 = makeStoreWithPrefix("aaa");
    Assume.assumeNotNull(store1);
    T store2 = makeStoreWithPrefix("bbb");
    clearAllData();
    
    try {
      assertFalse(store1.initialized());
      assertFalse(store2.initialized());
      
      FeatureFlag flag1a = new FeatureFlagBuilder("flag-a").version(1).build();
      FeatureFlag flag1b = new FeatureFlagBuilder("flag-b").version(1).build();
      FeatureFlag flag2a = new FeatureFlagBuilder("flag-a").version(2).build();
      FeatureFlag flag2c = new FeatureFlagBuilder("flag-c").version(2).build();
      
      store1.init(new DataBuilder().add(FEATURES, flag1a, flag1b).build());
      assertTrue(store1.initialized());
      assertFalse(store2.initialized());
      
      store2.init(new DataBuilder().add(FEATURES, flag2a, flag2c).build());
      assertTrue(store1.initialized());
      assertTrue(store2.initialized());
      
      Map<String, FeatureFlag> items1 = store1.all(FEATURES);
      Map<String, FeatureFlag> items2 = store2.all(FEATURES);
      assertEquals(2, items1.size());
      assertEquals(2, items2.size());
      assertEquals(flag1a.getVersion(), items1.get(flag1a.getKey()).getVersion());
      assertEquals(flag1b.getVersion(), items1.get(flag1b.getKey()).getVersion());
      assertEquals(flag2a.getVersion(), items2.get(flag2a.getKey()).getVersion());
      assertEquals(flag2c.getVersion(), items2.get(flag2c.getKey()).getVersion());
      
      assertEquals(flag1a.getVersion(), store1.get(FEATURES, flag1a.getKey()).getVersion());
      assertEquals(flag1b.getVersion(), store1.get(FEATURES, flag1b.getKey()).getVersion());
      assertEquals(flag2a.getVersion(), store2.get(FEATURES, flag2a.getKey()).getVersion());
      assertEquals(flag2c.getVersion(), store2.get(FEATURES, flag2c.getKey()).getVersion());
    } finally {
      store1.close();
      store2.close();
    }
  }
}
