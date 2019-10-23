package com.uddernetworks.mspaint.code.languages;

import com.uddernetworks.mspaint.code.ImageClass;
import com.uddernetworks.mspaint.util.IDEFileUtils;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class SourceMover {

    private static final Logger LOGGER = LoggerFactory.getLogger(SourceMover.class);

    private File input;

    private File destination;

    private List<File> addedFiles;

    public SourceMover(File input) {
        this.input = input;
    }

    /**
     * Moves the image files and other resources into a temporary directory, with all image files being read into their
     * "hard", or normal text values to be executed.
     *
     * @param imageClasses The {@link ImageClass}es to convert into normal files. All other files in the input directory
     *                     will be moved as-is.
     */
    public void moveToHardTemp(List<ImageClass> imageClasses) {
        try {
            System.out.println(111);
            addedFiles.clear();
            System.out.println(222);
            destination = new File(System.getProperty("java.io.tmpdir"), "MSPaintIDE_" + ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE));
            System.out.println("destination = " + destination);
            destination.mkdirs();
            System.out.println(333);
            var genSrc = new File(destination, "src");
            System.out.println(444);
            genSrc.mkdirs();
            System.out.println(555);

            var imageClassMap = imageClasses.stream().collect(Collectors.toMap(ImageClass::getInputImage, imageClass -> imageClass));
            IDEFileUtils.getFilesFromDirectory(input, (String[]) null).forEach(file -> {

                var relative = input.toURI().relativize(file.toURI());

                Optional.ofNullable(imageClassMap.get(file)).ifPresentOrElse(imageClass -> {
                    var absoluteOutput = new File(genSrc, relative.getPath().replaceAll("\\.png$", ""));
                    try {
                        FileUtils.write(absoluteOutput, imageClass.getText(), Charset.defaultCharset());
                        addedFiles.add(absoluteOutput);
                    } catch (IOException e) {
                        LOGGER.error("An error occurred while writing to the temp file {}", absoluteOutput.getAbsolutePath());
                    }
                }, () -> {
                    var to = new File(genSrc, relative.getPath());
                    try {
                        Files.copy(file.toPath(), to.toPath());
                        addedFiles.add(to);
                    } catch (IOException e) {
                        LOGGER.error("An error occured while writing to the temp file {}", to.getAbsolutePath());
                    }
                });
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public File getInput() {
        return input;
    }

    public File getDestination() {
        return destination;
    }

    public List<File> getAddedFiles() {
        return addedFiles;
    }
}
