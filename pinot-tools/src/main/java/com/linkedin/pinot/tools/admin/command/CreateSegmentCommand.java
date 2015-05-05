/**
 * Copyright (C) 2014-2015 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.pinot.tools.admin.command;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.codehaus.jackson.map.ObjectMapper;
import org.kohsuke.args4j.Option;

import com.linkedin.pinot.common.data.Schema;
import com.linkedin.pinot.core.data.readers.FileFormat;
import com.linkedin.pinot.core.indexsegment.generator.SegmentGeneratorConfig;
import com.linkedin.pinot.core.indexsegment.generator.SegmentVersion;
import com.linkedin.pinot.core.indexsegment.utils.AvroUtils;
import com.linkedin.pinot.core.segment.creator.impl.SegmentIndexCreationDriverImpl;

/**
 * Class to implement CreateSegment command.
 *
 * @author Mayank Shrivastava <mshrivastava@linkedin.com>
 */
public class CreateSegmentCommand extends AbstractBaseCommand implements Command {
  @Option(name="-dataDir", required=true, metaVar="<string>", usage="Directory containing the data.")
  private String _dataDir;

  @Option(name="-resourceName", required=true, metaVar="<string>", usage="Name of the resource.")
  private String _resourceName;

  @Option(name="-tableName", required=true, metaVar="<string>", usage="Name of the table.")
  private String _tableName;

  @Option(name="-segmentName", required=true, metaVar="<string>", usage="Name of the segment.")
  private String _segmentName;

  @Option(name="-schemaFile", required=false, metaVar="<string>", usage="File containing schema for data.")
  private String _schemaFile;

  @Option(name="-outDir", required=true, metaVar="<string>", usage="Name of output directory.")
  private String _outDir;

  @Option(name="-overwrite", required=false, metaVar="<string>", usage="Overwrite existing output directory.")
  private boolean _overwrite = false;

  @Option(name="-help", required=false, help=true, usage="Print this message.")
  boolean _help = false;

  public boolean getHelp() {
    return _help;
  }

  public CreateSegmentCommand setSchemaFile(String schemaFile) {
    _schemaFile = schemaFile;
    return this;
  }

  public CreateSegmentCommand setDataDir(String dataDir) {
    _dataDir = dataDir;
    return this;
  }

  public CreateSegmentCommand setResourceName(String resourceName) {
    _resourceName = resourceName;
    return this;
  }

  public CreateSegmentCommand setTableName(String tableName) {
    _tableName = tableName;
    return this;
  }

  public CreateSegmentCommand setSegmentName(String segmentName) {
    _segmentName = segmentName;
    return this;
  }

  public CreateSegmentCommand setOutputDir(String outDir) {
    _outDir = outDir;
    return this;
  }

  public CreateSegmentCommand setOverwrite(boolean overwrite) {
    _overwrite = overwrite;
    return this;
  }

  @Override
  public String toString() {
    return ("CreateSegmentCommand -schemaFile " + _schemaFile + " -dataDir " + _dataDir +
        " -resourceName " + _resourceName + " -tableName " + _tableName + " -segmentName " +
        _segmentName + " -outDir " + _outDir);
  }

  @Override
  public final String getName() {
    return "CreateSegment";
  }

  @Override
  public void cleanup() {
  }

  @Override
  public boolean execute() throws Exception {
    File dir = new File(_dataDir);
    if (!dir.exists() || !dir.isDirectory()) {
      throw new RuntimeException("Data directory " + _dataDir + " not found.");
    }

    File [] files = dir.listFiles(new FilenameFilter() {
      @Override
      public boolean accept(File dir, String name) {
        return name.endsWith("avro");
      }
    });

    if ((files == null) || (files.length == 0)) {
      throw new RuntimeException("Data directory " + _dataDir + " does not contain AVRO files.");
    }

    // Make sure output directory does not already exist, or can be overwritten.
    File odir = new File (_outDir);
    if (odir.exists()) {
      if (!_overwrite) {
        throw new IOException("Error: Output directory already exists.");
      } else {
        FileUtils.deleteDirectory(odir);
      }
    }

    final Schema schema;
    if (_schemaFile != null) {
      File schemaFile = new File(_schemaFile);
      schema = new ObjectMapper().readValue(schemaFile, Schema.class);
    } else {
      schema = AvroUtils.extractSchemaFromAvro(files[0]);
    }


    ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    int cnt = 0;
    for (final File file : files) {
      final int segCnt = cnt;

      executor.execute(new Runnable() {
        @Override
        public void run() {
          try {

            SegmentGeneratorConfig config = new SegmentGeneratorConfig(schema);
            config.setInputFileFormat(FileFormat.AVRO);
            config.setSegmentVersion(SegmentVersion.v1);

            config.setIndexOutputDir(_outDir);
            config.setResourceName(_resourceName);
            config.setTableName(_tableName);

            config.setTimeColumnName(schema.getTimeColumnName());
            config.setTimeUnitForSegment(schema.getTimeSpec().getIncomingGranularitySpec().getTimeType());
            config.setInputFilePath(file.getAbsolutePath());
            config.setSegmentName(_segmentName + "_" + segCnt);

            final SegmentIndexCreationDriverImpl driver = new SegmentIndexCreationDriverImpl();
            driver.init(config);
            driver.build();

          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }
      });

      cnt += 1;
    }

    executor.shutdown();
    executor.awaitTermination(1, TimeUnit.HOURS);

    return true;
  }
}
