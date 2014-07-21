package com.linkedin.pinot.segments.v1.creator;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.util.Arrays;
import java.util.TreeSet;

import org.apache.log4j.Logger;

import com.linkedin.pinot.index.data.FieldSpec;
import com.linkedin.pinot.raw.record.readers.FileSystemMode;
import com.linkedin.pinot.segments.v1.segment.dictionary.Dictionary;
import com.linkedin.pinot.segments.v1.segment.utils.OffHeapCompressedIntArray;


/**
 * Jul 15, 2014
 * @author Dhaval Patel <dpatel@linkedin.com>
 *
 */
public class IndexCreator {

  private static final Logger logger = Logger.getLogger(IndexCreator.class);

  protected enum IncomingColumnProfile {
    SortedSingleValue,
    UnsortedSingleValue,
    MultiValue;

    public static IncomingColumnProfile getTypeFromDictionaryCreator(DictionaryCreator creator) {
      if (creator.isMultiValued())
        return MultiValue;

      // for now only constructing unsorted single values
      //      if (creator.isSorted())
      //        return SortedSingleValue;

      return UnsortedSingleValue;
    }

    public String getFileNameExtention() {
      if (this == MultiValue)
        return V1Constants.Indexes.MULTI_VALUE_FWD_IDX_FILE_EXTENTION;
      if (this == SortedSingleValue)
        return V1Constants.Indexes.SORTED_FWD_IDX_FILE_EXTENTION;
      return V1Constants.Indexes.UN_SORTED_FWD_IDX_FILE_EXTENTION;
    }
  }

  private IncomingColumnProfile columnProfile;
  private DictionaryCreator dictionaryCreator;
  private FieldSpec spec;
  private File indexDir;
  private File invertedIndexFile;
  private File forwardIndexFile;
  private FileSystemMode mode;
  private int numberOfBits;
  private OffHeapCompressedIntArray unsoretdElementsIntArray;
  private long timeTaken = 0;
  private int counter = 0;

  private int min[];
  private int max[];

  public IndexCreator(File indexDir, DictionaryCreator dictionaryCreator, FieldSpec spec, FileSystemMode mode)
      throws IOException {
    this.spec = spec;
    this.indexDir = indexDir;
    this.dictionaryCreator = dictionaryCreator;
    this.columnProfile = IncomingColumnProfile.getTypeFromDictionaryCreator(dictionaryCreator);
    this.forwardIndexFile = new File(indexDir, spec.getName() + columnProfile.getFileNameExtention());
    this.invertedIndexFile = new File(indexDir, spec.getName() + V1Constants.Indexes.INVERTED_INDEX_EXTENSIONS);
    this.mode = mode;
    this.timeTaken = System.currentTimeMillis();
    init();
  }

  public void init() throws IOException {
    switch (columnProfile) {
      case SortedSingleValue:
        this.min = new int[dictionaryCreator.getDictionarySize()];
        this.max = new int[dictionaryCreator.getDictionarySize()];
        logger.info("column : " + spec.getName() + " column has been dubbed as a sorted column");
        logger.info("will use min/max array to persist this, initialized min/max array of total length : "
            + (dictionaryCreator.getDictionarySize() * 2));
        break;
      case UnsortedSingleValue:
        logger.info("column : " + spec.getName() + " column has been dubbed as a un-sorted single value column");
        numberOfBits = OffHeapCompressedIntArray.getNumOfBits(dictionaryCreator.getDictionarySize());
        unsoretdElementsIntArray =
            new OffHeapCompressedIntArray(dictionaryCreator.getTotalDocs(), numberOfBits, getByteBuffer(
                dictionaryCreator.getTotalDocs(), dictionaryCreator.getTotalDocs()));
        break;
      case MultiValue:
        logger.info("column : " + spec.getName()
            + " column has been dubbed as a multivalued column, would be skipping it for now");
        break;
    }
  }

  private static ByteBuffer getByteBuffer(int numOfElements, int dictionarySize) {
    return ByteBuffer.allocateDirect((int) OffHeapCompressedIntArray.getRequiredBufferSize(numOfElements,
        OffHeapCompressedIntArray.getNumOfBits(dictionarySize)));
  }

  public void add(int e) {
    switch (columnProfile) {
      case SortedSingleValue:
        addSortedSingleValue(e);
        break;
      case UnsortedSingleValue:
        addUnsortedSingleValue(e);
        break;
      case MultiValue:
        break;
    }
    counter++;
  }

  private void addSortedSingleValue(int value) {
    if (min[value] > counter) {
      min[value] = counter;
    }
    if (max[value] < counter) {
      max[value] = counter;
    }
  }

  private void addUnsortedSingleValue(int value) {
    if (value < 0)
      value = (value * -1) + 1;
    unsoretdElementsIntArray.setInt(counter, value);
  }

  private void sealSorted() throws IOException {
    DataOutputStream dataOutputStream = null;
    dataOutputStream = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(forwardIndexFile)));
    for (int i = 0; i < dictionaryCreator.getDictionarySize(); i++) {
      dataOutputStream.writeInt(min[i]);
      dataOutputStream.writeInt(max[i]);
    }
    dataOutputStream.close();
  }

  public long totalTimeTaken() {
    return this.timeTaken;
  }

  public void seal() throws IOException {
    switch (columnProfile) {
      case SortedSingleValue:
        sealSorted();
        break;
      case UnsortedSingleValue:
        DataOutputStream ds = new DataOutputStream(new FileOutputStream(forwardIndexFile));
        unsoretdElementsIntArray.getStorage().rewind();
        int byteSize = OffHeapCompressedIntArray.getRequiredBufferSize(dictionaryCreator.getTotalDocs(), numberOfBits);
        byte[] bytes = new byte[byteSize];
        unsoretdElementsIntArray.getStorage().get(bytes);
        ds.write(bytes);
        ds.close();
        break;
      case MultiValue:
        break;
    }
    this.timeTaken = System.currentTimeMillis() - this.timeTaken;
    logger.info("persisted index for column : " + spec.getName() + " in " + forwardIndexFile.getAbsolutePath());
  }
}