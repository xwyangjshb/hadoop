/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.om;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.hadoop.hdds.HddsConfigKeys;
import org.apache.hadoop.hdds.client.ContainerBlockID;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationFactor;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationType;
import org.apache.hadoop.hdds.scm.container.common.helpers.AllocatedBlock;
import org.apache.hadoop.hdds.scm.exceptions.SCMException;
import org.apache.hadoop.hdds.scm.exceptions.SCMException.ResultCodes;
import org.apache.hadoop.hdds.scm.protocol.ScmBlockLocationProtocol;
import org.apache.hadoop.hdds.security.token.OzoneBlockTokenIdentifier;
import org.apache.hadoop.hdds.security.x509.SecurityConfig;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.ozone.OzoneConfigKeys;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.om.helpers.OmKeyArgs;
import org.apache.hadoop.ozone.om.helpers.OmKeyInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfo;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.KeyInfo;
import org.apache.hadoop.ozone.security.OzoneBlockTokenSecretManager;
import org.apache.hadoop.security.ssl.KeyStoreTestUtil;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.test.LambdaTestUtils;
import org.apache.hadoop.util.Time;
import org.apache.hadoop.utils.db.RDBStore;
import org.apache.hadoop.utils.db.Table;
import org.apache.hadoop.utils.db.TableConfig;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.Statistics;
import org.rocksdb.StatsLevel;

import static java.nio.charset.StandardCharsets.UTF_8;

import static org.apache.hadoop.hdds.HddsConfigKeys.HDDS_GRPC_BLOCK_TOKEN_ENABLED;
import static org.apache.hadoop.hdds.HddsConfigKeys.OZONE_METADATA_DIRS;
import static org.apache.hadoop.hdds.security.token.OzoneBlockTokenIdentifier.readFieldsProtobuf;

/**
 * Test class for @{@link KeyManagerImpl}.
 * */
public class TestKeyManagerImpl {

  private static KeyManagerImpl keyManager;
  private static ScmBlockLocationProtocol scmBlockLocationProtocol;
  private static OzoneConfiguration conf;
  private static OMMetadataManager metadataManager;
  private static long blockSize = 1000;
  private static final String KEY_NAME = "key1";
  private static final String BUCKET_NAME = "bucket1";
  private static final String VOLUME_NAME = "vol1";
  private static RDBStore rdbStore = null;
  private static Table rdbTable = null;
  private static DBOptions options = null;
  private File metadataDir;
  private KeyInfo keyData;
  private OzoneBlockTokenSecretManager blockTokenSecretManager;
  private static final Text TOKEN_KIND = new Text("HDDS_BLOCK_TOKEN");

  @Rule
  public TemporaryFolder folder = new TemporaryFolder();

  @Before
  public void setUp() throws Exception {
    conf = new OzoneConfiguration();
    conf.setBoolean(OzoneConfigKeys.OZONE_SECURITY_ENABLED_KEY, true);
    metadataDir = folder.newFolder();
    conf.set(OZONE_METADATA_DIRS, metadataDir.toString());
    conf.setBoolean(HDDS_GRPC_BLOCK_TOKEN_ENABLED, true);
    setupMocks();
    blockTokenSecretManager = createBlockTokenSecretManager(conf);
    keyManager = new KeyManagerImpl(scmBlockLocationProtocol, metadataManager,
        conf, "om1", blockTokenSecretManager);

  }

  private OzoneBlockTokenSecretManager createBlockTokenSecretManager(
      OzoneConfiguration config) {
    long expiryTime = config.getTimeDuration(
        HddsConfigKeys.HDDS_BLOCK_TOKEN_EXPIRY_TIME,
        HddsConfigKeys.HDDS_BLOCK_TOKEN_EXPIRY_TIME_DEFAULT,
        TimeUnit.MILLISECONDS);
    // TODO: Pass OM cert serial ID.
    return new OzoneBlockTokenSecretManager(new SecurityConfig(config),
        expiryTime,
        "omCert");
  }

  private void setupMocks() throws Exception {
    scmBlockLocationProtocol = Mockito.mock(ScmBlockLocationProtocol.class);
    metadataManager = Mockito.mock(OMMetadataManager.class);

    Mockito.when(scmBlockLocationProtocol
        .allocateBlock(Mockito.anyLong(), Mockito.any(ReplicationType.class),
            Mockito.any(ReplicationFactor.class), Mockito.anyString()))
        .thenThrow(
            new SCMException("ChillModePrecheck failed for allocateBlock",
                ResultCodes.CHILL_MODE_EXCEPTION));
    setupRocksDb();
    byte[] obj= DFSUtil.string2Bytes(VOLUME_NAME+BUCKET_NAME);
    Mockito.when(metadataManager.getVolumeTable()).thenReturn(rdbTable);
    Mockito.when(metadataManager.getBucketTable()).thenReturn(rdbTable);
    Mockito.when(metadataManager.getOpenKeyTable()).thenReturn(rdbTable);
    Mockito.when(metadataManager.getKeyTable()).thenReturn(rdbTable);
    Mockito.when(metadataManager.getOzoneKeyBytes(VOLUME_NAME, BUCKET_NAME,
        KEY_NAME)).thenReturn(obj);
    Mockito.when(metadataManager.getOpenKeyBytes(Mockito.any(String.class),
        Mockito.any(String.class),
        Mockito.any(String.class),
        Mockito.any(Long.class))).thenReturn(obj);

    Mockito.when(metadataManager.getLock())
        .thenReturn(new OzoneManagerLock(conf));
    Mockito.when(metadataManager.getVolumeKey(VOLUME_NAME))
        .thenReturn(VOLUME_NAME.getBytes(UTF_8));
    Mockito.when(metadataManager.getBucketKey(VOLUME_NAME, BUCKET_NAME))
        .thenReturn(BUCKET_NAME.getBytes(UTF_8));
    Mockito.when(metadataManager.getOpenKeyBytes(VOLUME_NAME, BUCKET_NAME,
        KEY_NAME, 1)).thenReturn(KEY_NAME.getBytes(UTF_8));
  }

  private void setupRocksDb() throws Exception {
    options = new DBOptions();
    options.setCreateIfMissing(true);
    options.setCreateMissingColumnFamilies(true);

    Statistics statistics = new Statistics();
    statistics.setStatsLevel(StatsLevel.ALL);
    options = options.setStatistics(statistics);

    Set<TableConfig> configSet = new HashSet<>();
    for (String name : Arrays
        .asList(DFSUtil.bytes2String(RocksDB.DEFAULT_COLUMN_FAMILY),
            "testTable")) {
      TableConfig newConfig = new TableConfig(name, new ColumnFamilyOptions());
      configSet.add(newConfig);
    }
    keyData = KeyInfo.newBuilder()
        .setKeyName(KEY_NAME)
        .setBucketName(BUCKET_NAME)
        .setVolumeName(VOLUME_NAME)
        .setDataSize(blockSize)
        .setType(ReplicationType.STAND_ALONE)
        .setFactor(ReplicationFactor.ONE)
        .setCreationTime(Time.now())
        .setModificationTime(Time.now())
        .addKeyLocationList(OzoneManagerProtocolProtos.KeyLocationList.
            getDefaultInstance())
        .build();

    rdbStore = new RDBStore(folder.newFolder(), options, configSet);
    rdbTable = rdbStore.getTable("testTable");
    rdbTable.put(VOLUME_NAME.getBytes(UTF_8),
        RandomStringUtils.random(10).getBytes(UTF_8));
    rdbTable.put(BUCKET_NAME.getBytes(UTF_8),
        RandomStringUtils.random(10).getBytes(UTF_8));
    rdbTable.put(KEY_NAME.getBytes(UTF_8), keyData.toByteArray());
  }

  @Test
  public void testAllocateBlockFailureInChillMode() throws Exception {
    OmKeyArgs keyArgs = new OmKeyArgs.Builder().setKeyName(KEY_NAME)
        .setBucketName(BUCKET_NAME)
        .setFactor(ReplicationFactor.ONE)
        .setType(ReplicationType.STAND_ALONE)
        .setVolumeName(VOLUME_NAME).build();
    LambdaTestUtils.intercept(OMException.class,
        "ChillModePrecheck failed for allocateBlock", () -> {
          keyManager.allocateBlock(keyArgs, 1);
        });
  }

  @Test
  public void testOpenKeyFailureInChillMode() throws Exception {
    OmKeyArgs keyArgs = new OmKeyArgs.Builder().setKeyName(KEY_NAME)
        .setBucketName(BUCKET_NAME)
        .setFactor(ReplicationFactor.ONE)
        .setDataSize(1000)
        .setType(ReplicationType.STAND_ALONE)
        .setVolumeName(VOLUME_NAME).build();
    LambdaTestUtils.intercept(OMException.class,
        "ChillModePrecheck failed for allocateBlock", () -> {
          keyManager.openKey(keyArgs);
        });
  }

  @Test
  public void testOpenKeyInSecureCluster() throws Exception {
    AllocatedBlock allocatedBlock = new AllocatedBlock.Builder()
        .setContainerBlockID(new ContainerBlockID(1, 2))
        .setPipeline(null)
        .build();
    KeyPair keyPair = KeyStoreTestUtil.generateKeyPair("RSA");
    blockTokenSecretManager.start(keyPair);

    Mockito.when(scmBlockLocationProtocol.allocateBlock(ArgumentMatchers
            .anyLong(), ArgumentMatchers.any(), ArgumentMatchers.any(),
        ArgumentMatchers.any())).thenReturn(allocatedBlock);

    OmKeyArgs keyArgs = new OmKeyArgs.Builder().setKeyName(KEY_NAME)
        .setBucketName(BUCKET_NAME)
        .setFactor(ReplicationFactor.ONE)
        .setDataSize(1000)
        .setType(ReplicationType.STAND_ALONE)
        .setVolumeName(VOLUME_NAME).build();
    keyManager.openKey(keyArgs).getKeyInfo().getKeyLocationVersions()
        .stream().forEach(
            (c) -> c.getLocationList().stream().forEach(loc -> {
              validateBlockToken(loc.getToken());
            }));

    OmKeyLocationInfo locInfo = keyManager.allocateBlock(keyArgs, 1);
    validateBlockToken(locInfo.getToken());

    OmKeyInfo keyInfo = keyManager.lookupKey(keyArgs);
    keyInfo.getLatestVersionLocations().getLocationList().forEach(loc -> {
      validateBlockToken(loc.getToken());
    });
  }

  private void validateBlockToken(Token<OzoneBlockTokenIdentifier> token) {
    Assert.assertNotNull(token);
    try {
      OzoneBlockTokenIdentifier identifier =
          readFieldsProtobuf(new DataInputStream(
              new ByteArrayInputStream(token.getIdentifier())));
      Assert.assertNotNull(identifier);
      Assert.assertTrue(identifier.getKind().equals(TOKEN_KIND));
    } catch (IOException e) {
      Assert.fail("failed to parse identifier");
    }
  }
}