package nisargpatel.deadreckoning.filewriting;

import android.os.Environment;
import android.text.format.Time;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import nisargpatel.deadreckoning.extra.ExtraFunctions;

/**
 * Creates and writes to timestamped text files on external storage.
 * Files are grouped under a named folder and each write appends a line.
 * Multiple named files can be managed simultaneously via a {@code filename → File} map.
 */
public class DataFileWriter {

    private BufferedWriter bufferedWriter;
    private String folderName;
    private HashMap<String, File> files;

    public DataFileWriter() {
        files = new HashMap<>();
        folderName = null;
    }

    /**
     * Creates a writer that immediately creates all named files with their column headings.
     *
     * @param folderName   Subdirectory under external storage root.
     * @param fileNames    Logical names used as keys and as filename prefixes.
     * @param fileHeadings Header line written to each file on creation.
     * @throws IOException if a file cannot be created.
     */
    public DataFileWriter(String folderName, ArrayList<String> fileNames, ArrayList<String> fileHeadings) throws IOException {
        this();
        this.folderName = folderName;
        createFiles(fileNames, fileHeadings);
    }

    /**
     * Array-overload of {@link #DataFileWriter(String, ArrayList, ArrayList)}.
     *
     * @param fileNames    Array of logical file names.
     * @param fileHeadings Array of header lines, parallel to {@code fileNames}.
     */
    public DataFileWriter(String folderName, String[] fileNames, String[] fileHeadings) throws IOException {
        this();
        this.folderName = folderName;
        createFiles(ExtraFunctions.arrayToList(fileNames), ExtraFunctions.arrayToList(fileHeadings));
    }

    private File getFolder() {
        File folder = new File(Environment.getExternalStorageDirectory(), folderName);

        //create the folder is it doesn't exist
        createFolder(folder);

        return folder;
    }

    private void createFolder(File folder) {
        if (!folder.exists()) {
            if (folder.mkdirs()) {
                Log.d("data_files", "folder created: " + folder.getName());
            }
        }
    }

    /**
     * Creates a single timestamped file and registers it under {@code fileName}.
     *
     * @param fileName    Logical key and filename prefix.
     * @param fileHeading First line written to the new file.
     * @throws IOException if the file cannot be created.
     */
    public void createFile(String fileName, String fileHeading) throws IOException {

        String folderPath = getFolder().getPath();

        String timestampedFileName = getTimestampedFileName(fileName);
        File dataFile = new File(folderPath, timestampedFileName);

        if (dataFile.createNewFile()) {
            Log.d("data_files", "file created: " + timestampedFileName);
        }

        //storing the data file inside the HashMap
        files.put(fileName, dataFile);

        writeFileHeading(fileName, fileHeading);

    }

    private void writeFileHeading(String fileName, String fileHeading) {
        writeToFile(fileName, fileHeading);
    }

    public void createFiles(ArrayList<String> fileNames, ArrayList<String> fileHeadings) throws IOException {
        for (int i = 0; i < fileNames.size(); i++) {
            createFile(fileNames.get(i), fileHeadings.get(i));
        }
    }

    private String getTimestampedFileName(String fileName) {

        Time today = new Time(Time.getCurrentTimezone());
        today.setToNow();

        String date = today.year + "-" + (today.month + 1) + "-" + today.monthDay;
        String currentTime = today.format("%H-%M-%S");

        return fileName + "_" + date + "_" + currentTime + ".txt";

    }

    //overridden write methods
    /**
     * Appends a semicolon-separated row of float values followed by a newline.
     *
     * @param fileName Logical file key registered via {@link #createFile}.
     * @param values   Values to write on one line.
     */
    public void writeToFile(String fileName, ArrayList<Float> values) {
        File file = files.get(fileName);
        try {
            bufferedWriter = new BufferedWriter(new FileWriter(file, true));
            for (float value : values)
                bufferedWriter.write(value + ";");
            bufferedWriter.write(System.getProperty("line.separator")); //create a line break
            bufferedWriter.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Appends a string line followed by a platform newline.
     *
     * @param fileName Logical file key.
     * @param line     Text to write.
     */
    public void writeToFile(String fileName, String line) {
        File file = files.get(fileName);
        try {
            bufferedWriter = new BufferedWriter(new FileWriter(file, true));
            bufferedWriter.write(line);
            bufferedWriter.write(System.getProperty("line.separator")); //create a line break
            bufferedWriter.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** Varargs overload: packs floats into a list and delegates to {@link #writeToFile(String, ArrayList)}. */
    public void writeToFile(String fileName, float... args) {
        ArrayList<Float> values = new ArrayList<>();
        for (float arg : args)
            values.add(arg);
        writeToFile(fileName, values);
    }

}
