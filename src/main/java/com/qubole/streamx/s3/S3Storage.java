/**
 * Copyright 2015 Qubole Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 **/

package com.qubole.streamx.s3;

import com.qubole.streamx.s3.wal.DummyWAL;
import io.confluent.connect.hdfs.HdfsSinkConnectorConfig;
import io.confluent.connect.hdfs.storage.Storage;
import io.confluent.connect.hdfs.wal.WAL;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.velocity.exception.MethodInvocationException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;

public class S3Storage implements Storage {

  private final FileSystem fs;
  private final Configuration hadoopConf;
  private final String url;
  private final HdfsSinkConnectorConfig config;

  public S3Storage(Configuration hadoopConf, HdfsSinkConnectorConfig config, String url) throws IOException {
    fs = FileSystem.newInstance(URI.create(url), hadoopConf);
    this.hadoopConf = hadoopConf;
    this.url = url;
    this.config = config;
  }

  @Override
  public FileStatus[] listStatus(String path, PathFilter filter) throws IOException {
    return fs.listStatus(new Path(path), filter);
  }

  @Override
  public FileStatus[] listStatus(String path) throws IOException {
    return fs.listStatus(new Path(path));
  }

  @Override
  public void append(String filename, Object object) throws IOException {
  }

  @Override
  public boolean mkdirs(String filename) throws IOException {
    return fs.mkdirs(new Path(filename));
  }

  @Override
  public boolean exists(String filename) throws IOException {
    boolean readSucceeded = true;
    try {
      //s3 is read-after-write consistency. We use read to check if file exists, rather listing
      BufferedReader br = new BufferedReader(new InputStreamReader(fs.open(new Path(filename))));
    } catch(IOException e){
      readSucceeded = false;
    }
    if (readSucceeded)
      return true;

    try {
      //read fails for directories, hence we do listing as fallback
      return fs.exists(new Path(filename));
    } catch (IOException e) {
      return false;
    }
  }

  @Override
  public void commit(String tempFile, String committedFile) throws IOException {
    renameFile(tempFile, committedFile);
  }

  @Override
  public void delete(String filename) throws IOException {
    fs.delete(new Path(filename), true);
  }

  @Override
  public void close() throws IOException {
    if (fs != null) {
      fs.close();
    }
  }

  @Override
  public WAL wal(String topicsDir, TopicPartition topicPart) {
    try {
      Class<? extends WAL> walClass = (Class<? extends WAL>) Class
          .forName(config.getString(S3SinkConnectorConfig.WAL_CLASS_CONFIG));
      if (walClass.equals(DummyWAL.class)) {
        return new DummyWAL();
      }
      else {
        Constructor<? extends WAL> ctor = walClass.getConstructor(String.class, TopicPartition.class, Storage.class, HdfsSinkConnectorConfig.class);
        return ctor.newInstance(topicsDir, topicPart, this, config);
      }
    } catch (ClassNotFoundException | NoSuchMethodException | InvocationTargetException | MethodInvocationException | InstantiationException | IllegalAccessException e) {
      throw new ConnectException(e);
    }
  }

  @Override
  public Configuration conf() {
        return hadoopConf;
    }

  @Override
  public String url() {
        return url;
    }

  private void renameFile(String sourcePath, String targetPath) throws IOException {
    if (sourcePath.equals(targetPath)) {
      return;
    }
    final Path srcPath = new Path(sourcePath);
    final Path dstPath = new Path(targetPath);

    fs.rename(srcPath, dstPath);
  }
}
