package datawave.microservice.bundler;

import datawave.ingest.mapreduce.job.BulkIngestKey;
import datawave.microservice.bundler.configuration.BundlerProperties;
import datawave.microservice.file.FileScanner;
import datawave.microservice.file.configuration.FileScannerProperties;
import org.apache.accumulo.core.data.Value;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Writable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * BundleFileScanner expects to monitor an output directory from the ingest service. Output file will be a pair of an output sequence file and matching
 * .manifest file which contains the original input path of the file(s) processed. The bundler will combine sequence files into a single file and then push that
 * file to the output directory, moving all referenced files within the manifest from their original hdfs location to a new hdfs location.
 */
@Component
public class BundleFileScanner extends FileScanner {
    private final BundlerProperties bundlerProperties;
    
    protected List<Path> workingManifests = new ArrayList<>();
    private FileSystem inputFs;
    private FileSystem outputFs;
    private Path inputDir;
    private Path workDir;
    private Path bundleOutputDir;
    
    @Autowired
    public BundleFileScanner(Configuration conf, FileScannerProperties fileScannerProperties, BundlerProperties bundlerProperties) {
        super(conf, fileScannerProperties);
        this.bundlerProperties = bundlerProperties;
        
        inputDir = new Path(properties.getInputDir());
        try {
            inputFs = FileSystem.get(inputDir.toUri(), conf);
        } catch (IOException e) {
            log.error("Failed to get FileSystem for: " + inputDir, e);
        }
        workDir = new Path(bundlerProperties.getWorkDir());
        bundleOutputDir = new Path(bundlerProperties.getBundleOutputDir());
        try {
            outputFs = FileSystem.get(bundleOutputDir.toUri(), conf);
        } catch (IOException e) {
            log.error("Failed to get FileSystem for: " + bundleOutputDir, e);
        }
    }
    
    @Override
    protected int addFiles(FileStatus fileStatus) {
        Path manifestPath = new Path(inputDir, fileStatus.getPath().getName() + ".manifest");
        FileStatus manifestStatus = null;
        try {
            manifestStatus = inputFs.getFileStatus(manifestPath);
        } catch (IOException e) {
            log.error("Failed to get manifest status: " + manifestPath, e);
        }
        
        if (manifestStatus == null) {
            log.warn("No matching manifest file for : " + fileStatus.getPath() + " expected: " + manifestPath + " to exist.");
            return 0;
        }
        
        // add the manifest
        workingManifests.add(manifestStatus.getPath());
        
        return super.addFiles(fileStatus);
    }
    
    /**
     * Do not process .manifest files
     *
     * @param fileStatus
     * @return
     */
    @Override
    protected boolean preCheck(FileStatus fileStatus) {
        return !fileStatus.getPath().toString().endsWith(".manifest");
    }
    
    @Override
    protected void process() throws IOException {
        try {
            log.info("combining");
            String uuid = combine(workDir, workingFiles);
            log.info("moving");
            move(outputFs, workDir, bundleOutputDir, uuid);
            log.info("cleanup");
            cleanup(inputFs, workingFiles, workingManifests);
        } catch (Throwable e) {
            throw e;
        } finally {
            // cleanup working files
            workingFiles.clear();
            workingManifests.clear();
        }
    }
    
    /**
     * Merge up the working files into a single file in the workingDir
     *
     * @param workingDir
     * @param workingFiles
     * @return uuid of the new file
     */
    private String combine(Path workingDir, List<Path> workingFiles) {
        String uuid = UUID.randomUUID().toString();
        Path combinedFile = new Path(workingDir, uuid);
        
        // create the output files
        SequenceFile.Writer writer = null;
        try {
            writer = SequenceFile.createWriter(conf, SequenceFile.Writer.file(combinedFile), SequenceFile.Writer.keyClass(BulkIngestKey.class),
                            SequenceFile.Writer.valueClass(Value.class));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create sequence file", e);
        }
        
        for (Path workingFile : workingFiles) {
            SequenceFile.Reader reader = null;
            try {
                reader = new SequenceFile.Reader(conf, SequenceFile.Reader.file(workingFile));
                Class keyClass = reader.getKeyClass();
                Class valueClass = reader.getValueClass();
                Writable key = (Writable) keyClass.newInstance();
                Writable value = (Writable) valueClass.newInstance();
                while (reader.next(key, value)) {
                    writer.append(key, value);
                }
            } catch (IOException | IllegalAccessException | InstantiationException e) {
                throw new RuntimeException("Failed to merge sequence file: " + workingFile, e);
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException e) {
                        // just log it
                        log.warn("failed to close reader for " + workingFile, e);
                    }
                }
            }
        }
        
        try {
            writer.close();
        } catch (IOException e) {
            throw new RuntimeException("Failed to close sequence file: " + combinedFile, e);
        }
        
        return uuid;
    }
    
    /**
     * Move the uuid file from the workingDir to the ouputDir
     *
     * @param workingDir
     *            a local fs
     * @param outputDir
     *            a
     * @param uuid
     */
    private void move(FileSystem dstFs, Path workingDir, Path outputDir, String uuid) {
        try {
            // ensure the dest dir exists
            if (!dstFs.exists(outputDir)) {
                if (!dstFs.mkdirs(outputDir)) {
                    throw new IOException("Failed to create dest dir: " + outputDir);
                }
            }
            
            dstFs.moveFromLocalFile(new Path(workingDir, uuid), new Path(outputDir, uuid));
        } catch (IOException e) {
            throw new RuntimeException("Failed to move combined files", e);
        }
        
    }
    
    /**
     * Move original files to output Dir
     *
     * @param inputFs
     * @param workingFiles
     * @param workingManifests
     */
    private void cleanup(FileSystem inputFs, List<Path> workingFiles, List<Path> workingManifests) {
        // cleanup original input files that were combined
        for (Path workingFile : workingFiles) {
            try {
                inputFs.delete(workingFile, false);
            } catch (IOException e) {
                // TODO
                e.printStackTrace();
            }
        }
        
        // read each manifest file and move it to the output location
        for (Path workingManifest : workingManifests) {
            log.debug("Processing manifest: " + workingManifest);
            try {
                FSDataInputStream inputStream = inputFs.open(workingManifest);
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
                String line = null;
                
                while ((line = reader.readLine()) != null) {
                    String[] splits = line.split("\t");
                    log.info("file accounting: " + line);
                    String origFile = splits[1];
                    
                    Path origFilePath = new Path(origFile);
                    Path targetFilePath = new Path(origFile.replace("/flagged/", "/loaded/"));
                    Path targetDir = targetFilePath.getParent();
                    
                    // use the FS from wherever the original file came from
                    FileSystem destFs = FileSystem.get(origFilePath.toUri(), conf);
                    
                    if (!destFs.exists(targetDir)) {
                        if (!destFs.mkdirs(targetDir)) {
                            log.warn("Failed to create " + targetDir);
                            throw new IOException("Failed to create " + targetDir);
                        }
                    }
                    
                    if (!destFs.rename(origFilePath, targetFilePath)) {
                        log.warn("Failed to move orig file: " + origFilePath + " to " + targetFilePath);
                    }
                    
                    // Path targetPath = new Path(bundlerProperties.getManifestOutputDir());
                    // if (bundlerProperties.isPreservePath()) {
                    // int startIndex = origFilePath.toString().indexOf(bundlerProperties.getManifestPathRoot());
                    // int endIndex = startIndex + bundlerProperties.getManifestPathRoot().length();
                    //
                    // // only adjust pathing if the root path exists in the original
                    // if (startIndex > -1) {
                    // String preservePath = workingManifest.toString().substring(startIndex, endIndex + 1);
                    // targetPath = new Path(targetPath, preservePath);
                    // }
                    // }
                    //
                    // // use the FS from wherever the original file came from
                    // FileSystem destFs = FileSystem.get(origFilePath.toUri(), conf);
                    //
                    // // make sure target path exists or can be created
                    // if (!destFs.exists(targetPath)) {
                    // if (!destFs.mkdirs(targetPath)) {
                    // log.warn("Failed to create " + targetPath);
                    // throw new IOException("Failed to create " + targetPath);
                    // }
                    // }
                    //
                    // // move the file
                    // if (!destFs.rename(origFilePath, targetPath)) {
                    // log.warn("Failed to move orig file: " + origFilePath + " to " + targetPath);
                    // }
                }
                
                // now cleanup the manifest file
                inputFs.delete(workingManifest, false);
            } catch (UnsupportedEncodingException e) {
                // TODO
                e.printStackTrace();
            } catch (IOException e) {
                // TODO
                e.printStackTrace();
            }
        }
    }
}